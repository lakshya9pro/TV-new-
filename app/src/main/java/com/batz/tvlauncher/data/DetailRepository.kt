package com.batz.tvlauncher.data

import android.content.Context
import com.batz.tvlauncher.model.CastMember
import com.batz.tvlauncher.model.CrewMember
import com.batz.tvlauncher.model.DetailData
import com.batz.tvlauncher.model.Screenshot
import com.batz.tvlauncher.model.SimilarMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Loads detail data from a remote API endpoint or bundled `assets/app_details.json`.
 *
 * Supports Kinflex API endpoints (`https://kinflexbackend.onrender.com/api/detail?id=...&type=...`),
 * M3U live channel details, as well as standard JSON asset files.
 */
class DetailRepository(private val context: Context) {

    suspend fun loadDetail(
        itemId: String,
        apiUrl: String? = "https://kinflexbackend.onrender.com/api/detail",
        assetFileName: String = "app_details.json"
    ): DetailData? = withContext(Dispatchers.IO) {
        val parts = itemId.split(":")
        val rawId = parts[0]
        val mediaType = if (parts.size > 1) parts[1] else "movie"

        // If item is an M3U channel, directly load from app_details.json asset
        if (rawId.startsWith("m3u")) {
            val rawAsset = loadFromAsset(assetFileName)
            if (rawAsset.isNotBlank()) {
                val root = JSONObject(rawAsset)
                val obj = root.optJSONObject(rawId) ?: root.optJSONObject(itemId)
                if (obj != null) return@withContext parseDetail(rawId, obj)
            }
        }

        val raw = if (!apiUrl.isNullOrBlank()) {
            try {
                val fullUrl = if (apiUrl.contains("?")) {
                    "$apiUrl&id=$rawId&type=$mediaType"
                } else {
                    "$apiUrl?id=$rawId&type=$mediaType"
                }
                fetchFromApi(fullUrl)
            } catch (t: Throwable) {
                loadFromAsset(assetFileName)
            }
        } else {
            loadFromAsset(assetFileName)
        }

        if (raw.isBlank()) return@withContext null
        val root = JSONObject(raw)

        // Handles Kinflex API wrapper format { "success": true, "data": { ... } }
        val dataObj = if (root.optBoolean("success", false) && root.has("data")) {
            root.getJSONObject("data")
        } else {
            root.optJSONObject(itemId) ?: root.optJSONObject(rawId) ?: if (root.has("title")) root else null
        }

        if (dataObj != null) parseDetail(rawId, dataObj) else null
    }

    private fun loadFromAsset(assetFileName: String): String {
        return try {
            context.assets.open(assetFileName).use { input ->
                BufferedReader(InputStreamReader(input)).readText()
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun fetchFromApi(urlString: String): String {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "TVLauncher-AndroidApp")
        }
        try {
            if (connection.responseCode in 200..299) {
                return connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                throw IllegalStateException("HTTP error code: ${connection.responseCode}")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseDetail(id: String, obj: JSONObject): DetailData {
        val title = obj.optString("title", "Untitled")
        val overview = obj.optString("overview", obj.optString("description", ""))
        val releaseYear = obj.optString("releaseYear", obj.optString("year", ""))
        val publisher = obj.optString("director", "").let { dir ->
            if (dir.isNotBlank()) "Director: $dir" else obj.optString("publisher", releaseYear)
        }

        val genresArray = obj.optJSONArray("genres")
        val category = if (genresArray != null && genresArray.length() > 0) {
            (0 until genresArray.length()).joinToString(", ") { genresArray.getString(it) }
        } else {
            obj.optString("category", obj.optString("type", ""))
        }

        val cert = obj.optString("certification", obj.optString("contentRating", ""))
        val contentRating = if (cert.isNotBlank()) "Rated $cert" else ""

        val ratingVal = obj.optDouble("rating", obj.optDouble("ratingValue", 0.0))
        val voteCount = obj.optInt("voteCount", obj.optInt("ratingCount", 0))

        val poster = obj.optString("poster", obj.optString("iconUrl", "")).ifBlank { null }
        val backdrop = obj.optString("backdrop", obj.optString("heroImageUrl", "")).ifBlank { null }

        val screenshots = parseKinflexScreenshots(obj)
        val cast = parseCast(obj.optJSONArray("cast"))
        val crew = parseCrew(obj.optJSONArray("crew"))
        val similar = parseSimilar(obj.optJSONArray("similar"))
        val seasons = parseSeasons(obj, screenshots)
        val streamUrl = obj.optString("stream_url", obj.optString("streamUrl", obj.optString("url", ""))).ifBlank { null }

        return DetailData(
            id = id,
            title = title,
            publisher = publisher,
            category = category,
            contentRating = contentRating,
            ratingValue = ratingVal,
            ratingCount = voteCount,
            description = overview,
            iconUrl = poster,
            heroImageUrl = backdrop,
            placeholderColor = "#1C2B45",
            screenshots = screenshots,
            cast = cast,
            crew = crew,
            similar = similar,
            seasons = seasons,
            streamUrl = streamUrl
        )
    }

    private fun parseSeasons(obj: JSONObject, screenshots: List<Screenshot>): List<com.batz.tvlauncher.model.Season> {
        val seasonsList = mutableListOf<com.batz.tvlauncher.model.Season>()
        val seasonsArr = obj.optJSONArray("seasons")
        if (seasonsArr != null && seasonsArr.length() > 0) {
            for (s in 0 until seasonsArr.length()) {
                val seasonObj = seasonsArr.getJSONObject(s)
                val seasonNumber = seasonObj.optInt("seasonNumber", s + 1)
                val name = seasonObj.optString("name", "Season $seasonNumber").ifBlank { "Season $seasonNumber" }
                val epList = mutableListOf<Screenshot>()
                val episodes = seasonObj.optJSONArray("episodes")
                if (episodes != null) {
                    for (e in 0 until episodes.length()) {
                        val epObj = episodes.getJSONObject(e)
                        val epNumber = epObj.optInt("episodeNumber", e + 1)
                        val epName = epObj.optString("name", "Episode $epNumber").trim()
                        val stillPath = epObj.optString("still", "").ifBlank { epObj.optString("still_path", "") }

                        val fullStillUrl = if (stillPath.isNotBlank()) {
                            if (stillPath.startsWith("http")) stillPath else "https://image.tmdb.org/t/p/w500$stillPath"
                        } else null

                        epList.add(
                            Screenshot(
                                id = "ep_${seasonNumber}_$epNumber",
                                caption = "S${seasonNumber} E${epNumber}: $epName",
                                imageUrl = fullStillUrl,
                                placeholderColor = "#1C2B45"
                            )
                        )
                    }
                }
                seasonsList.add(
                    com.batz.tvlauncher.model.Season(
                        seasonNumber = seasonNumber,
                        name = name,
                        episodes = epList
                    )
                )
            }
        } else {
            val epScreenshots = screenshots.filter { it.id.startsWith("ep_") }
            if (epScreenshots.isNotEmpty()) {
                val grouped = epScreenshots.groupBy {
                    val parts = it.id.split("_")
                    if (parts.size >= 2) parts[1].toIntOrNull() ?: 1 else 1
                }
                grouped.toSortedMap().forEach { (seasonNum, eps) ->
                    seasonsList.add(
                        com.batz.tvlauncher.model.Season(
                            seasonNumber = seasonNum,
                            name = "Season $seasonNum",
                            episodes = eps
                        )
                    )
                }
            } else if (screenshots.isNotEmpty() && (obj.optString("category").contains("Series", true) ||
                        obj.optString("category").contains("Drama", true) ||
                        obj.optString("category").contains("Entertainment", true) ||
                        obj.optString("type").contains("tv", true))) {
                seasonsList.add(com.batz.tvlauncher.model.Season(seasonNumber = 1, name = "Season 1", episodes = screenshots))
                seasonsList.add(com.batz.tvlauncher.model.Season(seasonNumber = 2, name = "Season 2", episodes = screenshots.reversed()))
            }
        }
        return seasonsList
    }

    private fun parseKinflexScreenshots(obj: JSONObject): List<Screenshot> {
        val list = mutableListOf<Screenshot>()

        // 1. Episode stills from TV show seasons
        val seasons = obj.optJSONArray("seasons")
        if (seasons != null) {
            for (s in 0 until seasons.length()) {
                val seasonObj = seasons.getJSONObject(s)
                val seasonNumber = seasonObj.optInt("seasonNumber", s + 1)
                val episodes = seasonObj.optJSONArray("episodes")
                if (episodes != null) {
                    for (e in 0 until episodes.length()) {
                        val epObj = episodes.getJSONObject(e)
                        val epNumber = epObj.optInt("episodeNumber", e + 1)
                        val epName = epObj.optString("name", "Episode $epNumber").trim()
                        val stillPath = epObj.optString("still", "").ifBlank { epObj.optString("still_path", "") }

                        if (stillPath.isNotBlank()) {
                            val fullStillUrl = if (stillPath.startsWith("http")) {
                                stillPath
                            } else {
                                "https://image.tmdb.org/t/p/w500$stillPath"
                            }
                            list.add(
                                Screenshot(
                                    id = "ep_${seasonNumber}_$epNumber",
                                    caption = "S${seasonNumber} E${epNumber}: $epName",
                                    imageUrl = fullStillUrl,
                                    placeholderColor = "#1C2B45"
                                )
                            )
                        }
                    }
                }
            }
        }

        // 2. Trailers
        val trailers = obj.optJSONArray("trailers")
        if (trailers != null) {
            for (i in 0 until trailers.length()) {
                val t = trailers.getJSONObject(i)
                val key = t.optString("key", "")
                val name = t.optString("name", "Official Trailer")
                val thumb = if (key.isNotBlank()) "https://img.youtube.com/vi/$key/hqdefault.jpg" else null
                list.add(
                    Screenshot(
                        id = "trailer_$i",
                        caption = name,
                        imageUrl = thumb,
                        placeholderColor = "#1C2B45"
                    )
                )
            }
        }

        // 3. Screenshots array if explicitly present
        val arr = obj.optJSONArray("screenshots")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Screenshot(
                        id = o.optString("id", "shot_$i"),
                        caption = o.optString("caption", "").ifBlank { null },
                        imageUrl = o.optString("imageUrl", "").ifBlank { null },
                        placeholderColor = o.optString("placeholderColor", "#1C2B45")
                    )
                )
            }
        }

        return list
    }

    private fun parseCast(arr: JSONArray?): List<CastMember> {
        if (arr == null) return emptyList()
        val list = mutableListOf<CastMember>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.optString("name", "").trim()
            val character = o.optString("character", "").trim()
            val photo = o.optString("photo", o.optString("photoUrl", "")).ifBlank { null }
            if (name.isNotBlank()) {
                list.add(CastMember(name = name, character = character, photoUrl = photo))
            }
        }
        return list
    }

    private fun parseCrew(arr: JSONArray?): List<CrewMember> {
        if (arr == null) return emptyList()
        val list = mutableListOf<CrewMember>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.optString("name", "").trim()
            val job = o.optString("job", "").trim()
            val dept = o.optString("department", "").trim()
            if (name.isNotBlank()) {
                list.add(CrewMember(name = name, job = job, department = dept))
            }
        }
        return list
    }

    private fun parseSimilar(arr: JSONArray?): List<SimilarMedia> {
        if (arr == null) return emptyList()
        val list = mutableListOf<SimilarMedia>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("id", i.toString())
            val title = o.optString("title", "Untitled").trim()
            val poster = o.optString("poster", o.optString("posterUrl", o.optString("thumbnail", ""))).ifBlank { null }
            val rating = o.optDouble("rating", 0.0)
            val year = o.optString("releaseYear", o.optString("year", ""))
            val type = o.optString("type", "movie")
            val fullId = if (type.isNotBlank()) "$id:$type" else id
            list.add(
                SimilarMedia(
                    id = fullId,
                    title = title,
                    posterUrl = poster,
                    rating = rating,
                    year = year,
                    type = type
                )
            )
        }
        return list
    }
}
