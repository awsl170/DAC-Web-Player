package com.dacweb.player.net

import android.os.Bundle
import android.view.View
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 浏览文件服务器上的目录，只按扩展名过滤，不请求任何元数据。 */
class NetBrowserActivity : AppCompatActivity() {

    private lateinit var title: TextView
    private lateinit var info: TextView
    private lateinit var list: RecyclerView
    private lateinit var adapter: TrackAdapter

    private var path = ""
    private var rootPath = ""

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
        adapter = TrackAdapter(
            onClick = { _, t -> onEntry(t) },
            onLongClick = { _, _ -> }
        )
        adapter.badgeOf = { "" }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

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
        // 当前目录里所有音频 = 新的播放队列
        val tracks = adapter.snapshot().filter { it.id.startsWith("net:") }
        val at = tracks.indexOfFirst { it.id == t.id }.coerceAtLeast(0)
        if (tracks.isEmpty()) return
        // 只给点开的这一首读标签/规格：列表不逐项请求服务器，播放也不等它
        lifecycleScope.launch {
            val enriched = withContext(Dispatchers.IO) {
                tracks.map { if (it.id == t.id) NetMeta.enrich(this@NetBrowserActivity, it) else it }
            }
            PlayQueue.set(enriched, at)
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (path != rootPath && path.isNotEmpty()) {
            val parent = path.trimEnd('/').substringBeforeLast('/', "")
            load(parent.ifEmpty { rootPath })
        } else {
            super.onBackPressed()
        }
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
