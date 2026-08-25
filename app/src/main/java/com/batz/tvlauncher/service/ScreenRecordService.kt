package com.batz.tvlauncher.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcelable
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File

/**
 * Foreground service that captures the current screen (via [MediaProjection]) into an
 * MP4 file and saves it straight to the device gallery ("Movies/BatzMods Clips").
 *
 * The recording is video-only. Capturing this app's own internal playback audio without
 * the microphone is possible on API 29+ via AudioPlaybackCaptureConfiguration + AudioRecord,
 * but it requires a second MediaCodec/MediaMuxer pipeline running in parallel with the video
 * encoder. That's a natural next step if you want sound in the clip - this service focuses on
 * a rock solid, always-works video capture first.
 *
 * Started/stopped from PlayerActivity via ACTION_START / ACTION_STOP intents. Status is
 * reported back through the static [listener] rather than a bound-service/Messenger, since
 * only one PlayerActivity instance will ever drive this at a time.
 */
class ScreenRecordService : Service() {

    interface Listener {
        fun onRecordingStarted()
        fun onRecordingStopped(uri: Uri?)
        fun onRecordingError(message: String)
    }

    companion object {
        private const val TAG = "ScreenRecordService"
        private const val CHANNEL_ID = "clip_recording_channel"
        private const val NOTIF_ID = 5821

        const val ACTION_START = "com.example.mybasic.activity.service.action.START"
        const val ACTION_STOP = "com.example.mybasic.activity.service.action.STOP"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_DENSITY = "extra_density"

        @Volatile
        var listener: Listener? = null
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var outputUri: Uri? = null
    private var outputPfd: ParcelFileDescriptor? = null
    private var outputFile: File? = null
    private var isActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    // ── START ────────────────────────────────────────────────────────────

    private fun handleStart(intent: Intent) {
        if (isActive) return

        // Must call startForeground almost immediately after startForegroundService().
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = intent.parcelableExtraCompat(EXTRA_RESULT_DATA, Intent::class.java)
        val rawWidth = intent.getIntExtra(EXTRA_WIDTH, 1920)
        val rawHeight = intent.getIntExtra(EXTRA_HEIGHT, 1080)
        val density = intent.getIntExtra(EXTRA_DENSITY, 420)
        val width = rawWidth - (rawWidth % 2)
        val height = rawHeight - (rawHeight % 2)

        if (data == null || resultCode != android.app.Activity.RESULT_OK) {
            listener?.onRecordingError("Screen capture permission was not granted")
            cleanupAndStop()
            return
        }

        try {
            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("Could not obtain MediaProjection handle")
            mediaProjection = projection

            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped externally")
                    handleStop()
                }
            }, Handler(Looper.getMainLooper()))

            val recorder = MediaRecorder()
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setVideoFrameRate(30)
            recorder.setVideoSize(width, height)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setVideoEncodingBitRate(8_000_000)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val uri = createMediaStoreUri()
                outputUri = uri
                val pfd = uri?.let { contentResolver.openFileDescriptor(it, "rw") }
                outputPfd = pfd
                if (pfd == null) throw IllegalStateException("Could not create output file in gallery")
                recorder.setOutputFile(pfd.fileDescriptor)
            } else {
                val file = legacyOutputFile()
                outputFile = file
                recorder.setOutputFile(file.absolutePath)
            }

            recorder.prepare()
            mediaRecorder = recorder

            virtualDisplay = projection.createVirtualDisplay(
                "BatzModsClip",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface, null, null
            )

            recorder.start()
            isActive = true
            listener?.onRecordingStarted()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start clip recording", e)
            listener?.onRecordingError(e.localizedMessage ?: "Could not start recording")
            releaseResources()
            cleanupAndStop()
        }
    }

    // ── STOP ─────────────────────────────────────────────────────────────

    private fun handleStop() {
        if (!isActive) return
        isActive = false

        var stoppedCleanly = true
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            // Thrown if stop() is called almost immediately after start() (near-zero-length
            // clip). The output file is unusable in that case.
            Log.w(TAG, "MediaRecorder.stop() failed, clip was likely too short", e)
            stoppedCleanly = false
        }
        releaseResources()

        val finalUri = if (stoppedCleanly) finalizeOutput() else {
            discardOutput()
            null
        }

        if (stoppedCleanly && finalUri != null) {
            listener?.onRecordingStopped(finalUri)
        } else if (stoppedCleanly) {
            listener?.onRecordingError("Clip could not be saved")
        } else {
            listener?.onRecordingError("Clip was too short to save")
        }
        cleanupAndStop()
    }

    private fun releaseResources() {
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MediaRecorder", e)
        }
        mediaRecorder = null
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun finalizeOutput(): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && outputUri != null) {
                outputPfd?.close()
                val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                contentResolver.update(outputUri!!, values, null, null)
                outputUri
            } else if (outputFile != null) {
                MediaScannerConnection.scanFile(
                    this, arrayOf(outputFile!!.absolutePath), arrayOf("video/mp4"), null
                )
                Uri.fromFile(outputFile)
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Error finalizing clip", e)
            null
        }
    }

    private fun discardOutput() {
        try {
            outputPfd?.close()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && outputUri != null) {
                contentResolver.delete(outputUri!!, null, null)
            } else {
                outputFile?.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error discarding incomplete clip", e)
        }
    }

    private fun cleanupAndStop() {
        outputUri = null
        outputPfd = null
        outputFile = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isActive) {
            releaseResources()
            discardOutput()
        }
    }

    // ── OUTPUT TARGETS ───────────────────────────────────────────────────

    private fun createMediaStoreUri(): Uri? {
        val name = "Clip_${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/BatzMods Clips")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return contentResolver.insert(collection, values)
    }

    private fun legacyOutputFile(): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "BatzMods Clips"
        )
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "Clip_${System.currentTimeMillis()}.mp4")
    }

    // ── NOTIFICATION ─────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Clip recording", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording clip")
            .setContentText("Tap the clip button again to stop and save")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}

@Suppress("DEPRECATION")
private fun <T : Parcelable> Intent.parcelableExtraCompat(key: String, clazz: Class<T>): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, clazz)
    } else {
        getParcelableExtra(key)
    }
