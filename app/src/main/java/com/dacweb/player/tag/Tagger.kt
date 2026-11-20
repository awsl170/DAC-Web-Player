package com.dacweb.player.tag

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/** 按容器格式分发标签写入，并负责抓取封面二进制。 */
object Tagger {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    suspend fun tag(
        file: File,
        ext: String,
        tags: Map<String, String>,
        coverUrl: String?
    ) = withContext(Dispatchers.IO) {
        var cover: ByteArray? = null
        if (!coverUrl.isNullOrBlank()) {
            runCatching {
                val req = Request.Builder()
                    .url(coverUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        resp.body?.bytes()?.let { cover = normalizeCover(it) }
                    }
                }
            }
        }
        write(file, ext, tags, cover)
    }

    /** 把封面统一成 800x800 的 JPEG（居中裁成正方形再缩放），避免嵌入小图导致离线糊。 */
    private fun normalizeCover(bytes: ByteArray): ByteArray? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return@runCatching null
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
        val side = minOf(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, 800, 800, true)
        val out = ByteArrayOutputStream(512 * 1024)
        scaled.compress(Bitmap.CompressFormat.JPEG, 92, out)
        if (square !== src) square.recycle()
        if (scaled !== square) scaled.recycle()
        src.recycle()
        out.toByteArray()
    }.getOrNull()

    /** 已有的封面二进制（未归一化）直接写入时使用。 */
    fun write(file: File, ext: String, tags: Map<String, String>, cover: ByteArray?) =
        writeImpl(file, ext, tags, cover)

    private fun writeImpl(file: File, ext: String, tags: Map<String, String>, cover: ByteArray?) {
        val mime = "image/jpeg"
        when (ext.lowercase()) {
            "flac" -> {
                // 三个歌词字段全写：兼容不同播放器/标签工具的读取偏好
                val lyrics = tags["LYRICS"].orEmpty()
                val merged = tags.toMutableMap()
                if (lyrics.isNotBlank()) {
                    merged["LYRICS"] = lyrics
                    merged["UNSYNCEDLYRICS"] = lyrics
                    merged["USLT"] = lyrics
                }
                FlacTagger.write(file, merged, cover, mime)
            }
            "mp3" -> Id3Tagger.write(file, tags, cover, mime)
            else -> Unit
        }
    }
}
