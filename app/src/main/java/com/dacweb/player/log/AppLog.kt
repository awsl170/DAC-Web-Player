package com.dacweb.player.log

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

import com.dacweb.player.prefs.AppPrefs

/**
 * 统一日志出口。
 *
 * release 包默认**一条调试日志都不写**（不占 logcat、不攒诊断缓冲）；
 * 设置 → 通用 →「详细调试日志」打开后才输出。
 * debug 包（FLAG_DEBUGGABLE）始终输出，方便开发时不改设置。
 *
 * 注意：Log.e 不受开关影响——真出异常时日志必须留下。
 */
object AppLog {

    @Volatile
    private var enabled = false

    /** 启动时和改开关时各调一次。 */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        enabled = debuggable || AppPrefs.debugLog(app)
    }

    /** 是否在记录详细日志（解码/USB/插件的逐条诊断）。 */
    val verbose: Boolean get() = enabled

    fun i(tag: String, msg: String) {
        if (enabled) Log.i(tag, msg)
    }

    fun w(tag: String, msg: String) {
        if (enabled) Log.w(tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable) {
        if (enabled) Log.w(tag, msg, tr)
    }

    fun e(tag: String, msg: String) {
        Log.e(tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable) {
        Log.e(tag, msg, tr)
    }
}
