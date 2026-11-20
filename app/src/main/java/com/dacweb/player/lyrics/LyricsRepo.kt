package com.dacweb.player.lyrics

import android.content.Context
import android.net.Uri
import com.dacweb.player.plugin.Providers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一行歌词：时间轴（毫秒）+ 文本。 */
data class LyricLine(val timeMs: Long, val text: String)

object Lrc {

    private val TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /** 解析标准 LRC；一行可带多个时间标签，空行与元信息标签忽略。 */
    fun parse(raw: String?): List<LyricLine> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = mutableListOf<LyricLine>()
        for (line in raw.lineSequence()) {
            val tags = TAG.findAll(line).toList()
            if (tags.isEmpty()) continue
            val text = TAG.replace(line, "").trim()
            if (text.isEmpty()) continue
            for (m in tags) {
                val min = m.groupValues[1].toLongOrNull() ?: continue
                val sec = m.groupValues[2].toLongOrNull() ?: continue
                val frac = m.groupValues[3]
                val ms = when {
                    frac.isEmpty() -> 0L
                    frac.length == 1 -> frac.toLong() * 100
                    frac.length == 2 -> frac.toLong() * 10
                    else -> frac.take(3).toLong()
                }
                out += LyricLine(min * 60_000 + sec * 1000 + ms, text)
            }
        }
        if (out.isNotEmpty()) return out.sortedBy { it.timeMs }
        // 一行时间轴都没有：按纯文本歌词显示（LRCLIB 偶尔只给纯文本，占位歌词也可能不带标签）。
        // timeMs = -1 表示"没有时间轴"，indexAt 会跳过，永远不会被高亮。
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("[") }
            .map { LyricLine(-1L, it) }
            .toList()
    }

    /** 取当前时间应高亮的行下标（无匹配返回 -1）。 */
    fun indexAt(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs < 0) continue   // 纯文本歌词：没有时间轴，不高亮
            if (lines[i].timeMs <= positionMs) idx = i else break
        }
        return idx
    }
}

/** 歌词仓库：手动指定 → 插件 → 空。结果按「歌名|歌手」缓存，避免反复请求。 */
object LyricsRepo {

    private val cache = HashMap<String, List<LyricLine>>()

    @Volatile
    private var appCtx: Context? = null

    /** App 启动时调用一次，供没有 Context 的调用方（下载、写标签）使用。 */
    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    /**
     * 取歌词原文：用户手动选的 → 插件。
     * 解析与“写标签”都复用这一份原文，避免同一首歌查两遍。
     */
    suspend fun text(name: String, artist: String): String = withContext(Dispatchers.IO) {
        val ctx = appCtx
        if (ctx != null) {
            val manual = runCatching { LyricsOverride.get(ctx, name, artist) }.getOrNull()
            if (!manual.isNullOrBlank()) return@withContext manual
            Providers.lyrics(ctx, name, artist)?.let { if (it.isNotBlank()) return@withContext it }
        }
        ""
    }

    /**
     * 播放页用：本地文件里内嵌的歌词优先（下载时写进标签的），没有再走网络。
     * 之前只查网络，所以"文件里有歌词、网上查不到"的曲子会显示暂无歌词。
     */
    suspend fun loadFor(
        ctx: Context,
        name: String,
        artist: String,
        localUri: String?
    ): List<LyricLine> = withContext(Dispatchers.IO) {
        if (!localUri.isNullOrBlank()) {
            val uri = Uri.parse(localUri)
            if (uri.scheme == "content" || uri.scheme == "file") {
                val local = runCatching { LocalLyrics.read(ctx, uri) }.getOrNull()
                if (!local.isNullOrBlank()) return@withContext Lrc.parse(local)
            }
        }
        load(name, artist)
    }

    suspend fun load(name: String, artist: String): List<LyricLine> = withContext(Dispatchers.IO) {
        val key = "${name.trim().lowercase()}|${artist.trim().lowercase()}"
        cache[key]?.let { return@withContext it }

        val lines = Lrc.parse(text(name, artist))
        if (cache.size > 60) cache.clear()
        cache[key] = lines
        lines
    }
}
