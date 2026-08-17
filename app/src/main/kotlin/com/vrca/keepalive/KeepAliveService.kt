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
import android.net.wifi.WifiManager
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
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "VrcaKeepAlive"
        private const val CHANNEL_ID = "vrca_pipeline"
        private const val NOTIF_ID = 1001

        // Android explicitly warns against indefinitely-held wakelocks, and some
        // OEMs force-release them after a while. We acquire with a bounded timeout
        // and RE-ACQUIRE on every tick — equivalent to "held forever" in practice
        // while staying OEM-friendly and self-releasing if the tick loop ever dies.
        private const val WAKELOCK_TIMEOUT_MS = 6L * 60L * 60L * 1000L // 6 hours
        private const val TICK_MS = 30_000L

        fun start(context: Context) {
            val i = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var loopJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    /** Acquire (idempotently) a WiFi lock so Android keeps the Wi-Fi interface
     *  awake and STABLE while backgrounded / screen-off. This is the missing piece
     *  vs the reference companion (which holds PARTIAL_WAKE_LOCK + a WiFi lock):
     *  without it, Wi-Fi power management sleeps/re-associates the radio, which
     *  re-evaluates the PROCESS's default-network binding — the transition that
     *  makes outbound UDP to 127.0.0.1 silently stop delivering on Quest ("chatbox
     *  stops, reopen fixes it") AND that drops the VRChat WebSocket / Discord
     *  gateway / Firestore when backgrounded. FULL_LOW_LATENCY (API 29+) keeps the
     *  radio hot; it degrades to FULL when backgrounded, which is exactly the
     *  stay-connected behavior we want. Non-ref-counted; released on destroy. */
    private fun ensureWifiLock() {
        try {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    ?: return
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                else
                    @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wm.createWifiLock(mode, "$packageName:chatbox_wifi").apply {
                    setReferenceCounted(false)
                }
            }
            wifiLock?.let { if (!it.isHeld) it.acquire() }
        } catch (t: Throwable) {
            Log.e(TAG, "WifiLock acquire failed", t)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        startAsForeground()

        // PARTIAL_WAKE_LOCK keeps CPU running while screen is off.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:chatbox_keepalive").apply {
            setReferenceCounted(false)
            try {
                acquire(WAKELOCK_TIMEOUT_MS)
                Log.d(TAG, "WakeLock acquired")
            } catch (t: Throwable) {
                Log.e(TAG, "WakeLock acquire failed", t)
            }
        }

        // Keep the Wi-Fi radio awake + the network binding stable (see ensureWifiLock).
        ensureWifiLock()

        // Small periodic loop to keep the process "active" under some OEMs.
        // It also RE-ARMS the bounded wakelock each tick so the 6h timeout never
        // actually expires while the loop is alive — held-forever in practice,
        // but self-releasing the moment this loop stops (process death/teardown).
        loopJob?.cancel()
        loopJob = scope.launch {
            while (true) {
                delay(TICK_MS)
                try {
                    wakeLock?.let { wl ->
                        // Non-reference-counted: a fresh timed acquire simply
                        // resets the timeout; release-then-acquire isn't needed.
                        wl.acquire(WAKELOCK_TIMEOUT_MS)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "WakeLock re-acquire failed", t)
                }
                // Re-assert the WiFi lock in case an OEM force-released it.
                ensureWifiLock()
                Log.d(TAG, "tick")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent is a START_STICKY restart. If the user swiped the app away,
        // abort instead of resurrecting — this wakelock-holding service was the main
        // reason the process never died on swipe. We check BOTH the 15s freshness
        // window (immediate sticky restart) and the persistent swipe flag (a late
        // restart after the window expired); a legitimate reopen always passes a
        // non-null intent, so it bypasses this guard.
        if (intent == null &&
            (com.vrca.app.AppShutdown.isManualKillFresh(this) ||
                com.vrca.app.AppShutdown.isSwipedAway(this))) {
            try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
            try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            val appCtx = applicationContext
            Thread {
                try { Thread.sleep(300) } catch (_: Throwable) {}
                // onCreate posted the shared notification (id 1001) BEFORE this
                // guard ran; stopForeground removes it, but the removal can race
                // the kill below on some OEMs. Cancel explicitly — processed in
                // system_server, so it lands even after the process dies.
                com.vrca.app.AppShutdown.cancelPersistentNotification(appCtx)
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(0)
            }.start()
            return START_NOT_STICKY
        }
        // After an OS-initiated kill the process is brought back here (START_STICKY /
        // watchdog / boot) with no Activity, so the app-scoped VrcaViewModel — which owns
        // the chatbox senders AND the hourly liveness heartbeat AND the moderation/watch
        // listener — would stay dead until the user reopened the app. We ALWAYS recreate it
        // on revival (the swipe guard above already returned for a deliberate swipe, so this
        // only runs for a genuine OS kill where the process should keep working in the
        // background). This is required even when nothing is OSC-sending: the heartbeat keeps
        // the user reporting ONLINE and the moderation listener keeps admin watch/kill/toggles
        // reachable — both are runtime concerns independent of whether the chatbox is sending.
        // OSC transmission stays separately gated: `restoreFeatureSession()` only calls
        // startSending() when the persisted session was actually sending, so rebuilding the VM
        // never makes a configured-but-idle (e.g. Discord-RPC-only) user start transmitting.
        try {
            (applicationContext as? com.vrca.app.VrcaApplication)?.ensureRuntimeViewModel()
        } catch (_: Throwable) {}

        // If killed, try to come back.
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // KeepAliveService is always running and holds a wakelock, so its
        // onTaskRemoved is the reliable place to tear the whole process down.
        com.vrca.app.AppShutdown.onTaskSwiped(this)
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

        try {
            wifiLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Throwable) {
        }
        wifiLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val existing = nm.getNotificationChannel(CHANNEL_ID)
                if (existing == null) {
                    val ch = NotificationChannel(
                        CHANNEL_ID,
                        "VRC-A Background",
                        NotificationManager.IMPORTANCE_MIN
                    ).apply {
                        setShowBadge(false)
                        setSound(null, null)
                    }
                    nm.createNotificationChannel(ch)
                }
            }

            val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("VRC-A")
                    .setContentText("Running")
                    .setSmallIcon(R.drawable.ic_notif_sync)
                    .setOngoing(true)
                    .setShowWhen(false)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
                    .setContentTitle("VRC-A")
                    .setContentText("Running")
                    .setSmallIcon(R.drawable.ic_notif_sync)
                    .setOngoing(true)
                    .build()
            }

            startForeground(NOTIF_ID, notif)
        } catch (t: Throwable) {
            // API 31+ ForegroundServiceStartNotAllowedException (background start) or a
            // SecurityException (POST_NOTIFICATIONS missing on 13+) land here. CRITICAL:
            // this service is started via startForegroundService(), so if startForeground
            // fails and the service KEEPS RUNNING, the OS throws
            // ForegroundServiceDidNotStartInTimeException ~5s later and crashes the
            // (shared) process — the same OEM-kill crash loop by a different name. Stop
            // the service to satisfy the 5s contract; the watchdog / next foreground
            // launch restarts it when the OS permits a foreground start.
            Log.e(TAG, "startAsForeground failed — stopping to avoid did-not-start crash", t)
            try { stopSelf() } catch (_: Throwable) {}
        }
    }
}
