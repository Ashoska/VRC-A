package com.vrca.app

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.vrca.discord.DiscordRpcService
import com.vrca.keepalive.KeepAliveService
import com.vrca.overlay.OverlayService
import com.vrca.vrchat.VrchatPipelineState
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Centralised swipe-away shutdown.
 *
 * The app runs several foreground services in the SAME process — `KeepAliveService`
 * (always on, holds a wakelock), `VrchatPipelineService`, `DiscordRpcService`,
 * `OverlayService`. Previously only the pipeline service killed the process on
 * `onTaskRemoved`, but the pipeline isn't always running, and `KeepAliveService`
 * (which IS always running and holds a wakelock) had no `onTaskRemoved` at all —
 * so after a swipe the wakelock-holding service kept the whole process alive and
 * nothing ever killed it.
 *
 * Now EVERY service's `onTaskRemoved` calls [onTaskSwiped]. An [AtomicBoolean]
 * makes the heavy work (offline Firestore write + process kill) run exactly once
 * regardless of how many services fire it.
 */
object AppShutdown {
    private const val TAG = "AppShutdown"
    const val PREFS = "vrca_remote"
    const val KEY_MANUAL_KILL_AT = "manual_kill_at"
    const val KEY_SWIPED_AWAY = "swiped_away"
    const val MANUAL_KILL_WINDOW_MS = 15_000L

    private val shuttingDown = AtomicBoolean(false)

    /** True if a swipe-kill was stamped within the guard window (used by services'
     *  onStartCommand to abort a START_STICKY restart instead of resurrecting). */
    fun isManualKillFresh(context: Context): Boolean {
        val killedAt = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_MANUAL_KILL_AT, 0L)
        return System.currentTimeMillis() - killedAt < MANUAL_KILL_WINDOW_MS
    }

    /**
     * PERSISTENT deliberate-dismissal flag. Set true on a real swipe-away and
     * cleared ONLY when the user legitimately reopens the app ([clearSwipedAway]
     * from `MainActivity.onCreate`) or on reboot.
     *
     * The 15s [isManualKillFresh] window is NOT enough on its own: the WorkManager
     * watchdog ([PipelineWatchdogWorker]) survives the process kill and fires ~15
     * minutes later in a fresh process, long after the window expired — it would
     * then see the user still logged in and resurrect the pipeline + keep-alive,
     * making the swiped app "start running again after a while". This flag lets the
     * watchdog and the services' sticky-restart guards keep a swiped app dead until
     * the user actually touches it again. An OS/OEM kill never runs [onTaskSwiped],
     * so the flag stays false there and the watchdog still recovers the process.
     */
    fun isSwipedAway(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SWIPED_AWAY, false)

    /** Clear the persistent swipe flag — called on a legitimate app open / reboot. */
    fun clearSwipedAway(context: Context) {
        try {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SWIPED_AWAY, false)
                .apply()
        } catch (_: Throwable) {}
    }

    /**
     * Called from the `onTaskRemoved` of every foreground service. Stamps the
     * manual-kill flag, stops the other services, writes the user offline, then
     * hard-kills the whole process so nothing lingers in the background.
     */
    fun onTaskSwiped(context: Context) {
        if (!shuttingDown.compareAndSet(false, true)) return
        val app = context.applicationContext

        // Stamp the deliberate-kill flag synchronously so any START_STICKY
        // restart (null intent) aborts instead of resurrecting a service. Also set
        // the PERSISTENT swipe flag so the ~15-min WorkManager watchdog (which long
        // outlives the 15s window) doesn't bring the app back from the dead.
        try {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_MANUAL_KILL_AT, System.currentTimeMillis())
                .putBoolean(KEY_SWIPED_AWAY, true)
                .commit()
        } catch (_: Throwable) {}

        // Best-effort: cancel the periodic watchdog outright. The persistent flag
        // above is the authoritative guard (this cancel may not commit before the
        // process dies); MainActivity re-schedules it on the next legitimate open.
        try { com.vrca.keepalive.PipelineWatchdogWorker.cancel(app) } catch (_: Throwable) {}

        // A deliberate swipe is an intentional stop — disarm feature-session restore
        // so the next launch starts clean (matches "toggles start OFF on a fresh
        // launch"). An OS-initiated kill never runs this, so it stays armed and the
        // chatbox auto-resumes on the next process start.
        FeatureSessionStore.disarm(app)

        // Clear the process-lifetime ViewModelStore so VrcaViewModel.onCleared()
        // runs exactly once on a genuine shutdown — cancels the chatbox senders and
        // sync loops and fires the going-offline write. (onTaskRemoved is delivered
        // on the main thread, which is the correct thread to clear a ViewModelStore.)
        try {
            (app as? VrcaApplication)?.viewModelStore?.clear()
        } catch (_: Throwable) {}

        // Ask the other foreground services to stop. killProcess below tears the
        // whole process down regardless, but stopping first releases wakelocks
        // and removes their notifications promptly.
        stopOtherServices(app)

        Thread {
            try {
                val task = buildOfflineWriteTask(app)
                if (task != null) {
                    try { Tasks.await(task, 5, TimeUnit.SECONDS) }
                    catch (_: Throwable) {}
                }
            } catch (e: Throwable) {
                Log.w(TAG, "offline write failed", e)
            }
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(0)
        }.start()
    }

    private fun stopOtherServices(app: Context) {
        try {
            if (DiscordRpcService.isRunning) {
                app.startService(Intent(app, DiscordRpcService::class.java).apply {
                    action = DiscordRpcService.ACTION_STOP
                })
            }
        } catch (_: Throwable) {}
        // Stop the pipeline service too so its "Connected as X" persistent
        // notification is removed PROMPTLY on swipe — without this it lingered for
        // up to 5s (the offline-write await) before killProcess tore it down, which
        // looked like the swiped app was still "running / connected as username".
        try { app.stopService(Intent(app, com.vrca.vrchat.VrchatPipelineService::class.java)) } catch (_: Throwable) {}
        try { app.stopService(Intent(app, KeepAliveService::class.java)) } catch (_: Throwable) {}
        try { app.stopService(Intent(app, OverlayService::class.java)) } catch (_: Throwable) {}
    }

    private fun buildOfflineWriteTask(app: Context): com.google.android.gms.tasks.Task<Void>? {
        val deviceHash = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("device_id_hash", "") ?: ""
        if (deviceHash.isBlank()) return null
        return try {
            val presence = VrchatPipelineState.presence
            val data = mutableMapOf<String, Any>(
                "isOnlineInApp" to false,
                // Clean-shutdown marker. The admin's isUserOnline forces a row
                // offline the instant offlineAt is newer than lastActiveAt, so a
                // swipe shows offline immediately when this write lands; if it
                // races the 5s kill timeout and never lands, the ~65-min staleness
                // window still flips the user offline. (This is why "swipe doesn't
                // always go offline" is now self-correcting.)
                "offlineAt" to FieldValue.serverTimestamp(),
                "lastSeenAt" to FieldValue.serverTimestamp(),
                "savedFriendIds" to FieldValue.delete(),
                "savedFriendNames" to FieldValue.delete()
            )
            if (presence != null) {
                data["vrchatState"] = presence.status
                data["vrchatLocation"] = presence.location
                data["vrchatWorldName"] = presence.worldName
                data["vrchatDisplayName"] = presence.displayName
            }
            FirebaseFirestore.getInstance()
                .collection("users").document(deviceHash)
                .set(data, SetOptions.merge())
        } catch (_: Throwable) {
            null
        }
    }
}
