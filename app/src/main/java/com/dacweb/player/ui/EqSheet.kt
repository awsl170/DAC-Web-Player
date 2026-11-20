package com.dacweb.player.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.materialswitch.MaterialSwitch
import com.dacweb.player.R
import com.dacweb.player.audio.Eq

/**
 * 均衡器面板：开关 / 预设 / 5 段增益 / 低音增强 / 环绕。
 * 参数直接写入 [Eq]，由系统 audiofx 作用到 ExoPlayer 的音频会话上。
 */
class EqSheet(context: Context) : BottomSheetDialog(context) {

    private val density = context.resources.displayMetrics.density
    private val bandBars = mutableListOf<SeekBar>()

    init {
        val pad = (20 * density).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(context).apply {
            text = context.getString(R.string.equalizer)
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        if (!Eq.available) {
            root.addView(TextView(context).apply {
                text = context.getString(R.string.eq_unavailable)
                setPadding(0, pad / 2, 0, pad / 2)
            })
            setContentView(root)
        } else {
            buildPanel(context, root, pad)
        }
    }

    private fun buildPanel(context: Context, root: LinearLayout, pad: Int) {
        val density = this.density
        root.addView(MaterialSwitch(context).apply {
            text = context.getString(R.string.eq_enable)
            isChecked = Eq.enabled
            setOnCheckedChangeListener { _, checked -> Eq.setEnabled(checked) }
        })

        // 预设（首次回调是布局引起的，忽略掉，避免覆盖用户自定义的频段）
        var suppressPreset = true
        val range = Eq.bandLevelRange()
        val spinner = Spinner(context, Spinner.MODE_DROPDOWN).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                (0 until Eq.presetCount()).map { Eq.presetName(it) }
            )
            setSelection(Eq.currentPreset().coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, view: View?, position: Int, id: Long
                ) {
                    if (suppressPreset) {
                        suppressPreset = false
                        return
                    }
                    Eq.applyPreset(position)
                    if (range.size == 2) {
                        bandBars.forEachIndexed { i, bar ->
                            bar.progress = Eq.bandLevel(i) - range[0]
                        }
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        root.addView(label(context, context.getString(R.string.eq_preset), pad))
        root.addView(spinner, lp())

        if (range.size == 2) {
            for (i in 0 until Eq.bandCount) {
                val freq = Eq.bandCenterFreq(i)
                val freqText = if (freq >= 1000) "${freq / 1000}kHz" else "${freq}Hz"
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                row.addView(TextView(context).apply {
                    text = freqText
                    width = (72 * density).toInt()
                    textSize = 13f
                })
                val bar = SeekBar(context).apply {
                    max = range[1] - range[0]
                    progress = Eq.bandLevel(i) - range[0]
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                            if (fromUser) Eq.setBandLevel(i, progress + range[0])
                        }

                        override fun onStartTrackingTouch(sb: SeekBar?) {}
                        override fun onStopTrackingTouch(sb: SeekBar?) {}
                    })
                }
                bandBars += bar
                row.addView(bar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                root.addView(row, lp())
            }
        }

        root.addView(label(context, context.getString(R.string.eq_bass), pad))
        root.addView(slider(context, Eq.bassStrength) { Eq.setBass(it) })

        root.addView(label(context, context.getString(R.string.eq_virtualizer), pad))
        root.addView(slider(context, Eq.virtStrength) { Eq.setVirtualizer(it) })

        setContentView(root)
    }

    private fun lp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = (12 * density).toInt() }

    private fun label(ctx: Context, text: String, pad: Int) = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        alpha = 0.75f
        setPadding(0, pad / 2, 0, 0)
    }

    private fun slider(ctx: Context, initial: Int, onChange: (Int) -> Unit) = SeekBar(ctx).apply {
        max = 1000
        progress = initial
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }
}
