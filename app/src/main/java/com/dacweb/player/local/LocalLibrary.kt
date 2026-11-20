package com.dacweb.player.local

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import com.dacweb.player.audio.FfAudio
import com.dacweb.player.model.Track
import com.dacweb.player.prefs.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 本地（已下载）曲目。 */
data class LocalEntry(
    val track: Track,
    val sizeBytes: Long,
    val durationMs: Long,
    val mime: String,
    val displayName: String
)

/**
 * 本地下载库：扫描默认目录 Music/DACWebPlayer（兼容历史 Music/24bit）或用户选的 SAF 目录。
 * 只用于浏览/本地播放，不消耗在线额度。
 */
object LocalLibrary {

    /** 支持的扩展名（与打包进 APK 的 FFmpeg 解复用器/解码器对齐）。 */
    private val AUDIO_EXT = setOf(
        "flac", "mp3", "m4a", "wav", "ogg", "aac", "opus", "ape",
        "dsf", "dff",              // DSD（DSF / DFF）
        "aif", "aiff",             // AIFF
        "wma", "asf",              // Windows Media
        "dts", "mka"               // DTS / Matroska 音频
    )

    /** 这些扩展名通常不会被 Android 媒体库当成 audio 收录，需要再用 Files 表补扫一次。 */
    private val EXOTIC_EXT = setOf("dsf", "dff", "aif", "aiff", "wma", "asf", "dts", "mka", "ape")

    @Volatile
    private var entries: List<LocalEntry> = emptyList()

    @Volatile
    private var byKey: Map<String, LocalEntry> = emptyMap()

    fun cached(): List<LocalEntry> = entries

    fun cachedTracks(): List<Track> = entries.map { it.track }

    fun find(matchKey: String): LocalEntry? = byKey[matchKey]

    /** 用一个“已下载”版本替换在线曲目（保留原有在线信息，只补 localUri/封面）。 */
    fun withLocal(list: List<Track>): List<Track> = list.map { t ->
        val e = byKey[t.matchKey] ?: return@map t
        t.copy(
            localUri = e.track.localUri,
            cover = t.cover.ifBlank { e.track.cover }
        )
    }

    /** 删除一个已下载文件（MediaStore 与 SAF 目录都支持），成功后就地刷新缓存。 */
    suspend fun delete(ctx: Context, track: Track): Boolean = withContext(Dispatchers.IO) {
        if (track.localUri.isBlank()) return@withContext false
        val uri = Uri.parse(track.localUri)
        val ok = runCatching {
            ctx.contentResolver.delete(uri, null, null) > 0
        }.getOrDefault(false) || runCatching {
            androidx.documentfile.provider.DocumentFile.fromSingleUri(ctx, uri)?.delete() == true
        }.getOrDefault(false)
        if (ok) {
            entries = entries.filterNot { it.track.localUri == track.localUri }
            byKey = entries.associateBy { it.track.matchKey }
        }
        ok
    }

    suspend fun scan(ctx: Context): List<LocalEntry> = withContext(Dispatchers.IO) {
        val result = runCatching {
            val tree = AppPrefs.downloadTree(ctx)
            if (tree != null) scanTree(ctx, tree) else scanMediaStore(ctx)
        }.getOrDefault(emptyList())
        val sorted = result.sortedWith(compareBy({ it.track.name.lowercase() }, { it.track.artist.lowercase() }))
        entries = sorted
        byKey = sorted.associateBy { it.track.matchKey }
        sorted
    }

    /* ---------------- MediaStore ---------------- */

    private fun scanMediaStore(ctx: Context): List<LocalEntry> {
        val out = mutableListOf<LocalEntry>()
        val seen = mutableSetOf<String>()
        // API 28 及以下没有 RELATIVE_PATH 列（29 才加），老系统按绝对路径 DATA 过滤
        val legacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        @Suppress("DEPRECATION")
        val dirCol = if (legacy) MediaStore.Audio.Media.DATA else MediaStore.Audio.Media.RELATIVE_PATH
        val cols = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            dirCol
        )
        // 新目录 + 历史目录（老版本存在 Music/24bit 里的歌不能丢）
        val selection = "($dirCol LIKE ? OR $dirCol LIKE ?)"
        val args = arrayOf(
            if (legacy) "%/Music/DACWebPlayer/%" else "Music/DACWebPlayer%",
            if (legacy) "%/Music/24bit/%" else "Music/24bit%"
        )
        ctx.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols, selection, args,
            "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: c.getString(7).orEmpty()
                val artist = c.getString(2)?.takeIf { it != MediaStore.UNKNOWN_STRING }.orEmpty()
                val album = c.getString(3)?.takeIf { it != MediaStore.UNKNOWN_STRING }.orEmpty()
                val duration = c.getLong(4)
                val size = c.getLong(5)
                val mime = c.getString(6).orEmpty()
                val display = c.getString(7).orEmpty()
                val cover = thumbnail(ctx, uri, "ms2_$id")
                seen += "${display}|${size}"
                out += LocalEntry(
                    track = Track(
                        id = "local:$uri",
                        name = name,
                        artist = artist,
                        album = album,
                        cover = cover,
                        source = SOURCE_LOCAL,
                        localUri = uri.toString()
                    ),
                    sizeBytes = size,
                    durationMs = duration,
                    mime = mime,
                    displayName = display
                )
            }
        }
        // 补扫：媒体库把 .dsf/.dts/.asf 之类的文件记为"非音频"，不会出现在上面的 audio 表里。
        // 这里退一步查 Files 表，能拿到就收录（拿不到就跳过，绝不影响正常曲目）。
        runCatching { out += scanFilesExtra(ctx, seen) }
        return out
    }

    /**
     * 用 MediaStore.Files 补扫"非 audio 类型"的音频文件。
     *
     * 为什么需要：Android 的媒体扫描器只把认识的扩展名放进 `MediaStore.Audio`，
     * `.dsf / .dff / .dts / .asf / .dff` 这类往往根本进不了 audio 表，用户就会发现
     * "文件明明在，本地库里没有"。Files 表至少能看到文件本身。
     */
    private fun scanFilesExtra(ctx: Context, seen: MutableSet<String>): List<LocalEntry> {
        val out = mutableListOf<LocalEntry>()
        val legacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        @Suppress("DEPRECATION")
        val dirCol = if (legacy) MediaStore.Files.FileColumns.DATA else MediaStore.Files.FileColumns.RELATIVE_PATH
        val cols = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            dirCol
        )
        // RELATIVE_PATH LIKE 'Music/DACWebPlayer%' OR 'Music/24bit%'（老系统换成 DATA LIKE）AND (name LIKE '%.dsf' OR ...)
        val like = EXOTIC_EXT.joinToString(" OR ") { "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?" }
        val selection = "($dirCol LIKE ? OR $dirCol LIKE ?) AND ($like)"
        val args = (arrayOf(
            if (legacy) "%/Music/DACWebPlayer/%" else "Music/DACWebPlayer%",
            if (legacy) "%/Music/24bit/%" else "Music/24bit%"
        )) + EXOTIC_EXT.map { "%.$it" }.toTypedArray()
        ctx.contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), cols, selection, args,
            "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val display = c.getString(1).orEmpty()
                val size = c.getLong(2)
                if (display.isBlank()) continue
                if ("${display}|${size}" in seen) continue
                val ext = display.substringAfterLast('.', "").lowercase()
                if (ext !in EXOTIC_EXT) continue
                val uri = android.content.ContentUris.withAppendedId(
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), id
                )
                val entry = readDocument(ctx, uri, display, size) ?: continue
                seen += "${display}|${size}"
                out += entry
            }
        }
        return out
    }

    /* ---------------- SAF 目录 ---------------- */

    private fun scanTree(ctx: Context, tree: Uri): List<LocalEntry> {
        val out = mutableListOf<LocalEntry>()
        val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, tree) ?: return out
        walk(ctx, root, out, 0)
        return out
    }

    private fun walk(ctx: Context, dir: androidx.documentfile.provider.DocumentFile, out: MutableList<LocalEntry>, depth: Int) {
        if (depth > 2) return
        dir.listFiles().forEach { f ->
            if (f.isDirectory) {
                walk(ctx, f, out, depth + 1)
                return@forEach
            }
            val name = f.name.orEmpty()
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in AUDIO_EXT) return@forEach
            val entry = readDocument(ctx, f.uri, name, f.length()) ?: return@forEach
            out += entry
        }
    }

    private fun readDocument(ctx: Context, uri: Uri, display: String, size: Long): LocalEntry? {
        var title = display.substringBeforeLast('.')
        var artist = ""
        var album = ""
        var duration = 0L
        var coverPath = ""
        runCatching {
            val r = MediaMetadataRetriever()
            r.setDataSource(ctx, uri)
            title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() } ?: title
            artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
            album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()
            duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val pic = r.embeddedPicture
            if (pic != null && pic.isNotEmpty()) {
                coverPath = saveCover(ctx, "saf_" + display.hashCode(), pic)
            }
            r.release()
        }
        // 冷门容器（.dsf / .wma / .aiff / .dts）系统经常一个字都读不出来，
        // 这时用 FFmpeg 的 metadata 字典兜底——它认得所有我们打包进去的解复用器。
        val ext = display.substringAfterLast('.', "").lowercase()
        if (ext in EXOTIC_EXT || artist.isBlank() || album.isBlank() || coverPath.isEmpty()) {
            runCatching {
                val t = FfAudio.tags(ctx, uri.toString()) ?: return@runCatching
                if (t.title.isNotBlank()) title = t.title
                if (artist.isBlank()) artist = t.artist.ifBlank { t.albumArtist }
                if (album.isBlank()) album = t.album
                if (coverPath.isEmpty() && t.cover != null && t.cover.isNotEmpty()) {
                    coverPath = saveCover(ctx, "ff_" + display.hashCode(), t.cover)
                }
            }
        }
        return LocalEntry(
            track = Track(
                id = "local:$uri",
                name = title,
                artist = artist,
                album = album,
                cover = coverPath,
                source = SOURCE_LOCAL,
                localUri = uri.toString()
            ),
            sizeBytes = size,
            durationMs = duration,
            mime = "",
            displayName = display
        )
    }

    /* ---------------- 封面缩略图 ---------------- */

    /**
     * 取封面。优先读文件里内嵌的原始封面（下载时写入的是 800×800），
     * 因为 MediaStore 的缩略图接口只会给 240×240，铺满播放页就会糊。
     * 内嵌图读不到时才退回 loadThumbnail，并直接要 800×800。
     */
    private fun thumbnail(ctx: Context, uri: Uri, key: String): String {
        val dir = File(ctx.cacheDir, COVER_DIR).apply { mkdirs() }
        val f = File(dir, "$key.png")
        if (f.exists() && f.length() > 0) return f.absolutePath
        return runCatching {
            val mmr = MediaMetadataRetriever()
            val raw = try {
                mmr.setDataSource(ctx, uri)
                mmr.embeddedPicture
            } finally {
                runCatching { mmr.release() }
            }
            if (raw != null && raw.isNotEmpty()) {
                val img = File(dir, "$key.img").apply { writeBytes(raw) }
                return img.absolutePath
            }
            val bmp = ctx.contentResolver.loadThumbnail(uri, Size(800, 800), null)
            f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
            f.absolutePath
        }.getOrDefault("")
    }

    private fun saveCover(ctx: Context, key: String, bytes: ByteArray): String {
        val dir = File(ctx.cacheDir, COVER_DIR).apply { mkdirs() }
        val f = File(dir, "$key.img")
        if (!f.exists() || f.length() == 0L) {
            runCatching { f.writeBytes(bytes) }
        }
        return if (f.exists()) f.absolutePath else ""
    }

    const val SOURCE_LOCAL = "local"
    const val COVER_DIR = "local_covers"
}
