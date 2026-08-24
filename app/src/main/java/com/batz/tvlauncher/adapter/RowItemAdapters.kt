package com.batz.tvlauncher.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.batz.tvlauncher.R
import com.batz.tvlauncher.model.RowItem
import com.batz.tvlauncher.util.ImageLoader
import com.google.android.material.imageview.ShapeableImageView

typealias ItemClick = (RowItem) -> Unit

/** Circular icon row (Netflix/Prime/Live Channels top row). */
class TopIconsAdapter(
    private val items: List<RowItem>,
    private val onClick: ItemClick
) : RecyclerView.Adapter<TopIconsAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val image: ShapeableImageView = view.findViewById(R.id.iconImage)
        val initial: TextView = view.findViewById(R.id.iconInitial)
        val label: TextView = view.findViewById(R.id.iconLabel)
    }

    override fun getItemViewType(position: Int): Int = 101

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_top_icon, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.label.text = item.label
        ImageLoader.load(holder.image, holder.initial, item.iconUrl, item.label, item.placeholderColor)
        holder.image.setOnClickListener { onClick(item) }
        holder.image.contentDescription = item.label
    }

    override fun getItemCount() = items.size
}

/** Pill-shaped text chips (category filters). */
class ChipsAdapter(
    private val items: List<RowItem>,
    private val onClick: ItemClick
) : RecyclerView.Adapter<ChipsAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view as TextView
    }

    override fun getItemViewType(position: Int): Int = 102

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_category_chip, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.label.text = item.label
        holder.label.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size
}

/** Vertical 2:3 poster media cards used for movie/tv/live channels rows. */
class CardsAdapter(
    private val items: List<RowItem>,
    private val onClick: ItemClick
) : RecyclerView.Adapter<CardsAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.cardImage)
        val initial: TextView = view.findViewById(R.id.cardInitial)
        val label: TextView = view.findViewById(R.id.cardLabel)
        val badge: TextView = view.findViewById(R.id.cardBadge)
    }

    override fun getItemViewType(position: Int): Int = 103

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_media_card, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.label.text = item.label
        ImageLoader.load(holder.image, holder.initial, item.iconUrl, item.label, item.placeholderColor)
        holder.image.setOnClickListener { onClick(item) }
        if (item.badge.isNullOrBlank()) {
            holder.badge.visibility = View.GONE
        } else {
            holder.badge.visibility = View.VISIBLE
            holder.badge.text = item.badge
        }
    }

    override fun getItemCount() = items.size
}
