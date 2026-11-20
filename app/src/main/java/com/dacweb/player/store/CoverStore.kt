package com.dacweb.player.store

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * 歌单封面落盘：把封面下载到 filesDir/playlist_covers 里，之后离线也能显示。
 * 返回 `file://` URI 字符串，失败返回 null。
 */
object CoverStore {

    private const val DIR = "playlist_covers"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Mobile Safari/537.36"

    private val client: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }

    suspend fun save(ctx: Context, url: String): String? = withContext(Dispatchers.IO) {
        if (!url.startsWith("http")) return@withContext null
        runCatching {
            val name = Integer.toHexString(url.hashCode()) + "-" + url.length + ".img"
            val out = File(dir(ctx), name)
            if (out.exists() && out.length() > 1024) return@withContext Uri.fromFile(out).toString()
            val req = Request.Builder().url(url).header("User-Agent", UA).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val bytes = resp.body?.bytes() ?: return@withContext null
                if (bytes.size < 1024) return@withContext null
                out.writeBytes(bytes)
            }
            Uri.fromFile(out).toString()
        }.getOrNull()
    }

    /** 目录里文件的总字节数（缓存统计用）。 */
    fun sizeBytes(ctx: Context): Long = dir(ctx).listFiles()?.sumOf { it.length() } ?: 0L

    fun clear(ctx: Context) {
        dir(ctx).listFiles()?.forEach { it.delete() }
    }
}
