package com.dacweb.player.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.dacweb.player.prefs.AppPrefs
import java.io.File
import java.io.IOException

/**
 * 把打完标签的临时文件保存到用户选择的位置：
 *  - 选了目录（SAF tree URI）→ 直接写进该目录，文件名「歌名-作曲家.ext」
 *  - 没选 → MediaStore 的 Music/24bit（系统的音乐库，MediaScanner 可见）
 */
object DownloadStore {

    fun safeName(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").trim().ifBlank { "未知" }

    fun mimeOf(ext: String): String = when (ext.lowercase()) {
        "flac" -> "audio/flac"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "ape" -> "audio/ape"
        else -> "audio/*"
    }

    suspend fun save(ctx: Context, tmp: File, ext: String, meta: Downloader.Meta): Uri {
        val fileName = "${safeName(meta.title)}-${safeName(meta.artist)}.$ext"
        // Android 9 及以下没有 RELATIVE_PATH / IS_PENDING，只能写公共目录再交给扫描器入库
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return saveLegacy(ctx, tmp, ext, fileName)
        }
        val tree = AppPrefs.downloadTree(ctx)
        return if (tree != null) saveToTree(ctx, tree, tmp, ext, fileName, meta) else saveToMediaStore(ctx, tmp, ext, fileName, meta)
    }

    /** 老系统（API 22~28）的落盘：Music/DACWebPlayer 直接建文件，再用 MediaScanner 通知媒体库。 */
    private fun saveLegacy(ctx: Context, tmp: File, ext: String, fileName: String): Uri {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "DACWebPlayer")
        if (!dir.exists() && !dir.mkdirs()) throw IOException("无法创建目录 ${dir.absolutePath}")
        val out = File(dir, fileName)
        tmp.inputStream().use { ins ->
            out.outputStream().use { o -> ins.copyTo(o, 128 * 1024) }
        }
        if (out.length() < 4096) throw IOException("写入失败（${out.length()} 字节）")
        MediaScannerConnection.scanFile(ctx, arrayOf(out.absolutePath), arrayOf(mimeOf(ext)), null)
        return Uri.fromFile(out)
    }

    private fun saveToMediaStore(ctx: Context, tmp: File, ext: String, fileName: String, meta: Downloader.Meta): Uri {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeOf(ext))
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/DACWebPlayer")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
            put(MediaStore.Audio.Media.TITLE, meta.title)
            put(MediaStore.Audio.Media.ARTIST, meta.artist)
            put(MediaStore.Audio.Media.ALBUM, meta.album)
            if (meta.trackNo > 0) put(MediaStore.Audio.Media.TRACK, meta.discNo * 1000 + meta.trackNo)
            if (meta.year > 0) put(MediaStore.Audio.Media.YEAR, meta.year)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法在媒体库创建文件")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                tmp.inputStream().use { it.copyTo(out, 128 * 1024) }
            } ?: throw IOException("无法写入媒体库")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    private fun saveToTree(ctx: Context, tree: Uri, tmp: File, ext: String, fileName: String, meta: Downloader.Meta): Uri {
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: throw IOException("下载目录已失效，请重新选择")
        if (!root.canWrite()) throw IOException("没有写入该目录的权限，请重新选择")
        val target = uniqueChild(root, fileName)
            ?: root.createFile(mimeOf(ext), fileName)
            ?: throw IOException("无法在下载目录创建文件")
        ctx.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
            tmp.inputStream().use { it.copyTo(out, 128 * 1024) }
        } ?: throw IOException("无法写入下载目录")
        return target.uri
    }

    private fun uniqueChild(root: DocumentFile, name: String): DocumentFile? {
        val existing = root.findFile(name) ?: return null
        // 同名文件：覆盖（更符合直觉，避免目录里堆积 xx(1).flac）
        if (existing.isFile && existing.canWrite()) return existing
        return null
    }
}
