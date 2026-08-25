package com.batz.tvlauncher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.batz.tvlauncher.databinding.ActivityPlayerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ExoPlayerURL"

        const val EXTRA_MEDIA_URL = "extra_media_url"
        const val EXTRA_MEDIA_TITLE = "extra_media_title"
        const val EXTRA_IS_LIVE = "extra_is_live"

        fun start(context: Context, mediaUrl: String, title: String = "Live Video", isLive: Boolean = false) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_MEDIA_URL, mediaUrl)
                putExtra(EXTRA_MEDIA_TITLE, title)
                putExtra(EXTRA_IS_LIVE, isLive)
            }
            context.startActivity(intent)
        }
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var mediaUrl: String = ""
    private var mediaTitle: String = ""
    private var isLiveStream: Boolean = false

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Fullscreen & Keep Screen On
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaUrl = intent.getStringExtra(EXTRA_MEDIA_URL) ?: ""
        mediaTitle = intent.getStringExtra(EXTRA_MEDIA_TITLE) ?: "Live Video"
        isLiveStream = intent.getBooleanExtra(EXTRA_IS_LIVE, false)

        binding.playerTitleText.text = mediaTitle
        binding.playerUrlText.text = "URL: $mediaUrl"
        binding.liveBadge.visibility = if (isLiveStream) View.VISIBLE else View.GONE
        binding.playerBackButton.setOnClickListener { finish() }

        // LOG PLAYING MEDIA DETAILS TO LOGCAT
        Log.i(TAG, "==================================================")
        Log.i(TAG, "► PLAYING MEDIA TITLE: $mediaTitle")
        Log.i(TAG, "► PLAYING STREAM URL: $mediaUrl")
        Log.i(TAG, "► IS LIVE STREAM: $isLiveStream")
        Log.i(TAG, "==================================================")

        if (mediaUrl.isBlank()) {
            Log.e(TAG, "Error: Empty or null media URL passed to PlayerActivity")
            Toast.makeText(this, "Error: No media URL provided", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        initializePlayer()
    }

    private fun initializePlayer() {
        kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
            var targetStreamUrl = mediaUrl
            val customHeaders = mutableMapOf<String, String>()

            // Query local NanoServer router if mediaUrl starts with http://127.0.0.1:1937 or is a backend media ID
            if (mediaUrl.contains("127.0.0.1:1937") || (!mediaUrl.startsWith("http") && mediaUrl.isNotBlank())) {
                try {
                    val resolved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val itemId = if (mediaUrl.contains("url=")) {
                            mediaUrl.substringAfter("url=").substringBefore("&")
                        } else if (mediaUrl.contains("tmdb=")) {
                            mediaUrl.substringAfter("tmdb=").substringBefore("&")
                        } else {
                            mediaUrl
                        }
                        val requestUrl = if (mediaUrl.startsWith("http://127.0.0.1:1937/api/")) {
                            mediaUrl
                        } else {
                            "http://127.0.0.1:1937/api/stream?tmdb=$itemId"
                        }
                        Log.i(TAG, "Requesting stream resolution from NanoServer: $requestUrl")
                        val conn = java.net.URL(requestUrl).openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 10000
                        conn.readTimeout = 10000
                        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                        val jsonStr = stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
                        if (jsonStr.isNotEmpty()) {
                            val json = org.json.JSONObject(jsonStr)
                            val videoUrl = json.optString("videoUrl", json.optString("default_stream", json.optString("url", "")))
                            val headersObj = json.optJSONObject("headers")
                            headersObj?.keys()?.forEach { key ->
                                customHeaders[key] = headersObj.getString(key)
                            }
                            videoUrl
                        } else ""
                    }
                    if (resolved.isNotBlank() && !resolved.contains("127.0.0.1:1937/api/")) {
                        targetStreamUrl = resolved
                        Log.i(TAG, "Successfully resolved stream via NanoServer router: $targetStreamUrl")
                    } else {
                        Log.w(TAG, "NanoServer returned empty or recursive URL: $resolved")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "NanoServer resolution fallback to direct mediaUrl: ${e.message}")
                }
            }

            // Ensure targetStreamUrl is a playable stream, not an API endpoint
            if (targetStreamUrl.contains("127.0.0.1:1937/api/")) {
                targetStreamUrl = "https://amg00862-amg00862c6-amgplt0173.playout.now3.amagi.tv/playlist/amg00862-amg00862c6-amgplt0173/playlist.m3u8"
            }
            binding.playerUrlText.text = "URL: $targetStreamUrl"

            val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            if (targetStreamUrl.contains("turboviplay") || targetStreamUrl.contains("turbovid") || targetStreamUrl.contains("nextgencloudfabric")) {
                customHeaders["Referer"] = "https://turbovidhls.com/"
                customHeaders["Origin"] = "https://turbovidhls.com"
            } else if (targetStreamUrl.contains("heistotron") || targetStreamUrl.contains("mapple")) {
                customHeaders["Referer"] = "https://mapple.rip/"
                customHeaders["Origin"] = "https://mapple.rip"
            }

            val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(30_000)
                .setUserAgent(userAgent)
                .setDefaultRequestProperties(customHeaders)

            val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(this@PlayerActivity, httpDataSourceFactory)
            val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)

            Log.d(TAG, "Initializing ExoPlayer for URL: $targetStreamUrl with headers: $customHeaders")

            try {
                val exoPlayer = ExoPlayer.Builder(this@PlayerActivity)
                    .setMediaSourceFactory(mediaSourceFactory)
                    .build().apply {
                        val mediaItem = MediaItem.fromUri(Uri.parse(targetStreamUrl))
                        setMediaItem(mediaItem)
                        prepare()
                        playWhenReady = true
                        
                        addListener(object : Player.Listener {
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                val stateString = when (playbackState) {
                                    Player.STATE_BUFFERING -> "STATE_BUFFERING"
                                    Player.STATE_READY -> "STATE_READY"
                                    Player.STATE_ENDED -> "STATE_ENDED"
                                    Player.STATE_IDLE -> "STATE_IDLE"
                                    else -> "UNKNOWN"
                                }
                                Log.d(TAG, "Playback State Changed: $stateString | URL: $targetStreamUrl")
                            }

                            override fun onIsPlayingChanged(isPlaying: Boolean) {
                                Log.d(TAG, "Is Playing: $isPlaying | Title: $mediaTitle")
                            }

                            override fun onPlayerError(error: PlaybackException) {
                                Log.e(TAG, "Playback Error for URL: $targetStreamUrl | Error: ${error.message}", error)
                                Toast.makeText(
                                    this@PlayerActivity,
                                    "Playback Error: ${error.localizedMessage}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        })
                    }

                player = exoPlayer
                binding.playerView.player = exoPlayer
            } catch (e: Exception) {
                Log.e(TAG, "Exception initializing ExoPlayer for URL: $targetStreamUrl", e)
                Toast.makeText(this@PlayerActivity, "Failed to start player: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        player?.let { p ->
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (p.isPlaying) {
                        Log.d(TAG, "User paused stream: $mediaUrl")
                        p.pause()
                    } else {
                        Log.d(TAG, "User resumed stream: $mediaUrl")
                        p.play()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    val newPos = (p.currentPosition - 10000).coerceAtLeast(0)
                    Log.d(TAG, "Seeking backward to ${newPos}ms")
                    p.seekTo(newPos)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    val newPos = (p.currentPosition + 10000).coerceAtMost(p.duration)
                    Log.d(TAG, "Seeking forward to ${newPos}ms")
                    p.seekTo(newPos)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        if (player == null && mediaUrl.isNotBlank()) {
            initializePlayer()
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "PlayerActivity stopped. Releasing player for URL: $mediaUrl")
        releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "PlayerActivity destroyed for URL: $mediaUrl")
        releasePlayer()
    }

    private fun releasePlayer() {
        player?.let {
            it.release()
            player = null
        }
    }
}
