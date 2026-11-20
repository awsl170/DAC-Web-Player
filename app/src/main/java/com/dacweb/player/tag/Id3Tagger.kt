package com.dacweb.player.tag

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MP3 标签写入：写 ID3v2.3 头 + 文本帧 + APIC 封面。
 * 写入前先跳过文件里已有的 ID3v2 标签，避免叠加。
 */
object Id3Tagger {

    fun write(file: File, tags: Map<String, String>, cover: ByteArray?, coverMime: String) {
        val frames = ByteArrayOutputStream()

        fun textFrame(id: String, value: String) {
            if (value.isBlank()) return
            val payload = ByteArrayOutputStream()
            payload.write(0x01)                                  // UTF-16 with BOM
            payload.write(0xFF)
            payload.write(0xFE)
            payload.write(value.toByteArray(Charsets.UTF_16LE))
            payload.write(0x00)
            payload.write(0x00)
            writeFrame(frames, id, payload.toByteArray())
        }

        textFrame("TIT2", tags["TITLE"].orEmpty())
        textFrame("TPE1", tags["ARTIST"].orEmpty())
        textFrame("TALB", tags["ALBUM"].orEmpty())
        textFrame("TPE2", tags["ALBUMARTIST"].orEmpty())
        textFrame("TIT3", tags["SUBTITLE"].orEmpty())
        textFrame("TYER", tags["DATE"].orEmpty())
        textFrame("TRCK", tags["TRACKNUMBER"].orEmpty())
        textFrame("TPOS", tags["DISCNUMBER"].orEmpty())

        // USLT：非同步歌词（ID3v2.3 的歌词帧）
        val lyrics = tags["LYRICS"].orEmpty()
        if (lyrics.isNotBlank()) {
            val payload = ByteArrayOutputStream()
            payload.write(0x01)
            payload.write("eng".toByteArray(Charsets.US_ASCII))
            payload.write(0xFF)
            payload.write(0xFE)
            payload.write(0x00)
            payload.write(0x00)
            payload.write(lyrics.toByteArray(Charsets.UTF_16LE))
            writeFrame(frames, "USLT", payload.toByteArray())
            // 同时写 TXXX:LYRICS / TXXX:UNSYNCEDLYRICS，覆盖按自定义字段读歌词的工具
            userTextFrame(frames, "LYRICS", lyrics)
            userTextFrame(frames, "UNSYNCEDLYRICS", lyrics)
        }

        val comment = tags["COMMENT"].orEmpty()
        if (comment.isNotBlank()) {
            val payload = ByteArrayOutputStream()
            payload.write(0x01)
            payload.write("eng".toByteArray(Charsets.US_ASCII))
            payload.write(0xFF)
            payload.write(0xFE)
            payload.write(0x00)   // 空描述结束符（UTF-16 的 0x0000）
            payload.write(0x00)
            payload.write(comment.toByteArray(Charsets.UTF_16LE))
            writeFrame(frames, "COMM", payload.toByteArray())
        }

        if (cover != null && cover.isNotEmpty()) {
            val payload = ByteArrayOutputStream()
            payload.write(0x00)                                  // ISO-8859-1
            payload.write(coverMime.toByteArray(Charsets.US_ASCII))
            payload.write(0x00)
            payload.write(0x03)                                  // 封面(正面)
            payload.write(0x00)                                  // 空描述
            payload.write(cover)
            writeFrame(frames, "APIC", payload.toByteArray())
        }

        val body = frames.toByteArray()
        val src = RandomAccessFile(file, "r")
        val tmp = File(file.parentFile, file.name + ".tagtmp")
        try {
            val start = existingTagSize(src)
            tmp.outputStream().use { os ->
                os.write("ID3".toByteArray(Charsets.US_ASCII))
                os.write(3)
                os.write(0)
                os.write(0)                                      // flags
                os.write(syncSafe(body.size))
                os.write(body)
                src.seek(start)
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = src.read(buf)
                    if (n <= 0) break
                    os.write(buf, 0, n)
                }
            }
        } finally {
            src.close()
        }

        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "写入标签失败" }
        }
    }

    /** TXXX 自定义文本帧：encoding(1) + 描述 + 0x00 0x00 + 值。 */
    private fun userTextFrame(out: ByteArrayOutputStream, desc: String, value: String) {
        if (value.isBlank()) return
        val payload = ByteArrayOutputStream()
        payload.write(0x01)
        payload.write(0xFF)
        payload.write(0xFE)
        payload.write(desc.toByteArray(Charsets.UTF_16LE))
        payload.write(0x00)
        payload.write(0x00)
        payload.write(0xFF)
        payload.write(0xFE)
        payload.write(value.toByteArray(Charsets.UTF_16LE))
        writeFrame(out, "TXXX", payload.toByteArray())
    }

    private fun writeFrame(out: ByteArrayOutputStream, id: String, data: ByteArray) {
        out.write(id.toByteArray(Charsets.US_ASCII))
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(data.size).array())
        out.write(0)
        out.write(0)
        out.write(data)
    }

    private fun syncSafe(n: Int): ByteArray = byteArrayOf(
        ((n ushr 21) and 0x7F).toByte(),
        ((n ushr 14) and 0x7F).toByte(),
        ((n ushr 7) and 0x7F).toByte(),
        (n and 0x7F).toByte()
    )

    /** 已有 ID3v2 标签的总长度（含 10 字节头），没有则返回 0。 */
    private fun existingTagSize(src: RandomAccessFile): Long {
        if (src.length() < 10) return 0
        src.seek(0)
        val head = ByteArray(10)
        src.readFully(head)
        if (head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) return 0
        val size = ((head[6].toInt() and 0x7F) shl 21) or
                ((head[7].toInt() and 0x7F) shl 14) or
                ((head[8].toInt() and 0x7F) shl 7) or
                (head[9].toInt() and 0x7F)
        val footer = if ((head[5].toInt() and 0x10) != 0) 10 else 0
        return 10L + size + footer
    }
}
