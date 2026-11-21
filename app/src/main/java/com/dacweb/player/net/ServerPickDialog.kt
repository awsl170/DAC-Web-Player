package com.dacweb.player.net

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dacweb.player.R
import com.dacweb.player.ui.focusButtons
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 点「服务器」图标先弹这个：自动搜局域网里的 SMB 服务器，选中一个就带着 IP 去填登录框；
 * 搜不到（或要连 FTP/SFTP/WebDAV）就按「手动填写」。
 *
 * 列表行是 focusable + clickable 的，遥控器方向键能选中、OK 键能确认。
 */
object ServerPickDialog {

    fun show(
        activity: ComponentActivity,
        onPick: (host: String) -> Unit,
        onManual: () -> Unit,
        onLongPick: ((host: String) -> Unit)? = null
    ) {
        val ctx = activity
        val dp = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(12), px(24), 0)
        }

        val status = TextView(ctx).apply {
            setText(R.string.smb_searching)
            textSize = 14f
            setPadding(0, 0, 0, px(8))
        }
        val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(4))
        }
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            addView(
                list,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }

        root.addView(status)
        root.addView(bar)
        root.addView(scroll)

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.net_pick_title)
            .setView(root)
            .setNegativeButton(R.string.net_cancel, null)
            .setNeutralButton(R.string.net_manual, null)
            .create()

        dialog.setOnShowListener {
            val d = dialog as AlertDialog
            d.focusButtons()
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                d.dismiss()
                onManual()
            }
            activity.lifecycleScope.launch {
                val found = withContext(Dispatchers.IO) { SmbDiscovery.discover(activity) }
                bar.visibility = View.GONE
                if (found.isEmpty()) {
                    status.setText(R.string.smb_none)
                    return@launch
                }
                status.text = ctx.getString(R.string.smb_found, found.size)
                list.removeAllViews()
                found.forEach { f ->
                    list.addView(row(ctx, f.host, f.title, onClick = {
                        d.dismiss()
                        onPick(f.host)
                    }, onLongClick = onLongPick?.let { cb ->
                        {
                            d.dismiss()
                            cb(f.host)
                        }
                    }))
                }
                // 遥控器：结果一出来就把焦点给第一个，直接按 OK 就能进
                // （触摸模式下 requestFocus 会被忽略，必须 requestFocusFromTouch）
                list.getChildAt(0)?.let { v ->
                    if (v.isInTouchMode) v.requestFocusFromTouch() else v.requestFocus()
                }
            }
        }
        dialog.show()
    }

    private fun row(
        ctx: android.content.Context,
        host: String,
        title: String,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)? = null
    ): View {
        val dp = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(12), px(12), px(12), px(12))
            isFocusable = true
            isClickable = true
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_focus)
            setOnLongClickListener {
                onLongClick?.invoke()
                onLongClick != null
            }
        }
        row.addView(
            ImageView(ctx).apply {
                setImageResource(R.drawable.ic_server)
                setPadding(px(8), px(8), px(8), px(8))
            },
            LinearLayout.LayoutParams(px(48), px(48))
        )
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), 0, 0, 0)
        }
        texts.addView(TextView(ctx).apply {
            text = title
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        })
        texts.addView(TextView(ctx).apply {
            text = if (title == host) ctx.getString(R.string.smb_row_sub_ip)
                   else ctx.getString(R.string.smb_row_sub, host)
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, android.R.color.darker_gray))
        })
        row.addView(texts)
        row.setOnClickListener { onClick() }
        return row
    }
}
