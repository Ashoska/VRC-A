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

    // Local friends cache for unfriend detection.
    // Keyed by userId -> displayName (snapshot at connect time).
    private val friendsCache = mutableMapOf<String, String>()
    // Pending offline notifications â€” userId -> time they went offline
    // We wait 10 minutes before notifying in case they're just hopping worlds
    private val pendingOffline = mutableMapOf<String, Long>()
    private val OFFLINE_COOLDOWN_MS = 10 * 60 * 1000L  // 10 minutes
    private var friendsCacheLoaded = false

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

    override fun onDestroy() {
        webSocket?.cancel()
        serviceScope.cancel()
        VrchatPipelineState.isConnected = false
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Pipeline management
    // ------------------------------------------------------------------

    private fun startPipeline() {
        wsJob?.cancel()
        wsJob = serviceScope.launch {
            // First validate/refresh session
            val valid = VrchatAuthManager.validateSession(this@VrchatPipelineService)
            if (!valid) {
                updatePersistentNotif("Not logged in to VRChat ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â tap to sign in")
                VrchatPipelineState.isConnected = false
                fireNotLoggedInNotification()
                return@launch
            }

            // Load friends cache for unfriend detection.
            // On first start: restore persisted cache from SharedPrefs (saved before app close),
            // then fetch fresh from API and diff to detect any unfriends since last session.
            if (!friendsCacheLoaded) {
                restoreFriendsCache()  // load persisted cache first
                val previousIds = friendsCache.keys.toSet()
                loadFriendsCache()     // fetch fresh list from API
                // Detect unfriends that happened while app was closed
                val removedIds = previousIds - friendsCache.keys
                removedIds.forEach { userId ->
                    // We can't get display name from API anymore, but we had it in the old cache
                    val displayName = friendsCache[userId] ?: "Someone"
                    fireEventNotification(
                        id = "unfriend_offline_$userId".hashCode(),
                        title = "Unfriended while offline",
                        text = "$displayName removed you as a friend",
                        profileUrl = "https://vrchat.com/home/user/$userId",
                        channelKey = VrchatNotificationPrefs.KEY_NOTIF_UNFRIEND
                    )
                }
            }

            // Sync initial presence to Firestore
            syncPresenceToFirestore()

            connectWebSocket()
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
                    updatePersistentNotif(
                        "Connected as ${VrchatAuthManager.getStoredDisplayName(this@VrchatPipelineService) ?: "VRChat user"}"
                    )
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
                    // Look up cached display name ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â after deletion the API won't return them
                    val displayName = friendsCache.remove(userId) ?: "Someone"
                    fireEventNotification(
                        id = "unfriend_$userId".hashCode(),
                        title = "Unfriended",
                        text = "$displayName removed you as a friend",
                        profileUrl = "https://vrchat.com/home/user/$userId",
                        channelKey = VrchatNotificationPrefs.KEY_NOTIF_UNFRIEND
                    )
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
                    // Cancel any pending offline notification â€” they hopped worlds
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
                    // Start a 10-minute cooldown â€” if they come back online within that
                    // window (world hop) we cancel the notification. Only fire if they
                    // stay offline for the full cooldown period.
                    pendingOffline[userId] = System.currentTimeMillis()
                    serviceScope.launch {
                        delay(OFFLINE_COOLDOWN_MS)
                        // Still pending? They didn't come back online â€” fire now
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
                    // Friend moved to a new world ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â update cache but no notification by default
                    val userId = content?.optString("userId") ?: return
                    val user = content.optJSONObject("user")
                    val displayName = user?.optString("displayName")
                    if (displayName != null) friendsCache[userId] = displayName

                    // Also sync user presence if it's the logged-in user
                    val myId = VrchatAuthManager.getStoredUserId(this@VrchatPipelineService)
                    if (userId == myId) syncPresenceToFirestore()
                }

                "user-update" -> {
                    // The logged-in user's profile changed ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â re-sync presence
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

        // Also persist to DataStore so service restarts can diff correctly
        persistFriendsCache()
        Log.i(TAG, "Loaded ${friendsCache.size} friends into cache")
    }

    private fun persistFriendsCache() {
        // Store as JSON string in SharedPreferences (not encrypted ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â just display names)
        val prefs = getSharedPreferences("vrca_friends_cache", Context.MODE_PRIVATE)
        val json = JSONObject()
        friendsCache.forEach { (id, name) -> json.put(id, name) }
        prefs.edit().putString("cache", json.toString()).apply()
    }

    private fun restoreFriendsCache() {
        val prefs = getSharedPreferences("vrca_friends_cache", Context.MODE_PRIVATE)
        val raw = prefs.getString("cache", null) ?: return
        try {
            val json = JSONObject(raw)
            json.keys().forEach { key -> friendsCache[key] = json.optString(key) }
            friendsCacheLoaded = friendsCache.isNotEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore friends cache", e)
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
            "vrchatStatus" to presence.status,
            "vrchatStatusDescription" to presence.statusDescription,
            "vrchatWorld" to presence.worldName,
            "vrchatLocation" to presence.location,
            "vrchatInstancePlayerCount" to presence.instancePlayerCount,
            "vrchatInstanceCapacity" to presence.instanceCapacity,
            "vrchatPlatform" to presence.platform,
            "vrchatAvatarThumb" to presence.currentAvatarThumbnailUrl,
            "vrchatLastSyncAt" to FieldValue.serverTimestamp()
        )

        try {
            FirebaseFirestore.getInstance()
                .collection("users")
                .document(deviceHash)
                .set(updates, SetOptions.merge())
                .addOnFailureListener { e -> Log.w(TAG, "Firestore sync failed", e) }
        } catch (e: Exception) {
            Log.e(TAG, "syncPresenceToFirestore error", e)
        }

        // Update state object for in-app display
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
        val enabled = prefs[booleanPreferencesKey(channelKey)] ?: true
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
 * Shared in-memory state for the pipeline ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â lets the UI read connection
 * status and presence data without needing to bind to the service.
 */
object VrchatPipelineState {
    var isConnected: Boolean = false
    var presence: VrchatAuthManager.VrcUserPresence? = null
}
