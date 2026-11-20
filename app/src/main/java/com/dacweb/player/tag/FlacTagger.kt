package com.dacweb.player.tag

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FLAC 标签写入：重写 VORBIS_COMMENT(type 4) 与 PICTURE(type 6) 块，其余块原样保留。
 *
 * 文件结构： "fLaC" + 若干元数据块 + 音频帧。
 * 块头 4 字节：1 字节(最高位 = 最后一块标志，低 7 位 = 类型) + 3 字节大端长度。
 */
object FlacTagger {

    private const val TYPE_STREAMINFO = 0
    private const val TYPE_PADDING = 1
    private const val TYPE_VORBIS = 4
    private const val TYPE_PICTURE = 6

    fun write(file: File, tags: Map<String, String>, cover: ByteArray?, coverMime: String) {
        val src = RandomAccessFile(file, "r")
        val tmp = File(file.parentFile, file.name + ".tagtmp")
        try {
            val magic = ByteArray(4)
            src.readFully(magic)
            require(String(magic, Charsets.US_ASCII) == "fLaC") { "不是 FLAC 文件" }

            val blocks = ArrayList<Pair<Int, ByteArray>>()
            var last = false
            while (!last) {
                val h = src.read()
                require(h >= 0) { "FLAC 元数据损坏" }
                last = (h and 0x80) != 0
                val type = h and 0x7F
                val len = (src.read() shl 16) or (src.read() shl 8) or src.read()
                val data = ByteArray(len)
                src.readFully(data)
                blocks.add(type to data)
            }
            val audioStart = src.filePointer

            val out = ArrayList<Pair<Int, ByteArray>>()
            blocks.firstOrNull { it.first == TYPE_STREAMINFO }?.let { out.add(it) }
            for (b in blocks) {
                if (b.first == TYPE_STREAMINFO || b.first == TYPE_VORBIS ||
                    b.first == TYPE_PICTURE || b.first == TYPE_PADDING
                ) continue
                out.add(b)
            }
            out.add(TYPE_VORBIS to vorbisComment(tags))
            if (cover != null && cover.isNotEmpty()) {
                out.add(TYPE_PICTURE to picture(cover, coverMime))
            }

            tmp.outputStream().use { os ->
                os.write("fLaC".toByteArray(Charsets.US_ASCII))
                out.forEachIndexed { i, block ->
                    val type = if (i == out.lastIndex) 0x80 or block.first else block.first
                    os.write(type)
                    os.write((block.second.size ushr 16) and 0xFF)
                    os.write((block.second.size ushr 8) and 0xFF)
                    os.write(block.second.size and 0xFF)
                    os.write(block.second)
                }
                src.seek(audioStart)
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

    /** Vorbis comment：长度均为小端。 */
    private fun vorbisComment(tags: Map<String, String>): ByteArray {
        val vendor = "MusicPlayer".toByteArray(Charsets.UTF_8)
        val entries = tags.entries
            .filter { it.value.isNotBlank() }
            .map { "${it.key}=${it.value}".toByteArray(Charsets.UTF_8) }
        var size = 4 + vendor.size + 4
        entries.forEach { size += 4 + it.size }
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(vendor.size)
        buf.put(vendor)
        buf.putInt(entries.size)
        entries.forEach {
            buf.putInt(it.size)
            buf.put(it)
        }
        return buf.array()
    }

    /** PICTURE 块：长度均为大端；type 3 = 封面(正面)。 */
    private fun picture(data: ByteArray, mime: String): ByteArray {
        val m = mime.toByteArray(Charsets.US_ASCII)
        val buf = ByteBuffer.allocate(4 + 4 + m.size + 4 + 16 + 4 + data.size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(3)
        buf.putInt(m.size)
        buf.put(m)
        buf.putInt(0)   // description 长度
        buf.putInt(0)   // width
        buf.putInt(0)   // height
        buf.putInt(0)   // depth
        buf.putInt(0)   // colors
        buf.putInt(data.size)
        buf.put(data)
        return buf.array()
    }
}
