package com.dacweb.player.download

import android.content.Context
import com.dacweb.player.lyrics.LyricsOverride
import com.dacweb.player.lyrics.LyricsRepo
import com.dacweb.player.model.Quality
import com.dacweb.player.model.StreamInfo
import com.dacweb.player.model.Track
import com.dacweb.player.plugin.Providers

/**
 * 下载元数据补全：先用直链返回的字段，缺的部分问插件（[Providers.enrich]）。
 * App 本身不认识任何具体音源，插件没实现 enrich 时就只写直链里已有的信息。
 */
object MetaEnricher {

    /** 没有有效歌词时写入的占位文本。 */
    const val PLACEHOLDER_LYRICS = "[00:05.00]纯音乐,请欣赏"

    private val LRC_TIME = Regex("\\[\\d{1,3}:\\d{1,2}")

    /** 备用源可能是纯文本歌词（没有时间轴），只要有实质内容就接受。 */
    private fun isVisibleText(text: String): Boolean {
        val body = text.replace(Regex("\\[[^\\]]*]"), "").trim()
        return body.length >= 6 && body.any { it.isLetterOrDigit() }
    }

    /** 至少要有时间轴，且不是空壳（避免只返回 "[ti:...]" 这类头部）。 */
    private fun isValidLyrics(text: String): Boolean {
        if (text.isBlank()) return false
        if (!LRC_TIME.containsMatchIn(text)) return false
        val body = text.lines()
            .filter { it.isNotBlank() && !it.trim().startsWith("[") || LRC_TIME.containsMatchIn(it) }
            .joinToString("")
            .replace(Regex("\\[[^\\]]*]"), "")
            .trim()
        return body.length >= 4
    }

    suspend fun build(ctx: Context, stream: StreamInfo, quality: Quality, trackId: String): Downloader.Meta {
        val meta = runCatching {
            Providers.enrich(
                ctx,
                Track(
                    id = trackId,
                    name = stream.name,
                    artist = stream.artist,
                    album = stream.album,
                    cover = stream.cover,
                    quality = quality.name
                ),
                quality
            )
        }.getOrNull()

        val manualLyrics = runCatching {
            LyricsOverride.get(ctx, stream.name.ifBlank { "未知" }, stream.artist)
        }.getOrNull()
        val pluginLyrics = runCatching {
            LyricsRepo.text(stream.name.ifBlank { "未知" }, stream.artist)
        }.getOrDefault("").orEmpty()
        // 手动选过的歌词优先，其次是插件给的，再不行用占位
        val lyrics = when {
            !manualLyrics.isNullOrBlank() -> manualLyrics
            isValidLyrics(pluginLyrics) || isVisibleText(pluginLyrics) -> pluginLyrics
            meta != null && isValidLyrics(meta.lyrics) -> meta.lyrics
            else -> PLACEHOLDER_LYRICS
        }

        // 标签必须和播放界面显示的一致：标题/歌手/专辑/封面优先取直链返回的字段，
        // 插件只补页面里没有的信息（年份、音轨号等）。
        val title = stream.name.takeIf { it.isNotBlank() } ?: meta?.title.orEmpty()
        val artist = stream.artist.takeIf { it.isNotBlank() } ?: meta?.artist.orEmpty()
        val album = stream.album.takeIf { it.isNotBlank() } ?: meta?.album.orEmpty()
        val cover = stream.cover.takeIf { it.isNotBlank() } ?: meta?.coverUrl.orEmpty()

        val comment = buildString {
            append("音质=").append(stream.quality.ifBlank { quality.short })
            if (stream.size.isNotBlank()) append("; 大小=").append(stream.size)
            append("; id=").append(trackId)
            if (stream.format.isNotBlank()) append("; 格式=").append(stream.format)
        }

        return Downloader.Meta(
            id = trackId,
            title = title,
            artist = artist,
            album = album,
            cover = cover,
            quality = stream.quality.ifBlank { quality.short },
            format = stream.format,
            size = stream.size,
            albumArtist = artist,
            year = meta?.year ?: 0,
            trackNo = meta?.trackNo ?: 0,
            discNo = meta?.discNo ?: 0,
            alias = meta?.alias.orEmpty(),
            comment = comment,
            lyrics = lyrics
        )
    }

    fun placeholder(track: Track, quality: Quality): Downloader.Meta = Downloader.Meta(
        id = track.id,
        title = track.name,
        artist = track.artist,
        album = track.album,
        cover = track.cover,
        quality = quality.short,
        format = "",
        size = ""
    )
}
