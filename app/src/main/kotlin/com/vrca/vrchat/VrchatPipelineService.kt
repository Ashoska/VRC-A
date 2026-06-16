package com.vrca.vrchat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import com.vrca.BuildConfig
import com.vrca.app.MainActivity
import com.vrca.R
import com.vrca.discord.DiscordRpcService
import com.vrca.discord.DiscordRpcState
import com.vrca.discord.DiscordRpcStatus
import com.vrca.update.checkFirestoreRelease
import com.vrca.update.ReleaseCheckResult
import com.vrca.data.dataStore
import com.vrca.sync.AdminWatchState
import com.vrca.vrchat.VrchatNotificationPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * VrchatPipelineService
 *
 * Persistent foreground service that:
 *  1. Maintains a WebSocket connection to VRChat's pipeline
 *     (wss://pipeline.vrchat.cloud/?authToken=<cookie>)
 *  2. Fires Android notifications for: friend requests, invites,
 *     group events, group announcements, friend online/offline,
 *     and unfriend detection (by diffing a locally cached friends list)
 *  3. Syncs the user's VRChat presence data to Firestore so the admin
 *     panel always has an up-to-date view even when the app is closed
 *  4. Detects ban-evasion: after VRChat login, cross-checks the
 *     vrchatUserId against the bannedIdentifiers collection
 *
 * The WebSocket is receive-only per VRChat's design.
 * Reconnects automatically with exponential backoff on disconnect.
 */
class VrchatPipelineService : Service() {

    companion object {
        private const val TAG = "VrcPipeline"
        private const val PIPELINE_URL = "wss://pipeline.vrchat.cloud"
        private const val USER_AGENT = "VRC-A-Companion/1.0 (Android; companion app)"

        private const val NOTIF_CHANNEL_PERSISTENT = "vrca_pipeline"
        // Legacy single-channel ID — kept only so we can delete it on upgrade.
        private const val NOTIF_CHANNEL_EVENTS_LEGACY = "vrca_vrchat_events"
        // Category-specific channels: each gets independent Android-level
        // user control (sound/vibrate/badge per category).
        private const val NOTIF_CHANNEL_FRIENDS_ACTIVITY = "vrca_friends_activity"
        private const val NOTIF_CHANNEL_FRIEND_REQUESTS  = "vrca_friend_requests"
        private const val NOTIF_CHANNEL_FRIEND_REMOVALS  = "vrca_friend_removals"
        private const val NOTIF_CHANNEL_INVITES          = "vrca_invites"
        private const val NOTIF_CHANNEL_GROUPS           = "vrca_groups"
        private const val NOTIF_CHANNEL_CONNECTION       = "vrca_connection"
        // Group keys for stacking related notifications in the shade.
        private const val GROUP_KEY_FRIENDS  = "vrca_group_friends"
        private const val GROUP_KEY_SOCIAL   = "vrca_group_social"
        private const val GROUP_KEY_INVITES  = "vrca_group_invites"
        private const val GROUP_KEY_GROUPS   = "vrca_group_groups"
        // Reserved IDs for stable single-slot notifications.
        private const val NOTIF_ID_PERSISTENT      = 1001
        private const val NOTIF_ID_AUTH            = 9998
        private const val NOTIF_ID_CONNECTION      = 9997
        // Group summary IDs (stable per group).
        private const val NOTIF_ID_SUMMARY_FRIENDS = 9000
        private const val NOTIF_ID_SUMMARY_SOCIAL  = 9001
        private const val NOTIF_ID_SUMMARY_INVITES = 9002
        private const val NOTIF_ID_SUMMARY_GROUPS  = 9003

        private const val NOTIF_ID_APP_UPDATE = 9995

        const val ACTION_START = "com.vrca.PIPELINE_START"
        const val ACTION_STOP = "com.vrca.PIPELINE_STOP"

        // Extras passed when starting
        const val EXTRA_DEVICE_HASH = "device_hash"


        /** Send this intent to check if the service is running */
        fun isRunning(context: Context): Boolean {
            // Simple flag stored in application-level state
            return VrchatPipelineState.isConnected
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Single-threaded dispatcher that serializes ALL friendsCache read-modify-write
    // work (every WebSocket event handler + the REST profile-refresh diff). Pipeline
    // messages used to each launch on the multi-threaded IO dispatcher, so two events
    // touching the same friend ran concurrently — one's cache write clobbered the
    // other's and a diff read a stale `prev`, silently DROPPING a notification. The
    // classic symptoms: a fast back-to-back bio edit only firing once (or not at all),
    // and a quick friend→unfriend where the unfriend read the cache before friend-add
    // had written the entry (→ `?: return` drop). Serializing onto one thread makes
    // each handler's read-modify-write atomic and preserves VRChat's send ordering
    // (friend-add is processed before the friend-delete that followed it).
    private val pipelineDispatcher =
        java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var wsJob: Job? = null
    private var webSocket: WebSocket? = null
    private var reconnectAttempt = 0

    private val friendsCache = mutableMapOf<String, FriendCacheEntry>()
    private val pendingOffline = mutableMapOf<String, Long>()
    private val OFFLINE_COOLDOWN_MS = 10 * 60 * 1000L
    private var friendsCacheLoaded = false
    private var friendsFetchCount = 0
    private var lastUnfriendDiffMs = 0L
    // Tracks user IDs for which an unfriend notification has already been fired
    // this session, so the real-time and offline-diff handlers don't both fire.
    private val notifiedUnfriendIds = mutableSetOf<String>()
    private val seenNotifIds = LinkedHashSet<String>()
    private val MAX_SEEN_NOTIF_IDS = 500
    // Per-friend throttle for chatty events (location/avatar/status). One
    // notification per friend per LOCATION_NOTIF_COOLDOWN_MS prevents spam
    // when a friend rapidly hops worlds or swaps avatars.
    private val lastFriendLocationNotifMs = mutableMapOf<String, Long>()
    private val lastFriendAvatarNotifMs = mutableMapOf<String, Long>()
    private val lastFriendStatusNotifMs = mutableMapOf<String, Long>()
    private val LOCATION_NOTIF_COOLDOWN_MS = 60_000L
    private val WARMUP_MS = 30_000L
    private var pipelineConnectedAtMs = 0L
    private val recentlyRelocated = mutableMapOf<String, Long>()
    // Maps groupId -> group name, populated whenever we fetch the user's groups.
    // Lets the notification/announcement handlers show a real group name instead
    // of "null" when the websocket/REST payload only carries a groupId.
    private val groupNameCache = mutableMapOf<String, String>()
    private var presenceRefreshJob: Job? = null
    // Tracks last connection notification state so we don't spam connect/disconnect.
    private var lastConnectionNotifWasUp: Boolean? = null
    private var lastConnectedNotifText: String? = null
    // WebSocket health check: tracks when the last message was received.
    private var lastMessageReceivedMs = 0L
    private var notifRepostIterations = 0

    // Announcements listener (Firestore)
    private var announcementsListener: ListenerRegistration? = null
    private var seenAnnouncementIds = mutableSetOf<String>()
    private var announcementsInitialSnapshotDone = false

    // VRChat status page polling
    private var statusPageJob: Job? = null
    private var lastStatusAllClearMs = 0L
    private val STATUS_POLL_INTERVAL_MS = 120_000L  // 2 minutes
    private val STATUS_AUTO_DISMISS_MS = 20 * 60 * 1000L  // 20 minutes
    private val NOTIF_ID_VRCHAT_STATUS = 9996

    private val okClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    private var deviceHash: String = ""

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        loadSeenNotifIds()
        loadSeenAnnouncementIds()
        InAppAlertState.load(this)
        createNotificationChannels()
        attachAnnouncementsListener()
        startStatusPagePolling()
        startAppUpdateCheckLoop()
        startAuthRefreshLoop()
        startGroupAnnouncementPollLoop()
        startFriendsProfileRefreshLoop()

        // Re-post the persistent foreground notification if it gets swiped.
        // Also performs a WebSocket health check every 5th iteration (~50s):
        // if the pipeline should be connected but hasn't received any messages
        // in 5+ minutes, force a reconnect.
        serviceScope.launch {
            while (true) {
                delay(10_000)
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val active = nm.activeNotifications.any { it.id == NOTIF_ID_PERSISTENT }
                if (!active) {
                    val status = if (VrchatPipelineState.isConnected) {
                        lastConnectedNotifText ?: "Connected"
                    } else "Running"
                    startForeground(NOTIF_ID_PERSISTENT, buildPersistentNotification(status))
                }
                // WebSocket health check every 5th iteration (~50s)
                notifRepostIterations++
                if (notifRepostIterations % 5 == 0 && VrchatPipelineState.isConnected &&
                    lastMessageReceivedMs > 0 &&
                    System.currentTimeMillis() - lastMessageReceivedMs > 5 * 60 * 1000L) {
                    Log.w(TAG, "No WebSocket messages in 5min — forcing reconnect")
                    lastMessageReceivedMs = 0L
                    scheduleReconnect()
                }
            }
        }

        // Refresh persistent notification when Discord RPC status changes.
        serviceScope.launch {
            DiscordRpcState.statusFlow.collect {
                val status = if (VrchatPipelineState.isConnected) {
                    lastConnectedNotifText ?: "Connected"
                } else "Running"
                updatePersistentNotif(status)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // A null intent means Android is sticky-restarting us. If the user
                // just swiped the app away (manual_kill flag fresh), honour the
                // kill instead of resurrecting the service — otherwise START_STICKY
                // brings the whole process straight back and it looks "not killed".
                if (intent == null &&
                    (com.vrca.app.AppShutdown.isManualKillFresh(this) ||
                        com.vrca.app.AppShutdown.isSwipedAway(this))) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    // Android sticky-restarted us into a fresh process that would
                    // otherwise linger as a cached background process. Kill it
                    // outright after this method returns START_NOT_STICKY (so it
                    // won't be recreated again). The brief delay lets the return
                    // value register before the process dies.
                    val appCtx = applicationContext
                    Thread {
                        try { Thread.sleep(300) } catch (_: Throwable) {}
                        // Sweep the shared persistent notification (id 1001) in
                        // case the stopForeground removal races the kill — the
                        // cancel runs in system_server so it survives our death.
                        com.vrca.app.AppShutdown.cancelPersistentNotification(appCtx)
                        android.os.Process.killProcess(android.os.Process.myPid())
                        kotlin.system.exitProcess(0)
                    }.start()
                    return START_NOT_STICKY
                }
                // Prefer deviceHash from the intent extra, but fall back to the
                // SharedPreferences value so a sticky restart with null intent
                // (Android killed the service due to memory pressure) still
                // syncs to the right Firestore doc.
                val fromIntent = intent?.getStringExtra(EXTRA_DEVICE_HASH).orEmpty()
                if (fromIntent.isNotBlank()) {
                    deviceHash = fromIntent
                } else if (deviceHash.isBlank()) {
                    deviceHash = applicationContext
                        .getSharedPreferences("vrca_remote", Context.MODE_PRIVATE)
                        .getString("device_id_hash", "") ?: ""
                }
                // If the pipeline is already connected (duplicate ACTION_START
                // from a reconnect, system restart, or the user reopening the app
                // after pressing Back), don't tear it down and start over — that's
                // what causes friend caches to flush and VRChat presence to flicker.
                // Crucially, do NOT reset the notification to "Connecting..." in
                // that case: onOpen won't fire again to restore "Connected as X",
                // so the notification would get permanently stuck on "Connecting..."
                // even though presence and RPC are live. Re-assert foreground with
                // the CURRENT connected status instead.
                if (VrchatPipelineState.isConnected) {
                    startForeground(
                        NOTIF_ID_PERSISTENT,
                        buildPersistentNotification(lastConnectedNotifText ?: "Connected")
                    )
                } else {
                    startForeground(NOTIF_ID_PERSISTENT, buildPersistentNotification("Connecting..."))
                    startPipeline()
                }
            }
        }
        return START_STICKY  // restart if killed
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Delegate to the shared coordinator so the offline write + full process
        // kill happens exactly once regardless of which service fires first.
        com.vrca.app.AppShutdown.onTaskSwiped(this)
    }

    private fun isInWarmup(): Boolean =
        pipelineConnectedAtMs > 0 && System.currentTimeMillis() - pipelineConnectedAtMs < WARMUP_MS

    override fun onDestroy() {
        announcementsListener?.remove()
        announcementsListener = null
        statusPageJob?.cancel()
        webSocket?.cancel()
        serviceScope.cancel()
        pipelineDispatcher.close()
        VrchatPipelineState.isConnected = false
        VrchatPipelineState.presence = null
        VrchatPipelineState.statusPageState = null
        super.onDestroy()
    }


    // ------------------------------------------------------------------
    // Pipeline management
    // ------------------------------------------------------------------

    private fun startPipeline() {
        wsJob?.cancel()
        wsJob = serviceScope.launch {
            // Validate session (also refreshes cookies if expired)
            var valid = VrchatAuthManager.validateSession(this@VrchatPipelineService)
            if (!valid && VrchatAuthManager.hasSavedCredentials(this@VrchatPipelineService)) {
                Log.i(TAG, "Session invalid, attempting auto re-login...")
                updatePersistentNotif("Session expired - re-logging in...")
                valid = VrchatAuthManager.autoRelogin(this@VrchatPipelineService)
            }
            if (!valid) {
                updatePersistentNotif("Not logged in to VRChat - tap to sign in")
                VrchatPipelineState.isConnected = false
                fireNotLoggedInNotification()
                return@launch
            }

            if (!friendsCacheLoaded) {
                restoreFriendsCache()
                // Snapshot from the local cache (last persisted friends list) is
                // what the diff compares against. Captured BEFORE the first fresh
                // API call so offline-unfriends (people removed since last
                // session) are detected with their correct display names.
                val previousIds = friendsCache.keys.toSet()
                val previousNames = friendsCache.toMap()
                loadFriendsCache()
                friendsFetchCount++

                // Detect bio/name changes that happened while the app was offline
                // by comparing the old persisted cache against the fresh API data.
                if (!isInWarmup() && previousNames.isNotEmpty()) {
                    for ((userId, newEntry) in friendsCache) {
                        val old = previousNames[userId] ?: continue
                        // Name change detection
                        if (newEntry.displayName != old.displayName &&
                            old.displayName.isNotBlank() && newEntry.displayName.isNotBlank()) {
                            val nameGroupKey = "name_$userId"
                            fireEventNotification(
                                id = nameGroupKey.hashCode(),
                                title = "Friend renamed",
                                text = "${old.displayName} is now known as ${newEntry.displayName}",
                                profileUrl = "https://vrchat.com/home/user/$userId",
                                prefKey = VrchatNotificationPrefs.KEY_NOTIF_FRIEND_DISPLAY_NAME,
                                channelId = NOTIF_CHANNEL_FRIENDS_ACTIVITY,
                                groupKey = GROUP_KEY_FRIENDS,
                                bigText = "Was: ${old.displayName}\nNow: ${newEntry.displayName}",
                                alertBody = "${old.displayName} → ${newEntry.displayName}",
                                alertBeforeText = old.displayName,
                                alertAfterText = newEntry.displayName,
                                alertGroupKey = nameGroupKey
                            )
                        }
                        // Bio change detection — fires on edit, clear (→empty),
                        // or add (empty→). Both sides come from full fetches so
                        // the bio field is reliable; only empty→empty is ignored.
                        if (newEntry.bio != old.bio &&
                            (newEntry.bio.isNotBlank() || old.bio.isNotBlank())) {
                            val bioGroupKey = "bio_$userId"
                            val bioTitle = when {
                                newEntry.bio.isBlank() -> "${newEntry.displayName} cleared their bio"
                                old.bio.isBlank() -> "${newEntry.displayName} added a bio"
                                else -> "${newEntry.displayName} updated bio"
                            }
                            val beforeShown = old.bio.ifBlank { "(empty)" }
                            val afterShown = newEntry.bio.ifBlank { "(empty)" }
                            val bioAlertBody = "Before: $beforeShown\nAfter: $afterShown"
                            fireEventNotification(
                                id = bioGroupKey.hashCode(),
                                title = bioTitle,
                                text = bioTitle,
                                profileUrl = "https://vrchat.com/home/user/$userId",
                                prefKey = VrchatNotificationPrefs.KEY_NOTIF_FRIEND_BIO,
                                channelId = NOTIF_CHANNEL_FRIENDS_ACTIVITY,
                                groupKey = GROUP_KEY_FRIENDS,
                                bigText = bioAlertBody,
                                alertBody = bioAlertBody,
                                alertBeforeText = beforeShown,
                                alertAfterText = afterShown,
                                alertGroupKey = bioGroupKey
                            )
                        }
                    }
                }

                // Two-fetch confirmation: candidate removals are IDs absent from
                // the FIRST fetch. These don't fire notifications yet — only IDs
                // still missing in the SECOND fetch (60s later) get notified.
                // Eliminates false positives from VRChat API pagination flaps
                // where a single fetch occasionally returns an incomplete list.
                val candidateRemovals = if (previousIds.isNotEmpty())
                    previousIds - friendsCache.keys else emptySet()

                if (previousIds.isNotEmpty()) {
                    serviceScope.launch(pipelineDispatcher) {
                        delay(60_000)
                        loadFriendsCache()
                        friendsFetchCount++
                        diffFriendsCache(previousIds, previousNames, candidateRemovals)
                    }
                }
            }

            // Backfill notifications that arrived while offline
            serviceScope.launch { backfillOfflineNotifications() }

            // Sync initial presence to Firestore
            syncPresenceToFirestore()

            startPresenceRefreshLoop()
            connectWebSocket()
        }
    }

    private fun startPresenceRefreshLoop() {
        presenceRefreshJob?.cancel()
        presenceRefreshJob = serviceScope.launch {
            // Slow poll: keeps in-app presence (location, world, status) fresh
            // even when no admin is watching. The Firestore write inside
            // syncPresenceToFirestore is gated by AdminWatchState, so this
            // loop only updates VrchatPipelineState locally and pays no
            // Firestore traffic when unwatched. Skipped when watched since
            // the 5s watched-user loop below already refreshes presence —
            // running both would duplicate VRChat HTTP fetches and Firestore
            // writes.
            launch {
                while (true) {
                    if (!AdminWatchState.isWatched.value) {
                        try {
                            syncPresenceToFirestore(forceLocalUpdate = true)
                        } catch (e: Exception) {
                            Log.w(TAG, "Slow presence refresh failed", e)
                        }
                    }
                    delay(10_000)
                }
            }
            // NOTE: the old 30s browse-gated heartbeat and 5-min always-on
            // lastSeenAt heartbeat were removed. Liveness is now a single
            // once-per-hour write owned by VrcaViewModel.startHourlyHeartbeat()
            // (lastActiveAt), and directory browsing no longer causes any
            // per-user writes. The watched-user loop below still streams
            // presence (including lastActiveAt) every 10s while an admin has
            // this user's detail open.
            // Fast poll only while an admin is actively watching this user.
            // collectLatest cancels the inner loop the moment the watch flag
            // flips back to false, so Firestore traffic stops instantly.
            AdminWatchState.isWatched.collectLatest { watched ->
                if (watched) {
                    while (true) {
                        delay(10_000)
                        try {
                            syncPresenceToFirestore()
                        } catch (e: Exception) {
                            Log.w(TAG, "Presence refresh failed", e)
                        }
                    }
                }
            }
        }
    }

    // The public-side `config/adminPresence` listener was REMOVED. Under the
    // hourly model nothing reads AdminBrowsingState to drive a write (the browse-
    // volatile loop is a no-op), so the only effect of this listener was that the
    // admin's 30s `browsingAt` heartbeat fanned a billed read out to EVERY online
    // user's listener (~120k reads/hour at 1k users) for zero benefit. The admin
    // side no longer writes `browsingAt` either (see AdminRuntime).

    private suspend fun connectWebSocket() {
        val cookieHeader = VrchatAuthManager.getCookieHeader(this)
        if (cookieHeader == null) {
            Log.w(TAG, "No VRChat cookie — falling back to startPipeline for session recovery")
            startPipeline()
            return
        }
        val authToken = cookieHeader.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("auth=") }
            ?.removePrefix("auth=")
        if (authToken == null) {
            Log.w(TAG, "No auth token in cookie — falling back to startPipeline for session recovery")
            startPipeline()
            return
        }

        val url = "$PIPELINE_URL/?authToken=$authToken"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        withContext(Dispatchers.IO) {
            okClient.newWebSocket(request, object : WebSocketListener() {

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "Pipeline connected")
                    this@VrchatPipelineService.webSocket = webSocket
                    reconnectAttempt = 0
                    VrchatPipelineState.isConnected = true
                    pipelineConnectedAtMs = System.currentTimeMillis()
                    val displayName = VrchatAuthManager.getStoredDisplayName(this@VrchatPipelineService) ?: "VRChat user"
                    val notifText = "Connected as $displayName"
                    lastConnectedNotifText = notifText
                    updatePersistentNotif(notifText)
                    serviceScope.launch { fireConnectionNotification(true) }
                    // Profile pictures are NOT written to Firestore (cost) and NOT
                    // shown in the admin panel (AdminAvatar renders name initials).
                    // Auto-start Discord RPC if enabled
                    serviceScope.launch {
                        try {
                            val prefs = dataStore.data.first()
                            val rpcEnabled = prefs[booleanPreferencesKey("discord_rpc_enabled")] ?: false
                            val sessionSeeded = prefs[booleanPreferencesKey("discord_session_seeded")] ?: false
                            if (rpcEnabled && sessionSeeded && !DiscordRpcService.isRunning) {
                                val rpcIntent = Intent(this@VrchatPipelineService, DiscordRpcService::class.java)
                                rpcIntent.action = DiscordRpcService.ACTION_START
                                startForegroundService(rpcIntent)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not auto-start Discord RPC", e)
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    lastMessageReceivedMs = System.currentTimeMillis()
                    // Serialize message processing onto the single-threaded pipeline
                    // dispatcher so concurrent events can't clobber the friends cache.
                    serviceScope.launch(pipelineDispatcher) { handlePipelineMessage(text) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "Pipeline failure: ${t.message}")
                    VrchatPipelineState.isConnected = false
                    serviceScope.launch { fireConnectionNotification(false) }
                    scheduleReconnect()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "Pipeline closed: $code $reason")
                    VrchatPipelineState.isConnected = false
                    serviceScope.launch { fireConnectionNotification(false) }
                    scheduleReconnect()
                }
            })
        }
    }

    private fun scheduleReconnect() {
        // Dedup: a single socket failure fires BOTH onFailure and onClosed, and the
        // ~50s health check can also call this — each previously launched its own
        // reconnect coroutine, so multiple backoff timers ran and each opened a NEW
        // WebSocket. The result was several overlapping pipeline sockets racing (every
        // one of which can independently fail/close and spawn yet more), which itself
        // reads as "the pipeline keeps disconnecting." If a reconnect is already
        // pending (mid-backoff) or in progress, don't stack another. wsJob completes
        // quickly once connectWebSocket() hands the socket off, so a genuine later
        // failure still schedules a fresh retry.
        if (wsJob?.isActive == true) return
        wsJob = serviceScope.launch {
            val backoffMs = (minOf(reconnectAttempt, 6) * 10_000L).coerceAtLeast(5_000L)
            reconnectAttempt++
            updatePersistentNotif("Reconnecting in ${backoffMs / 1000}s...")
            delay(backoffMs)
            if (!VrchatAuthManager.isLoggedIn(this@VrchatPipelineService)) {
                startPipeline()
                return@launch
            }
            // isLoggedIn() only checks the auth cookie is PRESENT, not valid.
            // VRChat binds the auth cookie to the client IP, which changes
            // constantly on mobile — so a present cookie is often already dead,
            // and reconnecting with it just fails again forever. After the first
            // (likely transient) failure, validate the session and auto-relogin
            // before reconnecting so an expired/IP-invalidated session recovers
            // on its own instead of looping on a dead authToken.
            if (reconnectAttempt >= 2) {
                val valid = VrchatAuthManager.validateSession(this@VrchatPipelineService)
                if (!valid) {
                    updatePersistentNotif("Refreshing VRChat session...")
                    VrchatAuthManager.autoRelogin(this@VrchatPipelineService)
                }
            }
            updatePersistentNotif("Connecting...")
            connectWebSocket()
        }
    }

    // ------------------------------------------------------------------
    // Message handling
    // ------------------------------------------------------------------

    private suspend fun handlePipelineMessage(raw: String) {
        try {
            val json = JSONObject(raw)
            val type = json.optString("type")
            val contentRaw = json.optString("content")

            // content is a stringified JSON object in most cases
            val content = try {
                if (contentRaw.startsWith("{") || contentRaw.startsWith("["))
                    JSONObject(contentRaw) else null
            } catch (e: Exception) { null }

            when (type) {
                "friend-add" -> {
                    val userId = content?.optString("userId") ?: return
                    val user = content.optJSONObject("user")
                    val displayName = user?.optString("displayName") ?: userId
                    friendsCache[userId] = entryFromUserJson(user, displayName)
                    persistFriendsCache()
                    fireEventNotification(
                        id = "newfriend_$userId".hashCode(),
                        title = "New friend",
                        text = "You and $displayName are now friends",
                        profileUrl = "https://vrchat.com/home/user/$userId",
                        prefKey = VrchatNotificationPrefs.KEY_NOTIF_NEW_FRIEND,
                        channelId = NOTIF_CHANNEL_FRIEND_REQUESTS,
                        groupKey = GROUP_KEY_SOCIAL,
                        alertBody = "You and $displayName are now friends",
                        alertGroupKey = "friend_$userId"
                    )
                }

                "friend-delete" -> {
                    val userId = content?.optString("userId") ?: return
                    val cached = friendsCache[userId] ?: return
                    if (!notifiedUnfriendIds.add(userId)) {
                        friendsCache.remove(userId)
                        persistFriendsCache()
                        return
                    }

                    // Verify via /users/{id} before notifying. Pipeline events
                    // are usually accurate, but VRChat has been known to emit
                    // spurious friend-delete during friend list resyncs. If
                    // isFriend is still true, treat it as a flap and keep
                    // them in the cache. If we can't verify, default to the
                    // historical behavior (remove + notify).
                    val stillFriend = VrchatAuthManager.verifyStillFriend(this@VrchatPipelineService, userId)
                    if (stillFriend == true) {
                        notifiedUnfriendIds.remove(userId)
                        Log.i(TAG, "friend-delete $userId verified still friends — suppressing")
                        return
                    }

                    friendsCache.remove(userId)
                    persistFriendsCache()
                    fireEventNotification(
                        id = "unfriend_$userId".hashCode(),
                        title = "Friend removed",
                        text = "${cached.displayName} is no longer on your friends list",
                        profileUrl = "https://vrchat.com/home/user/$userId",
                        prefKey = VrchatNotificationPrefs.KEY_NOTIF_UNFRIEND,
                        channelId = NOTIF_CHANNEL_FRIEND_REMOVALS,
                        groupKey = GROUP_KEY_SOCIAL,
                        alertBody = "${cached.displayName} is no longer on your friends list",
                        alertGroupKey = "unfriend_$userId"
                    )
                }

                "friend-online" -> {
                    val userId = content?.optString("userId") ?: return
                    val user = content.optJSONObject("user")
                    val displayName = user?.optString("displayName")
                        ?: friendsCache[userId]?.displayName ?: userId
                    val location = content.optString("location", "")
                    val worldName = content.optJSONObject("world")?.optString("name").orEmpty()
                        .ifBlank { user?.optString("worldName").orEmpty() }
                        .ifBlank { user?.optJSONObject("world")?.optString("name").orEmpty() }
                    friendsCache[userId] = entryFromUserJson(user, displayName).copy(
                        location = location,
                        worldName = worldName
                    )
                    persistFriendsCache()
                    pendingOffline.remove(userId)
                    if (!isInWarmup()) {
                        val notifText = when {
                            location == "traveling" -> "$displayName is traveling between worlds"
                            location == "private" -> "$displayName is now online in a private instance"
                            worldName.isNotBlank() -> "$displayName is now online in $worldName"
                            else -> "$displayName is now online"
                        }
                        fireEventNotification(
                            id = "online_$userId".hashCode(),
                            title = "Friend online",
                            text = notifText,
                            profileUrl = "https://vrchat.com/home/user/$userId",
                            prefKey = VrchatNotificationPrefs.KEY_NOTIF_FRIEND_ONLINE,
                            channelId = NOTIF_CHANNEL_FRIENDS_ACTIVITY,
                            groupKey = GROUP_KEY_FRIENDS
                        )
                    }
                }

                "friend-active" -> {
                    val userId = content?.optString("userId") ?: return
                    val user = content.optJSONObject("user")
                    val displayName = user?.optString("displayName")
                        ?: friendsCache[userId]?.displayName ?: userId
                    val previous = friendsCache[userId]
                    friendsCache[userId] = entryFromUserJson(user, displayName).copy(
                        location = previous?.location.orEmpty(),
                        worldName = previous?.worldName.orEmpty()
                    )
                    persistFriendsCache()
                    if (!isInWarmup()) {
                        fireEventNotification(
                            id = "active_$userId".hashCode(),
                            title = "Friend on the website",
                            text = "$displayName is active on the VRChat website",
                            profileUrl = "https://vrchat.com/home/user/$userId",
                            prefKey = VrchatNotificationPrefs.KEY_NOTIF_FRIEND_ACTIVE,
                            channelId = NOTIF_CHANNEL_FRIENDS_ACTIVITY,
                            groupKey = GROUP_KEY_FRIENDS
                        )
                    }
                }

                "friend-offline" -> {
                    val userId = content?.optString("userId") ?: return
                    pendingOffline[userId] = System.currentTimeMillis()
                    serviceScope.launch(pipelineDispatcher) {
                        delay(OFFLINE_COOLDOWN_MS)
                        if (pendingOffline.containsKey(userId)) {
                            pendingOffline.remove(userId)
                            // Confirmed offline (no friend-online flap within the
                            // cooldown) — reflect it in the cache so the friends-
                            // online count drops; the entry itself is kept for
                            // unfriend notifications. A transient flap was cleared
                            // from pendingOffline by friend-online and never lands.
                            friendsCache[userId]?.let {
                                friendsCache[userId] = it.copy(status = "offline", location = "offline")
                                persistFriendsCache()
                            }
                            val displayName = friendsCache[userId]?.displayName ?: "A friend"
                            fireEventNotification(
                                id = "offline_$userId".hashCode(),
                                title = "Friend offline",
                                text = "$displayName went offline",
                                profileUrl = "https://vrchat.com/home/user/$userId",
                                prefKey = VrchatNotificationPrefs.KEY_NOTIF_FRIEND_OFFLINE,
                                channelId = NOTIF_CHANNEL_FRIENDS_ACTIVITY,
                                groupKey = GROUP_KEY_FRIENDS
                            )
                        }
                    }
                }

                "notification", "notification-v2" -> {
                    handleNotificationEvent(content, type)
                }

                "friend-location" -> {
                    val userId = content?.optString("userId") ?: return
                    val user = content.optJSONObject("user")
                    val displayName = user?.optString("displayName")
                        ?: friendsCache[userId]?.displayName ?: userId
                    val location = content.optString("location", "")
                    val worldName = content.optJSONObject("world")?.optString("name").orEmpty()
                        .ifBlank { user?.optString("worldName").orEmpty() }
                        .ifBlank { user?.optJSONObject("world")?.optString("name").orEmpty() }
                    val previous = friendsCache[userId]
                    val previousWorld = previous?.worldName.orEmpty()
                    friendsCache[userId] = (previous ?: FriendCacheEntry(displayName)).copy(
                        displayName = displayName,
                        location = location,
                        worldName = worldName
                    )
                    persistFriendsCache()
                    recentlyRelocated[userId] = System.currentTimeMillis()

                    if (!isInWarmup() && worldName.isNotBlank() && worldName != previousWorld &&
                        location.isNotBlank() && location != "private" && location != "traveling" && location != "offline") {
                        val now = System.currentTimeMillis()
                        val lastNotif = lastFriendLocationNotifMs[userId] ?: 0L
                        if (now - lastNotif >= LOCATION_NOTIF_COOLDOWN_MS) {
                            lastFriendLocationNotifMs[userId] = now
                            fireEventNotification(
                                id = "loc_$userId".hashCode(),
                                title = "Friend moved",
                                text = "$displayName joined $worldName",
                                profileUrl = "https://vrchat.com/home/user/$userId",
                                prefKey = VrchatNotificationPrefs.KEY_NOTIF_FRIEND_LOCATION,
                                channelId = NOTIF_CHANNEL_FRIENDS_ACTIVITY,
                                groupKey = GROUP_KEY_FRIENDS
                            )
                        }
                    }

                    val myId = VrchatAuthManager.getStoredUserId(this@VrchatPipelineService)
                    if (userId == myId) {
                        // Reactively update our OWN presence from the event payload so
                        // the Discord RPC / UI reflect the world change instantly, even
                        // if REST polls are failing in the background. Also writes to
                        // Firestore directly when watched (instant admin update).
                        applySelfLocationEvent(content)
                    }
                }

                "user-update" -> {
                    applySelfUserUpdate(content)
                }

                "friend-update" -> {
                    handleFriendUpdate(content)
                }

                "show-alert" -> {
                    val alertText = contentRaw.take(200).ifBlank { "VRChat server alert" }
                    fireEventNotification(
                        id = "alert_${alertText.hashCode()}".hashCode(),
                        title = "VRChat server alert",
                        text = alertText,
                        profileUrl = null,
                        prefKey = VrchatNotificationPrefs.KEY_NOTIF_VRCHAT_ALERT,
                        channelId = NOTIF_CHANNEL_CONNECTION,
                        groupKey = null
                    )
                }

                "user-location" -> {
                    // The current user's OWN world-hop. No notification, but patch our
                    // own presence reactively from the payload so the Discord RPC and
                    // the admin view follow the user into the new world immediately
                    // (the REST poll is unreliable when backgrounded on mobile).
                    applySelfLocationEvent(content)
                }

                "see-notification", "hide-notification",
                "clear-notification", "response-notification", "content-refresh" -> {
                    // Known pipeline events that don't need user notifications.
                }

                else -> {
                    if (type.isNotBlank()) Log.d(TAG, "Unhandled pipeline event: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse pipeline message: $raw", e)
        }
    }

    private suspend fun handleNotificationEvent(content: JSONObject?, type: String) {
        if (content == null) return
        val notifType = content.optString("type")
        val senderUserId = content.optString("senderUserId", "")
        val senderName = content.optString("senderUsername", "someone")
        val message = content.optString("message", "")
        val notifId = content.optString("id", "")

        when {
            notifType == "friendRequest" -> {
                // Collapse a friend-request and the subsequent new-friend into ONE
                // per-person card (shared friend_$userId group key).
                fireEventNotification(
                    id = "fr_$senderUserId".hashCode(),
                    title = "Friend request",
                    text = "$senderName sent you a friend request",
                    profileUrl = "https://vrchat.com/home/user/$senderUserId",
                    prefKey = VrchatNotificationPrefs.KEY_NOTIF_FRIEND_REQUEST,
                    channelId = NOTIF_CHANNEL_FRIEND_REQUESTS,
                    groupKey = GROUP_KEY_SOCIAL,
                    alertBody = "$senderName sent you a friend request",
                    alertGroupKey = "friend_$senderUserId"
                )
            }
            notifType == "invite" -> {
                // Suppress self-invites: when the user invites THEMSELVES (the app's
                // "Invite me" button or VRChat's own self-invite), VRChat echoes an
                // invite notification whose sender is the user's OWN id. Don't surface
                // it — they already know they invited themselves.
                val myId = VrchatAuthManager.getStoredUserId(this@VrchatPipelineService)
                if (senderUserId.isNotBlank() && senderUserId == myId) return
                val det = try {
                    JSONObject(content.optString("details", "{}"))
                } catch (e: Exception) { JSONObject() }
                val worldName = det.optString("worldName", "a world")
                // The instance location we were invited to, so the user can
                // re-invite themselves ("Invite me") to actually join.
                val wId = det.optString("worldId", "")
                val instId = det.optString("instanceId", "")
                val location = when {
                    wId.contains(":") -> wId
                    wId.isNotBlank() && instId.isNotBlank() -> "$wId:$instId"
                    else -> ""
                }
                val canInvite = location.startsWith("wrld_")
                fireEventNotification(
                    id = "inv_${notifId.ifBlank { senderUserId }}".hashCode(),
                    title = "World invite",
                    text = "$senderName invited you to $worldName",
                    // Tapping the notification re-invites you to the instance so you
                    // can join; falls back to the notifications page if we couldn't
                    // parse the location.
                    profileUrl = if (canInvite) null else "https://vrchat.com/home/notifications",
                    prefKey = VrchatNotificationPrefs.KEY_NOTIF_INVITE,
                    channelId = NOTIF_CHANNEL_INVITES,
                    groupKey = GROUP_KEY_INVITES,
                    dedupId = notifId.ifBlank { null },
                    alertBody = "$senderName invited you to $worldName",
                    // World invites fuse PER-INVITER (like bios) into one accumulating
                    // in-app card + Android notification (stop per-invite spam), keeping
                    // the full history — each event retains its own instance location +
                    // world name. Repeat invites to the SAME instance dedup to one entry;
                    // different instances stay separate, so the card's invite symbol
                    // opens an instance picker when that person sent several.
                    alertGroupKey = "worldinvite_${senderUserId.ifBlank { notifId }}",
                    alertEventTitle = worldName.ifBlank { null },
                    alertActionType = if (canInvite) NotificationActionReceiver.ACTION_INVITE_ME else null,
                    alertActionData = if (canInvite) location else null,
                    tapActionType = if (canInvite) NotificationActionReceiver.ACTION_INVITE_ME else null,
                    tapActionData = if (canInvite) location else null,
                    alertDedupByActionData = true
                )
            }
            notifType == "requestInvite" -> {
                // Fulfil the request by inviting the requester to the user's
                // current instance — on notification tap and via the in-app button.
                fireEventNotification(
                    id = "invreq_${senderUserId.ifBlank { notifId }}".hashCode(),
                    title = "Invite request",
                    text = "$senderName is asking for an invite to your instance",
                    profileUrl = if (senderUserId.isNotBlank()) null else "https://vrchat.com/home/notifications",
                    prefKey = VrchatNotificationPrefs.KEY_NOTIF_INVITE_REQUEST,
                    channelId = NOTIF_CHANNEL_INVITES,
                    groupKey = GROUP_KEY_INVITES,
                    dedupId = notifId.ifBlank { null },
                    alertBody = "$senderName is asking for an invite to your instance",
                    alertGroupKey = "invreq_${senderUserId.ifBlank { notifId }}",
                    alertActionType = if (senderUserId.isNotBlank()) NotificationActionReceiver.ACTION_INVITE_USER else null,
                    alertActionData = senderUserId.ifBlank { null },
                    tapActionType = if (senderUserId.isNotBlank()) NotificationActionReceiver.ACTION_INVITE_USER else null,
                    tapActionData = senderUserId.ifBlank { null },
                    alertSingleEvent = true
                )
            }
            notifType == "votetokick" -> {
                fireEventNotification(
                    id = "vtk_${notifId.ifBlank { senderUserId }}".hashCode(),
                    title = "Vote-to-kick",
                    text = message.ifBlank { "A vote-to-kick has been initiated" },
                    profileUrl = null,
                    prefKey = VrchatNotificationPrefs.KEY_NOTIF_VOTE_TO_KICK,
                    channelId = NOTIF_CHANNEL_FRIENDS_ACTIVITY,
                    groupKey = GROUP_KEY_FRIENDS,
                    dedupId = notifId.ifBlank { null }
                )
            }
            notifType == "message" -> {
                fireEventNotification(
                    id = "msg_${notifId.ifBlank { senderUserId }}".hashCode(),
                    title = "VRChat message",
                    text = if (message.isNotBlank()) "$senderName: $message" else "$senderName sent you a message",
                    profileUrl = if (senderUserId.isNotBlank()) "https://vrchat.com/home/user/$senderUserId" else null,
                    prefKey = VrchatNotificationPrefs.KEY_NOTIF_VRCHAT_MESSAGE,
                    channelId = NOTIF_CHANNEL_FRIEND_REQUESTS,
                    groupKey = GROUP_KEY_SOCIAL,
                    dedupId = notifId.ifBlank { null },
                    alertBody = if (message.isNotBlank()) "$senderName: $message" else "$senderName sent you a message",
                    alertGroupKey = if (senderUserId.isNotBlank()) "msg_$senderUserId" else "msg_${notifId.ifBlank { senderName }}"
                )
            }
            notifType == "gift" || notifType == "giftCredits" || notifType == "giftBundle" -> {
                val detail = message.ifBlank { "You received a VRChat gift" }
                fireEventNotification(
                    id = "gift_${notifId.ifBlank { senderUserId }}".hashCode(),
                    title = "Gift received",
                    text = if (senderName != "someone") "$senderName sent you a gift: $detail" else detail,
                    profileUrl = if (senderUserId.isNotBlank()) "https://vrchat.com/home/user/$senderUserId" else null,
                    prefKey = VrchatNotificationPrefs.KEY_NOTIF_GIFT_RECEIVED,
                    channelId = NOTIF_CHANNEL_FRIEND_REQUESTS,
                    groupKey = GROUP_KEY_SOCIAL,
                    dedupId = notifId.ifBlank { null }
                )
            }
            notifType == "requestInviteResponse" -> {
                fireEventNotification(
                    id = "invresp_$senderUserId".hashCode(),
                    title = "Invite request accepted",
                    text = "$senderName accepted your invite request",
                    profileUrl = "https://vrchat.com/home/notifications",
                    prefKey = VrchatNotificationPrefs.KEY_NOTIF_INVITE_REQUEST,
                    channelId = NOTIF_CHANNEL_INVITES,
                    groupKey = GROUP_KEY_INVITES,
                    dedupId = notifId.ifBlank { null }
                )
            }
            notifType == "inviteResponse" -> {
                fireEventNotification(
                    id = "invacpt_$senderUserId".hashCode(),
                    title = "Invite accepted",
                    text = "$senderName accepted your invite",
                    profileUrl = "https://vrchat.com/home/notifications",
                    prefKey = VrchatNotificationPrefs.KEY_NOTIF_INVITE,
                    channelId = NOTIF_CHANNEL_INVITES,
                    groupKey = GROUP_KEY_INVITES,
                    dedupId = notifId.ifBlank { null }
                )
            }
            // notification-v2 group events
            type == "notification-v2" -> {
                val v2Type = content.optString("type", "")
                val v2Title = content.optString("title", "")
                val baseId = if (notifId.isNotBlank()) notifId else "$v2Type-${message.hashCode()}"
                val detailsObj = try {
                    content.optString("details", "").let { d ->
                        if (d.startsWith("{")) JSONObject(d) else null
                    }
                } catch (_: Exception) { null }
                val groupId = content.optString("relatedGroupId", "").ifBlank {
                    content.optString("groupId", "").ifBlank {
                        content.optJSONObject("data")?.optString("groupId", "").orEmpty().ifBlank {
                            detailsObj?.optString("groupId", "").orEmpty().ifBlank {
                                if (senderUserId.startsWith("grp_")) senderUserId
                                else findIdWithPrefix(content, "grp_").orEmpty()
                            }
                        }
                    }
                }
                val eventId = content.optString("eventId", "").ifBlank {
                    content.optJSONObject("data")?.optString("eventId", "").orEmpty().ifBlank {
                        detailsObj?.optString("eventId", "").orEmpty().ifBlank {
                            content.optString("id", "").let { id ->
                                if (id.startsWith("cal_")) id else findIdWithPrefix(content, "cal_").orEmpty()
                            }
                        }
                    }
                }
                if (groupId.isBlank()) {
                    Log.d(TAG, "notification-v2 missing groupId: type=$v2Type sender=$senderUserId payload=${content.toString().take(500)}")
                }
                val groupUrl = if (groupId.isNotBlank() && eventId.isNotBlank())
                    "https://vrchat.com/home/group/$groupId/calendar/$eventId"
                else if (groupId.isNotBlank()) "https://vrchat.com/home/group/$groupId"
                else "https://vrchat.com/home/notifications"
                val v2CreatedMs = parseVrcTimestampMs(content.optString("created_at", ""))
                when {
                    v2Type.contains("announcement", true) || v2Type.contains("post", true) -> {
                        if (groupId.isNotBlank() && isContentFingerprintSeen(groupId, v2Title, message)) {
                            Log.d(TAG, "WS announcement skipped (already fired): group=$groupId title=$v2Title")
                        } else {
                        val displayGroupName = resolveGroupNameAsync(groupId, senderName)
                        val groupKey2 = when {
                            groupId.isNotBlank() -> "announcement_$groupId"
                            displayGroupName != "a group" -> "announcement_name_$displayGroupName"
                            else -> null
                        }
                        val announcementTitle = "Announcement from $displayGroupName"
                        val postUrl = if (groupId.isNotBlank()) "https://vrchat.com/home/group/$groupId/posts" else groupUrl
                        val announcementBody = message.ifBlank { v2Title.ifBlank { "New announcement" } }
                        fireEventNotification(
                            id = baseId.hashCode(),
                            title = announcementTitle,
                            text = "${v2Title.ifBlank { "Announcement" }}: ${announcementBody.take(120)}",
                            profileUrl = postUrl,
                            prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_ANNOUNCEMENT,
                            channelId = NOTIF_CHANNEL_GROUPS,
                            groupKey = GROUP_KEY_GROUPS,
                            dedupId = notifId.ifBlank { null },
                            alertGroupKey = groupKey2,
                            alertBody = announcementBody,
                            alertEventTitle = cleanName(v2Title).ifBlank { null },
                            eventTimestampMs = v2CreatedMs.takeIf { it > 0 }
                        )
                        if (groupId.isNotBlank()) addContentFingerprintToSeen(groupId, v2Title, message)
                        }
                    }
                    v2Type.contains("event", true) || v2Type.contains("calendar", true) -> {
                        if (groupId.isNotBlank() && eventId.isNotBlank() && isEventFingerprintSeen(groupId, eventId)) {
                            Log.d(TAG, "WS event skipped (already fired): group=$groupId event=$eventId")
                        } else {
                        val displayGroupName = resolveGroupNameAsync(groupId, senderName)
                        val groupKey2 = when {
                            groupId.isNotBlank() -> "event_$groupId"
                            displayGroupName != "a group" -> "event_name_$displayGroupName"
                            else -> null
                        }
                        val eventTitleStr = "Event from $displayGroupName"
                        val eventUrl = if (groupId.isNotBlank() && eventId.isNotBlank())
                            "https://vrchat.com/home/group/$groupId/calendar/$eventId"
                        else if (groupId.isNotBlank()) "https://vrchat.com/home/group/$groupId"
                        else groupUrl
                        val eventBody = message.ifBlank { v2Title.ifBlank { "New group event" } }
                        fireEventNotification(
                            id = baseId.hashCode(),
                            title = eventTitleStr,
                            text = "${v2Title.ifBlank { "Event" }}: ${eventBody.take(120)}",
                            profileUrl = eventUrl,
                            prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_EVENT,
                            channelId = NOTIF_CHANNEL_GROUPS,
                            groupKey = GROUP_KEY_GROUPS,
                            dedupId = notifId.ifBlank { null },
                            alertGroupKey = groupKey2,
                            alertBody = eventBody,
                            alertEventTitle = cleanName(v2Title).ifBlank { null },
                            eventTimestampMs = v2CreatedMs.takeIf { it > 0 }
                        )
                        if (groupId.isNotBlank()) addEventFingerprintToSeen(groupId, eventId)
                        }
                    }
                    v2Type.contains("invite", true) -> {
                        val body = message.take(140).ifBlank { "You've been invited to join a group" }
                        fireEventNotification(
                            id = baseId.hashCode(),
                            title = v2Title.ifBlank { "Group invite" },
                            text = body,
                            profileUrl = groupUrl,
                            prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_INVITE,
                            channelId = NOTIF_CHANNEL_INVITES,
                            groupKey = GROUP_KEY_INVITES,
                            dedupId = notifId.ifBlank { null },
                            alertBody = body,
                            alertGroupKey = "groupinvite_$baseId"
                        )
                    }
                    v2Type.contains("queue", true) -> {
                        val body = message.take(140).ifBlank { "Your spot in a group instance queue is ready" }
                        fireEventNotification(
                            id = baseId.hashCode(),
                            title = v2Title.ifBlank { "Queue ready" },
                            text = body,
                            profileUrl = groupUrl,
                            prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_QUEUE,
                            channelId = NOTIF_CHANNEL_GROUPS,
                            groupKey = GROUP_KEY_GROUPS,
                            dedupId = notifId.ifBlank { null },
                            alertBody = body,
                            alertGroupKey = "groupqueue_$baseId"
                        )
                    }
                    v2Type.contains("join", true) && v2Type.contains("request", true) -> {
                        val body = message.take(140).ifBlank { "Someone wants to join one of your groups" }
                        fireEventNotification(
                            id = baseId.hashCode(),
                            title = v2Title.ifBlank { "Group join request" },
                            text = body,
                            profileUrl = groupUrl,
                            prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_JOIN_REQUEST,
                            channelId = NOTIF_CHANNEL_GROUPS,
                            groupKey = GROUP_KEY_GROUPS,
                            dedupId = notifId.ifBlank { null },
                            alertBody = body,
                            alertGroupKey = "groupjoin_$baseId"
                        )
                    }
                    v2Type.contains("role", true) || v2Type.contains("transfer", true) -> {
                        val body = message.take(140).ifBlank { "Your role in a group changed" }
                        fireEventNotification(
                            id = baseId.hashCode(),
                            title = v2Title.ifBlank { "Group role updated" },
                            text = body,
                            profileUrl = groupUrl,
                            prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_ROLE,
                            channelId = NOTIF_CHANNEL_GROUPS,
                            groupKey = GROUP_KEY_GROUPS,
                            dedupId = notifId.ifBlank { null },
                            alertBody = body,
                            alertGroupKey = "grouprole_$baseId"
                        )
                    }
                    v2Type.contains("instance", true) -> {
                        val body = message.take(140).ifBlank { "A new group instance is now joinable" }
                        fireEventNotification(
                            id = baseId.hashCode(),
                            title = v2Title.ifBlank { "Group instance opened" },
                            text = body,
                            profileUrl = groupUrl,
                            prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_INSTANCE,
                            channelId = NOTIF_CHANNEL_GROUPS,
                            groupKey = GROUP_KEY_GROUPS,
                            dedupId = notifId.ifBlank { null },
                            alertBody = body,
                            alertGroupKey = "groupinstance_$baseId"
                        )
                    }
                    v2Type.startsWith("group.") -> {
                        val body = message.take(140).ifBlank { "New activity in one of your groups" }
                        fireEventNotification(
                            id = baseId.hashCode(),
                            title = v2Title.ifBlank { "Group activity" },
                            text = body,
                            profileUrl = groupUrl,
                            prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_EVENT,
                            channelId = NOTIF_CHANNEL_GROUPS,
                            groupKey = GROUP_KEY_GROUPS,
                            dedupId = notifId.ifBlank { null },
                            alertBody = message.ifBlank { body },
                            alertGroupKey = "groupevt_$baseId"
                        )
                    }
                    else -> {
                        // Unknown notification-v2 type — don't silently drop it.
                        // Log the exact type so we can add a dedicated branch, and
                        // fire it through the group channel so the user still sees
                        // it. This is the catch-all that fixes events/announcements
                        // whose type string didn't match the branches above.
                        Log.i(TAG, "notification-v2 unmatched type='$v2Type' title='$v2Title' " +
                            "groupId=$groupId payload=${content.toString().take(400)}")
                        if (v2Title.isNotBlank() || message.isNotBlank()) {
                            fireEventNotification(
                                id = baseId.hashCode(),
                                title = v2Title.ifBlank { "VRChat notification" },
                                text = message.take(140).ifBlank { v2Title },
                                profileUrl = if (groupId.isNotBlank()) groupUrl else "https://vrchat.com/home/notifications",
                                prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_EVENT,
                                channelId = NOTIF_CHANNEL_GROUPS,
                                groupKey = GROUP_KEY_GROUPS,
                                dedupId = notifId.ifBlank { null }
                            )
                        }
                    }
                }
            }
        }
    }

    /** Build a [FriendCacheEntry] from a VRChat user JSON object. */
    private fun entryFromUserJson(user: JSONObject?, displayName: String): FriendCacheEntry {
        if (user == null) return FriendCacheEntry(displayName = displayName)
        return FriendCacheEntry(
            displayName = displayName,
            status = user.optString("status", ""),
            statusDescription = user.optString("statusDescription", ""),
            location = user.optString("location", ""),
            worldName = user.optString("worldName", "")
                .ifBlank { user.optJSONObject("world")?.optString("name").orEmpty() },
            avatarThumb = user.optString("currentAvatarThumbnailImageUrl", ""),
            bio = user.optString("bio", ""),
            trustRank = extractTrustRank(user)
        )
    }

    private fun extractTrustRank(user: JSONObject): String {
        val tags = user.optJSONArray("tags") ?: return ""
        val ranks = listOf(
            "system_trust_legend",
            "system_trust_veteran",
            "system_trust_trusted",
            "system_trust_known",
            "system_trust_basic"
        )
        for (rank in ranks) {
            for (i in 0 until tags.length()) {
                if (tags.optString(i) == rank) return rank
            }
        }
        return ""
    }

    // VRChat's tag names are OFFSET from the displayed rank names (the same
    // mapping VRCX uses): system_trust_known displays as "User",
    // system_trust_trusted as "Known User", system_trust_veteran as
    // "Trusted User", system_trust_legend as "Veteran" (hidden rank).
    private fun prettyTrustRank(rank: String): String = when (rank) {
        "system_trust_legend"  -> "Veteran"
        "system_trust_veteran" -> "Trusted User"
        "system_trust_trusted" -> "Known User"
        "system_trust_known"   -> "User"
        "system_trust_basic"   -> "New User"
        else -> rank.removePrefix("system_trust_").replaceFirstChar { it.uppercase() }
    }

    /**
     * Friend changed VRChat status (Online ↔ Do Not Disturb ↔ Ask Me ↔ Join Me).
     * Throttled per friend so a chatty friend doesn't spam notifications.
     */
    private suspend fun maybeFireStatusChange(
        userId: String, displayName: String, prevStatus: String, newStatus: String
    ) {
        if (newStatus.isBlank() || prevStatus.isBlank() || prevStatus == newStatus) return
        val now = System.currentTimeMillis()
        val last = lastFriendStatusNotifMs[userId] ?: 0L
        if (now - last < LOCATION_NOTIF_COOLDOWN_MS) return
        lastFriendStatusNotifMs[userId] = now
        fireEventNotification(
            id = "status_$userId".hashCode(),
            title = "Friend changed presence",
            text = "$displayName is now ${prettyStatus(newStatus)}",
            profileUrl = "https://vrchat.com/home/user/$userId",
            prefKey = VrchatNotificationPrefs.KEY_NOTIF_FRIEND_STATUS,
            channelId = NOTIF_CHANNEL_FRIENDS_ACTIVITY,
            groupKey = GROUP_KEY_FRIENDS,
            bigText = "$displayName\n\nWas: ${prettyStatus(prevStatus)}\nNow: ${prettyStatus(newStatus)}"
        )
    }

    private fun prettyStatus(s: String): String = when (s.lowercase()) {
        "active"  -> "Online"
        "join me" -> "Join Me"
        "ask me"  -> "Ask Me"
        "busy"    -> "Do Not Disturb"
        "offline" -> "Offline"
        else      -> s.replaceFirstChar { it.uppercase() }
    }

    /**
     * Friend updated their profile — detect specific kinds of change and
     * fire targeted notifications. Each kind is throttled and individually
     * toggleable via prefs.
     */
    private suspend fun handleFriendUpdate(content: JSONObject?) {
        if (content == null) return
        val user = content.optJSONObject("user") ?: return
        val userId = user.optString("id").ifBlank { content.optString("userId") }
        if (userId.isBlank()) return
        val previous = friendsCache[userId] ?: return
        val newDisplayName = user.optString("displayName", previous.displayName)
        val newStatus = user.optString("status", previous.status)
        val newAvatar = user.optString("currentAvatarThumbnailImageUrl", previous.avatarThumb)
        // Only treat bio as changed when the payload explicitly carries the
        // field. Partial friend-update payloads (location/status only) omit
        // bio entirely — defaulting to previous.bio keeps those from firing.
        val bioPresent = user.has("bio")
        val newBio = if (bioPresent) user.optString("bio", "") else previous.bio
        val newRank = extractTrustRank(user).ifBlank { previous.trustRank }
        val newLocation = user.optString("location", previous.location)

        friendsCache[userId] = previous.copy(
            displayName = newDisplayName,
            status = newStatus,
            avatarThumb = newAvatar,
            bio = newBio,
            trustRank = newRank,
            location = newLocation
        )
        persistFriendsCache()

        if (isInWarmup()) return

        val locationChanged = newLocation != previous.location
        val recentRelocation = (System.currentTimeMillis() - (recentlyRelocated[userId] ?: 0L)) < 15_000L

        if (newDisplayName != previous.displayName && previous.displayName.isNotBlank()) {
            val nameGroupKey = "name_$userId"
            fireEventNotification(
                id = nameGroupKey.hashCode(),
                title = "Friend renamed",
                text = "${previous.displayName} is now known as $newDisplayName",
                profileUrl = "https://vrchat.com/home/user/$userId",
                prefKey = VrchatNotificationPrefs.KEY_NOTIF_FRIEND_DISPLAY_NAME,
                channelId = NOTIF_CHANNEL_FRIENDS_ACTIVITY,
                groupKey = GROUP_KEY_FRIENDS,
                bigText = "Was: ${previous.displayName}\nNow: $newDisplayName",
                alertBody = "${previous.displayName} → $newDisplayName",
                alertBeforeText = previous.displayName,
                alertAfterText = newDisplayName,
                alertGroupKey = nameGroupKey
            )
        }

        if (!locationChanged) {
            maybeFireStatusChange(userId, newDisplayName, previous.status, newStatus)
        }

        if (newAvatar.isNotBlank() && newAvatar != previous.avatarThumb &&
            previous.avatarThumb.isNotBlank() && !locationChanged && !recentRelocation) {
            val now = System.currentTimeMillis()
            val last = lastFriendAvatarNotifMs[userId] ?: 0L
            if (now - last >= LOCATION_NOTIF_COOLDOWN_MS) {
                lastFriendAvatarNotifMs[userId] = now
                fireEventNotification(
                    id = "avatar_$userId".hashCode(),
                    title = "Friend changed avatar",
                    text = "$newDisplayName switched to a new avatar",
                    profileUrl = "https://vrchat.com/home/user/$userId",
                    prefKey = VrchatNotificationPrefs.KEY_NOTIF_FRIEND_AVATAR,
                    channelId = NOTIF_CHANNEL_FRIENDS_ACTIVITY,
                    groupKey = GROUP_KEY_FRIENDS
                )
            }
        }

        // Fire on any real bio change the payload reported: edit, clear
        // (text → empty), or add (empty → text). Requires the bio field to be
        // present so partial payloads don't false-fire, and requires at least
        // one side non-blank so empty → empty is ignored.
        if (bioPresent && newBio != previous.bio &&
            (newBio.isNotBlank() || previous.bio.isNotBlank())) {
            val bioGroupKey = "bio_$userId"
            val bioTitle = when {
                newBio.isBlank() -> "$newDisplayName cleared their bio"
                previous.bio.isBlank() -> "$newDisplayName added a bio"
                else -> "$newDisplayName updated bio"
            }
            val beforeShown = previous.bio.ifBlank { "(empty)" }
            val afterShown = newBio.ifBlank { "(empty)" }
            val bioAlertBody = "Before: $beforeShown\nAfter: $afterShown"
            // Stable Android notification ID — updates in place without re-alerting
            fireEventNotification(
                id = bioGroupKey.hashCode(),
                title = bioTitle,
                text = bioTitle,
                profileUrl = "https://vrchat.com/home/user/$userId",
                prefKey = VrchatNotificationPrefs.KEY_NOTIF_FRIEND_BIO,
                channelId = NOTIF_CHANNEL_FRIENDS_ACTIVITY,
                groupKey = GROUP_KEY_FRIENDS,
                bigText = bioAlertBody,
                alertBody = bioAlertBody,
                alertBeforeText = beforeShown,
                alertAfterText = afterShown,
                alertGroupKey = bioGroupKey
            )
        }

        if (newRank.isNotBlank() && newRank != previous.trustRank && previous.trustRank.isNotBlank()) {
            fireEventNotification(
                id = "rank_$userId".hashCode(),
                title = "Friend trust rank changed",
                text = "$newDisplayName is now a ${prettyTrustRank(newRank)}",
                profileUrl = "https://vrchat.com/home/user/$userId",
                prefKey = VrchatNotificationPrefs.KEY_NOTIF_FRIEND_RANK,
                channelId = NOTIF_CHANNEL_FRIENDS_ACTIVITY,
                groupKey = GROUP_KEY_FRIENDS,
                alertBody = "$newDisplayName is now a ${prettyTrustRank(newRank)}",
                alertGroupKey = "rank_$userId"
            )
        }
    }

    // ------------------------------------------------------------------
    // Friends cache
    // ------------------------------------------------------------------

    private suspend fun loadFriendsCache() {
        val friends = VrchatAuthManager.fetchFriends(this)
        friendsCache.clear()
        friends.forEach {
            friendsCache[it.userId] = FriendCacheEntry(
                displayName = it.displayName,
                status = it.status,
                statusDescription = it.statusDescription,
                location = it.location,
                avatarThumb = it.avatarThumb,
                bio = it.bio,
                trustRank = it.trustRank
            )
        }
        friendsCacheLoaded = true
        persistFriendsCache()
        Log.i(TAG, "Loaded ${friendsCache.size} friends into cache")
    }

    // Fast cadence while the app is on-screen, slower steady cadence when
    // backgrounded. BOTH do a FULL sweep (online + offline passes): a friend who
    // edits their bio/name/rank from the WEBSITE or PHONE has VRChat status
    // "offline" in the friends API (web-login does NOT make you "online" there),
    // so they appear ONLY in the offline=true list. This loop is now a BACKUP — the
    // live WebSocket `friend-update` event carries `bio`/`displayName`/`tags` and
    // fires instantly via handleFriendUpdate for online friends — so its only job
    // is the website/phone-edit-while-offline case the WS can't deliver. Cadence was
    // dialed back from 12s/60s to 30s/60s: each sweep is TWO 2-pass REST fetches and
    // every authenticated call rolls the shared, IP-bound `auth` cookie the live
    // pipeline WebSocket also uses, so an over-tight foreground cadence was churning
    // that cookie / hitting 429s and making the WS (and Discord RPC that reads its
    // presence) disconnect far more often. 30s still catches an offline-friend edit
    // within ~30s while ~2.5x'ing the headroom. Notifications fire regardless of
    // foreground state, so the slower background cadence only delays display refresh
    // (which the user isn't looking at), not the notification itself.
    private val FRIENDS_PROFILE_FG_MS = 30_000L
    private val FRIENDS_PROFILE_REFRESH_MS = 60_000L

    /**
     * Periodic friends-profile refresh + diff (BACKUP path).
     *
     * The live WebSocket `friend-update` event reliably carries `bio` /
     * `displayName` / `tags`, so [handleFriendUpdate] already fires bio/name/rank
     * changes INSTANTLY for online friends. This loop exists only to catch the one
     * case the WS can't: a friend editing their profile from the website/phone
     * while their VRChat status is "offline" (VRChat sends no friend-update for
     * offline friends). It re-fetches the FULL friends list and runs the same diff.
     *
     * Foreground-reactive: it sleeps in short slices so foregrounding the app
     * (appForeground false -> true) cuts the long background sleep short and runs a
     * fast sweep within ~2s — without this, reopening the app left the loop stuck on
     * its current 60s background sleep before it sped up.
     *
     * It MERGES profile fields onto the existing cache entries and deliberately
     * preserves the live presence fields (location / worldName / status) that the
     * WebSocket events own — REST friend data lags those, so replacing them would
     * make the friends-online count and "friend moved" notifications flap.
     */
    private fun startFriendsProfileRefreshLoop() {
        serviceScope.launch {
            delay(15_000L)
            while (true) {
                try {
                    refreshFriendsProfilesAndDiff(onlineOnly = false)
                } catch (e: Exception) {
                    Log.w(TAG, "Friends profile refresh failed", e)
                }
                // Sleep in 2s slices so foregrounding the app shortens a background
                // wait immediately instead of waiting out the whole 60s.
                val wasForeground = VrchatPipelineState.appForeground
                val target = if (wasForeground) FRIENDS_PROFILE_FG_MS else FRIENDS_PROFILE_REFRESH_MS
                var waited = 0L
                while (waited < target) {
                    delay(2_000L)
                    waited += 2_000L
                    if (!wasForeground && VrchatPipelineState.appForeground) break
                }
            }
        }
    }

    private suspend fun refreshFriendsProfilesAndDiff(onlineOnly: Boolean) {
        if (!VrchatAuthManager.isLoggedIn(this) || !friendsCacheLoaded || isInWarmup()) return
        if (friendsCache.isEmpty()) return
        val fresh = VrchatAuthManager.fetchFriends(this, onlineOnly = onlineOnly)
        if (fresh.isEmpty()) return

        // Run the diff + cache write on the single-threaded pipeline dispatcher so it
        // is serialized with the live WebSocket handlers and can't clobber (or be
        // clobbered by) a concurrent friend-update / friend-location.
        kotlinx.coroutines.withContext(pipelineDispatcher) {
        var changed = false
        for (f in fresh) {
            val userId = f.userId
            val prev = friendsCache[userId] ?: continue

            // Name change
            if (f.displayName != prev.displayName &&
                prev.displayName.isNotBlank() && f.displayName.isNotBlank()
            ) {
                val nameGroupKey = "name_$userId"
                fireEventNotification(
                    id = nameGroupKey.hashCode(),
                    title = "Friend renamed",
                    text = "${prev.displayName} is now known as ${f.displayName}",
                    profileUrl = "https://vrchat.com/home/user/$userId",
                    prefKey = VrchatNotificationPrefs.KEY_NOTIF_FRIEND_DISPLAY_NAME,
                    channelId = NOTIF_CHANNEL_FRIENDS_ACTIVITY,
                    groupKey = GROUP_KEY_FRIENDS,
                    bigText = "Was: ${prev.displayName}\nNow: ${f.displayName}",
                    alertBody = "${prev.displayName} → ${f.displayName}",
                    alertBeforeText = prev.displayName,
                    alertAfterText = f.displayName,
                    alertGroupKey = nameGroupKey
                )
            }

            // Bio change — edit, clear (→empty), or add (empty→). Both sides come
            // from full fetches so the bio field is reliable; only empty→empty is
            // ignored.
            if (f.bio != prev.bio && (f.bio.isNotBlank() || prev.bio.isNotBlank())) {
                val bioGroupKey = "bio_$userId"
                val bioTitle = when {
                    f.bio.isBlank() -> "${f.displayName} cleared their bio"
                    prev.bio.isBlank() -> "${f.displayName} added a bio"
                    else -> "${f.displayName} updated bio"
                }
                val beforeShown = prev.bio.ifBlank { "(empty)" }
                val afterShown = f.bio.ifBlank { "(empty)" }
                val bioAlertBody = "Before: $beforeShown\nAfter: $afterShown"
                fireEventNotification(
                    id = bioGroupKey.hashCode(),
                    title = bioTitle,
                    text = bioTitle,
                    profileUrl = "https://vrchat.com/home/user/$userId",
                    prefKey = VrchatNotificationPrefs.KEY_NOTIF_FRIEND_BIO,
                    channelId = NOTIF_CHANNEL_FRIENDS_ACTIVITY,
                    groupKey = GROUP_KEY_FRIENDS,
                    bigText = bioAlertBody,
                    alertBody = bioAlertBody,
                    alertBeforeText = beforeShown,
                    alertAfterText = afterShown,
                    alertGroupKey = bioGroupKey
                )
            }

            // Trust rank change
            if (f.trustRank.isNotBlank() && f.trustRank != prev.trustRank &&
                prev.trustRank.isNotBlank()
            ) {
                fireEventNotification(
                    id = "rank_$userId".hashCode(),
                    title = "Friend trust rank changed",
                    text = "${f.displayName} is now a ${prettyTrustRank(f.trustRank)}",
                    profileUrl = "https://vrchat.com/home/user/$userId",
                    prefKey = VrchatNotificationPrefs.KEY_NOTIF_FRIEND_RANK,
                    channelId = NOTIF_CHANNEL_FRIENDS_ACTIVITY,
                    groupKey = GROUP_KEY_FRIENDS,
                    alertBody = "${f.displayName} is now a ${prettyTrustRank(f.trustRank)}",
                    alertGroupKey = "rank_$userId"
                )
            }

            // Merge profile fields onto the LATEST cache entry (a concurrent WS
            // friend-location/update may have refreshed presence during a fire
            // suspend) — preserve the live presence fields (location/world/status)
            // the WebSocket events own.
            val latest = friendsCache[userId] ?: prev
            friendsCache[userId] = latest.copy(
                displayName = f.displayName,
                statusDescription = f.statusDescription,
                avatarThumb = f.avatarThumb,
                bio = f.bio,
                trustRank = f.trustRank
            )
            changed = true
        }
        if (changed) persistFriendsCache()
        } // withContext(pipelineDispatcher)
    }

    private fun persistFriendsCache() {
        try {
            FriendsCacheStore.save(this, friendsCache.toMap())
        } catch (e: Exception) {
            Log.w(TAG, "persistFriendsCache error", e)
        }
        publishFriendsOnline()
    }

    /** Publish the (online, total) friend count for the VRChat tab — computed
     *  from the cache on every mutation, no API call. "Online" = any non-offline
     *  status (in-game or website-active), matching VRChat's own sidebar.
     *
     *  Counts by status OR location: the WebSocket friend-online /
     *  friend-location payloads don't always carry a `user.status` field (the
     *  user object is optional), so a status-only predicate never changed the
     *  count on live events — it only refreshed when the REST friends reload
     *  ran on reconnect, i.e. "the count only updates when I reopen the app".
     *  A live location (wrld_/private/traveling) proves online even when the
     *  event omitted status; friend-offline explicitly stamps both fields. */
    private fun publishFriendsOnline() {
        val total = friendsCache.size
        if (total == 0) return
        val online = friendsCache.values.count {
            (it.status.isNotBlank() && !it.status.equals("offline", true)) ||
                (it.location.isNotBlank() && !it.location.equals("offline", true))
        }
        VrchatPipelineState.friendsOnline = online to total
    }

    private fun loadSeenNotifIds() {
        val prefs = getSharedPreferences("vrca_seen_notifs", Context.MODE_PRIVATE)
        val raw = prefs.getStringSet("ids", null) ?: return
        synchronized(seenNotifIds) {
            seenNotifIds.addAll(raw)
        }
    }

    private fun persistSeenNotifIds() {
        val prefs = getSharedPreferences("vrca_seen_notifs", Context.MODE_PRIVATE)
        synchronized(seenNotifIds) {
            prefs.edit().putStringSet("ids", seenNotifIds.toSet()).apply()
        }
    }

    /**
     * Two-fetch confirmation: only fire unfriend notifications for IDs
     * absent from BOTH the first fetch (captured into [candidateRemovals])
     * and the second fetch (this call's [friendsCache]). Eliminates false
     * positives from VRChat API pagination flaps.
     */
    private suspend fun diffFriendsCache(
        previousIds: Set<String>,
        previousNames: Map<String, FriendCacheEntry>,
        candidateRemovals: Set<String>
    ) {
        val now = System.currentTimeMillis()
        if (now - lastUnfriendDiffMs < 5 * 60 * 1000L) return
        if (previousIds.isEmpty() || friendsCache.isEmpty()) return
        if (friendsCache.size < previousIds.size * 4 / 5) {
            Log.w(TAG, "Skipping unfriend diff: fresh list (${friendsCache.size}) < 80% of previous (${previousIds.size})")
            return
        }
        lastUnfriendDiffMs = now
        val confirmedRemovals = candidateRemovals.intersect(previousIds - friendsCache.keys)
        var verifiedTrue = 0
        var falsePositives = 0
        var unverifiable = 0
        confirmedRemovals.forEach { userId ->
            if (!notifiedUnfriendIds.add(userId)) return@forEach

            // Third gate: hit /users/{id} directly. If isFriend == true, the
            // friends list endpoint flapped; suppress the notification and
            // re-add to cache so the next offline diff doesn't re-flag them.
            // If isFriend == false (or 404), confirm the unfriend. If we
            // can't verify (rate limit, network error, expired session),
            // fall back to firing the notification — better a rare false
            // positive than missing a real unfriend.
            val stillFriend = VrchatAuthManager.verifyStillFriend(this@VrchatPipelineService, userId)
            when (stillFriend) {
                true -> {
                    falsePositives++
                    notifiedUnfriendIds.remove(userId)
                    val name = previousNames[userId]?.displayName ?: ""
                    if (name.isNotBlank()) {
                        friendsCache[userId] = previousNames[userId]!!
                    }
                    Log.i(TAG, "Unfriend diff: $userId verified still friends — suppressing")
                    return@forEach
                }
                false -> verifiedTrue++
                null -> unverifiable++
            }

            val displayName = previousNames[userId]?.displayName?.takeIf { it.isNotBlank() } ?: "Someone"
            fireEventNotification(
                id = "unfriend_offline_$userId".hashCode(),
                title = "Friend removed",
                text = "$displayName is no longer on your friends list",
                profileUrl = "https://vrchat.com/home/user/$userId",
                prefKey = VrchatNotificationPrefs.KEY_NOTIF_UNFRIEND,
                channelId = NOTIF_CHANNEL_FRIEND_REMOVALS,
                groupKey = GROUP_KEY_SOCIAL,
                alertBody = "$displayName is no longer on your friends list",
                alertGroupKey = "unfriend_$userId"
            )
        }
        Log.i(TAG, "Unfriend diff: ${confirmedRemovals.size} candidates → " +
            "$verifiedTrue verified, $falsePositives suppressed, $unverifiable unverifiable " +
            "(prev=${previousIds.size}, now=${friendsCache.size})")
    }

    private fun restoreFriendsCache() {
        try {
            val saved = FriendsCacheStore.load(this)
            saved.forEach { (id, entry) -> friendsCache[id] = entry }
            friendsCacheLoaded = friendsCache.isNotEmpty()
            Log.i(TAG, "Restored ${friendsCache.size} friends from local cache")
            publishFriendsOnline()
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore friends cache from local store", e)
        }
    }

    // ------------------------------------------------------------------
    // Offline notification backfill
    // ------------------------------------------------------------------

    /**
     * Parse a VRChat ISO 8601 timestamp string into epoch millis.
     * Returns 0 if the string is blank or unparseable.
     */
    private fun parseVrcTimestampMs(ts: String): Long {
        if (ts.isBlank()) return 0L
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            // Strip fractional seconds and trailing Z for SimpleDateFormat
            val cleaned = ts.replace(Regex("\\.[0-9]+Z?$"), "").removeSuffix("Z")
            fmt.parse(cleaned)?.time ?: 0L
        } catch (_: Exception) { 0L }
    }

    // Recursively scans a JSON object/array for the first string value (or
    // string-key) that starts with the given prefix (e.g. "cal_", "grp_").
    // VRChat's notification-v2 payloads bury the calendar event id and group
    // id in inconsistent places (top-level, `data`, `details`, `link`), so a
    // prefix scan is far more reliable than guessing exact field names.
    private fun findIdWithPrefix(node: Any?, prefix: String, depth: Int = 0): String? {
        if (depth > 6 || node == null) return null
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = node.opt(k)
                    if (v is String && v.startsWith(prefix)) return v
                    findIdWithPrefix(v, prefix, depth + 1)?.let { return it }
                }
            }
            is org.json.JSONArray -> {
                for (i in 0 until node.length()) {
                    findIdWithPrefix(node.opt(i), prefix, depth + 1)?.let { return it }
                }
            }
            is String -> {
                // A nested JSON string (e.g. a `details` blob) — parse and recurse.
                val t = node.trim()
                if (t.startsWith(prefix)) return t
                if (t.startsWith("{") || t.startsWith("[")) {
                    val parsed = try {
                        if (t.startsWith("{")) JSONObject(t) else org.json.JSONArray(t)
                    } catch (_: Exception) { null }
                    if (parsed != null) return findIdWithPrefix(parsed, prefix, depth + 1)
                }
                // Embedded in a URL like .../calendar/cal_xxx
                Regex("${Regex.escape(prefix)}[A-Za-z0-9_-]+").find(t)?.let { return it.value }
            }
        }
        return null
    }

    // Treat JSON "null", empty, and whitespace as no value so the UI never
    // renders "Announcement from null".
    private fun cleanName(raw: String?): String {
        val v = raw?.trim().orEmpty()
        return if (v.isEmpty() || v.equals("null", true)) "" else v
    }

    // Resolves the best display name for a group notification: cached group
    // name (by id) first, then the sender username, then a neutral fallback.
    private fun resolveGroupName(groupId: String, senderName: String?): String {
        groupNameCache[groupId]?.let { if (it.isNotBlank()) return it }
        val sender = cleanName(senderName)
        if (sender.isNotEmpty() && !sender.equals("someone", true)) return sender
        return "a group"
    }

    // Same as resolveGroupName but, when the groupId isn't cached and the
    // sender name is missing, fetches the group's real name from the API once
    // and caches it. Used for live + backfill group notifications so events
    // (which often omit senderUsername) still render "Event from <GroupName>".
    private suspend fun resolveGroupNameAsync(groupId: String, senderName: String?): String {
        groupNameCache[groupId]?.let { if (it.isNotBlank()) return it }
        val sender = cleanName(senderName)
        if (sender.isNotEmpty() && !sender.equals("someone", true)) return sender
        if (groupId.isNotBlank()) {
            val fetched = VrchatAuthManager.fetchGroupName(this, groupId)
            if (!fetched.isNullOrBlank()) {
                groupNameCache[groupId] = fetched
                return fetched
            }
        }
        return "a group"
    }

    // Processes a single notification-v2 object (from REST backfill OR the
    // poll-loop retry sweep) and fires the appropriate notification. Shared so
    // the periodic poll re-attempts anything the one-shot connect backfill
    // missed (transient 429/timeout/cold-start race); seenNotifIds dedup makes
    // re-processing safe (each fires at most once).
    private suspend fun processV2Notification(obj: JSONObject) {
        val notifId = obj.optString("id", "")
        val v2Type = obj.optString("type", "")
        val v2Title = obj.optString("title", "")
        val v2Sender = obj.optString("senderUsername", "")
        val message = obj.optString("message", "")
        val v2CreatedAt = obj.optString("created_at", "")
        val v2CreatedMs = parseVrcTimestampMs(v2CreatedAt)
        // Catch-up content fires however old it is (seenNotifIds still
        // guarantees each one fires only once) so users away for a week
        // don't miss them: group announcements/events plus role/transfer
        // changes. Remaining transient types (invites, queue, join
        // requests, instance) skip when older than 24h.
        val isCatchUpType = v2Type.contains("announcement", true) ||
            v2Type.contains("post", true) ||
            v2Type.contains("event", true) ||
            v2Type.contains("calendar", true) ||
            v2Type.contains("role", true) ||
            v2Type.contains("transfer", true)
        if (!isCatchUpType && v2CreatedMs > 0 &&
            System.currentTimeMillis() - v2CreatedMs > 24L * 60 * 60 * 1000) return
        val baseId = notifId.ifBlank { "$v2Type-${message.hashCode()}" }
        val groupId = obj.optString("relatedGroupId", "").ifBlank {
            obj.optString("groupId", "").ifBlank {
                findIdWithPrefix(obj, "grp_").orEmpty()
            }
        }
        val groupUrl = if (groupId.isNotBlank()) "https://vrchat.com/home/group/$groupId"
            else "https://vrchat.com/home/notifications"
        when {
            v2Type.contains("announcement", true) || v2Type.contains("post", true) -> {
                if (groupId.isNotBlank() && isContentFingerprintSeen(groupId, v2Title, message)) {
                    Log.d(TAG, "V2 announcement skipped (REST already fired): group=$groupId title=$v2Title")
                    return
                }
                val backfillGroupKey = if (groupId.isNotBlank()) "announcement_$groupId" else null
                val displayName = resolveGroupNameAsync(groupId, v2Sender)
                val postUrl = if (groupId.isNotBlank()) "https://vrchat.com/home/group/$groupId/posts" else groupUrl
                val body = message.ifBlank { v2Title.ifBlank { "New announcement" } }
                fireEventNotification(
                    id = baseId.hashCode(),
                    title = "Announcement from $displayName",
                    text = "${v2Title.ifBlank { "Announcement" }}: ${body.take(120)}",
                    profileUrl = postUrl,
                    prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_ANNOUNCEMENT,
                    channelId = NOTIF_CHANNEL_GROUPS, groupKey = GROUP_KEY_GROUPS,
                    dedupId = notifId.ifBlank { null },
                    alertGroupKey = backfillGroupKey,
                    alertBody = body,
                    alertEventTitle = cleanName(v2Title).ifBlank { null },
                    eventTimestampMs = v2CreatedMs.takeIf { it > 0 }
                )
                if (groupId.isNotBlank()) addContentFingerprintToSeen(groupId, v2Title, message)
            }
            v2Type.contains("event", true) || v2Type.contains("calendar", true) -> {
                val eventId2 = obj.optString("eventId", "").ifBlank {
                    obj.optJSONObject("data")?.optString("eventId", "").orEmpty().ifBlank {
                        if (notifId.startsWith("cal_")) notifId
                        else findIdWithPrefix(obj, "cal_").orEmpty()
                    }
                }
                if (groupId.isNotBlank() && eventId2.isNotBlank() && isEventFingerprintSeen(groupId, eventId2)) {
                    Log.d(TAG, "V2 event skipped (already fired): group=$groupId event=$eventId2")
                    return
                }
                val backfillGroupKey = if (groupId.isNotBlank()) "event_$groupId" else null
                val displayName = resolveGroupNameAsync(groupId, v2Sender)
                val evtUrl = if (groupId.isNotBlank() && eventId2.isNotBlank())
                    "https://vrchat.com/home/group/$groupId/calendar/$eventId2"
                else if (groupId.isNotBlank()) "https://vrchat.com/home/group/$groupId"
                else groupUrl
                val body = message.ifBlank { v2Title.ifBlank { "New group event" } }
                fireEventNotification(
                    id = baseId.hashCode(),
                    title = "Event from $displayName",
                    text = "${v2Title.ifBlank { "Event" }}: ${body.take(120)}",
                    profileUrl = evtUrl,
                    prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_EVENT,
                    channelId = NOTIF_CHANNEL_GROUPS, groupKey = GROUP_KEY_GROUPS,
                    dedupId = notifId.ifBlank { null },
                    alertGroupKey = backfillGroupKey,
                    alertBody = body,
                    alertEventTitle = cleanName(v2Title).ifBlank { null },
                    eventTimestampMs = v2CreatedMs.takeIf { it > 0 }
                )
                if (groupId.isNotBlank()) addEventFingerprintToSeen(groupId, eventId2)
            }
            v2Type.contains("invite", true) -> fireEventNotification(
                id = baseId.hashCode(), title = v2Title.ifBlank { "Group invite" },
                text = message.take(140).ifBlank { "You've been invited to join a group" },
                profileUrl = groupUrl, prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_INVITE,
                channelId = NOTIF_CHANNEL_INVITES, groupKey = GROUP_KEY_INVITES,
                dedupId = notifId.ifBlank { null },
                alertBody = message.take(140).ifBlank { "You've been invited to join a group" },
                alertGroupKey = "groupinvite_$baseId"
            )
            v2Type.contains("queue", true) -> fireEventNotification(
                id = baseId.hashCode(), title = v2Title.ifBlank { "Queue ready" },
                text = message.take(140).ifBlank { "Your spot in a group instance queue is ready" },
                profileUrl = groupUrl, prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_QUEUE,
                channelId = NOTIF_CHANNEL_GROUPS, groupKey = GROUP_KEY_GROUPS,
                dedupId = notifId.ifBlank { null },
                alertBody = message.take(140).ifBlank { "Your spot in a group instance queue is ready" },
                alertGroupKey = "groupqueue_$baseId"
            )
            v2Type.contains("join", true) && v2Type.contains("request", true) -> fireEventNotification(
                id = baseId.hashCode(), title = v2Title.ifBlank { "Group join request" },
                text = message.take(140).ifBlank { "Someone wants to join one of your groups" },
                profileUrl = groupUrl, prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_JOIN_REQUEST,
                channelId = NOTIF_CHANNEL_GROUPS, groupKey = GROUP_KEY_GROUPS,
                dedupId = notifId.ifBlank { null },
                alertBody = message.take(140).ifBlank { "Someone wants to join one of your groups" },
                alertGroupKey = "groupjoin_$baseId"
            )
            v2Type.contains("role", true) || v2Type.contains("transfer", true) -> fireEventNotification(
                id = baseId.hashCode(), title = v2Title.ifBlank { "Group role updated" },
                text = message.take(140).ifBlank { "Your role in a group changed" },
                profileUrl = groupUrl, prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_ROLE,
                channelId = NOTIF_CHANNEL_GROUPS, groupKey = GROUP_KEY_GROUPS,
                dedupId = notifId.ifBlank { null },
                alertBody = message.take(140).ifBlank { "Your role in a group changed" },
                alertGroupKey = "grouprole_$baseId"
            )
            v2Type.contains("instance", true) -> fireEventNotification(
                id = baseId.hashCode(), title = v2Title.ifBlank { "Group instance opened" },
                text = message.take(140).ifBlank { "A new group instance is now joinable" },
                profileUrl = groupUrl, prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_INSTANCE,
                channelId = NOTIF_CHANNEL_GROUPS, groupKey = GROUP_KEY_GROUPS,
                dedupId = notifId.ifBlank { null },
                alertBody = message.take(140).ifBlank { "A new group instance is now joinable" },
                alertGroupKey = "groupinstance_$baseId"
            )
            v2Type.startsWith("group.") -> fireEventNotification(
                id = baseId.hashCode(), title = v2Title.ifBlank { "Group activity" },
                text = message.take(140).ifBlank { "New activity in one of your groups" },
                profileUrl = groupUrl, prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_EVENT,
                channelId = NOTIF_CHANNEL_GROUPS, groupKey = GROUP_KEY_GROUPS,
                dedupId = notifId.ifBlank { null },
                alertBody = message.ifBlank { "New activity in one of your groups" },
                alertGroupKey = "groupevt_$baseId"
            )
            else -> {
                Log.i(TAG, "backfill notification-v2 unmatched type='$v2Type' title='$v2Title' groupId=$groupId")
                if (isCatchUpType && (v2Title.isNotBlank() || message.isNotBlank())) {
                    fireEventNotification(
                        id = baseId.hashCode(),
                        title = v2Title.ifBlank { "VRChat notification" },
                        text = message.take(140).ifBlank { v2Title },
                        profileUrl = if (groupId.isNotBlank()) groupUrl else "https://vrchat.com/home/notifications",
                        prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_EVENT,
                        channelId = NOTIF_CHANNEL_GROUPS, groupKey = GROUP_KEY_GROUPS,
                        dedupId = notifId.ifBlank { null }
                    )
                }
            }
        }
    }

    private suspend fun backfillOfflineNotifications() {
        try {
            // First-run guard: if this is the very first pipeline connect ever
            // (no prior backfill has run), seed the per-group announcement
            // timestamps silently and skip firing any V1/V2 notifications.
            // Otherwise users get a flood of historical group announcements,
            // pending friend requests, invites, etc. on first install.
            val initialized = dataStore.data.first()[booleanPreferencesKey("notif_backfill_initialized")] ?: false
            if (!initialized) {
                Log.i(TAG, "First-run backfill: seeding announcement timestamps without firing notifications")
                seedBackfillBaseline()
                val repo = com.vrca.data.UserPreferencesRepository(this@VrchatPipelineService)
                repo.saveNotifBackfillInitialized(true)
                repo.savePostsEventsBaselineV2(true)
                // Pre-seed V1/V2 notification IDs so they don't fire on first backfill
                try {
                    val v1 = VrchatAuthManager.fetchPendingNotifications(this@VrchatPipelineService)
                    if (v1 != null) {
                        for (i in 0 until v1.length()) {
                            val id = v1.optJSONObject(i)?.optString("id", "") ?: ""
                            if (id.isNotBlank()) synchronized(seenNotifIds) { seenNotifIds.add(id) }
                        }
                    }
                    delay(300)
                    val v2 = VrchatAuthManager.fetchPendingNotificationsV2(this@VrchatPipelineService)
                    if (v2 != null) {
                        for (i in 0 until v2.length()) {
                            val id = v2.optJSONObject(i)?.optString("id", "") ?: ""
                            if (id.isNotBlank()) synchronized(seenNotifIds) { seenNotifIds.add(id) }
                        }
                    }
                    persistSeenNotifIds()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to seed V1/V2 notification IDs", e)
                }
                return
            }

            // One-time migration: the posts/events baseline was never seeded
            // for users who installed before fetchGroupPosts was fixed (it
            // returned null for VRChat's wrapped JSON response). Re-seed
            // all existing posts + calendar events into the seenMap so they
            // don't flood as "new" on this connect.
            val postsEventsMigrated = dataStore.data.first()[booleanPreferencesKey("posts_events_baseline_v2")] ?: false
            if (!postsEventsMigrated) {
                Log.i(TAG, "Migration: seeding existing posts + calendar events into seenMap")
                seedPostsAndEventsIntoExistingMap()
                val migRepo = com.vrca.data.UserPreferencesRepository(this@VrchatPipelineService)
                migRepo.savePostsEventsBaselineV2(true)
            }

            // One-time baseline for the corrected calendar endpoint. The old
            // /groups/{id}/events paths always 404'd, so NO calendar events were
            // ever seeded. Now that GET /calendar/{groupId} works, the very first
            // sweep would fire EVERY existing upcoming event at once. Seed ALL
            // current events (past AND upcoming) without firing so only events
            // created AFTER this point surface as new.
            val calendarBaselined = dataStore.data.first()[booleanPreferencesKey("calendar_baseline_v3")] ?: false
            if (!calendarBaselined) {
                Log.i(TAG, "Migration: seeding ALL calendar events (endpoint fix baseline)")
                seedAllCalendarEventsBaseline()
                val calRepo = com.vrca.data.UserPreferencesRepository(this@VrchatPipelineService)
                calRepo.saveCalendarBaselineV3(true)
            }

            // V1 notifications: friend requests, invites, votetokick, messages
            val v1 = VrchatAuthManager.fetchPendingNotifications(this@VrchatPipelineService)
            if (v1 != null) {
                for (i in 0 until v1.length()) {
                    val obj = v1.optJSONObject(i) ?: continue
                    val notifId = obj.optString("id", "")
                    val notifType = obj.optString("type", "")
                    val senderName = obj.optString("senderUsername", "someone")
                    val senderUserId = obj.optString("senderUserId", "")
                    val message = obj.optString("message", "")
                    val v1CreatedAt = obj.optString("created_at", "")
                    val v1CreatedMs = parseVrcTimestampMs(v1CreatedAt)
                    // Friend requests are pending/actionable state that persists until
                    // accepted or declined, so they fire regardless of age (dedup fires
                    // each once). Other transient V1 types skip when older than 24h.
                    val v1CatchUp = notifType == "friendRequest"
                    if (!v1CatchUp && v1CreatedMs > 0 &&
                        System.currentTimeMillis() - v1CreatedMs > 24L * 60 * 60 * 1000) continue
                    when (notifType) {
                        "friendRequest" -> fireEventNotification(
                            id = "fr_$senderUserId".hashCode(),
                            title = "Friend request",
                            text = "$senderName sent you a friend request",
                            profileUrl = "https://vrchat.com/home/user/$senderUserId",
                            prefKey = VrchatNotificationPrefs.KEY_NOTIF_FRIEND_REQUEST,
                            channelId = NOTIF_CHANNEL_FRIEND_REQUESTS,
                            groupKey = GROUP_KEY_SOCIAL,
                            dedupId = notifId.ifBlank { null },
                            alertBody = "$senderName sent you a friend request",
                            alertGroupKey = "friend_$senderUserId"
                        )
                        "invite" -> {
                            // Skip self-invites (sender == our own id); see live path.
                            val myId = VrchatAuthManager.getStoredUserId(this@VrchatPipelineService)
                            if (senderUserId.isNotBlank() && senderUserId == myId) continue
                            val det = try {
                                JSONObject(obj.optString("details", "{}"))
                            } catch (e: Exception) { JSONObject() }
                            val worldName = det.optString("worldName", "a world")
                            val wId = det.optString("worldId", "")
                            val instId = det.optString("instanceId", "")
                            val location = when {
                                wId.contains(":") -> wId
                                wId.isNotBlank() && instId.isNotBlank() -> "$wId:$instId"
                                else -> ""
                            }
                            val canInvite = location.startsWith("wrld_")
                            fireEventNotification(
                                id = "inv_${notifId.ifBlank { senderUserId }}".hashCode(),
                                title = "World invite",
                                text = "$senderName invited you to $worldName",
                                profileUrl = if (canInvite) null else "https://vrchat.com/home/notifications",
                                prefKey = VrchatNotificationPrefs.KEY_NOTIF_INVITE,
                                channelId = NOTIF_CHANNEL_INVITES,
                                groupKey = GROUP_KEY_INVITES,
                                dedupId = notifId.ifBlank { null },
                                alertBody = "$senderName invited you to $worldName",
                                // Fuse world invites PER-INVITER (full history, same-
                                // instance dedup; the card's invite symbol picks).
                                alertGroupKey = "worldinvite_${senderUserId.ifBlank { notifId }}",
                                alertEventTitle = worldName.ifBlank { null },
                                alertActionType = if (canInvite) NotificationActionReceiver.ACTION_INVITE_ME else null,
                                alertActionData = if (canInvite) location else null,
                                tapActionType = if (canInvite) NotificationActionReceiver.ACTION_INVITE_ME else null,
                                tapActionData = if (canInvite) location else null,
                                alertDedupByActionData = true
                            )
                        }
                        "requestInvite" -> fireEventNotification(
                            id = "invreq_${senderUserId.ifBlank { notifId }}".hashCode(),
                            title = "Invite request",
                            text = "$senderName is asking for an invite to your instance",
                            profileUrl = if (senderUserId.isNotBlank()) null else "https://vrchat.com/home/notifications",
                            prefKey = VrchatNotificationPrefs.KEY_NOTIF_INVITE_REQUEST,
                            channelId = NOTIF_CHANNEL_INVITES,
                            groupKey = GROUP_KEY_INVITES,
                            dedupId = notifId.ifBlank { null },
                            alertBody = "$senderName is asking for an invite to your instance",
                            alertGroupKey = "invreq_${senderUserId.ifBlank { notifId }}",
                            alertActionType = if (senderUserId.isNotBlank()) NotificationActionReceiver.ACTION_INVITE_USER else null,
                            alertActionData = senderUserId.ifBlank { null },
                            tapActionType = if (senderUserId.isNotBlank()) NotificationActionReceiver.ACTION_INVITE_USER else null,
                            tapActionData = senderUserId.ifBlank { null },
                            alertSingleEvent = true
                        )
                        "votetokick" -> fireEventNotification(
                            id = "vtk_$notifId".hashCode(),
                            title = "Vote-to-kick",
                            text = message.ifBlank { "A vote-to-kick has been initiated" },
                            profileUrl = null,
                            prefKey = VrchatNotificationPrefs.KEY_NOTIF_VOTE_TO_KICK,
                            channelId = NOTIF_CHANNEL_FRIENDS_ACTIVITY,
                            groupKey = GROUP_KEY_FRIENDS,
                            dedupId = notifId.ifBlank { null }
                        )
                        "message" -> fireEventNotification(
                            id = "msg_$notifId".hashCode(),
                            title = "VRChat message",
                            text = if (message.isNotBlank()) "$senderName: $message" else "$senderName sent you a message",
                            profileUrl = if (senderUserId.isNotBlank()) "https://vrchat.com/home/user/$senderUserId" else null,
                            prefKey = VrchatNotificationPrefs.KEY_NOTIF_VRCHAT_MESSAGE,
                            channelId = NOTIF_CHANNEL_FRIEND_REQUESTS,
                            groupKey = GROUP_KEY_SOCIAL,
                            dedupId = notifId.ifBlank { null },
                            alertBody = if (message.isNotBlank()) "$senderName: $message" else "$senderName sent you a message",
                            alertGroupKey = if (senderUserId.isNotBlank()) "msg_$senderUserId" else "msg_$notifId"
                        )
                    }
                }
            }
            delay(300)

            // V2 notifications: group events
            val v2 = VrchatAuthManager.fetchPendingNotificationsV2(this@VrchatPipelineService)
            if (v2 != null) {
                for (i in 0 until v2.length()) {
                    val obj = v2.optJSONObject(i) ?: continue
                    processV2Notification(obj)
                }
            }
            delay(300)

            // Group announcements: fetch per-group and check timestamps
            val groups = VrchatAuthManager.fetchUserGroups(this@VrchatPipelineService)
            if (groups != null) {
                val seenRaw = dataStore.data.first()[androidx.datastore.preferences.core.stringPreferencesKey("notif_group_announcement_seen")] ?: "{}"
                val seenMap = try { JSONObject(seenRaw) } catch (e: Exception) { JSONObject() }
                val updatedMap = JSONObject(seenRaw)
                val groupCount = minOf(groups.length(), 50)
                for (i in 0 until groupCount) {
                    val group = groups.optJSONObject(i) ?: continue
                    val groupId = group.optString("groupId", "").ifBlank { group.optString("id", "") }
                    val groupName = group.optString("name", "A group")
                    if (groupId.isBlank()) continue
                    if (groupName.isNotBlank() && groupName != "A group") groupNameCache[groupId] = groupName
                    try {
                        // Sweep group POSTS (which includes the pinned
                        // announcement — no separate fetchGroupAnnouncement
                        // call needed, and removing it eliminates duplicate
                        // ga_ vs gp_ dedup-ID firing for the same content).
                        val posts = VrchatAuthManager.fetchGroupPosts(this@VrchatPipelineService, groupId, 20)
                        if (posts != null) {
                            for (j in 0 until posts.length()) {
                                val post = posts.optJSONObject(j) ?: continue
                                val postId = post.optString("id", "")
                                if (postId.isBlank()) continue
                                val rawPostTitle = post.optString("title", "")
                                val postTitle = rawPostTitle.ifBlank { groupName }
                                val postText = post.optString("text", "")
                                val postCreatedAt = post.optString("createdAt", "")
                                val postCreatedMs = parseVrcTimestampMs(postCreatedAt)
                                val postSeenKey = "${groupId}_post_$postId"
                                val postLastSeen = seenMap.optString(postSeenKey, "")
                                // Fire when there's ANY content (text or title) — a
                                // title-only announcement was previously skipped.
                                if (postCreatedAt.isNotBlank() && postCreatedAt != postLastSeen &&
                                    (postText.isNotBlank() || rawPostTitle.isNotBlank())) {
                                    // Cross-path dedup: the live notification-v2
                                    // handler fires this announcement the instant
                                    // the group posts and records its content
                                    // fingerprint. If we see that fingerprint here,
                                    // the live path already fired it — just record
                                    // the seen timestamp and skip so the 5-min poll
                                    // doesn't fire a duplicate a few minutes later.
                                    if (isContentFingerprintSeen(groupId, rawPostTitle, postText)) {
                                        updatedMap.put(postSeenKey, postCreatedAt)
                                    } else {
                                        fireEventNotification(
                                            id = "gp_${postId}".hashCode(),
                                            title = "Announcement from $groupName",
                                            text = "${postTitle}: ${postText.ifBlank { rawPostTitle }.take(120)}",
                                            profileUrl = "https://vrchat.com/home/group/$groupId/posts",
                                            prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_ANNOUNCEMENT,
                                            channelId = NOTIF_CHANNEL_GROUPS,
                                            groupKey = GROUP_KEY_GROUPS,
                                            dedupId = "gp_${postId}_$postCreatedAt",
                                            alertGroupKey = "announcement_$groupId",
                                            alertBody = postText.ifBlank { rawPostTitle.ifBlank { null } },
                                            alertEventTitle = rawPostTitle.ifBlank { null },
                                            eventTimestampMs = postCreatedMs.takeIf { it > 0 }
                                        )
                                        updatedMap.put(postSeenKey, postCreatedAt)
                                        addContentFingerprintToSeen(groupId, rawPostTitle, postText)
                                    }
                                }
                            }
                        }
                        // Sweep group CALENDAR EVENTS too. Events created while
                        // closed don't reliably show up in notifications-v2, so
                        // without this they never surface on reopen.
                        val events = VrchatAuthManager.fetchGroupCalendarEvents(this@VrchatPipelineService, groupId, 20)
                        if (events != null) {
                            for (j in 0 until events.length()) {
                                fireGroupCalendarEvent(events.optJSONObject(j) ?: continue, groupId, groupName, seenMap, updatedMap)
                            }
                        }
                        delay(250)
                    } catch (e: Exception) {
                        Log.w(TAG, "backfill: group announcement $groupId failed", e)
                    }
                }
                // Persist updated seen timestamps
                val repo = com.vrca.data.UserPreferencesRepository(this@VrchatPipelineService)
                repo.saveNotifGroupAnnouncementSeen(updatedMap.toString())
            }

            Log.i(TAG, "Offline notification backfill completed")
        } catch (e: Exception) {
            Log.w(TAG, "Offline notification backfill failed", e)
        }
    }

    /**
     * First-run baseline: walks the user's groups and records the current
     * announcement timestamp for each one without firing notifications. Future
     * backfill runs will only fire announcements newer than these timestamps,
     * so historical announcements (from before the user installed VRC-A) stay
     * silent. Pending V1/V2 notifications older than this point are also
     * silently dropped on the first run — only events that happen after setup
     * generate notifications.
     */
    private suspend fun seedBackfillBaseline() {
        try {
            val groups = VrchatAuthManager.fetchUserGroups(this@VrchatPipelineService) ?: return
            val seenMap = JSONObject()
            val groupCount = minOf(groups.length(), 50)
            for (i in 0 until groupCount) {
                val group = groups.optJSONObject(i) ?: continue
                val groupId = group.optString("groupId", "").ifBlank { group.optString("id", "") }
                if (groupId.isBlank()) continue
                val gName = group.optString("name", "")
                if (gName.isNotBlank()) groupNameCache[groupId] = gName
                try {
                    val announcement = VrchatAuthManager.fetchGroupAnnouncement(this@VrchatPipelineService, groupId)
                    val createdAt = announcement?.optString("createdAt", "") ?: ""
                    if (createdAt.isNotBlank()) seenMap.put(groupId, createdAt)
                    // Seed recent post IDs too so the backfill/poll posts sweep
                    // doesn't fire a flood of pre-existing posts on first run.
                    val posts = VrchatAuthManager.fetchGroupPosts(this@VrchatPipelineService, groupId, 20)
                    if (posts != null) {
                        for (j in 0 until posts.length()) {
                            val post = posts.optJSONObject(j) ?: continue
                            val postId = post.optString("id", "")
                            val postCreatedAt = post.optString("createdAt", "")
                            if (postId.isNotBlank() && postCreatedAt.isNotBlank()) {
                                seenMap.put("${groupId}_post_$postId", postCreatedAt)
                            }
                        }
                    }
                    // Seed existing calendar events too.
                    val events = VrchatAuthManager.fetchGroupCalendarEvents(this@VrchatPipelineService, groupId, 20)
                    if (events != null) {
                        for (j in 0 until events.length()) {
                            val ev = events.optJSONObject(j) ?: continue
                            val evId = ev.optString("id", "").ifBlank { findIdWithPrefix(ev, "cal_").orEmpty() }
                            if (evId.isBlank()) continue
                            val marker = ev.optString("startsAt", "").ifBlank {
                                ev.optString("createdAt", "").ifBlank { evId }
                            }
                            seenMap.put("${groupId}_event_$evId", marker)
                        }
                    }
                    delay(250)
                } catch (e: Exception) {
                    Log.w(TAG, "seedBackfillBaseline: group $groupId failed", e)
                }
            }
            val repo = com.vrca.data.UserPreferencesRepository(this@VrchatPipelineService)
            repo.saveNotifGroupAnnouncementSeen(seenMap.toString())
            Log.i(TAG, "Backfill baseline seeded for ${seenMap.length()} groups")
        } catch (e: Exception) {
            Log.w(TAG, "seedBackfillBaseline failed", e)
        }
    }

    private suspend fun seedPostsAndEventsIntoExistingMap() {
        try {
            val groups = VrchatAuthManager.fetchUserGroups(this@VrchatPipelineService) ?: return
            val seenKey = androidx.datastore.preferences.core.stringPreferencesKey("notif_group_announcement_seen")
            val seenRaw = dataStore.data.first()[seenKey] ?: "{}"
            val seenMap = try { JSONObject(seenRaw) } catch (_: Exception) { JSONObject() }
            var added = 0
            val groupCount = minOf(groups.length(), 50)
            for (i in 0 until groupCount) {
                val group = groups.optJSONObject(i) ?: continue
                val groupId = group.optString("groupId", "").ifBlank { group.optString("id", "") }
                if (groupId.isBlank()) continue
                val gName = group.optString("name", "")
                if (gName.isNotBlank()) groupNameCache[groupId] = gName
                try {
                    val posts = VrchatAuthManager.fetchGroupPosts(this@VrchatPipelineService, groupId, 20)
                    if (posts != null) {
                        for (j in 0 until posts.length()) {
                            val post = posts.optJSONObject(j) ?: continue
                            val postId = post.optString("id", "")
                            val postCreatedAt = post.optString("createdAt", "")
                            if (postId.isNotBlank() && postCreatedAt.isNotBlank()) {
                                val k = "${groupId}_post_$postId"
                                if (!seenMap.has(k)) { seenMap.put(k, postCreatedAt); added++ }
                            }
                        }
                    }
                    // Only seed PAST events (already happened) so they don't flood.
                    // Upcoming events are intentionally NOT seeded — the backfill
                    // sweep right after fires them once so the user sees events
                    // they missed while closed; per-event dedup stops re-fires.
                    val events = VrchatAuthManager.fetchGroupCalendarEvents(this@VrchatPipelineService, groupId, 20)
                    if (events != null) {
                        val now = System.currentTimeMillis()
                        for (j in 0 until events.length()) {
                            val ev = events.optJSONObject(j) ?: continue
                            val evId = ev.optString("id", "").ifBlank { findIdWithPrefix(ev, "cal_").orEmpty() }
                            if (evId.isBlank()) continue
                            val startsAt = ev.optString("startsAt", "")
                            val startsMs = parseVrcTimestampMs(startsAt)
                            val isPast = startsMs in 1 until now
                            if (!isPast) continue
                            val marker = startsAt.ifBlank { ev.optString("createdAt", "").ifBlank { evId } }
                            val k = "${groupId}_event_$evId"
                            if (!seenMap.has(k)) { seenMap.put(k, marker); added++ }
                        }
                    }
                    delay(250)
                } catch (e: Exception) {
                    Log.w(TAG, "seedPostsAndEvents: group $groupId failed", e)
                }
            }
            val repo = com.vrca.data.UserPreferencesRepository(this@VrchatPipelineService)
            repo.saveNotifGroupAnnouncementSeen(seenMap.toString())
            Log.i(TAG, "Posts/events migration: seeded $added entries into seenMap")
        } catch (e: Exception) {
            Log.w(TAG, "seedPostsAndEventsIntoExistingMap failed", e)
        }
    }

    /**
     * Seeds EVERY current calendar event (past and upcoming) into the seen map
     * AND the event fingerprint set, without firing. One-time baseline for the
     * corrected calendar endpoint so the first working sweep doesn't flood the
     * user with every pre-existing upcoming event. After this, only events
     * created later surface as new.
     */
    private suspend fun seedAllCalendarEventsBaseline() {
        try {
            val groups = VrchatAuthManager.fetchUserGroups(this@VrchatPipelineService) ?: return
            val seenKey = androidx.datastore.preferences.core.stringPreferencesKey("notif_group_announcement_seen")
            val seenRaw = dataStore.data.first()[seenKey] ?: "{}"
            val seenMap = try { JSONObject(seenRaw) } catch (_: Exception) { JSONObject() }
            var added = 0
            val groupCount = minOf(groups.length(), 50)
            for (i in 0 until groupCount) {
                val group = groups.optJSONObject(i) ?: continue
                val groupId = group.optString("groupId", "").ifBlank { group.optString("id", "") }
                if (groupId.isBlank()) continue
                try {
                    val events = VrchatAuthManager.fetchGroupCalendarEvents(this@VrchatPipelineService, groupId, 20)
                    if (events != null) {
                        for (j in 0 until events.length()) {
                            val ev = events.optJSONObject(j) ?: continue
                            val evId = ev.optString("id", "").ifBlank { findIdWithPrefix(ev, "cal_").orEmpty() }
                            if (evId.isBlank()) continue
                            val startsAt = ev.optString("startsAt", "")
                            val marker = startsAt.ifBlank { ev.optString("createdAt", "").ifBlank { evId } }
                            val k = "${groupId}_event_$evId"
                            if (!seenMap.has(k)) { seenMap.put(k, marker); added++ }
                            addEventFingerprintToSeen(groupId, evId)
                        }
                    }
                    delay(250)
                } catch (e: Exception) {
                    Log.w(TAG, "seedAllCalendarEventsBaseline: group $groupId failed", e)
                }
            }
            val repo = com.vrca.data.UserPreferencesRepository(this@VrchatPipelineService)
            repo.saveNotifGroupAnnouncementSeen(seenMap.toString())
            persistSeenNotifIds()
            Log.i(TAG, "Calendar baseline: seeded $added events into seenMap")
        } catch (e: Exception) {
            Log.w(TAG, "seedAllCalendarEventsBaseline failed", e)
        }
    }

    // ------------------------------------------------------------------
    // Firestore presence sync
    // ------------------------------------------------------------------

    /**
     * Reactively patch the CURRENT USER's own presence from a WebSocket pipeline
     * payload (`user-location` / self `friend-location`), WITHOUT a REST call.
     *
     * Why this exists: the user's own world-hop (`user-location`) used to be a no-op,
     * and self presence was refreshed ONLY by the periodic `fetchPresence()` REST
     * poll. On mobile the auth cookie is frequently IP-invalidated, so that poll
     * returns null and `VrchatPipelineState.presence` freezes — yet the Discord RPC
     * keeps pushing (its online timer is independent), so the RPC shows a stale world
     * while the user is actually somewhere else. The event payload already carries the
     * new location/world, so we patch presence straight from it: the RPC's
     * `presenceFlow.collectLatest` fires immediately and reflects the real state even
     * when REST is failing. When an admin is watching, the fresh fields are also
     * pushed to Firestore so the admin directory updates instantly too.
     */
    private fun applySelfLocationEvent(content: JSONObject?) {
        content ?: return
        val location = content.optString("location", "")
            .ifBlank { content.optString("instance", "") }
        val user = content.optJSONObject("user")
        val worldName = content.optJSONObject("world")?.optString("name").orEmpty()
            .ifBlank { user?.optString("worldName").orEmpty() }
            .ifBlank { user?.optJSONObject("world")?.optString("name").orEmpty() }
        if (location.isBlank() && worldName.isBlank()) return

        val prev = VrchatPipelineState.presence
        val newState = when {
            location.equals("offline", true) -> "offline"
            location.isNotBlank() -> "online"
            else -> prev?.state ?: "online"
        }
        val patched = if (prev != null) {
            prev.copy(
                location = if (location.isNotBlank()) location else prev.location,
                worldName = if (worldName.isNotBlank()) worldName else prev.worldName,
                state = newState,
                isOnlineInVRChat = newState != "offline"
            )
        } else {
            VrchatAuthManager.VrcUserPresence(
                userId = VrchatAuthManager.getStoredUserId(this) ?: "",
                displayName = VrchatAuthManager.getStoredDisplayName(this) ?: "",
                state = newState,
                status = "",
                statusDescription = "",
                location = location,
                platform = "",
                worldName = worldName,
                instancePlayerCount = 0,
                instanceCapacity = 0,
                currentAvatarThumbnailUrl = "",
                isOnlineInVRChat = newState != "offline"
            )
        }
        VrchatPipelineState.presence = patched
        pushSelfPresenceToFirestoreIfWatched()

        // Record this instance (full location WITH the ~nonce access token) into the
        // local 24h history so the user can re-invite themselves to it later from the
        // VRChat tab, with join/left times. Stays on-device only; pruned after 24h.
        if (location.startsWith("wrld_")) {
            InstanceHistoryStore.record(this, location, patched.worldName)
        } else if (location.equals("offline", true)) {
            // Left VRChat without hopping — close out the current instance's "left" time.
            InstanceHistoryStore.markCurrentLeft(this)
        }

        // Refresh the instance occupancy the instant we world-hop, via a single
        // lightweight GET /instances/{loc} (what the website does on a tab
        // refresh). The WS location event carries no player count, so without
        // this the count showed 0/stale until the heavy 3-call fetchPresence
        // chain next succeeded — minutes apart on mobile. One call, fired only
        // on the hop, so it adds no steady traffic.
        if (location.startsWith("wrld_")) {
            serviceScope.launch {
                val ic = VrchatAuthManager.fetchInstanceCount(this@VrchatPipelineService, location)
                    ?: return@launch
                val cur = VrchatPipelineState.presence ?: return@launch
                if (cur.location == location &&
                    (cur.instancePlayerCount != ic.players || cur.instanceCapacity != ic.capacity)
                ) {
                    VrchatPipelineState.presence = cur.copy(
                        instancePlayerCount = ic.players,
                        instanceCapacity = ic.capacity
                    )
                }
            }
        }
    }

    /** Reactively patch own status/state from a self `user-update` payload. */
    private fun applySelfUserUpdate(content: JSONObject?) {
        content ?: return
        val user = content.optJSONObject("user") ?: content
        val prev = VrchatPipelineState.presence ?: return
        val status = user.optString("status", prev.status).ifBlank { prev.status }
        val statusDesc = user.optString("statusDescription", prev.statusDescription)
        val state = user.optString("state", prev.state).ifBlank { prev.state }
        VrchatPipelineState.presence = prev.copy(
            status = status,
            statusDescription = statusDesc,
            state = state,
            isOnlineInVRChat = state != "offline"
        )
        pushSelfPresenceToFirestoreIfWatched()
    }

    /**
     * When an admin is watching, push the just-patched presence fields to Firestore
     * directly (no REST round-trip) so the admin sees the new location/world/state
     * instantly and resiliently — even if the next `fetchPresence()` REST poll fails.
     */
    private fun pushSelfPresenceToFirestoreIfWatched() {
        if (deviceHash.isBlank() || !AdminWatchState.isWatched.value) return
        val p = VrchatPipelineState.presence ?: return
        val updates = mapOf(
            "vrchatState" to p.state,
            "vrchatStatus" to p.status,
            "vrchatStatusDescription" to p.statusDescription,
            "vrchatWorld" to p.worldName,
            "vrchatLocation" to p.location,
            "vrchatIsOnline" to p.isOnlineInVRChat,
            "vrchatLastSyncAt" to FieldValue.serverTimestamp(),
            "lastActiveAt" to FieldValue.serverTimestamp(),
            "lastSeenAt" to FieldValue.serverTimestamp()
        )
        try {
            FirebaseFirestore.getInstance()
                .collection("users").document(deviceHash)
                .set(updates, SetOptions.merge())
        } catch (e: Exception) {
            Log.w(TAG, "pushSelfPresence failed", e)
        }
    }

    private suspend fun syncPresenceToFirestore(forceLocalUpdate: Boolean = false) {
        if (!forceLocalUpdate && deviceHash.isBlank()) return
        if (!forceLocalUpdate && !AdminWatchState.isWatched.value) return

        var presence = VrchatAuthManager.fetchPresence(this)
        if (presence == null) {
            // The heavy 3-call chain (/auth/user -> /users/{id} -> /instances)
            // failed (cookie IP-invalidation / rate limit / a partial 429). Try ONE
            // lightweight GET /users/{id} to recover the true location/state so a
            // genuinely in-game user doesn't freeze as "not in a world" on the admin
            // panel / "not in VRChat" on their Discord RPC. When the location is
            // unchanged we keep the last-known worldName + instance counts (the light
            // call doesn't fetch them); a world hop leaves worldName briefly blank
            // until the next heavy chain refills it. If even this single call fails,
            // the cookie is fully dead — fall through to the count-only patch.
            presence = VrchatAuthManager.fetchSelfPresenceLight(this)?.let { light ->
                val prev = VrchatPipelineState.presence
                if (prev != null && prev.location == light.location) {
                    light.copy(
                        worldName = prev.worldName,
                        worldImageUrl = prev.worldImageUrl,
                        instancePlayerCount = prev.instancePlayerCount,
                        instanceCapacity = prev.instanceCapacity
                    )
                } else light
            }
        }
        if (presence == null) {
            // Both the heavy chain and the light call failed. Keep the instance
            // occupancy current anyway via a single lightweight GET /instances/{loc}
            // on the last-known location — one request, what the website does on a
            // tab refresh — so the player count tracks reality instead of freezing.
            // Count-only patch; no Firestore write here (the heavy path owns the
            // watched write).
            val loc = VrchatPipelineState.presence?.location
            if (!loc.isNullOrBlank() && loc.startsWith("wrld_")) {
                VrchatAuthManager.fetchInstanceCount(this, loc)?.let { ic ->
                    VrchatPipelineState.presence?.let { p ->
                        if (p.location == loc &&
                            (p.instancePlayerCount != ic.players || p.instanceCapacity != ic.capacity)
                        ) {
                            VrchatPipelineState.presence = p.copy(
                                instancePlayerCount = ic.players,
                                instanceCapacity = ic.capacity
                            )
                        }
                    }
                }
            }
            return
        }
        // Keep the full, joinable location (with the ~nonce access token) that the
        // WebSocket self user-location event captured if the REST fetch came back
        // with the redacted "private" — VRChat hides invite/invite+ locations from
        // /users/{id} even for your own account, and that nonce is what lets the
        // admin self-invite into those instances. "private" means still-in-instance
        // (redacted), so this can't resurrect a left instance — a real departure
        // reports "offline", which is allowed through.
        val priorLoc = VrchatPipelineState.presence?.location
        if (presence.location == "private" && priorLoc != null && priorLoc.startsWith("wrld_")) {
            presence = presence.copy(
                location = priorLoc,
                worldName = presence.worldName.ifBlank { VrchatPipelineState.presence?.worldName.orEmpty() }
            )
        }
        VrchatPipelineState.presence = presence

        if (deviceHash.isBlank()) return
        if (!AdminWatchState.isWatched.value) return

        val updates = mapOf(
            "vrchatUserId" to presence.userId,
            "vrchatDisplayName" to presence.displayName,
            "displayName" to presence.displayName,
            "vrchatState" to presence.state,
            "vrchatStatus" to presence.status,
            "vrchatStatusDescription" to presence.statusDescription,
            "vrchatWorld" to presence.worldName,
            "vrchatLocation" to presence.location,
            "vrchatInstancePlayerCount" to presence.instancePlayerCount,
            "vrchatInstanceCapacity" to presence.instanceCapacity,
            "vrchatPlatform" to presence.platform,
            "vrchatAvatarThumb" to presence.currentAvatarThumbnailUrl,
            "vrchatIsOnline" to presence.isOnlineInVRChat,
            "vrchatAuthCookieValid" to true,
            "vrchatLastSyncAt" to FieldValue.serverTimestamp(),
            // While watched, the 10s loop also keeps the liveness fields fresh
            // so a watched user's online/offline is real-time (~25s), not
            // hour-stale. lastActiveAt is canonical; lastSeenAt mirrors it.
            "lastActiveAt" to FieldValue.serverTimestamp(),
            "lastSeenAt" to FieldValue.serverTimestamp()
        )

        try {
            FirebaseFirestore.getInstance()
                .collection("users")
                .document(deviceHash)
                .set(updates, SetOptions.merge())
                .addOnSuccessListener { Log.d(TAG, "Firestore presence sync OK (online=${presence.isOnlineInVRChat}, state=${presence.state}, loc=${presence.location})") }
                .addOnFailureListener { e -> Log.w(TAG, "Firestore presence sync FAILED: ${e.message}", e) }
        } catch (e: Exception) {
            Log.e(TAG, "syncPresenceToFirestore error", e)
        }
    }

    // ------------------------------------------------------------------
    // App update notification check
    // ------------------------------------------------------------------

    private fun startAppUpdateCheckLoop() {
        if (BuildConfig.IS_ADMIN_BUILD) return
        serviceScope.launch {
            while (true) {
                try {
                    val result = checkFirestoreRelease(BuildConfig.VERSION_CODE, deviceHash)
                    if (result is ReleaseCheckResult.UpdateAvailable) {
                        val info = result.info
                        val title = if (result.forced) "VRC-A update required"
                                    else "VRC-A update available"
                        val text = if (info.versionName.isNotBlank())
                            "Version ${info.versionName} is available"
                        else "A new version is available"
                        val bigText = if (info.notes.isNotBlank())
                            "$text\n\n${info.notes}"
                        else null
                        fireAppUpdateNotification(title, text, bigText)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "App update check failed", e)
                }
                delay(6 * 60 * 60 * 1000L)
            }
        }
    }

    private fun startAuthRefreshLoop() {
        serviceScope.launch {
            delay(6 * 60 * 60 * 1000L)
            while (true) {
                try {
                    // The 12-day age threshold rarely fires before the auth cookie
                    // actually dies (VRChat expires it far sooner, and any IP change
                    // invalidates it immediately). So also actively validate the
                    // session each cycle and re-login when it's dead, regardless of
                    // cookie age — this is what keeps the session alive 24/7 without
                    // a fresh 2FA prompt. autoRelogin sends only the trusted-device
                    // 2FA cookie, so VRChat skips the 2FA challenge.
                    if (VrchatAuthManager.isLoggedIn(this@VrchatPipelineService)) {
                        val stale = VrchatAuthManager.shouldRefreshCookies(this@VrchatPipelineService)
                        val valid = VrchatAuthManager.validateSession(this@VrchatPipelineService)
                        if (stale || !valid) {
                            Log.i(TAG, "Auth refresh (stale=$stale, valid=$valid) — re-logging in")
                            VrchatAuthManager.diagnoseAuthState(this@VrchatPipelineService)
                            val refreshed = VrchatAuthManager.autoRelogin(this@VrchatPipelineService)
                            Log.i(TAG, "Proactive auth refresh result: $refreshed")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Auth refresh check failed", e)
                }
                delay(6 * 60 * 60 * 1000L)
            }
        }
    }

    private fun startGroupAnnouncementPollLoop() {
        serviceScope.launch {
            // VRChat's notification/announcement endpoints lag behind reality:
            // an item created moments before reconnect often isn't in the API
            // response yet during the immediate connect backfill, so a single
            // sweep misses it. We run several quick catch-up sweeps (15s, 40s,
            // 90s) to absorb that propagation delay before settling into the
            // steady 5-min cadence. seenNotifIds dedup keeps each item to one fire.
            val warmupDelays = longArrayOf(15_000L, 25_000L, 50_000L)
            for (d in warmupDelays) {
                delay(d)
                try {
                    pollGroupAnnouncements()
                } catch (e: Exception) {
                    Log.w(TAG, "Group announcement warmup poll failed", e)
                }
            }
            while (true) {
                delay(5 * 60 * 1000L)
                try {
                    pollGroupAnnouncements()
                } catch (e: Exception) {
                    Log.w(TAG, "Group announcement poll failed", e)
                }
            }
        }
    }

    private suspend fun pollGroupAnnouncements() {
        if (!VrchatAuthManager.isLoggedIn(this)) return

        // Retry sweep of pending notification-v2 (events, announcements, role
        // changes). The one-shot connect backfill can miss these on a transient
        // 429/timeout or a cold-start race; re-running here every poll cycle is
        // the retry path. seenNotifIds dedup ensures each fires at most once.
        // Skipped on the very first connect (baseline not yet established).
        val initialized = dataStore.data.first()[booleanPreferencesKey("notif_backfill_initialized")] ?: false
        if (initialized) {
            try {
                val v2 = VrchatAuthManager.fetchPendingNotificationsV2(this)
                if (v2 != null) {
                    for (i in 0 until v2.length()) {
                        val obj = v2.optJSONObject(i) ?: continue
                        processV2Notification(obj)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "poll: V2 notification retry sweep failed", e)
            }
            delay(300)
        }

        val groups = VrchatAuthManager.fetchUserGroups(this) ?: return
        val seenKey = androidx.datastore.preferences.core.stringPreferencesKey("notif_group_announcement_seen")
        val seenRaw = dataStore.data.first()[seenKey] ?: "{}"
        val seenMap = try { JSONObject(seenRaw) } catch (_: Exception) { JSONObject() }
        val updatedMap = JSONObject(seenRaw)
        var changed = false
        val groupCount = minOf(groups.length(), 50)
        for (i in 0 until groupCount) {
            val group = groups.optJSONObject(i) ?: continue
            val groupId = group.optString("groupId", "").ifBlank { group.optString("id", "") }
            val groupName = group.optString("name", "A group")
            if (groupId.isBlank()) continue
            if (groupName.isNotBlank() && groupName != "A group") groupNameCache[groupId] = groupName
            try {
                // Posts endpoint includes the pinned announcement, so no
                // separate fetchGroupAnnouncement call — eliminates the
                // ga_ vs gp_ duplicate dedup-ID problem.
                val posts = VrchatAuthManager.fetchGroupPosts(this, groupId, 20)
                if (posts != null) {
                    for (j in 0 until posts.length()) {
                        val post = posts.optJSONObject(j) ?: continue
                        val postId = post.optString("id", "")
                        if (postId.isBlank()) continue
                        val rawPostTitle = post.optString("title", "")
                        val postTitle = rawPostTitle.ifBlank { groupName }
                        val postText = post.optString("text", "")
                        val postCreatedAt = post.optString("createdAt", "")
                        val postCreatedMs = parseVrcTimestampMs(postCreatedAt)
                        val postSeenKey = "${groupId}_post_$postId"
                        val postLastSeen = seenMap.optString(postSeenKey, "")
                        if (postCreatedAt.isNotBlank() && postCreatedAt != postLastSeen &&
                            (postText.isNotBlank() || rawPostTitle.isNotBlank())) {
                            fireEventNotification(
                                id = "gp_${postId}".hashCode(),
                                title = "Announcement from $groupName",
                                text = "${postTitle}: ${postText.ifBlank { rawPostTitle }.take(120)}",
                                profileUrl = "https://vrchat.com/home/group/$groupId/posts",
                                prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_ANNOUNCEMENT,
                                channelId = NOTIF_CHANNEL_GROUPS,
                                groupKey = GROUP_KEY_GROUPS,
                                dedupId = "gp_${postId}_$postCreatedAt",
                                alertGroupKey = "announcement_$groupId",
                                alertBody = postText.ifBlank { rawPostTitle.ifBlank { null } },
                                alertEventTitle = rawPostTitle.ifBlank { null },
                                eventTimestampMs = postCreatedMs.takeIf { it > 0 }
                            )
                            updatedMap.put(postSeenKey, postCreatedAt)
                            addContentFingerprintToSeen(groupId, rawPostTitle, postText)
                            changed = true
                        }
                    }
                }
                val events = VrchatAuthManager.fetchGroupCalendarEvents(this, groupId, 20)
                if (events != null) {
                    for (j in 0 until events.length()) {
                        if (fireGroupCalendarEvent(events.optJSONObject(j) ?: continue, groupId, groupName, seenMap, updatedMap)) {
                            changed = true
                        }
                    }
                }
                delay(300)
            } catch (e: Exception) {
                Log.w(TAG, "pollGroupAnnouncements: group $groupId failed", e)
            }
        }
        if (changed) {
            val repo = com.vrca.data.UserPreferencesRepository(this)
            repo.saveNotifGroupAnnouncementSeen(updatedMap.toString())
        }
    }

    private fun announcementContentFingerprint(groupId: String, title: String, text: String): String {
        // Identity is the post BODY (falling back to the title when the body is
        // blank), normalized so the SAME announcement produces the SAME key no
        // matter which path saw it: the live notification-v2 `message` field or
        // the REST group-post `text` field. The two sources also title a post
        // differently, so the title is intentionally excluded from the key —
        // including it broke cross-path dedup and double-fired announcements
        // (once live the instant the group posted, once on the 5-min poll).
        val body = text.ifBlank { title }
            .trim()
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .take(120)
        return "ann_fp_${groupId}_${body.hashCode()}"
    }

    private fun addContentFingerprintToSeen(groupId: String, title: String, text: String) {
        val fp = announcementContentFingerprint(groupId, title, text)
        synchronized(seenNotifIds) { seenNotifIds.add(fp) }
    }

    private fun isContentFingerprintSeen(groupId: String, title: String, text: String): Boolean {
        val fp = announcementContentFingerprint(groupId, title, text)
        return synchronized(seenNotifIds) { fp in seenNotifIds }
    }

    // Event fingerprint is keyed on the EVENT ID (not content) so that two
    // distinct events with the same generic title (e.g. "Event starting")
    // don't collide and suppress each other. Used for cross-path dedup of the
    // SAME event arriving via WebSocket, V2 backfill, and the REST calendar
    // sweep — all three extract the same `cal_xxx` id for a given event.
    private fun eventFingerprint(groupId: String, eventId: String): String {
        return "evt_fp_${groupId}_$eventId"
    }

    private fun addEventFingerprintToSeen(groupId: String, eventId: String) {
        if (eventId.isBlank()) return
        synchronized(seenNotifIds) { seenNotifIds.add(eventFingerprint(groupId, eventId)) }
    }

    private fun isEventFingerprintSeen(groupId: String, eventId: String): Boolean {
        if (eventId.isBlank()) return false
        val fp = eventFingerprint(groupId, eventId)
        return synchronized(seenNotifIds) { fp in seenNotifIds }
    }

    // Fires a group calendar event notification if it's new (not in seenMap).
    // Returns true if it fired (so callers can flag the seen-map dirty).
    private suspend fun fireGroupCalendarEvent(
        event: JSONObject,
        groupId: String,
        groupName: String,
        seenMap: JSONObject,
        updatedMap: JSONObject
    ): Boolean {
        val eventId = event.optString("id", "").ifBlank { findIdWithPrefix(event, "cal_").orEmpty() }
        if (eventId.isBlank()) return false
        val eventTitle = event.optString("title", "").ifBlank { event.optString("name", "") }
        val eventDesc = event.optString("description", "").ifBlank { event.optString("text", "") }
        val startsAt = event.optString("startsAt", "")
        val createdAt = event.optString("createdAt", "")
        // DISPLAY timestamp = when the event was POSTED (createdAt), NOT its
        // scheduled start. startsAt is normally in the FUTURE, so feeding it to
        // formatRelativeTime produced a negative delta that clamped to a
        // permanent "just now" that never advanced. Fall back to startsAt only
        // when createdAt is missing.
        val postedMs = parseVrcTimestampMs(createdAt).takeIf { it > 0 }
            ?: parseVrcTimestampMs(startsAt)
        val seenKey = "${groupId}_event_$eventId"
        val lastSeen = seenMap.optString(seenKey, "")
        // Change marker still tracks startsAt (then createdAt) so an unchanged
        // event doesn't re-fire, but an edited start time does.
        val marker = startsAt.ifBlank { createdAt }.ifBlank { eventId }
        if (marker == lastSeen) return false
        // Cross-path dedup keyed on the event id (not content) so a sibling
        // event with the same title doesn't get suppressed.
        if (isEventFingerprintSeen(groupId, eventId)) return false
        if (eventTitle.isBlank() && eventDesc.isBlank()) return false
        fireEventNotification(
            id = "gce_$eventId".hashCode(),
            title = "Event from $groupName",
            text = "${eventTitle.ifBlank { "Event" }}: ${eventDesc.take(120)}",
            profileUrl = "https://vrchat.com/home/group/$groupId/calendar/$eventId",
            prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_EVENT,
            channelId = NOTIF_CHANNEL_GROUPS,
            groupKey = GROUP_KEY_GROUPS,
            dedupId = "gce_${eventId}_$marker",
            alertGroupKey = "event_$groupId",
            alertBody = eventDesc.ifBlank { eventTitle }.ifBlank { null },
            alertEventTitle = eventTitle.ifBlank { null },
            eventTimestampMs = postedMs.takeIf { it > 0 }
        )
        updatedMap.put(seenKey, marker)
        addEventFingerprintToSeen(groupId, eventId)
        return true
    }

    private suspend fun fireAppUpdateNotification(title: String, text: String, bigText: String?) {
        val prefs = dataStore.data.first()
        val enabled = prefs[booleanPreferencesKey(VrchatNotificationPrefs.KEY_NOTIF_APP_UPDATE)] ?: true
        if (!enabled) return

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val tapIntent = PendingIntent.getActivity(
            this, NOTIF_ID_APP_UPDATE,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, NOTIF_CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (bigText != null) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
        }
        nm.notify(NOTIF_ID_APP_UPDATE, builder.build())
    }

    // ------------------------------------------------------------------
    // Admin announcements listener
    // ------------------------------------------------------------------

    private fun loadSeenAnnouncementIds() {
        val prefs = getSharedPreferences("vrca_seen_announcements", Context.MODE_PRIVATE)
        val stored = prefs.getStringSet("ids", emptySet()) ?: emptySet()
        seenAnnouncementIds = stored.toMutableSet()
    }

    private fun persistSeenAnnouncementIds() {
        getSharedPreferences("vrca_seen_announcements", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("ids", seenAnnouncementIds.toSet())
            .apply()
    }

    private fun attachAnnouncementsListener() {
        announcementsListener?.remove()
        announcementsInitialSnapshotDone = false
        announcementsListener = FirebaseFirestore.getInstance()
            .collection("announcements")
            .whereEqualTo("active", true)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(60)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.w(TAG, "Announcements listener error", err)
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener

                if (!announcementsInitialSnapshotDone) {
                    // First snapshot: seed seen IDs without firing notifications
                    for (doc in snap.documents) {
                        seenAnnouncementIds.add(doc.id)
                    }
                    persistSeenAnnouncementIds()
                    announcementsInitialSnapshotDone = true
                    return@addSnapshotListener
                }

                // Subsequent snapshots: fire notifications for new announcements
                for (doc in snap.documents) {
                    if (seenAnnouncementIds.contains(doc.id)) continue
                    seenAnnouncementIds.add(doc.id)

                    val title = doc.getString("title") ?: "Announcement"
                    val body = doc.getString("body") ?: ""
                    serviceScope.launch {
                        fireEventNotification(
                            id = "announcement_${doc.id}".hashCode(),
                            title = "VRC-A: $title",
                            text = body.take(200),
                            profileUrl = null,
                            prefKey = VrchatNotificationPrefs.KEY_NOTIF_ANNOUNCEMENTS,
                            channelId = NOTIF_CHANNEL_CONNECTION,
                            bigText = if (body.length > 100) body else null,
                            alertBody = body.ifBlank { null }
                        )
                    }
                }
                persistSeenAnnouncementIds()
            }
    }

    // ------------------------------------------------------------------
    // VRChat status page polling
    // ------------------------------------------------------------------

    private fun startStatusPagePolling() {
        statusPageJob?.cancel()
        statusPageJob = serviceScope.launch {
            while (true) {
                try {
                    pollVrchatStatusPage()
                } catch (e: Exception) {
                    Log.w(TAG, "Status page poll failed", e)
                }
                delay(STATUS_POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun pollVrchatStatusPage() {
        val json = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://status.vrchat.com/api/v2/summary.json")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            val response = okClient.newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Status page HTTP ${resp.code}")
                    return@withContext null
                }
                resp.body?.string()
            }
        } ?: return

        val root = JSONObject(json)
        val statusObj = root.optJSONObject("status")
        val indicator = statusObj?.optString("indicator", "none") ?: "none"
        val description = statusObj?.optString("description", "") ?: ""

        // Parse components
        val componentsArr = root.optJSONArray("components")
        val components = mutableListOf<VrchatStatusComponent>()
        if (componentsArr != null) {
            for (i in 0 until componentsArr.length()) {
                val c = componentsArr.optJSONObject(i) ?: continue
                components.add(VrchatStatusComponent(
                    name = c.optString("name", ""),
                    status = c.optString("status", "operational")
                ))
            }
        }

        // Parse active incidents
        val incidentsArr = root.optJSONArray("incidents")
        val incidents = mutableListOf<VrchatStatusIncident>()
        if (incidentsArr != null) {
            for (i in 0 until incidentsArr.length()) {
                val inc = incidentsArr.optJSONObject(i) ?: continue
                val updatesArr = inc.optJSONArray("incident_updates")
                val latestUpdate = if (updatesArr != null && updatesArr.length() > 0)
                    updatesArr.optJSONObject(0)?.optString("body", "") ?: ""
                else ""
                incidents.add(VrchatStatusIncident(
                    name = inc.optString("name", ""),
                    status = inc.optString("status", ""),
                    impact = inc.optString("impact", "none"),
                    latestUpdate = latestUpdate
                ))
            }
        }

        val state = VrchatStatusPageData(
            indicator = indicator,
            description = description,
            components = components,
            incidents = incidents
        )
        VrchatPipelineState.statusPageState = state

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (indicator == "none") {
            // All clear
            if (lastStatusAllClearMs == 0L) {
                lastStatusAllClearMs = System.currentTimeMillis()
            }
            val allClearDuration = System.currentTimeMillis() - lastStatusAllClearMs
            if (allClearDuration >= STATUS_AUTO_DISMISS_MS) {
                nm.cancel(NOTIF_ID_VRCHAT_STATUS)
                VrchatPipelineState.statusPageState = null
            }
        } else {
            lastStatusAllClearMs = 0L

            val notifTitle = when (indicator) {
                "critical" -> "VRChat: Major outage"
                "major" -> "VRChat: Significant issues"
                else -> "VRChat: Service degradation"
            }
            val notifText = if (incidents.isNotEmpty()) {
                incidents.first().name
            } else {
                description
            }
            val bigText = buildString {
                append(description)
                for (inc in incidents) {
                    append("\n\n")
                    append(inc.name)
                    if (inc.latestUpdate.isNotBlank()) {
                        append("\n")
                        append(inc.latestUpdate)
                    }
                }
                if (components.any { it.status != "operational" }) {
                    append("\n\nAffected:")
                    for (c in components.filter { it.status != "operational" }) {
                        append("\n• ${c.name}: ${c.status.replace("_", " ")}")
                    }
                }
            }

            fireStatusPageNotification(notifTitle, notifText, bigText)
        }
    }

    private suspend fun fireStatusPageNotification(title: String, text: String, bigText: String) {
        val prefs = dataStore.data.first()
        val enabled = prefs[booleanPreferencesKey(VrchatNotificationPrefs.KEY_NOTIF_VRCHAT_ALERT)] ?: false
        if (!enabled) return

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val tapIntent = PendingIntent.getActivity(
            this, NOTIF_ID_VRCHAT_STATUS,
            Intent(Intent.ACTION_VIEW, Uri.parse("https://status.vrchat.com")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, NOTIF_CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tapIntent)
            .setAutoCancel(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        nm.notify(NOTIF_ID_VRCHAT_STATUS, builder.build())
    }

    // ------------------------------------------------------------------
    // Notifications
    // ------------------------------------------------------------------

    private suspend fun fireEventNotification(
        id: Int,
        title: String,
        text: String,
        profileUrl: String?,
        prefKey: String,
        channelId: String,
        groupKey: String? = null,
        dedupId: String? = null,
        bigText: String? = null,
        alertBody: String? = null,
        alertBeforeText: String? = null,
        alertAfterText: String? = null,
        alertGroupKey: String? = null,
        alertEventTitle: String? = null,
        eventTimestampMs: Long? = null,
        alertActionType: String? = null,
        alertActionData: String? = null,
        tapActionType: String? = null,
        tapActionData: String? = null,
        // When true the in-app group keeps ONLY the latest event (no history) and
        // the Android notification shows no "(N)" count — used for invite requests,
        // which fuse per-person instead of listing every individual request.
        alertSingleEvent: Boolean = false,
        // When true, a new event whose actionData matches an existing event in the
        // group REPLACES it (dedup by target) — e.g. repeated world invites to the
        // SAME instance collapse to one entry instead of stacking duplicates.
        alertDedupByActionData: Boolean = false
    ) {
        // Check the toggle BEFORE touching seenNotifIds. Adding the dedup id
        // first risks permanently poisoning it: if `enabled` reads false for a
        // transient reason (e.g. DataStore not fully loaded during the cold-start
        // connect/backfill race), the notification would be dropped AND marked
        // seen, so it could never fire again on any later path (poll loop,
        // reconnect backfill). Gate first, then dedup only when we will fire.
        val prefs = dataStore.data.first()
        val enabled = prefs[booleanPreferencesKey(prefKey)] ?: false
        if (!enabled) {
            Log.d(TAG, "notif drop (toggle off): pref=$prefKey title=$title dedup=$dedupId")
            return
        }

        if (dedupId != null) {
            var alreadySeen = false
            synchronized(seenNotifIds) {
                if (!seenNotifIds.add(dedupId)) {
                    alreadySeen = true
                } else {
                    while (seenNotifIds.size > MAX_SEEN_NOTIF_IDS) {
                        seenNotifIds.iterator().let { it.next(); it.remove() }
                    }
                }
            }
            if (alreadySeen) {
                Log.d(TAG, "notif skip (already seen): dedup=$dedupId title=$title")
                return
            }
            persistSeenNotifIds()
        }
        Log.i(TAG, "notif FIRE: title=$title dedup=$dedupId group=$alertGroupKey")

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notifyIdForTap = if (alertGroupKey != null) alertGroupKey.hashCode() else id
        val tapIntent = if (tapActionType != null) {
            // Tapping the notification performs an action (e.g. re-invite) via a
            // broadcast receiver instead of opening a web page. The receiver
            // cancels this notification id once the action completes.
            PendingIntent.getBroadcast(
                this, id,
                NotificationActionReceiver.buildIntent(this, tapActionType, tapActionData, notifyIdForTap),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else if (profileUrl != null) {
            PendingIntent.getActivity(
                this, id,
                Intent(Intent.ACTION_VIEW, Uri.parse(profileUrl)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getActivity(
                this, id,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        // For grouped alerts, show event count in the Android notification title.
        // Single-event groups (e.g. invite requests) fuse per-person and never show
        // a count — each new event replaces the last, so a tally would be misleading.
        val displayTitle = if (alertGroupKey != null && !alertSingleEvent) {
            val existingGroup = InAppAlertState.groups.value.firstOrNull { it.groupId == alertGroupKey }
            val count = (existingGroup?.events?.size ?: 0) + 1 // +1 for the event about to be added
            if (count > 1) "$title ($count)" else title
        } else title

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(displayTitle)
            .setContentText(text)
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(alertGroupKey != null)

        if (bigText != null) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
        }

        if (groupKey != null) {
            builder.setGroup(groupKey)
            publishGroupSummary(nm, groupKey, channelId)
        }

        // For grouped alerts, use a stable Android notification ID per group
        // so subsequent events update the SAME notification (fuse together)
        // instead of creating a new notification per event.
        val notifyId = if (alertGroupKey != null) alertGroupKey.hashCode() else id
        nm.notify(notifyId, builder.build())

        if (alertBody != null) {
            val now = System.currentTimeMillis()
            // Use the event's real creation time (e.g. when the announcement was
            // posted on the website) for display, falling back to now. Keep a
            // unique id off `now` so two events at the same creation time don't collide.
            val displayTs = eventTimestampMs ?: now
            if (alertGroupKey != null) {
                InAppAlertState.addGroupedEvent(
                    ctx = this,
                    groupKey = alertGroupKey,
                    title = title,
                    url = profileUrl,
                    event = InAppAlertEvent(
                        id = "${alertGroupKey}_$now",
                        body = alertBody,
                        beforeText = alertBeforeText,
                        afterText = alertAfterText,
                        timestampMs = displayTs,
                        eventTitle = alertEventTitle,
                        url = profileUrl,
                        actionType = alertActionType,
                        actionData = alertActionData
                    ),
                    singleEvent = alertSingleEvent,
                    dedupByActionData = alertDedupByActionData
                )
            } else {
                InAppAlertState.addAlert(
                    this,
                    InAppAlert(
                        id = dedupId ?: "alert_$id",
                        title = title,
                        body = alertBody,
                        url = profileUrl,
                        timestampMs = displayTs,
                        beforeText = alertBeforeText,
                        afterText = alertAfterText
                    )
                )
            }
        }
    }

    private fun publishGroupSummary(nm: NotificationManager, groupKey: String, channelId: String) {
        val (summaryId, summaryText) = when (groupKey) {
            GROUP_KEY_FRIENDS -> NOTIF_ID_SUMMARY_FRIENDS to "Friends activity"
            GROUP_KEY_SOCIAL  -> NOTIF_ID_SUMMARY_SOCIAL  to "Friends list updates"
            GROUP_KEY_INVITES -> NOTIF_ID_SUMMARY_INVITES to "Invites"
            GROUP_KEY_GROUPS  -> NOTIF_ID_SUMMARY_GROUPS  to "Group activity"
            else -> return
        }
        val summary = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(summaryText)
            .setContentText("Multiple updates")
            .setStyle(NotificationCompat.InboxStyle().setSummaryText(summaryText))
            .setGroup(groupKey)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        nm.notify(summaryId, summary)
    }

    private suspend fun fireNotLoggedInNotification() {
        val prefs = dataStore.data.first()
        val enabled = prefs[booleanPreferencesKey(VrchatNotificationPrefs.KEY_NOTIF_AUTH)] ?: true
        if (!enabled) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val tapIntent = PendingIntent.getActivity(
            this, 9999,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, NOTIF_CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("VRC-A: Sign in required")
            .setContentText("Tap to re-sign in to your VRChat account")
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(NOTIF_ID_AUTH, notif)
    }

    /**
     * Single-slot connection-status notification. Same notification ID is
     * reused so reconnect cycles update in place rather than stacking.
     * Skipped if the new state matches the previous fired state.
     */
    private suspend fun fireConnectionNotification(connected: Boolean) {
        if (lastConnectionNotifWasUp == connected) return
        lastConnectionNotifWasUp = connected
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (connected) {
            // No "VRChat connected / Monitoring VRChat events" notification — the
            // persistent foreground notification already conveys this. Just clear
            // any leftover disconnect notification from a previous cycle.
            nm.cancel(NOTIF_ID_CONNECTION)
            return
        }
        val prefs = dataStore.data.first()
        val enabled = prefs[booleanPreferencesKey(VrchatNotificationPrefs.KEY_NOTIF_CONNECTION)] ?: false
        if (!enabled) return
        val tapIntent = PendingIntent.getActivity(
            this, NOTIF_ID_CONNECTION,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, NOTIF_CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("VRChat disconnected")
            .setContentText("Lost connection — attempting to reconnect")
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        nm.notify(NOTIF_ID_CONNECTION, notif)
    }

    private fun updatePersistentNotif(status: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID_PERSISTENT, buildPersistentNotification(status))
    }

    private fun buildPersistentNotification(status: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val rpcSuffix = if (DiscordRpcService.isRunning) {
            val rpcStatus = DiscordRpcState.status
            when (rpcStatus) {
                DiscordRpcStatus.CONNECTED -> " | Discord RPC active"
                DiscordRpcStatus.CONNECTING, DiscordRpcStatus.RECONNECTING -> " | Discord RPC connecting..."
                DiscordRpcStatus.FAILED, DiscordRpcStatus.SESSION_EXPIRED -> " | Discord RPC failed"
                else -> ""
            }
        } else ""
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_PERSISTENT)
            // Distinct sync glyph (matches the Automations tab) so the persistent
            // background-service notification is visually separate from event alerts.
            .setSmallIcon(R.drawable.ic_notif_sync)
            .setContentTitle("VRC-A")
            .setContentText(status + rpcSuffix)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setSilent(true)
            .setSound(null)
            .setVibrate(null)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setGroup("vrca_service")
            .build()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Drop the legacy single events channel from older installs so users
        // don't see a ghost "VRChat Events" entry in system notification
        // settings. Safe to call repeatedly — Android no-ops if absent.
        try { nm.deleteNotificationChannel(NOTIF_CHANNEL_EVENTS_LEGACY) } catch (_: Throwable) {}

        NotificationChannel(NOTIF_CHANNEL_PERSISTENT, "VRC-A Background",
            NotificationManager.IMPORTANCE_MIN).apply {
            description = "Shows when VRC-A is monitoring VRChat events in the background"
            setShowBadge(false)
            setSound(null, null)
            nm.createNotificationChannel(this)
        }

        NotificationChannel(NOTIF_CHANNEL_FRIENDS_ACTIVITY, "Friends Activity",
            NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Friends coming online, going offline, changing worlds, status, or avatar"
            nm.createNotificationChannel(this)
        }

        NotificationChannel(NOTIF_CHANNEL_FRIEND_REQUESTS, "Friend Requests",
            NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Incoming friend requests and new friends added"
            nm.createNotificationChannel(this)
        }

        NotificationChannel(NOTIF_CHANNEL_FRIEND_REMOVALS, "Friend Removals",
            NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Notifications when someone is removed from your friends list"
            nm.createNotificationChannel(this)
        }

        NotificationChannel(NOTIF_CHANNEL_INVITES, "Invites",
            NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "World invites, invite requests, and group invites"
            nm.createNotificationChannel(this)
        }

        NotificationChannel(NOTIF_CHANNEL_GROUPS, "Groups",
            NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Group announcements, role changes, queue ready, and other group activity"
            nm.createNotificationChannel(this)
        }

        NotificationChannel(NOTIF_CHANNEL_CONNECTION, "Connection Status",
            NotificationManager.IMPORTANCE_LOW).apply {
            description = "VRChat pipeline connect/disconnect and sign-in alerts"
            nm.createNotificationChannel(this)
        }
    }
}

/**
 * Shared in-memory state for the pipeline - lets the UI read connection
 * status and presence data without needing to bind to the service.
 * Uses StateFlow so Compose UI automatically recomposes on changes.
 */
data class VrchatStatusComponent(val name: String, val status: String)
data class VrchatStatusIncident(
    val name: String,
    val status: String,
    val impact: String,
    val latestUpdate: String
)
data class VrchatStatusPageData(
    val indicator: String,
    val description: String,
    val components: List<VrchatStatusComponent>,
    val incidents: List<VrchatStatusIncident>
)

object VrchatPipelineState {
    private val _isConnected = MutableStateFlow(false)
    val isConnectedFlow: StateFlow<Boolean> = _isConnected.asStateFlow()
    var isConnected: Boolean
        get() = _isConnected.value
        set(value) { _isConnected.value = value }

    private val _presence = MutableStateFlow<VrchatAuthManager.VrcUserPresence?>(null)
    val presenceFlow: StateFlow<VrchatAuthManager.VrcUserPresence?> = _presence.asStateFlow()
    var presence: VrchatAuthManager.VrcUserPresence?
        get() = _presence.value
        set(value) { _presence.value = value }

    private val _statusPageState = MutableStateFlow<VrchatStatusPageData?>(null)
    val statusPageFlow: StateFlow<VrchatStatusPageData?> = _statusPageState.asStateFlow()
    var statusPageState: VrchatStatusPageData?
        get() = _statusPageState.value
        set(value) { _statusPageState.value = value }

    // (online, total) friends from the local friends cache — fed by the service
    // on every cache mutation, ZERO extra API calls. Null until the cache loads.
    private val _friendsOnline = MutableStateFlow<Pair<Int, Int>?>(null)
    val friendsOnlineFlow: StateFlow<Pair<Int, Int>?> = _friendsOnline.asStateFlow()
    var friendsOnline: Pair<Int, Int>?
        get() = _friendsOnline.value
        set(value) { _friendsOnline.value = value }

    /** True while any Activity is started (app on-screen). Set from
     *  VrcaApplication's ActivityLifecycleCallbacks; read by the friends-profile
     *  refresh loop so it polls fast while the user is actively in the app and
     *  backs off when backgrounded. @Volatile — cross-thread read from the
     *  service's IO coroutine. */
    @Volatile var appForeground: Boolean = false
}
