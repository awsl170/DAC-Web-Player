package com.dacweb.player.usb

import com.dacweb.player.log.AppLog

/**
 * native 等时直通输出的薄封装。
 *
 * 目前只推 1 kHz 正弦测试音，用来验证「force claim + SETINTERFACE + SUBMITURB」这条路。
 */
object UacIso {

    private const val TAG = "UacIso"
    private var loaded = false

    fun available(): Boolean = runCatching {
        if (!loaded) {
            System.loadLibrary("uaciso")
            loaded = true
        }
        true
    }.getOrElse {
        AppLog.w(TAG, "native lib unavailable: ${it.message}")
        false
    }

    /** 返回 0 表示已开始；负数是 -errno。 */
    external fun nativeStart(
        fd: Int, iface: Int, alt: Int, ep: Int,
        rate: Int, channels: Int, bps: Int, packets: Int, maxPacket: Int
    ): Int

    external fun nativeStop()

    external fun nativeStats(): IntArray

    external fun nativeRunning(): Boolean

    external fun nativeStreamStart(
        fd: Int, iface: Int, alt: Int, ep: Int,
        rate: Int, channels: Int, bps: Int, packets: Int, maxPacket: Int
    ): Int

    external fun nativeWrite(data: ByteArray, length: Int): Int

    external fun nativeSetPaused(paused: Boolean)

    external fun nativeFrames(): Long

    external fun nativeBuffered(): Int

    fun running(): Boolean = if (!available()) false else runCatching { nativeRunning() }.getOrDefault(false)

    fun write(data: ByteArray, length: Int): Int =
        runCatching { nativeWrite(data, length) }.getOrDefault(-1)

    fun frames(): Long = runCatching { nativeFrames() }.getOrDefault(0L)

    fun buffered(): Int = runCatching { nativeBuffered() }.getOrDefault(0)

    external fun nativeSetUrbs(n: Int)

    /** 同时挂起的 URB 数（深度越大越抗抖动，默认 32 ≈ 256 ms）。 */
    fun setUrbs(n: Int) {
        if (!available()) return
        runCatching { nativeSetUrbs(n.coerceIn(4, 128)) }
    }

    external fun nativeSetBuffer(kb: Int)

    external fun nativeCapacity(): Int

    external fun nativeUnderruns(): Int

    /** 设置环形缓冲大小（KB）。下次启动流时生效。 */
    fun setBufferKb(kb: Int) {
        if (!available()) return
        runCatching { nativeSetBuffer(kb.coerceIn(64, 16384)) }
    }

    /** 环形缓冲实际容量（字节）。 */
    fun capacity(): Int = if (!available()) 0 else runCatching { nativeCapacity() }.getOrDefault(0)

    /** 欠载次数：每次等时包填不满就 +1，是"爆音/断续"的直接证据。 */
    fun underruns(): Int = if (!available()) 0 else runCatching { nativeUnderruns() }.getOrDefault(0)

    external fun nativeSetGain(gain: Float)

    fun setGain(gain: Float) {
        if (!available()) return
        runCatching { nativeSetGain(gain.coerceIn(0f, 1f)) }
    }

    /** [submitted, completed, errors] */
    fun stats(): IntArray = if (available()) runCatching { nativeStats() }.getOrDefault(intArrayOf(0, 0, 0))
    else intArrayOf(0, 0, 0)

    fun stop() {
        if (!loaded) return
        runCatching { nativeStop() }
    }
}
