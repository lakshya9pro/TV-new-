package com.batz.tvlauncher.proxy

import android.util.Log
import java.net.URI
import java.net.URLEncoder

/**
 * High-Performance Native C++ HLS Proxy Bridge for TVLauncher.
 */
object ProxyHandler {
    private const val TAG = "ProxyHandler"
    const val DEFAULT_PROXY_PORT = 1658

    @Volatile
    var isNativeLoaded: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("native_proxy")
            isNativeLoaded = true
            Log.i(TAG, "Successfully loaded native_proxy library in TVLauncher")
        } catch (e: Throwable) {
            Log.w(TAG, "Native proxy library not available: ${e.message}")
            isNativeLoaded = false
        }
    }

    // ─── Native JNI Declarations ─────────────────────────────────────────────

    private external fun nativeStartProxy(port: Int): Int
    private external fun nativeStopProxy()
    private external fun nativeIsRunning(): Boolean
    private external fun nativeGetPort(): Int
    private external fun nativeRewriteM3U8(content: String, baseUrl: String, proxyHost: String): String

    // ─── Public Proxy Controls ────────────────────────────────────────────────

    fun start(port: Int = DEFAULT_PROXY_PORT): Boolean {
        if (!isNativeLoaded) return false
        val assignedPort = nativeStartProxy(port)
        val success = assignedPort > 0
        if (success) {
            Log.i(TAG, "C++ Native Proxy started on port $assignedPort")
        } else {
            Log.e(TAG, "Failed to start C++ Native Proxy on port $port")
        }
        return success
    }

    fun stop() {
        if (isNativeLoaded) {
            nativeStopProxy()
            Log.i(TAG, "C++ Native Proxy stopped")
        }
    }

    fun isRunning(): Boolean {
        return isNativeLoaded && nativeIsRunning()
    }

    fun getPort(): Int {
        return if (isRunning()) nativeGetPort() else DEFAULT_PROXY_PORT
    }

    fun getProxyUrl(targetUrl: String, host: String = "127.0.0.1:${getPort()}"): String {
        return "http://$host/proxy?url=${URLEncoder.encode(targetUrl, "UTF-8")}"
    }

    fun rewriteM3U8(content: String, baseURL: String, proxyHost: String): String {
        if (isNativeLoaded) {
            try {
                val nativeRes = nativeRewriteM3U8(content, baseURL, proxyHost)
                if (nativeRes.isNotEmpty()) return nativeRes
            } catch (e: Exception) {
                Log.w(TAG, "nativeRewriteM3U8 error: ${e.message}")
            }
        }

        // Fallback Kotlin implementation
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
}
