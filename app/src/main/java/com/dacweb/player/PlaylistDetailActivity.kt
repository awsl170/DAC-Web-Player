package com.dacweb.player

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.dacweb.player.audio.PlayQueue
import com.dacweb.player.audio.PlayerHub
import com.dacweb.player.databinding.ActivityPlaylistBinding
import com.dacweb.player.local.LocalLibrary
import com.dacweb.player.model.Track
import com.dacweb.player.plugin.PluginLimitException
import com.dacweb.player.store.Playlist
import com.dacweb.player.store.PlaylistStore
import com.dacweb.player.ui.TrackAdapter
import kotlinx.coroutines.launch

/** 单个歌单详情：播放全部 / 单曲播放 / 从歌单移除。 */
class PlaylistDetailActivity : BaseActivity() {

    private lateinit var b: ActivityPlaylistBinding
    private lateinit var adapter: TrackAdapter
    private var playlist: Playlist? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlaylistBinding.inflate(layoutInflater)
        setContentView(b.root)

        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        b.toolbar.setNavigationOnClickListener { finish() }
        adapter = TrackAdapter(::playAt, ::onLongClick)
        b.tracks.layoutManager = LinearLayoutManager(this)
        b.tracks.adapter = adapter
        b.playAll.setOnClickListener {
            val list = adapter.snapshot()
            if (list.isEmpty()) {
                toast(getString(R.string.playlist_no_tracks))
                return@setOnClickListener
            }
            playAt(0, list[0])
        }
        load(id)
    }

    private fun load(id: String) {
        playlist = PlaylistStore.list(this).firstOrNull { it.id == id }
        val p = playlist ?: run {
            toast(getString(R.string.playlist_missing))
            finish()
            return
        }
        b.toolbar.title = p.name
        b.subtitle.text = getString(R.string.playlist_count, p.tracks.size)
        b.empty.visibility = if (p.tracks.isEmpty()) View.VISIBLE else View.GONE
        b.playAll.visibility = if (p.tracks.isEmpty()) View.GONE else View.VISIBLE
        // 歌单里记的音质已在 Track.quality 中，本地文件存在时优先本地
        adapter.submit(LocalLibrary.withLocal(p.tracks))
    }

    private fun playAt(position: Int, track: Track) {
        val list = adapter.snapshot()
        val idx = list.indexOfFirst { it.id == track.id && it.source == track.source }.coerceAtLeast(0)
        PlayQueue.set(list, idx)
        play()
    }

    private fun play() {
        val c = PlayerHub.controller
        if (c != null) {
            lifecycleScope.launch { runPlay(c) }
        } else {
            PlayerHub.connect(this) { c -> lifecycleScope.launch { runPlay(c) } }
        }
    }

    private suspend fun runPlay(c: androidx.media3.session.MediaController) {
        try {
            PlayQueue.playCurrent(this, c)
        } catch (e: PluginLimitException) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.limit_title)
                .setMessage(R.string.limit_message)
                .setPositiveButton(R.string.open_warp) { _, _ ->
                    packageManager.getLaunchIntentForPackage("com.cloudflare.cloudflarewarp")
                        ?.let { startActivity(it) }
                }
                .setNeutralButton(R.string.retry) { _, _ -> play() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } catch (e: Exception) {
            toast(getString(R.string.play_failed, e.message ?: ""))
        }
    }

    private fun onLongClick(position: Int, track: Track) {
        val actions = arrayOf(
            getString(R.string.play_now),
            getString(R.string.add_next),
            getString(R.string.remove_from_playlist)
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
                        0 -> playAt(position, track)
                        1 -> {
                            PlayQueue.insertNext(track)
                            toast(getString(R.string.added_next, track.name))
                        }
                        else -> {
                            playlist?.let { PlaylistStore.removeTrack(this@PlaylistDetailActivity, it.id, track) }
                            load(playlist?.id.orEmpty())
                            toast(getString(R.string.removed_from_playlist))
                        }
                    }
                }
            })
        }
        sheet.setContentView(container)
        sheet.show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_ID = "playlist_id"
    }
}
