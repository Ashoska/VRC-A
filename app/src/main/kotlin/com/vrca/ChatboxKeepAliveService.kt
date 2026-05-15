package com.vrca.keepalive

import com.vrca.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service whose only job is to keep the app scheduled while the screen is off (Doze).
 * Your existing ViewModel jobs keep sending OSC; this service prevents the process from being frozen.
 */
class ChatboxKeepAliveService : Service() {

    companion object {
        private const val TAG = "ChatboxKeepAliveService"
        private const val CHANNEL_ID = "chatbox_keepalive"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val i = Intent(context, ChatboxKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ChatboxKeepAliveService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var loopJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        startAsForeground()

        // PARTIAL_WAKE_LOCK keeps CPU running while screen is off.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:chatbox_keepalive").apply {
            setReferenceCounted(false)
            try {
                acquire()
                Log.d(TAG, "WakeLock acquired")
            } catch (t: Throwable) {
                Log.e(TAG, "WakeLock acquire failed", t)
            }
        }

        // Small periodic loop to keep the process "active" under some OEMs.
        loopJob?.cancel()
        loopJob = scope.launch {
            while (true) {
                delay(30_000L)
                Log.d(TAG, "tick")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If killed, try to come back.
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")

        loopJob?.cancel()
        loopJob = null

        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Throwable) {
        }
        wakeLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Chatbox Background",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps Chatbox running while screen is off so OSC continues."
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            }

            val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Chatbox running")
                    .setContentText("Keeping OSC updates alive while screen is off")
                    .setSmallIcon(R.drawable.ic_notification)
                    .setOngoing(true)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
                    .setContentTitle("Chatbox running")
                    .setContentText("Keeping OSC updates alive while screen is off")
                    .setSmallIcon(R.drawable.ic_notification)
                    .setOngoing(true)
                    .build()
            }

            startForeground(NOTIF_ID, notif)
        } catch (se: SecurityException) {
            // If POST_NOTIFICATIONS isn’t granted on Android 13+, some devices may throw.
            // Service will still try to run, but foreground may fail; log it.
            Log.e(TAG, "startForeground blocked (notification permission?)", se)
        } catch (t: Throwable) {
            Log.e(TAG, "startAsForeground failed", t)
        }
    }
}
