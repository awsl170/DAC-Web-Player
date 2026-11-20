package com.dacweb.player.tag

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 只改歌词、其它标签与封面原样保留：
 * 读出已有的 VORBIS_COMMENT / PICTURE（或 ID3 文本帧 / APIC），替换歌词字段后整体重写。
 * 用于「用户手动切换歌词后把新歌词写回已下载文件」。
 */
object TagUpdater {

    fun updateFromUri(ctx: Context, uri: Uri, lyrics: String): Boolean = runCatching {
        val ext = extOf(ctx, uri)
        if (ext != "flac" && ext != "mp3") return@runCatching false
        val tmp = File(ctx.cacheDir, "tag_${System.currentTimeMillis()}.$ext")
        try {
            val input = ctx.contentResolver.openInputStream(uri) ?: return@runCatching false
            input.use { src -> tmp.outputStream().use { dst -> src.copyTo(dst) } }
            if (!updateFile(tmp, ext, lyrics)) return@runCatching false

            var written = false
            runCatching {
                ctx.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    tmp.inputStream().use { it.copyTo(out) }
                    written = true
                }
            }
            if (!written) {
                runCatching {
                    ctx.contentResolver.openOutputStream(uri, "w")?.use { out ->
                        tmp.inputStream().use { it.copyTo(out) }
                        written = true
                    }
                }
            }
            written
        } finally {
            tmp.delete()
        }
    }.getOrDefault(false)

    fun updateFile(file: File, ext: String, lyrics: String): Boolean = runCatching {
        if (ext == "flac") updateFlac(file, lyrics) else updateMp3(file, lyrics)
        true
    }.getOrDefault(false)

    // ---------------------------------------------------------------- FLAC

    private fun updateFlac(file: File, lyrics: String) {
        val src = RandomAccessFile(file, "r")
        val tags = LinkedHashMap<String, String>()
        var cover: ByteArray? = null
        try {
            val magic = ByteArray(4)
            src.readFully(magic)
            require(String(magic, Charsets.US_ASCII) == "fLaC") { "不是 FLAC 文件" }
            var last = false
            while (!last) {
                val h = src.read()
                require(h >= 0) { "FLAC 元数据损坏" }
                last = (h and 0x80) != 0
                val type = h and 0x7F
                val len = (src.read() shl 16) or (src.read() shl 8) or src.read()
                val data = ByteArray(len)
                src.readFully(data)
                when (type) {
                    4 -> parseVorbis(data, tags)
                    6 -> cover = parsePicture(data)
                }
            }
        } finally {
            src.close()
        }
        if (lyrics.isNotBlank()) {
            tags["LYRICS"] = lyrics
            tags["UNSYNCEDLYRICS"] = lyrics
            tags["USLT"] = lyrics
        }
        FlacTagger.write(file, tags, cover, "image/jpeg")
    }

    private fun parseVorbis(data: ByteArray, out: MutableMap<String, String>) {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        runCatching {
            val vendorLen = buf.int
            buf.position(buf.position() + vendorLen)
            val count = buf.int
            repeat(count) {
                val len = buf.int
                val bytes = ByteArray(len)
                buf.get(bytes)
                val s = String(bytes, Charsets.UTF_8)
                val i = s.indexOf('=')
                if (i > 0) out[s.substring(0, i).uppercase()] = s.substring(i + 1)
            }
        }
    }

    private fun parsePicture(data: ByteArray): ByteArray? = runCatching {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        buf.int // type
        val mimeLen = buf.int
        buf.position(buf.position() + mimeLen)
        val descLen = buf.int
        buf.position(buf.position() + descLen)
        buf.int; buf.int; buf.int; buf.int // width height depth colors
        val size = buf.int
        val img = ByteArray(size)
        buf.get(img)
        img
    }.getOrNull()

    // ---------------------------------------------------------------- MP3

    private fun updateMp3(file: File, lyrics: String) {
        val bytes = file.readBytes()
        val tags = LinkedHashMap<String, String>()
        var cover: ByteArray? = null
        if (bytes.size > 10 && String(bytes, 0, 3, Charsets.US_ASCII) == "ID3") {
            val size = ((bytes[6].toInt() and 0x7F) shl 21) or
                ((bytes[7].toInt() and 0x7F) shl 14) or
                ((bytes[8].toInt() and 0x7F) shl 7) or
                (bytes[9].toInt() and 0x7F)
            var pos = 10
            val end = minOf(10 + size, bytes.size)
            while (pos + 10 <= end) {
                val id = String(bytes, pos, 4, Charsets.US_ASCII)
                if (id[0] == '\u0000') break
                val len = ((bytes[pos + 4].toInt() and 0xFF) shl 24) or
                    ((bytes[pos + 5].toInt() and 0xFF) shl 16) or
                    ((bytes[pos + 6].toInt() and 0xFF) shl 8) or
                    (bytes[pos + 7].toInt() and 0xFF)
                if (len <= 0 || pos + 10 + len > end) break
                val frame = bytes.copyOfRange(pos + 10, pos + 10 + len)
                when (id) {
                    "TIT2" -> tags["TITLE"] = decodeText(frame)
                    "TPE1" -> tags["ARTIST"] = decodeText(frame)
                    "TALB" -> tags["ALBUM"] = decodeText(frame)
                    "TPE2" -> tags["ALBUMARTIST"] = decodeText(frame)
                    "TIT3" -> tags["SUBTITLE"] = decodeText(frame)
                    "TYER" -> tags["DATE"] = decodeText(frame)
                    "TRCK" -> tags["TRACKNUMBER"] = decodeText(frame)
                    "TPOS" -> tags["DISCNUMBER"] = decodeText(frame)
                    "APIC" -> cover = parseApic(frame)
                }
                pos += 10 + len
            }
        }
        if (tags["TITLE"].isNullOrBlank()) return
        if (lyrics.isNotBlank()) tags["LYRICS"] = lyrics
        Id3Tagger.write(file, tags, cover, "image/jpeg")
    }

    private fun decodeText(frame: ByteArray): String = runCatching {
        if (frame.isEmpty()) return@runCatching ""
        val enc = frame[0].toInt()
        val body = frame.copyOfRange(1, frame.size)
        when (enc) {
            0 -> String(body, Charsets.ISO_8859_1)
            3 -> String(body, Charsets.UTF_8)
            else -> {
                var start = 0
                if (body.size >= 2 && body[0] == 0xFF.toByte() && body[1] == 0xFE.toByte()) start = 2
                var end = body.size
                while (end - 2 >= start && body[end - 1] == 0.toByte() && body[end - 2] == 0.toByte()) end -= 2
                String(body, start, end - start, Charsets.UTF_16LE)
            }
        }.trimEnd('\u0000')
    }.getOrDefault("")

    private fun parseApic(frame: ByteArray): ByteArray? = runCatching {
        var p = 1
        while (p < frame.size && frame[p] != 0.toByte()) p++   // mime
        p++                                                     // null
        p++                                                     // picture type
        while (p < frame.size && frame[p] != 0.toByte()) p++    // description
        p++
        if (p >= frame.size) null else frame.copyOfRange(p, frame.size)
    }.getOrNull()

    private fun extOf(ctx: Context, uri: Uri): String {
        val mime = runCatching { ctx.contentResolver.getType(uri) }.getOrNull().orEmpty()
        return when {
            mime.contains("flac") -> "flac"
            mime.contains("mpeg") || mime.contains("mp3") -> "mp3"
            else -> {
                val name = runCatching { uri.lastPathSegment.orEmpty() }.getOrDefault("")
                when {
                    name.endsWith(".flac", true) -> "flac"
                    name.endsWith(".mp3", true) -> "mp3"
                    else -> ""
                }
            }
        }
    }
}
