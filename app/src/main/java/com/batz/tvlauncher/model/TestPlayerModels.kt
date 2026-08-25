package com.batz.tvlauncher.model

data class Subtitle(
    val lang: String? = null,
    val label: String? = null,
    val url: String? = null,
    val isDefault: Boolean = false
)

data class MediaResponse(
    val status: String? = null,
    val title: String? = null,
    val seriesTitle: String? = null,
    val episodeTitle: String? = null,
    val videoUrl: String? = null,
    val defaultStream: String? = null,
    val url: String? = null,
    val sourceType: String? = null,
    val subtitles: List<Subtitle>? = null,
    val headers: Map<String, String>? = null
) {
    fun resolveVideoUrl(): String? = videoUrl ?: defaultStream ?: url
    fun resolveSourceType(): String = sourceType ?: "hls"
}

data class SeasonItem(
    val seasonNumber: Int,
    val name: String,
    val episodeCount: Int = 0
)

data class EpisodeItem(
    val episodeNumber: Int,
    val title: String,
    val description: String = "",
    val durationMinutes: Int = 0,
    val thumbnailUrl: String? = null,
    val isCurrent: Boolean = false
)
