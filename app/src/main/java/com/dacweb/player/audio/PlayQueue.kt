package com.dacweb.player.audio

import android.content.Context
import android.os.Bundle
import androidx.core.net.toUri
import androidx.core.net.toFile
import java.io.File
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.dacweb.player.model.Quality
import com.dacweb.player.model.Track
import com.dacweb.player.plugin.Providers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * 播放队列：保存一次搜索结果的完整曲目表，负责「解析直链 → 播放」以及上一首/下一首。
 * 队列是进程内单例，MediaSessionService 与 Activity 共用同一份。
 */
object PlayQueue {

    const val EXTRA_QUALITY = "quality"
    const val EXTRA_FORMAT = "format"
    const val EXTRA_SIZE = "size"
    const val EXTRA_ID = "id"

    private var items: List<Track> = emptyList()
    private var index: Int = -1

    val size: Int get() = items.size
    val currentIndex: Int get() = index
    val current: Track? get() = items.getOrNull(index)

    /** 当前队列的快照（供队列面板展示）。 */
    fun snapshot(): List<Track> = items

    fun set(list: List<Track>, start: Int) {
        items = list.toList()
        index = start.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    }

    fun clear() {
        items = emptyList()
        index = -1
    }

    /** 插入到当前曲目之后（「添加到下一首」）。空队列时直接成为当前曲目。 */
    fun insertNext(track: Track): Int {
        if (items.isEmpty()) {
            items = listOf(track)
            index = 0
            return 0
        }
        val at = (index + 1).coerceIn(0, items.size)
        val list = items.toMutableList()
        list.add(at, track)
        items = list
        return at
    }

    /** 解析当前曲目并开始播放。被插件限额时抛 [com.dacweb.player.plugin.PluginLimitException]。 */
    suspend fun playCurrent(ctx: Context, player: Player): Boolean {
        val track = current ?: return false
        if (track.isLocal) return playLocal(player, track)
        val quality = qualityFor(ctx, track)
        val info = withContext(Dispatchers.IO) { Providers.resolve(ctx, track, quality) }

        val item = buildItem(track, info)

        player.setMediaItem(item)
        player.prepare()
        player.play()
        return true
    }

    private fun buildItem(track: Track, info: com.dacweb.player.model.StreamInfo): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(info.name.ifBlank { track.name })
            .setArtist(info.artist.ifBlank { track.artist })
            .setAlbumTitle(info.album.ifBlank { track.album })
            .setExtras(Bundle().apply {
                putString(EXTRA_QUALITY, info.quality.ifBlank { "在线" })
                putString(EXTRA_FORMAT, info.format)
                putString(EXTRA_SIZE, info.size)
                putString(EXTRA_ID, track.id)
            })
            .apply {
                // 只用详情页 RSC 里的封面（126 CDN），不用搜索结果 JSON 的 cover
                if (info.cover.isNotEmpty()) setArtworkUri(info.cover.toUri())
            }
            .build()
        return MediaItem.Builder()
            .setUri(info.url)
            .setMediaId(track.id)
            .setMediaMetadata(metadata)
            .build()
    }

    /**
     * 播放报错后的自愈：强制重新解析直链（绕过缓存），并从当前进度续播。
     * 直链带时间戳会过期，缓存里也可能有残缺 Span，所以先清网页缓存再重取。
     */
    suspend fun replayForce(ctx: Context, player: Player): Boolean {
        val track = current ?: return false
        if (track.isLocal) return playLocal(player, track)
        val quality = qualityFor(ctx, track)
        val info = withContext(Dispatchers.IO) { Providers.resolve(ctx, track, quality, force = true) }
        val pos = player.currentPosition.coerceAtLeast(0L)
        player.setMediaItem(buildItem(track, info), pos)
        player.prepare()
        player.play()
        return true
    }

    /** 歌单里记的音质优先，否则跟随当前设置（具体怎么取由插件决定）。 */
    private fun qualityFor(ctx: Context, track: Track): Quality =
        track.quality.takeIf { it.isNotBlank() }?.let { Quality.byName(it) } ?: Quality.current(ctx)

    /** 本地文件直接播放，不解析、不消耗额度。 */
    private fun playLocal(player: Player, track: Track): Boolean {
        val uri = track.localUri.toUri()
        val ext = track.localUri.substringAfterLast('.', "").lowercase()
        val metadata = MediaMetadata.Builder()
            .setTitle(track.name)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setExtras(Bundle().apply {
                putString(EXTRA_QUALITY, "本地")
                putString(EXTRA_FORMAT, ext)
                putString(EXTRA_SIZE, "")
                putString(EXTRA_ID, track.id)
            })
            .apply {
                val c = track.cover
                if (c.isNotBlank()) {
                    // 网络文件的封面是 NetMeta 落在 cacheDir 的 file:// URI，不能再套一层 File()
                    setArtworkUri(
                        when {
                            c.startsWith("http") || c.startsWith("content:") || c.startsWith("file:") ||
                                c.startsWith("android.resource:") -> c.toUri()
                            else -> File(c).toUri()
                        }
                    )
                }
            }
            .build()
        val item = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(track.id)
            .setMediaMetadata(metadata)
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.play()
        return true
    }

    suspend fun playAt(ctx: Context, player: Player, i: Int): Boolean {
        if (i !in items.indices) return false
        index = i
        return playCurrent(ctx, player)
    }

    /** 下一首；返回 false 表示队列到头（未开启循环）。 */
    suspend fun next(ctx: Context, player: Player): Boolean {
        if (items.isEmpty()) return false
        if (player.shuffleModeEnabled && items.size > 1) {
            var n = index
            while (n == index) n = Random.nextInt(items.size)
            return playAt(ctx, player, n)
        }
        var n = index + 1
        if (n >= items.size) {
            // 「列表循环」也在这里判断：ExoPlayer 内部循环会让 ENDED 永不触发
            if (PlayRepeat.mode == Player.REPEAT_MODE_ALL) n = 0 else return false
        }
        return playAt(ctx, player, n)
    }

    suspend fun prev(ctx: Context, player: Player): Boolean {
        if (items.isEmpty()) return false
        var p = index - 1
        if (p < 0) {
            if (PlayRepeat.mode == Player.REPEAT_MODE_ALL) p = items.size - 1 else return false
        }
        return playAt(ctx, player, p)
    }
}
