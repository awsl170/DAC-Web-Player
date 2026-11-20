package com.dacweb.player

import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.dacweb.player.plugin.PluginManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 插件管理：内置私人实现 + 用户导入的外部插件。
 *
 * 外部插件是 dex/jar/apk 文件，导入后复制到 files/plugins/ 并用 DexClassLoader 加载，
 * 协议见 plugin/PluginApi.kt 与 docs/plugin-api.md。
 */
class PluginsActivity : BaseActivity() {

    private lateinit var content: LinearLayout

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        importPlugin(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            MaterialToolbar(this).apply {
                title = getString(R.string.plugins_title)
                setNavigationIcon(R.drawable.ic_arrow_back)
                setNavigationOnClickListener { finish() }
            }
        )
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        root.addView(
            ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                )
                addView(content)
            }
        )
        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        content.removeAllViews()
        val entries = PluginManager.entries(this)

        content.addView(hint(getString(R.string.plugins_hint)))
        content.addView(primaryButton(getString(R.string.plugins_import)) {
            runCatching { picker.launch(arrayOf("*/*")) }
        })

        if (entries.isNotEmpty()) {
            content.addView(
                label(getString(R.string.plugins_enabled_count, entries.count { it.enabled && it.ok }), 13f,
                    com.google.android.material.R.attr.colorPrimary)
                    .apply { setPadding(0, dp(14), 0, dp(4)) }
            )
        }

        entries.forEach { e ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(10))
                background = androidx.core.content.ContextCompat.getDrawable(
                    this@PluginsActivity, R.drawable.bg_card
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            }

            card.addView(label(e.name, 16f, android.R.attr.textColorPrimary).apply {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            val meta = buildString {
                append(if (e.file.isBlank()) getString(R.string.plugins_builtin_desc) else e.file)
                if (e.version.isNotBlank()) append("  ·  v${e.version}")
            }
            card.addView(label(meta, 12f, com.google.android.material.R.attr.colorOnSurfaceVariant))
            if (e.description.isNotBlank()) {
                card.addView(label(e.description, 12f, com.google.android.material.R.attr.colorOnSurfaceVariant).apply {
                    setPadding(0, dp(4), 0, 0)
                })
            }
            if (e.error != null) {
                card.addView(label(getString(R.string.plugins_error, e.error), 12f, com.google.android.material.R.attr.colorError))
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
            }

            if (e.ok) {
                val sw = MaterialSwitch(this).apply {
                    text = getString(R.string.plugins_enabled)
                    isChecked = e.enabled
                    setOnCheckedChangeListener { _, checked ->
                        PluginManager.setEnabled(this@PluginsActivity, e.id, checked)
                        render()
                    }
                }
                row.addView(sw, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            } else {
                row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            }

            if (e.file.isBlank()) {
                // 内置插件：只给开关，没有文件可删
                row.addView(label(getString(R.string.plugins_builtin), 12f,
                    com.google.android.material.R.attr.colorOnSurfaceVariant))
            } else run {
                row.addView(MaterialButton(this, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = getString(R.string.plugins_delete)
                    textSize = 12f
                    minHeight = 0
                    setOnClickListener {
                        val err = PluginManager.delete(this@PluginsActivity, e)
                        render()
                        snack(
                            if (err == null) getString(R.string.plugins_deleted)
                            else getString(R.string.plugins_delete_fail, err)
                        )
                    }
                })
            }
            card.addView(row)
            content.addView(card)
        }
    }

    private fun importPlugin(uri: android.net.Uri) {
        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) { PluginManager.import(this@PluginsActivity, uri) }
            render()
            snack(if (err == null) getString(R.string.plugins_import_ok) else getString(R.string.plugins_import_fail, err))
        }
    }

    private fun label(text: String, size: Float, colorAttr: Int) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(themeColor(colorAttr))
        isSingleLine = false
        ellipsize = TextUtils.TruncateAt.END
        maxLines = 3
    }

    private fun hint(text: String) = label(text, 12f, com.google.android.material.R.attr.colorOnSurfaceVariant).apply {
        setTextIsSelectable(true)
    }

    private fun primaryButton(text: String, onClick: () -> Unit) = MaterialButton(this).apply {
        this.text = text
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
    }

    private fun snack(msg: String) {
        Snackbar.make(content, msg, Snackbar.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()
}
