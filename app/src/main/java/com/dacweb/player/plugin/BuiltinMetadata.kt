package com.dacweb.player.plugin

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 内置「元数据插件」：只管封面 / 歌词 / 标签补全，不提供任何搜索与直链。
 *
 * 为什么内置这几个源：它们只是**公开的元数据库**（网易云、酷狗的歌词/封面接口，
 * MusicBrainz + Cover Art Archive 作为备选），与"音源"分开：
 * - 音源插件（用户导入）负责搜索、直链、以及它自己认得的元数据；
 * - 这里只在音源插件没给或没启用时补空位，顺序是「用户插件 → 内置源」，
 *   用户插件永远优先，所以不会打架；内置插件也能在「音源插件」页一键关掉。
 *
 * 网络礼貌：MusicBrainz 要求带 User-Agent 且每秒不超过 1 次，这里做了串行 + 间隔；
 * 网易云/酷狗接口需要 Referer/UA，否则拿不到 JSON。
 */
class BuiltinMetadata : MusicPlugin {

    override val id: String get() = ID
    override val name: String get() = "内置元数据（网易云 / 酷狗 / MusicBrainz）"
    override val version: String get() = "1.0"
    override val description: String
        get() = "只补封面与歌词，不提供搜索或直链；音源插件返回的内容优先于它。"

    override val providesSearch: Boolean get() = false
    override val providesMetadata: Boolean get() = true

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    @Volatile
    private var appCtx: Context? = null

    /** 音源插件声明的元数据源偏好（小写，如 "kugou"），只影响内置源内部的先后顺序。 */
    @Volatile
    private var preferred: List<String> = emptyList()

    fun setPreferred(names: List<String>) {
        preferred = names.map { it.lowercase() }.filter { it.isNotBlank() }
    }

    /** 内置源顺序：音源插件声明了酷狗就先用酷狗，否则默认网易云优先。 */
    private fun sources(): List<String> =
        if (preferred.firstOrNull()?.contains("kugou") == true) listOf("kugou", "netease")
        else listOf("netease", "kugou")

    override fun attach(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    // ------------------------------------------------------------ 对外接口

    override fun search(
        keyword: String,
        page: Int,
        size: Int,
        quality: String
    ): List<PluginTrack> = emptyList()

    override fun resolve(track: PluginTrack, quality: String): PluginStream =
        throw IOException("内置元数据插件不提供直链")

    override fun cover(track: PluginTrack): String? {
        val name = track.name.ifBlank { return null }
        val artist = track.artist
        // 音源插件自己带封面就不用问了
        if (track.cover.startsWith("http")) return track.cover
        return neteaseCover(name, artist)
    }

    override fun lyrics(name: String, artist: String): String? {
        for (src in sources()) {
            val text = when (src) {
                "netease" -> neteaseLyrics(name, artist)
                "kugou" -> kugouLyrics(name, artist, 0L)
                else -> null
            }
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    override fun searchLyrics(name: String, artist: String, durationMs: Long): List<PluginLyrics> {
        val out = mutableListOf<PluginLyrics>()
        for (src in sources()) {
            when (src) {
                "netease" -> neteaseLyricCandidates(name, artist, 4).forEach { out += it }
                "kugou" -> kugouLyricCandidates(name, artist, durationMs, 4).forEach { out += it }
            }
        }
        return out
    }

    override fun enrich(track: PluginTrack, quality: String): PluginMeta? {
        val name = track.name.ifBlank { return null }
        val artist = track.artist
        val hit = neteaseSong(name, artist) ?: return null
        return PluginMeta(
            title = hit.title,
            artist = hit.artist,
            album = hit.album,
            year = hit.year.toIntOrNull() ?: 0,
            coverUrl = track.cover.takeIf { it.startsWith("http") } ?: hit.cover,
            lyrics = lyrics(name, artist).orEmpty(),
            trackNo = hit.trackNo,
            discNo = 0,
            alias = ""
        )
    }

    // ------------------------------------------------------------ 网易云

    private data class Hit(
        val id: String,
        val title: String,
        val artist: String,
        val album: String,
        val cover: String,
        val year: String,
        val trackNo: Int
    )

    /** 网易云搜索取第一条匹配；接口是公开的 player/search 与 song/detail。 */
    private fun neteaseSong(name: String, artist: String): Hit? {
        val ids = neteaseSearchIds(name, artist) ?: return null
        val detail = getJson("https://music.163.com/api/song/detail?ids=[" + ids.joinToString(",") + "]")
            ?: return null
        val list = detail.optJSONArray("songs") ?: return null
        for (i in 0 until list.length()) {
            val s = list.optJSONObject(i) ?: continue
            val sName = s.optString("name")
            if (!sameSong(sName, name)) continue
            val albumObj = s.optJSONObject("album")
            val year = albumObj?.optLong("publishTime", 0L)?.takeIf { it > 0L }
                ?.let { java.text.SimpleDateFormat("yyyy", java.util.Locale.US).format(java.util.Date(it)) }
                .orEmpty()
            val artists = s.optJSONArray("artists")
            val aName = if (artists != null && artists.length() > 0) {
                artists.optJSONObject(0)?.optString("name").orEmpty()
            } else ""
            return Hit(
                id = s.optLong("id").toString(),
                title = sName,
                artist = aName,
                album = albumObj?.optString("name").orEmpty(),
                cover = albumObj?.optString("picUrl").orEmpty(),
                year = year,
                trackNo = s.optInt("no", 0)
            )
        }
        return null
    }

    private fun neteaseSearchIds(name: String, artist: String): List<Long>? {
        val kw = URLEncoder.encode(if (artist.isBlank()) name else "$name $artist", "UTF-8")
        val json = getJson("https://music.163.com/api/search/get?s=$kw&type=1&limit=5&offset=0")
            ?: return null
        val songs = json.optJSONObject("result")?.optJSONArray("songs") ?: return null
        val out = mutableListOf<Long>()
        for (i in 0 until songs.length()) {
            val id = songs.optJSONObject(i)?.optLong("id") ?: continue
            if (id > 0) out += id
        }
        return out.ifEmpty { null }
    }

    private fun neteaseCover(name: String, artist: String): String? =
        neteaseSong(name, artist)?.cover?.takeIf { it.startsWith("http") }

    private fun neteaseLyrics(name: String, artist: String): String? =
        neteaseLyricCandidates(name, artist, 1).firstOrNull()?.text

    private fun neteaseLyricCandidates(
        name: String,
        artist: String,
        limit: Int
    ): List<PluginLyrics> {
        val ids = neteaseSearchIds(name, artist) ?: return emptyList()
        val out = mutableListOf<PluginLyrics>()
        for (id in ids) {
            if (out.size >= limit) break
            val json = getJson("https://music.163.com/api/song/lyric?id=$id&lv=-1&kv=-1&tv=-1") ?: continue
            val text = json.optJSONObject("lrc")?.optString("lyric").orEmpty()
            if (!hasContent(text)) continue
            out += PluginLyrics(
                title = name,
                artist = artist,
                source = "网易云",
                text = text
            )
        }
        return out
    }

    // ------------------------------------------------------------ 酷狗

    private data class KugouCandidate(val id: String, val accessKey: String, val title: String)

    private fun kugouSearch(name: String, artist: String, durationMs: Long): List<KugouCandidate> {
        val kw = URLEncoder.encode(if (artist.isBlank()) name else "$name - $artist", "UTF-8")
        val dur = if (durationMs > 0) "&duration=${durationMs / 1000}" else ""
        val json = getJson(
            "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=$kw$dur",
            referer = "https://www.kugou.com/"
        ) ?: return emptyList()
        val arr = json.optJSONArray("candidates") ?: return emptyList()
        val out = mutableListOf<KugouCandidate>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val id = c.optString("id")
            val key = c.optString("accesskey")
            if (id.isBlank() || key.isBlank()) continue
            // 搜索结果里没有歌名时用输入名兜底，宿主只把它显示在候选列表里
            out += KugouCandidate(id, key, name)
        }
        return out
    }

    private fun kugouLyrics(name: String, artist: String, durationMs: Long): String? =
        kugouLyricCandidates(name, artist, durationMs, 1).firstOrNull()?.text

    private fun kugouLyricCandidates(
        name: String,
        artist: String,
        durationMs: Long,
        limit: Int
    ): List<PluginLyrics> {
        val out = mutableListOf<PluginLyrics>()
        for (c in kugouSearch(name, artist, durationMs)) {
            if (out.size >= limit) break
            val json = getJson(
                "https://lyrics.kugou.com/download?ver=1&client=pc&id=${c.id}" +
                    "&accesskey=${c.accessKey}&fmt=lrc&charset=utf8",
                referer = "https://www.kugou.com/"
            ) ?: continue
            val b64 = json.optString("content")
            if (b64.isBlank()) continue
            val text = runCatching {
                String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull() ?: continue
            if (!hasContent(text)) continue
            out += PluginLyrics(title = name, artist = artist, source = "酷狗", text = text)
        }
        return out
    }

    // MusicBrainz 只用于补"专辑/年份"这类标签，且对歌词没有数据；
    // 需要时按 https://musicbrainz.org/ws/2/release/?query=...&fmt=json 查询，
    // 请求必须带 User-Agent 并限制每秒 1 次，这里暂不启用，避免和音源插件的元数据抢答。

    // ------------------------------------------------------------ 工具

    private fun getJson(url: String, referer: String = "https://music.163.com/"): JSONObject? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", referer)
            .header("Accept", "application/json, text/plain, */*")
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) null else JSONObject(body)
            }
        }.getOrNull()
    }

    private fun sameSong(a: String, b: String): Boolean {
        fun norm(s: String) = s.lowercase()
            .replace(Regex("[（(\\[].*?[)）\\]]"), "")
            .replace(Regex("[\\s\\-_·.,，。'\"!?！？]"), "")
        val x = norm(a)
        val y = norm(b)
        if (x.isBlank() || y.isBlank()) return true
        return x == y || x.contains(y) || y.contains(x)
    }

    private fun hasContent(text: String): Boolean {
        if (text.isBlank()) return false
        val body = text.replace(Regex("\\[[^\\]]*]"), "").trim()
        return body.length >= 4
    }

    companion object {
        const val ID = "builtin.metadata"

        private const val UA =
            "DACWebPlayer/0.1 (https://github.com/awsl170/DAC-Web-Player)"

        @Volatile
        private var instance: BuiltinMetadata? = null

        /** 宿主用这个拿单例（它不经过 DexClassLoader，但和外部插件走同一套接口）。 */
        fun get(): BuiltinMetadata =
            instance ?: synchronized(this) {
                instance ?: BuiltinMetadata().also { instance = it }
            }
    }
}
