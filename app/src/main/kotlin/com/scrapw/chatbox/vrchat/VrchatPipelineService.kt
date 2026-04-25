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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import com.google.android.gms.tasks.Tasks
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
        private const val NOTIF_CHANNEL_EVENTS = "vrca_vrchat_events"
        private const val NOTIF_ID_PERSISTENT = 1001

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

    private val friendsCache = mutableMapOf<String, String>()
    private val pendingOffline = mutableMapOf<String, Long>()
    private val OFFLINE_COOLDOWN_MS = 10 * 60 * 1000L
    private var friendsCacheLoaded = false
    private var friendsFetchCount = 0
    private var lastUnfriendDiffMs = 0L
    // Tracks user IDs for which an unfriend notification has already been fired
    // this session, so the real-time and offline-diff handlers don't both fire.
    private val notifiedUnfriendIds = mutableSetOf<String>()
    private var pipelineConnectedAtMs = 0L
    private var presenceRefreshJob: Job? = null

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                deviceHash = intent?.getStringExtra(EXTRA_DEVICE_HASH) ?: ""
                startForeground(NOTIF_ID_PERSISTENT, buildPersistentNotification("Connecting..."))
                startPipeline()
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
                        "lastSeenAt" to FieldValue.serverTimestamp()
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
                val previousIds = friendsCache.keys.toSet()
                val previousNames = friendsCache.toMap()
                loadFriendsCache()
                friendsFetchCount++

                // Schedule a delayed second fetch + diff after 60s grace period.
                // This avoids false positives from incomplete API results on first connect.
                if (previousIds.isNotEmpty()) {
                    serviceScope.launch {
                        delay(60_000)
                        val snapshotBeforeRefresh = friendsCache.toMap()
                        loadFriendsCache()
                        friendsFetchCount++
                        diffFriendsCache(previousIds, previousNames)
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
                    scheduleReconnect()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "Pipeline closed: $code $reason")
                    VrchatPipelineState.isConnected = false
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
                    // Add to cache and persist
                    friendsCache[userId] = displayName
                    persistFriendsCache()
                    fireEventNotification(
                        id = userId.hashCode(),
                        title = "New friend",
                        text = "$displayName accepted your friend request",
                        profileUrl = "https://vrchat.com/home/user/$userId",
                        channelKey = VrchatNotificationPrefs.KEY_NOTIF_FRIEND_REQUEST
                    )
                }

                "friend-delete" -> {
                    val userId = content?.optString("userId") ?: return
                    val displayName = friendsCache.remove(userId) ?: "Someone"
                    persistFriendsCache()
                    if (notifiedUnfriendIds.add(userId)) {
                        fireEventNotification(
                            id = "unfriend_$userId".hashCode(),
                            title = "Unfriended",
                            text = "$displayName removed you as a friend",
                            profileUrl = "https://vrchat.com/home/user/$userId",
                            channelKey = VrchatNotificationPrefs.KEY_NOTIF_UNFRIEND
                        )
                    }
                }

                "friend-online" -> {
                    val userId = content?.optString("userId") ?: return
                    val user = content.optJSONObject("user")
                    val displayName = user?.optString("displayName")
                        ?: friendsCache[userId] ?: userId
                    val location = content.optString("location", "")
                    friendsCache[userId] = displayName
                    persistFriendsCache()
                    val locationText = when {
                        location.isBlank() || location == "private" -> "a private world"
                        location == "traveling" -> "traveling between worlds"
                        else -> "VRChat"
                    }
                    // Cancel any pending offline notification -- they hopped worlds
                    pendingOffline.remove(userId)
                    fireEventNotification(
                        id = "online_$userId".hashCode(),
                        title = "Friend online",
                        text = "$displayName is now online in $locationText",
                        profileUrl = "https://vrchat.com/home/user/$userId",
                        channelKey = VrchatNotificationPrefs.KEY_NOTIF_FRIEND_ONLINE
                    )
                }

                "friend-offline" -> {
                    val userId = content?.optString("userId") ?: return
                    // Start a 10-minute cooldown -- if they come back online within that
                    // window (world hop) we cancel the notification. Only fire if they
                    // stay offline for the full cooldown period.
                    pendingOffline[userId] = System.currentTimeMillis()
                    serviceScope.launch {
                        delay(OFFLINE_COOLDOWN_MS)
                        // Still pending? They didn't come back online -- fire now
                        if (pendingOffline.containsKey(userId)) {
                            pendingOffline.remove(userId)
                            val displayName = friendsCache[userId] ?: "A friend"
                            fireEventNotification(
                                id = "offline_$userId".hashCode(),
                                title = "Friend offline",
                                text = "$displayName went offline",
                                profileUrl = null,
                                channelKey = VrchatNotificationPrefs.KEY_NOTIF_FRIEND_OFFLINE
                            )
                        }
                    }
                }

                "notification", "notification-v2" -> {
                    handleNotificationEvent(content, type)
                }

                "friend-location" -> {
                    // Friend moved to a new world - update cache but no notification by default
                    val userId = content?.optString("userId") ?: return
                    val user = content.optJSONObject("user")
                    val displayName = user?.optString("displayName")
                    if (displayName != null) friendsCache[userId] = displayName

                    // Also sync user presence if it's the logged-in user
                    val myId = VrchatAuthManager.getStoredUserId(this@VrchatPipelineService)
                    if (userId == myId) syncPresenceToFirestore()
                }

                "user-update" -> {
                    // The logged-in user's profile changed - re-sync presence
                    syncPresenceToFirestore()
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

        when {
            notifType == "friendRequest" -> {
                fireEventNotification(
                    id = "fr_$senderUserId".hashCode(),
                    title = "Friend request",
                    text = "$senderName sent you a friend request",
                    profileUrl = "https://vrchat.com/home/user/$senderUserId",
                    channelKey = VrchatNotificationPrefs.KEY_NOTIF_FRIEND_REQUEST
                )
            }
            notifType == "invite" || notifType == "requestInvite" -> {
                val worldName = try {
                    JSONObject(content.optString("details", "{}")).optString("worldName", "a world")
                } catch (e: Exception) { "a world" }
                fireEventNotification(
                    id = "inv_$senderUserId".hashCode(),
                    title = if (notifType == "invite") "Invite received" else "Invite request",
                    text = if (notifType == "invite") "$senderName invited you to $worldName"
                           else "$senderName is requesting an invite",
                    profileUrl = null,
                    channelKey = VrchatNotificationPrefs.KEY_NOTIF_INVITE
                )
            }
            // notification-v2 group events
            type == "notification-v2" -> {
                val v2Type = content.optString("type", "")
                when {
                    v2Type.startsWith("group.announcement") -> {
                        fireEventNotification(
                            id = content.optString("id", "ga").hashCode(),
                            title = "Group announcement",
                            text = message.take(100).ifBlank { "New group announcement" },
                            profileUrl = null,
                            channelKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_ANNOUNCEMENT
                        )
                    }
                    v2Type.startsWith("group.") -> {
                        fireEventNotification(
                            id = content.optString("id", "ge").hashCode(),
                            title = "Group event",
                            text = message.take(100).ifBlank { "New group activity" },
                            profileUrl = null,
                            channelKey = VrchatNotificationPrefs.KEY_NOTIF_GROUP_EVENT
                        )
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Friends cache
    // ------------------------------------------------------------------

    private suspend fun loadFriendsCache() {
        val friends = VrchatAuthManager.fetchFriends(this)
        friendsCache.clear()
        friends.forEach { friendsCache[it.userId] = it.displayName }
        friendsCacheLoaded = true

        // Persist to Firestore so service restarts can diff correctly
        persistFriendsCache()
        Log.i(TAG, "Loaded ${friendsCache.size} friends into cache")
    }

    private fun persistFriendsCache() {
        if (deviceHash.isBlank()) return
        try {
            val ids = friendsCache.keys.toList()
            val names = friendsCache.values.toList()
            FirebaseFirestore.getInstance()
                .collection("users")
                .document(deviceHash)
                .set(mapOf(
                    "savedFriendIds" to ids,
                    "savedFriendNames" to names
                ), SetOptions.merge())
                .addOnFailureListener { e -> Log.w(TAG, "Failed to persist friends cache", e) }
        } catch (e: Exception) {
            Log.w(TAG, "persistFriendsCache error", e)
        }
    }

    private suspend fun diffFriendsCache(
        previousIds: Set<String>,
        previousNames: Map<String, String>
    ) {
        val now = System.currentTimeMillis()
        if (now - lastUnfriendDiffMs < 5 * 60 * 1000L) return
        if (previousIds.isEmpty() || friendsCache.isEmpty()) return
        // Guard: if fresh list is less than 80% of previous, assume API pagination error
        if (friendsCache.size < previousIds.size * 4 / 5) {
            Log.w(TAG, "Skipping unfriend diff: fresh list (${friendsCache.size}) < 80% of previous (${previousIds.size})")
            return
        }
        lastUnfriendDiffMs = now
        val removedIds = previousIds - friendsCache.keys
        removedIds.forEach { userId ->
            if (notifiedUnfriendIds.add(userId)) {
                val displayName = previousNames[userId] ?: "Someone"
                fireEventNotification(
                    id = "unfriend_offline_$userId".hashCode(),
                    title = "Unfriended while offline",
                    text = "$displayName removed you as a friend",
                    profileUrl = "https://vrchat.com/home/user/$userId",
                    channelKey = VrchatNotificationPrefs.KEY_NOTIF_UNFRIEND
                )
            }
        }
        if (removedIds.isNotEmpty()) {
            Log.i(TAG, "Unfriend diff: ${removedIds.size} removed (prev=${previousIds.size}, now=${friendsCache.size})")
        }
    }

    private suspend fun restoreFriendsCache() {
        if (deviceHash.isBlank()) return
        try {
            val doc = withContext(Dispatchers.IO) {
                Tasks.await(
                    FirebaseFirestore.getInstance()
                        .collection("users")
                        .document(deviceHash)
                        .get()
                )
            }
            @Suppress("UNCHECKED_CAST")
            val ids = doc.get("savedFriendIds") as? List<String> ?: return
            @Suppress("UNCHECKED_CAST")
            val names = doc.get("savedFriendNames") as? List<String> ?: return
            if (ids.size != names.size) return
            ids.zip(names).forEach { (id, name) -> friendsCache[id] = name }
            friendsCacheLoaded = friendsCache.isNotEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore friends cache from Firestore", e)
        }
    }

    // ------------------------------------------------------------------
    // Firestore presence sync
    // ------------------------------------------------------------------

    private suspend fun syncPresenceToFirestore() {
        if (deviceHash.isBlank()) return
        val presence = VrchatAuthManager.fetchPresence(this) ?: return

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
            "isOnlineInApp" to true,
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

        // Update state object for in-app display (always, even if Firestore fails)
        VrchatPipelineState.presence = presence
    }

    // ------------------------------------------------------------------
    // Notifications
    // ------------------------------------------------------------------

    private suspend fun fireEventNotification(
        id: Int,
        title: String,
        text: String,
        profileUrl: String?,
        channelKey: String
    ) {
        // Check if user has this notification type enabled
        val prefs = dataStore.data.first()
        val enabled = prefs[booleanPreferencesKey(channelKey)] ?: false
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

        val notif = NotificationCompat.Builder(this, NOTIF_CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        nm.notify(id, notif)
    }

    private fun fireNotLoggedInNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val tapIntent = PendingIntent.getActivity(
            this, 9999,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, NOTIF_CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("VRC-A: Sign in required")
            .setContentText("Tap to re-sign in to your VRChat account")
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(9998, notif)
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

        // Silent persistent service channel
        NotificationChannel(
            NOTIF_CHANNEL_PERSISTENT,
            "VRC-A Background",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Shows when VRC-A is monitoring VRChat events in the background"
            setShowBadge(false)
            nm.createNotificationChannel(this)
        }

        // Event notifications channel
        NotificationChannel(
            NOTIF_CHANNEL_EVENTS,
            "VRChat Events",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Friend requests, invites, unfriend alerts, and group events"
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
