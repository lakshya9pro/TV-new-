package com.batz.tvlauncher.test

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// ─── Mapple Models ────────────────────────────────────────────────────────────

data class MappleResult(
    val streamUrl: String = "",
    val apiStreamUrl: String = "",
    val found: Boolean = false,
    val error: String? = null
)

// ─── Mapple Engine ────────────────────────────────────────────────────────────

object Mapple {
    private const val TAG = "Mapple"

    fun isFallbackUrl(rawUrl: String): Boolean {
        val fallbackSig = "source.heistotron.uk/p/OTJlYTEzOTZlOTMwODU0NzBmNWI0YjVmNjBmODhjMmQ"
        return rawUrl.contains(fallbackSig)
    }

    /**
     * Loads https://mapple.rip/watch/... in a hidden WebView, intercepts the /api/stream request,
     * extracts the final stream_url from the response, and returns it to play in the player.
     */
    fun fetchMappleStream(
        tmdb: String,
        mediaType: String,
        season: String = "1",
        episode: String = "1",
        context: Context? = null,
        timeoutSeconds: Long = 15
    ): MappleResult {
        val ctx = context
        if (ctx == null) {
            Log.e(TAG, "Context is null, cannot launch hidden WebView for Mapple")
            return MappleResult(found = false, error = "Android Context unavailable")
        }

        Log.d(TAG, "fetchMappleStream: Loading watch page in hidden WebView for tmdb=$tmdb, type=$mediaType, S${season}E${episode}")

        var result = MappleResult(found = false, error = "Timeout waiting for /api/stream")
        val latch = CountDownLatch(1)

        val resolver = MappleWebViewResolver(ctx)
        resolver.resolve(tmdb, mediaType, season, episode, object : MappleWebViewResolver.Callback {
            override fun onSuccess(streamUrl: String, apiStreamUrl: String) {
                result = MappleResult(
                    streamUrl = streamUrl,
                    apiStreamUrl = apiStreamUrl,
                    found = streamUrl.isNotEmpty() && !isFallbackUrl(streamUrl)
                )
                latch.countDown()
            }

            override fun onError(error: String) {
                result = MappleResult(found = false, error = error)
                latch.countDown()
            }
        })

        try {
            latch.await(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "MappleWebView latch timeout: ${e.message}")
        }

        return result
    }
}

// ─── Hidden WebView Resolver Component ────────────────────────────────────────

class MappleWebViewResolver(private val context: Context) {

    private var webView: WebView? = null

    interface Callback {
        fun onSuccess(streamUrl: String, apiStreamUrl: String)
        fun onError(error: String)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun resolve(
        tmdbId: String,
        mediaType: String,
        season: String = "1",
        episode: String = "1",
        callback: Callback
    ) {
        Handler(Looper.getMainLooper()).post {
            try {
                val mType = if (mediaType == "tv") "tv" else "movie"
                val sNum = season.toIntOrNull() ?: 1
                val eNum = episode.toIntOrNull() ?: 1

                val pageURL = if (mType == "tv") {
                    "https://mapple.rip/watch/tv/$tmdbId/$sNum/$eNum"
                } else {
                    "https://mapple.rip/watch/movie/$tmdbId"
                }

                Log.i("MappleWebView", "Opening hidden WebView for: $pageURL")

                val wv = WebView(context.applicationContext)
                webView = wv

                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                }

                var resolved = false

                wv.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onStreamFound(streamUrl: String, apiStreamUrl: String) {
                        Handler(Looper.getMainLooper()).post {
                            if (!resolved) {
                                resolved = true
                                Log.i("MappleWebView", "Stream URL captured via JS Bridge: $streamUrl (API: $apiStreamUrl)")
                                destroyWebView()
                                callback.onSuccess(streamUrl, apiStreamUrl)
                            }
                        }
                    }

                    @JavascriptInterface
                    fun onError(err: String) {
                        Handler(Looper.getMainLooper()).post {
                            if (!resolved) {
                                resolved = true
                                destroyWebView()
                                callback.onError(err)
                            }
                        }
                    }
                }, "AndroidMappleBridge")

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        injectNetworkInterceptorScript(view)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        injectNetworkInterceptorScript(view)
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        if (reqUrl.contains("/api/stream?")) {
                            Log.i("MappleWebView", "Intercepted network request to /api/stream: $reqUrl")
                            if (!resolved) {
                                fetchApiStreamInBackground(reqUrl) { streamUrl ->
                                    if (!resolved && streamUrl.isNotEmpty()) {
                                        resolved = true
                                        Handler(Looper.getMainLooper()).post {
                                            destroyWebView()
                                            callback.onSuccess(streamUrl, reqUrl)
                                        }
                                    }
                                }
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                wv.loadUrl(pageURL)
            } catch (e: Exception) {
                callback.onError("WebView creation error: ${e.message}")
            }
        }
    }

    private fun fetchApiStreamInBackground(
        apiUrl: String,
        onSuccess: (String) -> Unit
    ) {
        Thread {
            try {
                val conn = URL(apiUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                conn.setRequestProperty("Referer", "https://mapple.rip/")

                val cookies = CookieManager.getInstance().getCookie("https://mapple.rip")
                if (!cookies.isNullOrEmpty()) {
                    conn.setRequestProperty("Cookie", cookies)
                }

                val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                val jsonStr = stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""

                if (jsonStr.isNotEmpty()) {
                    val json = JSONObject(jsonStr)
                    val success = json.optBoolean("success", false)
                    val dataObj = json.optJSONObject("data")
                    val streamUrl = dataObj?.optString("stream_url", "") ?: ""

                    if (success && streamUrl.isNotEmpty()) {
                        Log.i("MappleWebView", "Successfully extracted stream_url from /api/stream: $streamUrl")
                        onSuccess(streamUrl)
                    }
                }
            } catch (e: Exception) {
                Log.e("MappleWebView", "Error fetching intercepted /api/stream: ${e.message}")
            }
        }.start()
    }

    private fun destroyWebView() {
        try {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun injectNetworkInterceptorScript(view: WebView?) {
        val script = """
            (function() {
                if (window.__mappleInterceptorInjected) return;
                window.__mappleInterceptorInjected = true;

                // Intercept fetch() calls to /api/stream
                const origFetch = window.fetch;
                window.fetch = async function(...args) {
                    const url = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url ? args[0].url : '');
                    const response = await origFetch.apply(this, args);
                    if (url && url.indexOf('/api/stream') !== -1) {
                        try {
                            const clone = response.clone();
                            clone.json().then(data => {
                                if (data && data.success && data.data && data.data.stream_url) {
                                    window.AndroidMappleBridge.onStreamFound(data.data.stream_url, url);
                                }
                            }).catch(function(e) {});
                        } catch(e) {}
                    }
                    return response;
                };

                // Intercept XMLHttpRequest calls to /api/stream
                const origOpen = XMLHttpRequest.prototype.open;
                const origSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function(method, url) {
                    this._url = url;
                    return origOpen.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function() {
                    this.addEventListener('load', function() {
                        if (this._url && this._url.indexOf('/api/stream') !== -1) {
                            try {
                                const data = JSON.parse(this.responseText);
                                if (data && data.success && data.data && data.data.stream_url) {
                                    window.AndroidMappleBridge.onStreamFound(data.data.stream_url, this._url);
                                }
                            } catch(e) {}
                        }
                    });
                    return origSend.apply(this, arguments);
                };
            })();
        """.trimIndent()

        view?.evaluateJavascript(script, null)
    }
}
