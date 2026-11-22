package com.dacweb.player.prefs

import android.content.Context
import android.net.Uri

/** 轻量偏好：下载目录、并发数。 */
object AppPrefs {

    private const val FILE = "app_prefs"
    private const val KEY_TREE = "download_tree_uri"
    private const val KEY_TREE_LABEL = "download_tree_label"
    private const val KEY_CONCURRENCY = "download_concurrency"
    private const val KEY_NO_PROBE = "dl_no_probe"
    private const val KEY_DIR_ASKED = "download_dir_asked"
    private const val KEY_THEME = "theme_index"
    private const val KEY_NIGHT = "night_mode"
    private const val KEY_LYRIC_SIZE = "lyric_size"
    private const val KEY_LYRIC_COLOR = "lyric_color"
    private const val KEY_IMAGE_MB = "image_cache_mb"
    private const val KEY_DEBUG_LOG = "debug_log"

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun downloadTree(ctx: Context): Uri? =
        sp(ctx).getString(KEY_TREE, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun downloadTreeLabel(ctx: Context): String? = sp(ctx).getString(KEY_TREE_LABEL, null)

    fun setDownloadTree(ctx: Context, uri: Uri?, label: String?) {
        sp(ctx).edit().apply {
            if (uri == null) {
                remove(KEY_TREE)
                remove(KEY_TREE_LABEL)
            } else {
                putString(KEY_TREE, uri.toString())
                putString(KEY_TREE_LABEL, label)
            }
        }.apply()
    }

    /** 未选择目录时用 MediaStore 的 Music/24bit。 */
    fun downloadDirLabel(ctx: Context): String =
        downloadTreeLabel(ctx)?.takeIf { it.isNotBlank() } ?: "Music/DACWebPlayer"

    /** 是否已经问过下载目录（首次下载前问一次，之后在队列里改）。 */
    fun dirAsked(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_DIR_ASKED, false)

    fun setDirAsked(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_DIR_ASKED, value).apply()
    }

    fun dacDepth(ctx: Context): Int = sp(ctx).getInt("dac_depth", 24).coerceIn(16, 32)

    fun setDacDepth(ctx: Context, value: Int) = sp(ctx).edit().putInt("dac_depth", value).apply()

    /** 0 = 跟随源文件采样率 */
    fun dacRate(ctx: Context): Int = sp(ctx).getInt("dac_rate", 0)

    fun setDacRate(ctx: Context, value: Int) = sp(ctx).edit().putInt("dac_rate", value).apply()

    /**
     * 直通输出模式：
     * 0 = 完全直通（DAC 采样率/位深完全跟随源文件，不做重采样、不改位深，只调音量）
     * 1 = 兼容转换（可按偏好改位深；采样率与源不同时用线性重采样转换，速度保持正确）
     */
    fun dacBitPerfect(ctx: Context): Boolean = sp(ctx).getBoolean("dac_bit_perfect", true)

    fun setDacBitPerfect(ctx: Context, value: Boolean) =
        sp(ctx).edit().putBoolean("dac_bit_perfect", value).apply()

    /**
     * DAC 独占已开启：在 DAC 页点过「独占」并成功拿到接口后置 true。
     * 开启后普通播放页也会把声音直接送到 USB DAC（ExoPlayer 静音，只保留进度/通知/歌词）。
     */
    fun dacExclusive(ctx: Context): Boolean = sp(ctx).getBoolean("dac_exclusive", false)

    fun setDacExclusive(ctx: Context, value: Boolean) =
        sp(ctx).edit().putBoolean("dac_exclusive", value).apply()

    fun concurrency(ctx: Context): Int = sp(ctx).getInt(KEY_CONCURRENCY, 3).coerceIn(1, 6)

    fun setConcurrency(ctx: Context, n: Int) {
        sp(ctx).edit().putInt(KEY_CONCURRENCY, n.coerceIn(1, 6)).apply()
    }

    /** 实验：跳过 Range 探测，直接按多线程分段下载（出问题再回退单流）。 */
    fun multiThreadNoProbe(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_NO_PROBE, false)

    fun setMultiThreadNoProbe(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_NO_PROBE, value).apply()
    }

    /** 详细调试日志：默认关。开启后才记录解码/USB/插件的逐条诊断（release 包默认一条不写）。 */
    fun debugLog(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_DEBUG_LOG, false)

    fun setDebugLog(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_DEBUG_LOG, value).apply()
    }

    /* ---------------- 主题 ---------------- */

    const val THEME_PURPLE = 0
    const val THEME_BLUE = 1
    const val THEME_GREEN = 2
    const val THEME_ORANGE = 3
    const val THEME_TEAL = 4
    const val THEME_RED = 5

    /** 0=跟随系统 1=浅色 2=深色 */
    const val NIGHT_SYSTEM = 0
    const val NIGHT_LIGHT = 1
    const val NIGHT_DARK = 2

    fun themeIndex(ctx: Context): Int = sp(ctx).getInt(KEY_THEME, THEME_PURPLE).coerceIn(0, 5)

    fun setThemeIndex(ctx: Context, index: Int) {
        sp(ctx).edit().putInt(KEY_THEME, index.coerceIn(0, 5)).apply()
    }

    fun nightMode(ctx: Context): Int = sp(ctx).getInt(KEY_NIGHT, NIGHT_SYSTEM).coerceIn(0, 2)

    fun setNightMode(ctx: Context, mode: Int) {
        sp(ctx).edit().putInt(KEY_NIGHT, mode.coerceIn(0, 2)).apply()
    }

    // ---- 歌词显示 ----
    fun lyricSize(ctx: Context): Int = sp(ctx).getInt(KEY_LYRIC_SIZE, 17).coerceIn(12, 28)

    fun setLyricSize(ctx: Context, sp: Int) {
        sp(ctx).edit().putInt(KEY_LYRIC_SIZE, sp.coerceIn(12, 28)).apply()
    }

    /** 0=跟随主题，其余见 [com.dacweb.player.prefs.LyricPalette]。 */
    fun lyricColor(ctx: Context): Int = sp(ctx).getInt(KEY_LYRIC_COLOR, 0).coerceIn(0, 5)

    fun setLyricColor(ctx: Context, index: Int) {
        sp(ctx).edit().putInt(KEY_LYRIC_COLOR, index.coerceIn(0, 5)).apply()
    }

    // ---- 图片缓存上限（MB）----
    fun imageCacheMb(ctx: Context): Int = sp(ctx).getInt(KEY_IMAGE_MB, 200).coerceIn(50, 1024)

    fun setImageCacheMb(ctx: Context, mb: Int) {
        sp(ctx).edit().putInt(KEY_IMAGE_MB, mb.coerceIn(50, 1024)).apply()
    }

    // ---- 音频输出 ----

    /** 0=系统默认(AudioTrack 跟随源) 1=AudioTrack 强制采样率 2=AAudio 3=OpenSL ES 4=DAC 独占 */
    fun outputMode(ctx: Context): Int = sp(ctx).getInt("output_mode", 0).coerceIn(0, 4)

    fun setOutputMode(ctx: Context, mode: Int) {
        sp(ctx).edit().putInt("output_mode", mode.coerceIn(0, 4)).apply()
    }

    /** 直通播放的软件衰减（0..60 dB，0 为不衰减；DAC 硬件音量写不进去时的兜底）。 */
    fun dacGainDb(ctx: Context): Int =
        sp(ctx).getInt("dac_gain_db", 24).coerceIn(0, 60)

    fun setDacGainDb(ctx: Context, value: Int) {
        sp(ctx).edit().putInt("dac_gain_db", value.coerceIn(0, 60)).apply()
    }

    /** 直通音量（0..100%，100 为最大）。DAC 有硬件音量时直接写 FEATURE_UNIT，否则换算成软件衰减。 */
    fun dacVolumePercent(ctx: Context): Int =
        sp(ctx).getInt("dac_volume_percent", 70).coerceIn(0, 100)

    fun setDacVolumePercent(ctx: Context, value: Int) {
        sp(ctx).edit().putInt("dac_volume_percent", value.coerceIn(0, 100)).apply()
    }

    /**
     * 直通环形缓冲大小（KB）。越大越抗抖动（爆音更少），代价是暂停/切歌的延迟变大。
     * 96k/24bit/2ch 大约 576 KB/s，所以 1024 KB ≈ 1.8 s。
     */
    fun dacBufferKb(ctx: Context): Int =
        sp(ctx).getInt("dac_buffer_kb", 512).coerceIn(256, 8192)

    fun setDacBufferKb(ctx: Context, value: Int) {
        sp(ctx).edit().putInt("dac_buffer_kb", value.coerceIn(256, 8192)).apply()
    }

    /** 0=跟随源文件，其余为强制采样率（Hz）。 */
    fun outputRate(ctx: Context): Int = sp(ctx).getInt("output_rate", 0)

    fun setOutputRate(ctx: Context, rate: Int) {
        sp(ctx).edit().putInt("output_rate", rate).apply()
    }

    // ---------------------------------------------------------- 遥控器焦点效果
    const val FOCUS_AUTO = 0
    const val FOCUS_ON = 1
    const val FOCUS_OFF = 2

    /** 遥控器焦点高亮：0=跟随屏幕形态（默认），1=总是开，2=总是关 */
    fun focusVisualMode(ctx: Context): Int = sp(ctx).getInt("focus_visual", FOCUS_AUTO)

    fun setFocusVisualMode(ctx: Context, value: Int) {
        sp(ctx).edit().putInt("focus_visual", value).apply()
    }

    /**
     * 当前窗口要不要画遥控器焦点框。
     * 跟随屏幕形态时：竖屏手机不给（描边在触摸屏上只是干扰），横屏大屏（电视/盒子）给，
     * 判定用 smallestScreenWidthDp >= 600，手机横过来（约 360~420dp）仍然不画。
     */
    fun focusVisualOn(ctx: Context): Boolean = when (focusVisualMode(ctx)) {
        FOCUS_ON -> true
        FOCUS_OFF -> false
        else -> {
            val c = ctx.resources.configuration
            c.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
                c.smallestScreenWidthDp >= 600
        }
    }

    /**
     * 是否大屏模式（电视 / 盒子 / 平板）。
     *
     * 大屏模式下退出应用就不留后台播放；手机上则照旧后台播放。
     * 判定顺序：系统明确声明是电视形态 → 有 Leanback 特性 → 平板级宽度（>=720dp）
     * → 没有触摸屏但宽度 >=600dp。手机最小宽度只有 360~420dp，横过来也不会误判。
     */
    fun largeScreen(ctx: Context): Boolean {
        val c = ctx.resources.configuration
        val uiType = c.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK
        if (uiType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) return true
        val pm = ctx.packageManager
        if (pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)) return true
        if (c.smallestScreenWidthDp >= 720) return true
        return c.smallestScreenWidthDp >= 600 &&
            !pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
    }
}
