package com.example.mybasic.activity

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// ─── Constants ───────────────────────────────────────────────────────────────

private const val TAG = "NanoServer"

const val DefaultPort = 1657
const val DefaultReferer = "https://nextgencloudfabric.com/"
const val DefaultOrigin = "https://nextgencloudfabric.com"
const val UserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
const val SECRET_KEY_ENCRYPTED = "MxASAkl/yHTGg+/Tw1R7u96nGqkWsOZ2"
const val DES_KEY = "dsawdf634eebGFHITR5UT9kS0"
const val DES_IV = "32456738"
const val AES_KEY_STR = "0123456789123456"
const val AES_IV_STR = "2015030120123456"
const val WS_SECRET = "00b5f05c40b4f1d91dbc9b3fd8a059ef"
const val MAIN_URL = "https://filmin.ajfysu.com"
const val HOST_HEADER = "filmin.ajfysu.com"
const val APP_ID = "filmin"
const val CHANNEL_CODE = "filmin_sh_1000"
const val PACKAGE_NAME = "com.dramarush.shortin"
const val GAID = ""
const val P2P_SALT = "Zox882LYjEn4Rqpa"
const val TMDB_KEY = "e6333b32409e02a4a6eba6fb7ff866bb"

// ─── Global Device State ─────────────────────────────────────────────────────

private val brandModels = mapOf(
    "Samsung" to listOf("SM-S918B", "SM-A528B", "SM-M336B"),
    "Xiaomi" to listOf("2201117TI", "M2012K11AI", "Redmi Note 11"),
    "OnePlus" to listOf("LE2111", "CPH2449", "IN2023"),
    "Google" to listOf("Pixel 6", "Pixel 7", "Pixel 8"),
    "Realme" to listOf("RMX3085", "RMX3360", "RMX3551")
)

private val deviceId: String by lazy {
    val b = ByteArray(16)
    SecureRandom().nextBytes(b)
    b.joinToString("") { "%02x".format(it) }
}

private val mobMfr: String by lazy { brandModels.keys.toList().random() }
private val mobModel: String by lazy { brandModels[mobMfr]!!.random() }

@Volatile
private var cachedToken: String = ""
private val tokenLock = Any()

// ─── Data Classes ────────────────────────────────────────────────────────────

data class TmdbInfo(
    val id: Int,
    val title: String,
    val year: String,
    val type: String,
    var season: Int? = null,
    var episode: Int? = null
)

data class NewapiStreamResult(
    val title: String,
    val backdrop: String,
    val streamURLs: List<String>,
    val found: Boolean
)

data class StreamFlowResult(
    val streamURLs: List<String> = emptyList(),
    val title: String = "",
    val source: String = "",
    val found: Boolean = false
)

data class SubtitleTrack(
    val lang: String,
    val label: String,
    val url: String
)

// ─── Crypto & Helpers ────────────────────────────────────────────────────────

fun md5Hex(text: String): String {
    if (NativeEngine.isLoaded) {
        return NativeEngine.md5Hex(text)
    }
    val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

fun des3Decrypt(encryptedBase64: String): String {
    return try {
        val keyBytes = ByteArray(24)
        val desKeyBytes = DES_KEY.toByteArray(Charsets.UTF_8)
        System.arraycopy(desKeyBytes, 0, keyBytes, 0, minOf(desKeyBytes.size, 24))

        val data = android.util.Base64.decode(encryptedBase64, android.util.Base64.DEFAULT)
        val keySpec = SecretKeySpec(keyBytes, "DESede")
        val ivSpec = IvParameterSpec(DES_IV.toByteArray(Charsets.UTF_8))

        val cipher = Cipher.getInstance("DESede/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        val decrypted = cipher.doFinal(data)
        String(decrypted, Charsets.UTF_8)
    } catch (e: Exception) {
        ""
    }
}

fun generateSign(curTime: String): String {
    if (NativeEngine.isLoaded) {
        return NativeEngine.generateSign(curTime, deviceId)
    }
    val secret = des3Decrypt(SECRET_KEY_ENCRYPTED)
    return md5Hex(secret + deviceId + curTime).uppercase()
}

fun generateP2pToken(vodId: String, timestamp: String): String {
    if (NativeEngine.isLoaded) {
        return NativeEngine.generateP2pToken(vodId, timestamp, deviceId)
    }
    return md5Hex(P2P_SALT + deviceId + vodId + timestamp).uppercase()
}

fun signVideoUrl(videoUrl: String): String {
    if (videoUrl.isEmpty()) return ""
    return try {
        val uri = URI(videoUrl)
        val nowSec = System.currentTimeMillis() / 1000
        val wsTime = (nowSec + 60).toString(16)
        val wsSecret = md5Hex(WS_SECRET + uri.path + wsTime)
        val sep = if (videoUrl.contains("?")) "&" else "?"
        "${videoUrl}${sep}wsSecret=${wsSecret}&wsTime=${wsTime}"
    } catch (e: Exception) {
        videoUrl
    }
}

fun aesDecrypt(encryptedBase64: String): String {
    return try {
        val data = android.util.Base64.decode(encryptedBase64.trim(), android.util.Base64.DEFAULT)
        val keySpec = SecretKeySpec(AES_KEY_STR.toByteArray(Charsets.UTF_8), "AES")
        val ivSpec = IvParameterSpec(AES_IV_STR.toByteArray(Charsets.UTF_8))

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        val unpadded = cipher.doFinal(data)

        if (unpadded.size >= 2 && unpadded[0] == 0x1f.toByte() && unpadded[1] == 0x8b.toByte()) {
            GZIPInputStream(ByteArrayInputStream(unpadded)).use { gzip ->
                String(gzip.readBytes(), Charsets.UTF_8)
            }
        } else {
            String(unpadded, Charsets.UTF_8)
        }
    } catch (e: Exception) {
        ""
    }
}

fun buildHeaders(curTime: String, token: String): Map<String, String> {
    return mapOf(
        "Accept-Encoding" to "identity",
        "androidid" to deviceId,
        "app_id" to APP_ID,
        "app_language" to "en",
        "channel_code" to CHANNEL_CODE,
        "Connection" to "Keep-Alive",
        "Content-Type" to "application/x-www-form-urlencoded",
        "cur_time" to curTime,
        "device_id" to deviceId,
        "en_al" to "0",
        "gaid" to GAID,
        "Host" to HOST_HEADER,
        "is_display" to "GMT+05:30",
        "is_language" to "en",
        "is_vvv" to "0",
        "log-header" to "I am the log request header.",
        "mob_mfr" to mobMfr,
        "mobmodel" to mobModel,
        "package_name" to PACKAGE_NAME,
        "sign" to generateSign(curTime),
        "sys_platform" to "2",
        "sysrelease" to "13",
        "token" to token,
        "User-Agent" to "okhttp/4.11.0",
        "version" to "30000"
    )
}

fun httpsPost(endpoint: String, formData: Map<String, String>, headers: Map<String, String>): ByteArray {
    return try {
        val url = URL(MAIN_URL + endpoint)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 30000

        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }

        val postData = formData.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }

        conn.outputStream.use { os ->
            os.write(postData.toByteArray(Charsets.UTF_8))
        }

        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        stream?.use { it.readBytes() } ?: ByteArray(0)
    } catch (e: Exception) {
        ByteArray(0)
    }
}

fun fetchToken(): String {
    synchronized(tokenLock) {
        if (cachedToken.isNotEmpty()) return cachedToken

        val curTime = System.currentTimeMillis().toString()
        val headers = buildHeaders(curTime, "")
        val buf = httpsPost("/api/public/init", mapOf("invited_by" to "", "is_install" to "1"), headers)
        if (buf.isEmpty()) return ""

        val text = String(buf, Charsets.UTF_8).trim()
        val jsonStr = if (text.startsWith("{")) text else aesDecrypt(text)

        try {
            val parsed = JSONObject(jsonStr)
            val resObj = parsed.optJSONObject("result")
            val userObj = resObj?.optJSONObject("user_info")
            val t = userObj?.optString("token")
            if (!t.isNullOrEmpty()) {
                cachedToken = t
                return cachedToken
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ""
    }
}

fun apiPost(endpoint: String, formData: Map<String, String>): JSONObject? {
    val token = fetchToken()
    val curTime = System.currentTimeMillis().toString()
    val buf = httpsPost(endpoint, formData, buildHeaders(curTime, token))
    if (buf.isEmpty()) return null

    val dec = aesDecrypt(String(buf, Charsets.UTF_8).trim())
    return try {
        JSONObject(dec)
    } catch (e: Exception) {
        null
    }
}

fun getVodInfo(vodId: String, audioType: Int): JSONObject? {
    val token = fetchToken()
    val curTime = System.currentTimeMillis().toString()
    val buf = httpsPost(
        "/api/vod/info_new",
        mapOf(
            "sign" to generateP2pToken(vodId, curTime),
            "vod_id" to vodId,
            "cur_time" to curTime,
            "audio_type" to audioType.toString()
        ),
        buildHeaders(curTime, token)
    )
    if (buf.isEmpty()) return null

    val dec = aesDecrypt(String(buf, Charsets.UTF_8).trim())
    val result = try {
        JSONObject(dec)
    } catch (e: Exception) {
        return null
    }

    val resObj = result.optJSONObject("result")
    val collections = resObj?.optJSONArray("vod_collection")
    if (collections != null) {
        for (i in 0 until collections.length()) {
            val ep = collections.optJSONObject(i) ?: continue
            var rawUrl = ep.optString("vod_url")
            if (rawUrl.isEmpty()) rawUrl = ep.optString("down_url")

            ep.put("raw_url", rawUrl)
            ep.put("signed_url", signVideoUrl(rawUrl))
            if (ep.has("vod_url") && ep.getString("vod_url").isNotEmpty()) {
                ep.put("vod_url", signVideoUrl(ep.getString("vod_url")))
            }
            if (ep.has("down_url") && ep.getString("down_url").isNotEmpty()) {
                ep.put("down_url", signVideoUrl(ep.getString("down_url")))
            }
        }
    }
    return result
}

fun fetchTmdbDetails(id: String, mediaType: String): TmdbInfo? {
    if (id.startsWith("tt")) {
        val findURL = "https://api.themoviedb.org/3/find/$id?api_key=$TMDB_KEY&external_source=imdb_id"
        val jsonStr = httpGet(findURL)
        if (jsonStr.isNotEmpty()) {
            try {
                val findRes = JSONObject(jsonStr)
                val tvResults = findRes.optJSONArray("tv_results")
                val movieResults = findRes.optJSONArray("movie_results")

                if (mediaType == "tv" && tvResults != null && tvResults.length() > 0) {
                    return parseTmdbMap(tvResults.getJSONObject(0), "tv")
                } else if (movieResults != null && movieResults.length() > 0) {
                    return parseTmdbMap(movieResults.getJSONObject(0), "movie")
                } else if (tvResults != null && tvResults.length() > 0) {
                    return parseTmdbMap(tvResults.getJSONObject(0), "tv")
                }
            } catch (e: Exception) {}
        }
    }

    if (mediaType == "movie" || mediaType == "tv") {
        val reqURL = "https://api.themoviedb.org/3/$mediaType/$id?api_key=$TMDB_KEY"
        val jsonStr = httpGet(reqURL)
        if (jsonStr.isNotEmpty()) {
            try {
                return parseTmdbMap(JSONObject(jsonStr), mediaType)
            } catch (e: Exception) {}
        }
    }

    var jsonStr = httpGet("https://api.themoviedb.org/3/movie/$id?api_key=$TMDB_KEY")
    if (jsonStr.isNotEmpty()) {
        try {
            return parseTmdbMap(JSONObject(jsonStr), "movie")
        } catch (e: Exception) {}
    }

    jsonStr = httpGet("https://api.themoviedb.org/3/tv/$id?api_key=$TMDB_KEY")
    if (jsonStr.isNotEmpty()) {
        try {
            return parseTmdbMap(JSONObject(jsonStr), "tv")
        } catch (e: Exception) {}
    }

    return null
}

fun parseTmdbMap(data: JSONObject, mediaType: String): TmdbInfo {
    val idVal = data.optInt("id", 0)
    var titleVal = data.optString("title")
    if (titleVal.isEmpty()) titleVal = data.optString("name")
    if (titleVal.isEmpty()) titleVal = data.optString("original_title")
    if (titleVal.isEmpty()) titleVal = data.optString("original_name")

    var dateVal = data.optString("release_date")
    if (dateVal.isEmpty()) dateVal = data.optString("first_air_date")

    val yearVal = if (dateVal.contains("-")) dateVal.split("-")[0] else dateVal
    return TmdbInfo(id = idVal, title = titleVal, year = yearVal, type = mediaType)
}

fun scoreCandidate(item: JSONObject, title: String, year: String, season: Int?): Int {
    val vodName = item.optString("vod_name")
    val vodYear = item.optString("vod_year")
    if (NativeEngine.isLoaded) {
        return NativeEngine.scoreCandidate(vodName, vodYear, title, year, season ?: 0)
    }

    var score = 0
    val normVod = vodName.trim().lowercase()
    val normTitle = title.trim().lowercase()

    var baseTitle = normVod
    val parts = normVod.split(" - ")
    if (parts.isNotEmpty()) baseTitle = parts[0].trim()
    val parts2 = baseTitle.split(" season")
    if (parts2.isNotEmpty()) baseTitle = parts2[0].trim()

    if (baseTitle == normTitle) {
        score += 100
    } else if (normVod.startsWith("$normTitle ") || normVod.startsWith("$normTitle-")) {
        score += 80
    } else if (normVod.contains(normTitle)) {
        score += 20
    }

    if (vodYear.isNotEmpty() && year.isNotEmpty() && vodYear == year) {
        score += 40
    }

    if (season != null) {
        val sStr = "season $season"
        val sShort = "s$season"
        if (normVod.contains(sStr) || normVod.contains(sShort)) {
            score += 50
        }
    }

    if (!normVod.startsWith(normTitle) && !baseTitle.startsWith(normTitle)) {
        score -= 60
    }

    return score
}

fun findBestVodMatch(title: String, year: String, season: Int?, mediaType: String): JSONObject? {
    if (title.isEmpty()) return null

    val queries = mutableListOf<String>()
    if (season != null) queries.add("$title Season $season")
    if (mediaType == "tv" || season == null) {
        queries.add("$title Season 1")
        queries.add("$title Season")
    }
    if (year.isNotEmpty()) queries.add("$title $year")
    queries.add(title)

    val seenIds = mutableSetOf<String>()
    val candidateItems = mutableListOf<JSONObject>()

    for (q in queries) {
        val res = apiPost("/api/search/result", mapOf("kw" to q, "pn" to "1"))
        val items = res?.optJSONObject("result")?.optJSONArray("items")
            ?: res?.optJSONArray("result")
        if (items != null) {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val idStr = item.optString("id")
                if (idStr.isNotEmpty() && seenIds.add(idStr)) {
                    candidateItems.add(item)
                }
            }
        }
    }

    if (candidateItems.isEmpty()) return null

    var bestScore = -9999
    var bestItem: JSONObject? = null

    for (item in candidateItems) {
        val sc = scoreCandidate(item, title, year, season)
        if (sc > bestScore) {
            bestScore = sc
            bestItem = item
        }
    }

    return if (bestScore >= 30) bestItem else null
}

fun fetchNewapiStream(tmdb: String, imdb: String, mediaType: String, season: String, episode: String): NewapiStreamResult? {
    val idStr = if (tmdb.isNotEmpty()) tmdb else imdb
    if (idStr.isEmpty()) return null

    val sNum = season.toIntOrNull()?.coerceAtLeast(1) ?: 1
    val eNum = episode.toIntOrNull()?.coerceAtLeast(1) ?: 1

    val seasonPtr = if (mediaType == "tv") sNum else null
    val candidateTypes = if (mediaType == "movie" || mediaType == "tv") listOf(mediaType) else listOf("movie", "tv")

    var tmdbData: TmdbInfo? = null
    var matchedVod: JSONObject? = null

    for (candType in candidateTypes) {
        val data = fetchTmdbDetails(idStr, candType)
        if (data != null) {
            val match = findBestVodMatch(data.title, data.year, seasonPtr, data.type)
            if (match != null) {
                tmdbData = data
                matchedVod = match
                break
            } else if (tmdbData == null) {
                tmdbData = data
            }
        }
    }

    if (matchedVod == null && tmdbData != null) {
        matchedVod = findBestVodMatch(tmdbData.title, tmdbData.year, seasonPtr, tmdbData.type)
    }

    if (matchedVod == null) return null

    val vodId = matchedVod.optString("id")
    if (vodId.isEmpty()) return null

    val vodDetails = getVodInfo(vodId, 0) ?: return null
    val resObj = vodDetails.optJSONObject("result") ?: return null
    val collections = resObj.optJSONArray("vod_collection") ?: return null

    var selectedEp: JSONObject? = null
    if (mediaType == "tv" && eNum > 0) {
        for (i in 0 until collections.length()) {
            val ep = collections.optJSONObject(i) ?: continue
            if (ep.optInt("collection") == eNum) {
                selectedEp = ep
                break
            }
        }
    }
    if (selectedEp == null && collections.length() > 0) {
        selectedEp = collections.optJSONObject(0)
    }

    if (selectedEp == null) return null

    val rawURL = selectedEp.optString("raw_url")
    var signedURL = selectedEp.optString("signed_url")
    if (signedURL.isEmpty() && rawURL.isNotEmpty()) {
        signedURL = signVideoUrl(rawURL)
    }

    val finalURL = if (signedURL.isNotEmpty()) signedURL else rawURL
    if (finalURL.isEmpty()) return null

    val title = tmdbData?.title ?: matchedVod.optString("vod_name")
    val backdrop = matchedVod.optString("vod_pic")

    return NewapiStreamResult(
        title = title,
        backdrop = backdrop,
        streamURLs = listOf(finalURL),
        found = true
    )
}

fun httpGet(urlStr: String, headers: Map<String, String> = emptyMap()): String {
    return try {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", UserAgent)
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }

        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
    } catch (e: Exception) {
        ""
    }
}

fun scrapeTurbovidHLS(playCode: String): String {
    var code = playCode.trim()
    code = code.removePrefix("https://turbovidhls.com/t/")
        .removePrefix("http://turbovidhls.com/t/")
        .removePrefix("/t/")
        .trim()

    if (code.isEmpty()) return ""

    val html = httpGet("https://turbovidhls.com/t/$code")
    if (html.isEmpty()) return ""

    if (NativeEngine.isLoaded) {
        val nativeRes = NativeEngine.scrapeTurbovidHLS(html)
        if (nativeRes.isNotEmpty()) return nativeRes
    }

    val unescaped = html.replace("""\/""", "/")
    val regex = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
    return regex.find(unescaped)?.value ?: ""
}

const val VIDEO_JSON_REMOTE_URL = "https://raw.githubusercontent.com/Kineflex/Newflix/refs/heads/main/video.json"
const val VIDEO_JSON_FALLBACK_URL = "https://api.kineflex-netflex.workers.dev/video.json"

@Volatile
private var cachedRemoteVideoJson: String = ""
private var lastVideoJsonFetchTime: Long = 0L
private const val VIDEO_JSON_CACHE_TTL = 300_000L // 5 minutes cache

fun loadVideoJsonData(): String {
    val now = System.currentTimeMillis()

    // 1. Return cached online video.json if valid (5 min TTL)
    if (cachedRemoteVideoJson.isNotEmpty() && (now - lastVideoJsonFetchTime < VIDEO_JSON_CACHE_TTL)) {
        Log.d(TAG, "loadVideoJsonData: Returning cached dataset (${cachedRemoteVideoJson.length} bytes)")
        return cachedRemoteVideoJson
    }

    // 2. Fetch from GitHub Raw / Worker URL (Primary Source)
    for (targetUrl in listOf(VIDEO_JSON_REMOTE_URL, VIDEO_JSON_FALLBACK_URL)) {
        try {
            Log.d(TAG, "loadVideoJsonData: Fetching dataset from $targetUrl")
            val fetched = httpGet(targetUrl)
            if (fetched.isNotBlank() && (fetched.trim().startsWith("[") || fetched.trim().startsWith("{"))) {
                cachedRemoteVideoJson = fetched.trim()
                lastVideoJsonFetchTime = now
                Log.i(TAG, "loadVideoJsonData: Successfully fetched remote video.json from $targetUrl (${cachedRemoteVideoJson.length} bytes)")
                return cachedRemoteVideoJson
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadVideoJsonData: Error fetching $targetUrl: ${e.localizedMessage}", e)
        }
    }

    // 3. Offline fallback: Try local files safely without throwing permission exceptions
    val paths = listOf(
        "video.json",
        "data/video.json",
        "public/video.json",
        "../data/video.json",
        "../public/video.json",
        "/storage/emulated/0/NewFlix/Netflix-Backend/data/video.json",
        "/storage/emulated/0/NewFlix/Netflix-Backend/public/video.json"
    )

    for (p in paths) {
        try {
            val f = File(p)
            if (f.exists() && f.isFile) {
                val txt = f.readText(Charsets.UTF_8).trim()
                if (txt.isNotEmpty()) {
                    Log.i(TAG, "loadVideoJsonData: Loaded dataset from local file '$p'")
                    return txt
                }
            }
        } catch (e: Exception) {
            // Ignore permission / EACCES storage errors
        }
    }

    return cachedRemoteVideoJson
}

fun preloadVideoJsonData() {
    Executors.newSingleThreadExecutor().execute {
        try {
            Log.d("NanoServer", "preloadVideoJsonData: Preloading video.json dataset at app startup...")
            loadVideoJsonData()
        } catch (e: Exception) {
            Log.w("NanoServer", "preloadVideoJsonData error: ${e.localizedMessage}")
        }
    }
}

fun resolveFromVideoJson(id: String, tmdb: String, imdb: String, season: String, episode: String): Triple<String, String, Boolean> {
    val jsonStr = loadVideoJsonData()
    if (jsonStr.isEmpty()) return Triple("", "", false)

    val rawItems = try {
        JSONArray(jsonStr)
    } catch (e: Exception) {
        Log.e(TAG, "resolveFromVideoJson: Invalid JSON format", e)
        return Triple("", "", false)
    }

    val targets = listOf(tmdb, imdb, id).filter { it.isNotEmpty() }
    val sNum = season.toIntOrNull() ?: 0
    val eNum = episode.toIntOrNull() ?: 0

    for (i in 0 until rawItems.length()) {
        val item = rawItems.optJSONObject(i) ?: continue
        val vId = item.optString("id")
        val vTmdb = item.optString("tmdb")
        val vImdb = item.optString("imdb")

        val matched = targets.any { tgt -> tgt == vId || tgt == vTmdb || tgt == vImdb }
        if (!matched) continue

        var title = item.optString("title")
        if (title.isEmpty()) title = item.optString("name")

        if (sNum > 0 && eNum > 0) {
            val seasons = item.optJSONArray("seasons")
            if (seasons != null) {
                for (sIdx in 0 until seasons.length()) {
                    val sMap = seasons.optJSONObject(sIdx) ?: continue
                    if (sMap.optInt("season") == sNum) {
                        val episodes = sMap.optJSONArray("episodes")
                        if (episodes != null) {
                            for (eIdx in 0 until episodes.length()) {
                                val eMap = episodes.optJSONObject(eIdx) ?: continue
                                if (eMap.optInt("episode") == eNum) {
                                    val playCode = eMap.optString("play").ifEmpty { eMap.optString("turbovid") }
                                    if (playCode.isNotEmpty()) {
                                        val m3u8 = scrapeTurbovidHLS(playCode)
                                        if (m3u8.isNotEmpty()) {
                                            Log.i(TAG, "resolveFromVideoJson: Scraped Turbovid S$sNum:E$eNum for '$title': $m3u8")
                                            return Triple(m3u8, "$title S$sNum:E$eNum", true)
                                        }
                                    }
                                    val vUrl = eMap.optString("videoUrl")
                                    if (vUrl.isNotEmpty()) {
                                        Log.i(TAG, "resolveFromVideoJson: Found episode URL for '$title': $vUrl")
                                        return Triple(vUrl, title, true)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val playCode = item.optString("play").ifEmpty { item.optString("turbovid") }
        if (playCode.isNotEmpty()) {
            val m3u8 = scrapeTurbovidHLS(playCode)
            if (m3u8.isNotEmpty()) {
                Log.i(TAG, "resolveFromVideoJson: Scraped Turbovid stream for '$title': $m3u8")
                return Triple(m3u8, title, true)
            }
        }

        val vUrl = item.optString("videoUrl")
        if (vUrl.isNotEmpty()) {
            Log.i(TAG, "resolveFromVideoJson: Found item videoUrl for '$title': $vUrl")
            return Triple(vUrl, title, true)
        }
        val url = item.optString("url")
        if (url.isNotEmpty()) {
            Log.i(TAG, "resolveFromVideoJson: Found item url for '$title': $url")
            return Triple(url, title, true)
        }
    }

    return Triple("", "", false)
}

// ─── Videasy.to Provider (Tier 3) ──────────────────────────────────────────

private val videasyK = longArrayOf(
    1116352408L, 1899447441L, 3049323471L, 3921009573L, 961987163L, 1508970993L,
    2453635748L, 2870763221L, 3624381080L, 310598401L, 607225278L, 1426881987L,
    1925078388L, 2162078206L, 2614888103L, 3248222580L
)

private fun videasyB(e: Long): Boolean = ((e * (e + 1)) and 1L) == 0L

private fun videasyW(eIn: Long): Long {
    var e = eIn and 0xFFFFFFFFL
    e = (e xor (e ushr 16)) and 0xFFFFFFFFL
    e = (e * 2246822507L) and 0xFFFFFFFFL
    e = (e xor (e ushr 13)) and 0xFFFFFFFFL
    e = (e * 3266489909L) and 0xFFFFFFFFL
    return (e xor (e ushr 16)) and 0xFFFFFFFFL
}

private fun videasyV(eIn: Long, tIn: Long): Long {
    val e = eIn and 0xFFFFFFFFL
    val t = (tIn and 31L).toInt()
    if (t == 0) return e
    val left = (e shl t) and 0xFFFFFFFFL
    val right = (e ushr (32 - t)) and 0xFFFFFFFFL
    return (left or right) and 0xFFFFFFFFL
}

private fun decodeBase64UrlBytes(s: String): ByteArray? {
    return try {
        var t = s.replace("-", "+").replace("_", "/")
        while (t.length % 4 != 0) {
            t += "="
        }
        android.util.Base64.decode(t, android.util.Base64.DEFAULT)
    } catch (e: Exception) {
        null
    }
}

private class VideasyState(
    val S: LongArray,
    val HasValue: BooleanArray,
    var Acc: Long
)

fun decryptVideasyPayload(encryptedB64: String, seedStr: String, mediaIdNum: Int): String? {
    if (NativeEngine.isLoaded) {
        val nativeDec = NativeEngine.decryptVideasyPayload(encryptedB64, seedStr, mediaIdNum)
        if (nativeDec.isNotEmpty()) return nativeDec
    }

    val cipherBytes = decodeBase64UrlBytes(encryptedB64) ?: return null

    var t: Long = 2166136261L
    for (i in seedStr.indices) {
        val charByte = seedStr[i].code.toLong() and 0xFFL
        t = ((t xor charByte) * 16777619L) and 0xFFFFFFFFL
    }
    val hashSeed = videasyW(t)

    val mediaIdLong = mediaIdNum.toLong() and 0xFFFFFFFFL
    var a = videasyW(hashSeed xor videasyW(mediaIdLong xor 2654435769L))
    val sBox = LongArray(61)
    val hasVal = BooleanArray(61)

    for (e in 0L..7L) {
        if (videasyB(e)) {
            val tIdx = (a % 61L).toInt()
            a = videasyV(a + 2654435769L, 7L + (7L and e))
            sBox[tIdx] = (a xor videasyW(a)) and 0xFFFFFFFFL
            hasVal[tIdx] = true
            a = videasyW(a + tIdx.toLong())
        } else {
            val idx = e.toInt()
            sBox[idx] = videasyK[(15L and e).toInt()]
            hasVal[idx] = true
        }
    }

    val state = VideasyState(
        S = sBox,
        HasValue = hasVal,
        Acc = videasyW(2779096485L xor a)
    )

    val keystream = ByteArray(cipherBytes.size)
    var stepCounter = 0
    var eIdx = 0

    while (eIdx < cipherBytes.size) {
        val r = state.S
        val oOld = state.Acc
        val n = (oOld % 61L).toInt()
        val d = r[n]
        val sVal = oOld
        stepCounter++
        val stepMult = (stepCounter.toLong() * 2654435769L) and 0xFFFFFFFFL
        val aVal = (d xor stepMult) and 0xFFFFFFFFL

        val iVal: Long = if (state.HasValue[n]) 0xFFFFFFFFL else 0L

        val lValBits = ((sVal xor aVal) or (sVal and aVal and iVal)) and 0xFFFFFFFFL
        val lVal = (videasyV(lValBits + oOld, 31L and n.toLong()) xor videasyV(oOld, 31L and (n.toLong() * 7L))) and 0xFFFFFFFFL
        val oNew = videasyW(lVal + 2654435769L)
        r[n] = oNew
        state.HasValue[n] = true
        state.Acc = oNew

        val tVal = oNew
        keystream[eIdx] = (255L and tVal).toByte()
        eIdx++
        if (eIdx < cipherBytes.size) {
            keystream[eIdx] = ((tVal ushr 8) and 255L).toByte()
            eIdx++
        }
        if (eIdx < cipherBytes.size) {
            keystream[eIdx] = ((tVal ushr 16) and 255L).toByte()
            eIdx++
        }
        if (eIdx < cipherBytes.size) {
            keystream[eIdx] = ((tVal ushr 24) and 255L).toByte()
            eIdx++
        }
    }

    for (i in cipherBytes.indices) {
        cipherBytes[i] = (cipherBytes[i].toInt() xor keystream[i].toInt()).toByte()
    }

    val videasyMagic = byteArrayOf(109, 118, 109, 49) // "mvm1"
    if (cipherBytes.size < videasyMagic.size) {
        return null
    }
    for (i in videasyMagic.indices) {
        if (cipherBytes[i] != videasyMagic[i]) {
            return null
        }
    }

    return String(cipherBytes, videasyMagic.size, cipherBytes.size - videasyMagic.size, Charsets.UTF_8)
}

fun fetchVideasyStream(
    tmdb: String,
    imdb: String,
    mediaType: String,
    season: String,
    episode: String
): Pair<List<String>, Boolean> {
    if (tmdb.isEmpty()) return Pair(emptyList(), false)

    val tmdbIdNum = tmdb.toIntOrNull() ?: return Pair(emptyList(), false)
    val mType = if (mediaType == "tv") "tv" else "movie"
    val sNum = season.toIntOrNull()?.coerceAtLeast(1) ?: 1
    val eNum = episode.toIntOrNull()?.coerceAtLeast(1) ?: 1
    val imdbVal = if (imdb.isNotEmpty()) imdb else "tt1196946"

    val videasyHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Infinix X6739) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.7871.81 Mobile Safari/537.36",
        "Origin" to "https://player.videasy.to",
        "Referer" to "https://player.videasy.to/"
    )

    // 1. Fetch seed
    val seedURL = "https://api.speedracelight.com/seed?mediaId=$tmdbIdNum"
    val seedRespStr = httpGet(seedURL, videasyHeaders)
    if (seedRespStr.isEmpty()) return Pair(emptyList(), false)

    var seedStr = ""
    try {
        val seedJson = JSONObject(seedRespStr)
        seedStr = seedJson.optString("seed", "")
    } catch (e: Exception) {
        seedStr = seedRespStr.trim()
    }
    if (seedStr.isEmpty()) {
        seedStr = seedRespStr.trim()
    }

    // 2. Fetch TMDB details for title and year
    val tmdbDetails = fetchTmdbDetails(tmdb, mType)
    var title = tmdbDetails?.title ?: "The Mentalist"
    var year = tmdbDetails?.year ?: "2008"
    if (title.isEmpty()) title = "The Mentalist"
    if (year.isEmpty()) year = "2008"

    val sourcesURL = if (mType == "tv") {
        "https://api.speedracelight.com/cdn/sources-with-title?title=${URLEncoder.encode(title, "UTF-8")}&mediaType=tv&year=$year&episodeId=$eNum&seasonId=$sNum&tmdbId=$tmdbIdNum&imdbId=${URLEncoder.encode(imdbVal, "UTF-8")}&enc=2&seed=${URLEncoder.encode(seedStr, "UTF-8")}"
    } else {
        "https://api.speedracelight.com/cdn/sources-with-title?title=${URLEncoder.encode(title, "UTF-8")}&mediaType=movie&year=$year&tmdbId=$tmdbIdNum&imdbId=${URLEncoder.encode(imdbVal, "UTF-8")}&enc=2&seed=${URLEncoder.encode(seedStr, "UTF-8")}"
    }

    val encRespStr = httpGet(sourcesURL, videasyHeaders)
    if (encRespStr.isEmpty()) return Pair(emptyList(), false)

    val decryptedJson = decryptVideasyPayload(encRespStr, seedStr, tmdbIdNum) ?: return Pair(emptyList(), false)

    return try {
        val parsedData = JSONObject(decryptedJson)
        val results = mutableListOf<String>()

        val playlist = parsedData.optString("playlist", "")
        if (playlist.isNotEmpty()) {
            results.add(playlist)
        }

        val sourcesArr = parsedData.optJSONArray("sources")
        if (sourcesArr != null) {
            for (i in 0 until sourcesArr.length()) {
                val sObj = sourcesArr.optJSONObject(i) ?: continue
                val u = sObj.optString("url", "")
                if (u.isNotEmpty()) {
                    results.add(u)
                }
            }
        }

        if (results.isNotEmpty()) {
            Pair(results, true)
        } else {
            Pair(emptyList(), false)
        }
    } catch (e: Exception) {
        Pair(emptyList(), false)
    }
}

data class TierResult(
    val tier: Int,
    val sourceName: String,
    val title: String,
    val streamURLs: List<String>,
    val headers: Map<String, String>? = null,
    val found: Boolean = false
)

fun resolveTierStream(
    tier: Int,
    tmdb: String,
    imdb: String,
    mediaType: String,
    season: String,
    episode: String,
    host: String
): TierResult {
    when (tier) {
        1 -> {
            // Tier 1: video.json dataset / Turbovid (Proxy ONLY in Tier 1 for Turbovid .m3u8)
            val (vUrl, vTitle, ok) = resolveFromVideoJson(tmdb, tmdb, imdb, season, episode)
            if (ok && vUrl.isNotEmpty()) {
                val cleanPath = vUrl.substringBefore('?').substringBefore('#')
                val isTurbovidM3u8 = (vUrl.contains("turboviplay") || vUrl.contains("turbovid")) && cleanPath.endsWith(".m3u8", ignoreCase = true)
                val finalUrl = if (isTurbovidM3u8) {
                    "http://$host/proxy?url=${URLEncoder.encode(vUrl, "UTF-8")}"
                } else {
                    vUrl
                }
                return TierResult(
                    tier = 1,
                    sourceName = "video.json",
                    title = vTitle,
                    streamURLs = listOf(finalUrl),
                    headers = null,
                    found = true
                )
            }
        }
        2 -> {
            // Tier 2: Mapple provider (NO PROXY, headers: Origin=https://mapple.rip, Referer=https://mapple.rip/)
            val mappleRes = Mapple.fetchMappleStream(tmdb, mediaType, season, episode)
            if (mappleRes.found && mappleRes.streamUrl.isNotEmpty()) {
                return TierResult(
                    tier = 2,
                    sourceName = "mapple.rip",
                    title = "",
                    streamURLs = listOf(mappleRes.streamUrl),
                    headers = mapOf(
                        "Origin" to "https://mapple.rip",
                        "Referer" to "https://mapple.rip/"
                    ),
                    found = true
                )
            }
        }
        3 -> {
            // Tier 3: Videasy provider (NO PROXY, headers: Origin=https://player.videasy.to, Referer=https://player.videasy.to/)
            val (videasyUrls, ok) = fetchVideasyStream(tmdb, imdb, mediaType, season, episode)
            if (ok && videasyUrls.isNotEmpty()) {
                return TierResult(
                    tier = 3,
                    sourceName = "player.videasy.to",
                    title = "",
                    streamURLs = videasyUrls,
                    headers = mapOf(
                        "Origin" to "https://player.videasy.to",
                        "Referer" to "https://player.videasy.to/"
                    ),
                    found = true
                )
            }
        }
        4 -> {
            // Tier 4: filmin.ajfysu.com (Newapi CineTV Provider / Multi Audio)
            val newapiRes = fetchNewapiStream(tmdb, imdb, mediaType, season, episode)
            if (newapiRes != null && newapiRes.found && newapiRes.streamURLs.isNotEmpty()) {
                return TierResult(
                    tier = 4,
                    sourceName = "filmin.ajfysu.com",
                    title = newapiRes.title,
                    streamURLs = newapiRes.streamURLs,
                    found = true
                )
            }
        }
    }
    return TierResult(tier = tier, sourceName = "", title = "", streamURLs = emptyList(), found = false)
}

private val tierExecutor = Executors.newFixedThreadPool(4)

fun resolveStreamFlow(
    tmdb: String,
    imdb: String,
    mediaType: String,
    season: String,
    episode: String,
    host: String = "localhost:$DefaultPort",
    requestedTier: Int? = null
): TierResult {
    Log.d(TAG, "resolveStreamFlow: tmdb='$tmdb', imdb='$imdb', type='$mediaType', S${season}E${episode}, requestedTier=$requestedTier")

    if (requestedTier != null && requestedTier in 1..4) {
        val res = resolveTierStream(requestedTier, tmdb, imdb, mediaType, season, episode, host)
        if (res.found) return res
        return res
    }

    // Parallel Resolution: Submit Tiers 1..4 simultaneously
    val futures = (1..4).map { t ->
        t to tierExecutor.submit<TierResult> {
            resolveTierStream(t, tmdb, imdb, mediaType, season, episode, host)
        }
    }

    // Return the first tier that succeeds with found == true
    for ((t, future) in futures) {
        try {
            val res = future.get()
            if (res.found && res.streamURLs.isNotEmpty()) {
                Log.i(TAG, "resolveStreamFlow: Parallel tier match found -> Tier $t ('${res.sourceName}')")
                return res
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveStreamFlow: Tier $t error: ${e.localizedMessage}")
        }
    }

    return TierResult(tier = 0, sourceName = "", title = "", streamURLs = emptyList(), found = false)
}

val langMap = mapOf(
    "eng" to "English", "spa" to "Spanish", "fre" to "French", "ger" to "German",
    "deu" to "German", "ita" to "Italian", "por" to "Portuguese", "pob" to "Portuguese (BR)",
    "rus" to "Russian", "zho" to "Chinese", "chi" to "Chinese", "jpn" to "Japanese",
    "kor" to "Korean", "ara" to "Arabic", "hin" to "Hindi", "tam" to "Tamil",
    "tel" to "Telugu", "mal" to "Malayalam", "kan" to "Kannada", "ind" to "Indonesian",
    "vie" to "Vietnamese", "tha" to "Thai", "tur" to "Turkish", "pol" to "Polish"
)

fun fetchSubtitles(tmdbId: String, imdbIdParam: String, mediaType: String, season: String, episode: String): List<SubtitleTrack> {
    // OpenSubtitles integration disabled for now
    return emptyList()
}

fun rewriteM3U8(content: String, baseURL: String, proxyHost: String): String {
    val lines = content.split("\n")
    val uriRegex = Regex("""URI="([^"]+)"""")
    val result = StringBuilder()

    for (line in lines) {
        val lineTrimmed = line.trim()
        if (lineTrimmed.isEmpty()) {
            result.append("\n")
            continue
        }

        if (lineTrimmed.startsWith("#")) {
            val rewritten = uriRegex.replace(line) { match ->
                val sub = match.groupValues[1]
                val resolved = resolveURL(baseURL, sub)
                val proxied = "http://$proxyHost/proxy?url=${URLEncoder.encode(resolved, "UTF-8")}"
                """URI="$proxied""""
            }
            result.append(rewritten).append("\n")
            continue
        }

        val resolved = resolveURL(baseURL, lineTrimmed)
        val proxied = "http://$proxyHost/proxy?url=${URLEncoder.encode(resolved, "UTF-8")}"
        result.append(proxied).append("\n")
    }

    return result.toString()
}

fun resolveURL(baseStr: String, refStr: String): String {
    return try {
        val base = URI(baseStr)
        base.resolve(refStr).toString()
    } catch (e: Exception) {
        refStr
    }
}

// ─── NanoHTTPD Server Implementation ─────────────────────────────────────────

class NanoServer(val serverPort: Int = DefaultPort) : NanoHTTPD(serverPort) {

    private val executor = Executors.newFixedThreadPool(16)

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) {
            val response = newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")
            addCorsHeaders(response)
            return response
        }

        val uri = session.uri
        Log.i(TAG, "--> ${session.method} $uri params=${session.parms}")
        return try {
            when {
                uri == "/api/stream" || uri == "/api.php" -> handleStreamAPI(session)
                uri.startsWith("/api/tier") -> handleTierRouter(session)
                uri == "/proxy" -> handleProxy(session)
                uri == "/api/home" -> handleHome(session)
                uri == "/api/search" -> handleSearch(session)
                uri.startsWith("/api/vod/") -> handleVod(session)
                uri == "/api/topic" -> handleTopic(session)
                uri == "/api/video" -> handleVideo(session)
                uri == "/video.json" || uri == "/data/video.json" -> createJsonResponse(Response.Status.OK, loadVideoJsonData())
                uri.startsWith("/api/tmdb/") -> handleTmdb(session)
                uri.startsWith("/api/media/") -> handleMediaRouter(session)
                uri.startsWith("/player.html") -> handlePlayerHtml(session)
                else -> handleRoot(session)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling request $uri: ${e.localizedMessage}", e)
            val errJson = JSONObject().apply {
                put("status", "error")
                put("status_code", 500)
                put("message", e.message ?: "Server Error")
                put("sources", JSONArray())
                put("subtitles", JSONArray())
            }
            createJsonResponse(Response.Status.INTERNAL_ERROR, errJson.toString())
        }
    }

    private fun addCorsHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS, HEAD")
        response.addHeader("Access-Control-Allow-Headers", "*")
        response.addHeader("Access-Control-Expose-Headers", "*")
    }

    private fun createJsonResponse(status: Response.Status, jsonStr: String): Response {
        Log.i(TAG, "<-- Response status=${status.requestStatus} (${jsonStr.length} bytes)")
        val resp = newFixedLengthResponse(status, "application/json; charset=utf-8", jsonStr)
        addCorsHeaders(resp)
        return resp
    }

    private fun handleTierRouter(session: IHTTPSession): Response {
        val path = session.uri.removePrefix("/api/tier").trim('/')
        val tierNum = path.toIntOrNull() ?: session.parms["tier"]?.toIntOrNull() ?: 1
        return handleStreamAPI(session, forcedTier = tierNum)
    }

    private fun handleStreamAPI(session: IHTTPSession, forcedTier: Int? = null): Response {
        val parms = session.parms
        var mediaType = parms["type"] ?: ""
        val imdb = parms["imdb"] ?: ""
        val tmdb = parms["tmdb"] ?: ""
        var season = parms["season"] ?: ""
        var episode = parms["episode"] ?: ""
        val tierParam = forcedTier ?: parms["tier"]?.toIntOrNull()

        if (mediaType.isEmpty()) {
            mediaType = if (tmdb.isNotEmpty() && season.isEmpty()) "movie" else "tv"
        }
        if (season.isEmpty()) season = "1"
        if (episode.isEmpty()) episode = "1"

        val host = session.headers["host"] ?: "localhost:$serverPort"
        val tierRes = resolveStreamFlow(tmdb, imdb, mediaType, season, episode, host, tierParam)

        if (!tierRes.found || tierRes.streamURLs.isEmpty()) {
            val errJson = JSONObject().apply {
                put("status", "error")
                put("status_code", 404)
                put("message", "No stream found for tmdb='$tmdb', imdb='$imdb'")
                put("media_type", mediaType)
                put("tmdb_id", tmdb)
                put("imdb_id", imdb)
                put("sources", JSONArray())
                put("subtitles", JSONArray())
            }
            return createJsonResponse(Response.Status.NOT_FOUND, errJson.toString())
        }

        val defaultStream = tierRes.streamURLs.first()
        val json = JSONObject().apply {
            put("status", "success")
            put("status_code", 200)
            put("title", tierRes.title)
            put("media_type", mediaType)
            put("tmdb_id", tmdb)
            put("imdb_id", imdb)
            put("season", season.toIntOrNull() ?: 1)
            put("episode", episode.toIntOrNull() ?: 1)
            put("tier", tierRes.tier)
            put("source", tierRes.sourceName)
            put("videoUrl", defaultStream)
            put("default_stream", defaultStream)
            put("url", defaultStream)

            if (tierRes.headers != null && tierRes.headers.isNotEmpty()) {
                val headersObj = JSONObject()
                tierRes.headers.forEach { (k, v) -> headersObj.put(k, v) }
                put("headers", headersObj)
            }

            val sourcesArr = JSONArray()
            tierRes.streamURLs.forEach { s ->
                val cleanPath = s.substringBefore('?').substringBefore('#')
                val sType = if (cleanPath.endsWith(".m3u8", ignoreCase = true) || s.contains("heistotron", ignoreCase = true) || s.contains("/p/")) "hls" else "mp4"
                sourcesArr.put(JSONObject().apply {
                    put("url", s)
                    put("videoUrl", s)
                    put("quality", "auto")
                    put("type", sType)
                })
            }
            put("sources", sourcesArr)
            put("subtitles", JSONArray()) // OpenSubtitles disabled
        }

        return createJsonResponse(Response.Status.OK, json.toString())
    }

    private fun handleProxy(session: IHTTPSession): Response {
        val targetURLStr = session.parms["url"]
            ?: return createJsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "Missing 'url' query parameter").toString())

        return try {
            val targetURL = URL(targetURLStr)
            val conn = targetURL.openConnection() as HttpURLConnection
            conn.requestMethod = session.method.name
            conn.connectTimeout = 15000
            conn.readTimeout = 30000

            conn.setRequestProperty("User-Agent", UserAgent)
            if (targetURL.host.contains("turboviplay") || targetURL.host.contains("turbovid") || targetURLStr.contains("turboviplay") || targetURLStr.contains("turbovid")) {
                conn.setRequestProperty("Referer", "https://turbovidhls.com/")
                conn.setRequestProperty("Origin", "https://turbovidhls.com")
            } else {
                conn.setRequestProperty("Referer", DefaultReferer)
                conn.setRequestProperty("Origin", DefaultOrigin)
            }
            conn.setRequestProperty("Host", targetURL.host)
            conn.setRequestProperty("Accept", "*/*")

            val rangeHeader = session.headers["range"] ?: session.headers["Range"]
            if (rangeHeader != null) conn.setRequestProperty("Range", rangeHeader)

            val code = conn.responseCode
            val contentType = conn.contentType ?: "application/octet-stream"
            val isM3U8 = targetURL.path.endsWith(".m3u8") || contentType.contains("mpegurl", true) || contentType.contains("m3u8", true)

            if (isM3U8 && code == 200) {
                val bodyBytes = conn.inputStream.use { it.readBytes() }
                val host = session.headers["host"] ?: "localhost:$serverPort"
                val rewritten = rewriteM3U8(String(bodyBytes, Charsets.UTF_8), targetURLStr, host)
                val resp = newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", rewritten)
                addCorsHeaders(resp)
                resp
            } else {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val status = Response.Status.lookup(code) ?: Response.Status.OK
                val len = conn.contentLengthLong

                val resp = if (len >= 0) {
                    newFixedLengthResponse(status, contentType, stream, len)
                } else {
                    newChunkedResponse(status, contentType, stream)
                }

                addCorsHeaders(resp)
                conn.headerFields.forEach { (k, vv) ->
                    if (k != null && !k.equals("Content-Length", true) && !k.equals("Content-Encoding", true) && !k.equals("Transfer-Encoding", true)) {
                        resp.addHeader(k, vv.joinToString(", "))
                    }
                }
                resp
            }
        } catch (e: Exception) {
            createJsonResponse(Response.Status.INTERNAL_ERROR, JSONObject().put("error", "Proxy failed: ${e.message}").toString())
        }
    }

    private fun handleMediaRouter(session: IHTTPSession): Response {
        val path = session.uri.removePrefix("/api/media/").trim('/')
        val parts = path.split("/").filter { it.isNotEmpty() }

        if (parts.size >= 3 && parts[0] == "tv" && parts[2] == "seasons") {
            return handleSeasonsAPI(parts[1])
        }

        if (parts.size >= 4 && parts[0] == "tv" && parts[2] == "season" && session.uri.endsWith("/episodes")) {
            val seasonNum = parts[3].toIntOrNull() ?: 1
            return handleEpisodesAPI(parts[1], seasonNum)
        }

        return handleMediaAPI(session, parts)
    }

    private fun handleSeasonsAPI(id: String): Response {
        val tvId = if (id.startsWith("tt")) getTmdbTvId(id) else id.toIntOrNull() ?: 66732
        val tmdbURL = "https://api.themoviedb.org/3/tv/$tvId?api_key=$TMDB_KEY"
        val jsonStr = httpGet(tmdbURL)

        val seasonsArr = JSONArray()
        if (jsonStr.isNotEmpty()) {
            try {
                val detail = JSONObject(jsonStr)
                val seasons = detail.optJSONArray("seasons")
                if (seasons != null) {
                    for (i in 0 until seasons.length()) {
                        val s = seasons.optJSONObject(i) ?: continue
                        val sNum = s.optInt("season_number")
                        if (sNum > 0) {
                            var name = s.optString("name")
                            if (name.isEmpty()) name = "Season $sNum"
                            seasonsArr.put(JSONObject().apply {
                                put("season", sNum)
                                put("name", name)
                                put("episodeCount", s.optInt("episode_count"))
                            })
                        }
                    }
                }
            } catch (e: Exception) {}
        }

        if (seasonsArr.length() == 0) {
            for (i in 1..3) {
                seasonsArr.put(JSONObject().apply {
                    put("season", i)
                    put("name", "Season $i")
                    put("episodeCount", 8)
                })
            }
        }

        return createJsonResponse(Response.Status.OK, seasonsArr.toString())
    }

    private fun handleEpisodesAPI(id: String, seasonNum: Int): Response {
        val tvId = if (id.startsWith("tt")) getTmdbTvId(id) else id.toIntOrNull() ?: 66732
        val tmdbURL = "https://api.themoviedb.org/3/tv/$tvId/season/$seasonNum?api_key=$TMDB_KEY"
        val jsonStr = httpGet(tmdbURL)

        val epArr = JSONArray()
        if (jsonStr.isNotEmpty()) {
            try {
                val detail = JSONObject(jsonStr)
                val episodes = detail.optJSONArray("episodes")
                if (episodes != null) {
                    for (i in 0 until episodes.length()) {
                        val ep = episodes.optJSONObject(i) ?: continue
                        val still = ep.optString("still_path")
                        val thumb = if (still.isNotEmpty()) "https://image.tmdb.org/t/p/w500$still" else ""
                        var dur = ep.optInt("runtime") * 60
                        if (dur <= 0) dur = 2700

                        epArr.put(JSONObject().apply {
                            put("episode", ep.optInt("episode_number"))
                            put("title", ep.optString("name"))
                            put("description", ep.optString("overview"))
                            put("duration", dur)
                            put("thumbnail", thumb)
                        })
                    }
                }
            } catch (e: Exception) {}
        }

        if (epArr.length() == 0) {
            val names = listOf("Chapter One: The Vanishing", "Chapter Two: The Weirdo", "Chapter Three: Holly, Jolly", "Chapter Four: The Body")
            for (i in 1..4) {
                epArr.put(JSONObject().apply {
                    put("episode", i)
                    put("title", names[i - 1])
                    put("description", "Season $seasonNum, Episode $i description.")
                    put("duration", 2700)
                    put("thumbnail", "")
                })
            }
        }

        return createJsonResponse(Response.Status.OK, epArr.toString())
    }

    private fun getTmdbTvId(id: String): Int {
        val findURL = "https://api.themoviedb.org/3/find/$id?api_key=$TMDB_KEY&external_source=imdb_id"
        val jsonStr = httpGet(findURL)
        if (jsonStr.isNotEmpty()) {
            try {
                val res = JSONObject(jsonStr)
                val tvRes = res.optJSONArray("tv_results")
                if (tvRes != null && tvRes.length() > 0) {
                    return tvRes.getJSONObject(0).optInt("id", 66732)
                }
            } catch (e: Exception) {}
        }
        return 66732
    }

    private fun handleMediaAPI(session: IHTTPSession, parts: List<String>): Response {
        try {
            var mediaType = "movie"
            var id = "614945"
            var season = "1"
            var episode = "1"

            if (parts.isNotEmpty()) {
                if (parts[0] == "tv") {
                    mediaType = "tv"
                    if (parts.size >= 2) id = parts[1]
                    if (parts.size >= 3) season = parts[2]
                    if (parts.size >= 4) episode = parts[3]
                } else if (parts[0] == "movie") {
                    mediaType = "movie"
                    if (parts.size >= 2) id = parts[1]
                } else {
                    id = parts[0]
                }
            }

            val imdb = if (id.startsWith("tt")) id else ""
            val tmdb = if (!id.startsWith("tt")) id else ""
            val host = session.headers["host"] ?: "localhost:$serverPort"
            val requestedTier = session.parms["tier"]?.toIntOrNull()

            val tierRes = resolveStreamFlow(tmdb, imdb, mediaType, season, episode, host, requestedTier)
            val sNum = season.toIntOrNull() ?: 1
            val eNum = episode.toIntOrNull() ?: 1

            if (!tierRes.found || tierRes.streamURLs.isEmpty()) {
                val fallbackUrl = "https://vids.st/storage/uploads/video116676/1000205748.mp4"
                val fallbackJson = JSONObject().apply {
                    put("status", "success")
                    put("status_code", 200)
                    put("title", "Sample Media")
                    put("media_type", mediaType)
                    put("tmdb_id", tmdb)
                    put("imdb_id", imdb)
                    put("season", sNum)
                    put("episode", eNum)
                    put("tier", 1)
                    put("source", "fallback")
                    put("videoUrl", fallbackUrl)
                    put("default_stream", fallbackUrl)
                    put("url", fallbackUrl)
                    put("sources", JSONArray().put(JSONObject().apply {
                        put("url", fallbackUrl)
                        put("videoUrl", fallbackUrl)
                        put("quality", "auto")
                        put("type", "mp4")
                    }))
                    put("audioTracks", JSONArray().put(JSONObject().put("id", "en").put("label", "English")))
                    put("subtitles", JSONArray())
                }
                return createJsonResponse(Response.Status.OK, fallbackJson.toString())
            }

            val defaultStream = tierRes.streamURLs.first()
            val json = JSONObject().apply {
                put("status", "success")
                put("status_code", 200)
                put("title", tierRes.title)
                put("media_type", mediaType)
                put("tmdb_id", tmdb)
                put("imdb_id", imdb)
                put("season", sNum)
                put("episode", eNum)
                put("tier", tierRes.tier)
                put("source", tierRes.sourceName)
                put("videoUrl", defaultStream)
                put("default_stream", defaultStream)
                put("url", defaultStream)

                if (tierRes.headers != null && tierRes.headers.isNotEmpty()) {
                    val headersObj = JSONObject()
                    tierRes.headers.forEach { (k, v) -> headersObj.put(k, v) }
                    put("headers", headersObj)
                }

                val sourcesArr = JSONArray()
                tierRes.streamURLs.forEach { s ->
                    val cleanPath = s.substringBefore('?').substringBefore('#')
                    val sType = if (cleanPath.endsWith(".m3u8", ignoreCase = true) || s.contains("heistotron", ignoreCase = true) || s.contains("/p/")) "hls" else "mp4"
                    sourcesArr.put(JSONObject().apply {
                        put("url", s)
                        put("videoUrl", s)
                        put("quality", "auto")
                        put("type", sType)
                    })
                }
                put("sources", sourcesArr)
                put("audioTracks", JSONArray().put(JSONObject().put("id", "en").put("label", "English")))
                put("subtitles", JSONArray()) // OpenSubtitles disabled

                if (mediaType == "tv") {
                    put("next", JSONObject().apply {
                        put("season", sNum)
                        put("episode", eNum + 1)
                        put("title", "Episode ${eNum + 1}")
                    })
                }
            }

            return createJsonResponse(Response.Status.OK, json.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            val fallbackUrl = "https://vids.st/storage/uploads/video116676/1000205748.mp4"
            val fallbackJson = JSONObject().apply {
                put("status", "error")
                put("status_code", 500)
                put("message", e.message ?: "Server Error")
                put("videoUrl", fallbackUrl)
                put("sources", JSONArray().put(JSONObject().apply {
                    put("url", fallbackUrl)
                    put("videoUrl", fallbackUrl)
                    put("type", "mp4")
                }))
                put("subtitles", JSONArray())
            }
            return createJsonResponse(Response.Status.OK, fallbackJson.toString())
        }
    }

    private fun handleHome(session: IHTTPSession): Response {
        val page = session.parms["page"] ?: "1"
        val sections = listOf(
            "1" to "Recommended",
            "7753" to "Trending Now",
            "7828" to "Recently Added",
            "7829" to "Most Popular",
            "7749" to "Top Webseries This Week",
            "7792" to "Anime Hits",
            "7744" to "Top Search"
        )

        val resultsArr = JSONArray()
        sections.forEach { (secId, secName) ->
            val secObj = JSONObject().put("name", secName)
            if (secId == "1") {
                val res = apiPost("/api/search/recommend", mapOf("pn" to page))
                secObj.put("items", res?.opt("result") ?: JSONArray())
            } else {
                val res = apiPost("/api/topic/vod_list", mapOf("topic_id" to secId, "pn" to page))
                val resMap = res?.optJSONObject("result")
                secObj.put("items", resMap?.opt("vod_list") ?: JSONArray())
            }
            resultsArr.put(secObj)
        }

        return createJsonResponse(Response.Status.OK, JSONObject().put("sections", resultsArr).toString())
    }

    private fun handleSearch(session: IHTTPSession): Response {
        val q = session.parms["q"]?.trim() ?: ""
        if (q.isEmpty()) return createJsonResponse(Response.Status.OK, JSONObject().put("result", JSONArray()).toString())
        val page = session.parms["page"] ?: "1"
        val res = apiPost("/api/search/result", mapOf("kw" to q, "pn" to page))
        return createJsonResponse(Response.Status.OK, (res ?: JSONObject()).toString())
    }

    private fun handleVod(session: IHTTPSession): Response {
        val vodId = session.uri.removePrefix("/api/vod/").split("/")[0]
        if (vodId.isEmpty()) return createJsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "vod_id required").toString())
        val audioType = session.parms["audio_type"]?.toIntOrNull() ?: 0
        val res = getVodInfo(vodId, audioType)
        return createJsonResponse(Response.Status.OK, (res ?: JSONObject()).toString())
    }

    private fun handleTopic(session: IHTTPSession): Response {
        val topicId = session.parms["topic_id"] ?: ""
        if (topicId.isEmpty()) return createJsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "topic_id required").toString())
        val pn = session.parms["pn"] ?: "1"
        val res = apiPost("/api/topic/vod_list", mapOf("topic_id" to topicId, "pn" to pn))
        return createJsonResponse(Response.Status.OK, (res ?: JSONObject()).toString())
    }

    private fun handleVideo(session: IHTTPSession): Response {
        val vodId = session.parms["vod_id"] ?: ""
        if (vodId.isEmpty()) return createJsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "vod_id required").toString())

        val audioType = session.parms["audio_type"]?.toIntOrNull() ?: 0
        val collection = session.parms["collection"]?.toIntOrNull() ?: 1

        val res = getVodInfo(vodId, audioType) ?: return createJsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "not found").toString())
        val resObj = res.optJSONObject("result") ?: return createJsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "not found").toString())
        val collections = resObj.optJSONArray("vod_collection") ?: return createJsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "not found").toString())

        var selectedEp: JSONObject? = null
        for (i in 0 until collections.length()) {
            val ep = collections.optJSONObject(i) ?: continue
            if (ep.optInt("collection") == collection) {
                selectedEp = ep
                break
            }
        }

        if (selectedEp == null) return createJsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "episode not found").toString())

        val rawURL = selectedEp.optString("raw_url").ifEmpty { selectedEp.optString("vod_url") }
        val signedURL = selectedEp.optString("signed_url").ifEmpty { signVideoUrl(rawURL) }

        val json = JSONObject().apply {
            put("url", signedURL)
            put("raw", rawURL)
            put("episode", selectedEp)
        }
        return createJsonResponse(Response.Status.OK, json.toString())
    }

    private fun handleTmdb(session: IHTTPSession): Response {
        val parts = session.uri.removePrefix("/api/tmdb/").split("/").filter { it.isNotEmpty() }
        if (parts.isEmpty()) return createJsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "TMDB ID required").toString())

        var reqType = session.parms["type"] ?: ""
        val idStr: String
        var season: Int? = null
        var episode: Int? = null

        if (parts[0] == "movie" || parts[0] == "tv") {
            reqType = parts[0]
            idStr = parts[1]
            if (parts.size >= 4) {
                season = parts[2].toIntOrNull()
                episode = parts[3].toIntOrNull()
            }
        } else {
            idStr = parts[0]
            if (parts.size >= 3) {
                reqType = "tv"
                season = parts[1].toIntOrNull()
                episode = parts[2].toIntOrNull()
            }
        }

        val candidateTypes = if (reqType == "movie" || reqType == "tv") listOf(reqType) else listOf("movie", "tv")
        var tmdbData: TmdbInfo? = null
        var matchedVod: JSONObject? = null

        for (candType in candidateTypes) {
            val data = fetchTmdbDetails(idStr, candType)
            if (data != null) {
                val match = findBestVodMatch(data.title, data.year, season, data.type)
                if (match != null) {
                    tmdbData = data
                    matchedVod = match
                    break
                } else if (tmdbData == null) {
                    tmdbData = data
                }
            }
        }

        if (matchedVod == null) return createJsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "Media not found").toString())

        val vodId = matchedVod.optString("id")
        val audioType = session.parms["audio_type"]?.toIntOrNull() ?: 0
        val vodDetails = getVodInfo(vodId, audioType)

        val json = JSONObject().apply {
            put("tmdb", tmdbData?.let {
                JSONObject().put("id", it.id).put("title", it.title).put("year", it.year).put("type", it.type)
            })
            put("vod_match", matchedVod)
            put("vod_details", vodDetails)
        }
        return createJsonResponse(Response.Status.OK, json.toString())
    }

    private fun handlePlayerHtml(session: IHTTPSession): Response {
        val html = """
            <!DOCTYPE html>
            <html>
            <head><title>Player</title></head>
            <body style="background:#000;color:#fff;display:flex;justify-content:center;align-items:center;height:100vh;">
                <h2>NanoHTTPD Kotlin Player Interface</h2>
            </body>
            </html>
        """.trimIndent()
        val resp = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        addCorsHeaders(resp)
        return resp
    }

    private fun handleRoot(session: IHTTPSession): Response {
        val html = """
            <!DOCTYPE html>
            <html>
            <head><title>NanoHTTPD Kotlin Backend</title></head>
            <body style="font-family:sans-serif;padding:20px;">
                <h1>NanoHTTPD Kotlin Backend Server</h1>
                <p>Status: <b>Running</b> on port $serverPort</p>
                <ul>
                    <li><a href="/api/stream?type=tv&imdb=tt7335184&season=1&episode=1">Stream API</a></li>
                    <li><a href="/api/media/tv/tt7335184/1/1">Media JSON API</a></li>
                    <li><a href="/api/tmdb/movie/634649">TMDB API</a></li>
                </ul>
            </body>
            </html>
        """.trimIndent()
        val resp = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        addCorsHeaders(resp)
        return resp
    }
}
