package com.dacweb.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.dacweb.player.databinding.ActivityAboutBinding

/**
 * 第三方组件子页面：列出本应用用到的 Apache-2.0 等开源组件。
 *
 * 自有代码以 GPLv3 授权（见「关于」页），这里只列第三方依赖，避免两种许可证混在一起看。
 */
class ThirdPartyActivity : BaseActivity() {

    private lateinit var b: ActivityAboutBinding

    private data class Component(
        val name: String,
        val version: String,
        val license: String,
        val note: String,
        val url: String = "https://www.apache.org/licenses/LICENSE-2.0"
    )

    private val components = listOf(
        Component("AndroidX（core / appcompat / activity / lifecycle / recyclerview / constraintlayout / documentfile）", "1.13.1 / 1.7.0 / 1.8.x / 2.8.6 / 1.3.2 / 2.1.4 / 1.0.1", "Apache-2.0", "Google 官方的兼容库与基础组件。"),
        Component("Material Components for Android", "1.12.0", "Apache-2.0", "Material 3 控件：卡片、底部弹层、滑块、开关等。"),
        Component("Kotlin Standard Library / kotlinx.coroutines", "2.0.21 / 1.8.1", "Apache-2.0", "语言运行时与协程调度。"),
        Component("OkHttp", "4.12.0", "Apache-2.0", "HTTP 客户端；插件在自己进程内可自行选用，本应用只用它加载封面与文本资源。"),
        Component("Coil", "2.7.0", "Apache-2.0", "图片加载与磁盘缓存（按 MB 上限 LRU）。"),
        Component("AndroidX Media3（ExoPlayer / Session / UI / DataSource / Database）", "1.4.1", "Apache-2.0", "播放器内核、媒体会话、通知栏控制与流媒体缓存。"),
        Component("AndroidX DocumentFile", "1.0.1", "Apache-2.0", "SAF 下载目录读写。"),
        Component(
            "FFmpeg（libavcodec / libavformat / libavutil / libswresample）",
            "7.1",
            "LGPL-2.1-or-later",
            "本地音频文件的解码与重采样；以独立 .so 动态加载，未修改源码。\n" +
                "完整编译选项见仓库 docs/ffmpeg-build.md 与 scripts/build-ffmpeg-android.sh，\n" +
                "许可全文见 assets/licenses/LGPL-2.1.txt。",
            "https://ffmpeg.org/legal.html"
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.title = getString(R.string.about_third_party_title)
        b.toolbar.setNavigationOnClickListener { finish() }
        buildContent()
    }

    private fun buildContent() {
        b.content.removeAllViews()
        b.content.addView(paragraph(getString(R.string.about_third_party_note)).apply {
            setPadding(dp(16), dp(14), dp(16), dp(8))
        })
        components.forEach { c -> componentRow(c) }
    }

    private fun paragraph(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 12.5f
        setLineSpacing(0f, 1.15f)
        setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        setPadding(dp(16), dp(4), dp(16), dp(8))
    }

    private fun componentRow(c: Component) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { showComponent(c) }
        }
        box.addView(TextView(this).apply {
            text = c.name
            textSize = 13.5f
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
        })
        box.addView(TextView(this).apply {
            text = "${c.version} · ${c.license}"
            textSize = 12f
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        b.content.addView(box)
    }

    private fun showComponent(c: Component) {
        MaterialAlertDialogBuilder(this)
            .setTitle(c.name)
            .setMessage(
                "版本：${c.version}\n许可证：${c.license}\n\n${c.note}\n\n" +
                    if (c.license.startsWith("LGPL")) getString(R.string.about_license_lgpl)
                    else getString(R.string.about_license_apache)
            )
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.about_copy_link) { _, _ ->
                copy(c.url)
            }
            .show()
    }

    private fun copy(text: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("link", text))
        snack(getString(R.string.about_link_copied))
    }

    private fun snack(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
