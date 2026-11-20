package com.dacweb.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.dacweb.player.databinding.ActivityAboutBinding

/**
 * 关于：版本、更新日志、第三方组件与许可证。
 *
 * 许可证按开源项目的常规写法：逐个组件列名称/版本/许可，点开看要点与官方链接。
 */
class AboutActivity : BaseActivity() {

    private lateinit var b: ActivityAboutBinding

    private val sourceUrl get() = getString(R.string.about_source_url)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }
        buildContent()
    }

    private fun buildContent() {
        b.content.removeAllViews()

        section(getString(R.string.about_version))
        row(getString(R.string.about_version_name), versionName())
        row(getString(R.string.about_version_code), versionCode())
        row(getString(R.string.about_package), packageName)
        row(getString(R.string.about_build), if (isDebuggable()) getString(R.string.about_build_debug) else getString(R.string.about_build_release))
        row(getString(R.string.about_abis), Build.SUPPORTED_ABIS.joinToString(", "))
        row(getString(R.string.about_android), "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        row(getString(R.string.about_device), "${Build.MANUFACTURER} ${Build.MODEL}")
        row(getString(R.string.about_theme), nightLabel())

        section(getString(R.string.about_changelog))
        paragraph(readAsset("changelog.txt").ifBlank { getString(R.string.about_changelog_empty) })

        section(getString(R.string.about_ai_title))
        paragraph(getString(R.string.about_ai_body))

        section(getString(R.string.about_license))
        paragraph(getString(R.string.about_gpl_copyright))
        paragraph(getString(R.string.about_gpl_body))
        paragraph(getString(R.string.about_gpl_checklist))
        row(getString(R.string.about_source_link), sourceUrl)
        linkRow(getString(R.string.about_source_link) + "（复制）") { copy(sourceUrl) }
        linkRow(getString(R.string.about_gpl_title) + " ›") { showGpl() }

        section(getString(R.string.about_third_party))
        linkRow(getString(R.string.about_open_third_party)) {
            startActivity(android.content.Intent(this, ThirdPartyActivity::class.java))
        }
        paragraph(getString(R.string.about_third_party_note))

        section(getString(R.string.about_copyright))
        paragraph(getString(R.string.about_copyright_body))
    }

    // ---------------------------------------------------------------- 组装

    private fun section(title: String) {
        val tv = TextView(this).apply {
            text = title
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(themeColor(com.google.android.material.R.attr.colorPrimary))
            setPadding(dp(16), dp(22), dp(16), dp(6))
        }
        b.content.addView(tv)
    }

    private fun row(label: String, value: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        box.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
        })
        box.addView(TextView(this).apply {
            text = value
            textSize = 12.5f
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, dp(2), 0, 0)
        })
        b.content.addView(box)
    }

    private fun paragraph(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 12.5f
        setLineSpacing(0f, 1.15f)
        setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        setPadding(dp(16), dp(4), dp(16), dp(8))
    }.also { b.content.addView(it) }

    private fun linkRow(text: String, onClick: () -> Unit) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(themeColor(com.google.android.material.R.attr.colorPrimary))
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { onClick() }
        }
        b.content.addView(tv)
    }

    private fun showGpl() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_gpl_title)
            .setMessage(
                getString(R.string.about_gpl_copyright) + "\n\n" +
                    getString(R.string.about_gpl_body) + "\n\n" +
                    getString(R.string.about_gpl_checklist)
            )
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.about_copy_link) { _, _ ->
                copy("https://www.gnu.org/licenses/gpl-3.0.html")
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

    // ---------------------------------------------------------------- 数据

    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "-"
    }.getOrDefault("-")

    private fun versionCode(): String = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toString() else @Suppress("DEPRECATION") info.versionCode.toString()
    }.getOrDefault("-")

    private fun isDebuggable(): Boolean =
        (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun nightLabel(): String = when (AppCompatDelegate.getDefaultNightMode()) {
        AppCompatDelegate.MODE_NIGHT_NO -> getString(R.string.theme_light)
        AppCompatDelegate.MODE_NIGHT_YES -> getString(R.string.theme_dark)
        else -> getString(R.string.theme_follow_system)
    }

    private fun readAsset(name: String): String = runCatching {
        assets.open(name).bufferedReader().use { it.readText() }
    }.getOrDefault("")

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
