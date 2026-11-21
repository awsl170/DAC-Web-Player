package com.dacweb.player

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.dacweb.player.databinding.ActivityAboutBinding

/**
 * 更新日志子页面：关于页只放一个入口，正文挪到这里，避免关于页被一大段文字撑长。
 */
class ChangelogActivity : BaseActivity() {

    private lateinit var b: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.title = getString(R.string.about_changelog)
        b.toolbar.setNavigationOnClickListener { finish() }

        b.content.removeAllViews()
        val text = runCatching {
            assets.open("changelog.txt").bufferedReader().use { it.readText() }
        }.getOrDefault("")
        b.content.addView(TextView(this).apply {
            setText(if (text.isBlank()) getString(R.string.about_changelog_empty) else text.trim())
            textSize = 13.5f
            setLineSpacing(dp(5).toFloat(), 1.1f)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
            setPadding(dp(16), dp(14), dp(16), dp(24))
        })
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
