package com.batz.tvlauncher.test

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.mybasic.activity.adapter.EpisodeAdapter
import com.example.mybasic.activity.api.ApiClient
import com.example.mybasic.activity.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ExoPlayerActivity"

        const val EXTRA_MEDIA_ID     = "extra_media_id"
        const val EXTRA_TMDB_ID      = "extra_tmdb_id"
        const val EXTRA_MEDIA_TYPE   = "extra_media_type"   // "tv" | "movie"
        const val EXTRA_SEASON       = "extra_season"
        const val EXTRA_EPISODE      = "extra_episode"
        const val EXTRA_TITLE        = "extra_title"
        const val EXTRA_VIDEO_URL    = "extra_video_url"    // optional direct URL

        // ─── Put your TMDB API key here ───
        const val TMDB_API_KEY = "e6333b32409e02a4a6eba6fb7ff866bb"
        const val TMDB_IMG_BASE = "https://image.tmdb.org/t/p/w300"

        private const val SEEK_MS = 10_000L
        private const val CONTROLS_TIMEOUT = 4000L
        private const val DOUBLE_TAP_MS = 300L

        fun start(
            context: Context,
            mediaId: String,
            tmdbId: Int,
            type: String = "tv",
            season: Int = 1,
            episode: Int = 1,
            title: String? = null
        ) {
            context.startActivity(Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_MEDIA_ID, mediaId)
                putExtra(EXTRA_TMDB_ID, tmdbId)
                putExtra(EXTRA_MEDIA_TYPE, type)
                putExtra(EXTRA_SEASON, season)
                putExtra(EXTRA_EPISODE, episode)
                putExtra(EXTRA_TITLE, title)
            })
        }

        fun start(context: Context, videoUrl: String, title: String? = null) {
            context.startActivity(Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_VIDEO_URL, videoUrl)
                putExtra(EXTRA_TITLE, title)
            })
        }
    }

    // ── State ────────────────────────────────────────────────────────────────
    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var mediaId: String = ""
    private var tmdbId: Int = 0
    private var mediaType: String = "tv"
    private var currentSeason: Int = 1
    private var currentEpisode: Int = 1
    private var currentTitle: String? = null
    private var mediaResponse: MediaResponse? = null

    // Seasons / episodes cache
    private var seasonsCache: List<SeasonItem>? = null
    private val episodesCache = mutableMapOf<Int, List<EpisodeItem>>()
    private lateinit var episodeAdapter: EpisodeAdapter

    // Controls
    private val handler = Handler(Looper.getMainLooper())
    private var controlsVisible = true
    private var isLocked = false
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private val hideControlsRunnable = Runnable { hideControls() }
    private val seekBarUpdater = object : Runnable {
        override fun run() {
            updateSeekBar()
            handler.postDelayed(this, 500)
        }
    }

    // Playback source selection (server/tier, quality, display fit)
    private var selectedTier: Int? = null
    private var selectedQualityHeight: Int? = null   // null = Auto/adaptive
    private var currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private val tierNames = linkedMapOf(
        1 to "Fast",
        2 to "kineflex 4k (Original Audio)",
        3 to "4K (original audio)",
        4 to "Multi Audio (Reliable)"
    )

    // ── Views ────────────────────────────────────────────────────────────────
    private lateinit var playerView: PlayerView
    private lateinit var loadingOverlay: View
    private lateinit var gestureLayer: View
    private lateinit var controlsOverlay: View
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvTimeRemaining: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnEpisodes: View
    private lateinit var btnNextEp: View
    private lateinit var episodeSheet: View
    private lateinit var rvEpisodes: RecyclerView
    private lateinit var tvSeasonLabel: TextView
    private lateinit var lockOverlay: View
    private lateinit var tvSpeedLabel: TextView
    private lateinit var toastText: TextView
    private lateinit var btnSettings: ImageButton
    private lateinit var tvServerLabel: TextView
    private lateinit var seekBrightness: SeekBar

    // ═════════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ═════════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goFullscreenImmersive()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        setContentView(R.layout.activity_player)

        // Parse intent extras
        mediaId = intent.getStringExtra(EXTRA_MEDIA_ID) ?: ""
        tmdbId = intent.getIntExtra(EXTRA_TMDB_ID, 0)
        mediaType = intent.getStringExtra(EXTRA_MEDIA_TYPE) ?: "tv"
        currentSeason = intent.getIntExtra(EXTRA_SEASON, 1)
        currentEpisode = intent.getIntExtra(EXTRA_EPISODE, 1)
        currentTitle = intent.getStringExtra(EXTRA_TITLE)

        Log.d(
            TAG,
            "onCreate: mediaId='$mediaId', tmdbId=$tmdbId, type='$mediaType', season=$currentSeason, episode=$currentEpisode, title='$currentTitle', directUrl='${intent.getStringExtra(EXTRA_VIDEO_URL)}'"
        )

        bindViews()
        setupGestures()
        setupControls()
        setupEpisodes()
        initPlayer()
        loadMedia()
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart")
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
        goFullscreenImmersive()
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause")
        player?.playWhenReady = false
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop")
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: releasing ExoPlayer")
        player?.release()
        player = null
        handler.removeCallbacksAndMessages(null)
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  FULLSCREEN
    // ═════════════════════════════════════════════════════════════════════════

    private fun goFullscreenImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  VIEW BINDING
    // ═════════════════════════════════════════════════════════════════════════

    private fun bindViews() {
        playerView = findViewById(R.id.playerView)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        gestureLayer = findViewById(R.id.gestureLayer)
        controlsOverlay = findViewById(R.id.controlsOverlay)
        tvTitle = findViewById(R.id.tvTitle)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        tvTimeRemaining = findViewById(R.id.tvTimeRemaining)
        seekBar = findViewById(R.id.seekBar)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnEpisodes = findViewById(R.id.btnEpisodes)
        btnNextEp = findViewById(R.id.btnNextEp)
        episodeSheet = findViewById(R.id.episodeSheet)
        rvEpisodes = findViewById(R.id.rvEpisodes)
        tvSeasonLabel = findViewById(R.id.tvSeasonLabel)
        lockOverlay = findViewById(R.id.lockOverlay)
        tvSpeedLabel = findViewById(R.id.tvSpeedLabel)
        toastText = findViewById(R.id.toastText)
        btnSettings = findViewById(R.id.btnSettings)
        tvServerLabel = findViewById(R.id.tvServerLabel)
        seekBrightness = findViewById(R.id.seekBrightness)
        updateServerLabelUi()
        initBrightnessSlider()
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PLAYER INIT
    // ═════════════════════════════════════════════════════════════════════════

    private fun initPlayer() {
        Log.d(TAG, "initPlayer: building ExoPlayer instance")
        trackSelector = DefaultTrackSelector(this).apply {
            // NOTE: previously capped with .setMaxVideoSizeSd(), which silently blocked HD/4K
            // playback no matter what the source offered. Unlocked here so Auto can adapt all
            // the way up to the stream's real maximum, and the new Quality menu has something
            // to offer beyond SD.
            setParameters(
                buildUponParameters()
                    .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
                    .setForceHighestSupportedBitrate(false)
            )
        }
        val extractorsFactory = DefaultExtractorsFactory().apply {
            setConstantBitrateSeekingEnabled(true)
        }
        val defaultMediaSourceFactory = DefaultMediaSourceFactory(this, extractorsFactory)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000, // minBufferMs
                50_000, // maxBufferMs
                500,    // bufferForPlaybackMs (Instant start after 0.5s buffer)
                1_000   // bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(defaultMediaSourceFactory)
            .setTrackSelector(trackSelector!!)
            .setLoadControl(loadControl)
            .build()
            .also { exo ->
                playerView.player = exo
                playerView.resizeMode = currentResizeMode
                exo.playWhenReady = true

                exo.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        val stateStr = when (state) {
                            Player.STATE_BUFFERING -> "BUFFERING"
                            Player.STATE_READY -> "READY"
                            Player.STATE_ENDED -> "ENDED"
                            Player.STATE_IDLE -> "IDLE"
                            else -> "UNKNOWN($state)"
                        }
                        Log.i(TAG, "Player State Changed: $stateStr")

                        when (state) {
                            Player.STATE_BUFFERING -> loadingOverlay.visibility = View.VISIBLE
                            Player.STATE_READY -> {
                                loadingOverlay.visibility = View.GONE
                                handler.post(seekBarUpdater)
                            }
                            Player.STATE_ENDED -> onVideoEnded()
                            Player.STATE_IDLE -> loadingOverlay.visibility = View.GONE
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        Log.i(TAG, "Playback IsPlaying Changed: $isPlaying")
                        btnPlayPause.setImageResource(
                            if (isPlaying) R.drawable.ic_pause_large else R.drawable.ic_play_large
                        )
                        if (isPlaying) resetControlsTimer() else showControls()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "ExoPlayer Error [${error.errorCodeName}]: ${error.localizedMessage}", error)
                        loadingOverlay.visibility = View.GONE
                        showToast("Playback error: ${error.localizedMessage}")
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        Log.d(TAG, "onTracksChanged: ${tracks.groups.size} track groups available")
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        Log.i(TAG, "Video resolution: ${videoSize.width}x${videoSize.height}")
                    }
                })
            }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LOAD MEDIA (from localhost:1657 API)
    // ═════════════════════════════════════════════════════════════════════════

    private fun loadMedia() {
        loadingOverlay.visibility = View.VISIBLE
        Log.d(TAG, "loadMedia: requesting type=$mediaType, mediaId='$mediaId', S${currentSeason}E${currentEpisode}, tier=$selectedTier")

        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    if (mediaType == "movie") {
                        ApiClient.mediaApi.getMovieMedia(mediaId, selectedTier)
                    } else {
                        ApiClient.mediaApi.getTvMedia(mediaId, currentSeason, currentEpisode, selectedTier)
                    }
                }

                Log.i(TAG, "loadMedia: API response status code = ${response.code()}")
                if (response.isSuccessful && response.body() != null) {
                    mediaResponse = response.body()
                    Log.d(TAG, "loadMedia: API Response received: $mediaResponse")
                    applyMedia(mediaResponse!!)
                } else {
                    // Try direct URL from intent
                    val directUrl = intent.getStringExtra(EXTRA_VIDEO_URL)
                    if (!directUrl.isNullOrBlank()) {
                        Log.w(TAG, "loadMedia: API returned error ${response.code()}, falling back to direct URL: $directUrl")
                        playUrl(directUrl, "hls")
                        updateTitleBar()
                    } else {
                        Log.e(TAG, "loadMedia: Failed to load media (${response.code()}) and no fallback direct URL")
                        showToast("Failed to load media (${response.code()})")
                        loadingOverlay.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadMedia: Exception during media API fetch: ${e.localizedMessage}", e)
                // Network error — try direct URL fallback
                val directUrl = intent.getStringExtra(EXTRA_VIDEO_URL)
                if (!directUrl.isNullOrBlank()) {
                    Log.w(TAG, "loadMedia: Exception occurred, falling back to direct URL: $directUrl")
                    playUrl(directUrl, "hls")
                    updateTitleBar()
                } else {
                    showToast("Network error: ${e.localizedMessage}")
                    loadingOverlay.visibility = View.GONE
                }
            }
        }
    }

    private fun applyMedia(media: MediaResponse) {
        // Play video
        val videoUrl = media.resolveVideoUrl()
        if (videoUrl.isNullOrBlank()) {
            Log.e(TAG, "applyMedia: No playable video URL found in response")
            showToast("No playable video URL")
            loadingOverlay.visibility = View.GONE
            return
        }

        val sourceType = media.resolveSourceType()
        Log.i(TAG, "applyMedia: Playing resolved videoUrl='$videoUrl', sourceType='$sourceType', headers=${media.headers}")
        playUrl(videoUrl, sourceType, media.subtitles, media.headers)

        // Update chrome
        updateTitleBar()
        applyTypeVisibility()
    }

    private fun playUrl(
        url: String,
        type: String,
        subtitles: List<Subtitle>? = null,
        headers: Map<String, String>? = null
    ) {
        Log.i(TAG, "playUrl: Preparing media source for URL='$url', type='$type', subtitlesCount=${subtitles?.size ?: 0}")
        val exo = player ?: run {
            Log.e(TAG, "playUrl: ExoPlayer instance is null!")
            return
        }

        // Any manual quality lock from a previous stream won't line up with this one's
        // renditions, so drop back to Auto whenever the source changes.
        selectedQualityHeight = null

        val userAgent = if (url.contains("googleapis.com") || url.contains("commondatastorage")) {
            "ExoPlayerMedia3/1.2.0 (Linux;Android 10)"
        } else {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }

        val defaultRequestHeaders = mutableMapOf<String, String>()
        if (headers != null && headers.isNotEmpty()) {
            defaultRequestHeaders.putAll(headers)
        }
        if (url.contains("turboviplay") || url.contains("turbovid") || url.contains("nextgencloudfabric") || url.contains("e6r4r1")) {
            defaultRequestHeaders["Referer"] = "https://turbovidhls.com/"
            defaultRequestHeaders["Origin"] = "https://turbovidhls.com"
        } else if (url.contains("heistotron") || url.contains("mapple")) {
            defaultRequestHeaders["Referer"] = "https://mapple.rip/"
            defaultRequestHeaders["Origin"] = "https://mapple.rip"
        }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(defaultRequestHeaders)

        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)

        val subtitleConfigs = subtitles?.mapIndexedNotNull { i, sub ->
            val subUrl = sub.url
            if (subUrl.isNullOrBlank()) return@mapIndexedNotNull null
            Log.d(TAG, "playUrl: Attaching subtitle track #${i + 1} -> label='${sub.label}', lang='${sub.lang}', url='$subUrl'")
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                .setMimeType(
                    if (subUrl.contains(".vtt", ignoreCase = true)) MimeTypes.TEXT_VTT
                    else MimeTypes.APPLICATION_SUBRIP
                )
                .setLanguage(sub.lang ?: "en")
                .setLabel(sub.label ?: "Subtitle ${i + 1}")
                .setSelectionFlags(if (sub.isDefault || i == 0) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        } ?: emptyList()

        val isHls = type.equals("hls", true) || url.contains("m3u8", ignoreCase = true) || url.contains("hls", ignoreCase = true) || url.contains("heistotron", ignoreCase = true) || url.contains("/p/")
        val isDash = type.equals("dash", true) || url.contains("mpd", ignoreCase = true)

        val mimeType = when {
            isHls -> MimeTypes.APPLICATION_M3U8
            isDash -> MimeTypes.APPLICATION_MPD
            else -> null
        }

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setSubtitleConfigurations(subtitleConfigs)
            .apply { if (mimeType != null) setMimeType(mimeType) }
            .build()

        val extractorsFactory = DefaultExtractorsFactory().apply {
            setConstantBitrateSeekingEnabled(true)
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)

        val finalMediaSource = mediaSourceFactory.createMediaSource(mediaItem)

        exo.setMediaSource(finalMediaSource)
        exo.prepare()
        exo.playWhenReady = true
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  TITLE BAR
    // ═════════════════════════════════════════════════════════════════════════

    private fun updateTitleBar() {
        if (mediaType == "tv") {
            val label = "S${currentSeason}:E${currentEpisode}"
            val epTitle = mediaResponse?.episodeTitle
            tvTitle.text = if (epTitle != null) "$label \"$epTitle\"" else label
            val series = mediaResponse?.seriesTitle ?: currentTitle ?: ""
            if (series.isNotBlank()) {
                tvSubtitle.text = series
                tvSubtitle.visibility = View.VISIBLE
            }
        } else {
            tvTitle.text = mediaResponse?.title ?: currentTitle ?: "Playing"
            tvSubtitle.visibility = View.GONE
        }
    }

    private fun applyTypeVisibility() {
        val isTv = mediaType == "tv"
        btnEpisodes.visibility = if (isTv) View.VISIBLE else View.GONE
        btnNextEp.visibility = if (isTv) View.VISIBLE else View.GONE
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  CONTROLS
    // ═════════════════════════════════════════════════════════════════════════

    private fun setupControls() {
        // Back
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        // Play / Pause
        btnPlayPause.setOnClickListener {
            player?.let { if (it.isPlaying) it.pause() else it.play() }
            resetControlsTimer()
        }

        // Rewind / Forward
        findViewById<ImageButton>(R.id.btnRewind).setOnClickListener {
            player?.seekBack(); resetControlsTimer()
        }
        findViewById<ImageButton>(R.id.btnForward).setOnClickListener {
            player?.seekForward(); resetControlsTimer()
        }

        // SeekBar
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = player?.duration ?: 0
                    if (dur > 0) {
                        val pos = dur * progress / 1000
                        tvTimeRemaining.text = formatTime(dur - pos)
                    }
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {
                handler.removeCallbacks(hideControlsRunnable)
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                val dur = player?.duration ?: 0
                if (dur > 0) {
                    player?.seekTo(dur * sb.progress / 1000)
                }
                resetControlsTimer()
            }
        })

        // Lock
        findViewById<ImageButton>(R.id.btnLock).setOnClickListener { lockControls() }
        findViewById<ImageButton>(R.id.btnUnlock).setOnClickListener { unlockControls() }

        // Settings (video display / fit) — sits next to the lock button, Netflix-style
        btnSettings.setOnClickListener { showDisplaySettingsSheet() }

        // Server (streaming source / tier)
        findViewById<View>(R.id.btnServer).setOnClickListener { showServerTierSheet() }

        // Speed
        findViewById<View>(R.id.btnSpeed).setOnClickListener { showSpeedSheet() }

        // Audio & Subtitles (also hosts Server/Tier + Quality)
        findViewById<View>(R.id.btnAudioSubs).setOnClickListener { showAudioSubtitlesSheet() }

        // Next Episode
        btnNextEp.setOnClickListener { playNextEpisode() }

        // Episodes button
        btnEpisodes.setOnClickListener { openEpisodes() }

        resetControlsTimer()
    }

    private fun showControls() {
        controlsOverlay.visibility = View.VISIBLE
        controlsOverlay.animate().alpha(1f).setDuration(200).start()
        controlsVisible = true
    }

    private fun hideControls() {
        controlsOverlay.animate().alpha(0f).setDuration(200).withEndAction {
            controlsOverlay.visibility = View.GONE
        }.start()
        controlsVisible = false
    }

    private fun toggleControls() {
        if (controlsVisible) hideControls() else {
            showControls(); resetControlsTimer()
        }
    }

    private fun resetControlsTimer() {
        handler.removeCallbacks(hideControlsRunnable)
        if (player?.isPlaying == true && !isLocked)
            handler.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT)
    }

    private fun lockControls() {
        isLocked = true
        hideControls()
        lockOverlay.visibility = View.VISIBLE
        showToast("Controls locked")
    }

    private fun unlockControls() {
        isLocked = false
        lockOverlay.visibility = View.GONE
        showControls()
        resetControlsTimer()
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  GESTURES (tap, double-tap, vertical swipe)
    // ═════════════════════════════════════════════════════════════════════════

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        var downX = 0f; var downY = 0f; var dragging = false

        gestureLayer.setOnTouchListener { _, event ->
            if (isLocked) {
                if (event.action == MotionEvent.ACTION_UP) {
                    lockOverlay.visibility = View.VISIBLE
                }
                return@setOnTouchListener true
            }
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x; downY = event.y; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.y - downY
                    if (abs(dy) > 30 && !dragging) dragging = true
                    if (dragging) {
                        // Vertical swipe: left half = brightness, right half = volume
                        val delta = -dy / (gestureLayer.height * 0.6f)
                        if (downX < gestureLayer.width / 2) {
                            adjustBrightness(delta)
                        } else {
                            adjustVolume(delta)
                        }
                        downY = event.y
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        val now = System.currentTimeMillis()
                        val isDoubleTap = now - lastTapTime < DOUBLE_TAP_MS &&
                                abs(event.x - lastTapX) < 100
                        lastTapTime = now
                        lastTapX = event.x

                        if (isDoubleTap) {
                            // Double-tap seek
                            val left = event.x < gestureLayer.width / 2
                            player?.seekTo(
                                (player!!.currentPosition + if (left) -SEEK_MS else SEEK_MS)
                                    .coerceIn(0, player!!.duration)
                            )
                            flashRipple(left)
                        } else {
                            handler.postDelayed({
                                if (System.currentTimeMillis() - lastTapTime >= DOUBLE_TAP_MS) {
                                    toggleControls()
                                }
                            }, DOUBLE_TAP_MS + 10)
                        }
                    }
                }
            }
            true
        }
    }

    private fun flashRipple(left: Boolean) {
        val ripple: View = if (left) findViewById(R.id.rippleLeft)
        else findViewById(R.id.rippleRight)
        ripple.alpha = 1f
        ripple.animate().alpha(0f).setDuration(550).start()
    }

    private fun initBrightnessSlider() {
        if (!::seekBrightness.isInitialized) return
        val currentB = getScreenBrightness()
        seekBrightness.progress = (currentB * 100).toInt()

        seekBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val b = (progress / 100f).coerceIn(0.01f, 1f)
                    setScreenBrightness(b)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                handler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                resetControlsTimer()
            }
        })
    }

    private fun getScreenBrightness(): Float {
        var b = window.attributes.screenBrightness
        if (b < 0f) {
            b = try {
                android.provider.Settings.System.getInt(
                    contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS
                ) / 255f
            } catch (e: Exception) {
                0.5f
            }
        }
        return b.coerceIn(0.01f, 1f)
    }

    private fun setScreenBrightness(value: Float) {
        val target = value.coerceIn(0.01f, 1f)
        val lp = window.attributes
        lp.screenBrightness = target
        window.attributes = lp
        if (::seekBrightness.isInitialized) {
            seekBrightness.progress = (target * 100).toInt()
        }
    }

    private fun adjustBrightness(delta: Float) {
        val current = getScreenBrightness()
        setScreenBrightness(current + delta)
    }

    private fun adjustVolume(delta: Float) {
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val newVol = (cur + delta * 2).toInt().coerceIn(0, max)
        am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, newVol, 0)
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  SEEK BAR UPDATE
    // ═════════════════════════════════════════════════════════════════════════

    private fun updateSeekBar() {
        val exo = player ?: return
        val dur = exo.duration
        if (dur <= 0) return
        val pos = exo.currentPosition
        seekBar.progress = (pos * 1000 / dur).toInt()

        // Buffered
        seekBar.secondaryProgress =
            (exo.bufferedPosition * 1000 / dur).toInt()

        tvTimeRemaining.text = formatTime(dur - pos)
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = totalSec % 3600 / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  EPISODES (loaded from TMDB)
    // ═════════════════════════════════════════════════════════════════════════

    private fun setupEpisodes() {
        episodeAdapter = EpisodeAdapter { ep ->
            playEpisode(currentSeason, ep.episodeNumber)
        }
        rvEpisodes.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvEpisodes.adapter = episodeAdapter

        // Close episodes sheet
        findViewById<ImageButton>(R.id.epBackBtn).setOnClickListener {
            episodeSheet.visibility = View.GONE
        }

        // Season dropdown
        tvSeasonLabel.setOnClickListener { showSeasonPicker() }
        findViewById<FrameLayout>(R.id.seasonDropdown).setOnClickListener { showSeasonPicker() }
    }

    private fun openEpisodes() {
        if (mediaType != "tv") return
        episodeSheet.visibility = View.VISIBLE
        hideControls()
        loadSeasonsAndEpisodes()
    }

    private fun loadSeasonsAndEpisodes() {
        if (tmdbId <= 0) {
            showToast("No TMDB ID for episodes")
            return
        }

        val epLoading: ProgressBar = findViewById(R.id.epLoading)
        epLoading.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                // Fetch seasons if not cached
                if (seasonsCache == null) {
                    val resp = withContext(Dispatchers.IO) {
                        ApiClient.tmdbApi.getTvDetails(tmdbId, TMDB_API_KEY)
                    }
                    if (resp.isSuccessful) {
                        val tvDetails = resp.body()
                        seasonsCache = tvDetails?.seasons
                            ?.filter { it.seasonNumber > 0 }  // skip specials
                            ?.map {
                                SeasonItem(
                                    seasonNumber = it.seasonNumber,
                                    name = it.name ?: "Season ${it.seasonNumber}",
                                    episodeCount = it.episodeCount
                                )
                            } ?: listOf(SeasonItem(1, "Season 1", 10))
                    }
                }

                tvSeasonLabel.text = seasonsCache
                    ?.find { it.seasonNumber == currentSeason }
                    ?.name ?: "Season $currentSeason"

                // Fetch episodes
                loadEpisodesForSeason(currentSeason)

            } catch (e: Exception) {
                showToast("Failed to load seasons: ${e.localizedMessage}")
            } finally {
                epLoading.visibility = View.GONE
            }
        }
    }

    private fun loadEpisodesForSeason(season: Int) {
        val epLoading: ProgressBar = findViewById(R.id.epLoading)
        val emptyText: TextView = findViewById(R.id.epEmptyText)

        lifecycleScope.launch {
            try {
                epLoading.visibility = View.VISIBLE
                emptyText.visibility = View.GONE

                val cached = episodesCache[season]
                if (cached != null) {
                    renderEpisodes(cached, season)
                    epLoading.visibility = View.GONE
                    return@launch
                }

                val resp = withContext(Dispatchers.IO) {
                    ApiClient.tmdbApi.getSeasonDetail(tmdbId, season, TMDB_API_KEY)
                }

                if (resp.isSuccessful) {
                    val episodes = resp.body()?.episodes?.map { ep ->
                        EpisodeItem(
                            episodeNumber = ep.episodeNumber,
                            title = ep.name ?: "Episode ${ep.episodeNumber}",
                            description = ep.overview ?: "",
                            durationMinutes = ep.runtime ?: 0,
                            thumbnailUrl = ep.stillPath?.let { "$TMDB_IMG_BASE$it" },
                            isCurrent = season == currentSeason && ep.episodeNumber == currentEpisode
                        )
                    } ?: emptyList()

                    episodesCache[season] = episodes
                    renderEpisodes(episodes, season)
                } else {
                    emptyText.text = "Episodes aren't available right now."
                    emptyText.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                val emptyTv: TextView = findViewById(R.id.epEmptyText)
                emptyTv.text = "Couldn't load episodes."
                emptyTv.visibility = View.VISIBLE
            } finally {
                epLoading.visibility = View.GONE
            }
        }
    }

    private fun renderEpisodes(episodes: List<EpisodeItem>, season: Int) {
        // Mark current episode
        val updated = episodes.map {
            it.copy(isCurrent = season == currentSeason && it.episodeNumber == currentEpisode)
        }
        episodeAdapter.submitList(updated)

        // Scroll to current episode
        val idx = updated.indexOfFirst { it.isCurrent }
        if (idx >= 0) {
            rvEpisodes.post { rvEpisodes.scrollToPosition(idx) }
        }
    }

    private fun showSeasonPicker() {
        val seasons = seasonsCache ?: return
        val rows = seasons.map { s ->
            SheetRow(id = s.seasonNumber.toString(), title = s.name, selected = s.seasonNumber == currentSeason)
        }
        showNetflixSheet("Select Season", rows) { row ->
            val num = row.id.toInt()
            val picked = seasons.first { it.seasonNumber == num }
            currentSeason = picked.seasonNumber
            tvSeasonLabel.text = picked.name
            loadEpisodesForSeason(picked.seasonNumber)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PLAY EPISODE / NEXT
    // ═════════════════════════════════════════════════════════════════════════

    private fun playEpisode(season: Int, episode: Int) {
        Log.i(TAG, "playEpisode: switching to S${season}:E${episode}")
        currentSeason = season
        currentEpisode = episode
        episodeSheet.visibility = View.GONE
        loadMedia()
    }

    private fun playNextEpisode() {
        if (mediaType != "tv") return
        val nextEp = currentEpisode + 1
        Log.i(TAG, "playNextEpisode: advancing automatically to S${currentSeason}:E${nextEp}")
        playEpisode(currentSeason, nextEp)
    }

    private fun onVideoEnded() {
        Log.i(TAG, "onVideoEnded: Video playback completed")
        if (mediaType == "tv") {
            // Auto-play next
            playNextEpisode()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  NETFLIX-STYLE BOTTOM SHEETS (shared by every player menu)
    // ═════════════════════════════════════════════════════════════════════════

    private data class SheetRow(
        val id: String,
        val title: String,
        val subtitle: String? = null,
        val selected: Boolean = false,
        val isHeader: Boolean = false,
        val showChevron: Boolean = false
    )

    private fun showNetflixSheet(title: String, rows: List<SheetRow>, onSelect: (SheetRow) -> Unit) {
        val dialog = Dialog(this, R.style.NetflixBottomSheetDialog)
        val sheetView = layoutInflater.inflate(R.layout.dialog_netflix_sheet, null)
        dialog.setContentView(sheetView)
        dialog.window?.setGravity(Gravity.BOTTOM)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        sheetView.findViewById<TextView>(R.id.sheetTitle).text = title
        val container = sheetView.findViewById<LinearLayout>(R.id.sheetContent)

        rows.forEach { row ->
            if (row.isHeader) {
                val headerView = layoutInflater.inflate(R.layout.item_sheet_header, container, false)
                headerView.findViewById<TextView>(R.id.headerLabel).text = row.title
                container.addView(headerView)
            } else {
                val rowView = layoutInflater.inflate(R.layout.item_sheet_row, container, false)
                rowView.findViewById<TextView>(R.id.rowTitle).text = row.title

                val subtitleView = rowView.findViewById<TextView>(R.id.rowSubtitle)
                if (!row.subtitle.isNullOrBlank()) {
                    subtitleView.text = row.subtitle
                    subtitleView.visibility = View.VISIBLE
                } else {
                    subtitleView.visibility = View.GONE
                }

                rowView.findViewById<ImageView>(R.id.rowCheck).visibility =
                    if (row.selected) View.VISIBLE else View.GONE
                rowView.findViewById<ImageView>(R.id.rowChevron).visibility =
                    if (row.showChevron) View.VISIBLE else View.GONE

                rowView.setOnClickListener {
                    dialog.dismiss()
                    onSelect(row)
                }
                container.addView(rowView)
            }
        }

        // Sheets pause the auto-hide timer while open, and resume it once dismissed.
        handler.removeCallbacks(hideControlsRunnable)
        dialog.setOnDismissListener { resetControlsTimer() }
        dialog.show()
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  SPEED SHEET
    // ═════════════════════════════════════════════════════════════════════════

    private fun showSpeedSheet() {
        val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val currentSpeed = player?.playbackParameters?.speed ?: 1f
        val rows = speeds.map { s ->
            val label = if (s == 1f) "Normal (1x)" else "${formatSpeed(s)}x"
            SheetRow(id = s.toString(), title = label, selected = abs(s - currentSpeed) < 0.05f)
        }
        showNetflixSheet("Playback Speed", rows) { row ->
            val speed = row.id.toFloat()
            Log.i(TAG, "User selected playback speed: ${formatSpeed(speed)}x")
            player?.setPlaybackSpeed(speed)
            tvSpeedLabel.text = "Speed (${formatSpeed(speed)}x)"
        }
    }

    private fun formatSpeed(speed: Float): String =
        if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()

    // ═════════════════════════════════════════════════════════════════════════
    //  AUDIO, SUBTITLES, SERVER/TIER & QUALITY
    // ═════════════════════════════════════════════════════════════════════════

    private fun showAudioSubtitlesSheet() {
        val tracks = player?.currentTracks

        val audioTracks = mutableListOf<Pair<String, Tracks.Group>>()
        val textTracks = mutableListOf<Pair<String, Tracks.Group>>()

        tracks?.groups?.forEach { group ->
            when (group.type) {
                C.TRACK_TYPE_AUDIO -> {
                    val fmt = group.getTrackFormat(0)
                    val label = fmt.label ?: fmt.language?.let { languageDisplay(it) }
                        ?: "Audio ${audioTracks.size + 1}"
                    audioTracks.add(label to group)
                }
                C.TRACK_TYPE_TEXT -> {
                    val fmt = group.getTrackFormat(0)
                    val label = fmt.label ?: fmt.language?.let { languageDisplay(it) }
                        ?: "Subtitle ${textTracks.size + 1}"
                    textTracks.add(label to group)
                }
                else -> {}
            }
        }

        val subsOff = trackSelector?.parameters?.getRendererDisabled(getTextRendererIndex()) ?: true

        val rows = mutableListOf<SheetRow>()
        rows += SheetRow(id = "quality", title = "Video Quality", subtitle = currentQualityLabel(), showChevron = true)

        if (audioTracks.isNotEmpty()) {
            rows += SheetRow(id = "hdr_audio", title = "AUDIO", isHeader = true)
            audioTracks.forEachIndexed { i, (label, group) ->
                rows += SheetRow(id = "audio_$i", title = label, selected = group.isTrackSelected(0))
            }
        }

        rows += SheetRow(id = "hdr_subs", title = "SUBTITLES", isHeader = true)
        rows += SheetRow(id = "subs_off", title = "Off", selected = subsOff)
        textTracks.forEachIndexed { i, (label, group) ->
            rows += SheetRow(id = "subs_$i", title = label, selected = !subsOff && group.isTrackSelected(0))
        }

        showNetflixSheet("Audio & Subtitles", rows) { row ->
            when {
                row.id == "quality" -> showQualitySheet()
                row.id == "subs_off" -> {
                    Log.i(TAG, "User disabled subtitles ('Off' selected)")
                    trackSelector?.setParameters(
                        trackSelector!!.buildUponParameters()
                            .setRendererDisabled(getTextRendererIndex(), true)
                    )
                }
                row.id.startsWith("subs_") -> {
                    val idx = row.id.removePrefix("subs_").toInt()
                    val (label, group) = textTracks[idx]
                    Log.i(TAG, "User selected subtitle track: '$label'")
                    trackSelector?.setParameters(
                        trackSelector!!.buildUponParameters()
                            .setRendererDisabled(getTextRendererIndex(), false)
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                    )
                }
                row.id.startsWith("audio_") -> {
                    val idx = row.id.removePrefix("audio_").toInt()
                    val (label, group) = audioTracks[idx]
                    Log.i(TAG, "User selected audio track: '$label'")
                    trackSelector?.setParameters(
                        trackSelector!!.buildUponParameters()
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                    )
                }
            }
        }
    }

    private fun showServerTierSheet() {
        val rows = mutableListOf<SheetRow>()
        rows += SheetRow(id = "tier_auto", title = "Auto Normal (Cascading Tiers)", selected = selectedTier == null)
        tierNames.forEach { (num, name) ->
            rows += SheetRow(id = "tier_$num", title = "Server / Tier $num ($name)", selected = selectedTier == num)
        }
        showNetflixSheet("Select Streaming Server / Tier", rows) { row ->
            selectedTier = if (row.id == "tier_auto") null else row.id.removePrefix("tier_").toInt()
            Log.i(TAG, "User switched server/tier -> ${currentTierLabel()}")
            showToast("Switching to ${currentTierLabel()}...")
            updateServerLabelUi()
            loadMedia()
        }
    }

    private fun currentTierLabel(): String =
        selectedTier?.let { "Tier $it · ${tierNames[it] ?: ""}" } ?: "Auto Normal"

    /** Short label for the bottom-bar Server button, mirrors the Speed button's "Speed (1x)" style. */
    private fun updateServerLabelUi() {
        val short = selectedTier?.let { "Tier $it" } ?: "Auto"
        tvServerLabel.text = "Server ($short)"
    }

    private fun showQualitySheet() {
        val qualities = getAvailableVideoQualities()
        val rows = mutableListOf<SheetRow>()
        rows += SheetRow(
            id = "auto", title = "Auto",
            subtitle = "Adapts automatically to your connection",
            selected = selectedQualityHeight == null
        )
        qualities.forEach { h ->
            rows += SheetRow(id = "q_$h", title = qualityLabelForHeight(h), selected = selectedQualityHeight == h)
        }
        showNetflixSheet("Video Quality", rows) { row ->
            if (row.id == "auto") {
                applyVideoQuality(null)
                showToast("Quality: Auto")
            } else {
                val h = row.id.removePrefix("q_").toInt()
                applyVideoQuality(h)
                showToast("Quality: ${qualityLabelForHeight(h)}")
            }
        }
    }

    private fun applyVideoQuality(heightTarget: Int?) {
        val ts = trackSelector ?: return
        selectedQualityHeight = heightTarget
        if (heightTarget == null) {
            ts.setParameters(
                ts.buildUponParameters()
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                    .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
            )
            return
        }
        val tracks = player?.currentTracks ?: return
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until group.length) {
                if (group.getTrackFormat(i).height == heightTarget) {
                    ts.setParameters(
                        ts.buildUponParameters()
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                    )
                    return
                }
            }
        }
    }

    /** Distinct renditions actually present in the current HLS/DASH/MP4 source, highest first. */
    private fun getAvailableVideoQualities(): List<Int> {
        val tracks = player?.currentTracks ?: return emptyList()
        val heights = mutableSetOf<Int>()
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until group.length) {
                val h = group.getTrackFormat(i).height
                if (h > 0) heights.add(h)
            }
        }
        return heights.sortedDescending()
    }

    private fun currentQualityLabel(): String {
        val target = selectedQualityHeight
        if (target == null) {
            val h = player?.videoSize?.height ?: 0
            return if (h > 0) "Auto (${qualityLabelForHeight(h)})" else "Auto"
        }
        return qualityLabelForHeight(target)
    }

    private fun qualityLabelForHeight(h: Int): String = when {
        h >= 2160 -> "4K (2160p)"
        h >= 1440 -> "1440p (QHD)"
        h >= 1080 -> "1080p (Full HD)"
        h >= 720 -> "720p (HD)"
        h >= 480 -> "480p (SD)"
        h >= 360 -> "360p"
        h > 0 -> "${h}p"
        else -> "Auto"
    }

    private fun getTextRendererIndex(): Int {
        val exo = player ?: return 2
        for (i in 0 until exo.rendererCount) {
            if (exo.getRendererType(i) == C.TRACK_TYPE_TEXT) return i
        }
        return 2
    }

    private fun languageDisplay(code: String): String {
        return try {
            val name = Locale(code).displayLanguage
            if (name.isNotBlank() && !name.equals(code, ignoreCase = true)) {
                name.replaceFirstChar { it.uppercase() }
            } else {
                code.uppercase()
            }
        } catch (e: Exception) {
            code.uppercase()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  DISPLAY SETTINGS (gear icon — video fit / crop / stretch)
    // ═════════════════════════════════════════════════════════════════════════

    private data class ResizeOption(val mode: Int, val label: String, val description: String)

    private val resizeModeOptions = listOf(
        ResizeOption(AspectRatioFrameLayout.RESIZE_MODE_FIT, "Best Fit", "Show the entire video, may add black bars"),
        ResizeOption(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, "Center Crop", "Fill the screen; edges may be cropped"),
        ResizeOption(AspectRatioFrameLayout.RESIZE_MODE_FILL, "Stretch to Fill", "Fill the screen; image may distort")
    )

    private fun showDisplaySettingsSheet() {
        val rows = resizeModeOptions.map {
            SheetRow(id = it.mode.toString(), title = it.label, subtitle = it.description, selected = currentResizeMode == it.mode)
        }
        showNetflixSheet("Video Fit", rows) { row ->
            val mode = row.id.toInt()
            currentResizeMode = mode
            playerView.resizeMode = mode
            Log.i(TAG, "User changed video display mode -> ${resizeModeOptions.first { it.mode == mode }.label}")
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  TOAST
    // ═════════════════════════════════════════════════════════════════════════

    private fun showToast(msg: String) {
        toastText.text = msg
        toastText.animate().alpha(1f).setDuration(200).withEndAction {
            handler.postDelayed({
                toastText.animate().alpha(0f).setDuration(200).start()
            }, 2200)
        }.start()
    }
}
