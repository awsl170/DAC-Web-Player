package com.dacweb.player.store

import android.content.Context
import com.dacweb.player.model.Track
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Playlist(
    val id: String,
    val name: String,
    val tracks: List<Track> = emptyList()
)

/**
 * 歌单本地存储：filesDir/playlists.json（原子写，先写 .tmp 再改名）。
 * 每首歌保存完整 Track（含 source/sign/time/quality），所以歌单记得音质、也能重新解析直链。
 */
object PlaylistStore {

    private const val FILE = "playlists.json"

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun list(ctx: Context): List<Playlist> = read(ctx)

    fun create(ctx: Context, name: String): Playlist {
        val trimmed = name.trim().ifBlank { "未命名歌单" }
        val p = Playlist(UUID.randomUUID().toString(), trimmed)
        write(ctx, read(ctx) + p)
        return p
    }

    fun rename(ctx: Context, id: String, name: String): Boolean {
        val all = read(ctx).toMutableList()
        val i = all.indexOfFirst { it.id == id }
        if (i < 0) return false
        all[i] = all[i].copy(name = name.trim().ifBlank { all[i].name })
        write(ctx, all)
        return true
    }

    fun delete(ctx: Context, id: String) {
        write(ctx, read(ctx).filterNot { it.id == id })
    }

    /** 添加曲目（同源同 id 去重），返回真正新增的数量。 */
    fun add(ctx: Context, playlistId: String, tracks: List<Track>): Int {
        val all = read(ctx).toMutableList()
        val i = all.indexOfFirst { it.id == playlistId }
        if (i < 0 || tracks.isEmpty()) return 0
        val cur = all[i].tracks.toMutableList()
        var added = 0
        for (t in tracks) {
            if (cur.any { it.source == t.source && it.id == t.id }) continue
            cur += t.copy(localUri = "")
            added++
        }
        if (added > 0) {
            all[i] = all[i].copy(tracks = cur)
            write(ctx, all)
        }
        return added
    }

    /**
     * 添加曲目，并把封面下载到本地（file://），这样歌单离线也有图。
     */
    suspend fun addWithCovers(ctx: Context, playlistId: String, tracks: List<Track>): Int {
        val localized = tracks.map { t ->
            if (t.cover.startsWith("http")) {
                val saved = CoverStore.save(ctx, t.cover)
                if (saved != null) t.copy(cover = saved) else t
            } else t
        }
        return add(ctx, playlistId, localized)
    }

    fun removeTrack(ctx: Context, playlistId: String, track: Track) {
        val all = read(ctx).toMutableList()
        val i = all.indexOfFirst { it.id == playlistId }
        if (i < 0) return
        all[i] = all[i].copy(tracks = all[i].tracks.filterNot { it.source == track.source && it.id == track.id })
        write(ctx, all)
    }

    /** 所有歌单里出现过的曲目（用于搜索结果标记与本地优先匹配）。 */
    fun allTracks(ctx: Context): List<Track> = read(ctx).flatMap { it.tracks }

    fun matchKeys(ctx: Context): Set<String> = allTracks(ctx).map { it.matchKey }.toSet()

    fun playlistsContaining(ctx: Context, track: Track): List<String> =
        read(ctx).filter { p -> p.tracks.any { it.source == track.source && it.id == track.id } }
            .map { it.name }

    /* ---------------- JSON ---------------- */

    private fun read(ctx: Context): List<Playlist> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return runCatching {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("playlists") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Playlist(
                    id = o.optString("id"),
                    name = o.optString("name", "未命名歌单"),
                    tracks = parseTracks(o.optJSONArray("tracks"))
                )
            }.filter { it.id.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun write(ctx: Context, list: List<Playlist>) {
        val root = JSONObject()
        val arr = JSONArray()
        list.forEach { p ->
            val o = JSONObject()
                .put("id", p.id)
                .put("name", p.name)
            val ta = JSONArray()
            p.tracks.forEach { t ->
                ta.put(
                    JSONObject()
                        .put("id", t.id)
                        .put("name", t.name)
                        .put("artist", t.artist)
                        .put("album", t.album)
                        .put("cover", t.cover)
                        .put("source", t.source)
                        .put("sign", t.sign)
                        .put("time", t.time)
                        .put("quality", t.quality)
                        .put("provider", t.provider)
                )
            }
            o.put("tracks", ta)
            arr.put(o)
        }
        root.put("playlists", arr)
        val f = file(ctx)
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        runCatching {
            tmp.writeText(root.toString())
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        }
    }

    private fun parseTracks(arr: JSONArray?): List<Track> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Track(
                id = o.optString("id"),
                name = o.optString("name"),
                artist = o.optString("artist"),
                album = o.optString("album"),
                cover = o.optString("cover"),
                source = o.optString("source", ""),
                sign = o.optString("sign"),
                time = o.optLong("time"),
                quality = o.optString("quality"),
                provider = o.optString("provider")
            )
        }
    }
}
