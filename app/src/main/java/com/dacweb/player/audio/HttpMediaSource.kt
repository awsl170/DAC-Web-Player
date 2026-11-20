package com.dacweb.player.audio

import android.media.MediaDataSource
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 给 MediaExtractor / MediaCodec 用的 HTTP 数据源。
 *
 * MediaExtractor 自带的 http 通道不能设置 User-Agent/Referer，而 24bit 与酷我的
 * CDN 只认浏览器头，于是 setDataSource(url) 经常直接抛
 * "Failed to instantiate extractor"。这里用 OkHttp 自己实现一个可 seek 的
 * MediaDataSource：按需用 Range 请求从头/中间取流。
 */
class HttpMediaSource(
    private val url: String,
    private val headers: Map<String, String> = emptyMap()
) : MediaDataSource() {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var total = -1L
    private var pos = 0L
    private var response: Response? = null
    private var stream: InputStream? = null

    private fun request(rangeStart: Long): Request {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        if (rangeStart > 0) b.header("Range", "bytes=$rangeStart-")
        return b.build()
    }

    private fun openAt(start: Long) {
        closeStream()
        val resp = client.newCall(request(start)).execute()
        if (!resp.isSuccessful) {
            val code = resp.code
            resp.close()
            throw IOException("HTTP $code")
        }
        val body = resp.body ?: run {
            resp.close()
            throw IOException("响应没有内容")
        }
        response = resp
        stream = body.byteStream()
        pos = start
        if (start > 0 && resp.code == 200) {
            // 服务端忽略了 Range：手动丢掉前面 start 个字节
            var skipped = 0L
            while (skipped < start) {
                val n = stream!!.skip(start - skipped)
                if (n <= 0) break
                skipped += n
            }
            pos = skipped
        }
    }

    private fun closeStream() {
        runCatching { stream?.close() }
        stream = null
        runCatching { response?.close() }
        response = null
    }

    override fun getSize(): Long {
        if (total >= 0) return total
        // 先试 HEAD
        total = runCatching {
            val resp = client.newCall(request(0).newBuilder().head().build()).execute()
            resp.use { r ->
                if (r.isSuccessful) r.header("Content-Length")?.toLongOrNull() ?: -1L else -1L
            }
        }.getOrDefault(-1L)
        if (total > 0) return total
        // HEAD 被拒就发一个 bytes=0-0 看 Content-Range
        total = runCatching {
            val resp = client.newCall(request(0).newBuilder().header("Range", "bytes=0-0").build()).execute()
            resp.use { r ->
                val cr = r.header("Content-Range").orEmpty()
                val after = cr.substringAfter('/', "")
                if (after.isNotBlank() && after != "*") after.toLongOrNull() ?: -1L
                else r.header("Content-Length")?.toLongOrNull() ?: -1L
            }
        }.getOrDefault(-1L)
        return total
    }

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray?, offset: Int, length: Int): Int {
        if (buffer == null) return -1
        if (total >= 0 && position >= total) return -1
        if (stream == null || position != pos) openAt(position)
        val n = stream?.read(buffer, offset, length) ?: -1
        if (n <= 0) return -1
        pos += n
        return n
    }

    @Synchronized
    override fun close() {
        closeStream()
    }
}
