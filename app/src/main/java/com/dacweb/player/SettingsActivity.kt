package com.dacweb.player

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AlertDialog
import com.google.android.material.tabs.TabLayout
import com.dacweb.player.cache.CacheManager
import com.dacweb.player.databinding.ActivitySettingsBinding
import com.dacweb.player.model.Quality
import com.dacweb.player.prefs.AppPrefs
import com.dacweb.player.prefs.LyricPalette

/** 独立设置界面：常规（音质/下载目录/并发）+ 主题（深浅色/主题色）。 */
class SettingsActivity : BaseActivity() {

    private lateinit var b: ActivitySettingsBinding

    private val paletteColors = intArrayOf(
        R.color.palette_purple,
        R.color.palette_blue,
        R.color.palette_green,
        R.color.palette_orange,
        R.color.palette_teal,
        R.color.palette_red
    )

    private val paletteNames = arrayOf("紫", "蓝", "绿", "橙", "青", "红")

    private val pickDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            AppPrefs.setDownloadTree(this, uri, uri.lastPathSegment ?: uri.toString())
            render()
            Toast.makeText(this, getString(R.string.download_dir_changed, AppPrefs.downloadDirLabel(this)), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.toolbar.setNavigationOnClickListener { finish() }
        val startTab = savedInstanceState?.getInt(KEY_TAB, 0) ?: 0
        b.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                b.pageGeneral.visibility = if (tab.position == 0) View.VISIBLE else View.GONE
                b.pageAudio.visibility = if (tab.position == 1) View.VISIBLE else View.GONE
                b.pageTheme.visibility = if (tab.position == 2) View.VISIBLE else View.GONE
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        b.rowQuality.setOnClickListener { pickQuality() }
        b.rowDir.setOnClickListener { pickDir.launch(null) }
        b.btnResetDir.setOnClickListener {
            AppPrefs.setDownloadTree(this, null, null)
            render()
            Toast.makeText(this, getString(R.string.download_dir_changed, AppPrefs.downloadDirLabel(this)), Toast.LENGTH_SHORT).show()
        }
        b.rowConcurrency.setOnClickListener { pickConcurrency() }
        b.rowLyricSize.setOnClickListener { pickLyricSize() }
        b.rowLyricColor.setOnClickListener { pickLyricColor() }
        b.rowCacheUsage.setOnClickListener { render() }
        b.rowClearCache.setOnClickListener { clearCacheDialog() }
        b.rowImageMax.setOnClickListener { pickImageMax() }
        b.rowFocusVisual.setOnClickListener { pickFocusVisual() }
        b.audioPage.rowDac.setOnClickListener {
            startActivity(android.content.Intent(this, DacActivity::class.java))
        }
        bindAudioRates()
        b.rowPlugins.setOnClickListener {
            startActivity(android.content.Intent(this, PluginsActivity::class.java))
        }
        b.rowAbout.setOnClickListener {
            startActivity(android.content.Intent(this, AboutActivity::class.java))
        }
        b.switchNoProbe.isChecked = AppPrefs.multiThreadNoProbe(this)
        b.switchNoProbe.setOnCheckedChangeListener { _, checked ->
            AppPrefs.setMultiThreadNoProbe(this, checked)
        }
        b.switchDebugLog.isChecked = AppPrefs.debugLog(this)
        b.switchDebugLog.setOnCheckedChangeListener { _, checked ->
            AppPrefs.setDebugLog(this, checked)
            com.dacweb.player.log.AppLog.refresh(this)
        }

        b.nightGroup.setOnCheckedChangeListener { _, id ->
            val mode = when (id) {
                R.id.nightLight -> AppPrefs.NIGHT_LIGHT
                R.id.nightDark -> AppPrefs.NIGHT_DARK
                else -> AppPrefs.NIGHT_SYSTEM
            }
            if (mode != AppPrefs.nightMode(this)) {
                AppPrefs.setNightMode(this, mode)
                applyNightMode(mode)
                recreate()
            }
        }

        if (startTab != 0) {
            b.tabs.getTabAt(startTab)?.select()
        }

        buildPalette()
        render()
        renderAudio()
    }

    override fun onResume() {
        super.onResume()
        renderAudio()
    }

    private fun bindAudioRates() {
        val ids = intArrayOf(R.id.rateFollow, R.id.rate441, R.id.rate48, R.id.rate96, R.id.rate192)
        val values = intArrayOf(0, 44100, 48000, 96000, 192000)
        val current = values.indexOf(AppPrefs.outputRate(this)).let { if (it < 0) 0 else it }
        b.audioPage.rgRate.check(ids[current])
        b.audioPage.rgRate.setOnCheckedChangeListener { _, checked ->
            val index = ids.indexOf(checked)
            if (index >= 0) {
                AppPrefs.setOutputRate(this, values[index])
                renderAudio()
            }
        }
    }

    private fun renderAudio() {
        val rate = AppPrefs.outputRate(this)
        b.audioPage.status.text = buildString {
            append(getString(R.string.audio_status_current, getString(R.string.audio_mode_default)))
            append('\n')
            append(
                getString(
                    R.string.audio_status_rate,
                    if (rate > 0) audioRateLabel(rate) else getString(R.string.audio_rate_follow_value)
                )
            )
            append('\n')
            append(getString(R.string.audio_status_abi, android.os.Build.SUPPORTED_ABIS.joinToString(", ")))
            append('\n')
            append(getString(R.string.audio_status_note))
        }
    }

    private fun audioRateLabel(hz: Int): String =
        if (hz % 1000 == 0) "${hz / 1000} kHz" else String.format(java.util.Locale.US, "%.1f kHz", hz / 1000.0)

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, b.tabs.selectedTabPosition)
    }

    private fun render() {
        b.valueQuality.text = Quality.current(this).label
        b.valueLyricSize.text = getString(R.string.lyric_size_item, AppPrefs.lyricSize(this))
        b.valueLyricColor.text = LyricPalette.labelIndex(this, AppPrefs.lyricColor(this))
        b.valueDir.text = AppPrefs.downloadDirLabel(this)
        b.valueConcurrency.text = getString(R.string.concurrency_item, AppPrefs.concurrency(this))
        b.valueCacheUsage.text = CacheManager.summary(this)
        b.valueImageMax.text = getString(R.string.cache_image_max_item, AppPrefs.imageCacheMb(this))
        b.valueFocusVisual.text = focusVisualLabel(AppPrefs.focusVisualMode(this))
        val mode = AppPrefs.nightMode(this)
        b.nightGroup.check(
            when (mode) {
                AppPrefs.NIGHT_LIGHT -> R.id.nightLight
                AppPrefs.NIGHT_DARK -> R.id.nightDark
                else -> R.id.nightSystem
            }
        )
    }

    private fun focusVisualLabel(mode: Int): String = getString(
        when (mode) {
            AppPrefs.FOCUS_ON -> R.string.focus_on
            AppPrefs.FOCUS_OFF -> R.string.focus_off
            else -> R.string.focus_auto
        }
    )

    private fun pickFocusVisual() {
        val modes = intArrayOf(AppPrefs.FOCUS_AUTO, AppPrefs.FOCUS_ON, AppPrefs.FOCUS_OFF)
        val labels = modes.map { focusVisualLabel(it) }.toTypedArray()
        val current = modes.indexOf(AppPrefs.focusVisualMode(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_focus_visual)
            .setSingleChoiceItems(labels, current) { d, which ->
                AppPrefs.setFocusVisualMode(this, modes[which])
                d.dismiss()
                // 焦点描边是主题级的，必须重建界面才生效
                recreate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickQuality() {
        val values = Quality.entries
        val labels = values.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.quality_title)
            .setSingleChoiceItems(labels, values.indexOf(Quality.current(this))) { d, which ->
                Quality.set(this, values[which])
                render()
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickLyricSize() {
        val sizes = (12..28 step 2).toList()
        val labels = sizes.map { getString(R.string.lyric_size_item, it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.lyric_size)
            .setSingleChoiceItems(labels, sizes.indexOf(AppPrefs.lyricSize(this)).coerceAtLeast(0)) { d, which ->
                AppPrefs.setLyricSize(this, sizes[which])
                render()
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickLyricColor() {
        val labels = resources.getStringArray(R.array.lyric_colors)
        AlertDialog.Builder(this)
            .setTitle(R.string.lyric_color)
            .setSingleChoiceItems(labels, AppPrefs.lyricColor(this)) { d, which ->
                AppPrefs.setLyricColor(this, which)
                render()
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickConcurrency() {
        val options = (1..6).map { getString(R.string.concurrency_item, it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.concurrency_title)
            .setSingleChoiceItems(options, AppPrefs.concurrency(this) - 1) { d, which ->
                AppPrefs.setConcurrency(this, which + 1)
                render()
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun clearCacheDialog() {
        val items = arrayOf(
            getString(R.string.cache_item_images),
            getString(R.string.cache_item_temps),
            getString(R.string.cache_item_all)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.cache_clear_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> CacheManager.clearImages(this)
                    1 -> CacheManager.clearTemps(this)
                    else -> CacheManager.clearAll(this)
                }
                render()
                Toast.makeText(this, getString(R.string.cache_cleared, items[which]), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickImageMax() {
        val sizes = intArrayOf(100, 200, 300, 500, 1024)
        val labels = sizes.map { getString(R.string.cache_image_max_item, it) }.toTypedArray()
        val idx = sizes.indexOf(AppPrefs.imageCacheMb(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.cache_image_max)
            .setSingleChoiceItems(labels, idx) { d, which ->
                AppPrefs.setImageCacheMb(this, sizes[which])
                CacheManager.applyLimit(this)
                render()
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun buildPalette() {
        val size = dp(44)
        val gap = dp(10)
        val current = AppPrefs.themeIndex(this)
        b.paletteRow.removeAllViews()
        paletteColors.forEachIndexed { index, colorRes ->
            val swatch = View(this)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginEnd = gap
            swatch.layoutParams = lp
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(this@SettingsActivity, colorRes))
            }
            if (index == current) {
                drawable.setStroke(
                    dp(3),
                    themeColor(com.google.android.material.R.attr.colorOnSurface)
                )
            }
            swatch.background = drawable
            swatch.contentDescription = paletteNames[index]
            swatch.setOnClickListener {
                AppPrefs.setThemeIndex(this, index)
                recreate()
            }
            b.paletteRow.addView(swatch)
        }
        b.paletteName.text = paletteNames.getOrElse(current) { "" } + " · 主题色"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val KEY_TAB = "settings_tab"
    }
}
