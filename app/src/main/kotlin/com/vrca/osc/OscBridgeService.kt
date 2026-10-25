package com.vrca.osc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.vrca.R
import com.vrca.app.startForegroundSafely

/**
 * Headset: the foreground service that keeps the `:oscbridge` process (and so
 * [OscBridge], VRChat's connection) alive. Declared only in the headset manifest, with
 * `android:process=":oscbridge"`. It is NOT stopped by a swipe ([com.vrca.app.AppShutdown]
 * only stops its own services and kills the main process), so VRChat's push survives
 * the main app closing; it stops itself once the main app is gone and VRChat has been
 * silent for 10 minutes, or from its notification's Stop button.
 */
class OscBridgeService : Service() {

    companion object {
        private const val CHANNEL_ID = "osc_bridge"
        private const val NOTIF_ID = 4711
        private const val ACTION_STOP = "com.vrca.osc.BRIDGE_STOP"

        /** Start (or keep) the bridge. Safe to call repeatedly; may be refused by the OS
         *  when the app is in the background (then the poll fallback covers it). */
        fun start(context: Context) {
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    context, Intent(context, OscBridgeService::class.java)
                )
            }
        }
    }

    @Volatile private var lastConnected: Boolean? = null
    @Volatile private var notifLoopStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // User asked: end the bridge (VRChat stops sending until it restarts).
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            android.os.Process.killProcess(android.os.Process.myPid())
            return START_NOT_STICKY
        }
        if (!startForegroundSafely(NOTIF_ID, notification(OscBridge.vrchatConnected), "OscBridge")) return START_NOT_STICKY
        OscBridge.start(this) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        if (!notifLoopStarted) {
            notifLoopStarted = true
            Thread({ notificationLoop() }, "vrca-bridge-notif").apply { isDaemon = true; start() }
        }
        return START_STICKY
    }

    /** Keep the notification text in step with VRChat's state (changes only). */
    private fun notificationLoop() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        while (OscBridge.running) {
            val c = OscBridge.vrchatConnected
            if (c != lastConnected) {
                lastConnected = c
                runCatching { nm.notify(NOTIF_ID, notification(c)) }
            }
            try { Thread.sleep(5_000) } catch (_: InterruptedException) { break }
        }
    }

    private fun notification(connected: Boolean): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VRChat connection", NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false); setSound(null, null)
                }
            )
        }
        val stop = PendingIntent.getService(
            this, 0, Intent(this, OscBridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("VRC-A")
            .setContentText(if (connected) "Connected to VRChat" else "Waiting for VRChat")
            .setSmallIcon(R.drawable.ic_notif_sync)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }
}
