package com.dacweb.player.lyrics

import android.content.Context

/** 用户手动选过的歌词，按「歌名|歌手」记住，下次下载/播放直接用。 */
object LyricsOverride {

    private const val PREFS = "lyric_override"
    private const val MAX_ENTRIES = 300

    fun key(title: String, artist: String): String =
        title.trim().lowercase() + "|" + artist.trim().lowercase()

    fun get(ctx: Context, title: String, artist: String): String? =
        pref(ctx).getString(key(title, artist), null)?.takeIf { it.isNotBlank() }

    fun put(ctx: Context, title: String, artist: String, lyrics: String) {
        val p = pref(ctx)
        val all = p.all.entries.toMutableList()
        if (all.size > MAX_ENTRIES) {
            all.sortedBy { it.value.toString().length }
                .take(all.size - MAX_ENTRIES + 1)
                .forEach { p.edit().remove(it.key).apply() }
        }
        p.edit().putString(key(title, artist), lyrics).apply()
    }

    fun clear(ctx: Context) = pref(ctx).edit().clear().apply()

    private fun pref(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
