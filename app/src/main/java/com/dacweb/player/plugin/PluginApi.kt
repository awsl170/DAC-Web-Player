package com.dacweb.player.plugin

import android.content.Context
import java.io.IOException

/**
 * 插件 API：宿主只定义"搜索 / 直链 / 歌词 / 元数据"这几件事，具体音源完全由插件实现。
 *
 * 设计约束（改动等于改插件 ABI，请慎重）：
 * - 只依赖 Android SDK 与 JDK，不引用宿主内部类，外部插件编译时提供宿主 APK 作为 classpath 即可；
 * - 所有方法都是阻塞式，宿主在 IO 线程调用，插件里可以直接发网络请求，**不要自己再起线程或 runBlocking**；
 * - 宿主不设超时：网络请求请自己设 connect/read timeout，否则一次卡死会拖住整次搜索；
 * - 失败请抛异常（[PluginLimitException] 表示"额度用尽"，宿主会给出不同于普通错误的提示）；
 * - 接口只增不改：新增成员一律带默认实现，旧插件无需重新编译。
 */
interface MusicPlugin {

    /** 唯一 id，建议倒序域名风格，如 "com.example.myprovider"。 */
    val id: String

    /** 显示名。 */
    val name: String

    /** 版本号，仅用于展示。 */
    val version: String get() = "1.0"

    /** 一句话说明，显示在插件页。 */
    val description: String get() = ""

    /**
     * 是否是「音源插件」：提供搜索与直链，参与搜索结果合并。
     * 只做封面/歌词/标签的「元数据插件」请返回 false，这样宿主不会在搜索时白问它一次。
     */
    val providesSearch: Boolean get() = true

    /**
     * 是否提供元数据（封面 / 歌词 / 标签补全）。
     * 返回 false 的插件宿主不会把它放进元数据回落链，能省掉无谓的网络请求。
     */
    val providesMetadata: Boolean get() = true

    /**
     * 插件针对的接口版本。宿主当前是 2；将来接口有不兼容改动会递增，
     * 宿主遇到比自更新版的插件会拒绝加载并给出提示，而不是运行到一半崩掉。
     */
    val apiVersion: Int get() = 2

    /**
     * 音源插件可以声明希望优先使用的元数据源，宿主会把它排到元数据链前面。
     * 取值是元数据插件内部的源名，如 "netease"、"kugou"；不认识的值会被忽略。
     */
    fun preferredMetadata(): List<String> = emptyList()

    /**
     * 宿主初始化时调用一次，参数是 applicationContext。
     * 可以在这里创建缓存目录、读取配置；不需要就留空。
     */
    fun attach(ctx: Context) {}

    /**
     * 搜索。
     *
     * @param keyword 用户输入的关键词，可能包含空格
     * @param page 从 1 开始
     * @param size 期望返回条数（宿主只是建议）。返回条数少于 size 即视为最后一页，
     *             宿主不会再请求下一页；因此"刚好最后一页"时多返回几条空占位不如少返回
     * @param quality 宿主当前音质档位名（如 "HIRES"、"MP3_320"），插件自行解释
     * @return 命中的曲目；没有结果返回空列表，不要返回 null
     */
    @Throws(IOException::class)
    fun search(keyword: String, page: Int, size: Int, quality: String): List<PluginTrack>

    /**
     * 取播放直链。传入的 [track] 就是本插件 [search] 返回的对象，[PluginTrack.extra] 原样带回。
     *
     * 耗时操作（分页请求、解密）都在这里做；宿主已把调用放在 IO 线程。
     * 失败抛 [IOException]（方法已声明 throws，Java 插件直接写 `throw new IOException(..)`）；
     * 额度用尽抛 [PluginLimitException]。
     */
    @Throws(IOException::class)
    fun resolve(track: PluginTrack, quality: String): PluginStream

    /**
     * 同 [resolve]，但 force=true 表示绕过插件自己的直链缓存（播放失败自愈时用）。
     * 默认实现直接调用 [resolve]；有缓存层的插件请覆盖它。
     */
    @Throws(IOException::class)
    fun resolve(track: PluginTrack, quality: String, force: Boolean): PluginStream =
        resolve(track, quality)

    /**
     * 取歌词原文（LRC 或纯文本）。
     * @return null/空串表示"没有"，宿主会继续尝试本地文件内嵌歌词。
     */
    fun lyrics(name: String, artist: String): String? = null

    /**
     * 歌词候选（播放页「搜索歌词」用）。
     * @return 候选列表，每条带显示用的来源名与正文；不需要这个功能就留空。
     */
    fun searchLyrics(name: String, artist: String, durationMs: Long): List<PluginLyrics> =
        emptyList()

    /**
     * 补封面 URL（搜索结果里通常没有封面，宿主会在后台逐条调用）。
     * @return 图片 URL；没有就返回 null。
     */
    fun cover(track: PluginTrack): String? = null

    /**
     * 下载时补全标签元数据（专辑、年份、音轨号、封面、歌词等）。
     * 只填你知道的字段，其余留空即可；宿主会用直链返回的字段兜底。
     */
    fun enrich(track: PluginTrack, quality: String): PluginMeta? = null
}

/** 插件入口：外部包里的入口类要么实现它，要么直接实现 [MusicPlugin]。 */
interface PluginFactory {
    fun create(): MusicPlugin
}

/**
 * 额度用尽。插件在 [MusicPlugin.resolve] / [MusicPlugin.search] 里抛出它，
 * 宿主会显示"今日额度已用尽"这类提示，而不是普通的失败。
 */
class PluginLimitException(message: String = "今日访问已达限额") : IOException(message)

/**
 * 插件视角的曲目。宿主会把整个对象放进播放队列并写入歌单，
 * [extra] 里的内容在之后 [MusicPlugin.resolve] 时原样带回。
 */
data class PluginTrack @JvmOverloads constructor(
    /**
     * 曲目 id。宿主直接把它当 MediaItem.mediaId 用，所以请保证**全局唯一**，
     * 推荐 `"<插件短名>:<原始 id>"`，否则两个插件返回相同 id 时会串台。
     */
    val id: String,
    val name: String,
    val artist: String = "",
    val album: String = "",
    /** 封面 URL，可以留空由 [MusicPlugin.cover] 补。 */
    val cover: String = "",
    val durationMs: Long = 0L,
    /** 音质标记，宿主原样回传给 resolve()。 */
    val quality: String = "",
    /** 插件私有数据（sign、time、音源标记等）。 */
    val extra: Map<String, String> = emptyMap()
)

/** 插件视角的播放直链。 */
data class PluginStream @JvmOverloads constructor(
    val url: String,
    /** 实际标题/歌手等，留空则用 [PluginTrack] 上的值。 */
    val name: String = "",
    val artist: String = "",
    val album: String = "",
    val cover: String = "",
    /** 展示用音质，如 "24bit/96kHz FLAC"。 */
    val quality: String = "",
    val format: String = "",
    val size: String = "",
    /**
     * 预留字段：宿主**目前不会**把它传给播放器（ExoPlayer 的请求头是全局的）。
     * 需要鉴权的直链请把 token / 过期时间拼进 URL 本身。
     */
    val headers: Map<String, String> = emptyMap()
)

/** 一条歌词候选。 */
data class PluginLyrics @JvmOverloads constructor(
    val title: String,
    val artist: String = "",
    /** 显示用来源名，如 "我的音源"。 */
    val source: String = "",
    /** LRC 或纯文本正文。 */
    val text: String
)

/** 下载标签补全结果，只填有的字段。 */
data class PluginMeta @JvmOverloads constructor(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val year: Int = 0,
    val coverUrl: String = "",
    val lyrics: String = "",
    val trackNo: Int = 0,
    val discNo: Int = 0,
    val alias: String = ""
)
