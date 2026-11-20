package com.dacweb.player.model

import android.content.Context

/**
 * 音质档位。宿主只负责"选一个档位名"，具体怎么取（哪个接口、什么参数）由插件解释。
 *
 * 因此这里不再包含任何音源信息，只有给用户看的文案与格式提示。
 */
enum class Quality(
    /** 设置页/对话框里的完整说明。 */
    val label: String,
    /** 顶栏按钮上的短名。 */
    val short: String,
    /** 估算格式，用于下载标签兜底。 */
    val format: String
) {
    MASTER("母带级 24bit/192kHz", "母带", "flac"),
    HIRES("高解析 24bit/96kHz", "高解析", "flac"),
    CD("CD 级 16bit/44.1kHz", "CD", "flac"),
    LOSSLESS("无损 FLAC", "无损", "flac"),
    MP3_320("MP3 320kbps", "320k", "mp3"),
    MP3_128("MP3 128kbps", "128k", "mp3");

    companion object {
        private const val PREF = "app_prefs"
        private const val KEY = "quality_name"
        private const val KEY_OLD = "quality_path"

        /** 旧版本存的是音源相关的名字，读回来时映射到等价档位。 */
        private val LEGACY = mapOf(
            "KUWO_FLAC" to LOSSLESS,
            "KUWO_320" to MP3_320,
            "KUWO_128" to MP3_128
        )

        fun current(ctx: Context): Quality {
            val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            sp.getString(KEY, null)?.let { n ->
                entries.firstOrNull { it.name == n }?.let { return it }
                LEGACY[n]?.let { return it }
            }
            // 兼容更旧版本只存 path("a"/"c"/"b") 的情况
            when (sp.getString(KEY_OLD, null)) {
                "a" -> return MASTER
                "c" -> return HIRES
                "b" -> return CD
            }
            return HIRES
        }

        fun set(ctx: Context, q: Quality) {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY, q.name).apply()
        }

        /** 歌单里存的音质名 → 档位（旧名字也能解析）。 */
        fun byName(name: String): Quality? =
            entries.firstOrNull { it.name == name } ?: LEGACY[name]
    }
}
