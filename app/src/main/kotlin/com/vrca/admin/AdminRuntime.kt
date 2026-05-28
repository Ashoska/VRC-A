package com.vrca.admin

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-lifetime owner of the admin build's background-critical Firestore WRITES,
 * so they keep running "as if the admin were on screen" when the admin app is
 * backgrounded and its Activity is destroyed. (The Compose layer previously owned
 * these in `LaunchedEffect`, so they died with the Activity.)
 *
 * Two effects move here:
 *  1. **Browsing heartbeat + force-kill staleness sweep** — while the admin is on the
 *     Dashboard/Users tab, write `config/adminPresence.browsingAt` every 30s (this is
 *     what makes watched user apps run their own heartbeats) and, after a grace period,
 *     flip stale `isOnlineInApp` users offline.
 *  2. **Watcher heartbeat** — while a user is selected in the detail view, write
 *     `watcherActiveAt` on that user's doc every 30s (this is what puts the user app
 *     into 10s live-sync mode).
 *
 * The Compose layer only registers INTENT here ([setBrowsing], [setSelectedUser]) and
 * pushes the latest user list for the sweep ([updateSweepData]). The actual periodic
 * writes run on [scope] (app lifetime), torn down only by AppShutdown on a real swipe.
 *
 * Note: the sweep uses the most recent list pushed by the UI. When the admin is
 * backgrounded and the Compose users listener stops, the sweep operates on the
 * last-known snapshot — best-effort, which is its existing role. The browsing and
 * watcher heartbeats (the writes that keep the watched user actively syncing) continue
 * unconditionally.
 */
object AdminRuntime {
    private const val TAG = "AdminRuntime"
    private const val BROWSE_HEARTBEAT_MS = 30_000L
    private const val WATCHER_HEARTBEAT_MS = 30_000L
    private const val SWEEP_GRACE_MS = 90_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db get() = FirebaseFirestore.getInstance()

    private val started = AtomicBoolean(false)

    @Volatile private var selfDeviceHash: String = ""

    // Intent set by the Compose layer.
    private val browsing = MutableStateFlow(false)
    private val selectedUser = MutableStateFlow<String?>(null)

    // Latest user list (for the force-kill sweep). Minimal fields only.
    @Volatile private var sweepData: List<SweepUser> = emptyList()

    data class SweepUser(val docId: String, val isOnlineInApp: Boolean, val lastSeenMs: Long)

    private var browseJob: Job? = null
    private var watcherJob: Job? = null

    /** Idempotent. Call once when the admin UI first appears. */
    fun start(deviceHash: String) {
        selfDeviceHash = deviceHash.trim()
        if (!started.compareAndSet(false, true)) return
        browseJob = scope.launch { runBrowseHeartbeatLoop() }
        watcherJob = scope.launch { runWatcherHeartbeatLoop() }
    }

    fun setBrowsing(active: Boolean) { browsing.value = active }

    fun setSelectedUser(docId: String?) { selectedUser.value = docId?.trim()?.ifBlank { null } }

    fun updateSweepData(users: List<SweepUser>) { sweepData = users }

    val browsingState: StateFlow<Boolean> get() = browsing.asStateFlow()

    private suspend fun runBrowseHeartbeatLoop() {
        var browsingStartedAt = 0L
        while (true) {
            if (browsing.value) {
                if (browsingStartedAt == 0L) browsingStartedAt = System.currentTimeMillis()
                try {
                    db.collection("config").document("adminPresence")
                        .set(mapOf("browsingAt" to FieldValue.serverTimestamp()), SetOptions.merge())
                } catch (_: Throwable) {}

                if (System.currentTimeMillis() - browsingStartedAt > SWEEP_GRACE_MS) {
                    val now = System.currentTimeMillis()
                    for (u in sweepData) {
                        if (!u.isOnlineInApp) continue
                        if (u.lastSeenMs <= 0L) continue
                        if (now - u.lastSeenMs > ONLINE_STALENESS_WINDOW_MS) {
                            try {
                                db.collection("users").document(u.docId)
                                    .set(mapOf("isOnlineInApp" to false), SetOptions.merge())
                            } catch (_: Throwable) {}
                        }
                    }
                }
            } else {
                browsingStartedAt = 0L
            }
            delay(BROWSE_HEARTBEAT_MS)
        }
    }

    private suspend fun runWatcherHeartbeatLoop() {
        while (true) {
            val docId = selectedUser.value
            if (!docId.isNullOrBlank()) {
                try {
                    db.collection("users").document(docId)
                        .set(mapOf("watcherActiveAt" to FieldValue.serverTimestamp()), SetOptions.merge())
                } catch (_: Throwable) {
                    Log.w(TAG, "watcher heartbeat write failed")
                }
            }
            delay(WATCHER_HEARTBEAT_MS)
        }
    }
}
