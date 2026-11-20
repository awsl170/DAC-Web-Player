package com.dacweb.player.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.dacweb.player.log.AppLog
import java.io.Closeable
import java.util.concurrent.TimeUnit

/**
 * 一个已打开的 UAC 设备会话。
 *
 * 能做的事（全部不需要 root）：
 *  - 读配置描述符，拿到真实的 alt setting / 位深 / 声道 / 采样率表 / Feature Unit / Clock Source
 *  - force claim 把内核 snd-usb-audio 摘掉（独占的前提）
 *  - 读/写硬件音量（UAC2 与 UAC1 都是 1/256 dB 单位）
 *  - 读/写采样率（UAC2 写 Clock Source，UAC1 写流端点）
 */
class DacSession private constructor(
    val device: UsbDevice,
    val parsed: ParsedAudio,
    val acInterface: UsbInterface?,
    private var asInterface: UsbInterface?,
    val conn: UsbDeviceConnection,
    val exclusive: Boolean,
    var exclusiveClaimed: Boolean,
    val featureUnit: FeatureUnit?,
    val clock: ClockSource?,
    var volumeRange: IntArray?,
    var currentVolume: Int?,
    var samplingRate: Int?,
    var volumeChannel: Int = 0,
    var claimedIfaces: List<Int> = emptyList(),
    val rawConfig: ByteArray? = null
) : Closeable {

    private var closed = false

    /** 接管失败的原因（给 UI 显示，别再让用户看到"没权限"这种含糊提示）。 */
    var claimError: String? = null

    /** AudioControl 接口是否真的拿到（拿不到 ⇒ 音量/时钟控制请求多半会失败）。 */
    var acClaimed = false

    val uac2: Boolean get() = parsed.isUac2

    val bestStream: AudioStream? get() = parsed.bestStream()

    val isoOut: IsoEndpoint? get() = bestStream?.endpoint

    val clockLabel: String? get() = clock?.let { "#${it.id} ${it.typeLabel}" }

    /** 设备真实支持的采样率（描述符 + Clock Source GET_RANGE，UAC2）。 */
    var rates: List<Int> = emptyList()
    private var chosen: AudioStream? = null

    fun pickRate(preferred: Int = 48_000): Int? {
        val list = rates.ifEmpty { parsed.allSampleRates }
        if (list.isEmpty()) return null
        val std = list.filter { it in STANDARD_RATES }
        return when {
            preferred in list -> preferred
            48_000 in std -> 48_000
            44_100 in std -> 44_100
            std.isNotEmpty() -> std.last()
            else -> list.first()
        }
    }

    /** UAC2: GET_RANGE(CS, CN) -> [count][min,max,res]*(count) */
    private fun readRangeUac2(cs: Int, unit: Int, cn: Int): IntArray? {
        val buf = ByteArray(64)
        val n = conn.controlTransfer(
            0xA1, 0x02, (cs shl 8) or cn, (unit shl 8) or ifaceNumber, buf, buf.size, TIMEOUT
        )
        if (n < 14) return null
        fun le32(o: Int): Int = (buf[o].toInt() and 0xFF) or ((buf[o + 1].toInt() and 0xFF) shl 8) or
            ((buf[o + 2].toInt() and 0xFF) shl 16) or ((buf[o + 3].toInt() and 0xFF) shl 24)
        val count = (buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8)
        if (count < 1) return null
        val min = le32(2)
        val max = le32(6)
        val res = le32(10)
        if (max <= min) return null
        return intArrayOf(min, max, if (res > 0) res else 256)
    }

    private fun queryClockRates(): List<Int> {
        val c = clock ?: return emptyList()
        val r = runCatching { readRangeUac2(CS_SAMPLE_FREQ, c.id, 0) }.getOrNull() ?: return emptyList()
        val min = r[0]
        val max = r[1]
        val res = r[2]
        if (res in 1..100) {
            return STANDARD_RATES.filter { it in min..max }
        }
        val out = ArrayList<Int>()
        var f = min
        while (f <= max && out.size < 32) {
            out.add(f)
            f += res
        }
        return out
    }

    /** 把文件描述符交给 native（JNI 里还会 dup 一次）。 */
    val fileDescriptor: Int get() = conn.fileDescriptor

    data class StreamParams(
        val iface: Int,
        val alt: Int,
        val endpoint: Int,
        val channels: Int,
        val bps: Int,
        val rates: List<Int>,
        val maxPacket: Int,
        /** 描述符里的 bBitResolution（有效位）与 bSubslotSize（每样本占几个字节）。 */
        val bitResolution: Int = 0,
        val subslotSize: Int = 0
    )

    /** 按用户选的位深/采样率挑一个等时格式；挑不到就退回最优格式。 */
    fun streamFor(depth: Int, wantRate: Int): StreamParams? {
        val cands = parsed.streams.filter { it.usable }
        if (cands.isEmpty()) return null
        val byCh = cands.filter { it.channels == 2 }.ifEmpty { cands }
        val exact = byCh.filter { it.bitResolution == depth && it.subframeSize == maxOf(2, depth / 8) }
        val byDepth = exact.ifEmpty { byCh.sortedByDescending { it.bitResolution } }
        val withRate = if (wantRate > 0) byDepth.filter { wantRate in it.sampleRates }.ifEmpty { byDepth } else byDepth
        val s = withRate.first()
        val ep = s.endpoint ?: return null
        chosen = s
        AppLog.i("DacSession", "streamFor depth=$depth want=$wantRate -> alt=${s.alt} bits=${s.bitResolution} ch=${s.channels} rates=${s.sampleRates}")
        return StreamParams(
            iface = s.interfaceNumber,
            alt = s.alt,
            endpoint = ep.address,
            channels = s.channels,
            bps = s.subframeSize.coerceIn(2, 4),
            rates = s.sampleRates,
            maxPacket = ep.maxPacket,
            bitResolution = s.bitResolution,
            subslotSize = s.subframeSize
        )
    }

    /** 独占播放所需的参数（取位深最高的那个可用格式）。 */
    fun streamParams(): StreamParams? {
        val s = chosen ?: bestStream ?: return null
        val ep = s.endpoint ?: return null
        if (!s.usable) return null
        return StreamParams(
            iface = s.interfaceNumber,
            alt = s.alt,
            endpoint = ep.address,
            channels = s.channels,
            bps = s.subframeSize.coerceIn(2, 4),
            rates = s.sampleRates,
            maxPacket = ep.maxPacket,
            bitResolution = s.bitResolution,
            subslotSize = s.subframeSize
        )
    }

    /** 最近一次时钟写入的原始返回码（4=成功，其他=设备拒绝/STALL），给日志用。 */
    var lastClockRc = -1

    /**
     * 先接管 AudioControl 接口。
     *
     * 这一步必须发生在任何 class 控制请求（时钟、音量）之前：AC 接口没被本进程接管时，
     * 内核 snd-usb-audio 仍持有它，USBDEVFS_CONTROL 直接返回失败，
     * 现象就是 `setSampleRate → 被拒绝、回读 null、vol=null`，而且设备会按默认时钟跑（变调）。
     */
    fun claimControl(): Boolean {
        val ac = acInterface ?: return false
        if (acClaimed) return true
        var okAc = runCatching { conn.claimInterface(ac, false) }.getOrDefault(false)
        if (!okAc) {
            runCatching { Thread.sleep(30) }
            okAc = runCatching { conn.claimInterface(ac, true) }.getOrDefault(false)
        }
        acClaimed = okAc
        AppLog.i("DacSession", "claimControl iface=${ac.id} -> $okAc")
        if (okAc) refreshVolume()
        return okAc
    }

    /**
     * 接管音频流接口（等时传输前必须做）。
     *
     * 只碰 AS 接口，而且先试非强制的 claimInterface(itf, false)：对已经在被系统音频
     * 使用的接口做 force claim（DISCONNECT_CLAIM），在部分机型上会把内核 snd-usb-audio
     * 直接拽下来、连驱动一起崩，表现就是"授权后卡死、设备重启"。
     * 所以这里只在真要出声时接管，并且先礼后兵；失败就把原因写进 [claimError]。
     */
    fun claimStream(): Boolean {
        val s = chosen ?: bestStream ?: return false
        val itf = asInterface ?: run {
            var found: UsbInterface? = null
            for (i in 0 until device.interfaceCount) {
                val x = device.getInterface(i)
                if (x.id == s.interfaceNumber && x.alternateSetting == 0) { found = x; break }
            }
            found
        }
        if (itf == null) {
            claimError = "找不到音频流接口 ${s.interfaceNumber}"
            return false
        }
        asInterface = itf
        var ok = runCatching { conn.claimInterface(itf, false) }.getOrDefault(false)
        if (!ok) {
            AppLog.w("DacSession", "claim ${itf.id} 非强制失败，改用 force claim")
            // 给内核驱动一点时间把上一次的等时 URB 收尾，再 DISCONNECT_CLAIM，
            // 否则在"系统音频正在播"的瞬间硬摘驱动，个别机型会直接把 USB 子系统搞崩。
            runCatching { Thread.sleep(60) }
            ok = runCatching { conn.claimInterface(itf, true) }.getOrDefault(false)
        }
        AppLog.i("DacSession", "claimStream iface=${itf.id} alt=${itf.alternateSetting} -> $ok")
        exclusiveClaimed = ok
        if (ok) {
            claimedIfaces = claimedIfaces + itf.id
            claimError = null
        } else if (claimError == null) {
            claimError = "内核拒绝交出音频流接口（系统音频正在占用）"
        }
        // AudioControl 接口必须拿到，否则 UAC2 的 class 控制请求（音量/时钟）会被内核挡掉，
        // 表现就是"vol=null、rates= 空、设了采样率不变调反而不生效"。这里先礼后兵。
        acInterface?.let { ac ->
            var okAc = runCatching { conn.claimInterface(ac, false) }.getOrDefault(false)
            if (!okAc) {
                runCatching { Thread.sleep(30) }
                okAc = runCatching { conn.claimInterface(ac, true) }.getOrDefault(false)
            }
            acClaimed = okAc
            AppLog.i("DacSession", "claim AC iface=${ac.id} -> $okAc")
            if (okAc) refreshVolume()
        }
        return ok
    }

    private val ifaceNumber: Int get() = parsed.controls.firstOrNull()?.interfaceNumber ?: 0

    private fun read2(cs: Int, code: Int, unit: Int, cn: Int = volumeChannel): Int? {
        if (unit <= 0) return null
        val buf = ByteArray(2)
        // UAC2 只有 CUR=0x01 / RANGE=0x02，靠 bmRequestType 的方向位区分读写；
        // UAC1 才是 0x81/0x82/0x83/0x84 这套 GET_CUR/MIN/MAX/RES。
        val req = if (uac2) CUR2 else CUR
        val wIndex = (unit shl 8) or ifaceNumber
        var n = -1
        for (attempt in 0 until 3) {
            n = conn.controlTransfer(0xA1, req, (cs shl 8) or cn, wIndex, buf, buf.size, TIMEOUT)
            if (n == 2) break
            if (n >= 0) break          // 设备明确拒绝（STALL/0 字节），重试也没用
            Thread.sleep(8)
        }
        if (n != 2) return null
        return (buf[0].toInt() and 0xFF) or (buf[1].toInt() shl 8)
    }

    private fun write2(cs: Int, unit: Int, value: Int, cn: Int = volumeChannel): Boolean {
        if (unit <= 0) return false
        val buf = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
        val n = conn.controlTransfer(0x21, 0x01, (cs shl 8) or cn, (unit shl 8) or ifaceNumber, buf, buf.size, TIMEOUT)
        return n == 2
    }

    /**
     * UAC2 里音量通常只挂在通道（CN=1..N）上，CN=0 的主控制只有静音，
     * 所以这里按探测到的通道号逐声道写，全都失败才算失败。
     */
    fun setVolume(value: Int): Boolean {
        val fu = featureUnit ?: return false
        if (volumeChannel > 0) {
            val n = fu.channels.coerceAtLeast(1)
            var ok = false
            for (ch in 1..n) ok = write2(CS_VOLUME, fu.id, value, ch) || ok
            if (ok) {
                // 顺手把主控制也写成同一个值（有的固件只认 CN=0，写它不会更差）
                write2(CS_VOLUME, fu.id, value, 0)
                return true
            }
        }
        // 兜底：主控制与各声道都试一遍（不同固件把音量挂在不同 CN 上）
        var any = write2(CS_VOLUME, fu.id, value, 0)
        for (ch in 1..fu.channels.coerceAtLeast(1)) any = write2(CS_VOLUME, fu.id, value, ch) || any
        return any
    }

    fun setMute(on: Boolean): Boolean {
        val fu = featureUnit ?: return false
        if (!fu.hasMute) return false
        return write2(CS_MUTE, fu.id, if (on) 1 else 0)
    }

    fun readVolume(): Int? {
        val fu = featureUnit ?: return null
        return read2(CS_VOLUME, CUR, fu.id)
    }

    /**
     * 拿到 AudioControl 接口之后再试一次音量探测。
     * 很多机上（含 CX31993）AC 接口没接管时 class 控制请求会被内核挡掉，open() 阶段必然读不到。
     */
    fun refreshVolume(): Boolean {
        if (hasHardwareVolume) return true
        val fu = featureUnit ?: scanFeature(conn, ifaceNumber, uac2) ?: return false
        val probe = probeVolume(conn, ifaceNumber, fu, uac2) ?: return false
        volumeChannel = probe[0]
        volumeRange = intArrayOf(probe[1], probe[2], if (probe[3] > 0) probe[3] else 256)
        currentVolume = probe[4]
        AppLog.i("DacSession", "refreshVolume cn=${probe[0]} range=${volumeRange?.toList()} ac=$acClaimed")
        lastHasHardwareVolume = true
        return true
    }

    /** 设备是否真的能通过 FEATURE_UNIT 调音量（写入失败时保持 false）。 */
    val hasHardwareVolume: Boolean get() = volumeRange != null

    /**
     * 0..100% → 硬件音量寄存器值（1/256 dB）。范围与步进来自设备自己的 GET_MIN/MAX/RES，
     * 所以不同 DAC 的负 dB 上限、0.5dB 步进都能对上。
     */
    fun setVolumePercent(percent: Int): Boolean {
        val r = volumeRange ?: return false
        val fu = featureUnit ?: return false
        val lo = r[0]
        val hi = r[1]
        val res = if (r[2] > 0) r[2] else 256
        val p = percent.coerceIn(0, 100)
        var v = lo + (((hi - lo).toLong() * p) / 100L).toInt()
        if (res > 1) v = lo + ((v - lo) / res) * res
        // 写所有声道，不再只写探测到的那一个 CN
        val wrote = setVolume(v)
        // 写完读回来验一次：个别固件 SET_CUR 回 2 字节却没真的存下来。
        // 回读和写入值差得超过 2 个步进，就当硬件音量不可用，交给 DacPlayer 退回软件增益，
        // 这样音量一定可调（宁可降一点音质，也不能"调到 0 还是最大"）。
        val back = if (wrote) readVolume() else null
        val drift = if (back == null) 0 else kotlin.math.abs(back - v)
        val ok = wrote && drift <= res * 2
        if (ok) currentVolume = v
        AppLog.i(
            "DacSession",
            "setVolumePercent p=$p v=$v db=${db(v)} ch=$volumeChannel write=$wrote back=$back ok=$ok"
        )
        return ok
    }

    fun setSampleRate(hz: Int): Boolean {
        val clock = clock
        if (uac2 && clock != null) {
            val buf = byteArrayOf(
                (hz and 0xFF).toByte(),
                ((hz shr 8) and 0xFF).toByte(),
                ((hz shr 16) and 0xFF).toByte(),
                ((hz shr 24) and 0xFF).toByte()
            )
            val n = conn.controlTransfer(
                0x21, 0x01, (CS_SAMPLE_FREQ shl 8), (clock.id shl 8) or ifaceNumber, buf, 4, TIMEOUT
            )
            lastClockRc = n
            val back = readSampleRate()
            AppLog.i("DacSession", "setSampleRate($hz) rc=$n clock=#${clock.id} iface=$ifaceNumber readback=$back")
            return n == 4
        }
        val ep = isoOut ?: return false
        val stream = bestStream ?: return false
        val buf = byteArrayOf(
            (hz and 0xFF).toByte(), ((hz shr 8) and 0xFF).toByte(), ((hz shr 16) and 0xFF).toByte()
        )
        val n = conn.controlTransfer(
            0x22, 0x01, CS_SAMPLE_FREQ shl 8, ep.address or (stream.interfaceNumber shl 8), buf, 3, TIMEOUT
        )
        return n == 3
    }

    fun readSampleRate(): Int? {
        val clock = clock
        if (uac2 && clock != null) {
            val buf = ByteArray(4)
            val n = conn.controlTransfer(
                0xA1, CUR2, (CS_SAMPLE_FREQ shl 8), (clock.id shl 8) or ifaceNumber, buf, 4, TIMEOUT
            )
            if (n != 4) return null
            return (buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8) or
                ((buf[2].toInt() and 0xFF) shl 16) or ((buf[3].toInt() and 0xFF) shl 24)
        }
        val ep = isoOut ?: return null
        val stream = bestStream ?: return null
        val buf = ByteArray(3)
        val n = conn.controlTransfer(
            0xA2, CUR, CS_SAMPLE_FREQ shl 8, ep.address or (stream.interfaceNumber shl 8), buf, 3, TIMEOUT
        )
        if (n != 3) return null
        return (buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8) or ((buf[2].toInt() and 0xFF) shl 16)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { asInterface?.let { conn.releaseInterface(it) } }
        runCatching { acInterface?.let { conn.releaseInterface(it) } }
        runCatching { conn.close() }
        runCatching { GATE.release() }
    }

    companion object {
        private const val TIMEOUT = 800
        private const val CUR = 0x81    // UAC1 GET_CUR；UAC2 用 CUR2
        private const val CUR2 = 0x01   // UAC2 GET_CUR（方向位 IN 区分读写）
        private const val MIN = 0x82
        private const val MAX = 0x83
        private const val RES = 0x84
        private const val RANGE = 0x02   // UAC2 GET_RANGE
        private const val CS_MUTE = 0x01
        private const val CS_VOLUME = 0x02
        private const val CS_SAMPLE_FREQ = 0x01

        val COMMON_RATES = listOf(44100, 48000, 88200, 96000, 176400, 192000)

        /** 上一次探测的结论（null = 还不知道）。UI 只读这个，绝不在主线上去开 USB 会话。 */
        @Volatile
        var lastHasHardwareVolume: Boolean? = null

        /** 同一时刻只允许一个 USB 会话：两个线程同时 open/claim 会让内核 USB 栈崩掉。 */
        private val GATE = java.util.concurrent.Semaphore(1, true)

        /**
         * 不占流的情况下快速问一句"这台 DAC 能不能用硬件音量"：
         * 建一个只读会话（不 claim），读完立刻关。正在直通播放时 GATE 会直接拒绝，返回 false。
         */
        fun probeHardwareVolume(ctx: Context): Boolean {
            val manager = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
            val device = manager.deviceList.values.firstOrNull { d ->
                (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }
            } ?: return false
            val s = open(ctx, device) ?: return false
            return try {
                s.hasHardwareVolume
            } finally {
                s.close()
            }
        }

        fun open(ctx: Context, device: UsbDevice, exclusive: Boolean = false): DacSession? {
            val manager = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
            if (!manager.hasPermission(device)) return null
            val acquired = runCatching { GATE.tryAcquire(1200, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            if (!acquired) {
                AppLog.w("DacSession", "另一个 USB 会话还在，放弃打开（避免并发 claim）")
                return null
            }
            val conn = manager.openDevice(device)
            if (conn == null) {
                GATE.release()
                return null
            }

            val rawConfig = runCatching { Uac.fetchConfiguration(conn) }.getOrNull()
            val parsed = runCatching {
                rawConfig?.let { Uac.parse(it) }
            }.getOrNull()?.takeIf { it.isAudio } ?: runCatching { Uac.describe(device) }.getOrNull()

            if (parsed == null || !parsed.isAudio) {
                runCatching { conn.close() }
                GATE.release()
                return null
            }

            var ac: UsbInterface? = null
            for (idx in 0 until device.interfaceCount) {
                val itf = device.getInterface(idx)
                if (itf.interfaceClass != UsbConstants.USB_CLASS_AUDIO) continue
                if (itf.interfaceSubclass == 1) {
                    ac = itf
                    break
                }
            }

            // 注意：这里**不**做任何 claim。
            // 早期版本在探测/打开时就 force claim 所有 AUDIO 接口，只要系统音频正在用这块声卡，
            // 内核 snd-usb-audio 会被直接 detach，真机上表现为"授权完就卡死、随后重启"。
            // 现在只在真正开始等时播放时由 [claimStream] 接管音频流接口。
            val claimed = false
            val claimedIfaces = ArrayList<Int>()

            var feature = parsed.featureWithVolume
            var clock = parsed.clocks.firstOrNull()

            // 描述符里没有就退回“逐个 entityID 试探”
            val ifaceNum = parsed.controls.firstOrNull()?.interfaceNumber ?: 0
            if (feature == null) {
                feature = scanFeature(conn, ifaceNum, parsed.isUac2)
                AppLog.w("DacSession", "描述符里没有带音量的 FEATURE_UNIT，试探结果=${feature?.id}")
            }
            if (clock == null) clock = scanClock(conn, ifaceNum, parsed.isUac2)

            // 硬件音量探测：UAC1/UAC2 都用 GET_MIN/GET_MAX/GET_RES（各 2 字节，1/256 dB，有符号）。
            // 老代码读 14 字节的 "range" 结构（那根本不是音量请求），所以 CX31993 这类设备一直报 vol=null。
            var range: IntArray? = null
            var cur: Int? = null
            var volCn = 0
            feature?.let { fu ->
                val probe = probeVolume(conn, ifaceNum, fu, parsed.isUac2)
                if (probe != null) {
                    volCn = probe[0]
                    range = intArrayOf(probe[1], probe[2], if (probe[3] > 0) probe[3] else 256)
                    cur = probe[4]
                }
                AppLog.i("DacSession", "volume fu=${fu.id} ch=${fu.channels} cn=$volCn range=${range?.toList()} cur=$cur")
            }

            lastHasHardwareVolume = range != null
            val session = DacSession(
                device = device,
                parsed = parsed,
                acInterface = ac,
                asInterface = null,
                conn = conn,
                exclusive = exclusive,
                exclusiveClaimed = claimed,
                featureUnit = feature,
                clock = clock,
                volumeRange = range,
                currentVolume = cur,
                samplingRate = null,
                volumeChannel = volCn,
                claimedIfaces = claimedIfaces,
                rawConfig = rawConfig
            )
            session.samplingRate = runCatching { session.readSampleRate() }.getOrNull()
            session.rates = runCatching {
                (parsed.allSampleRates + session.queryClockRates()).distinct().sorted()
            }.getOrDefault(parsed.allSampleRates)
            AppLog.i("DacSession", "rates=${session.rates}")
            return session
        }

        private fun scanFeature(conn: UsbDeviceConnection, iface: Int, uac2: Boolean = true): FeatureUnit? {
            for (id in 2..14) {
                val r = (if (uac2) readRangeUac2(conn, iface, id, 0) else readRangeUac1(conn, iface, id, 0))
                    ?: continue
                if (r[1] > r[0]) return FeatureUnit(id, -1, hasMute = false, hasVolume = true, channels = 0)
            }
            return null
        }

        private fun scanClock(conn: UsbDeviceConnection, iface: Int, uac2: Boolean = true): ClockSource? {
            val req = if (uac2) CUR2 else CUR
            for (id in 1..14) {
                val buf = ByteArray(4)
                val n = conn.controlTransfer(0xA1, req, CS_SAMPLE_FREQ shl 8, (id shl 8) or iface, buf, 4, TIMEOUT)
                if (n != 4) continue
                val hz = (buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8) or
                    ((buf[2].toInt() and 0xFF) shl 16) or ((buf[3].toInt() and 0xFF) shl 24)
                if (hz in 8000..768000) return ClockSource(id, 2)
            }
            return null
        }

        /** 返回 [cn, min, max, res, cur]，单位 1/256 dB（有符号）。 */
        private fun probeVolume(
            conn: UsbDeviceConnection, iface: Int, fu: FeatureUnit, uac2: Boolean
        ): IntArray? {
            // 先试各声道（CN=1..N），最后才试 CN=0 主控制。
            // CX31993 这类固件 CN=0 的 GET_RANGE 能读、SET_CUR 也回 2 字节，但主控制不作用到输出，
            // 结果是"探测成功、音量却一点不生效"。声道音量才是真正生效的那一级（与内核 mixer 一致）。
            val order = ArrayList<Int>()
            for (c in 1..fu.channels.coerceIn(0, 8).coerceAtLeast(2)) order.add(c)
            order.add(0)
            // 每个 CN 的返回码都留着：只看最后一次会丢掉真正的失败原因
            val trace = StringBuilder()
            for (cn in order) {
                // UAC2：MIN/MAX/RES 不是一个一个读的。0x82 = GET_RANGE，
                // 返回 wNumSubRanges(2B) + (MIN,MAX,RES)×N，每项 2 或 4 字节。
                // UAC1：0x82/0x83/0x84 分别是 GET_MIN/GET_MAX/GET_RES，各 2 字节。
                lastRangeRc = 0
                val range = if (uac2) {
                    readRangeUac2(conn, iface, fu.id, cn)
                } else {
                    readRangeUac1(conn, iface, fu.id, cn)
                }
                if (range == null) {
                    trace.append("cn$cn:rc=$lastRangeRc ")
                    continue
                }
                val lo = range[0]
                val hi = range[1]
                if (hi <= lo) {
                    trace.append("cn$cn:range=${lo}..${hi} ")
                    continue
                }
                val res = if (range[2] > 0) range[2] else 256
                val cur = readWordSigned(conn, iface, CS_VOLUME, if (uac2) CUR2 else CUR, fu.id, cn) ?: hi
                lastVolumeProbe = "cn=$cn min=$lo max=$hi res=$res"
                return intArrayOf(cn, lo, hi, res, cur)
            }
            lastVolumeProbe = "全部失败 $trace"
            return null
        }

        /** UAC1：GET_MIN/GET_MAX/GET_RES 是三个独立请求，各 2 字节。 */
        private fun readRangeUac1(
            conn: UsbDeviceConnection, iface: Int, unit: Int, cn: Int
        ): IntArray? {
            val lo = readWordSigned(conn, iface, CS_VOLUME, MIN, unit, cn) ?: run { lastRangeRc = -1; return null }
            val hi = readWordSigned(conn, iface, CS_VOLUME, MAX, unit, cn) ?: run { lastRangeRc = -1; return null }
            val res = readWordSigned(conn, iface, CS_VOLUME, RES, unit, cn) ?: 256
            return intArrayOf(lo, hi, res)
        }

        /**
         * UAC2 GET_RANGE（bRequest 0x82）。返回值长度决定每项宽度：
         * 8 字节 = 1 个子范围 × 2 字节，20 字节 = 1 个 × 4 字节等。
         * 这是 UAC2 与 UAC1 最大的差别，按 2 字节逐个读 MIN/MAX 一定 STALL。
         */
        private fun readRangeUac2(
            conn: UsbDeviceConnection, iface: Int, unit: Int, cn: Int
        ): IntArray? {
            val buf = ByteArray(16)
            var n = -1
            for (attempt in 0 until 2) {
                n = conn.controlTransfer(0xA1, RANGE, (CS_VOLUME shl 8) or cn, (unit shl 8) or iface, buf, 14, TIMEOUT)
                if (n >= 4) break
                if (n >= 0) break
                Thread.sleep(8)
            }
            if (n < 4) { lastRangeRc = n; return null }
            lastRangeRc = n
            val count = (buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8)
            if (count <= 0) return null
            val item = ((n - 2) / 3) / count          // 2 或 4
            if (item != 2 && item != 4) return null
            fun at(off: Int): Int = if (item == 2) {
                val v = (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)
                if (v > 32767) v - 65536 else v
            } else {
                val v = (buf[off].toInt() and 0xFF) or
                    ((buf[off + 1].toInt() and 0xFF) shl 8) or
                    ((buf[off + 2].toInt() and 0xFF) shl 16) or
                    ((buf[off + 3].toInt() and 0xFF) shl 24)
                // 4 字节单位也是 1/256 dB，换算到 16 位等价值
                (v / 65536)
            }
            return intArrayOf(at(2), at(2 + item), at(2 + item * 2))
        }

        private fun readWordUac2(
            conn: UsbDeviceConnection, iface: Int, cs: Int, unit: Int, cn: Int
        ): Int? {
            val buf = ByteArray(2)
            var n = conn.controlTransfer(0xA1, 0x01, (cs shl 8) or cn, (unit shl 8) or iface, buf, buf.size, TIMEOUT)
            if (n != 2) n = conn.controlTransfer(0xA1, 0x81, (cs shl 8) or cn, (unit shl 8) or iface, buf, buf.size, TIMEOUT)
            if (n != 2) return null
            return (buf[0].toInt() and 0xFF) or (buf[1].toInt() shl 8)
        }

        private fun readWord(
            conn: UsbDeviceConnection, iface: Int, cs: Int, code: Int, unit: Int, cn: Int = 0
        ): Int? {
            val buf = ByteArray(2)
            val n = conn.controlTransfer(0xA1, code, (cs shl 8) or cn, (unit shl 8) or iface, buf, buf.size, TIMEOUT)
            if (n != 2) return null
            return (buf[0].toInt() and 0xFF) or (buf[1].toInt() shl 8)
        }

        /** 音量是 16 位有符号（1/256 dB），readWord 会把 -60dB 读成 58880。 */
        private fun readWordSigned(
            conn: UsbDeviceConnection, iface: Int, cs: Int, code: Int, unit: Int, cn: Int = 0
        ): Int? {
            val v = readWord(conn, iface, cs, code, unit, cn) ?: return null
            return if (v > 32767) v - 65536 else v
        }

        /** 最近一次音量探测的原始结果，供 DacPlayer 写进用户可复制的诊断日志。 */
        @Volatile
        var lastVolumeProbe: String = ""

        /** 最近一次 GET_RANGE 的返回字节数（<0 为 STALL），只用于诊断。 */
        private var lastRangeRc: Int = 0

        fun db(value: Int): String = String.format(java.util.Locale.US, "%.1f dB", value / 256.0)
    }
}
