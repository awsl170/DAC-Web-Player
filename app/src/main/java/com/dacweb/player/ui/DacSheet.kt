package com.dacweb.player.ui

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.dacweb.player.PlayerActivity
import com.dacweb.player.R
import com.dacweb.player.audio.DacPlayer
import com.dacweb.player.audio.DacRoute
import com.dacweb.player.audio.PlayerHub
import com.dacweb.player.prefs.AppPrefs

/**
 * 播放页顶栏「DAC」小框点开的面板。
 *
 * 三种状态（和顶栏小框的颜色对应）：
 * - 没插 DAC：面板只说"未检测到设备"（顶栏小框直接不显示）
 * - 插了但没开独占（黑色）：只显示设备信息 + "开启独占"
 * - 开了独占（蓝色）：设备信息 + 输出模式/位深/采样率/音量 + "退出独占"
 * - 出错（红色）：错误原因单独一行红字
 *
 * 面板内容整体重建（[render]）而不是逐个改控件状态——之前切了模式下面对应的位深/采样率
 * 按钮的可用状态不刷新，得关掉面板重开才生效，就是只改一次 `isEnabled` 的后果。
 */
object DacSheet {

    /** 当前正在显示的底部面板：连点几次不能叠出好几个（每个都会开 USB 会话，越叠越慢）。 */
    private var current: BottomSheetDialog? = null

    fun show(activity: PlayerActivity) {
        if (current?.isShowing == true) return
        val dialog = BottomSheetDialog(activity)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), dp(activity, 20))
        }
        val scroll = ScrollView(activity).apply { addView(box) }
        dialog.setContentView(scroll)

        fun render() {
            box.removeAllViews()
            fill(activity, box) { render() }
        }
        render()
        dialog.setOnDismissListener { if (current === dialog) current = null }
        current = dialog
        dialog.show()
    }

    private fun fill(
        activity: PlayerActivity,
        box: LinearLayout,
        refresh: () -> Unit
    ) {
        val ctx: Context = activity
        box.addView(TextView(activity).apply {
            text = activity.getString(R.string.dac_sheet_title)
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(ctx, 6), 0, dp(ctx, 8))
        })

        val device = DacRoute.device(ctx)
        if (device == null) {
            box.addView(line(ctx, activity.getString(R.string.dac_sheet_no_device), 13f))
            return
        }

        val name = runCatching { device.productName }.getOrNull().orEmpty()
            .ifBlank { device.deviceName }
        box.addView(
            line(
                ctx,
                activity.getString(
                    R.string.dac_sheet_device,
                    "$name（%04X:%04X）".format(device.vendorId, device.productId)
                ),
                12.5f
            )
        )
        box.addView(line(ctx, stateText(ctx), 12.5f))

        // 错误单独一行红字：顶栏小框此时是红的，这里的文案要说清是什么错
        DacRoute.failureText()?.let { err ->
            box.addView(line(ctx, activity.getString(R.string.dac_sheet_error, err), 12.5f).apply {
                setTextColor(color(ctx, com.google.android.material.R.attr.colorError))
            })
        }

        val exclusive = AppPrefs.dacExclusive(ctx) && DacRoute.hasPermission(ctx)
        if (!exclusive) {
            // 黑色状态：只有设备信息和这一个开关
            box.addView(button(ctx, activity.getString(R.string.dac_sheet_enable)) {
                // 先确认 USB 权限，再开独占。顺序很重要：
                // 没有权限时绝不能去 open/claim 设备（会立刻报错，甚至把内核驱动拽崩）。
                fun enable() {
                    AppPrefs.setDacExclusive(ctx, true)
                    // 立刻按当前进度起一条直通（播放服务里的 500ms 循环也会兜底）
                    PlayerHub.controller?.let { c ->
                        DacRoute.restart(ctx, c)
                        if (!c.isPlaying) c.play()
                    }
                    refresh()
                }
                if (DacRoute.hasPermission(ctx)) {
                    enable()
                } else {
                    activity.requestDacPermission { granted ->
                        if (granted) {
                            enable()
                        } else {
                            android.widget.Toast.makeText(
                                ctx, activity.getString(R.string.dac_permission_denied),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            refresh()
                        }
                    }
                }
            }, rowParams(ctx))
            return
        }

        // 输出模式：完全直通 / 兼容
        box.addView(button(ctx, modeText(ctx)) {
            AppPrefs.setDacBitPerfect(ctx, !AppPrefs.dacBitPerfect(ctx))
            reopen(activity)
            refresh()
        }, rowParams(ctx))

        // 位深 / 采样率：只有兼容模式才允许改（直通模式跟源文件走）
        val bitPerfect = AppPrefs.dacBitPerfect(ctx)
        box.addView(button(ctx, activity.getString(R.string.dac_sheet_depth) + "：" + depthText(ctx)) {
            if (AppPrefs.dacBitPerfect(ctx)) return@button
            val next = when (AppPrefs.dacDepth(ctx)) { 16 -> 24; 24 -> 32; else -> 16 }
            AppPrefs.setDacDepth(ctx, next)
            reopen(activity)
            refresh()
        }.apply { enabled(!bitPerfect) }, rowParams(ctx))

        box.addView(button(ctx, activity.getString(R.string.dac_sheet_rate) + "：" + rateText(ctx)) {
            if (AppPrefs.dacBitPerfect(ctx)) return@button
            val next = when (AppPrefs.dacRate(ctx)) {
                0 -> 44_100
                44_100 -> 48_000
                48_000 -> 96_000
                96_000 -> 192_000
                else -> 0
            }
            AppPrefs.setDacRate(ctx, next)
            reopen(activity)
            refresh()
        }.apply { enabled(!bitPerfect) }, rowParams(ctx))

        box.addView(button(ctx, activity.getString(R.string.dac_sheet_buffer) + "：" + bufferText(ctx)) {
            val next = when (AppPrefs.dacBufferKb(ctx)) {
                256 -> 512
                512 -> 1024
                1024 -> 2048
                2048 -> 4096
                else -> 256
            }
            AppPrefs.setDacBufferKb(ctx, next)
            reopen(activity)
            refresh()
        }, rowParams(ctx))

        // 音量：右 = 更响。DAC 有 FEATURE_UNIT 音量就写硬件，否则退回软件衰减
        val volText = line(ctx, volumeText(ctx), 12.5f).apply {
            setPadding(0, dp(ctx, 12), 0, dp(ctx, 2))
        }
        box.addView(volText)
        box.addView(SeekBar(activity).apply {
            max = 100
            progress = AppPrefs.dacVolumePercent(ctx)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    DacPlayer.applyVolumePercent(ctx, value)
                    volText.text = volumeText(ctx)
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit

                override fun onStopTrackingTouch(bar: SeekBar) {
                    AppPrefs.setDacVolumePercent(ctx, bar.progress)
                    volText.text = volumeText(ctx)
                }
            })
        })

        box.addView(button(ctx, activity.getString(R.string.dac_sheet_off)) {
            // 退出独占：先把偏好关掉（让 500ms 同步循环别再启动直通），再淡出停流、恢复系统播放音量
            AppPrefs.setDacExclusive(ctx, false)
            DacRoute.stop(ctx, PlayerHub.controller)
            refresh()
        }, rowParams(ctx))
    }

    // ---- 文案 ----

    private fun stateText(ctx: Context): String {
        if (!AppPrefs.dacExclusive(ctx)) return ctx.getString(R.string.dac_sheet_state_idle)
        val text = DacRoute.label(ctx) ?: return ctx.getString(R.string.dac_sheet_state_idle)
        return ctx.getString(R.string.dac_sheet_state, text.removePrefix("DAC 独占 · "))
    }

    private fun modeText(ctx: Context): String =
        ctx.getString(R.string.dac_sheet_mode) + "：" + ctx.getString(
            if (AppPrefs.dacBitPerfect(ctx)) R.string.dac_mode_bitperfect else R.string.dac_mode_compat
        )

    private fun depthText(ctx: Context): String =
        if (AppPrefs.dacBitPerfect(ctx)) ctx.getString(R.string.dac_options_follow)
        else ctx.getString(R.string.dac_depth, AppPrefs.dacDepth(ctx))

    private fun rateText(ctx: Context): String {
        val r = AppPrefs.dacRate(ctx)
        return if (AppPrefs.dacBitPerfect(ctx) || r <= 0) ctx.getString(R.string.dac_rate_follow)
        else ctx.getString(R.string.dac_rate_hz, r)
    }

    private fun bufferText(ctx: Context): String {
        val kb = AppPrefs.dacBufferKb(ctx)
        return ctx.getString(R.string.dac_sheet_buffer_value, kb)
    }

    private fun volumeText(ctx: Context): String {
        val p = AppPrefs.dacVolumePercent(ctx)
        return ctx.getString(if (hardware(ctx)) R.string.dac_sheet_volume_hw else R.string.dac_sheet_volume_sw, p)
    }

    /**
     * 只用"已经知道"的结论，绝不在 UI 线程上开 USB 会话：
     * 以前这里会 probeHardwareVolume()，等于每次重绘面板都去抢 USB 门闩（最多 1.2s），
     * 所以打开面板慢、切模式也跟着卡。
     */
    private fun hardware(ctx: Context): Boolean =
        DacPlayer.hardwareVolume() ||
            com.dacweb.player.usb.DacSession.lastHasHardwareVolume == true

    /** 改了模式/位深/采样率之后按当前进度重开直通（旧的会话参数不会热更新）。 */
    private fun reopen(activity: PlayerActivity) {
        val c = PlayerHub.controller ?: return
        val uri = c.currentMediaItem?.localConfiguration?.uri?.toString() ?: return
        val pos = c.currentPosition.coerceAtLeast(0L)
        val app = activity.applicationContext
        Thread {
            DacPlayer.softStop()
            DacPlayer.start(app, uri, pos)
        }.apply { name = "dac-sheet-reopen"; start() }
    }

    // ---- 小工具 ----

    private fun line(ctx: Context, text: String, size: Float): TextView = TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(color(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant))
    }

    private fun button(ctx: Context, text: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            textSize = 13f
            minHeight = 0
            setOnClickListener { onClick() }
        }

    /** 禁用态同时把透明度调低，光看边框分不清能不能点。 */
    private fun MaterialButton.enabled(on: Boolean) {
        isEnabled = on
        alpha = if (on) 1f else 0.5f
    }

    private fun rowParams(ctx: Context) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(ctx, 6) }

    /** DacSheet 不是 Activity 子类，拿不到 BaseActivity.themeColor，这里自己解一个主题色。 */
    private fun color(ctx: Context, attr: Int): Int {
        val tv = android.util.TypedValue()
        val ok = ctx.theme.resolveAttribute(attr, tv, true)
        if (!ok) return android.graphics.Color.GRAY
        return if (tv.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(ctx, tv.resourceId)
        } else tv.data
    }

    private fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()
}
