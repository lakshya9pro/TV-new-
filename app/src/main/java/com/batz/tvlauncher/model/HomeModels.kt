package com.batz.tvlauncher.model

/**
 * Top-level content for the whole home screen.
 * Everything shown on screen — icons, chips, cards, labels, badges — comes from this
 * object, which is parsed from assets/home_data.json (or a remote URL, see JsonRepository).
 */
data class HomeData(
    val searchHint: String,
    val rows: List<HomeRow>
)

/**
 * A single horizontal row on the home screen.
 * [type] controls which ViewHolder/layout is used for its [items]:
 *  - "icons"  -> circular app icon row (top of screen)
 *  - "search" -> static search bar (items ignored)
 *  - "chips"  -> pill-shaped category buttons
 *  - "cards"  -> landscape image cards with a label (games, movies, music, etc.)
 */
data class HomeRow(
    val id: String,
    val title: String?,
    val type: String,
    val items: List<RowItem> = emptyList()
)

data class RowItem(
    val id: String,
    val label: String,
    val iconUrl: String? = null,
    val badge: String? = null,
    /** Hex color (e.g. "#5C6BC0") used for the placeholder tile/avatar when no image loads. */
    val placeholderColor: String? = null,
    /** Direct playable stream URL (e.g. HLS .m3u8 or MP4 video URL). */
    val streamUrl: String? = null
)
