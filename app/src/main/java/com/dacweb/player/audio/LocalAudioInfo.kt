package com.dacweb.player.audio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.util.Locale

/**
 * 本地文件的音频规格（播放页左上角展示用）：位深、采样率、平均比特率。
 *
 * 只用 MediaExtractor 读容器头，不解码；读不到的字段就不显示，绝不抛异常。
 */
object LocalAudioInfo {

    data class Info(val bits: Int, val rateHz: Int, val bitrateKbps: Int)

    /**
     * 读文件头；失败返回 null。调用方应在 IO 线程调用。
     *
     * 先走 MediaExtractor（快、不解码）；它读不出来时（.dsf / .dts / .asf 等媒体库不认识的
     * 容器，或字段全为空）再退到 FFmpeg 的容器解析——FFmpeg 那边认识所有我们编进去的解复用器。
     */
    fun read(ctx: Context, uri: Uri): Info? = extractorRead(ctx, uri) ?: ffRead(ctx, uri)

    private fun extractorRead(ctx: Context, uri: Uri): Info? = runCatching {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            if (ex.trackCount <= 0) return null
            val f = ex.getTrackFormat(0)
            val rate = f.intOr(MediaFormat.KEY_SAMPLE_RATE, 0)
            val bits = bitsOf(f)
            val kbps = bitrateKbps(ctx, uri, f)
            if (bits <= 0 && rate <= 0 && kbps <= 0) return@runCatching null
            Info(bits, rate, kbps)
        } finally {
            runCatching { ex.release() }
        }
    }.getOrNull()

    /** FFmpeg 兜底：只读容器/解码器信息，不解码整首歌。 */
    private fun ffRead(ctx: Context, uri: Uri): Info? = runCatching {
        if (!FfAudio.available()) return@runCatching null
        val tr = FfAudio.open(ctx, uri.toString(), 0, 0) ?: return@runCatching null
        try {
            val bits = tr.srcBits
            val rate = tr.srcRate
            val kbps = runCatching {
                val bytes = sizeOf(ctx, uri) ?: return@runCatching 0
                val ms = tr.durationMs
                if (bytes <= 0 || ms <= 0) 0 else (bytes * 8L / ms).toInt()   // 字节*8/毫秒 = kbps
            }.getOrDefault(0)
            if (bits <= 0 && rate <= 0 && kbps <= 0) null else Info(bits, rate, kbps)
        } finally {
            FfAudio.close(tr)
        }
    }.getOrNull()

    /** "24bit · 96kHz · 1024kb/s"，缺失的字段自动省略。 */
    fun format(info: Info?): String {
        if (info == null) return ""
        return buildList {
            if (info.bits > 0) add("${info.bits}bit")
            if (info.rateHz > 0) add(rateLabel(info.rateHz))
            if (info.bitrateKbps > 0) add("${info.bitrateKbps}kb/s")
        }.joinToString(" · ")
    }

    private fun bitsOf(f: MediaFormat): Int {
        if (f.containsKey("bits-per-sample")) {
            val b = f.intOr("bits-per-sample", 0)
            if (b > 0) return b
        }
        // 没有位深字段时按 PCM 编码推：21 = 24bit packed，22 = 32bit float，其余 16bit
        return when (f.intOr("pcm-encoding", 0)) {
            21 -> 24
            22 -> 32
            2 -> 16
            else -> 0
        }
    }

    /** 优先用容器里的 KEY_BIT_RATE；没有就用「文件字节数 / 时长」估算。 */
    private fun bitrateKbps(ctx: Context, uri: Uri, f: MediaFormat): Int {
        val fromFormat = f.intOr(MediaFormat.KEY_BIT_RATE, 0)
        if (fromFormat > 0) return fromFormat / 1000
        val bytes = sizeOf(ctx, uri) ?: return 0
        val durationUs = f.longOr(MediaFormat.KEY_DURATION, 0L)
        if (bytes <= 0 || durationUs <= 0) return 0
        return (bytes * 8_000_000L / durationUs / 1000L).toInt()
    }

    private fun sizeOf(ctx: Context, uri: Uri): Long? = runCatching {
        when (uri.scheme) {
            "file" -> File(uri.path!!).length().takeIf { it > 0 }
            "content" -> ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            else -> null
        }
    }.getOrNull()

    private fun rateLabel(hz: Int): String =
        if (hz % 1000 == 0) "${hz / 1000}kHz"
        else String.format(Locale.US, "%.1fkHz", hz / 1000.0)

    private fun MediaFormat.intOr(key: String, def: Int): Int =
        runCatching { if (containsKey(key)) getInteger(key) else def }.getOrDefault(def)

    private fun MediaFormat.longOr(key: String, def: Long): Long =
        runCatching { if (containsKey(key)) getLong(key) else def }.getOrDefault(def)
}
