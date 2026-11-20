package com.dacweb.player.lyrics

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 读取本地文件里内嵌的歌词。
 *
 * 下载时 MetaEnricher 会把歌词写进 ID3 的 USLT / TXXX:LYRICS（mp3）或
 * VORBIS_COMMENT 的 LYRICS / UNSYNCEDLYRICS（flac），但之前从来没有代码把它们读回来，
 * 于是"文件里明明有歌词，播放页却显示暂无歌词"。这里补上读的一侧。
 */
object LocalLyrics {

    /** 返回文件里的歌词原文；没有则 null。 */
    fun read(ctx: Context, uri: Uri): String? {
        val head = runCatching { openHead(ctx, uri, 4) }.getOrNull() ?: return null
        val text = when {
            head.size >= 3 && String(head, 0, 3, Charsets.US_ASCII) == "ID3" ->
                runCatching { readId3(ctx, uri) }.getOrNull()
            head.size >= 4 && String(head, 0, 4, Charsets.US_ASCII) == "fLaC" ->
                runCatching { readFlac(ctx, uri) }.getOrNull()
            else -> null
        }
        return text?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun openStream(ctx: Context, uri: Uri): InputStream? = when (uri.scheme) {
        "file" -> uri.path?.let { File(it).takeIf(File::exists)?.inputStream() }
        else -> ctx.contentResolver.openInputStream(uri)
    }

    private fun openHead(ctx: Context, uri: Uri, n: Int): ByteArray? =
        openStream(ctx, uri)?.use { it.readNBytes(n) }

    private fun readAll(ctx: Context, uri: Uri): ByteArray? =
        openStream(ctx, uri)?.use { input ->
            val out = ByteArrayOutputStream(64 * 1024)
            input.copyTo(out)
            out.toByteArray()
        }

    /* ---------------- ID3v2 ---------------- */

    private fun readId3(ctx: Context, uri: Uri): String? {
        val data = readAll(ctx, uri) ?: return null
        if (data.size < 10) return null
        val major = data[3].toInt() and 0xFF
        val flags = data[5].toInt() and 0xFF
        val size = syncSafe(data, 6)
        var pos = 10
        if (flags and 0x40 != 0) {
            // 扩展头：v2.3 是 4 字节长度（不含自身），v2.4 是同步安全整数（含自身）
            if (pos + 4 > data.size) return null
            pos += if (major >= 4) syncSafe(data, pos) else 4 + beInt(data, pos)
        }
        val end = minOf(size + 10, data.size)
        while (pos + 10 <= end) {
            val id = String(data, pos, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val frameSize = if (major >= 4) syncSafe(data, pos + 4) else beInt(data, pos + 4)
            if (frameSize <= 0 || pos + 10 + frameSize > end) break
            val body = data.copyOfRange(pos + 10, pos + 10 + frameSize)
            when (id) {
                "USLT" -> if (body.size > 4) {
                    val enc = body[0].toInt()
                    // 3 字节语言码之后是编码相关的描述串，跳过
                    var p = 4
                    p = skipString(body, p, enc)
                    decode(body, p, body.size, enc)?.let { if (it.isNotBlank()) return it }
                }
                "TXXX" -> if (body.size > 1) {
                    val enc = body[0].toInt()
                    val descEnd = skipString(body, 1, enc)
                    val desc = decode(body, 1, descEnd, enc)?.trim()?.uppercase().orEmpty()
                    if (desc == "LYRICS" || desc == "UNSYNCEDLYRICS" || desc == "UNSYNCHRONISEDLYRICS") {
                        decode(body, descEnd, body.size, enc)?.let { if (it.isNotBlank()) return it }
                    }
                }
            }
            pos += 10 + frameSize
        }
        return null
    }

    private fun beInt(data: ByteArray, at: Int): Int {
        if (at + 4 > data.size) return 0
        return ((data[at].toInt() and 0xFF) shl 24) or ((data[at + 1].toInt() and 0xFF) shl 16) or
            ((data[at + 2].toInt() and 0xFF) shl 8) or (data[at + 3].toInt() and 0xFF)
    }

    private fun syncSafe(data: ByteArray, at: Int): Int {
        if (at + 4 > data.size) return 0
        return ((data[at].toInt() and 0x7F) shl 21) or ((data[at + 1].toInt() and 0x7F) shl 14) or
            ((data[at + 2].toInt() and 0x7F) shl 7) or (data[at + 3].toInt() and 0x7F)
    }

    /** 跳过以 0x00（或双 0x00）结尾的编码字符串，返回其后的偏移。 */
    private fun skipString(body: ByteArray, from: Int, enc: Int): Int {
        var p = from
        while (p < body.size) {
            if (body[p].toInt() == 0) {
                return if (enc == 1 || enc == 2) {
                    // UTF-16：至少吃掉两个 0 字节
                    minOf(p + 2, body.size)
                } else p + 1
            }
            p += if (enc == 1 || enc == 2) 2 else 1
        }
        return body.size
    }

    private fun decode(body: ByteArray, from: Int, to: Int, enc: Int): String? {
        if (to <= from) return null
        val bytes = body.copyOfRange(from, to)
        return runCatching {
            when (enc) {
                0 -> String(bytes, Charsets.ISO_8859_1)
                1 -> String(bytes, Charsets.UTF_16)
                2 -> String(bytes, Charsets.UTF_16BE)
                else -> String(bytes, Charsets.UTF_8)
            }
        }.getOrNull()
    }

    /* ---------------- FLAC ---------------- */

    private fun readFlac(ctx: Context, uri: Uri): String? {
        val data = readAll(ctx, uri) ?: return null
        if (data.size < 8) return null
        var pos = 4
        var last = false
        while (!last && pos + 4 <= data.size) {
            val header = data[pos].toInt() and 0xFF
            last = header and 0x80 != 0
            val type = header and 0x7F
            val len = ((data[pos + 1].toInt() and 0xFF) shl 16) or
                ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
            pos += 4
            if (pos + len > data.size) return null
            if (type == 4) return readVorbis(data, pos, len)
            pos += len
        }
        return null
    }

    private fun readVorbis(data: ByteArray, at: Int, len: Int): String? {
        val bb = ByteBuffer.wrap(data, at, len).order(ByteOrder.LITTLE_ENDIAN)
        val vendorLen = bb.int
        if (vendorLen < 0 || vendorLen > bb.remaining()) return null
        bb.position(bb.position() + vendorLen)
        if (bb.remaining() < 4) return null
        val count = bb.int
        for (i in 0 until count) {
            if (bb.remaining() < 4) return null
            val l = bb.int
            if (l <= 0 || l > bb.remaining()) return null
            val raw = ByteArray(l)
            bb.get(raw)
            val kv = String(raw, Charsets.UTF_8)
            val eq = kv.indexOf('=')
            if (eq <= 0) continue
            val key = kv.substring(0, eq).uppercase()
            if (key == "LYRICS" || key == "UNSYNCEDLYRICS" || key == "UNSYNCHRONISEDLYRICS") {
                kv.substring(eq + 1).takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return null
    }
}
