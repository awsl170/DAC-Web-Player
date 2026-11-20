package com.dacweb.player

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.dacweb.player.cache.CacheManager
import com.dacweb.player.lyrics.LyricsRepo
import java.io.File

/**
 * 兜底崩溃记录：把未捕获异常的堆栈写到 filesDir，下次启动时在首页弹出来，
 * 这样即使没有 adb / logcat 也能看到崩溃原因。
 *
 * 同时作为 Coil 的 ImageLoaderFactory：磁盘缓存按 MB 上限（默认 200MB）。
 */
class App : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader = CacheManager.buildLoader(this)

    override fun onCreate() {
        super.onCreate()
        BaseActivity.applyNightMode(com.dacweb.player.prefs.AppPrefs.nightMode(this))

        LyricsRepo.init(this)

        // release 包默认不写调试日志；debug 包或设置里打开开关才记录
        com.dacweb.player.log.AppLog.refresh(this)

        // 启动时清理超过 2 天的缓存（图片 / 临时文件）
        Thread { runCatching { CacheManager.purgeExpired(this) } }.start()

        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                File(filesDir, CRASH_FILE).writeText(
                    "${e.javaClass.name}: ${e.message}\n" +
                            "---\n" + e.stackTraceToString()
                )
            }
            prev?.uncaughtException(t, e)
        }
    }

    companion object {
        private const val CRASH_FILE = "last_crash.txt"

        fun lastCrash(ctx: Context): String? {
            val f = File(ctx.filesDir, CRASH_FILE)
            if (!f.exists()) return null
            val text = runCatching { f.readText() }.getOrNull()
            runCatching { f.delete() }
            return text?.takeIf { it.isNotBlank() }
        }
    }
}
