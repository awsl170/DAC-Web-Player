package com.dacweb.player.download

import android.content.Context
import android.net.Uri
import com.dacweb.player.model.Quality
import com.dacweb.player.model.StreamInfo
import com.dacweb.player.model.Track
import com.dacweb.player.plugin.PluginLimitException
import com.dacweb.player.prefs.AppPrefs
import com.dacweb.player.plugin.Providers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * 并发下载队列。
 * - 多个任务同时下载（并发数由设置决定）；
 * - 「取直链」全局串行，因为每次取直链都消耗 1 次 24bit 每日额度；
 * - 取到直链后先用网易云补全标签，再下载并写标签、落盘。
 */
object DownloadQueue {

    enum class Status { WAITING, LINKING, DOWNLOADING, SAVING, DONE, FAILED, CANCELLED }

    data class Snapshot(
        val key: String,
        val title: String,
        val artist: String,
        val quality: String,
        val status: Status,
        val progress: Int,
        val error: String,
        val limited: Boolean,
        val uri: Uri?
    ) {
        val running: Boolean
            get() = status == Status.WAITING || status == Status.LINKING ||
                    status == Status.DOWNLOADING || status == Status.SAVING
    }

    private data class Task(
        val key: String,
        val track: Track,
        val quality: Quality,
        val status: Status = Status.WAITING,
        val progress: Int = 0,
        val error: String = "",
        val limited: Boolean = false,
        val uri: Uri? = null,
        val title: String = track.name,
        val artist: String = track.artist,
        val preUrl: String? = null,
        /** 复用直链时连带复用播放页那张封面（详情页 126 CDN），别退回搜索结果的小图 */
        val preCover: String? = null,
        val preFormat: String? = null,
        val preSize: String? = null
    ) {
        val running: Boolean
            get() = status == Status.WAITING || status == Status.LINKING ||
                    status == Status.DOWNLOADING || status == Status.SAVING
    }

    private var appCtx: Context? = null
    private var scope: CoroutineScope? = null
    private var channel: Channel<Pair<String, Task>>? = null
    private var workers = 0

    private val lock = Any()
    private val tasks = LinkedHashMap<String, Task>()
    private val _state = MutableStateFlow<List<Snapshot>>(emptyList())
    val state: StateFlow<List<Snapshot>> = _state.asStateFlow()

    /** 取直链串行锁：避免并发请求把额度瞬间打光。 */
    private val linkMutex = Mutex()

    @Volatile
    var onLimit: (() -> Unit)? = null

    fun init(ctx: Context) {
        if (scope != null) return
        appCtx = ctx.applicationContext
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        channel = Channel(Channel.UNLIMITED)
        ensureWorkers()
    }

    private fun ensureWorkers() {
        val want = appCtx?.let { AppPrefs.concurrency(it) } ?: 3
        val sc = scope ?: return
        val ch = channel ?: return
        if (workers >= want) return
        repeat(want - workers) {
            workers++
            sc.launch {
                for ((key, task) in ch) {
                    if (current(key)?.status == Status.CANCELLED) continue
                    runTask(key, task)
                }
            }
        }
    }

    /**
     * @param preUrl 已经拿到手的直链（比如这首正在播放）。给了就不再消耗一次取直链额度，
     * @param preCover/preFormat/preSize 复用直链时，顺带复用播放页那张详情封面和真实格式，
     */
    fun enqueue(
        track: Track,
        quality: Quality,
        preUrl: String? = null,
        preCover: String? = null,
        preFormat: String? = null,
        preSize: String? = null
    ): String {
        val key = UUID.randomUUID().toString().take(8)
        val task = Task(
            key = key,
            track = track,
            quality = quality,
            preUrl = preUrl?.takeIf { it.isNotBlank() },
            preCover = preCover?.takeIf { it.isNotBlank() },
            preFormat = preFormat?.takeIf { it.isNotBlank() },
            preSize = preSize?.takeIf { it.isNotBlank() }
        )
        synchronized(lock) { tasks[key] = task }
        ensureWorkers()
        channel?.trySend(key to task)
        publish()
        return key
    }

    fun cancel(key: String) {
        synchronized(lock) {
            val t = tasks[key] ?: return
            if (t.status == Status.DONE || t.status == Status.FAILED) return
            tasks[key] = t.copy(status = Status.CANCELLED, error = "已取消")
        }
        publish()
    }

    fun retry(key: String) {
        val task = synchronized(lock) {
            val t = tasks[key] ?: return
            if (t.status != Status.FAILED && t.status != Status.CANCELLED) return
            val fresh = t.copy(status = Status.WAITING, progress = 0, error = "", limited = false, uri = null)
            tasks[key] = fresh
            fresh
        }
        publish()
        channel?.trySend(key to task)
    }

    fun clearFinished() {
        synchronized(lock) {
            val keep = tasks.filterValues { it.running }
            tasks.clear()
            tasks.putAll(keep)
        }
        publish()
    }

    fun activeCount(): Int = synchronized(lock) { tasks.values.count { it.running } }

    private fun current(key: String): Task? = synchronized(lock) { tasks[key] }

    private fun update(key: String, block: (Task) -> Task) {
        synchronized(lock) {
            val t = tasks[key] ?: return
            tasks[key] = block(t)
        }
        publish()
    }

    private fun publish() {
        val snapshot = synchronized(lock) {
            tasks.values.map {
                Snapshot(it.key, it.title, it.artist, it.quality.short, it.status, it.progress, it.error, it.limited, it.uri)
            }
        }
        _state.value = snapshot
    }

    private suspend fun runTask(key: String, task: Task) {
        val ctx = appCtx ?: return
        try {
            update(key) { it.copy(status = Status.LINKING, progress = 0, error = "") }
            val stream = task.preUrl?.let { url ->
                StreamInfo(
                    url = url,
                    name = task.track.name,
                    artist = task.track.artist,
                    album = task.track.album,
                    // 优先用播放页那张详情封面；没有才退回列表封面
                    cover = task.preCover ?: task.track.cover,
                    quality = task.quality.label,
                    format = task.preFormat ?: task.quality.format,
                    size = task.preSize ?: ""
                )
            } ?: linkMutex.withLock { Providers.resolve(ctx, task.track, task.quality) }
            val meta = MetaEnricher.build(ctx, stream, task.quality, task.track.id)
            update(key) { it.copy(title = meta.title, artist = meta.artist) }

            update(key) { it.copy(status = Status.DOWNLOADING) }
            var lastEmit = 0L
            val uri = Downloader.download(ctx, stream.url, meta) { pct ->
                val now = System.currentTimeMillis()
                if (pct >= 100 || now - lastEmit > 250) {
                    lastEmit = now
                    update(key) { it.copy(status = Status.DOWNLOADING, progress = pct) }
                }
            }
            update(key) { it.copy(status = Status.DONE, progress = 100, uri = uri) }
        } catch (e: Exception) {
            val limited = e is PluginLimitException
            update(key) {
                it.copy(
                    status = Status.FAILED,
                    error = when {
                        limited -> "今日额度已用完，重启 WARP 后可继续"
                        else -> e.message ?: e.javaClass.simpleName
                    },
                    limited = limited
                )
            }
            if (limited) onLimit?.invoke()
        }
    }
}
