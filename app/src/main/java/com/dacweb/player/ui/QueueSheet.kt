package com.dacweb.player.ui

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.graphics.Typeface
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dacweb.player.R
import com.dacweb.player.audio.PlayQueue
import com.dacweb.player.model.Track

/** 播放队列面板：显示当前队列，点击任意一首直接切过去。 */
class QueueSheet(context: Context, private val onPick: (Int) -> Unit) : Dialog(context) {

    private val d = context.resources.displayMetrics.density

    init {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_dialog)
            setPadding((20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt(), (12 * d).toInt())
        }
        root.addView(TextView(context).apply {
            text = context.getString(R.string.play_queue)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })

        val items = PlayQueue.snapshot()
        val current = PlayQueue.currentIndex

        if (items.isEmpty()) {
            root.addView(TextView(context).apply {
                text = context.getString(R.string.queue_playing_empty)
                setPadding(0, (16 * d).toInt(), 0, (16 * d).toInt())
            })
            setContentView(root)
            setupWindow()
        } else {
            buildList(context, root, items, current)
            setupWindow()
        }
    }

    /** 电视上没有手势，底部弹层很难用：改成屏幕中央的一个窗口，遥控器上下就能选 */
    private fun setupWindow() {
        val dm = context.resources.displayMetrics
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.CENTER)
            setLayout((minOf(dm.widthPixels * 0.72f, 620 * d)).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun buildList(context: Context, root: LinearLayout, items: List<Track>, current: Int) {
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        items.forEachIndexed { i, t -> list.addView(row(context, i, t, i == current)) }

        val screenH = context.resources.displayMetrics.heightPixels
        val listH = minOf((420 * d).toInt(), (screenH * 0.6f).toInt())
        root.addView(ScrollView(context).apply { addView(list) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, listH
        ))
        setContentView(root)
    }

    private fun row(ctx: Context, index: Int, t: Track, isCurrent: Boolean): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (12 * d).toInt(), 0, (12 * d).toInt())
            setBackgroundResource(R.drawable.bg_focus)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dismiss()
                onPick(index)
            }
        }
        row.addView(TextView(ctx).apply {
            text = t.name
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(
                if (isCurrent) themeColor(ctx, com.google.android.material.R.attr.colorPrimary)
                else themeColor(ctx, com.google.android.material.R.attr.colorOnSurface)
            )
            if (isCurrent) setTypeface(typeface, Typeface.BOLD)
        })
        val sub = t.artist.ifBlank { t.album }
        row.addView(TextView(ctx).apply {
            text = if (isCurrent) "$sub · ${ctx.getString(R.string.playing_now)}" else sub
            textSize = 12f
            alpha = 0.7f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, (2 * d).toInt(), 0, 0)
        })
        return row
    }

    private fun selectableBackground(): Int {
        val tv = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return tv.resourceId
    }

    private fun themeColor(ctx: Context, attr: Int): Int {
        val tv = android.util.TypedValue()
        ctx.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }
}
