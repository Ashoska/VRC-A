package com.scrapw.chatbox.vrchat

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
import com.google.firebase.firestore.SetOptions
import com.scrapw.chatbox.MainActivity
import com.scrapw.chatbox.R
import com.scrapw.chatbox.dataStore
import com.scrapw.chatbox.sync.AdminBrowsingState
import com.scrapw.chatbox.sync.AdminWatchState
import com.scrapw.chatbox.vrchat.VrchatNotificationPrefs
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
import org.json.JSONObject
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

        const val ACTION_START = "com.scrapw.chatbox.PIPELINE_START"
        const val ACTION_STOP = "com.scrapw.chatbox.PIPELINE_STOP"

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
    // Per-friend throttle for chatty events (location/avatar/status). One
    // notification per friend per LOCATION_NOTIF_COOLDOWN_MS prevents spam
    // when a friend rapidly hops worlds or swaps avatars.
    private val lastFriendLocationNotifMs = mutableMapOf<String, Long>()
    private val lastFriendAvatarNotifMs = mutableMapOf<String, Long>()
    private val lastFriendStatusNotifMs = mutableMapOf<String, Long>()
    private val LOCATION_NOTIF_COOLDOWN_MS = 60_000L
    private var pipelineConnectedAtMs = 0L
    private var presenceRefreshJob: Job? = null
    // Tracks last connection notification state so we don't spam connect/disconnect.
    private var lastConnectionNotifWasUp: Boolean? = null

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
        createNotificationChannels()
        attachAdminPresenceListener()
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
    }

    override fun onDestroy() {
        adminPresenceListener?.remove()
        adminPresenceListener = null
        webSocket?.cancel()
        serviceScope.cancel()
        VrchatPipelineState.isConnected = false
        VrchatPipelineState.presence = null
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
                FirebaseFirestore.getInstance()
                    .collection("users").document(deviceHash)
                    .set(mapOf(
                        "isOnlineInApp" to false,
                        "lastSeenAt" to FieldValue.serverTimestamp(),
                        // Drop any legacy friends data still lingering on the doc.
                        "savedFriendIds" to FieldValue.delete(),
                        "savedFriendNames" to FieldValue.delete()
                    ), SetOptions.merge())
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

            // Sync initial presence to Firestore
            syncPresenceToFirestore()

            startPresenceRefreshLoop()
            connectWebSocket()
        }
    }

    private fun startPresenceRefreshLoop() {
        presenceRefreshJob?.cancel()
        presenceRefreshJob = serviceScope.launch {
            // Always-on slow poll: keeps in-app presence (location, world,
            // status) fresh even when no admin is watching. The Firestore
            // write inside syncPresenceToFirestore is gated by AdminWatchState,
            // so this loop only updates VrchatPipelineState locally and pays
            // no Firestore traffic when unwatched. Without this loop the
            // in-app world/location can go stale because VRChat doesn't
            // always send user-update events when the user changes worlds.
            launch {
                while (true) {
                    try {
                        syncPresenceToFirestore()
                    } catch (e: Exception) {
                        Log.w(TAG, "Slow presence refresh failed", e)
                    }
                    delay(15_000)
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
                        delay(500)
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
                if (err != null || snap == null) {
                    AdminBrowsingState.updateFromTimestampMs(null)
                    return@addSnapshotListener
                }
                val ms = snap.getTimestamp("browsingAt")?.toDate()?.time
                AdminBrowsingState.updateFromTimestampMs(ms)
            }
    }

    private suspend fun connectWebSocket() {
        val cookieHeader = VrchatAuthManager.getCookieHeader(this) ?: return
        // Extract auth token value from cookie string (auth=authcookie_xxx)
        val authToken = cookieHeader.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("auth=") }
            ?.removePrefix("auth=")
            ?: return

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
                    updatePersistentNotif(
                        "Connected as ${VrchatAuthManager.getStoredDisplayName(this@VrchatPipelineService) ?: "VRChat user"}"
                    )
                    serviceScope.launch { fireConnectionNotification(true) }
                    // Auto-start Discord RPC if enabled
                    serviceScope.launch {
                        try {
                            val prefs = dataStore.data.first()
                            val rpcEnabled = prefs[booleanPreferencesKey("discord_rpc_enabled")] ?: false
                            val rpcToken = prefs[androidx.datastore.preferences.core.stringPreferencesKey("discord_token")] ?: ""
                            if (rpcEnabled && rpcToken.isNotBlank() && !DiscordRpcService.isRunning) {
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
                connectWebSocket()
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
                    val cached = friendsCache.remove(userId)
                    persistFriendsCache()
                    if (cached != null && notifiedUnfriendIds.add(userId)) {
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
                    val previous = friendsCache[userId]
                    val newStatus = user?.optString("status") ?: previous?.status.orEmpty()
                    friendsCache[userId] = entryFromUserJson(user, displayName).copy(
                        location = location,
                        worldName = worldName
                    )
                    persistFriendsCache()
                    val notifText = when {
                        location == "traveling" -> "$displayName is traveling between worlds"
                        location == "private" -> "$displayName is now online in a private instance"
                        worldName.isNotBlank() -> "$displayName is now online in $worldName"
                        else -> "$displayName is now online"
                    }
                    pendingOffline.remove(userId)
                    fireEventNotification(
                        id = "online_$userId".hashCode(),
                        title = "Friend online",
                        text = notifText,
                        profileUrl = "https://vrchat.com/home/user/$userId",
                        prefKey = VrchatNotificationPrefs.KEY_NOTIF_FRIEND_ONLINE,
                        channelId = NOTIF_CHANNEL_FRIENDS_ACTIVITY,
                        groupKey = GROUP_KEY_FRIENDS
                    )
                    maybeFireStatusChange(userId, displayName, previous?.status.orEmpty(), newStatus)
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
                                profileUrl = null,
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

                    // Fire location notification if world actually changed and is non-private.
                    if (worldName.isNotBlank() && worldName != previousWorld &&
                        location != "private" && location != "traveling") {
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
                    if (userId == myId) syncPresenceToFirestore()
                }

                "user-update" -> {
                    syncPresenceToFirestore()
                }

                "friend-update" -> {
                    handleFriendUpdate(content)
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
                    profileUrl = null,
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
                    profileUrl = null,
                    prefKey = VrchatNotificationPrefs.KEY_NOTIF_INVITE,
                    channelId = NOTIF_CHANNEL_INVITES,
                    groupKey = GROUP_KEY_INVITES
                )
            }
            // notification-v2 group events
            type == "notification-v2" -> {
                val v2Type = content.optString("type", "")
                val v2Title = content.optString("title", "")
                val baseId = if (notifId.isNotBlank()) notifId else "$v2Type-${message.hashCode()}"
                when {
                    v2Type.contains("announcement", true) -> {
                        fireEventNotification(
                            id = baseId.hashCode(),
                            title = v2Title.ifBlank { "Group announcement" },
                            text = message.take(140).ifBlank { "New announcement in one of your groups" },
                            profileUrl = null,
                            prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_ANNOUNCEMENT,
                            channelId = NOTIF_CHANNEL_GROUPS,
                            groupKey = GROUP_KEY_GROUPS
                        )
                    }
                    v2Type.contains("invite", true) -> {
                        fireEventNotification(
                            id = baseId.hashCode(),
                            title = v2Title.ifBlank { "Group invite" },
                            text = message.take(140).ifBlank { "You've been invited to join a group" },
                            profileUrl = null,
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
                            profileUrl = null,
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
                            profileUrl = null,
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
                            profileUrl = null,
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
                            profileUrl = null,
                            prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_INSTANCE,
                            channelId = NOTIF_CHANNEL_GROUPS,
                            groupKey = GROUP_KEY_GROUPS
                        )
                    }
                    v2Type.startsWith("group.") -> {
                        fireEventNotification(
                            id = baseId.hashCode(),
                            title = v2Title.ifBlank { "Group activity" },
                            text = message.take(140).ifBlank { "New activity in one of your groups" },
                            profileUrl = null,
                            prefKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_EVENT,
                            channelId = NOTIF_CHANNEL_GROUPS,
                            groupKey = GROUP_KEY_GROUPS
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
            bio = user.optString("bio", "")
        )
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
            title = "Friend status changed",
            text = "$displayName is now ${prettyStatus(newStatus)}",
            profileUrl = "https://vrchat.com/home/user/$userId",
            prefKey = VrchatNotificationPrefs.KEY_NOTIF_FRIEND_STATUS,
            channelId = NOTIF_CHANNEL_FRIENDS_ACTIVITY,
            groupKey = GROUP_KEY_FRIENDS
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

        // Update cache regardless of whether we notify.
        friendsCache[userId] = previous.copy(
            displayName = newDisplayName,
            status = newStatus,
            avatarThumb = newAvatar,
            bio = newBio
        )
        persistFriendsCache()

        // Display name change.
        if (newDisplayName != previous.displayName && previous.displayName.isNotBlank()) {
            fireEventNotification(
                id = "name_$userId".hashCode(),
                title = "Friend renamed",
                text = "${previous.displayName} is now known as $newDisplayName",
                profileUrl = "https://vrchat.com/home/user/$userId",
                prefKey = VrchatNotificationPrefs.KEY_NOTIF_FRIEND_DISPLAY_NAME,
                channelId = NOTIF_CHANNEL_FRIENDS_ACTIVITY,
                groupKey = GROUP_KEY_FRIENDS
            )
        }

        // Status change.
        maybeFireStatusChange(userId, newDisplayName, previous.status, newStatus)

        // Avatar change (throttled per friend).
        if (newAvatar.isNotBlank() && newAvatar != previous.avatarThumb && previous.avatarThumb.isNotBlank()) {
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

        // Bio change.
        if (newBio.isNotBlank() && newBio != previous.bio && previous.bio.isNotBlank()) {
            fireEventNotification(
                id = "bio_$userId".hashCode(),
                title = "Friend updated profile",
                text = "$newDisplayName updated their bio",
                profileUrl = "https://vrchat.com/home/user/$userId",
                prefKey = VrchatNotificationPrefs.KEY_NOTIF_FRIEND_BIO,
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
                bio = it.bio
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

    /**
     * Two-fetch confirmation: only fire unfriend notifications for IDs
     * absent from BOTH the first fetch (captured into [candidateRemovals])
     * and the second fetch (this call's [friendsCache]). Eliminates false
     * positives from VRChat API pagination flaps.
     */
    private suspend fun diffFriendsCache(
        previousIds: Set<String>,
        previousNames: Map<String, String>,
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
        confirmedRemovals.forEach { userId ->
            if (notifiedUnfriendIds.add(userId)) {
                val displayName = previousNames[userId] ?: "Someone"
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
        }
        Log.i(TAG, "Unfriend diff: ${confirmedRemovals.size} confirmed removals " +
            "(prev=${previousIds.size}, candidates=${candidateRemovals.size}, now=${friendsCache.size})")
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
    // Firestore presence sync
    // ------------------------------------------------------------------

    private suspend fun syncPresenceToFirestore() {
        val presence = VrchatAuthManager.fetchPresence(this) ?: return

        // Always update in-app state so the user's own UI stays current —
        // this happens regardless of deviceHash or watch status, since the
        // in-app VRChat tab reads from VrchatPipelineState.
        VrchatPipelineState.presence = presence

        // Firestore write is gated by both deviceHash availability AND admin
        // watch status. Admins only see live VRChat data while they have the
        // user selected; writing every state change to Firestore for every
        // user 24/7 is what blew through the free quota.
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
    // Notifications
    // ------------------------------------------------------------------

    private suspend fun fireEventNotification(
        id: Int,
        title: String,
        text: String,
        profileUrl: String?,
        prefKey: String,
        channelId: String,
        groupKey: String? = null
    ) {
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

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (groupKey != null) {
            builder.setGroup(groupKey)
            // Also publish/refresh a group summary so multiple notifs stack.
            publishGroupSummary(nm, groupKey, channelId)
        }

        nm.notify(id, builder.build())
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
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(summaryText)
            .setContentText("Multiple updates")
            .setStyle(NotificationCompat.InboxStyle().setSummaryText(summaryText))
            .setGroup(groupKey)
            .setGroupSummary(true)
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
            .setSmallIcon(R.drawable.ic_launcher_foreground)
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
        val prefs = dataStore.data.first()
        val enabled = prefs[booleanPreferencesKey(VrchatNotificationPrefs.KEY_NOTIF_CONNECTION)] ?: false
        if (!enabled) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val tapIntent = PendingIntent.getActivity(
            this, NOTIF_ID_CONNECTION,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, NOTIF_CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (connected) "VRChat connected" else "VRChat disconnected")
            .setContentText(if (connected) "Monitoring VRChat events"
                            else "Lost connection — attempting to reconnect")
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
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_PERSISTENT)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("VRC-A")
            .setContentText(status)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
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
}
