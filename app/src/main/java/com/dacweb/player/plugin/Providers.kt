package com.dacweb.player.plugin

import android.content.Context
import com.dacweb.player.model.Quality
import com.dacweb.player.model.StreamInfo
import com.dacweb.player.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 宿主侧的插件编排：搜索、直链、歌词、封面、标签补全都从这里走。
 *
 * App 本身不含任何音源实现；没有启用插件时搜索会抛 [IOException]，
 * 由 UI 提示用户去「设置 → 音源插件」导入。
 */
object Providers {

    /** 是否至少有一个可用的插件。 */
    fun hasPlugin(ctx: Context): Boolean = PluginManager.enabledPlugins(ctx).isNotEmpty()

    fun reload(ctx: Context) = PluginManager.refresh(ctx)

    /**
     * 搜索：当前选中插件优先，空结果依次回落其它启用插件。
     * @throws PluginLimitException 插件报告额度用尽时原样抛出
     * @throws IOException 没有插件，或所有插件都失败
     */
    suspend fun search(
        ctx: Context,
        keyword: String,
        page: Int,
        size: Int,
        quality: Quality
    ): List<Track> = withContext(Dispatchers.IO) {
        val order = searchOrder(ctx)
        if (order.isEmpty()) throw IOException(noPluginMessage())
        var lastError: String? = null
        for (p in order) {
            val result = runCatching { p.search(keyword, page, size, quality.name) }
            result.onFailure {
                if (it is PluginLimitException) throw it
                lastError = it.message
            }
            val songs = result.getOrNull().orEmpty()
            if (songs.isNotEmpty()) return@withContext songs.map { it.toTrack(p.id) }
        }
        lastError?.let { throw IOException(it) }
        emptyList()
    }

    /**
     * 启用的插件按列表顺序依次尝试（可以同时启用多个，前一个没结果就落到下一个）。
     * 没有"当前插件"的概念了：多源并行/回落由这里统一编排。
     */
    private fun searchOrder(ctx: Context): List<MusicPlugin> =
        PluginManager.enabledPlugins(ctx).filter { it.providesSearch }

    /**
     * 元数据回落链：先问用户导入的插件（它们更了解自己的音源），最后才是内置元数据插件。
     * 只做封面/歌词的插件不受"音源插件优先级"影响，永远不会顶掉别人已经给出的结果。
     */
    private fun metadataOrder(ctx: Context): List<MusicPlugin> {
        val all = PluginManager.enabledPlugins(ctx)
        val external = all.filter { it.providesMetadata && it.id != BuiltinMetadata.ID }
        val builtin = all.firstOrNull { it.id == BuiltinMetadata.ID }
        // 音源插件可以声明自己更信任的元数据源，内置插件据此调整内部源的顺序
        val prefer = all.filter { it.providesSearch }.flatMap {
            runCatching { it.preferredMetadata() }.getOrDefault(emptyList())
        }
        (builtin as? BuiltinMetadata)?.let { runCatching { it.setPreferred(prefer) } }
        return if (builtin == null) external else external + builtin
    }

    fun resolve(ctx: Context, track: Track, quality: Quality, force: Boolean = false): StreamInfo {
        val plugin = choose(ctx, track.provider)
            ?: throw IOException(noPluginMessage())
        val stream = runCatching { plugin.resolve(track.toPlugin(), quality.name, force) }
            .getOrElse { e ->
                if (e is PluginLimitException) throw e
                throw IOException("${plugin.name} 取直链失败：${e.message ?: e.javaClass.simpleName}", e)
            }
        if (stream.url.isBlank()) throw IOException("${plugin.name} 返回了空直链")
        return StreamInfo(
            url = stream.url,
            name = stream.name.ifBlank { track.name },
            artist = stream.artist.ifBlank { track.artist },
            album = stream.album.ifBlank { track.album },
            cover = stream.cover.ifBlank { track.cover },
            quality = stream.quality.ifBlank { quality.label },
            format = stream.format.ifBlank { quality.format },
            size = stream.size
        )
    }

    /** 播放页歌词：按顺序问启用插件，第一个非空即用。 */
    fun lyrics(ctx: Context, name: String, artist: String): String? {
        for (p in metadataOrder(ctx)) {
            val text = runCatching { p.lyrics(name, artist) }.getOrNull()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    /** 手动换歌词的候选列表（多插件结果合并）。 */
    fun searchLyrics(ctx: Context, name: String, artist: String, durationMs: Long): List<PluginLyrics> {
        val out = mutableListOf<PluginLyrics>()
        for (p in metadataOrder(ctx)) {
            runCatching { p.searchLyrics(name, artist, durationMs) }
                .getOrDefault(emptyList())
                .forEach { c -> if (c.text.isNotBlank()) out += c.copy(source = c.source.ifBlank { p.name }) }
        }
        return out
    }

    /** 补封面 URL；插件没实现就返回 null。 */
    fun cover(ctx: Context, track: Track): String? {
        // 先问"这条曲目所属的音源插件"，再按元数据链回落，最后才用内置源
        val own = choose(ctx, track.provider)
        if (own != null && own.providesMetadata) {
            runCatching { own.cover(track.toPlugin()) }.getOrNull()
                ?.takeIf { it.isNotBlank() }?.let { return it }
        }
        for (p in metadataOrder(ctx)) {
            if (p === own) continue
            runCatching { p.cover(track.toPlugin()) }.getOrNull()
                ?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    /** 下载时补标签元数据；插件没实现就返回 null。 */
    fun enrich(ctx: Context, track: Track, quality: Quality): PluginMeta? {
        val own = choose(ctx, track.provider)
        if (own != null && own.providesMetadata) {
            runCatching { own.enrich(track.toPlugin(), quality.name) }.getOrNull()?.let { return it }
        }
        for (p in metadataOrder(ctx)) {
            if (p === own) continue
            runCatching { p.enrich(track.toPlugin(), quality.name) }.getOrNull()?.let { return it }
        }
        return null
    }

    fun noPluginMessage(): String = "还没有启用任何音源插件：请到「设置 → 音源插件」导入"

    /** 按 provider 找插件；provider 为空或未知时退回当前插件/第一个启用插件。 */
    private fun choose(ctx: Context, providerId: String): MusicPlugin? {
        PluginManager.byId(ctx, providerId)?.let { if (PluginManager.isEnabled(ctx, it.id)) return it }
        return PluginManager.enabledPlugins(ctx).firstOrNull()
    }

    private fun PluginTrack.toTrack(providerId: String) = Track(
        id = id,
        name = name,
        artist = artist,
        album = album,
        cover = cover,
        source = extra["source"].orEmpty(),
        sign = extra["sign"].orEmpty(),
        time = extra["time"]?.toLongOrNull() ?: 0L,
        quality = quality,
        provider = providerId
    )

    private fun Track.toPlugin() = PluginTrack(
        id = id,
        name = name,
        artist = artist,
        album = album,
        cover = cover,
        quality = quality,
        extra = buildMap {
            if (source.isNotEmpty()) put("source", source)
            if (sign.isNotEmpty()) put("sign", sign)
            if (time > 0L) put("time", time.toString())
        }
    )
}
