package com.batz.tvlauncher.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.batz.tvlauncher.R
import com.batz.tvlauncher.model.CastMember
import com.batz.tvlauncher.model.CrewMember
import com.batz.tvlauncher.model.Screenshot
import com.batz.tvlauncher.model.SimilarMedia
import com.batz.tvlauncher.util.ImageLoader
import com.google.android.material.imageview.ShapeableImageView

class SeasonsAdapter(
    private val seasons: List<com.batz.tvlauncher.model.Season>,
    private var selectedPosition: Int = 0,
    private val onSeasonSelected: (com.batz.tvlauncher.model.Season, Int) -> Unit
) : RecyclerView.Adapter<SeasonsAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.seasonChipLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_season_chip, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val season = seasons[position]
        holder.label.text = season.name
        holder.label.isSelected = (position == selectedPosition)

        holder.label.setOnClickListener {
            val currentPos = holder.bindingAdapterPosition
            if (currentPos != RecyclerView.NO_POSITION && selectedPosition != currentPos) {
                val oldPos = selectedPosition
                selectedPosition = currentPos
                notifyItemChanged(oldPos)
                notifyItemChanged(selectedPosition)
                onSeasonSelected(season, selectedPosition)
            }
        }
    }

    override fun getItemCount() = seasons.size
}

class ScreenshotsAdapter(
    private val items: List<Screenshot>
) : RecyclerView.Adapter<ScreenshotsAdapter.VH>() {

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
        val item = items[position]
        ImageLoader.load(
            holder.image, holder.initial, item.imageUrl,
            item.caption ?: "•", item.placeholderColor
        )
        if (item.caption.isNullOrBlank()) {
            holder.caption.visibility = View.GONE
        } else {
            holder.caption.visibility = View.VISIBLE
            holder.caption.text = item.caption
        }
    }

    override fun getItemCount() = items.size
}

class CastAdapter(
    private val items: List<CastMember>
) : RecyclerView.Adapter<CastAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val photo: ShapeableImageView = view.findViewById(R.id.castPhoto)
        val initial: TextView = view.findViewById(R.id.castInitial)
        val name: TextView = view.findViewById(R.id.castName)
        val character: TextView = view.findViewById(R.id.castCharacter)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_cast_member, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        if (item.character.isNotBlank()) {
            holder.character.visibility = View.VISIBLE
            holder.character.text = "as ${item.character}"
        } else {
            holder.character.visibility = View.GONE
        }
        ImageLoader.load(holder.photo, holder.initial, item.photoUrl, item.name, "#1C2B45")
    }

    override fun getItemCount() = items.size
}

class CrewAdapter(
    private val items: List<CrewMember>
) : RecyclerView.Adapter<CrewAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.crewName)
        val job: TextView = view.findViewById(R.id.crewJob)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_crew_member, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        val jobText = if (item.department.isNotBlank() && item.department != item.job) {
            "${item.job} • ${item.department}"
        } else {
            item.job
        }
        holder.job.text = jobText
    }

    override fun getItemCount() = items.size
}

class SimilarAdapter(
    private val items: List<SimilarMedia>,
    private val onClick: (SimilarMedia) -> Unit
) : RecyclerView.Adapter<SimilarAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val poster: ImageView = view.findViewById(R.id.similarPoster)
        val initial: TextView = view.findViewById(R.id.similarInitial)
        val label: TextView = view.findViewById(R.id.similarLabel)
        val badge: TextView = view.findViewById(R.id.similarBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_similar_card, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.label.text = item.title
        ImageLoader.load(holder.poster, holder.initial, item.posterUrl, item.title, "#1C2B45")
        if (item.rating > 0) {
            holder.badge.visibility = View.VISIBLE
            holder.badge.text = "★ %.1f".format(item.rating)
        } else if (!item.year.isNullOrBlank()) {
            holder.badge.visibility = View.VISIBLE
            holder.badge.text = item.year
        } else {
            holder.badge.visibility = View.GONE
        }
        holder.poster.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size
}
