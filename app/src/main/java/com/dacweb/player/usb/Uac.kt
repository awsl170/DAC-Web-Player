package com.dacweb.player.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint

/**
 * USB 音频描述符的读取与解析。
 *
 * `UsbDevice.getRawDescriptors()` 不是公开 API，但可以用标准控制请求
 * GET_DESCRIPTOR(CONFIGURATION) 把整份配置描述符（含 Class-Specific 的
 * Feature Unit / Clock Source / Format Type I）读回来，不需要 root、不需要 native。
 */

data class FeatureUnit(
    val id: Int,
    val sourceId: Int,
    val hasMute: Boolean,
    val hasVolume: Boolean,
    val channels: Int
)

/** type: 0 外部时钟、1 内部固定、2 内部可变、3 内部可编程 */
data class ClockSource(val id: Int, val type: Int) {
    val typeLabel: String
        get() = when (type) {
            0 -> "外部时钟"
            1 -> "内部固定"
            2 -> "内部可变"
            else -> "内部可编程"
        }

    val settable: Boolean get() = type == 2 || type == 3
}

data class IsoEndpoint(val address: Int, val maxPacket: Int, val interval: Int) {
    val isOut: Boolean get() = address and 0x80 == 0
}

data class AudioStream(
    val interfaceNumber: Int,
    val alt: Int,
    val protocol: Int,
    val channels: Int,
    val bitResolution: Int,
    val subframeSize: Int,
    val sampleRates: List<Int>,
    val endpoint: IsoEndpoint?
) {
    val usable: Boolean get() = endpoint != null && endpoint.isOut && channels > 0
}

data class AudioControl(val interfaceNumber: Int, val alt: Int, val protocol: Int)

data class ParsedAudio(
    val controls: List<AudioControl>,
    val streams: List<AudioStream>,
    val features: List<FeatureUnit>,
    val clocks: List<ClockSource>
) {
    val isAudio: Boolean get() = controls.isNotEmpty() || streams.isNotEmpty()
    val isUac2: Boolean
        get() = controls.any { it.protocol == 0x20 } || streams.any { it.protocol == 0x20 }
    val isUac1: Boolean
        get() = controls.any { it.protocol == 0x00 } || streams.any { it.protocol == 0x00 }

    val isoStreams: List<AudioStream> get() = streams.filter { it.usable }

    /**
     * 选一个“最像真实播歌”的格式：
     *  - 立体声（2ch）优先，CX31993 这类解码耳放不会真有 4 声道；
     *  - 标准采样率（44.1k 家族 / 48k 家族）优先，过滤掉解析噪声值；
     *  - 同位深时优先位深高的合法值（16/24/32）。
     */
    fun bestStream(): AudioStream? = isoStreams.maxByOrNull { s ->
        val stereo = if (s.channels == 2) 1000 else 0
        val std = s.sampleRates.count { it in STANDARD_RATES }
        val bit = if (s.bitResolution in setOf(16, 24, 32)) s.bitResolution else 0
        stereo + std * 100 + bit
    }

    /** 优先 48k，其次 44.1k，再按大小取标准值；都不标准就退回第一个。 */
    fun pickRate(preferred: Int = 48_000): Int? {
        val s = bestStream() ?: return null
        val std = s.sampleRates.filter { it in STANDARD_RATES }
        return when {
            preferred in s.sampleRates -> preferred
            preferred in std -> preferred
            48_000 in std -> 48_000
            44_100 in std -> 44_100
            std.isNotEmpty() -> std.first()
            s.sampleRates.isNotEmpty() -> s.sampleRates.first()
            else -> null
        }
    }

    val allSampleRates: List<Int> get() = streams.flatMap { it.sampleRates }.distinct().sorted()
    val featureWithVolume: FeatureUnit? get() = features.firstOrNull { it.hasVolume }
}

val STANDARD_RATES = setOf(
    8_000, 11_025, 16_000, 22_050, 24_000, 32_000,
    44_100, 48_000, 88_200, 96_000, 176_400, 192_000, 352_800, 384_000, 705_600, 768_000
)

object Uac {

    /** 读整份配置描述符（GET_DESCRIPTOR CONFIGURATION）。 */
    fun fetchConfiguration(conn: UsbDeviceConnection): ByteArray? {
        val head = ByteArray(9)
        var n = conn.controlTransfer(0x80, 0x06, 0x0200, 0, head, head.size, 1000)
        if (n < 9) return null
        val total = (head[2].toInt() and 0xFF) or ((head[3].toInt() and 0xFF) shl 8)
        if (total < 9 || total > 65535) return null
        val out = ByteArray(total)
        n = conn.controlTransfer(0x80, 0x06, 0x0200, 0, out, out.size, 3000)
        if (n < 9) return null
        return out
    }

    /** 解析配置描述符字节流；也接受从 device raw descriptors 里截出的整段。 */
    fun parse(config: ByteArray): ParsedAudio {
        val controls = ArrayList<AudioControl>()
        val streams = ArrayList<AudioStream>()
        val features = ArrayList<FeatureUnit>()
        val clocks = ArrayList<ClockSource>()

        var i = 0
        // 如果第一个描述符是 CONFIGURATION(0x02)，跳过它的 9 字节头
        if (config.size > 9 && (config[1].toInt() and 0xFF) == 0x02) {
            i = config[0].toInt() and 0xFF
        }

        var ifNum = -1
        var ifAlt = 0
        var ifSub = -1
        var ifProto = -1

        var curFeatures = ArrayList<FeatureUnit>()
        var curClocks = ArrayList<ClockSource>()
        var curChannels = 0
        var curSubframe = 0
        var curBits = 0
        var curRates = ArrayList<Int>()
        var curEp: IsoEndpoint? = null
        var curIfaceAdded = false

        fun flush() {
            if (curIfaceAdded) {
                when (ifSub) {
                    1 -> controls.add(AudioControl(ifNum, ifAlt, ifProto))
                    2 -> streams.add(
                        AudioStream(
                            interfaceNumber = ifNum,
                            alt = ifAlt,
                            protocol = ifProto,
                            channels = curChannels,
                            bitResolution = curBits,
                            subframeSize = curSubframe,
                            sampleRates = curRates.toList(),
                            endpoint = curEp
                        )
                    )
                }
                features.addAll(curFeatures)
                clocks.addAll(curClocks)
            }
            curFeatures = ArrayList()
            curClocks = ArrayList()
            curChannels = 0
            curSubframe = 0
            curBits = 0
            curRates = ArrayList()
            curEp = null
            curIfaceAdded = false
        }

        while (i + 2 <= config.size) {
            val len = config[i].toInt() and 0xFF
            if (len < 2 || i + len > config.size) break
            when (val type = config[i + 1].toInt() and 0xFF) {
                0x04 -> {
                    flush()
                    ifNum = u8(config, i + 2)
                    ifAlt = u8(config, i + 3)
                    val cls = u8(config, i + 5)
                    ifSub = u8(config, i + 6)
                    ifProto = u8(config, i + 7)
                    curIfaceAdded = cls == UsbConstants.USB_CLASS_AUDIO
                }

                0x24 -> {
                    val subtype = u8(config, i + 2)
                    if (ifSub == 1) {
                        when (subtype) {
                            0x06 -> { // FEATURE UNIT
                                val id = u8(config, i + 3)
                                val src = u8(config, i + 4)
                                if (ifProto == 0x20) {
                                    val master = u8(config, i + 5)
                                    val ch = maxOf((len - 5) / 4 - 1, 0)
                                    curFeatures.add(
                                        FeatureUnit(id, src, master and 0x01 != 0, master and 0x02 != 0, ch)
                                    )
                                } else {
                                    val ctrlSize = u8(config, i + 5)
                                    val master = u8(config, i + 6)
                                    val ch = if (ctrlSize > 0) maxOf((len - 6) / ctrlSize - 1, 0) else 0
                                    curFeatures.add(
                                        FeatureUnit(id, src, master and 0x01 != 0, master and 0x02 != 0, ch)
                                    )
                                }
                            }

                            0x0A -> curClocks.add(ClockSource(u8(config, i + 3), u8(config, i + 4) and 0x03))
                        }
                    } else if (ifSub == 2 && subtype == 0x01 && len >= 11) {
                        // AS_GENERAL：声道数在这里（bNrChannels 在 +10），精简格式描述符里没有声道
                        val ch = u8(config, i + 10)
                        if (ch in 1..8) curChannels = ch
                    } else if (ifSub == 2 && subtype == 0x02) { // FORMAT TYPE I
                        if (len == 6) {
                            // 精简形态（CX31993 这类固件）：
                            // [len][0x24][0x02][bFormatType][bSubframeSize][bBitResolution]
                            // 没有 bNrChannels / bSamFreqType，采样率要靠 Clock Source 的 GET_RANGE 查
                            curSubframe = u8(config, i + 4)
                            curBits = u8(config, i + 5)
                            curChannels = if (curChannels > 0) curChannels else 2
                            i += len
                            continue
                        }
                        val fmtType = u8(config, i + 3)
                        if (fmtType != 1) { i += len; continue }
                        curChannels = u8(config, i + 4)
                        curSubframe = u8(config, i + 5)
                        curBits = u8(config, i + 6)
                        val freqCount = u8(config, i + 7)
                        // UAC2 的 tSamFreq 是 4 字节，UAC1 是 3 字节；0 表示连续区间
                        if (ifProto == 0x20) {
                            if (freqCount == 0) {
                                val lo = u32(config, i + 8).toInt()
                                val hi = u32(config, i + 12).toInt()
                                if (lo in RATE_MIN..RATE_MAX) curRates.add(lo)
                                if (hi in RATE_MIN..RATE_MAX && hi != lo) curRates.add(hi)
                            } else {
                                for (k in 0 until freqCount) {
                                    val o = i + 8 + k * 4
                                    if (o + 3 >= config.size) break
                                    val hz = u32(config, o).toInt()
                                    if (hz in RATE_MIN..RATE_MAX) curRates.add(hz)
                                }
                            }
                        } else {
                            if (freqCount == 0) {
                                val lo = u24(config, i + 8)
                                val hi = u24(config, i + 11)
                                if (lo in RATE_MIN..RATE_MAX) curRates.add(lo)
                                if (hi in RATE_MIN..RATE_MAX && hi != lo) curRates.add(hi)
                            } else {
                                for (k in 0 until freqCount) {
                                    val o = i + 8 + k * 3
                                    if (o + 2 >= config.size) break
                                    val hz = u24(config, o)
                                    if (hz in RATE_MIN..RATE_MAX) curRates.add(hz)
                                }
                            }
                        }
                    }
                }

                0x05 -> {
                    if (ifSub == 2 && u8(config, i + 3) and 0x03 == 0x01) {
                        curEp = IsoEndpoint(u8(config, i + 2), u16(config, i + 4), u8(config, i + 6))
                    }
                }
            }
            i += len
        }
        flush()
        return ParsedAudio(controls, streams, features, clocks)
    }

    /** 没有描述符时的降级描述（只有接口与端点，没有格式与实体）。 */
    fun describe(device: UsbDevice): ParsedAudio {
        val controls = ArrayList<AudioControl>()
        val streams = ArrayList<AudioStream>()
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            if (itf.interfaceClass != UsbConstants.USB_CLASS_AUDIO) continue
            val proto = runCatching { itf.interfaceProtocol }.getOrDefault(0)
            when (itf.interfaceSubclass) {
                1 -> controls.add(AudioControl(itf.id, itf.alternateSetting, proto))
                2 -> {
                    var iso: IsoEndpoint? = null
                    for (e in 0 until itf.endpointCount) {
                        val ep: UsbEndpoint = itf.getEndpoint(e)
                        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC) {
                            iso = IsoEndpoint(ep.address, ep.maxPacketSize, ep.interval)
                            break
                        }
                    }
                    streams.add(AudioStream(itf.id, itf.alternateSetting, proto, 0, 0, 0, emptyList(), iso))
                }
            }
        }
        return ParsedAudio(controls, streams, emptyList(), emptyList())
    }

    private fun u8(b: ByteArray, i: Int): Int = if (i in b.indices) b[i].toInt() and 0xFF else 0

    private fun u16(b: ByteArray, i: Int): Int = u8(b, i) or (u8(b, i + 1) shl 8)

    private fun u24(b: ByteArray, i: Int): Int =
        u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16)

    private fun u32(b: ByteArray, i: Int): Long =
        (u8(b, i).toLong()) or (u8(b, i + 1).toLong() shl 8) or
            (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 3).toLong() shl 24)

    private const val RATE_MIN = 8_000
    private const val RATE_MAX = 768_000
}
