package com.dacweb.player.audio

import android.content.Context
import android.net.Uri
import com.dacweb.player.log.AppLog
import java.io.File

/**
 * FFmpeg 解码器的薄封装（native/ffaudio.c）。
 *
 * 存在的理由：MediaExtractor/MediaCodec 在真机上对 24bit 文件给出的字段互相矛盾
 * （pcm-encoding 说 16bit、bits-per-sample 说 24、readSampleData 一次吐 32KB），
 * 宿主侧只能靠猜"每样本几个字节"。FFmpeg 解析容器时直接给 bits_per_raw_sample，
 * 再由 libswresample 输出指定宽度（16bit / 24bit 3 字节 / 32bit），宿主不再判断。
 *
 * 只支持本地文件（file: / content: / 绝对路径）；网络曲目继续走 MediaExtractor。
 */
object FfAudio {

    private const val TAG = "FfAudio"
    private var loaded = false

    fun available(): Boolean = runCatching {
        if (!loaded) {
            // 顺序不能乱：avformat 依赖 avcodec，avcodec 依赖 avutil，ffaudio 依赖全部
            System.loadLibrary("avutil")
            System.loadLibrary("swresample")
            System.loadLibrary("avcodec")
            System.loadLibrary("avformat")
            System.loadLibrary("ffaudio")
            loaded = true
        }
        true
    }.getOrElse {
        AppLog.w(TAG, "FFmpeg 不可用: ${it.message}")
        false
    }

    /** 一次打开的解码会话。 */
    class Track(
        val handle: Long,
        /** 源采样率 / 声道 / 真实位深 */
        val srcRate: Int,
        val srcChannels: Int,
        val srcBits: Int,
        /** 输出（swr 之后）采样率 / 声道 / 每样本字节数 */
        val outRate: Int,
        val outChannels: Int,
        val outBytes: Int,
        val durationMs: Long,
        val sourceFloat: Boolean
    )

    private external fun nativeOpen(path: String?, fd: Int, wantRate: Int, wantBps: Int): Long
    private external fun nativeInfo(h: Long): IntArray
    private external fun nativeRead(h: Long, buf: ByteArray, cap: Int): Int
    private external fun nativeSeek(h: Long, ms: Long): Int
    private external fun nativeSkipTo(h: Long, ms: Long): Int
    private external fun nativeClose(h: Long)
    private external fun nativeLastError(h: Long): String

    /**
     * [wantBps] 传 0 表示"按源位深自动"（24bit → 3 字节），传 2/3/4 指定输出宽度。
     * [wantRate] 传 0 表示保持源采样率。
     */
    fun open(ctx: Context, url: String, wantRate: Int, wantBps: Int): Track? {
        if (!available()) return null
        var pfd: android.os.ParcelFileDescriptor? = null
        val u = Uri.parse(url)
        var path: String? = null
        var fd = -1
        try {
            when {
                u.scheme == "content" -> {
                    pfd = ctx.contentResolver.openFileDescriptor(u, "r")
                    fd = pfd?.fd ?: -1
                    if (fd < 0) return null
                }
                u.scheme == "file" -> path = u.path
                u.scheme == null -> path = url
                else -> return null   // http(s) 不归 FFmpeg 管
            }
            if (path != null && !File(path).isFile) return null
            val h = nativeOpen(path, fd, wantRate, wantBps)
            // fd 已经在 native 里 dup 过了，这里可以关掉 PFD
            pfd?.close()
            pfd = null
            if (h == 0L) {
                AppLog.w(TAG, "open 失败: $url")
                return null
            }
            val v = nativeInfo(h)
            return Track(
                handle = h,
                srcRate = v.getOrElse(0) { 0 },
                srcChannels = v.getOrElse(1) { 0 },
                srcBits = v.getOrElse(2) { 0 },
                outRate = v.getOrElse(3) { 0 },
                outChannels = v.getOrElse(4) { 0 },
                outBytes = v.getOrElse(5) { 0 },
                durationMs = v.getOrElse(6) { 0 }.toLong(),
                sourceFloat = v.getOrElse(7) { 0 } == 1
            )
        } catch (e: Exception) {
            AppLog.w(TAG, "open 异常: ${e.message}")
            return null
        } finally {
            runCatching { pfd?.close() }
        }
    }

    fun lastError(t: Track?): String = runCatching {
        if (t == null) "" else nativeLastError(t.handle)
    }.getOrDefault("")

    /** >0 读到的字节数；0 放完；<0 出错。 */
    fun read(t: Track, buf: ByteArray, cap: Int = buf.size): Int =
        runCatching { nativeRead(t.handle, buf, cap) }.getOrDefault(-1)

    fun seek(t: Track, ms: Long): Boolean =
        runCatching { nativeSeek(t.handle, ms) == 0 }.getOrDefault(false)

    /**
     * 跳过到 [ms]：用解码丢弃实现，不依赖 av_seek_frame
     * （后者对自定义 AVIO / 尺寸未知的 WAV 会卡死或落到数据块外）。
     * 返回 0 = 到位；1 = 已到文件末尾；<0 = 出错。
     */
    fun skipTo(t: Track, ms: Long): Int = runCatching { nativeSkipTo(t.handle, ms) }.getOrDefault(-1)

    fun close(t: Track?) {
        if (t == null) return
        runCatching { nativeClose(t.handle) }
    }

    /** 读出来的标签 + 内嵌封面。空字段是空串，没有封面时 [cover] 为 null。 */
    class Tags(
        val title: String,
        val artist: String,
        val album: String,
        val albumArtist: String,
        val date: String,
        val track: String,
        val disc: String,
        val genre: String,
        val comment: String,
        val encoder: String,
        val cover: ByteArray?,
        val coverMime: String
    ) {
        val isEmpty: Boolean
            get() = title.isEmpty() && artist.isEmpty() && album.isEmpty() && cover == null
    }

    private external fun nativeTags(path: String?, fd: Int): Array<Any?>?

    /**
     * 只读标签，不建解码器——扫描本地库时逐文件调用成本很低。
     * 支持 FFmpeg 能解复用的所有容器（flac/mp3/m4a/wav/aiff/dsf/wma/dts/ogg…），
     * 对 MediaMetadataRetriever 读不出东西的冷门格式（.dsf/.wma/.aiff）是唯一可用的途径。
     */
    fun tags(ctx: Context, url: String): Tags? {
        if (!available()) return null
        var pfd: android.os.ParcelFileDescriptor? = null
        val u = Uri.parse(url)
        var path: String? = null
        var fd = -1
        try {
            when {
                u.scheme == "content" -> {
                    pfd = ctx.contentResolver.openFileDescriptor(u, "r")
                    fd = pfd?.fd ?: -1
                    if (fd < 0) return null
                }
                u.scheme == "file" -> path = u.path
                u.scheme == null -> path = url
                else -> return null
            }
            if (path != null && !File(path).isFile) return null
            val raw = nativeTags(path, fd) ?: return null
            pfd?.close()
            pfd = null
            @Suppress("UNCHECKED_CAST")
            val fields = (raw.getOrNull(0) as? Array<String?>) ?: emptyArray()
            fun at(i: Int) = fields.getOrNull(i).orEmpty()
            val cover = raw.getOrNull(1) as? ByteArray
            if (cover != null) AppLog.i(TAG, "tags: ${at(0)} / ${at(1)} cover=${cover.size}B")
            return Tags(
                title = at(0), artist = at(1), album = at(2), albumArtist = at(3), date = at(4),
                track = at(5), disc = at(6), genre = at(7), comment = at(8), encoder = at(9),
                cover = cover, coverMime = (raw.getOrNull(2) as? String).orEmpty()
            )
        } catch (e: Exception) {
            AppLog.w(TAG, "tags 异常: ${e.message}")
            return null
        } finally {
            runCatching { pfd?.close() }
        }
    }
}
