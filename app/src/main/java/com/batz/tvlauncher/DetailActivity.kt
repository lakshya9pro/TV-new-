package com.batz.tvlauncher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.batz.tvlauncher.adapter.CastAdapter
import com.batz.tvlauncher.adapter.CrewAdapter
import com.batz.tvlauncher.adapter.ScreenshotsAdapter
import com.batz.tvlauncher.adapter.SeasonsAdapter
import com.batz.tvlauncher.adapter.SimilarAdapter
import com.batz.tvlauncher.data.DetailRepository
import com.batz.tvlauncher.databinding.ActivityDetailBinding
import com.batz.tvlauncher.model.DetailData
import com.batz.tvlauncher.util.ImageLoader
import kotlinx.coroutines.launch

class DetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ITEM_ID = "extra_item_id"
        const val EXTRA_ITEM_LABEL = "extra_item_label" // fallback title while loading

        fun start(context: Context, itemId: String, itemLabel: String) {
            val intent = Intent(context, DetailActivity::class.java)
            intent.putExtra(EXTRA_ITEM_ID, itemId)
            intent.putExtra(EXTRA_ITEM_LABEL, itemLabel)
            context.startActivity(intent)
        }
    }

    private lateinit var binding: ActivityDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val itemId = intent.getStringExtra(EXTRA_ITEM_ID) ?: return finish()
        val fallbackLabel = intent.getStringExtra(EXTRA_ITEM_LABEL) ?: ""
        binding.titleText.text = fallbackLabel

        lifecycleScope.launch {
            val detail = DetailRepository(applicationContext).loadDetail(itemId)
            if (detail == null) {
                binding.metaLineText.text = "No details available"
                binding.descriptionText.text =
                    "Could not load details for \"$fallbackLabel\". Check your internet connection or backend API."
                return@launch
            }
            bind(detail)
        }
    }

    private fun bind(detail: DetailData) {
        binding.titleText.text = detail.title

        val metaParts = listOfNotNull(
            detail.publisher.ifBlank { null },
            detail.category.ifBlank { null },
            detail.contentRating.ifBlank { null }
        )
        binding.metaLineText.text = metaParts.joinToString("  •  ")

        val fullStars = detail.ratingValue.toInt().coerceIn(0, 5)
        val stars = "★".repeat(fullStars) + "☆".repeat(5 - fullStars)
        binding.ratingLineText.text = if (detail.ratingValue > 0) "$stars  ${detail.ratingValue}" else ""

        binding.descriptionText.text = detail.description

        ImageLoader.load(
            binding.appIconImage, binding.appIconInitial,
            detail.iconUrl, detail.title, detail.placeholderColor
        )
        ImageLoader.load(
            binding.heroImage, null,
            detail.heroImageUrl, detail.title, detail.placeholderColor
        )

        if (detail.id.startsWith("m3u") || detail.contentRating == "LIVE") {
            binding.installButton.text = "Play Live Stream"
        } else {
            binding.installButton.text = "Watch Now"
        }

        binding.installButton.setOnClickListener {
            val liveM3u8Fallback = "https://amg00862-amg00862c6-amgplt0173.playout.now3.amagi.tv/playlist/amg00862-amg00862c6-amgplt0173/playlist.m3u8"
            val targetStream = if (!detail.streamUrl.isNullOrBlank()) {
                detail.streamUrl
            } else if (detail.id.isNotBlank() && !detail.id.startsWith("local_")) {
                "http://127.0.0.1:1937/api/stream?url=${detail.id}"
            } else {
                liveM3u8Fallback
            }
            PlayerActivity.start(this, mediaUrl = targetStream, title = detail.title, isLive = detail.id.startsWith("m3u") || detail.contentRating == "LIVE")
        }

        // Season Selector & Episodes
        if (detail.seasons.isNotEmpty()) {
            binding.seasonSectionContainer.visibility = View.VISIBLE
            binding.seasonRecyclerView.layoutManager =
                LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

            val initialSeason = detail.seasons[0]
            updateEpisodesList(detail.title, initialSeason.name, initialSeason.episodes.ifEmpty { detail.screenshots })

            binding.seasonRecyclerView.adapter = SeasonsAdapter(detail.seasons, selectedPosition = 0) { season, _ ->
                updateEpisodesList(detail.title, season.name, season.episodes.ifEmpty { detail.screenshots })
            }
        } else {
            binding.seasonSectionContainer.visibility = View.GONE
            updateEpisodesList(detail.title, "Screenshots", detail.screenshots)
        }

        // Cast Section
        if (detail.cast.isNotEmpty()) {
            binding.castSectionContainer.visibility = View.VISIBLE
            binding.castRecyclerView.layoutManager =
                LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            binding.castRecyclerView.adapter = CastAdapter(detail.cast)
        } else {
            binding.castSectionContainer.visibility = View.GONE
        }

        // Crew Section
        if (detail.crew.isNotEmpty()) {
            binding.crewSectionContainer.visibility = View.VISIBLE
            binding.crewRecyclerView.layoutManager =
                LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            binding.crewRecyclerView.adapter = CrewAdapter(detail.crew)
        } else {
            binding.crewSectionContainer.visibility = View.GONE
        }

        // Similar Movies / TV Shows Section
        if (detail.similar.isNotEmpty()) {
            binding.similarSectionContainer.visibility = View.VISIBLE
            binding.similarRecyclerView.layoutManager =
                LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            binding.similarRecyclerView.adapter = SimilarAdapter(detail.similar) { item ->
                DetailActivity.start(this, itemId = item.id, itemLabel = item.title)
            }
        } else {
            binding.similarSectionContainer.visibility = View.GONE
        }
    }

    private fun updateEpisodesList(mainTitle: String, titlePrefix: String, screenshots: List<com.batz.tvlauncher.model.Screenshot>) {
        if (screenshots.isNotEmpty()) {
            binding.screenshotsTitle.visibility = View.VISIBLE
            binding.screenshotsRecyclerView.visibility = View.VISIBLE
            if (screenshots.any { it.id.startsWith("ep_") }) {
                binding.screenshotsTitle.text = "$titlePrefix Episodes"
            } else if (screenshots.any { it.id.startsWith("trailer_") }) {
                binding.screenshotsTitle.text = "Trailers & Previews"
            } else {
                binding.screenshotsTitle.text = titlePrefix
            }
            binding.screenshotsRecyclerView.layoutManager =
                LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            binding.screenshotsRecyclerView.adapter = ScreenshotsAdapter(screenshots) { episodeItem ->
                val epTitle = if (!episodeItem.caption.isNullOrBlank()) "$mainTitle - ${episodeItem.caption}" else mainTitle
                val epStream = "https://amg00862-amg00862c6-amgplt0173.playout.now3.amagi.tv/playlist/amg00862-amg00862c6-amgplt0173/playlist.m3u8"
                PlayerActivity.start(this, mediaUrl = epStream, title = epTitle)
            }
        } else {
            binding.screenshotsTitle.visibility = View.GONE
            binding.screenshotsRecyclerView.visibility = View.GONE
        }
    }
}
