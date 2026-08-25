package com.batz.tvlauncher.data

import com.batz.tvlauncher.model.MediaResponse
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ApiResponse<T>(
    val isSuccessful: Boolean,
    val codeVal: Int,
    private val bodyData: T?
) {
    fun code(): Int = codeVal
    fun body(): T? = bodyData
}

object ApiClient {
    val mediaApi = MediaApi()
    val tmdbApi = TmdbApi()

    class MediaApi {
        fun getMovieMedia(mediaId: String, selectedTier: Int? = null): ApiResponse<MediaResponse> {
            return fetchMediaJson("http://127.0.0.1:1937/api/media/movie/$mediaId${if (selectedTier != null) "?tier=$selectedTier" else ""}")
        }

        fun getTvMedia(mediaId: String, season: Int, episode: Int, selectedTier: Int? = null): ApiResponse<MediaResponse> {
            return fetchMediaJson("http://127.0.0.1:1937/api/media/tv/$mediaId/$season/$episode${if (selectedTier != null) "?tier=$selectedTier" else ""}")
        }

        private fun fetchMediaJson(urlStr: String): ApiResponse<MediaResponse> {
            return try {
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val jsonStr = stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""

                if (code in 200..299 && jsonStr.isNotEmpty()) {
                    val json = JSONObject(jsonStr)
                    val media = MediaResponse(
                        status = json.optString("status"),
                        title = json.optString("title"),
                        seriesTitle = json.optString("seriesTitle"),
                        episodeTitle = json.optString("episodeTitle"),
                        videoUrl = json.optString("videoUrl"),
                        defaultStream = json.optString("default_stream"),
                        url = json.optString("url"),
                        sourceType = json.optString("sourceType")
                    )
                    ApiResponse(true, code, media)
                } else {
                    ApiResponse(false, code, null)
                }
            } catch (e: Exception) {
                ApiResponse(false, 500, null)
            }
        }
    }

    class TmdbApi {
        fun getTvDetails(tmdbId: Int, apiKey: String): ApiResponse<TvDetails> {
            return ApiResponse(false, 404, null)
        }

        fun getSeasonDetail(tmdbId: Int, season: Int, apiKey: String): ApiResponse<SeasonDetails> {
            return ApiResponse(false, 404, null)
        }
    }

    class TvDetails(val seasons: List<SeasonItemRaw>? = null)
    class SeasonItemRaw(val seasonNumber: Int, val name: String?, val episodeCount: Int)
    class SeasonDetails(val episodes: List<EpisodeItemRaw>? = null)
    class EpisodeItemRaw(val episodeNumber: Int, val name: String?, val overview: String?, val runtime: Int?, val stillPath: String?)
}
