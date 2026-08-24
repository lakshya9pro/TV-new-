package com.batz.tvlauncher.data

import android.content.Context
import com.batz.tvlauncher.model.HomeData
import com.batz.tvlauncher.model.HomeRow
import com.batz.tvlauncher.model.RowItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Single source of truth for home-screen content.
 *
 * M3U channels are placed ONLY in the Top Icon Row (`top_icons`), while real movie & TV show
 * cards populate the media card rows.
 */
class JsonRepository(private val context: Context) {

    suspend fun load(
        apiUrl: String? = "https://kinflexbackend.onrender.com/data.json",
        assetFileName: String = "home_data.json"
    ): HomeData = withContext(Dispatchers.IO) {
        val raw = if (!apiUrl.isNullOrBlank()) {
            try {
                fetchFromApi(apiUrl)
            } catch (t: Throwable) {
                // Network failure -> fallback to local asset
                loadFromAsset(assetFileName)
            }
        } else {
            loadFromAsset(assetFileName)
        }
        parse(raw)
    }

    private fun loadFromAsset(assetFileName: String): String {
        return context.assets.open(assetFileName).use { input ->
            BufferedReader(InputStreamReader(input)).readText()
        }
    }

    private fun fetchFromApi(urlString: String): String {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Accept", "*/*")
            setRequestProperty("User-Agent", "TVLauncher-AndroidApp")
        }
        try {
            if (connection.responseCode in 200..299) {
                return connection.inputStream.bufferedReader().use { it.readText() }
            } else if (connection.responseCode in 300..399) {
                val redirectUrl = connection.getHeaderField("Location")
                if (!redirectUrl.isNullOrBlank()) {
                    return fetchFromApi(redirectUrl)
                }
                throw IllegalStateException("HTTP redirect error code: ${connection.responseCode}")
            } else {
                throw IllegalStateException("HTTP error code: ${connection.responseCode}")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchM3uChannels(m3uUrl: String = "https://raw.githubusercontent.com/himanshu-temp/m3u/main/lgtv.m3u"): List<RowItem> {
        val items = mutableListOf<RowItem>()
        try {
            val rawM3u = fetchFromApi(m3uUrl)
            val lines = rawM3u.lines()
            var currentLogo = ""
            var currentGroup = "Live TV"
            var currentName = ""
            var index = 0

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("#EXTINF:")) {
                    val logoMatch = Regex("""tvg-logo="([^"]*)"""").find(trimmed)
                    val groupMatch = Regex("""group-title="([^"]*)"""").find(trimmed)
                    val nameMatch = Regex(""",([^,]*)$""").find(trimmed)

                    currentLogo = logoMatch?.groupValues?.get(1) ?: ""
                    currentGroup = groupMatch?.groupValues?.get(1) ?: "Live TV"
                    currentName = nameMatch?.groupValues?.get(1)?.trim() ?: "Live Channel"
                } else if (trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                    items.add(
                        RowItem(
                            id = "m3u_$index",
                            label = if (currentName.isNotBlank()) currentName else "Channel $index",
                            iconUrl = if (currentLogo.isNotBlank()) currentLogo else null,
                            badge = currentGroup,
                            placeholderColor = "#1E3A52",
                            streamUrl = trimmed
                        )
                    )
                    index++
                }
            }
        } catch (e: Exception) {
            // Ignore error
        }
        return items
    }

    private fun readLocalM3uItems(): List<RowItem> {
        return try {
            val raw = loadFromAsset("home_data.json")
            val root = JSONObject(raw)
            val rowsJson = root.optJSONArray("rows") ?: return emptyList()
            for (i in 0 until rowsJson.length()) {
                val r = rowsJson.getJSONObject(i)
                if (r.optString("id") == "top_icons" || r.optString("id") == "live_channels") {
                    val parsed = parseItems(r.optJSONArray("items"))
                    if (parsed.isNotEmpty()) return parsed
                }
            }
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parse(raw: String): HomeData {
        val root = JSONObject(raw)

        if (root.has("rows")) {
            // Standard home_data.json format
            val searchHint = root.optString("searchHint", "Search movies and TV shows...")
            val rowsJson = root.getJSONArray("rows")
            val rows = mutableListOf<HomeRow>()

            for (i in 0 until rowsJson.length()) {
                val rowObj = rowsJson.getJSONObject(i)
                val type = rowObj.optString("type", "cards")
                val title = if (rowObj.has("title") && !rowObj.isNull("title")) rowObj.getString("title") else null

                val parsedItems = parseItems(rowObj.optJSONArray("items"))
                rows.add(
                    HomeRow(
                        id = rowObj.optString("id", i.toString()),
                        title = title,
                        type = type,
                        items = parsedItems
                    )
                )
            }
            return HomeData(searchHint = searchHint, rows = rows)
        } else {
            val rows = mutableListOf<HomeRow>()

            val m3uItems = fetchM3uChannels().ifEmpty { readLocalM3uItems() }

            // 1. Top Circular Icons Row (M3U channels placed ONLY in top_icons row)
            val topIcons = if (m3uItems.isNotEmpty()) {
                m3uItems
            } else {
                listOf(
                    RowItem("zee_dil_se", "Zee Dil Se", "https://d2mxb63djushzm.cloudfront.net/images/Zee_Dil_Se.png", "Live TV", "#1E3A52", "https://amg00862-amg00862c6-amgplt0173.playout.now3.amagi.tv/playlist/amg00862-amg00862c6-amgplt0173/playlist.m3u8"),
                    RowItem("zee_comedy", "Zee Comedy Nation", "https://d3bd0tgyk368z1.cloudfront.net/zeelg/LG%20logo%20artwork/400x200/zcomedynation.png", "Comedy", "#1E3A52", "https://amg00862-amg00862c5-amgplt0173.playout.now3.amagi.tv/playlist/amg00862-amg00862c5-amgplt0173/playlist.m3u8"),
                    RowItem("atrangii", "Atrangii", "https://d3s2p39m306u7r.cloudfront.net/atrangii/LG/Atrangii_FullandFinal.png", "Entertainment", "#1E3A52", "https://amg13343-amg13343c1-amgplt0173.playout.now3.amagi.tv/playlist/amg13343-amg13343c1-amgplt0173/playlist.m3u8"),
                    RowItem("lallantop", "Lallantop", "https://d3s2p39m306u7r.cloudfront.net/tvtn/Lg-images/LT%20400x200.png", "News", "#1E3A52", "https://amg00644-amg00644c4-amgplt0173.playout.now3.amagi.tv/playlist/amg00644-amg00644c4-amgplt0173/playlist.m3u8")
                )
            }
            rows.add(HomeRow(id = "top_icons", title = null, type = "icons", items = topIcons))

            // 2. Search Bar
            rows.add(HomeRow(id = "search", title = null, type = "search", items = emptyList()))

            // 3. Categories
            val categoryChips = parseCategoryChips(root.optJSONArray("genres"))
            rows.add(HomeRow(id = "categories", title = "Categories", type = "chips", items = categoryChips))

            // 4. Latest Releases
            if (root.has("latestMovies")) {
                val latestItems = parseKinflexItems(root.optJSONArray("latestMovies"))
                if (latestItems.isNotEmpty()) {
                    rows.add(
                        HomeRow(
                            id = "latest",
                            title = "Latest Releases",
                            type = "cards",
                            items = latestItems
                        )
                    )
                }
            }

            // 5. Hero / Featured Movies & Series
            if (root.has("heroMovies")) {
                val heroItems = parseKinflexItems(root.optJSONArray("heroMovies"))
                if (heroItems.isNotEmpty()) {
                    rows.add(
                        HomeRow(
                            id = "hero",
                            title = "Featured Movies & Series",
                            type = "cards",
                            items = heroItems
                        )
                    )
                }
            }

            // 6. Top 10 Movies & Shows Today
            if (root.has("top10Movies")) {
                val top10Items = parseKinflexItems(root.optJSONArray("top10Movies"), isTop10 = true)
                if (top10Items.isNotEmpty()) {
                    rows.add(
                        HomeRow(
                            id = "top10",
                            title = "🔥 Top 10 Today",
                            type = "cards",
                            items = top10Items
                        )
                    )
                }
            }

            // 7. Trending Movies
            if (root.has("trendingMovies")) {
                val trendingItems = parseKinflexItems(root.optJSONArray("trendingMovies"))
                if (trendingItems.isNotEmpty()) {
                    rows.add(
                        HomeRow(
                            id = "trending",
                            title = "Trending Movies",
                            type = "cards",
                            items = trendingItems
                        )
                    )
                }
            }

            // 8. Most Popular Movies
            if (root.has("popularMovies")) {
                val popularItems = parseKinflexItems(root.optJSONArray("popularMovies"))
                if (popularItems.isNotEmpty()) {
                    rows.add(
                        HomeRow(
                            id = "popular",
                            title = "Most Popular Movies",
                            type = "cards",
                            items = popularItems
                        )
                    )
                }
            }

            // 9. Popular Web Shows & Series
            if (root.has("webSeries")) {
                val webSeriesItems = parseKinflexItems(root.optJSONArray("webSeries"))
                if (webSeriesItems.isNotEmpty()) {
                    rows.add(
                        HomeRow(
                            id = "web_series",
                            title = "Popular Web Shows & Series",
                            type = "cards",
                            items = webSeriesItems
                        )
                    )
                }
            }

            // 10. Action & Adventure
            if (root.has("actionMovies")) {
                val actionItems = parseKinflexItems(root.optJSONArray("actionMovies"))
                if (actionItems.isNotEmpty()) {
                    rows.add(
                        HomeRow(
                            id = "action",
                            title = "Action & Adventure",
                            type = "cards",
                            items = actionItems
                        )
                    )
                }
            }

            return HomeData(searchHint = "Search movies and TV shows...", rows = rows)
        }
    }

    private fun parseCategoryChips(genresArr: JSONArray?): List<RowItem> {
        val list = mutableListOf<RowItem>()
        if (genresArr != null && genresArr.length() > 0) {
            for (i in 0 until genresArr.length()) {
                val name = genresArr.optString(i, "").trim()
                if (name.isNotBlank()) {
                    list.add(RowItem(id = "cat_$i", label = name))
                }
            }
        }
        if (list.isEmpty()) {
            val defaults = listOf("Action", "Comedy", "Drama", "Thriller", "Romance", "Horror", "Sci-Fi", "Animation", "Family")
            defaults.forEachIndexed { i, name ->
                list.add(RowItem(id = "cat_$i", label = name))
            }
        }
        return list
    }

    private fun parseKinflexItems(arr: JSONArray?, isTop10: Boolean = false): List<RowItem> {
        if (arr == null) return emptyList()
        val list = mutableListOf<RowItem>()
        for (i in 0 until arr.length()) {
            val itemObj = arr.getJSONObject(i)
            val rawId = itemObj.optString("id", i.toString())
            val title = itemObj.optString("title", itemObj.optString("name", "Untitled")).trim()
            val type = itemObj.optString("type", itemObj.optString("media_type", "movie"))

            var thumbnail = itemObj.optString("thumbnail", "").ifBlank {
                itemObj.optString("poster", itemObj.optString("poster_path", ""))
            }.trim()
            if (thumbnail.isNotBlank() && !thumbnail.startsWith("http")) {
                thumbnail = "https://image.tmdb.org/t/p/w500$thumbnail"
            }

            val quality = itemObj.optString("quality", "").ifBlank {
                itemObj.optString("year", itemObj.optString("release_date", ""))
            }

            val badgeText = if (isTop10) "#${i + 1}" else quality.ifBlank { null }

            val fullId = if (rawId != "0" && type.isNotBlank()) "$rawId:$type" else rawId

            list.add(
                RowItem(
                    id = fullId,
                    label = title,
                    iconUrl = thumbnail.ifBlank { null },
                    badge = badgeText,
                    placeholderColor = "#1C2B45"
                )
            )
        }
        return list
    }

    private fun parseItems(itemsJson: JSONArray?): List<RowItem> {
        if (itemsJson == null) return emptyList()
        val result = mutableListOf<RowItem>()
        for (i in 0 until itemsJson.length()) {
            val itemObj = itemsJson.getJSONObject(i)
            result.add(
                RowItem(
                    id = itemObj.getString("id"),
                    label = itemObj.optString("label", ""),
                    iconUrl = itemObj.optString("iconUrl", "").ifBlank { null },
                    badge = itemObj.optString("badge", "").ifBlank { null },
                    placeholderColor = itemObj.optString("placeholderColor", "").ifBlank { null }
                )
            )
        }
        return result
    }
}
