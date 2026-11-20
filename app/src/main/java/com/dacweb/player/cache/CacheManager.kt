package com.dacweb.player.cache

import android.content.Context
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.dacweb.player.prefs.AppPrefs
import java.io.File

/**
 * 缓存统一管理：
 * - 图片（Coil 磁盘缓存）按 MB 上限 + LRU 自动回收；
 * - 所有缓存记录/文件最长保留 2 天，启动时清理；
 * - 设置页可分类手动清理。
 */
object CacheManager {

    /** 任何缓存最多保留 2 天。 */
    const val MAX_AGE_MS = 2L * 24 * 60 * 60 * 1000
    private const val IMAGE_DIR = "image_cache"
    private const val TEMP_PREFIX = "dl_"
    private const val TEMP_KEEP_MS = 60L * 60 * 1000

    fun imageDir(ctx: Context): File = File(ctx.cacheDir, IMAGE_DIR)

    /** Coil 的全局 ImageLoader：磁盘缓存上限取设置里的 MB 数。 */
    fun buildLoader(ctx: Context): ImageLoader {
        val mb = AppPrefs.imageCacheMb(ctx)
        return ImageLoader.Builder(ctx)
            .diskCache {
                DiskCache.Builder()
                    .directory(imageDir(ctx))
                    .maxSizeBytes(mb * 1024L * 1024L)
                    .build()
            }
            .memoryCache { MemoryCache.Builder(ctx).maxSizePercent(0.15).build() }
            .crossfade(true)
            .build()
    }

    /** 改了上限后立即生效。 */
    fun applyLimit(ctx: Context) {
        runCatching { Coil.setImageLoader(buildLoader(ctx)) }
    }

    /** App 启动时跑一次：清掉超过 2 天的所有缓存。 */
    fun purgeExpired(ctx: Context) {
        val now = System.currentTimeMillis()
        runCatching {
            ctx.cacheDir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.startsWith(TEMP_PREFIX) && now - f.lastModified() > TEMP_KEEP_MS) {
                    runCatching { f.delete() }
                }
            }
        }
        runCatching {
            val stale = imageDir(ctx).listFiles()?.any { now - it.lastModified() > MAX_AGE_MS } == true
            if (stale) Coil.imageLoader(ctx).diskCache?.clear()
        }
    }

    /* ---------------- 占用 ---------------- */

    fun imageBytes(ctx: Context): Long = dirBytes(imageDir(ctx))

    fun audioBytes(ctx: Context): Long = runCatching { MediaCache.sizeBytes(ctx) }.getOrDefault(0L)

    fun clearAudio(ctx: Context) {
        runCatching { MediaCache.clear(ctx) }
    }

    fun tempBytes(ctx: Context): Long =
        ctx.cacheDir.listFiles()?.filter { it.isFile && it.name.startsWith(TEMP_PREFIX) }
            ?.sumOf { it.length() } ?: 0L

    fun totalBytes(ctx: Context): Long = imageBytes(ctx) + tempBytes(ctx) + audioBytes(ctx)

    fun summary(ctx: Context): String {
        val img = imageBytes(ctx)
        val temp = tempBytes(ctx)
        val audio = audioBytes(ctx)
        val parts = mutableListOf("图片 ${fmt(img)}")
        if (audio > 0) parts += "音频 ${fmt(audio)}"
        if (temp > 0) parts += "临时 ${fmt(temp)}"
        return parts.joinToString(" · ")
    }

    /* ---------------- 清理 ---------------- */

    fun clearImages(ctx: Context) {
        runCatching { Coil.imageLoader(ctx).diskCache?.clear() }
    }

    fun clearTemps(ctx: Context) {
        ctx.cacheDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith(TEMP_PREFIX)) runCatching { f.delete() }
        }
    }

    fun clearAll(ctx: Context) {
        clearImages(ctx)
        clearTemps(ctx)
        clearAudio(ctx)
    }

    /* ---------------- 工具 ---------------- */

    private fun dirBytes(dir: File): Long {
        var total = 0L
        dir.listFiles()?.forEach { f ->
            total += if (f.isDirectory) dirBytes(f) else f.length()
        }
        return total
    }

    fun fmt(bytes: Long): String = when {
        bytes <= 0L -> "0 KB"
        bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    }
}
