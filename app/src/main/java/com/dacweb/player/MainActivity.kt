package com.dacweb.player

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import com.dacweb.player.plugin.Providers
import androidx.media3.session.MediaController
import android.widget.PopupMenu
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.dacweb.player.audio.PlayQueue
import com.dacweb.player.audio.PlayerHub
import com.dacweb.player.databinding.ActivityMainBinding
import com.dacweb.player.download.DownloadQueue
import com.dacweb.player.local.LocalLibrary
import com.dacweb.player.model.Quality
import com.dacweb.player.model.Track
import com.dacweb.player.plugin.PluginLimitException
import com.dacweb.player.prefs.AppPrefs
import com.dacweb.player.store.Playlist
import com.dacweb.player.store.PlaylistStore
import com.dacweb.player.ui.PlaylistAdapter
import com.dacweb.player.ui.TrackAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class MainActivity : BaseActivity() {

    companion object {
        /** 每页条数（酷我可以指定，24bit 固定 30）。 */
        private const val PAGE_SIZE = 30
    }


    private lateinit var b: ActivityMainBinding
    private lateinit var adapter: TrackAdapter
    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var localAdapter: TrackAdapter

    /** 本地页当前的过滤关键词（空串表示不过滤）。 */
    private var localQuery = ""
    private var localJob: Job? = null
    private var page = 0
    private var endReached = false
    private var loadingMore = false
    private var query = ""
    private var searching = false
    private var queueJob: Job? = null
    private var coverJob: Job? = null
    private var pendingBatch: List<Track>? = null

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val pickTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri == null) {
                pendingBatch = null
                return@registerForActivityResult
            }
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            AppPrefs.setDownloadTree(this, uri, uri.lastPathSegment ?: "已选择目录")
            AppPrefs.setDirAsked(this, true)
            toast(getString(R.string.download_dir_changed, AppPrefs.downloadDirLabel(this)))
            pendingBatch?.let { list ->
                pendingBatch = null
                startDownloadAll(list)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        DownloadQueue.init(this)
        DownloadQueue.onLimit = { runOnUiThread { showLimitDialog { retryLimited() } } }

        adapter = TrackAdapter(::onRowClick, ::onRowLongClick)
        b.results.layoutManager = LinearLayoutManager(this)
        b.results.adapter = adapter
        b.results.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 3) loadMore()
            }
        })

        playlistAdapter = PlaylistAdapter(::openPlaylist, ::onPlaylistMore)
        b.playlists.layoutManager = LinearLayoutManager(this)
        b.playlists.adapter = playlistAdapter

        localAdapter = TrackAdapter(::onLocalClick, ::onLocalLongClick)
        b.locals.layoutManager = LinearLayoutManager(this)
        b.locals.adapter = localAdapter
        b.localSearchInput.doAfterTextChanged { t ->
            localQuery = t?.toString()?.trim().orEmpty()
            b.clearLocalSearch.visibility = if (localQuery.isEmpty()) View.GONE else View.VISIBLE
            renderLocal()
        }
        b.clearLocalSearch.setOnClickListener { b.localSearchInput.setText("") }
        b.localSearchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                currentFocus?.clearFocus()
                true
            } else false
        }

        b.newPlaylistBtn.setOnClickListener { newPlaylistDialog() }
        b.refreshLocal.setOnClickListener { refreshLocal(true) }
        // 电视遥控器：搜索结果里按「返回」先把焦点交还底部三个 tab（否则一路按下去就退出了应用），
        // 焦点已经在底部 tab 上时再按返回才真的退出。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (b.pageSearch.visibility == View.VISIBLE && !isInBottomNav(currentFocus)) {
                    focusBottomTab()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        b.tabSearch.setOnClickListener { showPage(0) }
        b.tabPlaylist.setOnClickListener { showPage(1); refreshPlaylists() }
        b.tabLocal.setOnClickListener { showPage(2); refreshLocal(false) }
        showPage(0)
        refreshPlaylists()

        b.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else false
        }
        b.searchInput.doAfterTextChanged { text ->
            b.clearSearch.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        b.clearSearch.setOnClickListener { b.searchInput.setText("") }

        b.netBtn.setOnClickListener {
            com.dacweb.player.net.ServerPickDialog.show(
                this,
                onPick = { host -> connectDiscovered(host) },
                onLongPick = { host ->
                    // 长按：即使连过也重新问一次账号密码
                    com.dacweb.player.net.ServerDialog.show(this, presetHost = host, presetType = "smb") { server ->
                        openNetBrowser(server)
                    }
                },
                onManual = {
                com.dacweb.player.net.ServerDialog.show(this) { server -> openNetBrowser(server) }
            })
        }
        b.netBtn.setOnLongClickListener {
            com.dacweb.player.net.ServerDialog.show(this) { server -> openNetBrowser(server) }
            true
        }
        b.qualityBtn.setOnClickListener { showQualityDialog() }
        b.settingsBtn.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.queueBtn.setOnClickListener { showQueueDialog() }
        b.miniBar.setOnClickListener { startActivity(Intent(this, PlayerActivity::class.java)) }
        b.miniPlay.setOnClickListener {
            val c = PlayerHub.controller ?: return@setOnClickListener
            if (c.isPlaying) c.pause() else c.play()
        }
        b.miniBar.setOnLongClickListener {
            startActivity(Intent(this, PlayerActivity::class.java)); true
        }

        b.selectAllBtn.setOnClickListener {
            adapter.selectAll()
            updateSelectionUi()
        }
        b.downloadSelBtn.setOnClickListener { downloadSelected() }
        b.exitSelectBtn.setOnClickListener { exitSelection() }

        updateQualityLabel()

        lifecycleScope.launch {
            DownloadQueue.state.collect { list ->
                b.queueBtn.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        checkLastCrash()
    }

    /** 上次进程崩溃的话，把堆栈摆出来，方便直接复制反馈。 */
    private fun checkLastCrash() {
        val crash = App.lastCrash(this) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.crash_title)
            .setMessage(crash.take(6000))
            .setPositiveButton(R.string.crash_copy) { _, _ ->
                androidx.core.content.ContextCompat.getSystemService(this, ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("crash", crash))
                toast(getString(R.string.crash_copied))
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        PlayerHub.connect(this) { c ->
            c.addListener(listener)
            updateMini()
        }
    }

    override fun onStop() {
        super.onStop()
        PlayerHub.controller?.removeListener(listener)
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updateMini()
        }
    }


    // ---------------- 页面切换 ----------------

    private fun showPage(index: Int) {
        b.pageSearch.visibility = if (index == 0) View.VISIBLE else View.GONE
        b.pagePlaylist.visibility = if (index == 1) View.VISIBLE else View.GONE
        b.pageLocal.visibility = if (index == 2) View.VISIBLE else View.GONE
        tintTab(b.tabSearchIcon, b.tabSearchLabel, index == 0)
        tintTab(b.tabPlaylistIcon, b.tabPlaylistLabel, index == 1)
        tintTab(b.tabLocalIcon, b.tabLocalLabel, index == 2)
    }

    /** 当前控件是否在底部三个 tab 里面 */
    private fun isInBottomNav(v: View?): Boolean {
        var p: View? = v
        while (p != null) {
            if (p.id == R.id.bottomNav) return true
            p = p.parent as? View
        }
        return false
    }

    /** 把焦点交回当前页面对应的底部 tab */
    private fun focusBottomTab() {
        val v: View = when {
            b.pageLocal.visibility == View.VISIBLE -> b.tabLocal
            b.pagePlaylist.visibility == View.VISIBLE -> b.tabPlaylist
            else -> b.tabSearch
        }
        if (v.isInTouchMode) v.requestFocusFromTouch() else v.requestFocus()
    }

    private fun tintTab(icon: android.widget.ImageView, label: android.widget.TextView, active: Boolean) {
        val color = if (active) themeColor(com.google.android.material.R.attr.colorPrimary)
        else themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        icon.setColorFilter(color)
        label.setTextColor(color)
        label.alpha = if (active) 1f else 0.85f
    }

    // ---------------- 歌单 ----------------

    private fun refreshPlaylists() {
        val list = PlaylistStore.list(this)
        playlistAdapter.submit(list)
        b.playlistEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openPlaylist(p: Playlist) {
        startActivity(
            Intent(this, PlaylistDetailActivity::class.java)
                .putExtra(PlaylistDetailActivity.EXTRA_ID, p.id)
        )
    }

    private fun onPlaylistMore(p: Playlist, anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 0, 0, getString(R.string.play_all))
        menu.menu.add(0, 1, 1, getString(R.string.rename_playlist))
        menu.menu.add(0, 2, 2, getString(R.string.delete_playlist))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> openPlaylist(p)
                1 -> renamePlaylistDialog(p)
                else -> MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.delete_playlist)
                    .setMessage(getString(R.string.delete_playlist_confirm, p.name))
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        PlaylistStore.delete(this, p.id)
                        refreshPlaylists()
                        snack(getString(R.string.playlist_deleted))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            true
        }
        menu.show()
    }

    private fun newPlaylistDialog() {
        val input = EditText(this).apply { hint = getString(R.string.new_playlist_hint) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.playlist_new)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                    .ifBlank { getString(R.string.playlist_default_name) }
                PlaylistStore.create(this, name)
                refreshPlaylists()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun renamePlaylistDialog(p: Playlist) {
        val input = EditText(this).apply {
            setText(p.name)
            hint = getString(R.string.new_playlist_hint)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_playlist)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                PlaylistStore.rename(this, p.id, input.text.toString())
                refreshPlaylists()
                snack(getString(R.string.playlist_renamed))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 把曲目加进歌单：已有歌单列表 + 新建。 */
    private fun addToPlaylistDialog(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val lists = PlaylistStore.list(this)
        val names = lists.map { it.name }.toMutableList()
        names.add(getString(R.string.playlist_new))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_playlist)
            .setItems(names.toTypedArray()) { d, which ->
                d.dismiss()
                if (which == lists.size) {
                    newPlaylistThenAdd(tracks)
                } else {
                    val p = lists[which]
                    lifecycleScope.launch {
                        val n = PlaylistStore.addWithCovers(this@MainActivity, p.id, tracks)
                        snack(getString(R.string.added_to_playlist, p.name, n))
                        refreshPlaylists()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun newPlaylistThenAdd(tracks: List<Track>) {
        val input = EditText(this).apply { hint = getString(R.string.new_playlist_hint) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.playlist_new)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                    .ifBlank { getString(R.string.playlist_default_name) }
                val p = PlaylistStore.create(this, name)
                lifecycleScope.launch {
                    val n = PlaylistStore.addWithCovers(this@MainActivity, p.id, tracks)
                    snack(getString(R.string.added_to_playlist, p.name, n))
                    refreshPlaylists()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------- 本地音乐 ----------------

    private fun refreshLocal(force: Boolean) {
        if (!force && LocalLibrary.cached().isNotEmpty()) {
            renderLocal()
            return
        }
        localJob?.cancel()
        localJob = lifecycleScope.launch {
            val list = LocalLibrary.scan(this@MainActivity)
            b.localInfo.text = getString(R.string.local_count, list.size)
            renderLocal()
        }
    }

    private fun renderLocal() {
        val all = LocalLibrary.cachedTracks()
        val tracks = filterLocal(all, localQuery)
        localAdapter.submit(tracks)
        b.localInfo.text = if (localQuery.isEmpty()) {
            getString(R.string.local_count, tracks.size)
        } else {
            getString(R.string.local_count, tracks.size) + " / " + getString(R.string.local_count, all.size)
        }
        b.localEmpty.text = getString(if (localQuery.isEmpty()) R.string.local_empty else R.string.local_no_match)
        b.localEmpty.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 本地过滤：曲名/歌手/专辑任一包含关键词即命中（不区分大小写）。 */
    private fun filterLocal(list: List<Track>, q: String): List<Track> {
        if (q.isBlank()) return list
        val k = q.lowercase()
        return list.filter {
            it.name.lowercase().contains(k) ||
                it.artist.lowercase().contains(k) ||
                it.album.lowercase().contains(k)
        }
    }

    private fun confirmDeleteLocal(track: Track) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.delete_local)
            .setMessage(getString(R.string.delete_local_confirm, track.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete_local) { _, _ ->
                lifecycleScope.launch {
                    val ok = LocalLibrary.delete(this@MainActivity, track)
                    renderLocal()
                    refreshLocal(true)
                    snack(if (ok) getString(R.string.local_deleted) else getString(R.string.delete_failed))
                }
            }
            .show()
    }

    private fun onLocalClick(position: Int, track: Track) {
        val list = localAdapter.snapshot()
        val idx = list.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        PlayQueue.set(list, idx)
        playCurrent()
    }

    private fun onLocalLongClick(position: Int, track: Track) {
        val actions = arrayOf(
            getString(R.string.play_now),
            getString(R.string.add_next),
            getString(R.string.add_to_playlist),
            getString(R.string.delete_local)
        )
        val sheet = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        container.addView(TextView(this).apply {
            text = track.name
            textSize = 16f
            setPadding(dp(24), dp(12), dp(24), dp(8))
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
        })
        actions.forEachIndexed { i, label ->
            container.addView(TextView(this).apply {
                text = label
                textSize = 16f
                setPadding(dp(24), dp(14), dp(24), dp(14))
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
                setOnClickListener {
                    sheet.dismiss()
                    when (i) {
                        0 -> onLocalClick(position, track)
                        1 -> {
                            PlayQueue.insertNext(track)
                            toast(getString(R.string.added_next, track.name))
                        }
                        2 -> addToPlaylistDialog(listOf(track))
                        else -> confirmDeleteLocal(track)
                    }
                }
            })
        }
        sheet.setContentView(container)
        sheet.show()
    }

    /**
     * 自动发现列表里选中一台服务器：这台以前连成功过就直接登录（电视上用遥控器敲账号密码太痛苦），
     * 没连过、或者这次登录报错，才弹出账号密码框。
     */
    private fun connectDiscovered(host: String) {
        val known = com.dacweb.player.net.NetStore.knownFor(this, host)
        if (known == null) {
            com.dacweb.player.net.ServerDialog.show(this, presetHost = host, presetType = "smb") { server ->
                openNetBrowser(server)
            }
            return
        }
        Toast.makeText(this, getString(R.string.net_auto_connecting, known.label), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    com.dacweb.player.net.NetStore.connect(this@MainActivity, known).list(known.startPath)
                }.isSuccess
            }
            if (ok) {
                openNetBrowser(known)
            } else {
                Toast.makeText(this@MainActivity, R.string.net_auto_failed, Toast.LENGTH_SHORT).show()
                com.dacweb.player.net.ServerDialog.show(
                    this@MainActivity, presetHost = host, presetType = known.type
                ) { server -> openNetBrowser(server) }
            }
        }
    }

    private fun openNetBrowser(server: com.dacweb.player.net.NetServer) {
        startActivity(
            android.content.Intent(this, com.dacweb.player.net.NetBrowserActivity::class.java)
                .putExtra(com.dacweb.player.net.NetBrowserActivity.EXTRA_PATH, server.startPath)
        )
    }

    override fun onResume() {
        super.onResume()
        refreshPlaylists()
        // 电视上没有触摸，进来必须先把焦点落在某个可操作控件上，方向键才有反应
        if (currentFocus == null) {
            val v: android.view.View = b.tabSearch
            // 触摸模式下 requestFocus() 会被忽略，用 requestFocusFromTouch 才能让外接遥控/键盘接管
            if (v.isInTouchMode) v.requestFocusFromTouch() else v.requestFocus()
        }
    }

    private fun updateQualityLabel() {
        b.qualityBtn.text = getString(R.string.quality_prefix, Quality.current(this).short)
    }

    private fun showQualityDialog() {
        val entries = Quality.entries
        val cur = Quality.current(this)
        val labels = entries.map { it.label }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.quality_title)
            .setSingleChoiceItems(labels, entries.indexOf(cur)) { d, which ->
                Quality.set(this, entries[which])
                updateQualityLabel()
                adapter.submit(emptyList())
                endReached = false
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------- 列表交互 ----------------

    private fun onRowClick(position: Int, track: Track) {
        if (adapter.selectionMode) {
            adapter.toggleSelection(track.id)
            updateSelectionUi()
            return
        }
        val list = adapter.snapshot()
        val idx = list.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        PlayQueue.set(list, idx)
        playCurrent()
    }

    private fun onRowLongClick(position: Int, track: Track) {
        val actions = arrayOf(
            getString(R.string.play_now),
            getString(R.string.add_next),
            getString(R.string.add_to_playlist),
            getString(R.string.download_this),
            getString(R.string.multi_select)
        )
        val sheet = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val header = TextView(this).apply {
            text = track.name
            textSize = 16f
            setPadding(dp(24), dp(12), dp(24), dp(8))
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
        }
        container.addView(header)
        actions.forEachIndexed { index, label ->
            val row = TextView(this).apply {
                text = label
                textSize = 16f
                setPadding(dp(24), dp(14), dp(24), dp(14))
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
                setBackgroundColor(0)
            }
            row.setOnClickListener {
                sheet.dismiss()
                when (index) {
                    0 -> {
                        val list = adapter.snapshot()
                        val idx = list.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
                        PlayQueue.set(list, idx)
                        playCurrent()
                    }
                    1 -> {
                        PlayQueue.insertNext(track)
                        toast(getString(R.string.added_next, track.name))
                    }
                    2 -> addToPlaylistDialog(listOf(track))
                    3 -> enqueueWithDirAsk(listOf(track))
                    else -> enterSelection(track.id)
                }
            }
            container.addView(row)
        }
        sheet.setContentView(container)
        sheet.show()
    }

    private fun enterSelection(preselect: String? = null) {
        adapter.enterSelection()
        if (preselect != null) adapter.toggleSelection(preselect)
        b.normalBar.visibility = View.GONE
        b.selectionBar.visibility = View.VISIBLE
        updateSelectionUi()
    }

    private fun exitSelection() {
        adapter.exitSelection()
        b.selectionBar.visibility = View.GONE
        b.normalBar.visibility = View.VISIBLE
    }

    private fun updateSelectionUi() {
        b.selectionInfo.text = getString(R.string.selected_n, adapter.selectedCount)
    }

    private fun downloadSelected() {
        val list = adapter.selectedTracks()
        if (list.isEmpty()) {
            toast(getString(R.string.nothing_selected))
            return
        }
        exitSelection()
        enqueueWithDirAsk(list)
    }

    // ---------------- 下载 ----------------

    /** 第一次下载先问一次保存位置；之后按设置直接入队。 */
    private fun enqueueWithDirAsk(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        if (!AppPrefs.dirAsked(this)) {
            pendingBatch = tracks
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dir_ask_title)
                .setMessage(R.string.dir_ask_message)
                .setPositiveButton(R.string.dir_ask_pick) { _, _ -> pickTree.launch(null) }
                .setNegativeButton(R.string.dir_ask_default) { _, _ ->
                    AppPrefs.setDirAsked(this, true)
                    val list = pendingBatch ?: tracks
                    pendingBatch = null
                    startDownloadAll(list)
                }
                .setNeutralButton(android.R.string.cancel, null)
                .setOnCancelListener { pendingBatch = null }
                .show()
            return
        }
        startDownloadAll(tracks)
    }

    private fun startDownloadAll(tracks: List<Track>) {
        val quality = Quality.current(this)
        val playing = PlayerHub.controller?.currentMediaItem
        val playingUrl = playing?.localConfiguration?.uri?.toString()
        val playingId = playing?.mediaId
        val pMd = playing?.mediaMetadata
        val pEx = pMd?.extras
        tracks.forEach { t ->
            val reuse = playingUrl?.takeIf { it.startsWith("http") && playingId == t.id }
            if (reuse != null) {
                // 正在放的那首：连封面/格式/大小一起复用，少一次取直链
                DownloadQueue.enqueue(
                    t, quality, reuse,
                    pMd?.artworkUri?.toString(),
                    pEx?.getString(PlayQueue.EXTRA_FORMAT),
                    pEx?.getString(PlayQueue.EXTRA_SIZE)
                )
            } else {
                DownloadQueue.enqueue(t, quality)
            }
        }
        val msg = if (tracks.size == 1) {
            getString(R.string.download_enqueued, tracks.first().name)
        } else {
            getString(R.string.download_enqueued_many, tracks.size)
        }
        toast(msg)
        b.queueBtn.visibility = View.VISIBLE
    }

    // ---------------- 下载队列 ----------------

    private fun showQueueDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val scroll = ScrollView(this).apply { addView(container) }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.queue_title)
            .setView(scroll)
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.queue_change_dir) { _, _ -> pickTree.launch(null) }
            .setNegativeButton(R.string.clear_finished) { _, _ -> DownloadQueue.clearFinished() }
            .create()
        dialog.show()
        queueJob?.cancel()
        queueJob = lifecycleScope.launch {
            DownloadQueue.state.collect { list -> renderQueue(container, list) }
        }
        dialog.setOnDismissListener { queueJob?.cancel() }
    }

    private fun renderQueue(container: LinearLayout, list: List<DownloadQueue.Snapshot>) {
        container.removeAllViews()
        if (list.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.queue_empty)
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        for (t in list) {
            val row = TextView(this).apply {
                text = buildString {
                    append(t.title).append(" — ").append(t.artist).append('\n')
                    append(t.quality).append(" · ").append(statusText(t))
                }
                setPadding(0, dp(10), 0, dp(10))
                setTextColor(
                    if (t.status == DownloadQueue.Status.FAILED)
                        themeColor(com.google.android.material.R.attr.colorError)
                    else themeColor(com.google.android.material.R.attr.colorOnSurface)
                )
            }
            row.setOnClickListener {
                if (t.status == DownloadQueue.Status.FAILED) DownloadQueue.retry(t.key)
                else if (t.running) DownloadQueue.cancel(t.key)
            }
            container.addView(row)
        }
    }

    private fun statusText(t: DownloadQueue.Snapshot): String = when (t.status) {
        DownloadQueue.Status.WAITING -> getString(R.string.st_waiting)
        DownloadQueue.Status.LINKING -> getString(R.string.st_linking)
        DownloadQueue.Status.DOWNLOADING -> getString(R.string.st_downloading, t.progress)
        DownloadQueue.Status.SAVING -> getString(R.string.st_saving)
        DownloadQueue.Status.DONE -> getString(R.string.st_done)
        DownloadQueue.Status.FAILED -> getString(R.string.st_failed, t.error)
        DownloadQueue.Status.CANCELLED -> getString(R.string.st_cancelled)
    }

    private fun retryLimited() {
        DownloadQueue.state.value
            .filter { it.limited && it.status == DownloadQueue.Status.FAILED }
            .forEach { DownloadQueue.retry(it.key) }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun updateMini() {
        val c = PlayerHub.controller ?: return
        val item = c.currentMediaItem
        if (item == null) {
            b.miniBar.visibility = View.GONE
            return
        }
        b.miniBar.visibility = View.VISIBLE
        val md = item.mediaMetadata
        b.miniTitle.text = md.title ?: getString(R.string.unknown)
        val q = md.extras?.getString(PlayQueue.EXTRA_QUALITY)
        val fmt = md.extras?.getString(PlayQueue.EXTRA_FORMAT)
        val extra = listOfNotNull(q, fmt).filter { it.isNotBlank() }.joinToString(" · ")
        b.miniArtist.text = listOf(md.artist?.toString().orEmpty(), extra)
            .filter { it.isNotBlank() }.joinToString(" · ")
        b.miniCover.load(md.artworkUri)
        b.miniPlay.setImageResource(if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        applyNetTagsMini(item)
    }

    /**
     * 搜索框里输入的是 http(s) 音频直链时直接播放（不消耗音源额度）。
     * 后缀从 URL 的路径部分取（忽略 ?query 和 #fragment）。
     */
    /** 网络文件（dacnet-*）切歌后补标签：列表只有文件名，读到头再显示。 */
    private fun applyNetTagsMini(item: androidx.media3.common.MediaItem) {
        val uri = item.localConfiguration?.uri ?: return
        if (uri.scheme?.startsWith("dacnet-") != true) return
        val id = item.mediaId
        lifecycleScope.launch {
            val m = withContext(Dispatchers.IO) {
                com.dacweb.player.net.NetMeta.probe(this@MainActivity, uri)
            } ?: return@launch
            if (PlayerHub.controller?.currentMediaItem?.mediaId != id) return@launch
            if (m.title.isNotBlank()) b.miniTitle.text = m.title
            if (m.artist.isNotBlank()) {
                val q = PlayerHub.controller?.currentMediaItem?.mediaMetadata?.extras
                    ?.getString(PlayQueue.EXTRA_QUALITY)
                val fmt = PlayerHub.controller?.currentMediaItem?.mediaMetadata?.extras
                    ?.getString(PlayQueue.EXTRA_FORMAT)
                val extra = listOfNotNull(q, fmt).filter { it.isNotBlank() }.joinToString(" · ")
                b.miniArtist.text = listOf(m.artist, extra).filter { it.isNotBlank() }.joinToString(" · ")
            }
            m.cover?.let { b.miniCover.load("file://${it.absolutePath}") }
        }
    }

    private fun playDirectLink(url: String) {
        val path = url.substringBefore('#').substringBefore('?')
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext !in LocalLibrary.SUPPORTED_EXT) {
            toast(getString(R.string.link_unsupported, if (ext.isEmpty()) "?" else ext))
            return
        }
        val fileName = android.net.Uri.decode(path.substringAfterLast('/'))
        val name = fileName.removeSuffix(".$ext").ifBlank { fileName }.ifBlank { url }
        val track = Track(
            id = "url:" + url,
            name = name,
            artist = getString(R.string.link_source),
            album = "",
            cover = "",
            localUri = url   // 走"本地文件"通道，ExoPlayer/直通都能直接读 http(s)
        )
        PlayQueue.set(listOf(track), 0)
        PlayerHub.connect(this) { c ->
            lifecycleScope.launch {
                runCatching { PlayQueue.playCurrent(this@MainActivity, c) }
                    .onFailure { toast(getString(R.string.play_failed, it.message.orEmpty())) }
            }
        }
        b.clearSearch.visibility = View.VISIBLE
        hideKeyboardNow()
    }

    private fun hideKeyboardNow() {
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(b.searchInput.windowToken, 0)
        currentFocus?.clearFocus()
    }

    private fun doSearch() {
        val kw = b.searchInput.text.toString().trim()
        if (kw.isEmpty() || searching) return
        // 直接贴一个音频直链：不搜了，直接播
        if (kw.startsWith("http://") || kw.startsWith("https://")) {
            playDirectLink(kw)
            return
        }
        // 换了关键词就把上一轮的临时缓冲清掉（用户要求：重新搜索时清缓冲）
        if (kw != query && com.dacweb.player.audio.PlayerHub.controller?.isPlaying != true) {
            // 正在播的时候不能动缓存：SimpleCache 被 release 会直接打断播放
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { com.dacweb.player.cache.CacheManager.clearAudio(this@MainActivity) }
            }
        }
        searching = true
        query = kw
        page = 0
        endReached = false
        b.searchProgress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val list = fetchPage(1)
                page = 1
                if (list.size < pageSize()) endReached = true
                submitRanked(list, append = false)
                if (list.isEmpty()) snack(getString(R.string.no_result))
            } catch (e: Exception) {
                snack(getString(R.string.search_failed, e.message ?: ""))
            } finally {
                searching = false
                b.searchProgress.visibility = View.GONE
            }
        }
    }

    /** 每页条数：由插件决定实际返回多少，这里只是宿主请求值。 */
    private fun pageSize(): Int = PAGE_SIZE

    private suspend fun fetchPage(p: Int): List<Track> =
        Providers.search(this, query, p, pageSize(), Quality.current(this))

    /** 按「已下载 → 歌单 → 其它」排序，并更新行内标记。 */
    private fun submitRanked(list: List<Track>, append: Boolean) {
        val ctx = this
        val plKeys = PlaylistStore.matchKeys(ctx)
        val ranked = list.sortedBy { t ->
            when {
                LocalLibrary.find(t.matchKey) != null -> 0
                plKeys.contains(t.matchKey) -> 1
                else -> 2
            }
        }
        adapter.badgeOf = { t ->
            when {
                LocalLibrary.find(t.matchKey) != null -> getString(R.string.badge_downloaded)
                plKeys.contains(t.matchKey) -> getString(R.string.badge_playlist)
                else -> ""
            }
        }
        if (append) adapter.append(ranked) else adapter.submit(ranked)
        prefetchCovers(ranked)
    }

    /** 滑到底部自动加载下一页；不足一页就提示到底了。 */
    private fun loadMore() {
        if (searching || loadingMore || endReached || query.isBlank()) return
        if (adapter.itemCount == 0) return
        loadingMore = true
        lifecycleScope.launch {
            try {
                val list = fetchPage(page + 1)
                page += 1
                if (list.size < pageSize()) {
                    endReached = true
                    snack(getString(R.string.end_reached))
                }
                if (list.isNotEmpty()) submitRanked(list, append = true)
            } catch (e: Exception) {
                snack(getString(R.string.search_failed, e.message ?: ""))
            } finally {
                loadingMore = false
            }
        }
    }

    /** 列表出来后并发找插件补封面（只调插件，宿主不碰任何具体音源）。 */
    private fun prefetchCovers(list: List<Track>) {
        if (list.isEmpty()) return
        coverJob?.cancel()
        val ctx = applicationContext
        coverJob = lifecycleScope.launch(Dispatchers.IO) {
            val sem = Semaphore(6)
            list.take(30).map { t ->
                async {
                    sem.withPermit {
                        val cover = runCatching { Providers.cover(ctx, t) }.getOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?: t.cover.takeIf { it.isNotBlank() }
                            ?: return@withPermit
                        withContext(Dispatchers.Main) { adapter.setCover(t.id, cover) }
                    }
                }
            }.awaitAll()
        }
    }

    private fun playCurrent() {
        withController { c ->
            lifecycleScope.launch {
                try {
                    PlayQueue.playCurrent(this@MainActivity, c)
                    updateMini()
                } catch (e: PluginLimitException) {
                    showLimitDialog { playCurrent() }
                } catch (e: Exception) {
                    toast(getString(R.string.play_failed, e.message ?: ""))
                } finally {
                    // 播放失败信息已用 toast 提示，不在这里显示状态
                }
            }
        }
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = PlayerHub.controller
        if (c != null) {
            block(c)
        } else {
            PlayerHub.connect(this) { block(it) }
        }
    }

    private fun showLimitDialog(onRetry: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.limit_title)
            .setMessage(R.string.limit_message)
            .setPositiveButton(R.string.open_warp) { _, _ -> openWarp() }
            .setNeutralButton(R.string.retry) { _, _ -> onRetry() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openWarp() {
        val intent = packageManager.getLaunchIntentForPackage("com.cloudflare.cloudflarewarp")
        if (intent != null) {
            startActivity(intent)
        } else {
            toast(getString(R.string.warp_missing))
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** 3~4 秒的底部气泡提示，悬浮在迷你播放条之上，不占列表版面。 */
    private fun snack(msg: String) {
        val bar = Snackbar.make(b.results, msg, 4000)
        if (b.miniBar.visibility == View.VISIBLE) bar.anchorView = b.miniBar
        bar.show()
    }
}
