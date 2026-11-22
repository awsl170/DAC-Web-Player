package com.dacweb.player.audio

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.media3.common.Player
import com.dacweb.player.prefs.AppPrefs

/**
 * 把 DAC 独占接进普通播放页（临时方案）。
 *
 * ExoPlayer 仍然负责队列、进度、通知栏、歌词对时，只是被静音（volume = 0）；
 * 真正出声的是 [DacPlayer] 的 USB 等时直通。这样不用改 PlaybackService/MediaSession，
 * 播放页和通知栏的所有按钮都还能用。
 *
 * 换曲时先 [DacPlayer.softStop]（增益压到 0 再停，避免硬切爆音），
 * 再按新曲目从当前进度重启——native 起播自带 5 ms 淡入。
 */
object DacRoute {

    /** 最近一次直通会话的输出格式，如 "44.1k · 16bit · 直通"。 */
    @Volatile
    private var format = ""

    /** 启动失败原因；成功后为 null。 */
    @Volatile
    private var failure: String? = null

    /** 当前直通绑定的曲目 uri，用来判断要不要换曲重启。 */
    private var boundUri: String? = null

    /** 上次启动尝试的时刻，失败后限速重试，避免每 500 ms 反复开关 USB。 */
    private var lastAttemptMs = 0L

    /** 插着 DAC 但用户还没授权：此时绝不能去 open/claim 设备。 */
    @Volatile
    private var needsPermission = false

    /**
     * 起播预热中：DAC 侧缓冲还没灌满，ExoPlayer 被按住不动。
     * 在线曲目第一次打开要先连服务器 + 解复用 + 填缓冲，这段时间进度条如果先跑，
     * 声音就会晚到，歌词跟着错位——所以先把两边都停住，缓冲够了再一起走。
     */
    private var priming = false
    private var primeSince = 0L

    private const val PRIME_TIMEOUT_MS = 4000L

    /** 需要用户点一下授权。 */
    fun permissionNeeded(): Boolean = needsPermission

    /** 设备此刻有没有 USB 访问权限。 */
    fun hasPermission(ctx: Context): Boolean {
        val d = device(ctx) ?: return false
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        return usb.hasPermission(d)
    }

    fun device(ctx: Context): UsbDevice? {
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        return DacPlayer.audioDevice(usb)
    }

    /** 用户在 DAC 页开过独占，并且此刻插着 USB 音频设备。 */
    fun enabled(ctx: Context): Boolean = AppPrefs.dacExclusive(ctx) && device(ctx) != null

    /** 播放页状态框的文案；null 表示不显示这个框。 */
    fun label(ctx: Context): String? {
        if (!enabled(ctx)) return null
        if (needsPermission) return ctx.getString(com.dacweb.player.R.string.dac_need_permission)
        failure?.let { return "DAC 独占 · $it" }
        return "DAC 独占 · " + (format.ifBlank { "待播放" })
    }

    /** 最近一次直通失败原因；null 表示没有错误。 */
    fun failureText(): String? = failure

    /** 直通会话是否正在出声。 */
    fun active(): Boolean = DacPlayer.started

    /** 设备被拔掉/退出独占时清状态。 */
    fun reset() {
        boundUri = null
        lastAttemptMs = 0L
        format = ""
        failure = null
        needsPermission = false
        priming = false
    }

    /**
     * 跟着播放器同步直通会话。播放页每 500 ms 调一次即可（bind 时也调一次）。
     * - 曲目变了：软停旧会话，从当前进度起新的
     * - 播放/暂停：跟着走（ExoPlayer 暂停时进度冻结，两边不会越走越偏）
     */
    fun sync(ctx: Context, player: Player?) {
        if (!enabled(ctx)) {
            // 退出独占时 ExoPlayer 的音量必须无条件恢复：softStop() 会把 started 变成 false，
            // 若把恢复音量塞在 if (started) 里面，那条路径永远走不到，结果就是"退出独占后没声音"。
            if (DacPlayer.started) DacPlayer.softStop()
            if (player != null && player.volume != 1f) player.volume = 1f
            reset()
            return
        }
        val c = player ?: return
        // 还没授权：保持系统播放（别静音），只标记状态等用户点「开启独占」。
        // 早期版本在这里直接 start，结果 play 一响就报"没有 USB 访问权限"，红色错误甩在脸上。
        if (!hasPermission(ctx)) {
            if (DacPlayer.started) DacPlayer.softStop()
            if (c.volume != 1f) c.volume = 1f
            needsPermission = true
            failure = null
            format = ""
            boundUri = null
            return
        }
        needsPermission = false
        // 声音已经从 USB 直出：ExoPlayer 必须静音，否则手机扬声器会同时出声
        if (c.volume != 0f) c.volume = 0f
        val uri = c.currentMediaItem?.localConfiguration?.uri?.toString()
        if (uri.isNullOrBlank()) return

        // 预热中：两边都别动，等 DAC 缓冲灌到 2/3 或超时，再让 ExoPlayer 和 USB 同时开跑
        if (priming && uri == boundUri) {
            if (!DacPlayer.started) {
                priming = false
                DacPlayer.error?.let { failure = it }
            } else if (DacPlayer.primeReady() || System.currentTimeMillis() - primeSince > PRIME_TIMEOUT_MS) {
                priming = false
                DacPlayer.resume()
                c.play()
            }
            format = DacPlayer.streamLabel
            return
        }
        // 预热途中曲目被换掉：放弃这次预热，走下面的正常换曲逻辑
        if (priming) priming = false

        val pos = c.currentPosition.coerceAtLeast(0L)
        val dur = c.duration
        // 已经播到结尾：decodeLoop 会自己停下，这里不要再从"末尾"重启（否则会反复开关一轮）
        val atEnd = dur > 0 && pos >= dur - 700

        if (!DacPlayer.started || boundUri != uri) {
            if (atEnd) return
            // 直通已经自然播完：ExoPlayer 还差最后一点进度时别再从这里重开，
            // 否则 DacPlayer 反复重播最后几秒（真机上就是"结尾一直循环"）。
            if (DacPlayer.finished && boundUri == uri && dur > 0 && pos >= dur - 1500) return
            val now = System.currentTimeMillis()
            // 同一首歌已经失败过就不再自动重试：每 3 秒开一次 USB 会话，
            // 个别机型会把 USB 栈拖死（表现就是"直通一开就崩/一直重连"）。等用户重新点播放。
            if (failure != null && boundUri == uri) return
            if (now - lastAttemptMs < 800) return
            lastAttemptMs = now
            startAt(ctx, uri, pos)
            if (failure != null) return
            // 用户想听（正在播/刚点播放）：先按住 ExoPlayer，等 DAC 缓冲灌满再一起走
            if (c.playWhenReady || c.isPlaying) {
                priming = true
                primeSince = System.currentTimeMillis()
                DacPlayer.primeHold()
                c.pause()
            } else if (!c.isPlaying) {
                c.play()
            }
            return
        }
        if (DacPlayer.error != null) {
            failure = DacPlayer.error
            return
        }
        if (c.isPlaying && !DacPlayer.isPlaying) DacPlayer.resume()
        else if (!c.isPlaying && DacPlayer.isPlaying) DacPlayer.pause()
        format = DacPlayer.streamLabel
    }

    /** 用户拖动进度条：ExoPlayer 已经 seek 过，直通会话要从新位置重开。 */
    fun restart(ctx: Context, player: Player?) {
        val c = player ?: return
        if (!enabled(ctx)) return
        val uri = c.currentMediaItem?.localConfiguration?.uri?.toString() ?: return
        val wantPlay = c.playWhenReady || c.isPlaying
        startAt(ctx, uri, c.currentPosition.coerceAtLeast(0L))
        if (failure == null && wantPlay) {
            priming = true
            primeSince = System.currentTimeMillis()
            DacPlayer.primeHold()
            c.pause()
        }
    }

    /** 退出直通（关独占/拔线）：恢复 ExoPlayer 音量，别让播放器一直静音。 */
    fun stop(ctx: Context, player: Player?) {
        DacPlayer.softStop()
        reset()
        player?.volume = 1f
    }

    private fun startAt(ctx: Context, uri: String, positionMs: Long) {
        // 没授权就一步都不要碰设备：open/claim 一次失败就够写进系统日志，两次就可能把驱动拽崩
        if (!hasPermission(ctx)) {
            needsPermission = true
            failure = null
            format = ""
            return
        }
        DacPlayer.softStop()
        boundUri = uri
        val err = DacPlayer.start(ctx, uri, positionMs)
        failure = err
        format = if (err == null) DacPlayer.streamLabel else ""
    }
}
