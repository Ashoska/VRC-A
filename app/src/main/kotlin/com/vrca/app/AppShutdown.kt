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
     * Called from the `onTaskRemoved` of every foreground service. Stamps the
     * manual-kill flag, stops the other services, writes the user offline, then
     * hard-kills the whole process so nothing lingers in the background.
     */
    fun onTaskSwiped(context: Context) {
        if (!shuttingDown.compareAndSet(false, true)) return
        val app = context.applicationContext

        // Stamp the deliberate-kill flag synchronously so any START_STICKY
        // restart (null intent) aborts instead of resurrecting a service.
        try {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_MANUAL_KILL_AT, System.currentTimeMillis())
                .commit()
        } catch (_: Throwable) {}

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
