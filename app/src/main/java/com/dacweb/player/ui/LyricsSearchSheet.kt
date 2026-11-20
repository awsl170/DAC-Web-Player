package com.dacweb.player.ui

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.dacweb.player.R
import com.dacweb.player.plugin.Providers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 手动换歌词：向所有启用插件要候选，列出后选中回调 LRC 文本。
 */
class LyricsSearchSheet(
    private val ctx: Context,
    private val title: String,
    private val artist: String,
    private val durationMs: Long,
    private val onPick: (String, String) -> Unit
) : BottomSheetDialog(ctx) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var list: LinearLayout

    init {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(12))
        }
        root.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.lyric_search_title)
            textSize = 16f
            setPadding(dp(24), dp(12), dp(24), dp(6))
            setTextColor(Colors.attrColor(ctx, com.google.android.material.R.attr.colorOnSurface))
        })
        list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(ctx).apply { addView(list) }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (ctx.resources.displayMetrics.heightPixels * 0.55).toInt()
        )
        root.addView(scroll, params)
        setContentView(root)
        load()
    }

    override fun onStop() {
        super.onStop()
        scope.cancel()
    }

    private fun load() {
        scope.launch {
            val progress = ProgressBar(ctx).apply { isIndeterminate = true }
            list.addView(progress, LinearLayout.LayoutParams(-1, dp(48)))
            var count = 0

            val candidates = withContext(Dispatchers.IO) {
                runCatching { Providers.searchLyrics(ctx, title, artist, durationMs) }.getOrDefault(emptyList())
            }
            candidates.forEach { c ->
                addRow(c.source, c.title, c.artist) { c.text }
                count++
            }
            list.removeView(progress)
            if (count == 0) {
                list.addView(TextView(ctx).apply {
                    text = ctx.getString(R.string.lyric_search_empty)
                    setPadding(dp(24), dp(18), dp(24), dp(18))
                    alpha = 0.7f
                })
            }
        }
    }

    private fun addRow(source: String, song: String, artists: String, load: suspend () -> String?) {
        val label = "$source · $song" + if (artists.isBlank()) "" else " - $artists"
        val row = TextView(ctx).apply {
            text = label
            textSize = 15f
            gravity = Gravity.START
            setPadding(dp(24), dp(14), dp(24), dp(14))
            setTextColor(Colors.attrColor(ctx, com.google.android.material.R.attr.colorOnSurface))
        }
        row.setOnClickListener {
            row.isEnabled = false
            row.text = "$label …"
            scope.launch {
                val text = runCatching { load() }.getOrNull()
                if (!text.isNullOrBlank()) {
                    dismiss()
                    onPick(text, source)
                } else {
                    row.isEnabled = true
                    row.text = label
                }
            }
        }
        list.addView(row)
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
}

private object Colors {
    fun attrColor(ctx: Context, attr: Int): Int {
        val tv = android.util.TypedValue()
        ctx.theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) androidx.core.content.ContextCompat.getColor(ctx, tv.resourceId) else tv.data
    }
}
