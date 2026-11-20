package com.dacweb.player.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.dacweb.player.databinding.ItemTrackBinding
import com.dacweb.player.model.Track

/**
 * 搜索结果列表。默认单击=播放/入队，长按=弹出操作菜单；
 * 进入多选模式后单击=勾选。
 */
class TrackAdapter(
    private val onClick: (Int, Track) -> Unit,
    private val onLongClick: (Int, Track) -> Unit
) : RecyclerView.Adapter<TrackAdapter.VH>() {

    private val items = mutableListOf<Track>()
    private val selected = linkedSetOf<String>()

    /** 行内附加标记（如「已下载」「歌单」），由调用方决定。 */
    var badgeOf: (Track) -> String = { "" }

    var selectionMode: Boolean = false
        private set

    fun submit(list: List<Track>) {
        items.clear()
        items.addAll(list)
        selected.clear()
        notifyDataSetChanged()
    }

    fun snapshot(): List<Track> = items.toList()

    val selectedCount: Int get() = selected.size

    fun selectedTracks(): List<Track> = items.filter { selected.contains(it.id) }

    /** 网易云封面查到后回调更新（列表里临时显示用）。 */
    fun setCover(id: String, cover: String) {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0 || items[i].cover == cover) return
        items[i] = items[i].copy(cover = cover)
        notifyItemChanged(i)
    }

    /** 追加下一页，按 (source,id) 去重。 */
    fun append(list: List<Track>) {
        var added = 0
        for (t in list) {
            if (items.any { it.id == t.id && it.source == t.source }) continue
            items.add(t)
            added++
        }
        if (added > 0) notifyItemRangeInserted(items.size - added, added)
    }

    fun enterSelection() {
        selectionMode = true
        selected.clear()
        notifyDataSetChanged()
    }

    fun exitSelection() {
        selectionMode = false
        selected.clear()
        notifyDataSetChanged()
    }

    fun toggleSelection(id: String): Int {
        if (!selected.add(id)) selected.remove(id)
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) notifyItemChanged(i)
        return selected.size
    }

    fun selectAll() {
        selected.clear()
        items.forEach { selected.add(it.id) }
        notifyDataSetChanged()
    }

    class VH(val b: ItemTrackBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = items[position]
        val b = holder.b
        b.title.text = t.name
        val badge = badgeOf(t)
        b.subtitle.text = (
            listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · ") +
                if (badge.isNotBlank()) " · $badge" else ""
            )
        // 曲目自带封面（本地文件是 file://，在线曲目由插件补全或搜索接口下发）
        val cover = t.cover.takeIf { it.isNotBlank() }
        if (cover.isNullOrBlank()) {
            b.cover.setImageResource(android.R.drawable.ic_media_play)
        } else {
            b.cover.load(cover) {
                crossfade(true)
                placeholder(android.R.drawable.ic_media_play)
            }
        }
        b.check.visibility = if (selectionMode) View.VISIBLE else View.GONE
        b.check.isChecked = selected.contains(t.id)
        b.root.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(pos, t)
        }
        b.root.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION && !selectionMode) onLongClick(pos, t)
            true
        }
    }
}
