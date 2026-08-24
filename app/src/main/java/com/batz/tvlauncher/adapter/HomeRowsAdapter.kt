package com.batz.tvlauncher.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.batz.tvlauncher.R
import com.batz.tvlauncher.model.HomeRow
import com.batz.tvlauncher.model.RowItem

/**
 * Vertical list of rows. Each row inflates its own horizontal RecyclerView; a single shared
 * [RecyclerView.RecycledViewPool] per row-type is used so scrolling on low-end TV hardware
 * doesn't re-inflate item views from scratch every time a row type repeats.
 */
class HomeRowsAdapter(
    private val rows: List<HomeRow>,
    private val searchHint: String,
    private val onItemClick: (RowItem) -> Unit
) : RecyclerView.Adapter<HomeRowsAdapter.RowVH>() {

    companion object {
        private const val TYPE_ICONS = 1
        private const val TYPE_SEARCH = 2
        private const val TYPE_CHIPS = 3
        private const val TYPE_CARDS = 4
    }

    private val iconsPool = RecyclerView.RecycledViewPool()
    private val chipsPool = RecyclerView.RecycledViewPool()
    private val cardsPool = RecyclerView.RecycledViewPool()

    inner class RowVH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.rowTitle)
        val searchBar: View = view.findViewById(R.id.searchBarContainer)
        val searchHint: TextView = view.findViewById(R.id.searchHintText)
        val itemsList: RecyclerView = view.findViewById(R.id.rowItemsRecyclerView)
    }

    override fun getItemViewType(position: Int): Int {
        return when (rows[position].type) {
            "icons" -> TYPE_ICONS
            "search" -> TYPE_SEARCH
            "chips" -> TYPE_CHIPS
            else -> TYPE_CARDS
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowVH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_home_row, parent, false)
        return RowVH(view)
    }

    override fun onBindViewHolder(holder: RowVH, position: Int) {
        val row = rows[position]

        if (row.title.isNullOrBlank()) {
            holder.title.visibility = View.GONE
        } else {
            holder.title.visibility = View.VISIBLE
            holder.title.text = row.title
        }

        when (getItemViewType(position)) {
            TYPE_SEARCH -> {
                holder.searchBar.visibility = View.VISIBLE
                holder.itemsList.visibility = View.GONE
                holder.searchHint.text = searchHint
                holder.searchBar.setOnClickListener {
                    com.batz.tvlauncher.SearchActivity.start(holder.itemView.context)
                }
                holder.searchHint.setOnClickListener {
                    com.batz.tvlauncher.SearchActivity.start(holder.itemView.context)
                }
            }
            TYPE_ICONS -> {
                holder.searchBar.visibility = View.GONE
                holder.itemsList.visibility = View.VISIBLE
                setupHorizontalList(holder.itemsList, iconsPool)
                holder.itemsList.adapter = TopIconsAdapter(row.items, onItemClick)
            }
            TYPE_CHIPS -> {
                holder.searchBar.visibility = View.GONE
                holder.itemsList.visibility = View.VISIBLE
                setupHorizontalList(holder.itemsList, chipsPool)
                holder.itemsList.adapter = ChipsAdapter(row.items) { item ->
                    com.batz.tvlauncher.SearchActivity.start(holder.itemView.context, initialQuery = item.label, mode = "genre")
                }
            }
            else -> { // TYPE_CARDS
                holder.searchBar.visibility = View.GONE
                holder.itemsList.visibility = View.VISIBLE
                setupHorizontalList(holder.itemsList, cardsPool)
                holder.itemsList.adapter = CardsAdapter(row.items, onItemClick)
            }
        }
    }

    private fun setupHorizontalList(recyclerView: RecyclerView, pool: RecyclerView.RecycledViewPool) {
        if (recyclerView.layoutManager == null) {
            recyclerView.layoutManager =
                LinearLayoutManager(recyclerView.context, LinearLayoutManager.HORIZONTAL, false)
        }
        recyclerView.setRecycledViewPool(pool)
        recyclerView.setHasFixedSize(false)
        recyclerView.setItemViewCacheSize(6)
    }

    override fun getItemCount() = rows.size
}
