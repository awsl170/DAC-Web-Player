package com.dacweb.player.net

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dacweb.player.R
import com.dacweb.player.audio.PlayQueue
import com.dacweb.player.audio.PlayerHub
import com.dacweb.player.local.LocalLibrary
import com.dacweb.player.model.Track
import com.dacweb.player.ui.TrackAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 浏览文件服务器上的目录，只按扩展名过滤，不请求任何元数据。 */
class NetBrowserActivity : AppCompatActivity() {

    private lateinit var title: TextView
    private lateinit var info: TextView
    private lateinit var list: RecyclerView
    private lateinit var adapter: TrackAdapter
    private lateinit var search: EditText
    private lateinit var clearSearch: View
    private lateinit var playFolder: View

    private var path = ""
    private var rootPath = ""

    /** 当前是否处于搜索结果视图（此时 path 仍是搜索前所在目录）。 */
    private var searchMode = false

    /** 递归扫描的取消令牌：每次新扫描/返回目录都自增，旧扫描看到代数不同就自己退出。 */
    @Volatile
    private var scanGen = 0

    private var searchJob: Job? = null

    /** 目录行用真正的文件夹图标（TrackAdapter 的封面位接 android.resource URI）。 */
    private val folderIcon: String by lazy {
        "android.resource://" + packageName + "/drawable/" + R.drawable.ic_folder
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_net_browser)

        // 全面屏（target 35 强制 edge-to-edge）下自己加状态栏/导航栏留白，否则标题会被时间挡住
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        findViewById<android.widget.ImageButton>(R.id.netBack).setOnClickListener { finish() }
        title = findViewById(R.id.netPath)
        info = findViewById(R.id.netInfo)
        list = findViewById(R.id.netList)
        search = findViewById(R.id.netSearch)
        clearSearch = findViewById(R.id.netClearSearch)
        playFolder = findViewById(R.id.netPlayFolder)
        adapter = TrackAdapter(
            onClick = { _, t -> onEntry(t) },
            onLongClick = { _, _ -> }
        )
        adapter.badgeOf = { "" }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                beginSearch(search.text.toString())
                true
            } else false
        }
        // 边输边搜会有掉线/SMB 并发问题，这里只在按回车（或点清除）时动手
        clearSearch.setOnClickListener {
            search.setText("")
            exitSearch()
        }
        playFolder.setOnClickListener { playCurrentFolder() }

        val server = NetStore.current
        if (server == null) {
            Toast.makeText(this, R.string.net_not_connected, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        rootPath = normalizePath(server.startPath).ifBlank { if (server.type == "smb") "/" else "." }
        path = intent.getStringExtra(EXTRA_PATH) ?: rootPath
        load(path)
    }

    private fun load(p: String) {
        val server = NetStore.current ?: return
        scanGen++
        searchMode = false
        info.text = getString(R.string.net_loading)
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { NetStore.client()?.list(p) ?: emptyList() }
            }
            res.onSuccess { entries ->
                path = p
                title.text = if (p.isBlank()) "/" else p
                val dirs = entries.filter { it.dir }.sortedBy { it.name.lowercase() }
                val files = entries.filter { !it.dir && it.name.substringAfterLast('.', "").lowercase() in LocalLibrary.SUPPORTED_EXT }
                    .sortedBy { it.name.lowercase() }

                val rows = mutableListOf<Track>()
                if (p != rootPath && p.isNotEmpty()) {
                    rows += Track(id = "netdir:..", name = "..", artist = getString(R.string.net_parent), album = "", cover = folderIcon)
                }
                dirs.forEach {
                    rows += Track(id = "netdir:" + it.path, name = it.name, artist = getString(R.string.net_folder), album = "", cover = folderIcon)
                }
                files.forEach {
                    rows += Track(
                        id = "net:" + it.path,
                        name = it.name,
                        artist = server.label,
                        album = if (it.size > 0) sizeText(it.size) else "",
                        cover = "",
                        localUri = NetStore.uriFor(server, it.path).toString()
                    )
                }
                adapter.submit(rows)
                info.text = getString(R.string.net_count, files.size, dirs.size)
                // 遥控器：列表出来了把焦点给第一行
                list.post { list.scrollToPosition(0) }
                // 列表出来后把焦点交给第一行；遥控器从返回按钮按「下」原本会被 RecyclerView
                // 这个容器自己吃掉（容器 focusable，抢了焦点又没有视觉反馈），所以在布局里
                // 把 netList 设为不可聚焦，这里也允许从返回按钮直接落到列表。
                if (currentFocus == null || currentFocus === list || currentFocus?.id == R.id.netBack) {
                    list.post {
                        val first = list.layoutManager?.findViewByPosition(0) ?: list
                        if (first.isInTouchMode) first.requestFocusFromTouch() else first.requestFocus()
                    }
                }
            }.onFailure {
                Toast.makeText(
                    this@NetBrowserActivity,
                    getString(R.string.net_failed, it.message ?: it.javaClass.simpleName),
                    Toast.LENGTH_LONG
                ).show()
                info.text = ""
            }
        }
    }

    // ---------------------------------------------------------------- 搜索

    private fun beginSearch(raw: String) {
        val q = raw.trim()
        if (q.isEmpty()) { exitSearch(); return }
        val server = NetStore.current ?: return
        searchJob?.cancel()
        val gen = ++scanGen
        searchMode = true
        clearSearch.visibility = View.VISIBLE
        title.text = getString(R.string.net_search_hint)
        info.text = getString(R.string.net_searching, 0)
        searchJob = lifecycleScope.launch {
            val hits = withContext(Dispatchers.IO) {
                walk(path, q, gen, progress = { dirs, found ->
                    runOnUiThread {
                        if (gen == scanGen && !isFinishing) {
                            info.text = getString(R.string.net_searching, dirs)
                        }
                    }
                })
            }
            if (gen != scanGen || isFinishing) return@launch
            val rows = hits.map {
                Track(
                    id = "net:" + it.path,
                    name = it.name,
                    artist = server.label,
                    album = it.path.substringBeforeLast('/', ""),
                    cover = "",
                    localUri = NetStore.uriFor(server, it.path).toString()
                )
            }
            adapter.submit(rows)
            info.text = if (rows.isEmpty()) getString(R.string.net_search_empty, q)
            else getString(R.string.net_search_result, rows.size, getString(R.string.net_search_scope))
            list.post { list.scrollToPosition(0) }
        }
    }

    private fun exitSearch() {
        searchJob?.cancel()
        search.setText("")
        clearSearch.visibility = View.GONE
        load(path)
    }

    /** 关掉搜索框焦点，免得遥控器方向键被输入框吃掉。 */
    private fun hideSearchKeyboard() {
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
    }

    // ------------------------------------------------- 递归遍历 / 播放整个文件夹

    /** 广度优先遍历 root 下所有目录，收音频文件；[query] 为空表示不过滤文件名词。 */
    private fun walk(
        root: String,
        query: String?,
        gen: Int,
        maxFiles: Int = 800,
        maxDirs: Int = 4000,
        progress: ((dirs: Int, found: Int) -> Unit)? = null
    ): List<NetEntry> {
        val client = NetStore.client() ?: return emptyList()
        val out = ArrayList<NetEntry>()
        val queue = ArrayDeque<String>()
        queue.add(root)
        var dirs = 0
        while (queue.isNotEmpty() && out.size < maxFiles && dirs < maxDirs) {
            if (gen != scanGen) break
            val d = queue.removeFirst()
            dirs++
            val entries = try {
                client.list(d)
            } catch (e: Exception) {
                continue  // 某个子目录没权限/断线，跳过继续扫，别整个失败
            }
            for (e in entries) {
                if (e.dir) {
                    queue.add(e.path)
                } else if (e.name.substringAfterLast('.', "").lowercase() in LocalLibrary.SUPPORTED_EXT) {
                    if (query == null || e.name.contains(query, ignoreCase = true)) {
                        out.add(e)
                        if (out.size >= maxFiles) break
                    }
                }
            }
            if (progress != null && dirs % 10 == 0) progress(dirs, out.size)
        }
        return out.sortedBy { it.path.lowercase() }
    }

    /** 「播放」键：当前文件夹（搜索时是搜索结果）里所有音轨一起进队列。 */
    private fun playCurrentFolder() {
        val server = NetStore.current ?: return
        hideSearchKeyboard()
        if (searchMode) {
            val rows = adapter.snapshot().filter { it.id.startsWith("net:") }
            if (rows.isEmpty()) {
                Toast.makeText(this, R.string.net_search_empty, Toast.LENGTH_SHORT).show()
                return
            }
            playTracks(rows, 0)
            return
        }
        val base = path
        val gen = ++scanGen
        info.text = getString(R.string.net_scanning, 0)
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                walk(base, null, gen, progress = { _, found ->
                    runOnUiThread {
                        if (gen == scanGen && !isFinishing) info.text = getString(R.string.net_scanning, found)
                    }
                })
            }
            if (gen != scanGen || isFinishing) return@launch
            if (files.isEmpty()) {
                info.text = getString(R.string.net_scan_empty)
                return@launch
            }
            val rows = files.map {
                Track(
                    id = "net:" + it.path,
                    name = it.name,
                    artist = server.label,
                    album = it.path.substringBeforeLast('/', ""),
                    cover = "",
                    localUri = NetStore.uriFor(server, it.path).toString()
                )
            }
            // 扫描模式结束，把列表换成这批曲目，播放顺序和屏幕上一致
            searchMode = false
            adapter.submit(rows)
            info.text = getString(R.string.net_count, rows.size, 0)
            playTracks(rows, 0)
        }
    }

    private fun playTracks(tracks: List<Track>, at: Int) {
        if (tracks.isEmpty()) return
        val first = tracks.getOrNull(at.coerceIn(0, tracks.size - 1)) ?: return
        lifecycleScope.launch {
            // 只给要播的这一首补标签，其余保持文件名，起播不用等元数据
            val enriched = withContext(Dispatchers.IO) {
                tracks.map { if (it.id == first.id) NetMeta.enrich(this@NetBrowserActivity, it) else it }
            }
            PlayQueue.set(enriched, at.coerceIn(0, enriched.size - 1))
            PlayerHub.connect(this@NetBrowserActivity) { c ->
                launch {
                    runCatching { PlayQueue.playCurrent(this@NetBrowserActivity, c) }
                        .onFailure {
                            Toast.makeText(
                                this@NetBrowserActivity,
                                getString(R.string.play_failed, it.message.orEmpty()),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                }
            }
        }
    }

    private fun onEntry(t: Track) {
        if (t.id.startsWith("netdir:")) {
            val target = t.id.removePrefix("netdir:")
            if (target == "..") {
                val parent = path.trimEnd('/').substringBeforeLast('/', "")
                load(parent.ifEmpty { rootPath })
            } else {
                load(target)
            }
            return
        }
        // 当前列表里所有音频 = 新的播放队列（目录视图和搜索结果视图都适用）
        val tracks = adapter.snapshot().filter { it.id.startsWith("net:") }
        val at = tracks.indexOfFirst { it.id == t.id }.coerceAtLeast(0)
        if (tracks.isEmpty()) return
        playTracks(tracks, at)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (searchMode) {
            exitSearch()
            return
        }
        if (path != rootPath && path.isNotEmpty()) {
            val parent = path.trimEnd('/').substringBeforeLast('/', "")
            load(parent.ifEmpty { rootPath })
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        scanGen++
        super.onDestroy()
    }

    private fun sizeText(b: Long): String = when {
        b >= 1024L * 1024 * 1024 -> String.format("%.1f GB", b / 1024.0 / 1024 / 1024)
        b >= 1024L * 1024 -> String.format("%.1f MB", b / 1024.0 / 1024)
        b >= 1024 -> String.format("%.0f KB", b / 1024.0)
        else -> "$b B"
    }

    companion object {
        const val EXTRA_PATH = "path"
    }
}
