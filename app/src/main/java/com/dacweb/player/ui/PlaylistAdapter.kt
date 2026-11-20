package com.dacweb.player.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.dacweb.player.databinding.ItemPlaylistBinding
import com.dacweb.player.store.Playlist

/** 歌单列表：单击进入，右侧按钮弹出重命名/删除。 */
class PlaylistAdapter(
    private val onClick: (Playlist) -> Unit,
    private val onMore: (Playlist, View) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.VH>() {

    private val items = mutableListOf<Playlist>()

    fun submit(list: List<Playlist>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val b: ItemPlaylistBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        holder.b.name.text = p.name
        holder.b.count.text = "${p.tracks.size} 首"
        holder.b.root.setOnClickListener { onClick(p) }
        holder.b.more.setOnClickListener { v -> onMore(p, v) }
    }
}
