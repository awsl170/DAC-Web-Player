package com.dacweb.player.lyrics

import android.content.Context
import android.net.Uri
import com.dacweb.player.net.NetStore
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

    private const val HEAD_CAP = 2 * 1024 * 1024

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

    private fun openStream(ctx: Context, uri: Uri): InputStream? = when {
        uri.scheme == "file" -> uri.path?.let { File(it).takeIf(File::exists)?.inputStream() }
        uri.scheme?.startsWith("dacnet-") == true -> {
            // SMB/FTP/WebDAV：借 NetReader 读（只读头部，不整首下载）
            val client = NetStore.client()
            val reader = client?.open(uri.path.orEmpty().trim('/'))
            if (reader == null) null else NetReaderStream(reader)
        }
        else -> ctx.contentResolver.openInputStream(uri)
    }

    /** 把 NetReader 包成 InputStream（顺序读，够解析容器头）。 */
    private class NetReaderStream(private val r: com.dacweb.player.net.NetReader) : InputStream() {
        private var pos = 0L
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val n = r.readAt(pos, b, off, len)
            if (n > 0) pos += n
            return n
        }
        override fun close() { runCatching { r.close() } }
    }

    private fun openHead(ctx: Context, uri: Uri, n: Int): ByteArray? =
        openStream(ctx, uri)?.use { it.readNBytes(n) }

    /** 标签区都在文件开头：只读前 4 MiB，别把几十 MB 的整首歌搬进内存。 */
    private fun readAll(ctx: Context, uri: Uri): ByteArray? =
        openStream(ctx, uri)?.use { input ->
            val out = ByteArrayOutputStream(64 * 1024)
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (total < HEAD_CAP) {
                val n = input.read(buf, 0, minOf(buf.size, HEAD_CAP - total))
                if (n <= 0) break
                out.write(buf, 0, n)
                total += n
            }
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

    /**
     * 标签里的中文常见两种存法：ID3v2.4/Vorbis 的 UTF-8，以及老工具的 GBK。
     * 先按 UTF-8 严格解码，失败再退 GB18030（兼容 GBK），最后才 ISO-8859-1 兜底。
     */
    private fun smartDecode(raw: ByteArray, from: Int, to: Int): String {
        if (to <= from) return ""
        val bytes = raw.copyOfRange(from, to).let {
            // 去掉结尾的 NUL
            var e = it.size
            while (e > 0 && it[e - 1].toInt() == 0) e--
            if (e == it.size) it else it.copyOf(e)
        }
        if (bytes.isEmpty()) return ""
        StrictUtf8.decode(bytes)?.let { return it }
        runCatching { return String(bytes, charset("GB18030")) }
        return String(bytes, Charsets.ISO_8859_1)
    }

    /** UTF-8 严格解码器：遇到非法序列返回 null。 */
    private object StrictUtf8 {
        fun decode(b: ByteArray): String? = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b))
                .toString()
        }.getOrNull()
    }

    private fun decode(body: ByteArray, from: Int, to: Int, enc: Int): String? {
        if (to <= from) return null
        val bytes = body.copyOfRange(from, to)
        return runCatching {
            when (enc) {
                0 -> smartDecode(bytes, 0, bytes.size)
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
            val eq = raw.indexOf('='.code.toByte())
            if (eq <= 0) continue
            val key = String(raw, 0, eq, Charsets.ISO_8859_1).trim().uppercase()
            if (key == "LYRICS" || key == "UNSYNCEDLYRICS" ||
                key == "UNSYNCHRONISEDLYRICS" || key == "USLT"
            ) {
                smartDecode(raw, eq + 1, raw.size).takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return null
    }
}
