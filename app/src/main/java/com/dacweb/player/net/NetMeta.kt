package com.dacweb.player.net

import android.content.Context
import android.net.Uri
import com.dacweb.player.audio.FfAudio
import com.dacweb.player.log.AppLog
import com.dacweb.player.model.Track
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 网络文件（SMB/FTP/SFTP/WebDAV）的标签与音频规格。
 *
 * 列表里**不**为每个文件发请求（用户明确要求只显示文件名）；只有点开某一首准备播放时，
 * 才读它的头部若干字节交给 FFmpeg 解析容器：标签（标题/歌手/专辑/封面）+ 规格
 * （采样率/位深/声道/码率/时长）。结果按 URI 缓存在内存里，同一首反复播不重复读。
 */
object NetMeta {

    private const val TAG = "NetMeta"

    /** 头部最多读 2 MiB：FLAC 的 STREAMINFO/VORBIS_COMMENT、MP3 的 ID3v2、MP4 的 moov 都在这个范围内。 */
    private const val HEAD_CAP = 2L * 1024 * 1024

    data class Meta(
        val title: String,
        val artist: String,
        val album: String,
        /** "24bit · 96kHz · 2ch · 1411kb/s"，缺失字段自动省略 */
        val spec: String,
        val durationMs: Long,
        /** 封面已落盘到 cacheDir，Coil 可直接按 file:// 显示 */
        val cover: File?
    )

    private val cache = ConcurrentHashMap<String, Any>()   // 值: Meta 或 NONE
    private val NONE = Any()

    /** 同一首可能被多处同时请求（播放页 + 迷你条 + 列表），按键加锁，避免重复读服务器。 */
    private val keyLocks = ConcurrentHashMap<String, Any>()

    fun probe(ctx: Context, uri: Uri): Meta? {
        val key = uri.toString()
        cache[key]?.let { return if (it === NONE) null else it as Meta }
        val lock = keyLocks.getOrPut(key) { Any() }
        synchronized(lock) {
            cache[key]?.let { return if (it === NONE) null else it as Meta }
            val m = runCatching { doProbe(ctx, uri) }.getOrNull()
            cache[key] = m ?: NONE
            return m
        }
    }

    /** 播放页左上角那行规格；网络文件读不到就返回空串。 */
    fun specOf(ctx: Context, uri: Uri): String = probe(ctx, uri)?.spec.orEmpty()

    /** 把标签补进曲目：读不到时原样返回，不影响播放。 */
    fun enrich(ctx: Context, track: Track): Track {
        if (!track.localUri.startsWith("dacnet-")) return track
        val m = probe(ctx, Uri.parse(track.localUri)) ?: return track
        return track.copy(
            name = m.title.ifBlank { track.name },
            artist = m.artist.ifBlank { track.artist },
            album = m.album.ifBlank { track.album },
            cover = m.cover?.let { "file://${it.absolutePath}" } ?: track.cover
        )
    }

    private fun doProbe(ctx: Context, uri: Uri): Meta? {
        val client = NetStore.client() ?: return null
        val path = uri.path.orEmpty().trim('/')
        val reader = client.open(path)
        try {
            val size = reader.size
            if (size <= 0) return null
            val cap = minOf(size, HEAD_CAP).toInt()
            if (cap < 64) return null
            val buf = ByteArray(cap)
            var off = 0
            while (off < cap) {
                val n = reader.readAt(off.toLong(), buf, off, cap - off)
                if (n <= 0) break
                off += n
            }
            if (off < 64) return null
            val headBytes = if (off == cap) buf else buf.copyOf(off)
            val head = FfAudio.probeMem(headBytes) ?: return null
            // 容器自报的 bit_rate 只是头部估算；用「文件大小 ÷ 时长」更接近真实平均码率
            val kbps = if (head.durationMs > 0 && size > 0) {
                (size * 8 / head.durationMs).toInt()
            } else head.kbps
            // 封面直接交给 FFmpeg（attached_pic / covr / APIC 它都认），落盘后由 Coil 显示
            val rawCover = head.cover
                ?.takeIf { looksLikeImage(it) }
                ?.let { it to if (looksLikePng(it)) "image/png" else "image/jpeg" }
            val cover = rawCover?.let { saveCover(ctx, path, it.first, it.second) }
            AppLog.i(
                TAG,
                "probe ${path.substringAfterLast('/')} rate=${head.rateHz} bits=${head.bits} " +
                    "ch=${head.channels} kbps=${head.kbps} cover=${head.cover?.size ?: 0}"
            )
            return Meta(
                title = head.title,
                artist = head.artist,
                album = head.album,
                spec = specText(uri, head, kbps),
                durationMs = head.durationMs,
                cover = cover
            )
        } finally {
            runCatching { reader.close() }
        }
    }

    private fun specText(uri: Uri, h: FfAudio.Head, kbps: Int): String = buildList {
        fmtLabel(uri.path.orEmpty())?.let { add(it) }
        if (h.bits > 0) add("${h.bits}bit")
        if (h.rateHz > 0) add(rateLabel(h.rateHz))
        if (h.channels > 0) add("${h.channels}ch")
        if (kbps > 0) add("${kbps}kb/s")
    }.joinToString(" · ")

    private fun rateLabel(hz: Int): String =
        if (hz % 1000 == 0) "${hz / 1000} kHz" else String.format(Locale.US, "%.1f kHz", hz / 1000.0)

    private fun fmtLabel(path: String): String? = when (path.substringAfterLast('.', "").lowercase()) {
        "flac" -> "FLAC"
        "mp3" -> "MP3"
        "m4a", "aac", "mp4" -> "AAC"
        "wav" -> "WAV"
        "aiff", "aif" -> "AIFF"
        "dsf", "dff" -> "DSD"
        "wma" -> "WMA"
        "ogg", "oga", "opus" -> "OGG"
        "ape" -> "APE"
        "dts" -> "DTS"
        "mka" -> "MKA"
        else -> null
    }

    private fun looksLikeImage(b: ByteArray): Boolean {
        if (b.size < 16) return false
        val jpg = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() &&
            b[b.size - 2] == 0xFF.toByte() && b[b.size - 1] == 0xD9.toByte()
        val png = b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()
        return jpg || png
    }

    private fun looksLikePng(b: ByteArray): Boolean =
        b.size > 8 && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()

    private fun saveCover(ctx: Context, path: String, data: ByteArray, mime: String): File? = runCatching {
        val dir = File(ctx.cacheDir, "netcover").apply { mkdirs() }
        val ext = if (mime.contains("png")) "png" else "jpg"
        val name = Integer.toHexString((path + data.size).hashCode()) + "." + ext
        val f = File(dir, name)
        if (!f.isFile || f.length() != data.size.toLong()) f.writeBytes(data)
        f
    }.getOrNull()
}
