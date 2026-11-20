package com.dacweb.player.model

data class Track(
    val id: String,
    val name: String,
    val artist: String,
    val album: String,
    val cover: String,
    /** 插件私有的来源标记（原样回传给插件，宿主不解释）；本地曲目为空。 */
    val source: String = "",
    /** 插件私有数据，取直链时原样带回。 */
    val sign: String = "",
    val time: Long = 0L,
    /** 加入歌单时记住的音质（Quality.name）；空表示跟随当前设置。 */
    val quality: String = "",
    /** 本地文件 URI；非空时直接播放本地文件，不再走在线音源。 */
    val localUri: String = "",
    /** 产出这条曲目的插件 id（见 plugin/PluginManager）；空 = 未记录，取直链时用当前启用的插件。 */
    val provider: String = ""
) {
    val isLocal: Boolean get() = localUri.isNotEmpty()

    /** 去重/匹配用的键：歌名 + 首歌手。 */
    val matchKey: String
        get() = (name.trim().lowercase() + "|" + artist.split("&", "/", "、", ",")
            .firstOrNull().orEmpty().trim().lowercase())
}

data class StreamInfo(
    val url: String,
    val name: String,
    val artist: String,
    val album: String,
    val cover: String,
    val quality: String,
    val format: String,
    val size: String
)
