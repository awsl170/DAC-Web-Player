package com.dacweb.player.prefs

import android.content.Context

/** 歌词配色：索引 0 表示跟随主题（用 colorPrimary 作为高亮色）。 */
object LyricPalette {
    /** 0 为占位，跟随主题时不使用。 */
    val colors = intArrayOf(
        0,
        0xFFFFFFFF.toInt(),
        0xFFBDBDBD.toInt(),
        0xFFFFD54F.toInt(),
        0xFF81D4FA.toInt(),
        0xFFF48FB1.toInt()
    )

    fun labelIndex(ctx: Context, index: Int): String =
        ctx.resources.getStringArray(com.dacweb.player.R.array.lyric_colors).getOrElse(index) { "" }

    /** 返回 0 表示跟随主题。 */
    fun color(index: Int): Int = colors.getOrElse(index) { 0 }
}
