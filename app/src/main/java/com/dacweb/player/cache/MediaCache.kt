package com.dacweb.player.cache

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import java.io.File

/**
 * 在线音频的统一缓冲层（Media3 SimpleCache）。
 *
 * 三个入口共用同一份磁盘缓存：
 *  - 播放（PlaybackService 的 MediaSourceFactory）：边播边写；
 *  - 下载（Downloader）：只读复用已缓存字节，不产生第二个写者。
 *
 * 缓存键就是音频 URL（默认 CacheKeyFactory），所以三者天然对齐——同一首
 * 只要 URL 没换（直链 45 分钟内有效），播放和下载之间不会重复下载同一段字节。
 */
private class SchemeSwitchFactory(
    private val cached: DataSource.Factory,
    private val plain: DataSource.Factory
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        SchemeSwitchDataSource(cached.createDataSource(), plain.createDataSource())
}

private class SchemeSwitchDataSource(
    private val cached: DataSource,
    private val plain: DataSource
) : DataSource {

    private var delegate: DataSource = cached

    override fun open(dataSpec: DataSpec): Long {
        val scheme = dataSpec.uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            // 缓存层出任何问题（坏 span、被清空、磁盘满）都退回直连，别让整首播放失败
            delegate = cached
            return try {
                cached.open(dataSpec)
            } catch (e: java.io.IOException) {
                cached.close()
                delegate = plain
                plain.open(dataSpec)
            }
        }
        delegate = plain
        return plain.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate.read(buffer, offset, length)

    override fun addTransferListener(transferListener: TransferListener) {
        cached.addTransferListener(transferListener)
        plain.addTransferListener(transferListener)
    }

    override fun getUri(): Uri? = delegate.uri

    override fun close() = delegate.close()
}

object MediaCache {

    private const val DIR = "audio_cache"

    /**
     * 音频缓存上限；LRU 淘汰，超过就删最久没听的。
     *
     * 这个缓存只服务「本次使用」：同一首回跳/重播不用重新拉、下载时已过的片段可以直接复用，
     * 用完（播放服务销毁 / 退出应用）就整体释放，不在手机上长期留几百 MB。
     * 所以上限压到 256 MB——比一首无损曲的几倍还大，但不会再堆到 1.5 GB。
     */
    private const val MAX_BYTES = 256L * 1024 * 1024

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    @Volatile
    private var simple: SimpleCache? = null

    @Volatile
    private var database: StandaloneDatabaseProvider? = null

    fun get(ctx: Context): SimpleCache = simple ?: synchronized(this) {
        simple ?: run {
            val app = ctx.applicationContext
            val db = StandaloneDatabaseProvider(app).also { database = it }
            SimpleCache(
                File(app.cacheDir, DIR),
                LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                db
            ).also { simple = it }
        }
    }

    fun keyOf(url: String): String =
        CacheKeyFactory.DEFAULT.buildCacheKey(DataSpec(Uri.parse(url)))

    private fun http(): DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
        .setUserAgent(UA)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(20_000)
        .setReadTimeoutMs(30_000)

    /** 边读边写：播放与下载都用这个。 */
    fun readWriteFactory(ctx: Context): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(get(ctx))
        .setUpstreamDataSourceFactory(upstream(ctx))
        .setCacheKeyFactory(cacheKeys())
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /**
     * 上游必须能处理 content:// 与 file://（本地下载的曲目）。
     * 之前这里只给了 DefaultHttpDataSource，本地文件被当成网络地址去连，
     * 于是报 ERROR_CODE_IO_NETWORK_CONNECTION_FAILED。
     */
    fun upstream(ctx: Context): DefaultDataSource.Factory =
        DefaultDataSource.Factory(ctx.applicationContext, http())

    fun cacheKeys(): CacheKeyFactory = CacheKeyFactory.DEFAULT

    /**
     * 播放用：按 scheme 在「带缓存的 HTTP 通道」和「本地文件通道」之间二选一。
     *
     * 之前播放器只有 HTTP 上游，本地 content:// 曲目也被当成网络地址去连，
     * 于是报 ERROR_CODE_IO_NETWORK_CONNECTION_FAILED。CacheDataSource 的
     * 缓存键不能返回 null（media3 的接口不允许），所以改在 DataSource 层分流。
     */
    fun switchingFactory(ctx: Context): DataSource.Factory =
        SchemeSwitchFactory(readWriteFactory(ctx), upstream(ctx))

    fun dataSource(ctx: Context): CacheDataSource = readWriteFactory(ctx).createDataSource()
    /**
     * 只读入口：读已有缓存但不产生第二个写者。
     * 播放中的 CacheDataSource 是写者，下载若也用写入口就会和它抢同一个 Span。
     */
    fun readOnlyFactory(ctx: Context): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(get(ctx))
        .setUpstreamDataSourceFactory(upstream(ctx))
        .setCacheKeyFactory(cacheKeys())
        .setCacheWriteDataSinkFactory(null)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    fun readOnlyDataSource(ctx: Context): CacheDataSource =
        readOnlyFactory(ctx).createDataSource()


    /** 丢掉某个 URL 的缓存（播放报 Source error 时先清再用）。 */
    fun removeUrl(ctx: Context, url: String) {
        runCatching { get(ctx).removeResource(keyOf(url)) }
    }

    fun cachedBytes(ctx: Context, url: String): Long =
        runCatching { get(ctx).getCachedBytes(keyOf(url), 0, Long.MAX_VALUE) }.getOrDefault(0L)

    fun release() {
        synchronized(this) {
            runCatching { simple?.release() }
            simple = null
            database = null
        }
    }

    fun sizeBytes(ctx: Context): Long = dirSize(File(ctx.applicationContext.cacheDir, DIR))

    /**
     * 彻底清空并释放：播放服务销毁（用户退出应用）时调用。
     *
     * 与 [clear] 的区别是这里可以放心 release + 删目录——此时 ExoPlayer 已经释放，
     * 不会再有 CacheDataSource 持有这个实例（那样会 ERROR_CODE_IO_UNSPECIFIED）。
     * 下次播放会重新创建一个空缓存。
     */
    fun purge(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(this) {
            runCatching {
                simple?.keys?.toList()?.forEach { key -> runCatching { simple?.removeResource(key) } }
            }
            runCatching { simple?.release() }
            simple = null
            runCatching { database?.close() }
            database = null
        }
        runCatching { File(app.cacheDir, DIR).deleteRecursively() }
    }

    /**
     * 清空缓存内容，但**不释放** SimpleCache 实例。
     *
     * 之前这里 release() 之后还顺手删目录，而 PlaybackService 的 CacheDataSource
     * 是在启动时创建好的、一直持有这个实例；一旦被释放，之后所有网络播放都会
     * 以 ERROR_CODE_IO_UNSPECIFIED 失败。现在逐个 removeResource，实例保持可用。
     */
    fun clear(ctx: Context) {
        runCatching {
            val cache = get(ctx)
            for (key in cache.keys.toList()) {
                runCatching { cache.removeResource(key) }
            }
        }
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }
}
