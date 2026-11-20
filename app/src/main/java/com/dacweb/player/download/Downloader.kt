package com.dacweb.player.download

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import com.dacweb.player.cache.MediaCache
import com.dacweb.player.prefs.AppPrefs
import com.dacweb.player.tag.Tagger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * 下载 → 写标签 → 保存到用户目录。
 * - 每个 host 只探测一次是否支持 Range（和 py 的 _host_range_cache 一致）
 * - 大文件（>8MB）走 3 段并行，小文件单流
 */
object Downloader {

    data class Meta(
        val id: String,
        val title: String,
        val artist: String,
        val album: String,
        val cover: String,
        val quality: String,
        val format: String,
        val size: String,
        val albumArtist: String = artist,
        val year: Int = 0,
        val trackNo: Int = 0,
        val discNo: Int = 0,
        val alias: String = "",
        val comment: String = "",
        val lyrics: String = ""
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** host -> 是否支持 Range。只探测一次。 */
    private val rangeCache = ConcurrentHashMap<String, Boolean>()

    suspend fun download(
        ctx: Context,
        url: String,
        meta: Meta,
        onProgress: (Int) -> Unit = {}
    ): Uri = withContext(Dispatchers.IO) {
        var ext = meta.format.trim().lowercase()
        if (ext.isEmpty()) ext = url.substringBefore('?').substringAfterLast('.', "flac").lowercase()
        val tmp = File(ctx.cacheDir, "dl_${System.currentTimeMillis()}_${System.nanoTime() % 1000}.$ext")
        try {
            // 走缓冲层：已缓存的部分直接命中，剩下的从网络补；
            // 顺手把这次下载的字节也留在缓存里，之后播放同一首不用再下。
            try {
                fetchViaCache(ctx, url, tmp, onProgress)
            } catch (e: Exception) {
                tmp.delete()
                fetch(url, tmp, onProgress, AppPrefs.multiThreadNoProbe(ctx))
            }
            if (tmp.length() < 4096) throw IOException("下载的文件异常（${tmp.length()} 字节）")

            val tags = linkedMapOf(
                "TITLE" to meta.title,
                "ARTIST" to meta.artist,
                "ALBUM" to meta.album,
                "ALBUMARTIST" to meta.albumArtist.ifBlank { meta.artist },
                "COMMENT" to meta.comment.ifBlank { "音质=${meta.quality}" }
            )
            if (meta.year > 0) tags["DATE"] = meta.year.toString()
            if (meta.trackNo > 0) tags["TRACKNUMBER"] = meta.trackNo.toString()
            if (meta.discNo > 0) tags["DISCNUMBER"] = meta.discNo.toString()
            if (meta.alias.isNotBlank()) tags["SUBTITLE"] = meta.alias
            if (meta.lyrics.isNotBlank()) tags["LYRICS"] = meta.lyrics

            runCatching { Tagger.tag(tmp, ext, tags, meta.cover.ifBlank { null }) }
            DownloadStore.save(ctx, tmp, ext, meta)
        } finally {
            tmp.delete()
        }
    }

    // ------------------------------------------------------------ 传输

    /** 经 Media3 SimpleCache 读：命中缓存的部分不发请求，缺失的自动补，并写入缓存。 */
    private fun fetchViaCache(ctx: Context, url: String, out: File, onProgress: (Int) -> Unit) {
        val ds = MediaCache.readOnlyDataSource(ctx)
        try {
            val length = ds.open(DataSpec(Uri.parse(url)))
            FileOutputStream(out).use { fos ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                while (true) {
                    val n = ds.read(buf, 0, buf.size)
                    if (n == C.RESULT_END_OF_INPUT) break
                    fos.write(buf, 0, n)
                    done += n
                    if (length > 0) onProgress(((done * 100) / length).toInt().coerceIn(0, 100))
                }
            }
        } finally {
            runCatching { ds.close() }
        }
    }

    private fun fetch(url: String, out: File, onProgress: (Int) -> Unit, noProbe: Boolean) {
        if (noProbe) {
            // 实验模式：不探测，直接按 HEAD 拿大小后并行分段；任何异常回退单流
            val total = headTotal(url)
            if (total >= 8L * 1024 * 1024) {
                val ok = runCatching {
                    fetchParallel(url, out, total, onProgress)
                    true
                }.getOrDefault(false)
                if (ok) return
                out.delete()
                fetchSingle(url, out, true, onProgress)
                return
            }
            fetchSingle(url, out, total > 0, onProgress)
            return
        }
        val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
        val supportsRange = rangeCache.getOrPut(host) { probeRange(url) }
        val total = if (supportsRange) probeTotal(url) else 0L

        if (supportsRange && total >= 8L * 1024 * 1024) {
            fetchParallel(url, out, total, onProgress)
        } else {
            fetchSingle(url, out, supportsRange, onProgress)
        }
    }

    /** 轻量取总大小：HEAD 一次，不拉正文。 */
    private fun headTotal(url: String): Long = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", UA).head().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use 0L
            resp.header("Content-Length")?.toLongOrNull() ?: 0L
        }
    }.getOrDefault(0L)

    /** 探测一次：Range: bytes=0-0 返回 206 即支持。 */
    private fun probeRange(url: String): Boolean = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", UA).header("Range", "bytes=0-0").build()
        client.newCall(req).execute().use { it.code == 206 }
    }.getOrDefault(false)

    private fun probeTotal(url: String): Long = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", UA).header("Range", "bytes=0-0").build()
        client.newCall(req).execute().use { resp ->
            val cr = resp.header("Content-Range").orEmpty()
            if (cr.contains('/')) cr.substringAfterLast('/').toLongOrNull() ?: 0L else 0L
        }
    }.getOrDefault(0L)

    private fun fetchSingle(
        url: String,
        out: File,
        supportsRange: Boolean,
        onProgress: (Int) -> Unit
    ) {
        var done = 0L
        var total = 0L
        var attempt = 0
        var lastPct = -1
        while (attempt < 4) {
            attempt++
            try {
                val b = Request.Builder().url(url).header("User-Agent", UA)
                    .header("Accept-Encoding", "identity")
                if (attempt > 1 && supportsRange && done > 0) b.header("Range", "bytes=$done-")
                client.newCall(b.build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("下载失败：HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("下载失败：空响应")
                    if (total <= 0) {
                        total = if (attempt > 1 && supportsRange) done + body.contentLength()
                        else body.contentLength()
                    }
                    body.byteStream().use { input ->
                        java.io.FileOutputStream(out, attempt > 1 && supportsRange).use { sink ->
                            val buf = ByteArray(128 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                sink.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct != lastPct) { lastPct = pct; onProgress(min(99, pct)) }
                                }
                            }
                        }
                    }
                }
                if (total > 0 && done < total) throw IOException("下载中断（已收到 $done / $total 字节）")
                return
            } catch (e: IOException) {
                if (attempt >= 4) throw IOException("下载失败：${e.message}", e)
                Thread.sleep(600L * attempt)
            }
        }
    }

    private fun fetchParallel(url: String, out: File, total: Long, onProgress: (Int) -> Unit) {
        val parts = 3
        RandomAccessFile(out, "rw").use { raf -> raf.setLength(total) }
        val per = total / parts
        val progress = java.util.concurrent.atomic.AtomicLong(0)
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()

        val threads = (0 until parts).map { i ->
            val start = i * per
            val end = if (i == parts - 1) total - 1 else start + per - 1
            Thread {
                var pos = start
                var attempt = 0
                while (pos <= end) {
                    attempt++
                    try {
                        val req = Request.Builder().url(url)
                            .header("User-Agent", UA)
                            .header("Accept-Encoding", "identity")
                            .header("Range", "bytes=$pos-$end")
                            .build()
                        client.newCall(req).execute().use { resp ->
                            if (resp.code != 206) throw IOException("分段下载失败：HTTP ${resp.code}")
                            val body = resp.body ?: throw IOException("分段下载失败：空响应")
                            RandomAccessFile(out, "rw").use { raf ->
                                raf.seek(pos)
                                body.byteStream().use { input ->
                                    val buf = ByteArray(128 * 1024)
                                    while (pos <= end) {
                                        val n = input.read(buf)
                                        if (n <= 0) break
                                        raf.write(buf, 0, n)
                                        pos += n
                                        val done = progress.addAndGet(n.toLong())
                                        onProgress(min(99, (done * 100 / total).toInt()))
                                    }
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        // 每段最多续传 4 次：media CDN 会主动掐掉长连接
                        if (attempt >= 4) {
                            errors.add(t)
                            return@Thread
                        }
                        Thread.sleep(600L * attempt)
                    }
                }
            }.apply { isDaemon = true; start() }
        }
        threads.forEach { it.join() }
        errors.firstOrNull()?.let { throw IOException("下载失败：${it.message}", it) }
        onProgress(100)
    }
}
