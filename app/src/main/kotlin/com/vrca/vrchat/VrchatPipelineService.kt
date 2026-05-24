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
import com.vrca.BuildConfig
import com.vrca.app.MainActivity
import com.vrca.R
import com.vrca.discord.DiscordRpcService
import com.vrca.discord.DiscordRpcState
import com.vrca.discord.DiscordRpcStatus
import com.vrca.update.checkFirestoreRelease
import com.vrca.update.checkTargetedUpdate
import com.vrca.update.ReleaseCheckResult
import com.vrca.data.dataStore
import com.vrca.sync.AdminBrowsingState
import com.vrca.sync.AdminWatchState
import com.vrca.vrchat.VrchatNotificationPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
        attachAdminPresenceListener()
        attachAnnouncementsListener()
        startStatusPagePolling()
        startAppUpdateCheckLoop()
        startAuthRefreshLoop()
        startGroupAnnouncementPollLoop()

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
                startForeground(NOTIF_ID_PERSISTENT, buildPersistentNotification("Connecting..."))
                // If the pipeline is already connected (duplicate ACTION_START
                // from a reconnect or system restart), don't tear it down and
                // start over — that's what causes friend caches to flush and
                // VRChat presence to flicker.
                if (!VrchatPipelineState.isConnected) {
                    startPipeline()
                }
            }
        }
        return START_STICKY  // restart if killed
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        writeOfflineAndStopRpc()
        stopSelf()
        super.onTaskRemoved(rootIntent)
        Thread {
            Thread.sleep(10_000)
            android.os.Process.killProcess(android.os.Process.myPid())
        }.start()
    }

    private fun isInWarmup(): Boolean =
        pipelineConnectedAtMs > 0 && System.currentTimeMillis() - pipelineConnectedAtMs < WARMUP_MS

    override fun onDestroy() {
        adminPresenceListener?.remove()
        adminPresenceListener = null
        announcementsListener?.remove()
        announcementsListener = null
        statusPageJob?.cancel()
        webSocket?.cancel()
        serviceScope.cancel()
        VrchatPipelineState.isConnected = false
        VrchatPipelineState.presence = null
        VrchatPipelineState.statusPageState = null
        super.onDestroy()
    }

    private fun writeOfflineAndStopRpc() {
        if (DiscordRpcService.isRunning) {
            val stopRpc = Intent(this, DiscordRpcService::class.java)
            stopRpc.action = DiscordRpcService.ACTION_STOP
            try { startService(stopRpc) } catch (_: Throwable) {}
        }
        if (deviceHash.isNotBlank()) {
            try {
                val presence = VrchatPipelineState.presence
                val data = mutableMapOf<String, Any>(
                    "isOnlineInApp" to false,
                    "lastSeenAt" to FieldValue.serverTimestamp(),
                    // Drop any legacy friends data still lingering on the doc.
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
            } catch (_: Throwable) {}
        }
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
                        // Bio change detection — always fires as grouped in-app alert
                        if (newEntry.bio != old.bio &&
                            old.bio.isNotBlank() && newEntry.bio.isNotBlank()) {
                            val bioGroupKey = "bio_$userId"
                            val bioAlertBody = "Before: ${old.bio}\nAfter: ${newEntry.bio}"
                            fireEventNotification(
                                id = bioGroupKey.hashCode(),
                                title = "${newEntry.displayName} updated bio",
                                text = "${newEntry.displayName} updated their bio",
                                profileUrl = "https://vrchat.com/home/user/$userId",
                                prefKey = VrchatNotificationPrefs.KEY_NOTIF_FRIEND_BIO,
                                channelId = NOTIF_CHANNEL_FRIENDS_ACTIVITY,
                                groupKey = GROUP_KEY_FRIENDS,
                                bigText = bioAlertBody,
                                alertBody = bioAlertBody,
                                alertBeforeText = old.bio,
                                alertAfterText = newEntry.bio,
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
                    serviceScope.launch {
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
            // Heartbeat: 30s lastSeenAt write while ANY admin is browsing the
            // dashboard or user directory. AdminBrowsingState is fed by a
            // snapshot listener on config/adminPresence (attached below).
            // When admin closes both tabs → flag goes stale → heartbeat
            // stops → no Firestore traffic. If the user app is force-killed,
            // no heartbeat fires regardless, so lastSeenAt goes stale and
            // the admin's staleness filter flips them to offline.
            launch {
                AdminBrowsingState.isBrowsing.collectLatest { browsing ->
                    if (browsing) {
                        while (true) {
                            try {
                                writeHeartbeat()
                            } catch (e: Exception) {
                                Log.w(TAG, "Heartbeat write failed", e)
                            }
                            delay(30_000)
                        }
                    }
                }
            }
            // Fast poll only while an admin is actively watching this user.
            // collectLatest cancels the inner loop the moment the watch flag
            // flips back to false, so Firestore traffic stops instantly.
            AdminWatchState.isWatched.collectLatest { watched ->
                if (watched) {
                    while (true) {
                        delay(5_000)
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

    private fun writeHeartbeat() {
        if (deviceHash.isBlank()) return
        FirebaseFirestore.getInstance()
            .collection("users")
            .document(deviceHash)
            .set(
                mapOf("lastSeenAt" to FieldValue.serverTimestamp()),
                SetOptions.merge()
            )
    }

    private var adminPresenceListener: com.google.firebase.firestore.ListenerRegistration? = null

    private fun attachAdminPresenceListener() {
        adminPresenceListener?.remove()
        adminPresenceListener = FirebaseFirestore.getInstance()
            .collection("config")
            .document("adminPresence")
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    AdminBrowsingState.updateFromTimestampMs(null)
                    if (err is com.google.firebase.firestore.FirebaseFirestoreException &&
                        err.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        Log.w(TAG, "adminPresence listener denied — detaching to avoid retry loop")
                        adminPresenceListener?.remove()
                        adminPresenceListener = null
                    }
                    return@addSnapshotListener
                }
                if (snap == null) {
                    AdminBrowsingState.updateFromTimestampMs(null)
                    return@addSnapshotListener
                }
                val ms = snap.getTimestamp("browsingAt")?.toDate()?.time
                AdminBrowsingState.updateFromTimestampMs(ms)
            }
    }

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
                    serviceScope.launch { handlePipelineMessage(text) }
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
        wsJob = serviceScope.launch {
            val backoffMs = (minOf(reconnectAttempt, 6) * 10_000L).coerceAtLeast(5_000L)
            reconnectAttempt++
            updatePersistentNotif("Reconnecting in ${backoffMs / 1000}s...")
            delay(backoffMs)
            if (VrchatAuthManager.isLoggedIn(this@VrchatPipelineService)) {
                updatePersistentNotif("Connecting...")
                connectWebSocket()
            } else {
                startPipeline()
            }
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
                        groupKey = GROUP_KEY_SOCIAL
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
                        groupKey = GROUP_KEY_SOCIAL
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
                    serviceScope.launch {
                        delay(OFFLINE_COOLDOWN_MS)
                        if (pendingOffline.containsKey(userId)) {
                            pendingOffline.remove(userId)
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
                    if (userId == myId && AdminWatchState.isWatched.value) syncPresenceToFirestore()
                }

                "user-update" -> {
                    if (AdminWatchState.isWatched.value) syncPresenceToFirestore()
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

                "user-location", "see-notification", "hide-notification",
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
                fireEventNotification(
                    id = "fr_$senderUserId".hashCode(),
                    title = "Friend request",
                    text = "$senderName sent you a friend request",
                    profileUrl = "https://vrchat.com/home/user/$senderUserId",
                    prefKey = VrchatNotificationPrefs.KEY_NOTIF_FRIEND_REQUEST,
                    channelId = NOTIF_CHANNEL_FRIEND_REQUESTS,
                    groupKey = GROUP_KEY_SOCIAL
                )
            }
            notifType == "invite" -> {
                val worldName = try {
                    JSONObject(content.optString("details", "{}")).optString("worldName", "a world")
                } catch (e: Exception) { "a world" }
                fireEventNotification(
                    id = "inv_$senderUserId".hashCode(),
                    title = "World invite",
                    text = "$senderName invited you to $worldName",
                    profileUrl = "https://vrchat.com/home/notifications",
                    prefKey = VrchatNotificationPrefs.KEY_NOTIF_INVITE,
                    channelId = NOTIF_CHANNEL_INVITES,
                    groupKey = GROUP_KEY_INVITES
                )
            }
            notifType == "requestInvite" -> {
                fireEventNotification(
                    id = "invreq_$senderUserId".hashCode(),
                    title = "Invite request",
                    text = "$senderName is asking for an invite to your instance",
                    profileUrl = "https://vrchat.com/home/notifications",
                    prefKey = VrchatNotificationPrefs.KEY_NOTIF_INVITE_REQUEST,
                    channelId = NOTIF_CHANNEL_INVITES,
                    groupKey = GROUP_KEY_INVITES
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
                    dedupId = notifId.ifBlank { null }
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
                                if (senderUserId.startsWith("grp_")) senderUserId else ""
                            }
                        }
                    }
                }
                val eventId = content.optString("eventId", "").ifBlank {
                    content.optJSONObject("data")?.optString("eventId", "").orEmpty().ifBlank {
                        detailsObj?.optString("eventId", "").orEmpty().ifBlank {
                            content.optString("id", "").let { id ->
                                if (id.startsWith("cal_")) id else ""
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
                        val displayGroupName = resolveGroupName(groupId, senderName)
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
                    }
                    v2Type.contains("event", true) || v2Type.contains("calendar", true) -> {
                        val displayGroupName = resolveGroupName(groupId, senderName)
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
                    }
                    v2Type.contains("invite", true) -> {
                        fireEventNotification(
                            id = baseId.hashCode(),
                            title = v2Title.ifBlank { "Group invite" },
                            text = message.take(140).ifBlank { "You've been invited to join a group" },
                            profileUrl = groupUrl,
                            prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_INVITE,
                            channelId = NOTIF_CHANNEL_INVITES,
                            groupKey = GROUP_KEY_INVITES
                        )
                    }
                    v2Type.contains("queue", true) -> {
                        fireEventNotification(
                            id = baseId.hashCode(),
                            title = v2Title.ifBlank { "Queue ready" },
                            text = message.take(140).ifBlank { "Your spot in a group instance queue is ready" },
                            profileUrl = groupUrl,
                            prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_QUEUE,
                            channelId = NOTIF_CHANNEL_GROUPS,
                            groupKey = GROUP_KEY_GROUPS
                        )
                    }
                    v2Type.contains("join", true) && v2Type.contains("request", true) -> {
                        fireEventNotification(
                            id = baseId.hashCode(),
                            title = v2Title.ifBlank { "Group join request" },
                            text = message.take(140).ifBlank { "Someone wants to join one of your groups" },
                            profileUrl = groupUrl,
                            prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_JOIN_REQUEST,
                            channelId = NOTIF_CHANNEL_GROUPS,
                            groupKey = GROUP_KEY_GROUPS
                        )
                    }
                    v2Type.contains("role", true) || v2Type.contains("transfer", true) -> {
                        fireEventNotification(
                            id = baseId.hashCode(),
                            title = v2Title.ifBlank { "Group role updated" },
                            text = message.take(140).ifBlank { "Your role in a group changed" },
                            profileUrl = groupUrl,
                            prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_ROLE,
                            channelId = NOTIF_CHANNEL_GROUPS,
                            groupKey = GROUP_KEY_GROUPS
                        )
                    }
                    v2Type.contains("instance", true) -> {
                        fireEventNotification(
                            id = baseId.hashCode(),
                            title = v2Title.ifBlank { "Group instance opened" },
                            text = message.take(140).ifBlank { "A new group instance is now joinable" },
                            profileUrl = groupUrl,
                            prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_INSTANCE,
                            channelId = NOTIF_CHANNEL_GROUPS,
                            groupKey = GROUP_KEY_GROUPS
                        )
                    }
                    v2Type.startsWith("group.") -> {
                        val fullBody = message.ifBlank { null }
                        fireEventNotification(
                            id = baseId.hashCode(),
                            title = v2Title.ifBlank { "Group activity" },
                            text = message.take(140).ifBlank { "New activity in one of your groups" },
                            profileUrl = groupUrl,
                            prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_EVENT,
                            channelId = NOTIF_CHANNEL_GROUPS,
                            groupKey = GROUP_KEY_GROUPS,
                            alertBody = if (fullBody != null && fullBody.length > 100) fullBody else null
                        )
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

    private fun prettyTrustRank(rank: String): String = when (rank) {
        "system_trust_legend"  -> "Legendary"
        "system_trust_veteran" -> "Veteran"
        "system_trust_trusted" -> "Trusted"
        "system_trust_known"   -> "Known"
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
        val newBio = user.optString("bio", previous.bio)
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

        if (newBio.isNotBlank() && newBio != previous.bio && previous.bio.isNotBlank()) {
            val bioGroupKey = "bio_$userId"
            val bioAlertBody = "Before: ${previous.bio}\nAfter: $newBio"
            // Stable Android notification ID — updates in place without re-alerting
            fireEventNotification(
                id = bioGroupKey.hashCode(),
                title = "$newDisplayName updated bio",
                text = "$newDisplayName updated their bio",
                profileUrl = "https://vrchat.com/home/user/$userId",
                prefKey = VrchatNotificationPrefs.KEY_NOTIF_FRIEND_BIO,
                channelId = NOTIF_CHANNEL_FRIENDS_ACTIVITY,
                groupKey = GROUP_KEY_FRIENDS,
                bigText = bioAlertBody,
                alertBody = bioAlertBody,
                alertBeforeText = previous.bio,
                alertAfterText = newBio,
                alertGroupKey = bioGroupKey
            )
        }

        if (newRank.isNotBlank() && newRank != previous.trustRank && previous.trustRank.isNotBlank()) {
            fireEventNotification(
                id = "rank_$userId".hashCode(),
                title = "Friend trust rank changed",
                text = "$newDisplayName is now ${prettyTrustRank(newRank)}",
                profileUrl = "https://vrchat.com/home/user/$userId",
                prefKey = VrchatNotificationPrefs.KEY_NOTIF_FRIEND_RANK,
                channelId = NOTIF_CHANNEL_FRIENDS_ACTIVITY,
                groupKey = GROUP_KEY_FRIENDS
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

    private fun persistFriendsCache() {
        try {
            FriendsCacheStore.save(this, friendsCache.toMap())
        } catch (e: Exception) {
            Log.w(TAG, "persistFriendsCache error", e)
        }
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
                groupKey = GROUP_KEY_SOCIAL
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
                    // Skip notifications older than 24h
                    val v1CreatedAt = obj.optString("created_at", "")
                    val v1CreatedMs = parseVrcTimestampMs(v1CreatedAt)
                    if (v1CreatedMs > 0 && System.currentTimeMillis() - v1CreatedMs > 24L * 60 * 60 * 1000) continue
                    when (notifType) {
                        "friendRequest" -> fireEventNotification(
                            id = "fr_$senderUserId".hashCode(),
                            title = "Friend request",
                            text = "$senderName sent you a friend request",
                            profileUrl = "https://vrchat.com/home/user/$senderUserId",
                            prefKey = VrchatNotificationPrefs.KEY_NOTIF_FRIEND_REQUEST,
                            channelId = NOTIF_CHANNEL_FRIEND_REQUESTS,
                            groupKey = GROUP_KEY_SOCIAL,
                            dedupId = notifId.ifBlank { null }
                        )
                        "invite" -> {
                            val worldName = try {
                                JSONObject(obj.optString("details", "{}")).optString("worldName", "a world")
                            } catch (e: Exception) { "a world" }
                            fireEventNotification(
                                id = "inv_$senderUserId".hashCode(),
                                title = "World invite",
                                text = "$senderName invited you to $worldName",
                                profileUrl = "https://vrchat.com/home/notifications",
                                prefKey = VrchatNotificationPrefs.KEY_NOTIF_INVITE,
                                channelId = NOTIF_CHANNEL_INVITES,
                                groupKey = GROUP_KEY_INVITES,
                                dedupId = notifId.ifBlank { null }
                            )
                        }
                        "requestInvite" -> fireEventNotification(
                            id = "invreq_$senderUserId".hashCode(),
                            title = "Invite request",
                            text = "$senderName is asking for an invite to your instance",
                            profileUrl = "https://vrchat.com/home/notifications",
                            prefKey = VrchatNotificationPrefs.KEY_NOTIF_INVITE_REQUEST,
                            channelId = NOTIF_CHANNEL_INVITES,
                            groupKey = GROUP_KEY_INVITES,
                            dedupId = notifId.ifBlank { null }
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
                            dedupId = notifId.ifBlank { null }
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
                    val notifId = obj.optString("id", "")
                    val v2Type = obj.optString("type", "")
                    val v2Title = obj.optString("title", "")
                    val v2Sender = obj.optString("senderUsername", "")
                    val message = obj.optString("message", "")
                    val v2CreatedAt = obj.optString("created_at", "")
                    val v2CreatedMs = parseVrcTimestampMs(v2CreatedAt)
                    // Group announcements/events are catch-up content: fire them
                    // however old they are (seenNotifIds still guarantees each one
                    // fires only once) so users away for a week don't miss them.
                    // Transient types (friend requests, invites, queue, etc.) skip
                    // when older than 24h to avoid resurfacing stale noise.
                    val isCatchUpType = v2Type.contains("announcement", true) ||
                        v2Type.contains("post", true) ||
                        v2Type.contains("event", true) ||
                        v2Type.contains("calendar", true)
                    if (!isCatchUpType && v2CreatedMs > 0 &&
                        System.currentTimeMillis() - v2CreatedMs > 24L * 60 * 60 * 1000) continue
                    val baseId = notifId.ifBlank { "$v2Type-${message.hashCode()}" }
                    val groupId = obj.optString("relatedGroupId", "").ifBlank {
                        obj.optString("groupId", "")
                    }
                    val groupUrl = if (groupId.isNotBlank()) "https://vrchat.com/home/group/$groupId"
                        else "https://vrchat.com/home/notifications"
                    when {
                        v2Type.contains("announcement", true) || v2Type.contains("post", true) -> {
                            val backfillGroupKey = if (groupId.isNotBlank()) "announcement_$groupId" else null
                            val displayName = resolveGroupName(groupId, v2Sender)
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
                        }
                        v2Type.contains("event", true) || v2Type.contains("calendar", true) -> {
                            val backfillGroupKey = if (groupId.isNotBlank()) "event_$groupId" else null
                            val displayName = resolveGroupName(groupId, v2Sender)
                            val eventId2 = obj.optString("eventId", "").ifBlank {
                                obj.optJSONObject("data")?.optString("eventId", "").orEmpty().ifBlank {
                                    notifId.let { if (it.startsWith("cal_")) it else "" }
                                }
                            }
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
                        }
                        v2Type.contains("invite", true) -> fireEventNotification(
                            id = baseId.hashCode(), title = v2Title.ifBlank { "Group invite" },
                            text = message.take(140).ifBlank { "You've been invited to join a group" },
                            profileUrl = groupUrl, prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_INVITE,
                            channelId = NOTIF_CHANNEL_INVITES, groupKey = GROUP_KEY_INVITES,
                            dedupId = notifId.ifBlank { null }
                        )
                        v2Type.contains("queue", true) -> fireEventNotification(
                            id = baseId.hashCode(), title = v2Title.ifBlank { "Queue ready" },
                            text = message.take(140).ifBlank { "Your spot in a group instance queue is ready" },
                            profileUrl = groupUrl, prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_QUEUE,
                            channelId = NOTIF_CHANNEL_GROUPS, groupKey = GROUP_KEY_GROUPS,
                            dedupId = notifId.ifBlank { null }
                        )
                        v2Type.contains("join", true) && v2Type.contains("request", true) -> fireEventNotification(
                            id = baseId.hashCode(), title = v2Title.ifBlank { "Group join request" },
                            text = message.take(140).ifBlank { "Someone wants to join one of your groups" },
                            profileUrl = groupUrl, prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_JOIN_REQUEST,
                            channelId = NOTIF_CHANNEL_GROUPS, groupKey = GROUP_KEY_GROUPS,
                            dedupId = notifId.ifBlank { null }
                        )
                        v2Type.contains("role", true) || v2Type.contains("transfer", true) -> fireEventNotification(
                            id = baseId.hashCode(), title = v2Title.ifBlank { "Group role updated" },
                            text = message.take(140).ifBlank { "Your role in a group changed" },
                            profileUrl = groupUrl, prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_ROLE,
                            channelId = NOTIF_CHANNEL_GROUPS, groupKey = GROUP_KEY_GROUPS,
                            dedupId = notifId.ifBlank { null }
                        )
                        v2Type.contains("instance", true) -> fireEventNotification(
                            id = baseId.hashCode(), title = v2Title.ifBlank { "Group instance opened" },
                            text = message.take(140).ifBlank { "A new group instance is now joinable" },
                            profileUrl = groupUrl, prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_INSTANCE,
                            channelId = NOTIF_CHANNEL_GROUPS, groupKey = GROUP_KEY_GROUPS,
                            dedupId = notifId.ifBlank { null }
                        )
                        v2Type.startsWith("group.") -> fireEventNotification(
                            id = baseId.hashCode(), title = v2Title.ifBlank { "Group activity" },
                            text = message.take(140).ifBlank { "New activity in one of your groups" },
                            profileUrl = groupUrl, prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_EVENT,
                            channelId = NOTIF_CHANNEL_GROUPS, groupKey = GROUP_KEY_GROUPS,
                            dedupId = notifId.ifBlank { null },
                            alertBody = if (message.length > 100) message else null
                        )
                    }
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
                        val announcement = VrchatAuthManager.fetchGroupAnnouncement(this@VrchatPipelineService, groupId)
                        if (announcement != null) {
                            val announcementText = announcement.optString("text", "").ifBlank {
                                announcement.optString("title", "")
                            }
                            val announcementTitle = announcement.optString("title", "").ifBlank { groupName }
                            val createdAt = announcement.optString("createdAt", "")
                            val createdMs = parseVrcTimestampMs(createdAt)
                            val lastSeen = seenMap.optString(groupId, "")
                            // No age cutoff: the per-group seen timestamp fires each
                            // announcement exactly once, so week-away users still get
                            // catch-up announcements without old ones resurfacing.
                            if (createdAt.isNotBlank() && createdAt != lastSeen && announcementText.isNotBlank()) {
                                fireEventNotification(
                                    id = "ga_${groupId}_${createdAt.hashCode()}".hashCode(),
                                    title = "Announcement from $groupName",
                                    text = "${announcementTitle}: ${announcementText.take(120)}",
                                    profileUrl = "https://vrchat.com/home/group/$groupId/posts",
                                    prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_ANNOUNCEMENT,
                                    channelId = NOTIF_CHANNEL_GROUPS,
                                    groupKey = GROUP_KEY_GROUPS,
                                    dedupId = "ga_${groupId}_$createdAt",
                                    alertGroupKey = "announcement_$groupId",
                                    alertBody = announcementText.ifBlank { null },
                                    alertEventTitle = announcementTitle.ifBlank { null },
                                    eventTimestampMs = createdMs.takeIf { it > 0 }
                                )
                                updatedMap.put(groupId, createdAt)
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

    // ------------------------------------------------------------------
    // Firestore presence sync
    // ------------------------------------------------------------------

    private suspend fun syncPresenceToFirestore(forceLocalUpdate: Boolean = false) {
        if (!forceLocalUpdate && deviceHash.isBlank()) return
        if (!forceLocalUpdate && !AdminWatchState.isWatched.value) return

        val presence = VrchatAuthManager.fetchPresence(this) ?: return
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
                    val targeted = if (deviceHash.isNotBlank()) checkTargetedUpdate(deviceHash) else null
                    val result = if (targeted is ReleaseCheckResult.UpdateAvailable) {
                        targeted
                    } else {
                        checkFirestoreRelease(BuildConfig.VERSION_CODE)
                    }
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
                    if (VrchatAuthManager.shouldRefreshCookies(this@VrchatPipelineService)) {
                        Log.i(TAG, "Auth cookies stale (>12 days) — proactively refreshing")
                        VrchatAuthManager.diagnoseAuthState(this@VrchatPipelineService)
                        val refreshed = VrchatAuthManager.autoRelogin(this@VrchatPipelineService)
                        Log.i(TAG, "Proactive auth refresh result: $refreshed")
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
            delay(5 * 60 * 1000L)
            while (true) {
                try {
                    pollGroupAnnouncements()
                } catch (e: Exception) {
                    Log.w(TAG, "Group announcement poll failed", e)
                }
                delay(5 * 60 * 1000L)
            }
        }
    }

    private suspend fun pollGroupAnnouncements() {
        if (!VrchatAuthManager.isLoggedIn(this)) return
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
                val announcement = VrchatAuthManager.fetchGroupAnnouncement(this, groupId)
                if (announcement != null) {
                    val text = announcement.optString("text", "").ifBlank {
                        announcement.optString("title", "")
                    }
                    val aTitle = announcement.optString("title", "").ifBlank { groupName }
                    val createdAt = announcement.optString("createdAt", "")
                    val createdMs = parseVrcTimestampMs(createdAt)
                    val lastSeen = seenMap.optString(groupId, "")
                    if (createdAt.isNotBlank() && createdAt != lastSeen && text.isNotBlank()) {
                        fireEventNotification(
                            id = "ga_${groupId}_${createdAt.hashCode()}".hashCode(),
                            title = "Announcement from $groupName",
                            text = "${aTitle}: ${text.take(120)}",
                            profileUrl = "https://vrchat.com/home/group/$groupId/posts",
                            prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_ANNOUNCEMENT,
                            channelId = NOTIF_CHANNEL_GROUPS,
                            groupKey = GROUP_KEY_GROUPS,
                            dedupId = "ga_${groupId}_$createdAt",
                            alertGroupKey = "announcement_$groupId",
                            alertBody = text.ifBlank { null },
                            alertEventTitle = aTitle.ifBlank { null },
                            eventTimestampMs = createdMs.takeIf { it > 0 }
                        )
                        updatedMap.put(groupId, createdAt)
                        changed = true
                    }
                }
                val posts = VrchatAuthManager.fetchGroupPosts(this, groupId, 5)
                if (posts != null) {
                    for (j in 0 until posts.length()) {
                        val post = posts.optJSONObject(j) ?: continue
                        val postId = post.optString("id", "")
                        if (postId.isBlank()) continue
                        val postTitle = post.optString("title", "").ifBlank { groupName }
                        val postText = post.optString("text", "")
                        val postCreatedAt = post.optString("createdAt", "")
                        val postCreatedMs = parseVrcTimestampMs(postCreatedAt)
                        val postSeenKey = "${groupId}_post_$postId"
                        val postLastSeen = seenMap.optString(postSeenKey, "")
                        if (postCreatedAt.isNotBlank() && postCreatedAt != postLastSeen && postText.isNotBlank()) {
                            fireEventNotification(
                                id = "gp_${postId}".hashCode(),
                                title = "Announcement from $groupName",
                                text = "${postTitle}: ${postText.take(120)}",
                                profileUrl = "https://vrchat.com/home/group/$groupId/posts",
                                prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_ANNOUNCEMENT,
                                channelId = NOTIF_CHANNEL_GROUPS,
                                groupKey = GROUP_KEY_GROUPS,
                                dedupId = "gp_${postId}_$postCreatedAt",
                                alertGroupKey = "announcement_$groupId",
                                alertBody = postText.ifBlank { null },
                                alertEventTitle = postTitle.ifBlank { null },
                                eventTimestampMs = postCreatedMs.takeIf { it > 0 }
                            )
                            updatedMap.put(postSeenKey, postCreatedAt)
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
        eventTimestampMs: Long? = null
    ) {
        if (dedupId != null) {
            synchronized(seenNotifIds) {
                if (!seenNotifIds.add(dedupId)) return
                while (seenNotifIds.size > MAX_SEEN_NOTIF_IDS) {
                    seenNotifIds.iterator().let { it.next(); it.remove() }
                }
            }
            persistSeenNotifIds()
        }
        val prefs = dataStore.data.first()
        val enabled = prefs[booleanPreferencesKey(prefKey)] ?: false
        if (!enabled) return

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val tapIntent = if (profileUrl != null) {
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

        // For grouped alerts, show event count in the Android notification title
        val displayTitle = if (alertGroupKey != null) {
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
                        eventTitle = alertEventTitle
                    )
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

    private fun fireNotLoggedInNotification() {
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
            .setSmallIcon(R.drawable.ic_notification)
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
}
