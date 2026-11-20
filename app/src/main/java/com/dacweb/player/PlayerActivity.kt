package com.dacweb.player

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.dacweb.player.audio.DacPlayer
import com.dacweb.player.audio.LocalAudioInfo
import com.dacweb.player.audio.DacRoute
import com.dacweb.player.audio.PlayQueue
import com.dacweb.player.audio.PlayRepeat
import com.dacweb.player.cache.MediaCache
import com.dacweb.player.audio.PlayerHub
import com.dacweb.player.databinding.ActivityPlayerBinding
import com.dacweb.player.download.DownloadQueue
import com.dacweb.player.lyrics.Lrc
import com.dacweb.player.lyrics.LyricLine
import com.dacweb.player.lyrics.LyricsOverride
import com.dacweb.player.lyrics.LyricsRepo
import com.dacweb.player.model.Quality
import com.dacweb.player.model.StreamInfo
import com.dacweb.player.plugin.PluginLimitException
import com.dacweb.player.prefs.AppPrefs
import com.dacweb.player.prefs.LyricPalette
import com.dacweb.player.tag.TagUpdater
import com.dacweb.player.ui.EqSheet
import com.dacweb.player.ui.LyricsSearchSheet
import com.dacweb.player.ui.QueueSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerActivity : BaseActivity() {

    private lateinit var b: ActivityPlayerBinding
    private val handler = Handler(Looper.getMainLooper())
    private var dragging = false

    private val lineViews = mutableListOf<TextView>()
    private var lyrics: List<LyricLine> = emptyList()
    private var lyricsFor: String? = null
    private var lyricSig: Pair<Int, Int>? = null
    private var lyricsVisible = false
    private var lastLine = -2

    /** 本地曲目规格缓存（mediaId → "24bit · 96kHz · 1024kb/s"），避免每次 bind 重读文件头。 */
    private val localSpec = HashMap<String, String>()

    private val usbPermAction get() = "$packageName.USB_PERMISSION_PLAYER"
    private var usbPermReceiver: BroadcastReceiver? = null
    private var usbPermCallback: ((Boolean) -> Unit)? = null

    /**
     * 在播放页就地申请 USB 权限（点 DAC 面板里的「开启独占」时用）。
     *
     * 关键点：**只在用户明确点开独占时才申请**。插上 DAC 时不主动 open/claim 设备，
     * 否则一插线就弹授权，授权完还可能因为内核驱动被并发 detach 而重启。
     */
    fun requestDacPermission(onResult: (Boolean) -> Unit) {
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        val device = DacRoute.device(this)
        if (device == null) {
            onResult(false)
            return
        }
        if (usb.hasPermission(device)) {
            onResult(true)
            return
        }
        usbPermCallback = onResult
        if (usbPermReceiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != usbPermAction) return
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    releaseUsbPerm()
                    usbPermCallback?.invoke(granted)
                    usbPermCallback = null
                }
            }
            usbPermReceiver = r
            ContextCompat.registerReceiver(
                this, r, IntentFilter(usbPermAction), ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
        val pi = PendingIntent.getBroadcast(
            this, 7, Intent(usbPermAction).setPackage(packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        usb.requestPermission(device, pi)
    }

    private fun releaseUsbPerm() {
        usbPermReceiver?.let { runCatching { unregisterReceiver(it) } }
        usbPermReceiver = null
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            updateLyricHighlight()
            updateDacChip()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

        DownloadQueue.init(this)

        b.btnBack.setOnClickListener { finish() }
        b.btnLyrics.setOnClickListener { showLyrics(!lyricsVisible) }
        b.btnQueue.setOnClickListener { QueueSheet(this) { index -> playFromQueue(index) }.show() }
        b.btnEq.setOnClickListener { EqSheet(this).show() }
        b.btnLyricSearch.setOnClickListener { openLyricSearch() }

        PlayerHub.connect(this) { c ->
            c.addListener(listener)
            bind(c)
        }

        b.btnPlay.setOnClickListener {
            val c = PlayerHub.controller ?: return@setOnClickListener
            if (c.isPlaying) c.pause() else c.play()
        }
        b.btnPrev.setOnClickListener {
            val c = PlayerHub.controller ?: return@setOnClickListener
            lifecycleScope.launch {
                try {
                    if (!PlayQueue.prev(this@PlayerActivity, c)) {
                        if (c.currentPosition > 3000) c.seekTo(0) else c.seekToPreviousMediaItem()
                    }
                } catch (e: PluginLimitException) {
                    showLimitDialog { }
                }
            }
        }
        b.btnNext.setOnClickListener {
            val c = PlayerHub.controller ?: return@setOnClickListener
            lifecycleScope.launch {
                try {
                    if (!PlayQueue.next(this@PlayerActivity, c)) c.seekToNextMediaItem()
                } catch (e: PluginLimitException) {
                    showLimitDialog { }
                }
            }
        }
        b.btnRepeat.setOnClickListener {
            val c = PlayerHub.controller ?: return@setOnClickListener
            PlayRepeat.cycle()
            c.repeatMode = Player.REPEAT_MODE_OFF
            updateModes(c)
        }
        b.btnShuffle.setOnClickListener {
            val c = PlayerHub.controller ?: return@setOnClickListener
            c.shuffleModeEnabled = !c.shuffleModeEnabled
            updateModes(c)
        }
        b.btnDownload.setOnClickListener { downloadCurrent() }
        b.dacChip.isClickable = true
        b.dacChip.isFocusable = true
        b.dacChip.setOnClickListener { com.dacweb.player.ui.DacSheet.show(this) }

        b.seek.addOnChangeListener { _, value, fromUser ->
            if (fromUser) b.pos.text = fmt(ratioToMs(value))
        }
        b.seek.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                dragging = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                dragging = false
                val c = PlayerHub.controller ?: return
                c.seekTo(ratioToMs(slider.value))
                // 直通是独立解码会话，seek 后必须从新位置重开，否则声音还停在旧位置
                DacRoute.restart(this@PlayerActivity, c)
            }
        })
    }

    override fun onStart() {
        super.onStart()
        handler.post(ticker)
        PlayerHub.controller?.let { bind(it) }
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        releaseUsbPerm()
        super.onDestroy()
        PlayerHub.controller?.removeListener(listener)
    }

    /** 同一首只自动重试一次，防止死循环。 */
    private var retriedId: String? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            bind(player)
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val c = PlayerHub.controller ?: return
            val item = c.currentMediaItem ?: return
            val uri = item.localConfiguration?.uri?.toString()
            if (uri != null && item.mediaId != retriedId) {
                retriedId = item.mediaId
                if (uri.startsWith("http")) MediaCache.removeUrl(this@PlayerActivity, uri)
                b.status.text = getString(R.string.retrying)
                b.status.visibility = View.VISIBLE
                lifecycleScope.launch {
                    val ok = runCatching { PlayQueue.replayForce(this@PlayerActivity, c) }
                        .getOrDefault(false)
                    if (!ok) bind(c)
                }
            } else {
                bind(c)
            }
        }
    }

    private fun bind(c: Player) {
        val item = c.currentMediaItem
        if (item == null) {
            finish()
            return
        }
        val md = item.mediaMetadata
        b.title.text = md.title ?: getString(R.string.unknown)
        b.artist.text = md.artist?.toString().orEmpty()
        b.title.isSelected = true
        b.cover.load(md.artworkUri)

        val ex = md.extras
        val quality = ex?.getString(PlayQueue.EXTRA_QUALITY).orEmpty()
        val fmt = ex?.getString(PlayQueue.EXTRA_FORMAT).orEmpty()
        val size = ex?.getString(PlayQueue.EXTRA_SIZE).orEmpty()
        val local = quality == getString(R.string.local_short)
        // 本地文件没东西可下载，按钮留着只会误触
        b.btnDownload.visibility = if (local) View.GONE else View.VISIBLE
        if (local) {
            b.qualityLabel.text = localSpec[item.mediaId] ?: getString(R.string.local_format_reading)
            loadLocalSpec(item)
        } else {
            b.qualityLabel.text = listOf(fmt.uppercase(), size, quality)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
        }
        updateDacChip()

        b.status.text = when {
            c.playbackState == Player.STATE_BUFFERING -> getString(R.string.buffering)
            c.playerError != null -> c.playerError?.let { e ->
                val cause = e.cause?.message ?: e.message
                if (cause.isNullOrBlank()) e.errorCodeName else e.errorCodeName + " · " + cause
            } ?: ""
            else -> ""
        }
        b.status.visibility = if (b.status.text.isBlank()) View.GONE else View.VISIBLE

        b.btnPlay.setImageResource(if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        b.btnPlay.contentDescription = getString(if (c.isPlaying) R.string.pause else R.string.play)
        updateModes(c)
        updateProgress()

        val id = item.mediaId
        if (id != lyricsFor) {
            lyricsFor = id
            lyrics = emptyList()
            lineViews.forEach { b.lyricBox.removeView(it) }
            lineViews.clear()
            lastLine = -2
            b.lyricEmpty.visibility = if (lyricsVisible) View.VISIBLE else View.GONE
            loadLyrics(md.title?.toString().orEmpty(), md.artist?.toString().orEmpty())
        }
    }

    /** 本地曲目：读容器头的位深/采样率/平均码率，读完若还是同一首就刷新标签。 */
    private fun loadLocalSpec(item: MediaItem) {
        val id = item.mediaId
        if (localSpec.containsKey(id)) return
        val uri = item.localConfiguration?.uri ?: return
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                LocalAudioInfo.format(LocalAudioInfo.read(this@PlayerActivity, uri))
            }
            if (text.isBlank()) return@launch
            localSpec[id] = text
            if (PlayerHub.controller?.currentMediaItem?.mediaId == id) b.qualityLabel.text = text
        }
    }

    // ---------- 歌词 ----------

    private fun showLyrics(show: Boolean) {
        lyricsVisible = show
        b.cover.visibility = if (show) View.INVISIBLE else View.VISIBLE
        b.lyricsScroll.visibility = if (show) View.VISIBLE else View.GONE
        b.btnLyrics.text = getString(if (show) R.string.cover else R.string.lyrics)
        b.btnLyricSearch.visibility = if (show) View.VISIBLE else View.GONE
        b.lyricEmpty.visibility =
            if (show && lineViews.isEmpty()) View.VISIBLE else View.GONE
        lastLine = -2
        updateLyricHighlight()
    }

    private fun openLyricSearch() {
        val md = PlayerHub.controller?.mediaMetadata ?: return
        val title = md.title?.toString().orEmpty()
        val artist = md.artist?.toString().orEmpty()
        if (title.isBlank()) return
        val duration = PlayerHub.controller?.duration?.coerceAtLeast(0L) ?: 0L
        LyricsSearchSheet(this, title, artist, duration) { text, _ ->
            applyManualLyrics(title, artist, text)
        }.show()
    }

    /** 手动选了歌词：记住 → 刷新界面 → 如果是已下载文件就写回标签。 */
    private fun applyManualLyrics(title: String, artist: String, text: String) {
        LyricsOverride.put(this, title, artist, text)
        lyrics = Lrc.parse(text)
        lastLine = -2
        buildLyricViews()
        updateLyricHighlight()

        val uri = PlayerHub.controller?.currentMediaItem?.localConfiguration?.uri
        if (uri != null && (uri.scheme == "content" || uri.scheme == "file")) {
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    TagUpdater.updateFromUri(this@PlayerActivity, uri, text)
                }
                Toast.makeText(
                    this@PlayerActivity,
                    getString(if (ok) R.string.lyric_applied else R.string.lyric_write_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            Toast.makeText(this, R.string.lyric_applied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadLyrics(name: String, artist: String) {
        if (name.isBlank()) {
            b.lyricEmpty.visibility = if (lyricsVisible) View.VISIBLE else View.GONE
            return
        }
        val forId = lyricsFor
        // 本地文件优先用内嵌歌词（下载时写进标签的），网络查不到也不会显示"暂无歌词"
        val localUri = PlayerHub.controller?.currentMediaItem?.localConfiguration?.uri?.toString()
        lifecycleScope.launch {
            val loaded = runCatching {
                LyricsRepo.loadFor(this@PlayerActivity, name, artist, localUri)
            }.getOrDefault(emptyList())
            if (lyricsFor != forId) return@launch
            lyrics = loaded
            buildLyricViews()
        }
    }

    private fun buildLyricViews() {
        b.lyricBox.removeAllViews()
        lineViews.clear()
        if (lyrics.isEmpty()) {
            b.lyricEmpty.visibility = if (lyricsVisible) View.VISIBLE else View.GONE
            return
        }
        b.lyricEmpty.visibility = View.GONE
        lyricSig = AppPrefs.lyricSize(this) to AppPrefs.lyricColor(this)
        val padV = (6 * resources.displayMetrics.density).toInt()
        val baseSize = AppPrefs.lyricSize(this).toFloat()
        val baseColor = lyricColorOr(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        for (line in lyrics) {
            val tv = TextView(this).apply {
                text = line.text
                textSize = baseSize
                setPadding(0, padV, 0, padV)
                setTextColor(baseColor)
                alpha = 0.55f
            }
            b.lyricBox.addView(tv)
            lineViews += tv
        }
        lastLine = -2
        updateLyricHighlight()
    }

    private fun updateLyricHighlight() {
        if (!lyricsVisible || lyrics.isEmpty() || lineViews.isEmpty()) return
        val c = PlayerHub.controller ?: return
        val idx = Lrc.indexAt(lyrics, c.currentPosition.coerceAtLeast(0))
        if (idx == lastLine) return
        lastLine = idx

        val normal = lyricColorOr(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        val accent = lyricColorOr(themeColor(com.google.android.material.R.attr.colorPrimary))
        val baseSize = AppPrefs.lyricSize(this).toFloat()

        lineViews.forEachIndexed { i, tv ->
            val active = i == idx
            tv.setTextColor(if (active) accent else normal)
            tv.alpha = if (active) 1f else 0.55f
            tv.textSize = if (active) baseSize + 1.5f else baseSize
        }
        if (idx in lineViews.indices) {
            val target = lineViews[idx]
            val y = target.top - b.artArea.height / 2 + target.height / 2
            runCatching { b.lyricsScroll.smoothScrollTo(0, y.coerceAtLeast(0)) }
        }
    }

    /** 设置里改过字号/颜色后回到播放页，重建一次歌词视图。 */
    override fun onResume() {
        super.onResume()
        if (lyrics.isNotEmpty()) {
            val sig = AppPrefs.lyricSize(this) to AppPrefs.lyricColor(this)
            if (sig != lyricSig) buildLyricViews()
        }
    }

    private fun lyricColorOr(fallback: Int): Int {
        val idx = AppPrefs.lyricColor(this)
        return if (idx == 0) fallback else LyricPalette.color(idx)
    }

    private fun playFromQueue(index: Int) {
        val c = PlayerHub.controller ?: return
        lifecycleScope.launch {
            try {
                PlayQueue.playAt(this@PlayerActivity, c, index)
            } catch (e: PluginLimitException) {
                showLimitDialog { }
            } catch (e: Exception) {
                Toast.makeText(this@PlayerActivity, e.message ?: "", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateModes(c: Player) {
        val repeat = PlayRepeat.mode
        b.btnRepeat.setImageResource(
            if (repeat == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat
        )
        b.btnRepeat.contentDescription = when (repeat) {
            Player.REPEAT_MODE_ONE -> getString(R.string.repeat_one)
            Player.REPEAT_MODE_ALL -> getString(R.string.repeat_all)
            else -> getString(R.string.repeat_off)
        }
        b.btnRepeat.alpha = if (repeat == Player.REPEAT_MODE_OFF) 0.55f else 1f
        b.btnShuffle.alpha = if (c.shuffleModeEnabled) 1f else 0.55f
        b.btnShuffle.contentDescription =
            getString(if (c.shuffleModeEnabled) R.string.shuffle_on else R.string.shuffle_off)
    }

    private fun updateProgress() {
        val c = PlayerHub.controller ?: return
        val dur = c.duration.coerceAtLeast(0)
        val pos = c.currentPosition.coerceAtLeast(0)
        b.dur.text = fmt(dur)
        if (!dragging && dur > 0) {
            b.pos.text = fmt(pos)
            // Slider 对非法 value 会直接抛异常，这里再兜一层
            runCatching {
                b.seek.value = (pos.toFloat() * 1000f / dur).coerceIn(0f, 1000f)
                    .let { Math.round(it).toFloat() }
            }
        }
    }

    /** 播放页右上角的 DAC 状态框：开独占且插着 DAC 才显示。 */
    private fun updateDacChip() {
        // 插着 DAC 就显示（哪怕没开独占），没插才藏起来
        if (DacRoute.device(this) == null) {
            b.dacChip.visibility = View.GONE
            return
        }
        // 顶栏只放一个短标签，串太长会和"音质/大小"框互相挤压；细节点开面板看
        b.dacChip.text = getString(R.string.dac_chip)
        b.dacChip.visibility = View.VISIBLE
        val exclusive = AppPrefs.dacExclusive(this)
        val err = if (exclusive) DacRoute.failureText() else null
        // 开了独占但还没授权：蓝色（可用色）提示"点我一下就好"，不要用红色吓人
        val needPerm = exclusive && !DacRoute.hasPermission(this)
        val tint = when {
            err != null -> com.google.android.material.R.attr.colorError
            needPerm -> com.google.android.material.R.attr.colorPrimary
            exclusive && DacRoute.active() -> com.google.android.material.R.attr.colorPrimary
            else -> com.google.android.material.R.attr.colorOnSurfaceVariant
        }
        b.dacChip.setTextColor(themeColor(tint))
    }

    /**
     * 直通播放时音量键调的是 native 软件增益（ExoPlayer 已静音，系统音量对 USB 直通无效）。
     * 上 = 更响 = 衰减少 3 dB，下 = 更小声 = 衰减多 3 dB。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (DacRoute.enabled(this) && DacPlayer.started) {
            val up = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
            val down = event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
            if (up || down) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val p = (AppPrefs.dacVolumePercent(this) + if (up) 5 else -5).coerceIn(0, 100)
                    DacPlayer.applyVolumePercent(this, p)
                    Toast.makeText(this, getString(R.string.dac_volume_keys, p), Toast.LENGTH_SHORT).show()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun ratioToMs(value: Float): Long {
        val dur = PlayerHub.controller?.duration?.coerceAtLeast(0) ?: 0
        return (dur * value / 1000f).toLong()
    }

    private fun fmt(ms: Long): String {
        if (ms <= 0) return "0:00"
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    private fun downloadCurrent() {
        val c = PlayerHub.controller ?: return
        val item = c.currentMediaItem ?: return
        val track = PlayQueue.current
        if (track != null) {
            val live = item.localConfiguration?.uri?.toString()?.takeIf { it.startsWith("http") }
            // 直链能复用就连同播放页的详情封面、真实格式/大小一起复用，避免下载器选错扩展名、封面缩水
            val md0 = item.mediaMetadata
            val ex0 = md0.extras
            DownloadQueue.enqueue(
                track,
                Quality.current(this),
                live,
                md0.artworkUri?.toString(),
                ex0?.getString(PlayQueue.EXTRA_FORMAT),
                ex0?.getString(PlayQueue.EXTRA_SIZE)
            )
            Toast.makeText(this, getString(R.string.download_enqueued, track.name), Toast.LENGTH_SHORT).show()
            return
        }
        // 兜底：队列丢了就用当前播放项的信息重新取一次直链
        val url = item.localConfiguration?.uri?.toString().orEmpty()
        if (url.isBlank()) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val md = item.mediaMetadata
        val ex = md.extras
        val quality = Quality.current(this)
        lifecycleScope.launch {
            try {
                val stream = StreamInfo(
                    url = url,
                    name = md.title?.toString().orEmpty(),
                    artist = md.artist?.toString().orEmpty(),
                    album = md.albumTitle?.toString().orEmpty(),
                    cover = md.artworkUri?.toString().orEmpty(),
                    quality = ex?.getString(PlayQueue.EXTRA_QUALITY) ?: quality.label,
                    format = ex?.getString(PlayQueue.EXTRA_FORMAT).orEmpty(),
                    size = ex?.getString(PlayQueue.EXTRA_SIZE).orEmpty()
                )
                val meta = com.dacweb.player.download.MetaEnricher.build(
                    this@PlayerActivity, stream, quality, ex?.getString(PlayQueue.EXTRA_ID) ?: item.mediaId
                )
                com.dacweb.player.download.Downloader.download(this@PlayerActivity, url, meta) { }
                Toast.makeText(this@PlayerActivity, R.string.download_done, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@PlayerActivity,
                    getString(R.string.download_failed, e.message ?: ""),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showLimitDialog(onRetry: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.limit_title)
            .setMessage(R.string.limit_message)
            .setPositiveButton(R.string.open_warp) { _, _ ->
                packageManager.getLaunchIntentForPackage("com.cloudflare.cloudflarewarp")
                    ?.let { startActivity(it) }
            }
            .setNeutralButton(R.string.retry) { _, _ -> onRetry() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
