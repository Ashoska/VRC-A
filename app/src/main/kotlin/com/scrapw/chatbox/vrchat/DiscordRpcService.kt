package com.scrapw.chatbox.vrchat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
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
        private const val NOTIF_CHANNEL = "vrca_discord_rpc"
        private const val NOTIF_ID = 1002

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
    private var onlineStartMs = 0L
    private var token = ""

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
        startForeground(NOTIF_ID, buildNotif("Discord Rich Presence starting..."))
        scope.launch {
            val prefs = dataStore.data.first()
            token = prefs[androidx.datastore.preferences.core.stringPreferencesKey("discord_token")] ?: ""
            if (token.isBlank()) {
                Log.w(TAG, "No Discord token configured")
                updateNotif("No Discord token - configure in settings")
                return@launch
            }
            connect()
        }
        return START_NOT_STICKY
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
        ws?.close(1000, "Service stopping")
        ws = null
        isRunning = false
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
                    updateNotif("Connected to Discord")
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
                delay(15_000)
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

    private fun buildPresenceData(): JSONObject {
        val vrcPresence = VrchatPipelineState.presence
        val isOnline = vrcPresence?.isOnlineInVRChat == true

        if (isOnline && onlineStartMs == 0L) {
            onlineStartMs = System.currentTimeMillis()
        } else if (!isOnline) {
            onlineStartMs = 0L
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

                if (vrcPresence.worldName.isNotBlank()) {
                    put("details", vrcPresence.worldName)
                    val playerInfo = if (vrcPresence.instanceCapacity > 0)
                        "${vrcPresence.instancePlayerCount} of ${vrcPresence.instanceCapacity}"
                    else "${vrcPresence.instancePlayerCount} players"
                    put("state", "$statusText - $playerInfo")
                } else {
                    put("details", when (vrcPresence.location) {
                        "private" -> "In a Private World"
                        "traveling" -> "Traveling"
                        else -> statusText
                    })
                    put("state", when (vrcPresence.location) {
                        "private" -> statusText
                        "traveling" -> "Between Worlds"
                        else -> "In VRChat"
                    })
                }

                if (onlineStartMs > 0) {
                    put("timestamps", JSONObject().apply {
                        put("start", onlineStartMs / 1000)
                    })
                }

                put("assets", JSONObject().apply {
                    if (vrcPresence.currentAvatarThumbnailUrl.isNotBlank()) {
                        put("large_image", vrcPresence.currentAvatarThumbnailUrl)
                        put("large_text", vrcPresence.displayName)
                    } else {
                        put("large_image", "vrchat")
                        put("large_text", "VRChat")
                    }
                    val smallKey = when (vrcPresence.status) {
                        "ask me" -> "type-orange"
                        "busy" -> "type-red"
                        "join me" -> "type-blue"
                        else -> "type-green"
                    }
                    put("small_image", smallKey)
                    put("small_text", statusText)
                })
            } else {
                put("details", "Not in VRChat")
                put("state", "Using VRC-A")
                put("assets", JSONObject().apply {
                    put("large_image", "vrchat")
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
            "Discord Rich Presence",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Shows when Discord Rich Presence is active"
            setShowBadge(false)
            nm.createNotificationChannel(this)
        }
    }

    private fun buildNotif(text: String): Notification {
        return Notification.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("VRC-A Discord RPC")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun updateNotif(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotif(text))
    }
}
