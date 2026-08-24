package com.batz.tvlauncher.model

/**
 * Everything shown on the app detail page (title, meta line, rating, description,
 * screenshots, cast, crew, and similar movies/TV shows).
 */
data class Season(
    val seasonNumber: Int,
    val name: String,
    val episodes: List<Screenshot> = emptyList()
)

data class DetailData(
    val id: String,
    val title: String,
    val publisher: String,
    val category: String,
    val contentRating: String,
    val ratingValue: Double,
    val ratingCount: Int,
    val description: String,
    val iconUrl: String?,
    val heroImageUrl: String?,
    val placeholderColor: String?,
    val screenshots: List<Screenshot> = emptyList(),
    val cast: List<CastMember> = emptyList(),
    val crew: List<CrewMember> = emptyList(),
    val similar: List<SimilarMedia> = emptyList(),
    val seasons: List<Season> = emptyList(),
    val streamUrl: String? = null
)

data class Screenshot(
    val id: String,
    val caption: String?,
    val imageUrl: String?,
    val placeholderColor: String?
)

data class CastMember(
    val name: String,
    val character: String,
    val photoUrl: String?
)

data class CrewMember(
    val name: String,
    val job: String,
    val department: String
)

data class SimilarMedia(
    val id: String,
    val title: String,
    val posterUrl: String?,
    val rating: Double,
    val year: String?,
    val type: String
)
