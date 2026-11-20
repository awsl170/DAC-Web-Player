package com.dacweb.player.audio

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.dacweb.player.log.AppLog
import com.dacweb.player.usb.DacSession
import com.dacweb.player.usb.UacIso
import java.nio.ByteOrder

/**
 * DAC 独占播放：MediaExtractor + MediaCodec 解成 float PCM，再按 DAC 的容器位深
 * 打包后写进 native 的等时环形缓冲。
 *
 * 和 ExoPlayer 是两条独立的输出路径：开启直通时会先 pause 掉 ExoPlayer，
 * 由这里直接驱动 USB 设备。
 */
object DacPlayer {

    private const val TAG = "DacPlayer"

    /** 软件衰减的上限：到这个值就直接当静音（0 增益），别留 -96 dB 的底噪。 */
    private const val SILENT_DB = 96

    /** 直通期间持有，防止熄屏/切后台时 CPU 睡下去导致等时包断流。 */
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    @Volatile
    var isPlaying = false
        private set

    @Volatile
    var durationMs = 0L
        private set

    @Volatile
    var error: String? = null
        private set

    @Volatile
    var started = false
        private set

    @Volatile
    private var running = false

    private var session: DacSession? = null
    private var thread: Thread? = null
    private var rate = 48_000
    private var startFrames = 0L
    /** 本次直通是从源文件的第几毫秒起播（播放页 seek 后重启直通用）。 */
    private var startOffsetMs = 0L

    /** 当前直通会话的输出格式，播放页状态框直接显示（"44.1k · 16bit · 直通"）。 */
    /** 当前软件增益（线性），换曲淡出用。 */
    @Volatile
    private var lastGain = 1f

    /** 当前会话是否已经自然播完（用于避免播放页在结尾反复重开直通）。 */
    @Volatile
    var finished = false
        private set

    @Volatile
    var streamLabel = ""
        private set

    /** 兼容模式下目标采样率≠源采样率时的线性重采样器；直通模式下永远是 null。 */
    private var resampler: LinearResampler? = null
    /** 每次 start 只打一条 first write，便于判断数据到底有没有进 USB。 */
    private var firstWriteNote = true

    private val logLines = ArrayDeque<String>()

    /** 真机插着 DAC 时没有 adb，诊断信息只能自己攒着给界面看。 */
    fun note(msg: String) {
        // 正式版默认不记录：只有 debug 包或设置里开了「详细调试日志」才攒
        if (!AppLog.verbose) return
        synchronized(logLines) {
            logLines.addLast(msg)
            while (logLines.size > 40) logLines.removeFirst()
        }
        AppLog.i(TAG, msg)
    }

    fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private val lock = Any()

    /** 收尾只做一次：解码线程自然结束与 [stop] 会同时到达，重复 nativeStop/close 会让 USB 栈崩。 */
    private val cleanedUp = java.util.concurrent.atomic.AtomicBoolean(true)

    private fun teardownOnce() {
        if (!cleanedUp.compareAndSet(false, true)) return
        releaseWake()
        hwVolume = false
        UacIso.stop()
        runCatching { session?.close() }
        session = null
    }

    /** 直通解码期间持 PARTIAL_WAKE_LOCK：否则熄屏/切后台后 CPU 被降频甚至挂起，等时包跟不上就卡顿。 */
    private fun acquireWake(ctx: Context) {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "dac:iso").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { AppLog.w(TAG, "acquireWake 失败：${it.message}") }
    }

    private fun releaseWake() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }
    private val opLock = Any()

    /** dB 衰减 → 线性增益（0 dB = 1.0，60 dB ≈ 0.001）。 */
    fun gainLinear(db: Int): Float = Math.pow(10.0, -db / 20.0).toFloat()

    /** 直通播放期间实时调软件衰减（dB，越大越小声）；native 侧对正在发包的数据立即生效。 */
    /**
     * 直通音量统一入口：DAC 有 FEATURE_UNIT 音量就写硬件（软件增益保持 1.0，避免两级衰减），
     * 没有才退回 native 软件衰减。percent 0..100，100 = 设备最大音量。
     */
    fun applyVolumePercent(ctx: Context, percent: Int): Boolean {
        val p = percent.coerceIn(0, 100)
        com.dacweb.player.prefs.AppPrefs.setDacVolumePercent(ctx, p)
        val s = session
        if (s != null && s.hasHardwareVolume) {
            val ok = s.setVolumePercent(p)
            if (ok) {
                hwVolume = true
                lastGain = 1f
                UacIso.setGain(1f)
                return true
            }
            hwVolume = false
        }
        setGainDb(percentToDb(p))
        return false
    }

    /** 0..100% → 软件衰减 dB（100% = 0 dB，0% = 静音）。 */
    private fun percentToDb(p: Int): Int {
        if (p <= 0) return SILENT_DB
        return ((100 - p) * 60 + 50) / 100
    }

    /** 当前是否真的在用硬件音量（写入并回读校验通过的才算；给 UI 显示"硬件/软件"用）。 */
    fun hardwareVolume(): Boolean = hwVolume

    @Volatile
    private var hwVolume = false

    private fun applyVolume(s: DacSession, ctx: Context) {
        val p = com.dacweb.player.prefs.AppPrefs.dacVolumePercent(ctx)
        if (s.hasHardwareVolume && s.setVolumePercent(p)) {
            hwVolume = true
            lastGain = 1f
            note("硬件音量：$p%（${DacSession.db(s.currentVolume ?: 0)}）")
        } else {
            hwVolume = false
            // 硬件音量写了也没生效：退回软件衰减，并按当前百分比换算（不再用固定的 dacGainDb）
            val db = percentToDb(p)
            lastGain = if (db >= SILENT_DB) 0f else gainLinear(db)
            note("软件音量：$p% ≈ -$db dB（硬件音量不可用：${DacSession.lastVolumeProbe.ifBlank { "未探测到 FEATURE_UNIT 音量" }}）")
        }
        UacIso.setGain(lastGain)
    }

    fun setGainDb(db: Int) {
        lastGain = if (db >= SILENT_DB) 0f else gainLinear(db)
        UacIso.setGain(lastGain)
    }

    fun audioDevice(usb: UsbManager): UsbDevice? = usb.deviceList.values.firstOrNull { d ->
        (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }
    }

    /** 源文件的音频参数；完全直通模式下 DAC 的采样率/位深直接取这里的值。 */
    private data class SourceProbe(
        val mime: String,
        val rate: Int,
        val channels: Int,
        val pcmEnc: Int,
        /** 容器头里写的位深（FLAC STREAMINFO 之类），0 表示源文件没写 */
        val bits: Int = 0,
        /** extractor 读一个 sample 得到的字节数（对 audio/raw 就是"一帧"= 声道 × 每样本字节） */
        val frameBytes: Int = 0
    )

    /** 位深 → 每个样本的字节数。 */
    private fun depthBytes(depth: Int): Int = when (depth) {
        16 -> 2
        24 -> 3
        else -> 4
    }

    /** 源 PCM 编码 → 位深；audio/raw 用它决定直通时的容器。 */
    private fun srcDepthOf(pcmEnc: Int): Int = when (pcmEnc) {
        AudioFormat.ENCODING_PCM_FLOAT -> 32
        21 -> 24   // AudioFormat.ENCODING_PCM_24BIT（API 31，这里写字面量免得 lint 拦编译）
        22 -> 32   // AudioFormat.ENCODING_PCM_32BIT
        else -> 16
    }

    /**
     * 用解码器问"真话"：它实际吐出来的采样率/声道/位深。
     *
     * 之前的做法是信容器头（FLAC STREAMINFO 的 bits-per-sample）。但 Android 的
     * FLAC/ALAC 解码器经常把 24bit 源降成 16bit 输出（或反过来用 float 输出），
     * 我们却按容器位深去建 24bit 等时流，于是"按 24bit 送 16bit 样本"——
     * 声音发虚/发怪，遇到声道或字节排布再对不上就是噪声。
     * 解码器自己报的 KEY_PCM_ENCODING 才是最终送进 USB 的那份数据的格式。
     */
    private fun probeDecoded(ctx: Context, url: String): SourceProbe? {
        var ex: MediaExtractor? = null
        var mc: MediaCodec? = null
        return try {
            ex = openExtractor(ctx, url)
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/") && m != "audio/raw") { fmt = f; break }
            }
            if (fmt == null) return null
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null
            mc = MediaCodec.createDecoderByType(mime)
            mc.configure(fmt, null, null, 0)
            mc.start()
            val info = MediaCodec.BufferInfo()
            val deadline = System.currentTimeMillis() + 600
            var outFmt: MediaFormat? = null
            while (System.currentTimeMillis() < deadline && outFmt == null) {
                val inIdx = mc.dequeueInputBuffer(20_000)
                if (inIdx >= 0) {
                    val ib = mc.getInputBuffer(inIdx)
                    val n = if (ib == null) -1 else ex.readSampleData(ib, 0)
                    if (n < 0) {
                        mc.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        mc.queueInputBuffer(inIdx, 0, n, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
                when (val oi = mc.dequeueOutputBuffer(info, 20_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFmt = mc.outputFormat
                    in 0..Int.MAX_VALUE -> {
                        outFmt = mc.outputFormat
                        mc.releaseOutputBuffer(oi, false)
                    }
                    else -> Unit
                }
            }
            val of = outFmt ?: return null
            val rate = if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) of.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
            val ch = if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) of.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0
            val enc = if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                of.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else AudioFormat.ENCODING_PCM_16BIT
            note("解码器实测：rate=$rate ch=$ch pcmEnc=$enc")
            SourceProbe("audio/decoded", rate, ch, enc, srcDepthOf(enc))
        } catch (e: Exception) {
            note("解码器探测失败：" + e.javaClass.simpleName + " " + e.message)
            null
        } finally {
            runCatching { mc?.stop() }
            runCatching { mc?.release() }
            runCatching { ex?.release() }
        }
    }

    /** 开启直通播放。返回 null 表示成功，否则是失败原因。 */
    /** 只读源文件的采样率/声道/位深，用来决定 DAC 该切到哪个采样率。 */
    private fun probeSource(ctx: Context, url: String): SourceProbe {
        var ex: MediaExtractor? = null
        return try {
            ex = openExtractor(ctx, url)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                val r = if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                } else 0
                val ch = if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else 0
                val enc = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else -1
                // 24bit 的 FLAC/ALAC 是压缩格式，mime 不是 audio/raw，只能从容器头读真实位深，
                // 否则会一律按 16bit 建流（之前的 24bit 源被降成 16bit 就是这个原因）。
                val bits = runCatching { f.getInteger("bits-per-sample") }.getOrDefault(0)
                // 最可靠的一招：让 extractor 真读一帧，size / 声道数就是每个样本占几个字节。
                // 不依赖文件头解析（WAV 的 fmt 块有 EXTENSIBLE 变体，24bit 还可能装在
                // 32bit 槽里），也不依赖 pcm-encoding（真机上它对 24bit WAV 一律报 16bit）。
                var frame = 0
                if (mime == "audio/raw") {
                    frame = runCatching {
                        ex.selectTrack(i)
                        val buf = java.nio.ByteBuffer.allocateDirect(64 * 1024)
                        val n = ex.readSampleData(buf, 0)
                        if (n > 0) n else 0
                    }.getOrDefault(0)
                }
                note("probe: mime=$mime rate=$r ch=$ch pcmEnc=$enc bits=$bits frame=$frame")
                return SourceProbe(mime, r, ch, enc, bits, frame)
            }
            note("probe: 没有音频轨")
            SourceProbe("?", 0, 0, -1)
        } catch (e: Exception) {
            note("probe 失败：" + e.javaClass.simpleName + " " + e.message)
            SourceProbe("?", 0, 0, -1)
        } finally {
            runCatching { ex?.release() }
        }
    }

    /** http(s) 走自带 UA 的数据源，本地文件交给系统。 */
    private fun openExtractor(ctx: Context, url: String): MediaExtractor {
        val ex = MediaExtractor()
        if (url.startsWith("http://") || url.startsWith("https://")) {
            ex.setDataSource(
                HttpMediaSource(
                    url,
                    mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                        "Accept" to "*/*"
                    )
                )
            )
        } else {
            ex.setDataSource(ctx, android.net.Uri.parse(url), null)
        }
        return ex
    }

    fun start(ctx: Context, url: String, startMs: Long = 0L): String? = synchronized(opLock) {
        startLocked(ctx, url, startMs)
    }

    private fun startLocked(ctx: Context, url: String, startMs: Long): String? {
        softStop()
        synchronized(logLines) { logLines.clear() }
        if (!UacIso.available()) return "native 库没加载".also { note("native 库没加载") }
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        note("devices=" + usb.deviceList.values.joinToString { d ->
            d.deviceName + ":" + d.vendorId.toString(16) + "/" + d.productId.toString(16)
        })
        val device = audioDevice(usb) ?: return "没有找到 USB 音频设备"
        if (!usb.hasPermission(device)) return "没有 USB 访问权限"

        val s = DacSession.open(ctx, device, exclusive = true)
            ?: return "打开 DAC 失败（可能被系统音频占用）".also { note("open 失败") }
        cleanedUp.set(false)
        // 先接管 AudioControl：不接管的话后面所有 SET_CUR/GET_CUR 都会被内核挡掉
        // （日志表现为 setSampleRate 被拒绝、回读 null、vol=null），设备就按默认时钟跑 ⇒ 变调。
        val acOk = s.claimControl()
        note("控制接口：AC=${if (acOk) "已接管" else "未接管"}，硬件音量=${if (s.hasHardwareVolume) "有" else "无"}")
        note("uac2=${s.uac2} clock=${s.clockLabel} vol=${s.volumeRange?.joinToString()}")
        note("音量探测：${DacSession.lastVolumeProbe.ifBlank { "无结果" }}")
        note("rates=" + s.rates.take(8).joinToString())
        val probe = probeSource(ctx, url)

        // 完全直通：DAC 的位深/采样率跟源文件一模一样，只让 native 侧做音量衰减；
        // 兼容模式：位深按用户选择，采样率与源不同时用线性重采样补上（否则会变速）。
        val bitPerfect = com.dacweb.player.prefs.AppPrefs.dacBitPerfect(ctx)
        // 本地文件优先走 FFmpeg：位深/采样率由库给出，不再依赖 MediaExtractor 那堆互相矛盾的字段。
        if (isLocalFile(url) && FfAudio.available()) {
            return startFfmpeg(ctx, url, s, bitPerfect, startMs)
        }
        // 源位深：PCM 轨看 pcm-encoding，压缩轨（FLAC/ALAC/WAV 头）看容器 bits-per-sample，
        // 两者都有时取大的——宁可 24bit 容器装 16bit 样本，也不要把 24bit 样本塞进 16bit 容器。
        val containerBits = if (probe.bits >= 16) probe.bits else 0
        val pcmBits = if (probe.pcmEnc > 0) srcDepthOf(probe.pcmEnc) else 0
        // audio/raw 的字节排布 extractor 直接告诉我们；压缩轨则问解码器（容器头不可信）。
        val decoded = if (probe.mime != "audio/raw") probeDecoded(ctx, url) else null
        // audio/raw 的字节布局以文件头为准：真机上 24bit/96k 的 WAV 会同时报
        // pcmEnc=16bit 与 bits=24，两个字段打架；按 2 字节去读 3 字节样本就是"丝丝声"。
        val headerBytes = if (probe.mime == "audio/raw") PcmHeader.bytesPerSample(ctx, url) else 0
        // 每样本字节数的三级来源，按可信度排：
        //  1) extractor 报的 bits-per-sample（WAV 的 fmt 块 / FLAC 的 STREAMINFO，都是文件自身写的）
        //  2) 自己解析文件头（兼容块对齐等边角，可能认错，见下面的 hex 诊断）
        //  3) pcm-encoding（真机上对 24bit WAV 一律报 16bit，最不可信）
        val bitsBytes = if (probe.bits in 16..32) (probe.bits + 7) / 8 else 0
        val hdrOk = headerBytes in 1..4 && headerBytes * 8 >= probe.bits
        val rawBytes = when {
            hdrOk -> headerBytes
            bitsBytes in 1..4 -> bitsBytes
            headerBytes in 1..4 -> headerBytes
            else -> 0
        }
        if (probe.mime == "audio/raw") {
            val hdr = PcmHeader.info(ctx, url)
            note("源布局：bits=${probe.bits} ⇒ ${bitsBytes}B；文件头 ⇒ ${headerBytes}B" +
                "（${hdr?.bits ?: 0}bit/${hdr?.channels ?: 0}ch）；pcmEnc 说 ${pcmBits}bit ⇒ 采用 ${rawBytes}B")
            note("文件头 hex: " + PcmHeader.headHex(ctx, url, 64))
        }
        val srcDepth = when {
            probe.mime == "audio/raw" && rawBytes > 0 -> rawBytes * 8
            probe.mime == "audio/raw" -> maxOf(pcmBits, containerBits).takeIf { it > 0 } ?: 16
            // 解码器吐 float：数据足够精确，位深按容器头（16/24/32）来，DAC 不支持时下面会回落
            decoded != null && decoded.pcmEnc == AudioFormat.ENCODING_PCM_FLOAT ->
                containerBits.takeIf { it in 16..32 } ?: 24
            // 解码器吐定点：它说几位就是几位，绝不能虚报
            decoded != null -> decoded.bits.coerceIn(16, 32)
            else -> 16
        }
        val depth = if (bitPerfect) srcDepth else com.dacweb.player.prefs.AppPrefs.dacDepth(ctx)
        val prefRate = com.dacweb.player.prefs.AppPrefs.dacRate(ctx)
        val srcRate = probe.rate.takeIf { it > 0 } ?: (decoded?.rate ?: 0)
        val want = when {
            bitPerfect -> srcRate
            prefRate > 0 -> prefRate
            else -> probe.rate
        }
        val params = s.streamFor(depth, want) ?: run { s.close(); return "DAC 没有可用的等时输出" }
        rate = when {
            // 描述符里没有采样率列表时不做否定判断，仍按源采样率送（直通优先）
            want > 0 && (params.rates.isEmpty() || want in params.rates) -> want
            bitPerfect && want > 0 -> {
                // 直通不允许重采样：DAC 不支持源采样率就明确报错，别悄悄变速
                s.close()
                return "DAC 不支持 ${want} Hz（完全直通不重采样），请切到兼容模式"
            }
            want > 0 -> s.pickRate(want) ?: want
            else -> s.pickRate(48_000) ?: s.samplingRate ?: 48_000
        }
        if (bitPerfect && params.bps != depthBytes(depth)) {
            note("注意：DAC 没有 ${depth}bit 等时格式，实际用 ${params.bps * 8}bit 容器")
        }
        // 先写时钟再选 alt（与内核 snd_usb_pcm_prepare 一致），否则设备按默认时钟锁死流
        val clockOk = s.setSampleRate(rate)
        note("时钟：请求 ${rate}Hz → ${if (clockOk) "ok" else "被拒绝"}，回读 ${s.readSampleRate()}")
        if (!s.claimStream()) {
            val why = s.claimError ?: "无法接管 USB 音频接口"
            s.close()
            return why
        }
        note("接口：AC=${if (s.acClaimed) "已接管" else "未接管"}，硬件音量=${if (s.hasHardwareVolume) "有" else "无"}")

        applyVolume(s, ctx)

        val bufKb = com.dacweb.player.prefs.AppPrefs.dacBufferKb(ctx)
        UacIso.setBufferKb(bufKb)
        UacIso.setUrbs(32)
        note("缓冲：请求 ${bufKb} KB（96k/24bit 约 ${"%.1f".format(bufKb * 1024.0 / 576000.0)} s）")

        val rc = UacIso.nativeStreamStart(
            s.fileDescriptor, params.iface, params.alt, params.endpoint,
            rate, params.channels, params.bps, 8, params.maxPacket
        )
        note("stream: iface=${params.iface} alt=${params.alt} ep=${params.endpoint} " +
            "ch=${params.channels} bps=${params.bps} rate=$rate maxPacket=${params.maxPacket} " +
            "bitRes=${params.bitResolution} subslot=${params.subslotSize} rc=$rc")
        if (rc != 0) {
            s.close()
            return "启动等时通道失败（rc=$rc）"
        }

        session = s
        started = true
        acquireWake(ctx)
        startFrames = 0
        startOffsetMs = startMs.coerceAtLeast(0L)
        val modeText = if (bitPerfect) "直通" else "兼容"
        streamLabel = "${rateText(rate)} · ${params.bps * 8}bit · $modeText"
        error = null
        durationMs = 0
        isPlaying = true
        running = true
        finished = false
        resampler = null
        firstWriteNote = true
        thread = Thread {
            decodeLoop(ctx, url, params.channels, params.bps, rate, bitPerfect, srcDepth, startOffsetMs, rawBytes)
        }.apply {
            name = "dac-decode"
            start()
        }
        AppLog.i(TAG, "started: rate=$rate ch=${params.channels} bps=${params.bps}")
        return null
    }

    fun pause() {
        UacIso.nativeSetPaused(true)
        isPlaying = false
    }

    fun resume() {
        if (session == null) return
        UacIso.nativeSetPaused(false)
        isPlaying = true
    }

    fun stop() = synchronized(opLock) {
        stopLocked()
    }

    private fun stopLocked() {
        running = false
        isPlaying = false
        started = false
        thread?.join(1500)
        thread = null
        synchronized(lock) {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            codec = null
            runCatching { extractor?.release() }
            extractor = null
        }
        teardownOnce()
    }

    /**
     * 换曲/退出直通前用：先把软件增益压到 0 让正在排队的包淡出（native 每包都乘 g_gain），
     * 再真的停流。直接 stop 会在最后一个满幅包上硬切，耳机里就是一声"啪"。
     */
    fun softStop() = synchronized(opLock) {
        softStopLocked()
    }

    private fun softStopLocked() {
        if (!started) { stop(); return }
        // 分 6 步把增益压到 0（约 70ms）：一步到位会在最后几包上听到"啪"，
        // 这也是切歌时轻微爆音的来源。
        val base = lastGain.coerceIn(0f, 1f)
        for (i in 1..6) {
            UacIso.setGain(base * (1f - i / 6f))
            try { Thread.sleep(10) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
        UacIso.setGain(0f)
        try { Thread.sleep(20) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        stop()
    }

    private fun rateText(hz: Int): String =
        if (hz % 1000 == 0) "${hz / 1000}k" else String.format(java.util.Locale.US, "%.1fk", hz / 1000.0)

    fun positionMs(): Long {
        if (rate <= 0) return 0
        val frames = UacIso.frames() - startFrames
        val base = startOffsetMs
        return if (frames <= 0) base else base + frames * 1000L / rate
    }

    /**
     * 线性插值重采样：只在"兼容模式 + 目标采样率≠源采样率"时启用。
     * 之前直接把 44.1k 的样本按 96k 的时钟送出去，就是"改采样率连速度一起变"的原因。
     */
    private class LinearResampler(private val srcRate: Int, private val dstRate: Int, private val ch: Int) {
        private var carry = FloatArray(0)
        private var pos = 0.0

        fun process(input: FloatArray, inFrames: Int, sink: (FloatArray, Int) -> Unit) {
            if (inFrames <= 0 || ch <= 0) return
            val hasCarry = carry.isNotEmpty()
            val total = inFrames + if (hasCarry) 1 else 0
            if (total < 2) return
            val buf = FloatArray(total * ch)
            var w = 0
            if (hasCarry) {
                System.arraycopy(carry, 0, buf, 0, ch)
                w = ch
            }
            System.arraycopy(input, 0, buf, w, inFrames * ch)

            val step = srcRate.toDouble() / dstRate.toDouble().coerceAtLeast(1.0)
            var t = pos
            val maxOut = (total / step).toInt() + 2
            val out = FloatArray(maxOut * ch)
            var n = 0
            while (t <= total - 2 && n < maxOut) {
                val i0 = t.toInt()
                val frac = (t - i0).toFloat()
                val b0 = i0 * ch
                val b1 = (i0 + 1) * ch
                for (c in 0 until ch) {
                    val a = buf[b0 + c]
                    out[n * ch + c] = a + (buf[b1 + c] - a) * frac
                }
                n++
                t += step
            }
            if (n > 0) sink(out, n)

            // 留最后一帧给下一块做插值起点；pos 换算成"相对这一帧"的位置
            carry = FloatArray(ch)
            System.arraycopy(buf, (total - 1) * ch, carry, 0, ch)
            pos = (t - (total - 1)).coerceAtLeast(0.0)
        }
    }

    /** 源采样率与 DAC 采样率不同才需要重采样；相同（含完全直通）时保持 null，走原样路径。 */
    private fun ensureResampler(srcRate: Int, srcCh: Int, outRate: Int) {
        resampler = if (srcRate > 0 && outRate > 0 && srcRate != outRate) {
            LinearResampler(srcRate, outRate, srcCh)
        } else {
            null
        }
    }

    /** audio/raw：直接从 extractor 读 PCM。直通且格式一致时原样送，否则按 DAC 容器重打包。 */
    private fun decodeRawPcm(
        ex: MediaExtractor,
        srcChannels: Int,
        pcmEnc: Int,
        outChannels: Int,
        bytesPerSample: Int,
        passthrough: Boolean,
        srcBytes: Int = 0
    ) {
        val isFloat = pcmEnc == AudioFormat.ENCODING_PCM_FLOAT && srcBytes == 4
        // 文件头说了算（见 PcmHeader）：pcm-encoding 在这台机器上会把 24bit 报成 16bit
        val bytesPerSrcSample = if (srcBytes in 1..4) srcBytes else if (isFloat) 4 else when (pcmEnc) {
            21 -> 3   // 24bit
            22 -> 4   // 32bit
            else -> 2
        }
        val inBuf = ByteArray(256 * 1024)
        val bb = java.nio.ByteBuffer.wrap(inBuf).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        while (running) {
            val size = ex.readSampleData(bb, 0)
            if (size < 0) {
                note("raw 读到结尾")
                break
            }
            if (size == 0) {
                ex.advance()
                continue
            }
            if (passthrough) {
                // 位深/声道/采样率全跟源一致：一个字节都不动，只让 native 乘音量
                val w = UacIso.write(inBuf, size)
                if (firstWriteNote) {
                    firstWriteNote = false
                    note("raw passthrough first write: bytes=$size written=$w rb=${UacIso.buffered()}")
                    // 头 24 字节十六进制：真·24bit 音频的前几个样本很小且相邻变化平缓；
                    // 若是噪声/错位，数值会满量程乱跳，一眼就能看出来。
                    val n = minOf(24, size)
                    val hex = StringBuilder()
                    for (i in 0 until n) hex.append(String.format("%02x", inBuf[i]))
                    note("raw 前 ${n} 字节: $hex")
                }
                ex.advance()
                continue
            }
            val totalSamples = size / bytesPerSrcSample
            val frames = totalSamples / srcChannels
            if (frames > 0) {
                val fb = FloatArray(frames * srcChannels)
                for (i in 0 until frames * srcChannels) {
                    fb[i] = when {
                        isFloat -> bb.getFloat(i * 4)
                        bytesPerSrcSample == 3 -> {
                            val o = i * 3
                            val v = (inBuf[o].toInt() and 0xFF) or
                                ((inBuf[o + 1].toInt() and 0xFF) shl 8) or
                                ((inBuf[o + 2].toInt() and 0xFF) shl 16)
                            ((if (v and 0x800000 != 0) v or 0xFF000000.toInt() else v).toFloat()) / 8388608f
                        }
                        bytesPerSrcSample == 4 -> bb.getInt(i * 4) / 2147483648f
                        else -> bb.getShort(i * 2) / 32768f
                    }
                }
                emit(fb, frames, srcChannels, outChannels, bytesPerSample)
            }
            ex.advance()
        }
    }

    /** 有重采样器就先重采样，再打包成 DAC 容器位深写进等时缓冲。 */
    private fun emit(src: FloatArray, frames: Int, srcCh: Int, outCh: Int, bps: Int) {
        val rs = resampler
        if (rs != null) {
            rs.process(src, frames) { buf, n -> writePacked(buf, n, srcCh, outCh, bps) }
        } else {
            writePacked(src, frames, srcCh, outCh, bps)
        }
    }

    private fun writePacked(src: FloatArray, frames: Int, srcCh: Int, outCh: Int, bps: Int) {
        if (frames <= 0) return
        val out = ByteArray(frames * outCh * bps)
        var o = 0
        for (f in 0 until frames) {
            val base = f * srcCh
            for (c in 0 until outCh) {
                val v = if (srcCh > 0) src[base + (if (c < srcCh) c else 0)] else 0f
                o = writeSampleRaw(out, o, v, bps)
            }
        }
        if (o > 0) {
            val w = UacIso.write(out, o)
            if (firstWriteNote) {
                firstWriteNote = false
                note("first write: bytes=$o written=$w rb=${UacIso.buffered()}")
            }
        }
    }

    private fun writeSampleRaw(out: ByteArray, at: Int, v: Float, bytesPerSample: Int): Int {
        var o = at
        when (bytesPerSample) {
            2 -> {
                val s = (v * 32767f).toInt().coerceIn(-32768, 32767)
                out[o++] = (s and 0xFF).toByte()
                out[o++] = ((s shr 8) and 0xFF).toByte()
            }
            3 -> {
                // 饱和：float 源偶尔略超 ±1.0，不夹住会溢出到第 4 字节（被丢弃）→ 爆音
                val s = (v * 8388607f).toInt().coerceIn(-8388608, 8388607)
                out[o++] = (s and 0xFF).toByte()
                out[o++] = ((s shr 8) and 0xFF).toByte()
                out[o++] = ((s shr 16) and 0xFF).toByte()
            }
            else -> {
                val s = (v * 2147483520f).toLong().toInt()
                out[o++] = (s and 0xFF).toByte()
                out[o++] = ((s shr 8) and 0xFF).toByte()
                out[o++] = ((s shr 16) and 0xFF).toByte()
                out[o++] = ((s shr 24) and 0xFF).toByte()
            }
        }
        return o
    }


    // ------------------------------------------------------------ FFmpeg 路线

    private fun isLocalFile(url: String): Boolean {
        val u = android.net.Uri.parse(url)
        return u.scheme == null || u.scheme == "file" || u.scheme == "content"
    }

    /**
     * 本地文件走 FFmpeg 解码：`bits_per_raw_sample` 决定每样本字节数，swresample 直接
     * 输出 DAC 要的宽度（16 / 24(3字节) / 32）。宿主侧不再判断容器位深。
     */
    private fun startFfmpeg(ctx: Context, url: String, s: DacSession, bitPerfect: Boolean, startMs: Long): String? {
        var tr = FfAudio.open(ctx, url, 0, 0)
            ?: return "FFmpeg 打不开这个文件（该容器的解复用器或解码器可能没编进库，细节见 logcat 的 ffaudio）"
        note("ffmpeg: src=${tr.srcRate}Hz/${tr.srcChannels}ch/${tr.srcBits}bit float=${tr.sourceFloat}")
        val depth = if (bitPerfect) tr.outBytes * 8 else com.dacweb.player.prefs.AppPrefs.dacDepth(ctx)
        val prefRate = com.dacweb.player.prefs.AppPrefs.dacRate(ctx)
        val want = if (bitPerfect) tr.srcRate else if (prefRate > 0) prefRate else tr.srcRate

        val params = s.streamFor(depth, want) ?: run {
            FfAudio.close(tr)
            s.close()
            return "DAC 没有可用的等时输出"
        }
        var outRate = when {
            want > 0 && (params.rates.isEmpty() || want in params.rates) -> want
            bitPerfect && want > 0 -> {
                FfAudio.close(tr)
                s.close()
                return "DAC 不支持 ${want} Hz（完全直通不重采样），请切到兼容模式"
            }
            want > 0 -> s.pickRate(want) ?: want
            else -> s.pickRate(48_000) ?: s.samplingRate ?: 48_000
        }
        // 关键顺序：内核 snd-usb-audio 也是先写时钟（SET_CUR）再 SET_INTERFACE(alt)。
        // 反过来先选 alt，设备会按默认时钟把流锁死，之后写时钟不生效 ⇒ 变调 / 兼容模式没声音。
        val clockOk = s.setSampleRate(outRate)
        note("时钟：请求 ${outRate}Hz → ${if (clockOk) "ok" else "被拒绝"}，回读 ${s.readSampleRate()}")
        if (!s.claimStream()) {
            val why = s.claimError ?: "无法接管 USB 音频接口"
            FfAudio.close(tr)
            s.close()
            return why
        }
        note("接口：AC=${if (s.acClaimed) "已接管" else "未接管"}，硬件音量=${if (s.hasHardwareVolume) "有" else "无"}")
        if (tr.outBytes != params.bps || tr.outRate != outRate) {
            note("ffmpeg 重开：${tr.outBytes}B/${tr.outRate}Hz → ${params.bps}B/${outRate}Hz")
            FfAudio.close(tr)
            tr = FfAudio.open(ctx, url, outRate, params.bps) ?: return "FFmpeg 重开失败"
            outRate = tr.outRate
        }
        if (bitPerfect && params.bps != tr.outBytes) {
            note("注意：DAC 没有 ${tr.outBytes * 8}bit 等时格式，实际用 ${params.bps * 8}bit 容器")
        }
        applyVolume(s, ctx)

        val bufKb = com.dacweb.player.prefs.AppPrefs.dacBufferKb(ctx)
        UacIso.setBufferKb(bufKb)
        UacIso.setUrbs(32)
        note("缓冲：请求 ${bufKb} KB（96k/24bit 约 ${"%.1f".format(bufKb * 1024.0 / 576000.0)} s）")

        val rc = UacIso.nativeStreamStart(
            s.fileDescriptor, params.iface, params.alt, params.endpoint,
            outRate, params.channels, params.bps, 8, params.maxPacket
        )
        note("stream(ffmpeg): iface=${params.iface} alt=${params.alt} ep=${params.endpoint} " +
            "ch=${params.channels} bps=${params.bps} rate=$outRate bitRes=${params.bitResolution} " +
            "subslot=${params.subslotSize} rc=$rc")
        if (rc != 0) {
            FfAudio.close(tr)
            s.close()
            return "启动等时通道失败（rc=$rc）"
        }

        session = s
        started = true
        acquireWake(ctx)
        startFrames = 0
        val durMs = tr.durationMs
        var seekTarget = startMs.coerceAtLeast(0L)
        if (durMs > 0 && seekTarget > durMs - 500) {
            val clamped = (durMs - 500).coerceAtLeast(0L)
            note("seek 目标 ${seekTarget}ms 超出时长 ${durMs}ms，夹到 ${clamped}ms")
            seekTarget = clamped
        }
        startOffsetMs = seekTarget
        note("时长：ffmpeg 报 ${durMs}ms，实际从 ${seekTarget}ms 开始")
        rate = outRate
        val modeText = if (bitPerfect) "直通" else "兼容"
        streamLabel = "${rateText(outRate)} · ${params.bps * 8}bit · $modeText"
        error = null
        durationMs = tr.durationMs
        isPlaying = true
        running = true
        finished = false
        firstWriteNote = true
        thread = Thread { decodeLoopFf(tr, params.channels, params.bps, outRate) }.apply {
            name = "dac-decode-ff"
            start()
        }
        return null
    }

    private fun decodeLoopFf(tr: FfAudio.Track, outCh: Int, bps: Int, outRate: Int) {
        // 提权到音频线程优先级：切后台/熄屏时普通线程会被调度器压后，等时包就断流了
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        val buf = ByteArray(32 * 1024)
        var reads = 0L
        var bytes = 0L
        var zeroReads = 0
        var skipMs = 0L
        var lastStat = System.currentTimeMillis()
        try {
            if (startOffsetMs > 0) {
                // 不用 av_seek_frame：它对自定义 AVIO / 尺寸未知的 WAV 会卡死或落到数据块外
                // （真机上表现为"从中间播放"整首静音、界面像卡住）。这里改用解码丢弃来定位。
                val t0 = System.currentTimeMillis()
                val r = FfAudio.skipTo(tr, startOffsetMs)
                skipMs = System.currentTimeMillis() - t0
                note("跳过：${startOffsetMs}ms → r=$r，用时 ${skipMs}ms（时长 ${tr.durationMs}ms）")
                if (r != 0) {
                    error = "定位失败（超出文件末尾或解码出错）"
                    note("定位失败：r=$r")
                    finished = true
                    return
                }
            }
            while (running) {
                val cap = UacIso.capacity()
                // 缓冲放到 3/4 才开始压着写：阈值必须跟着真实容量走，
                // 写死一个比容量还大的数就永远不会触发（旧代码 1.4 MB vs 512 KB 就是这个坑）。
                if (cap > 0 && UacIso.buffered() > cap * 3 / 4) {
                    Thread.sleep(3)
                    continue
                }
                val n = FfAudio.read(tr, buf, buf.size)
                if (n < 0) {
                    val why = FfAudio.lastError(tr)
                    error = "FFmpeg 解码中断" + if (why.isNotBlank()) "：$why" else ""
                    note("read 出错：$why")
                    break
                }
                if (n == 0) {
                    note("解码结束：reads=$reads bytes=$bytes 欠载=${UacIso.underruns()}")
                    finished = true
                    break
                }
                if (reads == 0L) {
                    val sb = StringBuilder()
                    for (i in 0 until minOf(24, n)) sb.append(String.format("%02x", buf[i]))
                    note("first write: bytes=$n hex=$sb （欠载=${UacIso.underruns()}）")
                    zeroReads = if (n >= 64 && buf.take(64).all { it == 0.toByte() }) 1 else 0
                } else if (zeroReads in 1..4) {
                    if (buf.take(64).all { it == 0.toByte() }) zeroReads++ else zeroReads = 0
                    if (zeroReads == 5) note("前 5 块都是静音（文件开头可能本来就是静音）")
                }
                val w = UacIso.write(buf, n)
                if (w < 0) {
                    error = "等时通道已停止"
                    note("write 失败：n=$n 返回=$w running=${UacIso.running()}")
                    break
                }
                reads++
                bytes += n
                if (reads <= 5) {
                    note("read#$reads n=$n buffered=${UacIso.buffered()}/${UacIso.capacity()} 欠载=${UacIso.underruns()}")
                }
                val now = System.currentTimeMillis()
                if (now - lastStat > 2000) {
                    lastStat = now
                    val st = UacIso.stats()
                    note("stat: reads=$reads buf=${UacIso.buffered()}/${UacIso.capacity()} " +
                        "欠载=${UacIso.underruns()} urb sub=${st[0]} comp=${st[1]} err=${st[2]}")
                }
            }
        } catch (e: Exception) {
            error = "FFmpeg 解码异常：" + e.message
            note("异常：" + e.javaClass.simpleName + " " + e.message)
        } finally {
            isPlaying = false
            teardownOnce()
            FfAudio.close(tr)
        }
    }

    private fun decodeLoop(
        ctx: Context,
        url: String,
        outChannels: Int,
        bytesPerSample: Int,
        outRate: Int,
        bitPerfect: Boolean,
        srcDepth: Int,
        startMs: Long,
        rawBytesHint: Int = 0
    ) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        var ex: MediaExtractor? = null
        var mc: MediaCodec? = null
        var sizeHint: Long = -1L
        try {
            ex = openExtractor(ctx, url)
            if (url.startsWith("http")) note("source http size=$sizeHint")
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) {
                error = "没有音频轨"
                return
            }
            ex.selectTrack(track)
            if (startMs > 0) {
                ex.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                note("seekTo ${startMs}ms")
            }
            extractor = ex
            durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION) / 1000
            } else 0L
            if (durationMs <= 0) {
                // MediaDataSource 输入时 KEY_DURATION 常常缺失，用字节数 / 码率兜底
                val bitrate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                    format.getInteger(MediaFormat.KEY_BIT_RATE)
                } else 0
                if (bitrate > 0) {
                    if (sizeHint > 0) durationMs = sizeHint * 8000L / bitrate
                }
            }
            var srcChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 2
            if (srcChannels <= 0) srcChannels = 2

            val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mp4a-latm"
            val srcRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 0
            val pcmEncIn = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else AudioFormat.ENCODING_PCM_16BIT
            val bitrate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                format.getInteger(MediaFormat.KEY_BIT_RATE)
            } else -1
            note("extractor ok tracks=${ex.trackCount} mime=$mime ch=$srcChannels rate=$srcRate pcmEnc=$pcmEncIn bitrate=$bitrate durMs=$durationMs")

            // 目标采样率与源不同才重采样，保证速度不变；完全直通模式下两者必然相等。
            ensureResampler(srcRate, srcChannels, outRate)

            // 已经是 PCM 的轨（MediaExtractor 报 audio/raw）不需要解码器，直接读样本；
            // 之前把它塞进 MediaCodec 再按 float 读，就是那阵呲呲声的来源。
            if (mime == "audio/raw") {
                // pcmEncIn 才是字节排布的真话（16/24/32bit、float），位深只是它的派生物。
                val srcBytes = if (rawBytesHint in 1..4) rawBytesHint else when (pcmEncIn) {
                    AudioFormat.ENCODING_PCM_FLOAT -> 4
                    21 -> 3
                    22 -> 4
                    else -> 2
                }
                val passthrough = bitPerfect && srcRate == outRate &&
                    srcBytes == bytesPerSample && srcChannels == outChannels
                if (passthrough) note("raw 完全直通：${srcBytes * 8} bit / $srcRate Hz / ${srcChannels}ch")
                decodeRawPcm(ex, srcChannels, pcmEncIn, outChannels, bytesPerSample, passthrough, srcBytes)
                return
            }
            mc = MediaCodec.createDecoderByType(mime)
            // 不强制 float：解码器不一定认这个 key，认了才按 float 读，否则按 16-bit 读回来再转
            var pcmFloat = false
            runCatching {
                format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
                pcmFloat = true
            }
            mc.configure(format, null, null, 0)
            mc.start()
            codec = mc

            val info = MediaCodec.BufferInfo()
            var inputDone = false

            while (running) {
                if (!inputDone) {
                    val inIdx = mc.dequeueInputBuffer(2000)
                    if (inIdx >= 0) {
                        val ib = mc.getInputBuffer(inIdx)
                        val size = if (ib == null) -1 else ex.readSampleData(ib, 0)
                        if (size < 0) {
                            mc.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            mc.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }

                val outIdx = mc.dequeueOutputBuffer(info, 2000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = mc.outputFormat
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        val c = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (c > 0) srcChannels = c
                    }
                    pcmFloat = if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    } else false
                    ensureResampler(srcRate, srcChannels, outRate)
                    note("outFormat ch=$srcChannels pcmFloat=$pcmFloat")
                } else if (outIdx >= 0) {
                    val ob = mc.getOutputBuffer(outIdx)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val totalSamples = if (pcmFloat) info.size / 4 else info.size / 2
                        val fb = if (pcmFloat) {
                            ob.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                        } else {
                            null
                        }
                        val sb = if (!pcmFloat) {
                            ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        } else {
                            null
                        }
                        val frames = totalSamples / srcChannels
                        if (frames > 0) {
                            // 统一成浮点帧，再交给 emit（需要时先重采样，再按 DAC 位深打包）
                            val src = FloatArray(frames * srcChannels)
                            for (i in src.indices) {
                                src[i] = if (pcmFloat) fb!!.get(i) else sb!!.get(i) / 32768f
                            }
                            emit(src, frames, srcChannels, outChannels, bytesPerSample)
                        }
                    }
                    mc.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            note("decode 失败：" + e.javaClass.simpleName + " " + e.message)
        } finally {
            synchronized(lock) {
                runCatching { mc?.stop() }
                runCatching { mc?.release() }
                codec = null
                runCatching { ex?.release() }
                extractor = null
            }
            // running 仍为 true 说明是解码循环自己跑完的（不是 stop() 打断）
            if (running && error == null) finished = true
            isPlaying = false
            running = false
            started = false
            teardownOnce()
        }
    }
}
