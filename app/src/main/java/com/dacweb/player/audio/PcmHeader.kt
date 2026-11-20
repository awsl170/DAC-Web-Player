package com.dacweb.player.audio

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * 直接读文件头，取"这个文件里样本到底占几个字节"。
 *
 * 为什么不能只信 MediaExtractor：真机实测 24bit/96kHz 的 WAV，`pcm-encoding` 报的是
 * ENCODING_PCM_16BIT(2)，而 `bits-per-sample` 报 24 —— 两个字段互相矛盾。若按 2 字节读
 * 一个 3 字节的样本，整条流就错位，听感就是"丝丝声/声音诡异"。WAV 的 fmt 块里写的
 * 才是文件本身的布局，这里按块解析，不再猜。
 *
 * 只处理无损/PCM 容器：WAV(RIFF)、AIFF(AIFC)、FLAC。其它格式返回 null。
 */
object PcmHeader {

    /**
     * 解析结果：采样率(Hz)、声道数、位深，以及**每个样本实际占几个字节**。
     *
     * [bytes] 优先取 `nBlockAlign / 声道数`（WAV）——这是文件自身的步长，最可靠；
     * 有些 24bit WAV 是"24bit 有效位装在 32bit 容器里"（blockAlign=8 / 2ch ⇒ 每样本 4 字节），
     * 按 bits 推出 3 字节就会读错位、听到丝丝声。
     */
    data class Info(val rate: Int, val channels: Int, val bits: Int, val bytes: Int)

    /** 每个样本的字节数（1..4），失败返回 0。 */
    fun bytesPerSample(ctx: Context, url: String): Int = info(ctx, url)?.bytes ?: 0


    /** 文件头前 n 字节的十六进制，只用于诊断日志。 */
    fun headHex(ctx: Context, url: String, n: Int = 64): String = try {
        open(ctx, url)?.use { ins ->
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val k = ins.read(buf, off, n - off)
                if (k <= 0) break
                off += k
            }
            if (off <= 0) "(空)" else {
                val sb = StringBuilder()
                for (i in 0 until off) sb.append(String.format("%02x", buf[i]))
                sb.toString()
            }
        } ?: "(打不开)"
    } catch (e: Exception) {
        "(失败 " + e.javaClass.simpleName + ")"
    }

    private fun bytesOf(bits: Int): Int = when {
        bits <= 0 -> 0
        bits <= 8 -> 1
        bits <= 16 -> 2
        bits <= 24 -> 3
        else -> 4
    }

    fun info(ctx: Context, url: String): Info? = try {
        open(ctx, url)?.use { parse(it) }
    } catch (e: Exception) {
        null
    }

    /**
     * 网络文件：只拉前 64 KB（Range 请求）解析文件头。
     * 在线 24bit WAV 同样会报 pcmEnc=16bit，若不读头就会按 2 字节读 3 字节样本。
     */
    private fun remote(url: String): InputStream? {
        return try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 8000
            conn.setRequestProperty("Range", "bytes=0-65535")
            conn.setRequestProperty("User-Agent", "DACWebPlayer/0.1")
            conn.connect()
            val buf = ByteArray(64 * 1024)
            var off = 0
            val ins = conn.inputStream
            while (off < buf.size) {
                val n = ins.read(buf, off, buf.size - off)
                if (n <= 0) break
                off += n
            }
            runCatching { ins.close() }
            runCatching { conn.disconnect() }
            java.io.ByteArrayInputStream(buf, 0, off)
        } catch (e: Exception) {
            null
        }
    }

    private fun open(ctx: Context, url: String): InputStream? {
        val u = Uri.parse(url)
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> remote(url)
            u.scheme == "content" || u.scheme == "file" || u.scheme == null ->
                if (u.scheme == null) {
                    val f = File(url)
                    if (f.isFile) FileInputStream(f) else null
                } else {
                    ctx.contentResolver.openInputStream(u)
                }
            else -> {
                val f = File(url)
                if (f.isFile) FileInputStream(f) else null
            }
        }?.let { BufferedInputStream(it, 64 * 1024) }
    }

    private fun parse(input: InputStream): Info? {
        val head = ByteArray(12)
        if (!readFully(input, head, 12)) return null
        val tag = String(head, 0, 4, Charsets.US_ASCII)
        return when (tag) {
            "RIFF" -> parseWav(input)
            "FORM" -> parseAiff(input)
            "fLaC" -> parseFlac(input)
            else -> null
        }
    }

    // ---------------------------------------------------------------- WAV

    private fun parseWav(input: InputStream): Info? {
        val b = ByteArray(8)
        while (readFully(input, b, 8)) {
            val id = String(b, 0, 4, Charsets.US_ASCII)
            val size = le32(b, 4)
            if (id == "fmt ") {
                val f = ByteArray(16)
                if (!readFully(input, f, 16)) return null
                val format = le16(f, 0)
                val ch = le16(f, 2)
                val rate = le32(f, 4)
                // f[8..11] = byteRate, f[12..13] = blockAlign（一帧字节数）, f[14..15] = bits
                val blockAlign = le16(f, 12)
                val bits = le16(f, 14)
                if (format == 1 || format == 0xFFFE) {
                    val per = if (ch > 0 && blockAlign > 0) blockAlign / ch else bytesOf(bits)
                    val b = per.coerceIn(1, 4)
                    return Info(rate, ch, if (bits > 0) bits else b * 8, b)
                }
                return null
            }
            if (size < 0 || !skipFully(input, size + (size and 1))) return null
        }
        return null
    }

    // ---------------------------------------------------------------- AIFF

    private fun parseAiff(input: InputStream): Info? {
        val b = ByteArray(8)
        while (readFully(input, b, 8)) {
            val id = String(b, 0, 4, Charsets.US_ASCII)
            val size = be32(b, 4)
            if (id == "COMM") {
                val c = ByteArray(18)
                if (!readFully(input, c, 18)) return null
                val ch = be16(c, 0)
                val bits = be16(c, 6)
                val rate = extended80(c, 8)
                return Info(rate.toInt(), ch, bits, bytesOf(bits))
            }
            if (size < 0 || !skipFully(input, size + (size and 1))) return null
        }
        return null
    }

    // ---------------------------------------------------------------- FLAC

    private fun parseFlac(input: InputStream): Info? {
        val h = ByteArray(4)
        if (!readFully(input, h, 4)) return null
        val type = h[0].toInt() and 0x7F
        val len = ((h[1].toInt() and 0xFF) shl 16) or
            ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
        if (type != 0) return null   // 第一个块必须是 STREAMINFO
        if (len < 18) return null
        val s = ByteArray(len)
        if (!readFully(input, s, len)) return null
        // STREAMINFO: min/max blocksize(2+2) min/max framesize(3+3) 之后 8 字节：
        // 20bit 采样率 / 3bit 声道数-1 / 5bit 位深-1 / 36bit 总样本数
        val rate = ((s[10].toInt() and 0xFF) shl 12) or
            ((s[11].toInt() and 0xFF) shl 4) or
            ((s[12].toInt() and 0xF0) ushr 4)
        val ch = ((s[12].toInt() and 0x0E) ushr 1) + 1
        val bits = (((s[12].toInt() and 0x01) shl 4) or ((s[13].toInt() and 0xF0) ushr 4)) + 1
        return Info(rate, ch, bits, bytesOf(bits))
    }

    // ---------------------------------------------------------------- 小工具

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Boolean {
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n <= 0) return false
            off += n
        }
        return true
    }

    private fun skipFully(input: InputStream, n: Int): Boolean {
        var left = n.toLong()
        while (left > 0) {
            val s = input.skip(left)
            if (s <= 0) {
                if (input.read() < 0) return false
                left -= 1
            } else {
                left -= s
            }
        }
        return true
    }

    private fun le16(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun be16(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun be32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    /** IEEE 754 80-bit extended（AIFF 采样率）。 */
    private fun extended80(b: ByteArray, o: Int): Double {
        val exp = ((b[o].toInt() and 0x7F) shl 8) or (b[o + 1].toInt() and 0xFF)
        var mant = 0L
        for (i in 0 until 8) {
            mant = (mant shl 8) or (b[o + 2 + i].toLong() and 0xFF)
        }
        if (exp == 0 && mant == 0L) return 0.0
        return mant.toDouble() * Math.pow(2.0, (exp - 16383 - 63).toDouble())
    }
}
