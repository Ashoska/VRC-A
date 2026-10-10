package com.vrca.speech

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.vrca.R

/**
 * Keeps the microphone open while VRC-A is in the background (e.g. you're in VRChat).
 *
 * Android silences an app's mic once it leaves the screen unless a foreground service
 * of type `microphone` is running, and that service must be STARTED while the app is
 * on screen (here: when you tap the mic). Without it dictation stopped after about one
 * phrase once you switched to VRChat. It only runs while dictation is on; the capture
 * itself stays in [SpeechToText] (any component of the app may record while it runs).
 * A game in front that holds the mic itself may still take priority (Android's input
 * sharing rules), which only a device test can show.
 */
class DictationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ok = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, notification())
            }
            true
        } catch (t: Throwable) {
            // Not allowed now (e.g. started from the background): dictation still works
            // while VRC-A is on screen, just not tabbed out.
            Log.w(TAG, "mic foreground service refused", t)
            false
        }
        if (!ok) stopSelf()
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App swiped away: stop listening rather than keep a mic open with no UI.
        SpeechToText.stop()
        stopSelf()
    }

    private fun notification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Voice to text", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while voice to text is listening"
                setShowBadge(false)
            })
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_sync)
            .setContentTitle("Voice to text is listening")
            .setContentText("Open VRC-A and tap stop to end it.")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "DictationService"
        private const val CHANNEL_ID = "vrca_dictation"
        private const val NOTIF_ID = 7301

        /** Call while VRC-A is on screen (Android only allows a mic service start then). */
        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, DictationService::class.java)) }
                .onFailure { Log.w(TAG, "couldn't start mic service", it) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, DictationService::class.java)) }
        }
    }
}
