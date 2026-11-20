package com.dacweb.player

import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import com.dacweb.player.databinding.ActivityDacBinding
import com.dacweb.player.usb.DacSession
import com.dacweb.player.usb.FeatureUnit
import com.dacweb.player.usb.ParsedAudio
import com.dacweb.player.audio.DacPlayer
import com.dacweb.player.audio.PlayerHub
import com.dacweb.player.prefs.AppPrefs
import com.dacweb.player.usb.UacIso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * DAC / USB 音频探测页。
 *
 * 这一页只做“能问到什么”：是不是 UAC 设备、UAC1 还是 UAC2、有没有等时输出端点、
 * 能不能 force claim 把内核驱动摘掉、硬件音量范围与当前值、支持哪些采样率。
 * 真正的等时播放要等 native 层。
 */
class DacActivity : BaseActivity() {

    private lateinit var b: ActivityDacBinding
    private val manager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }

    private data class Probe(
        val device: UsbDevice,
        val audio: ParsedAudio,
        val uac2: Boolean,
        val claimed: Boolean,
        val feature: FeatureUnit?,
        val rates: List<Int>,
        val volumeRange: IntArray?,
        val volume: Int?,
        val sampleRate: Int?,
        val clockLabel: String?,
        val error: String? = null,
        val raw: ByteArray? = null,
        val claimedIfaces: List<Int> = emptyList()
    )

    private val probes = mutableMapOf<String, Probe>()
    private var streamStatusView: TextView? = null
    private var gainBar: SeekBar? = null
    private var gainLabel: TextView? = null
    private var logView: TextView? = null
    private var modeBtn: MaterialButton? = null
    private var depthBtn: MaterialButton? = null
    private var rateBtn: MaterialButton? = null
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            renderStreamStatus()
            ticker.postDelayed(this, 1000)
        }
    }
    private var pendingDevice: UsbDevice? = null
    private var pendingMode: Boolean? = false
    private var pendingStream = false
    /** 直通是不是这一页拉起来的：只有自己拉起来的才在退出时停，别把播放页的直通顺手杀了。 */
    private var startedHere = false

    private val permissionAction get() = "$packageName.USB_PERMISSION"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                permissionAction -> {
                    val device: UsbDevice? = if (android.os.Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device == null) return
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        if (pendingStream) {
                            pendingStream = false
                            startStream()
                        } else {
                            val mode = pendingMode
                            if (mode == null) runDirectTest(device) else launchProbe(device, mode)
                        }
                    } else {
                        snack(getString(R.string.dac_permission_denied))
                    }
                    pendingDevice = null
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> render()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDacBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }

        val filter = IntentFilter().apply {
            addAction(permissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        render()
        ticker.postDelayed(tick, 1000)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        if (startedHere) runCatching { DacPlayer.stop() }
        ticker.removeCallbacks(tick)
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 列表

    private fun render() {
        if (isFinishing || isDestroyed) return
        b.list.removeAllViews()
        val devices = manager.deviceList.values.toList()
        if (devices.isEmpty()) {
            b.list.addView(hint(getString(R.string.dac_empty)))
            return
        }
        b.list.addView(hint(getString(R.string.dac_hint)))
        devices.forEach { b.list.addView(buildCard(it)) }
    }

    private fun buildCard(device: UsbDevice): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            radius = dp(14).toFloat()
            setCardBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurfaceVariant))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        card.addView(box)

        val name = device.productName?.takeIf { it.isNotBlank() }
            ?: device.manufacturerName?.takeIf { it.isNotBlank() }
            ?: getString(R.string.dac_unknown_device)
        box.addView(text(name, 16f, true))
        box.addView(text("VID:%04X  PID:%04X".format(device.vendorId, device.productId), 12f, false))

        val probe = probes[device.deviceId.toString()]
        val status = TextView(this).apply {
            textSize = 12.5f
            setPadding(0, dp(8), 0, dp(4))
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        box.addView(status)
        status.text = when {
            probe == null -> getString(R.string.dac_not_probed)
            probe.error != null -> probe.error
            else -> describe(probe)
        }

        if (probe != null && probe.error == null) {
            val range = probe.volumeRange
            if (range != null) {
                box.addView(text(getString(R.string.dac_volume, DacSession.db(probe.volume ?: range[0])), 12.5f, false))
                val bar = SeekBar(this).apply {
                    max = (range[1] - range[0]).coerceAtLeast(1)
                    progress = ((probe.volume ?: range[1]) - range[0]).coerceIn(0, max)
                    setPadding(0, dp(4), 0, dp(4))
                }
                val label = text("", 12f, false)
                box.addView(bar)
                box.addView(label)
                label.text = getString(R.string.dac_volume, DacSession.db(range[0] + bar.progress))
                bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        label.text = getString(R.string.dac_volume, DacSession.db(range[0] + progress))
                    }

                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {
                        val value = range[0] + (sb?.progress ?: 0)
                        applyVolume(device, value)
                    }
                })
            }

            // 软件衰减滑杆已移到下面的「直通播放」卡片（百分比、右=更响），
            // 这里只保留 DAC 自己的硬件音量。

            val streams = probe.audio.isoStreams
            if (streams.isNotEmpty()) {
                box.addView(text("可用格式", 12.5f, false))
                streams.take(8).forEach { st ->
                    val rs = st.sampleRates.joinToString("/") { prettyRate(it) }
                    box.addView(
                        text(
                            "iface ${st.interfaceNumber} · alt ${st.alt} · ${st.channels}ch · " +
                                "${st.bitResolution}bit/${st.subframeSize}B · $rs",
                            11.5f, false
                        )
                    )
                }
            }
            val raw = probe.raw
            if (raw != null && raw.isNotEmpty()) {
                box.addView(text("原始描述符（前 160 字节）", 12.5f, false))
                val hex = raw.joinToString(" ") { "%02x".format(it) }
                box.addView(text(raw.take(160).joinToString(" ") { "%02x".format(it) }, 9.5f, false))
                val copy = MaterialButton(
                    this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = "复制完整描述符"
                    textSize = 12f
                    setOnClickListener {
                        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(
                            android.content.ClipData.newPlainText("usb-descriptor", hex)
                        )
                        snack("描述符已复制（${raw.size} 字节）")
                    }
                }
                box.addView(copy)
            }

            val rates = probe.rates
            if (rates.isNotEmpty()) {
                box.addView(text(getString(R.string.dac_rates), 12.5f, false))
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(4), 0, 0)
                }
                rates.take(8).forEach { hz ->
                    val chip = MaterialButton(
                        this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
                    ).apply {
                        text = if (hz % 1000 == 0) "${hz / 1000}k" else "${"%.1f".format(hz / 1000.0)}k"
                        textSize = 11f
                        minWidth = 0
                        minimumWidth = 0
                        insetTop = 0
                        insetBottom = 0
                        setPadding(dp(8), 0, dp(8), 0)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = dp(6) }
                        setOnClickListener { applyRate(device, hz) }
                    }
                    row.addView(chip)
                }
                box.addView(row)
            }
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        actions.addView(button(getString(R.string.dac_probe)) { ensurePermission(device, false) })
        actions.addView(button(getString(R.string.dac_exclusive)) { ensurePermission(device, true) })
        actions.addView(button(getString(R.string.dac_direct_test)) { ensurePermission(device, null) })
        box.addView(actions)

        // 独占开关：开了之后普通播放页就直通输出，不用一直待在这个页面
        val routeOn = AppPrefs.dacExclusive(this)
        val routeBtn = MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = getString(if (routeOn) R.string.dac_route_toggle_on else R.string.dac_route_toggle_off)
            textSize = 12f
            setOnClickListener {
                if (AppPrefs.dacExclusive(this@DacActivity)) {
                    DacPlayer.softStop()
                    AppPrefs.setDacExclusive(this@DacActivity, false)
                    snack(getString(R.string.dac_route_off))
                    render()
                } else {
                    ensurePermission(device, true)
                }
            }
        }
        box.addView(routeBtn)
        if (routeOn) box.addView(text(getString(R.string.dac_route_on), 11.5f, false))

        val streamRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        val btnStream = MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = getString(R.string.dac_stream_play)
            textSize = 12f
            setOnClickListener { streamPlay() }
        }
        val btnStreamStop = MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = getString(R.string.dac_stream_stop)
            textSize = 12f
            setOnClickListener {
                // 先退出独占再停流：否则 PlaybackService 里 500ms 的 DacRoute.sync 会立刻把它又拉起来，
                // 两个线程同时关同一条 USB 流，真机上直接把 USB 子系统搞崩（设备重启）。
                AppPrefs.setDacExclusive(this@DacActivity, false)
                com.dacweb.player.audio.DacRoute.stop(this@DacActivity, null)
                renderStreamStatus()
                snack(getString(R.string.dac_stream_stopped))
            }
        }
        streamRow.addView(btnStream, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        streamRow.addView(btnStreamStop, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(streamRow)
        streamStatusView = text(streamText(), 11.5f, false)
        box.addView(streamStatusView)

        // 直通音量：DAC 有硬件音量就写 FEATURE_UNIT，否则退回软件衰减；拖了立刻生效，不需要重启直通。
        // 放在直通卡片里是因为直通播放只在这一页存在，探测卡片不一定有设备。
        val startPct = AppPrefs.dacVolumePercent(this)
        gainLabel = text(volumeText(startPct), 12f, false)
        box.addView(gainLabel)
        gainBar = SeekBar(this).apply {
            max = 100
            progress = startPct
            setPadding(0, dp(4), 0, dp(4))
        }
        box.addView(gainBar)
        gainBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                // 只改音量和文字，绝不重建列表：重建会在触摸回调里销毁当前 SeekBar 导致崩溃
                if (!fromUser) return
                gainLabel?.text = volumeText(progress)
                DacPlayer.applyVolumePercent(this@DacActivity, progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                val p = sb?.progress ?: 100
                AppPrefs.setDacVolumePercent(this@DacActivity, p)
                DacPlayer.applyVolumePercent(this@DacActivity, p)
            }
        })

        val optRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modeBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            textSize = 12f
            setOnClickListener { cycleMode() }
        }
        box.addView(modeBtn)
        depthBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            textSize = 12f
            setOnClickListener { cycleDepth() }
        }
        rateBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            textSize = 12f
            setOnClickListener { cycleRate() }
        }
        optRow.addView(depthBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        optRow.addView(rateBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(optRow)
        renderOptions()

        box.addView(
            button(getString(R.string.dac_export_log)) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val body = DacPlayer.logText().ifEmpty { getString(R.string.dac_log_empty) }
                cm.setPrimaryClip(ClipData.newPlainText("dac-log", body))
                snack(getString(R.string.dac_log_copied))
            }
        )
        logView = text(getString(R.string.dac_log_empty), 9f, false).also {
            it.typeface = android.graphics.Typeface.MONOSPACE
            it.setTextIsSelectable(true)
        }
        box.addView(logView)

        return card
    }

    private fun describe(p: Probe): String {
        val sb = StringBuilder()
        sb.append(if (p.uac2) "UAC2" else "UAC1")
        p.sampleRate?.let { sb.append(" · 当前 ").append(prettyRate(it)) }
        p.clockLabel?.let { sb.append(" · ").append(it) }
        sb.append('\n')

        val fu = p.feature
        if (fu == null) {
            sb.append(getString(R.string.dac_no_feature_unit))
        } else {
            sb.append("Feature Unit #").append(fu.id)
            if (fu.channels > 0) sb.append(" · ").append(fu.channels).append(" 声道")
            val r = p.volumeRange
            if (r != null) {
                sb.append(" · 音量 ").append(DacSession.db(r[0])).append(" ~ ").append(DacSession.db(r[1]))
                if (r.size > 2 && r[2] > 0) sb.append(" · 步进 ").append(DacSession.db(r[2]))
                sb.append(" · 当前 ").append(DacSession.db(p.volume ?: r[1]))
            } else {
                sb.append(" · 不支持硬件音量")
            }
        }
        sb.append('\n')

        val iso = p.audio.isoStreams.firstOrNull()
        val ep = iso?.endpoint
        if (iso != null && ep != null) {
            sb.append(getString(R.string.dac_iso_ok))
            sb.append(" · 包 ").append(ep.maxPacket).append(" B")
            sb.append(" · 间隔 ").append(ep.interval)
        } else {
            sb.append(getString(R.string.dac_iso_missing))
        }
        sb.append('\n')

        sb.append(
            when {
                p.claimed -> getString(R.string.dac_claim_ok)
                p.error == null -> getString(R.string.dac_claim_pending)
                else -> getString(R.string.dac_claim_fail)
            }
        )
        return sb.toString()
    }

    private fun prettyRate(hz: Int): String =
        if (hz % 1000 == 0) "${hz / 1000} kHz" else String.format(java.util.Locale.US, "%.1f kHz", hz / 1000.0)

    // ---------------------------------------------------------------- 权限与探测

    private fun ensurePermission(device: UsbDevice, exclusive: Boolean?) {
        pendingDevice = device
        pendingMode = exclusive
        if (manager.hasPermission(device)) {
            if (exclusive == null) runDirectTest(device) else launchProbe(device, exclusive)
        } else {
            val intent = Intent(permissionAction).setPackage(packageName)
            val flags = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            manager.requestPermission(device, PendingIntent.getBroadcast(this, 0, intent, flags))
        }
    }

    private fun launchProbe(device: UsbDevice, exclusive: Boolean) {
        snack(getString(R.string.dac_probing))
        lifecycleScope.launch {
            // 探测前先把可能正在跑的直通停掉：两个 UsbDeviceConnection 同时 claim 同一台设备
            // 是内核 USB 栈崩掉（表现为"授权后卡死、设备重启"）的主要诱因。
            runCatching { DacPlayer.softStop() }
            // 探测一律按"不接管"跑：只读描述符 + 控制请求，绝不在这一页 force claim。
            val probe = withContext(Dispatchers.IO) { doProbe(device, false) }
            probes[device.deviceId.toString()] = probe
            render()
            if (exclusive) {
                // 记住"用户想开独占"：真正的接口接管推迟到开始播放的那一刻（DacPlayer.start → claimStream）
                AppPrefs.setDacExclusive(this@DacActivity, probe.error == null)
                val msg = if (probe.error == null)
                    getString(R.string.dac_exclusive_ok)
                else getString(R.string.dac_exclusive_fail)
                snack(msg)
            }
        }
    }

    private fun doProbe(device: UsbDevice, exclusive: Boolean): Probe {
        if (!manager.hasPermission(device)) {
            return Probe(
                device, ParsedAudio(emptyList(), emptyList(), emptyList(), emptyList()), false, false,
                null, emptyList(), null, null, null, null, getString(R.string.dac_permission_denied)
            )
        }
        val session = runCatching { DacSession.open(this, device, exclusive) }.getOrNull()
            ?: return Probe(
                device, ParsedAudio(emptyList(), emptyList(), emptyList(), emptyList()), false, false,
                null, emptyList(), null, null, null, null,
                getString(R.string.dac_busy_or_audio)
            )
        return session.use {
            Probe(
                device = device,
                audio = it.parsed,
                uac2 = it.uac2,
                claimed = it.exclusiveClaimed,
                feature = it.featureUnit,
                rates = it.rates.ifEmpty { DacSession.COMMON_RATES },
                volumeRange = it.volumeRange,
                volume = it.currentVolume,
                sampleRate = it.samplingRate,
                clockLabel = it.clockLabel,
                error = null,
                raw = it.rawConfig,
                claimedIfaces = it.claimedIfaces
            )
        }
    }

    private fun applyVolume(device: UsbDevice, value: Int) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    DacSession.open(this@DacActivity, device)?.use { it.setVolume(value) } ?: false
                }.getOrDefault(false)
            }
            if (ok) {
                probes[device.deviceId.toString()]?.let { p ->
                    probes[device.deviceId.toString()] = p.copy(volume = value)
                }
                snack(getString(R.string.dac_volume_set, DacSession.db(value)))
            } else {
                snack(getString(R.string.dac_volume_failed))
            }
        }
    }

    private fun applyRate(device: UsbDevice, hz: Int) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    DacSession.open(this@DacActivity, device)?.use { it.setSampleRate(hz) } ?: false
                }.getOrDefault(false)
            }
            if (ok) {
                probes[device.deviceId.toString()]?.let { p ->
                    probes[device.deviceId.toString()] = p.copy(sampleRate = hz)
                }
                render()
                snack(getString(R.string.dac_rate_set, prettyRate(hz)))
            } else {
                snack(getString(R.string.dac_rate_failed))
            }
        }
    }

    // ---------------------------------------------------------------- 直通测试

    private fun runDirectTest(device: UsbDevice) {
        snack(getString(R.string.dac_direct_starting))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { directTest(device) }
            render()
            snack(result)
        }
    }

    private fun directTest(device: UsbDevice): String {
        if (!UacIso.available()) return getString(R.string.dac_native_missing)
        val session = runCatching { DacSession.open(this, device, exclusive = true) }.getOrNull()
            ?: return getString(R.string.dac_open_failed)
        session.use { s ->
            val params = s.streamParams() ?: return getString(R.string.dac_no_stream)
            val claimed = s.claimStream()
            val rate = s.pickRate(48_000) ?: s.samplingRate ?: 48_000
            runCatching { s.setSampleRate(rate) }
            // 测试音很吵：先把 DAC 硬件音量压到 -30 dB（若支持），避免一开声就炸耳朵
            val vr = s.volumeRange
            if (vr != null && vr.size >= 2) {
                val target = (vr[1] - 30 * 256).coerceIn(vr[0], vr[1])
                runCatching { s.setVolume(target) }
            }
            UacIso.setGain(Math.pow(10.0, -AppPrefs.dacGainDb(this) / 20.0).toFloat())
            val rc = runCatching {
                UacIso.nativeStart(
                    s.fileDescriptor, params.iface, params.alt, params.endpoint,
                    rate, params.channels, params.bps, 8, params.maxPacket
                )
            }.getOrElse { -1 }
            if (rc != 0) {
                return getString(R.string.dac_direct_failed, rc, claimed)
            }
            Thread.sleep(1500)
            val st = UacIso.stats()
            UacIso.stop()
            return getString(
                R.string.dac_direct_done, st.getOrElse(0) { 0 }, st.getOrElse(1) { 0 },
                st.getOrElse(2) { 0 }, params.alt, params.bps * 8, params.channels, rate
            )
        }
    }

    // ---------------------------------------------------------------- 直通播放

    private fun streamText(): String {
        val err = DacPlayer.error
        return when {
            err != null -> getString(R.string.dac_stream_error, err)
            DacPlayer.isPlaying -> getString(
                R.string.dac_stream_playing, fmtMs(DacPlayer.positionMs()), fmtMs(DacPlayer.durationMs)
            )
            DacPlayer.started -> getString(R.string.dac_stream_paused)
            else -> getString(R.string.dac_stream_idle)
        }
    }

    private fun renderOptions() {
        val perfect = AppPrefs.dacBitPerfect(this)
        modeBtn?.text = getString(
            if (perfect) R.string.dac_mode_bitperfect else R.string.dac_mode_compat
        )
        depthBtn?.text = if (perfect) {
            getString(R.string.dac_options_follow)
        } else {
            getString(R.string.dac_depth, AppPrefs.dacDepth(this))
        }
        val r = AppPrefs.dacRate(this)
        rateBtn?.text = if (perfect || r <= 0) {
            getString(R.string.dac_rate_follow)
        } else {
            getString(R.string.dac_rate_hz, r)
        }
        // 完全直通下位深/采样率必须跟源文件，按钮只是展示，点一下给提示
        depthBtn?.alpha = if (perfect) 0.5f else 1f
        rateBtn?.alpha = if (perfect) 0.5f else 1f
    }

    /** 完全直通 ⇄ 兼容转换。切换后下一次「直通播放」生效。 */
    private fun cycleMode() {
        AppPrefs.setDacBitPerfect(this, !AppPrefs.dacBitPerfect(this))
        renderOptions()
        snack(getString(R.string.dac_mode_switch))
    }

    private fun cycleDepth() {
        if (AppPrefs.dacBitPerfect(this)) {
            snack(getString(R.string.dac_mode_switch))
            return
        }
        val next = when (AppPrefs.dacDepth(this)) {
            16 -> 24
            24 -> 32
            else -> 16
        }
        AppPrefs.setDacDepth(this, next)
        renderOptions()
        snack(getString(R.string.dac_depth_applied, next))
    }

    private fun cycleRate() {
        if (AppPrefs.dacBitPerfect(this)) {
            snack(getString(R.string.dac_mode_switch))
            return
        }
        val order = intArrayOf(0, 44100, 48000, 96000, 192000)
        val cur = AppPrefs.dacRate(this)
        val idx = order.indexOf(cur).let { if (it < 0) 0 else it }
        val next = order[(idx + 1) % order.size]
        AppPrefs.setDacRate(this, next)
        renderOptions()
        snack(if (next <= 0) getString(R.string.dac_rate_follow) else getString(R.string.dac_rate_hz, next))
    }

    private fun renderStreamStatus() {
        streamStatusView?.text = streamText()
        logView?.text = DacPlayer.logText().ifEmpty { getString(R.string.dac_log_empty) }
    }

    // ---------------------------------------------------------------- 直通音量

    private fun volumeText(percent: Int): String {
        val hw = DacPlayer.hardwareVolume() || com.dacweb.player.usb.DacSession.probeHardwareVolume(this)
        val res = if (hw) R.string.dac_sheet_volume_hw else R.string.dac_sheet_volume_sw
        return getString(res, percent.coerceIn(0, 100))
    }

    /** 音量键 / 滑杆共用的落地逻辑：存偏好、写硬件或软件音量、同步滑块与文字。 */
    private fun applyVolumePct(percent: Int) {
        val p = percent.coerceIn(0, 100)
        AppPrefs.setDacVolumePercent(this, p)
        DacPlayer.applyVolumePercent(this, p)
        gainLabel?.text = volumeText(p)
        gainBar?.progress = p
    }

    /** 直通播放时把音量键抢过来调直通音量（系统音量对 USB 直通无效）。 */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val up = event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP
        val down = event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
        if ((up || down) && event.action == android.view.KeyEvent.ACTION_DOWN && DacPlayer.started) {
            val cur = AppPrefs.dacVolumePercent(this)
            applyVolumePct(if (up) cur + 5 else cur - 5)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun streamPlay() {
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        val device = DacPlayer.audioDevice(usb)
        if (device == null) {
            snack(getString(R.string.dac_no_device))
            return
        }
        if (!usb.hasPermission(device)) {
            // 之前必须先去点「检测」才能拿到权限，现在点直通会自己弹系统授权框
            pendingStream = true
            pendingDevice = device
            val intent = Intent(permissionAction).setPackage(packageName)
            val flags = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            usb.requestPermission(device, PendingIntent.getBroadcast(this, 0, intent, flags))
            snack(getString(R.string.dac_permission_asking))
            return
        }
        startStream()
    }

    private fun startStream() {
        lifecycleScope.launch {
            var c = PlayerHub.controller
            if (c == null) {
                PlayerHub.connect(this@DacActivity) { }
                kotlinx.coroutines.delay(900)
                c = PlayerHub.controller
            }
            val uri = c?.currentMediaItem?.localConfiguration?.uri?.toString()
            if (uri.isNullOrEmpty()) {
                snack(getString(R.string.dac_stream_nourl))
                return@launch
            }
            c?.pause()
            snack(getString(R.string.dac_stream_starting))
            val err = withContext(Dispatchers.IO) { DacPlayer.start(this@DacActivity, uri) }
            startedHere = err == null
            snack(if (err == null) getString(R.string.dac_stream_started) else getString(R.string.dac_stream_failed, err))
            renderStreamStatus()
        }
    }

    private fun fmtMs(ms: Long): String {
        if (ms <= 0) return "0:00"
        val s = ms / 1000
        return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60)
    }

    // ---------------------------------------------------------------- 小工具

    private fun text(value: String, size: Float, bold: Boolean): TextView = TextView(this).apply {
        text = value
        textSize = size
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
    }

    private fun hint(value: String): TextView = text(value, 13f, false).apply {
        setPadding(0, dp(10), 0, dp(10))
        setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
    }

    private fun button(label: String, action: () -> Unit): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            textSize = 12f
            insetTop = 0
            insetBottom = 0
            minWidth = 0
            minimumWidth = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            setOnClickListener { action() }
        }

    private fun snack(message: String) {
        Snackbar.make(b.list, message, Snackbar.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
