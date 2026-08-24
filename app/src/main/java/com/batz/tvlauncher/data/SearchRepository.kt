package com.batz.tvlauncher.data

import com.batz.tvlauncher.model.RowItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Executes search & genre queries against Kinflex API:
 * - Search Mode: `https://kinflexbackend.onrender.com/api/search?q={query}&type=multi&mode=search`
 * - Genre Mode: `https://kinflexbackend.onrender.com/api/search?q={genre}&type=multi&mode=genre`
 */
class SearchRepository {

    suspend fun search(query: String, mode: String = "search"): List<RowItem> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val encodedQuery = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val urlString = "https://kinflexbackend.onrender.com/api/search?q=$encodedQuery&type=multi&mode=$mode"
        val items = mutableListOf<RowItem>()
        try {
            val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "TVLauncher-AndroidApp")
            }
            if (connection.responseCode in 200..299) {
                val raw = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()

                val arr = if (raw.trim().startsWith("[")) {
                    JSONArray(raw)
                } else {
                    val root = JSONObject(raw)
                    root.optJSONArray("results") ?: JSONArray()
                }

                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val mediaType = obj.optString("media_type", obj.optString("type", "movie"))
                    if (mediaType == "person") continue

                    val id = obj.optString("id", i.toString())
                    val title = obj.optString("title", obj.optString("name", obj.optString("original_title", obj.optString("original_name", "Untitled")))).trim()
                    if (title.isBlank()) continue

                    var poster = obj.optString("poster_path", obj.optString("poster", obj.optString("thumbnail", ""))).trim()
                    if (poster.isNotBlank() && !poster.startsWith("http")) {
                        poster = "https://image.tmdb.org/t/p/w500$poster"
                    }

                    val voteAvg = obj.optDouble("vote_average", obj.optDouble("rating", 0.0))
                    val date = obj.optString("release_date", obj.optString("first_air_date", obj.optString("year", "")))
                    val year = if (date.length >= 4) date.substring(0, 4) else date

                    val badgeText = if (voteAvg > 0) "★ %.1f".format(voteAvg) else year.ifBlank { mediaType.uppercase() }

                    val fullId = if (mediaType.isNotBlank()) "$id:$mediaType" else id

                    items.add(
                        RowItem(
                            id = fullId,
                            label = title,
                            iconUrl = poster.ifBlank { null },
                            badge = badgeText.ifBlank { null },
                            placeholderColor = "#1C2B45"
                        )
                    )
                }
            } else {
                connection.disconnect()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        items
    }
}
