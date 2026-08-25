package com.batz.tvlauncher.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.batz.tvlauncher.R
import com.batz.tvlauncher.model.EpisodeItem
import com.batz.tvlauncher.util.ImageLoader

class EpisodeAdapter(
    private val onEpisodeClick: (EpisodeItem) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.VH>() {

    private var items: List<EpisodeItem> = emptyList()

    fun submitList(newList: List<EpisodeItem>) {
        items = newList
        notifyDataSetChanged()
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.screenshotImage)
        val initial: TextView = view.findViewById(R.id.screenshotInitial)
        val caption: TextView = view.findViewById(R.id.screenshotCaption)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_screenshot, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ep = items[position]
        holder.caption.text = "E${ep.episodeNumber}. ${ep.title}"
        holder.caption.visibility = View.VISIBLE
        ImageLoader.load(holder.image, holder.initial, ep.thumbnailUrl, ep.title, "#1C2B45")

        holder.itemView.setOnClickListener {
            onEpisodeClick(ep)
        }
    }

    override fun getItemCount() = items.size
}
