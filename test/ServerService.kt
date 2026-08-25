package com.example.mybasic.activity

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class ServerService : Service() {

    companion object {
        private const val TAG = "ServerService"
        const val PORT = 1937
        const val CHANNEL_ID = "nano_server_channel"
        const val NOTIF_ID = 1001
        var isRunning = false
            private set
    }

    private var server: NanoServer? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ServerService onCreate: Service initialized")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        if (server == null || !server!!.isAlive) {
            try {
                server = NanoServer(PORT)
                server?.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                isRunning = true
                Log.i(TAG, "NanoServer successfully started on http://127.0.0.1:$PORT")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start NanoServer on port $PORT: ${e.localizedMessage}", e)
                isRunning = false
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.w(TAG, "ServerService onDestroy: Stopping NanoServer")
        server?.stop()
        server = null
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Backend Server Service", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NanoHTTPD Backend Server Running")
            .setContentText("Listening on http://127.0.0.1:$PORT")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build()
    }
}
