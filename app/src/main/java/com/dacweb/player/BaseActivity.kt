package com.dacweb.player

import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dacweb.player.prefs.AppPrefs

/**
 * 所有界面的基类：
 * 1. 在 inflate 之前把主题色板叠加到当前主题上（?attr/colorPrimary 等跟随设置）；
 * 2. 开启 edge-to-edge，系统栏透明并跟随深浅色切换图标颜色，
 *    状态栏/导航栏因此与页面背景融为一体，而不是一块纯主题色。
 */
abstract class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(overlayOf(AppPrefs.themeIndex(this)), true)
        // 遥控器焦点效果：竖屏手机默认关（描边只在遥控器上有意义），横屏大屏默认开
        theme.applyStyle(
            if (AppPrefs.focusVisualOn(this)) R.style.ThemeOverlay_MusicPlayer_FocusOn
            else R.style.ThemeOverlay_MusicPlayer_FocusOff,
            true
        )
        super.onCreate(savedInstanceState)
        setupSystemBars()
    }

    private fun setupSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT

        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }

        // 内容避开系统栏：状态栏区域露出的是页面背景色，和 AppBar 连成一片。
        val content = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    /** 从当前主题解析属性色（如 colorOnSurface）。 */
    protected fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    companion object {

        val overlayIds = intArrayOf(
            R.style.ThemeOverlay_MusicPlayer_Purple,
            R.style.ThemeOverlay_MusicPlayer_Blue,
            R.style.ThemeOverlay_MusicPlayer_Green,
            R.style.ThemeOverlay_MusicPlayer_Orange,
            R.style.ThemeOverlay_MusicPlayer_Teal,
            R.style.ThemeOverlay_MusicPlayer_Red
        )

        fun overlayOf(index: Int): Int = overlayIds[index.coerceIn(0, overlayIds.size - 1)]

        /** 代码里动态建行时用：取当前主题的列表行/胶囊按钮背景资源 id。 */
        fun rowFocusRes(ctx: android.content.Context, round: Boolean = false): Int {
            val tv = android.util.TypedValue()
            ctx.theme.resolveAttribute(if (round) R.attr.rowFocusRound else R.attr.rowFocus, tv, true)
            return if (tv.resourceId != 0) tv.resourceId else R.drawable.bg_focus
        }

        /** 深浅色模式：把偏好映射成 AppCompatDelegate 的模式。 */
        fun applyNightMode(mode: Int) {
            AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    AppPrefs.NIGHT_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                    AppPrefs.NIGHT_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }
}
