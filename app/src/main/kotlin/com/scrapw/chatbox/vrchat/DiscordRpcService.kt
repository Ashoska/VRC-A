package com.scrapw.chatbox.vrchat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.datastore.preferences.core.edit
import com.scrapw.chatbox.R
import com.scrapw.chatbox.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class DiscordRpcService : Service() {

    companion object {
        private const val TAG = "DiscordRPC"
        private const val GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"
        private const val VRCHAT_APP_ID = "438274841678872576"
        private const val NOTIF_CHANNEL = "vrca_pipeline"
        private const val NOTIF_ID = 1001

        // Public HTTPS URL of the VRChat logo used as the default RPC image.
        // Hosted on a public GitHub repo so Discord's external-assets endpoint
        // can fetch it anonymously to mint an `mp:external/...` reference.
        private const val DEFAULT_VRCHAT_IMAGE_URL =
            "https://raw.githubusercontent.com/shadowash321rulse-lab/VRChat-rpc-display/main/vrchat-1102x620.jpg"

        // DataStore key for the persistent `mp:external/...` reference.
        // Once resolved, the reference is stable and reused across restarts.
        private const val DEFAULT_IMAGE_REF_PREF = "discord_default_image_ref"

        const val ACTION_START = "com.scrapw.chatbox.DISCORD_RPC_START"
        const val ACTION_STOP = "com.scrapw.chatbox.DISCORD_RPC_STOP"

        var isRunning = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ws: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var presenceJob: Job? = null
    private var lastSeq: Int? = null
    private var token = ""

    private var onlineStartEpochMs = 0L

    private val assetResolver = DiscordExternalAssetResolver(
        applicationId = VRCHAT_APP_ID,
        tokenProvider = { token }
    )

    private val client by lazy {
        OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        ensureChannel()
        startForeground(NOTIF_ID, buildNotif("Connected + Discord RPC starting..."))

        // If the service is already running and connected, treat duplicate
        // ACTION_START intents as a no-op. Resetting onlineStartEpochMs and
        // re-launching connect() here would zero out the elapsed-time counter
        // that Discord shows in the user's activity card — which is exactly
        // the bug users reported as "RPC time counter resetting".
        if (isRunning && ws != null) {
            return START_STICKY
        }

        onlineStartEpochMs = 0L

        scope.launch {
            val prefs = dataStore.data.first()
            token = prefs[androidx.datastore.preferences.core.stringPreferencesKey("discord_token")] ?: ""
            if (token.isBlank()) {
                Log.w(TAG, "No Discord token configured")
                updateNotif("No Discord token - configure in settings")
                return@launch
            }

            // Seed the resolver's in-memory cache with the persisted default-image
            // reference (if we've resolved it before). This avoids a fresh API
            // round-trip on every app start and removes the brief moment where
            // the presence loop falls back to the unrendered "vrchat" key.
            val cachedRef = prefs[androidx.datastore.preferences.core.stringPreferencesKey(DEFAULT_IMAGE_REF_PREF)]
            if (!cachedRef.isNullOrBlank()) {
                assetResolver.prePopulate(DEFAULT_VRCHAT_IMAGE_URL, cachedRef)
            } else {
                // First run: resolve once and persist the reference.
                scope.launch {
                    val ref = assetResolver.resolve(DEFAULT_VRCHAT_IMAGE_URL)
                    if (!ref.isNullOrBlank()) {
                        runCatching {
                            applicationContext.dataStore.edit { p ->
                                p[androidx.datastore.preferences.core.stringPreferencesKey(DEFAULT_IMAGE_REF_PREF)] = ref
                            }
                        }
                    }
                }
            }

            connect()
        }
        // START_STICKY so Android restarts the service if killed; the duplicate
        // intent guard above handles the resulting null-intent restart.
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        disconnect()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        disconnect()
        scope.cancel()
        isRunning = false
        super.onDestroy()
    }

    private fun connect() {
        val req = Request.Builder().url(GATEWAY_URL).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Gateway connected")
                isRunning = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    handleMessage(webSocket, JSONObject(text))
                } catch (e: Exception) {
                    Log.e(TAG, "Message handling error", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Gateway closing: $code $reason")
                webSocket.close(1000, null)
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Gateway failure", t)
                scheduleReconnect()
            }
        })
    }

    private fun disconnect() {
        heartbeatJob?.cancel()
        presenceJob?.cancel()
        val socket = ws
        ws = null
        isRunning = false
        if (socket == null) return
        // Clear presence synchronously so the work finishes before process
        // death. Thread.sleep blocks the caller (~1.5s) which is fine since
        // the service is shutting down anyway. Android gives onTaskRemoved
        // several seconds before force-killing the process.
        try {
            val clearPresence = JSONObject().apply {
                put("op", 3)
                put("d", JSONObject().apply {
                    put("since", JSONObject.NULL)
                    put("activities", JSONArray())
                    put("status", "online")
                    put("afk", false)
                })
            }
            socket.send(clearPresence.toString())
            Log.i(TAG, "Sent empty activities to clear Discord presence")
            Thread.sleep(1500)
        } catch (_: Throwable) {}
        try { socket.close(1000, "Service stopping") } catch (_: Throwable) {}
    }

    private fun scheduleReconnect() {
        isRunning = false
        heartbeatJob?.cancel()
        presenceJob?.cancel()
        scope.launch {
            delay(5000)
            if (token.isNotBlank()) connect()
        }
    }

    private fun handleMessage(webSocket: WebSocket, json: JSONObject) {
        val op = json.optInt("op")
        if (!json.isNull("s")) lastSeq = json.optInt("s")

        when (op) {
            10 -> {
                val interval = json.getJSONObject("d").getLong("heartbeat_interval")
                startHeartbeat(webSocket, interval)
                sendIdentify(webSocket)
            }
            11 -> { /* Heartbeat ACK */ }
            0 -> {
                val event = json.optString("t")
                if (event == "READY") {
                    Log.i(TAG, "Discord session ready")
                    updateNotif("Connected + Discord RPC active")
                    startPresenceLoop(webSocket)
                }
            }
            7 -> {
                Log.i(TAG, "Gateway requested reconnect")
                webSocket.close(4000, "Reconnecting")
                scheduleReconnect()
            }
            9 -> {
                Log.w(TAG, "Invalid session")
                webSocket.close(4000, "Invalid session")
                scheduleReconnect()
            }
        }
    }

    private fun startHeartbeat(webSocket: WebSocket, intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                delay(intervalMs)
                val payload = JSONObject().apply {
                    put("op", 1)
                    put("d", lastSeq ?: JSONObject.NULL)
                }
                webSocket.send(payload.toString())
            }
        }
    }

    private fun sendIdentify(webSocket: WebSocket) {
        val presence = buildPresenceData()
        val identify = JSONObject().apply {
            put("op", 2)
            put("d", JSONObject().apply {
                put("token", token)
                put("intents", 0)
                put("properties", JSONObject().apply {
                    put("os", "Windows")
                    put("browser", "Discord Client")
                    put("device", "")
                })
                put("presence", presence)
            })
        }
        webSocket.send(identify.toString())
    }

    private fun startPresenceLoop(webSocket: WebSocket) {
        presenceJob?.cancel()
        presenceJob = scope.launch {
            while (true) {
                delay(5_000)
                val update = JSONObject().apply {
                    put("op", 3)
                    put("d", buildPresenceData())
                }
                try {
                    webSocket.send(update.toString())
                } catch (e: Exception) {
                    Log.w(TAG, "Presence update send failed", e)
                    break
                }
            }
        }
    }

    /**
     * Returns a cached `mp:external/...` reference if available; otherwise
     * kicks off async resolution and returns the `"vrchat"` asset key as
     * a fallback. The next presence loop tick (5s later) will pick up the
     * resolved value once it lands in the cache.
     */
    private fun resolveOrFallback(url: String): String {
        assetResolver.peekCached(url)?.let { return it }
        scope.launch { assetResolver.resolve(url) }
        return "vrchat"
    }

    private fun buildPresenceData(): JSONObject {
        val vrcPresence = VrchatPipelineState.presence
        val isOnline = vrcPresence?.isOnlineInVRChat == true

        if (isOnline && onlineStartEpochMs == 0L) {
            onlineStartEpochMs = System.currentTimeMillis()
        } else if (!isOnline) {
            onlineStartEpochMs = 0L
        }

        val activity = JSONObject().apply {
            put("name", "VRChat")
            put("type", 0)
            put("application_id", VRCHAT_APP_ID)

            if (isOnline && vrcPresence != null) {
                val statusText = when (vrcPresence.status) {
                    "ask me" -> "Ask Me"
                    "busy" -> "Do Not Disturb"
                    "join me" -> "Join Me"
                    else -> "Online"
                }

                val isDndOrAskMe = vrcPresence.status == "busy" ||
                    vrcPresence.status == "ask me"

                val showWorldDetails = !isDndOrAskMe &&
                    vrcPresence.worldName.isNotBlank() &&
                    vrcPresence.location != "private"

                if (showWorldDetails) {
                    put("details", vrcPresence.worldName)
                    val playerInfo = if (vrcPresence.instanceCapacity > 0)
                        "${vrcPresence.instancePlayerCount} of ${vrcPresence.instanceCapacity}"
                    else "${vrcPresence.instancePlayerCount} players"
                    put("state", "$statusText - $playerInfo")
                } else if (isDndOrAskMe) {
                    put("details", statusText)
                    put("state", "In VRChat")
                } else {
                    put("details", when {
                        vrcPresence.location == "private" -> "In a Private World"
                        vrcPresence.location == "traveling" -> "Traveling"
                        else -> "In VRChat"
                    })
                    put("state", statusText)
                }

                if (onlineStartEpochMs > 0) {
                    put("timestamps", JSONObject().apply {
                        put("start", onlineStartEpochMs)
                    })
                }

                put("assets", JSONObject().apply {
                    val largeImage = if (showWorldDetails && vrcPresence.worldImageUrl.isNotBlank()) {
                        resolveOrFallback(vrcPresence.worldImageUrl)
                    } else {
                        resolveOrFallback(DEFAULT_VRCHAT_IMAGE_URL)
                    }
                    put("large_image", largeImage)
                    put("large_text", if (showWorldDetails) vrcPresence.worldName else "VRChat")
                })
            } else {
                put("details", "Not in VRChat")
                put("state", "Using VRC-A")
                put("assets", JSONObject().apply {
                    put("large_image", resolveOrFallback(DEFAULT_VRCHAT_IMAGE_URL))
                    put("large_text", "VRChat")
                })
            }
        }

        return JSONObject().apply {
            put("since", JSONObject.NULL)
            put("activities", JSONArray().put(activity))
            put("status", if (isOnline) "online" else "idle")
            put("afk", false)
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        NotificationChannel(
            NOTIF_CHANNEL,
            "VRC-A Background",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Shows when VRC-A is running in the background"
            setShowBadge(false)
            nm.createNotificationChannel(this)
        }
    }

    private fun buildNotif(text: String): Notification {
        return Notification.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("VRC-A")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun updateNotif(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotif(text))
    }
}
