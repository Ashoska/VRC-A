package com.scrapw.chatbox.vrchat

import android.annotation.SuppressLint
import android.app.Notification
import android.graphics.Bitmap
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.scrapw.chatbox.R
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
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class DiscordRpcStatus {
    IDLE,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    SESSION_EXPIRED,
    FAILED
}

object DiscordRpcState {
    private val _status = MutableStateFlow(DiscordRpcStatus.IDLE)
    val statusFlow: StateFlow<DiscordRpcStatus> = _status.asStateFlow()
    var status: DiscordRpcStatus
        get() = _status.value
        set(value) { _status.value = value }

    private val _failureMessage = MutableStateFlow<String?>(null)
    val failureMessageFlow: StateFlow<String?> = _failureMessage.asStateFlow()
    var failureMessage: String?
        get() = _failureMessage.value
        set(value) { _failureMessage.value = value }

    fun reset() {
        status = DiscordRpcStatus.IDLE
        failureMessage = null
    }
}

class DiscordRpcService : Service() {

    companion object {
        private const val TAG = "DiscordRPC"
        private const val VRCHAT_APP_ID = "438274841678872576"
        private const val NOTIF_CHANNEL = "vrca_pipeline"
        private const val NOTIF_ID = 1001

        private const val DEFAULT_VRCHAT_IMAGE_URL =
            "https://raw.githubusercontent.com/shadowash321rulse-lab/VRChat-rpc-display/main/vrchat-1102x620.jpg"

        const val ACTION_START = "com.scrapw.chatbox.DISCORD_RPC_START"
        const val ACTION_STOP = "com.scrapw.chatbox.DISCORD_RPC_STOP"

        private const val MAX_SHIM_RETRIES = 5
        private const val MAX_SESSION_RECOVERIES = 3
        private const val SHIM_RETRY_BASE_DELAY_MS = 3000L
        private const val PUSH_MIN_INTERVAL_MS = 1500L
        private const val PUSH_TIMER_INTERVAL_MS = 10_000L
        private const val SESSION_CHECK_INTERVAL_MS = 30_000L
        private const val MAX_CONSECUTIVE_PUSH_FAILURES = 5

        var isRunning = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var presenceWatchJob: Job? = null
    private var presenceTimerJob: Job? = null
    private var sessionMonitorJob: Job? = null
    private var shimReady = false
    private var shimRetryCount = 0
    private var sessionRecoveryCount = 0
    private var consecutivePushFailures = 0
    private var lastPushAttemptMs = 0L
    private var lastShimResult = ""

    private var onlineStartEpochMs = 0L
    private val rpcPrefs by lazy {
        applicationContext.getSharedPreferences("discord_rpc_state", Context.MODE_PRIVATE)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                teardown()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        ensureChannel()
        startForeground(NOTIF_ID, buildNotif("Discord RPC starting..."))

        if (isRunning && webView != null) {
            return START_STICKY
        }

        sessionRecoveryCount = 0
        onlineStartEpochMs = rpcPrefs.getLong("online_start_epoch", 0L)
        DiscordRpcState.status = DiscordRpcStatus.CONNECTING
        DiscordRpcState.failureMessage = null

        val hasCookies = CookieManager.getInstance()
            .getCookie("https://discord.com")
            ?.isNotBlank() == true

        if (!hasCookies) {
            Log.w(TAG, "No Discord cookies — user must log in via WebView first")
            updateNotif("Not signed into Discord — sign in from settings")
            DiscordRpcState.status = DiscordRpcStatus.SESSION_EXPIRED
            DiscordRpcState.failureMessage = "Not signed in — open settings to sign in"
            return START_STICKY
        }

        loadWebView()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        clearActivity()
        mainHandler.postDelayed({
            teardown()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }, 1500)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        isRunning = false
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadWebView() {
        mainHandler.post {
            webView?.let { old ->
                old.stopLoading()
                old.loadUrl("about:blank")
                old.destroy()
                webView = null
            }

            shimReady = false
            shimRetryCount = 0
            consecutivePushFailures = 0

            val wv = WebView(applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.userAgentString =
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        val u = url ?: ""
                        if (u.contains("discord.com")) {
                            view?.evaluateJavascript(WS_HOOK_JS, null)
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        val u = url ?: ""
                        if (u.contains("discord.com/channels") || u.contains("discord.com/app")) {
                            injectShimDelayed()
                        } else if (u.contains("discord.com/login")) {
                            Log.w(TAG, "Discord session expired — landed on login page")
                            onSessionExpired()
                        }
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url?.toString() ?: return false
                        if (url.contains("discord.com/login")) {
                            Log.w(TAG, "Redirect to login detected — session expired")
                            onSessionExpired()
                        }
                        return false
                    }
                }

                loadUrl("https://discord.com/channels/@me")
            }
            webView = wv
            isRunning = true
            updateNotif("Discord RPC connecting...")
        }
    }

    private fun onSessionExpired() {
        shimReady = false
        stopPresenceUpdates()

        if (sessionRecoveryCount < MAX_SESSION_RECOVERIES) {
            sessionRecoveryCount++
            Log.i(TAG, "Session expired — attempting recovery ($sessionRecoveryCount/$MAX_SESSION_RECOVERIES)")
            DiscordRpcState.status = DiscordRpcStatus.RECONNECTING
            DiscordRpcState.failureMessage = "Session expired — reconnecting ($sessionRecoveryCount/$MAX_SESSION_RECOVERIES)"
            updateNotif("Discord session expired — reconnecting...")

            scope.launch {
                delay(2000L * sessionRecoveryCount)
                mainHandler.post {
                    webView?.let { wv ->
                        CookieManager.getInstance().flush()
                        wv.loadUrl("https://discord.com/channels/@me")
                    }
                }
            }
        } else {
            Log.w(TAG, "Session expired — max recovery attempts reached")
            DiscordRpcState.status = DiscordRpcStatus.SESSION_EXPIRED
            DiscordRpcState.failureMessage = "Discord session expired — sign in again from settings"
            updateNotif("Discord session expired — sign in again from settings")
        }
    }

    private fun injectShimDelayed() {
        mainHandler.postDelayed({
            injectShim()
        }, 5000)
    }

    private fun injectShim() {
        if (shimRetryCount >= MAX_SHIM_RETRIES) {
            Log.w(TAG, "Shim injection failed after $MAX_SHIM_RETRIES attempts — last: $lastShimResult")
            DiscordRpcState.status = DiscordRpcStatus.FAILED
            DiscordRpcState.failureMessage = "Discord setup failed: $lastShimResult"
            updateNotif("Discord RPC: connection setup failed — needs update")
            return
        }

        val wv = webView ?: return
        wv.evaluateJavascript(MODULE_FINDER_JS) { result ->
            val cleaned = result?.trim()?.replace("\"", "") ?: "null"
            lastShimResult = cleaned
            if (cleaned == "ok") {
                Log.i(TAG, "JS shim injected — module finder succeeded")
                shimReady = true
                shimRetryCount = 0
                consecutivePushFailures = 0
                sessionRecoveryCount = 0
                DiscordRpcState.status = DiscordRpcStatus.CONNECTED
                DiscordRpcState.failureMessage = null
                updateNotif("Discord RPC active")
                startPresenceUpdates()
                startSessionMonitor()
            } else {
                shimRetryCount++
                Log.w(TAG, "JS shim injection failed (attempt $shimRetryCount/$MAX_SHIM_RETRIES): $cleaned")
                DiscordRpcState.status = DiscordRpcStatus.RECONNECTING
                DiscordRpcState.failureMessage = "Setting up Discord connection... ($shimRetryCount/$MAX_SHIM_RETRIES)"
                val backoffMs = SHIM_RETRY_BASE_DELAY_MS * shimRetryCount
                mainHandler.postDelayed({ injectShim() }, backoffMs)
            }
        }
    }

    private fun startPresenceUpdates() {
        stopPresenceUpdates()

        presenceWatchJob = scope.launch {
            VrchatPipelineState.presenceFlow.collectLatest {
                val now = System.currentTimeMillis()
                val elapsed = now - lastPushAttemptMs
                if (elapsed < PUSH_MIN_INTERVAL_MS) delay(PUSH_MIN_INTERVAL_MS - elapsed)
                pushActivity()
            }
        }

        presenceTimerJob = scope.launch {
            while (true) {
                delay(PUSH_TIMER_INTERVAL_MS)
                pushActivity()
            }
        }
    }

    private fun stopPresenceUpdates() {
        presenceWatchJob?.cancel()
        presenceTimerJob?.cancel()
        presenceWatchJob = null
        presenceTimerJob = null
    }

    private fun startSessionMonitor() {
        sessionMonitorJob?.cancel()
        sessionMonitorJob = scope.launch {
            while (true) {
                delay(SESSION_CHECK_INTERVAL_MS)
                if (!shimReady) continue

                mainHandler.post {
                    val wv = webView ?: return@post
                    wv.evaluateJavascript(
                        "(function(){ var ws = window._vrca_gatewayWs; return ws && ws.readyState === 1 ? 'alive' : 'dead'; })()"
                    ) { result ->
                        val alive = result?.trim()?.replace("\"", "") == "alive"
                        if (!alive && shimReady) {
                            Log.w(TAG, "Session monitor: dispatcher reference lost — re-injecting shim")
                            shimReady = false
                            shimRetryCount = 0
                            DiscordRpcState.status = DiscordRpcStatus.RECONNECTING
                            DiscordRpcState.failureMessage = "Reconnecting to Discord..."
                            injectShim()
                        }
                    }
                }
            }
        }
    }

    private fun pushActivity() {
        if (!shimReady) return
        val wv = webView ?: return

        val now = System.currentTimeMillis()
        if (now - lastPushAttemptMs < PUSH_MIN_INTERVAL_MS) return
        lastPushAttemptMs = now

        val activity = buildActivityJson()
        val escaped = activity.toString()
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        mainHandler.post {
            wv.evaluateJavascript("window.VRCA_setActivity('$escaped')") { result ->
                val ok = result?.trim()?.replace("\"", "") == "ok"
                if (ok) {
                    consecutivePushFailures = 0
                } else {
                    consecutivePushFailures++
                    Log.w(TAG, "pushActivity failed ($consecutivePushFailures/$MAX_CONSECUTIVE_PUSH_FAILURES): $result")
                    if (consecutivePushFailures >= MAX_CONSECUTIVE_PUSH_FAILURES) {
                        Log.w(TAG, "Too many consecutive push failures — re-injecting shim")
                        shimReady = false
                        shimRetryCount = 0
                        consecutivePushFailures = 0
                        DiscordRpcState.status = DiscordRpcStatus.RECONNECTING
                        DiscordRpcState.failureMessage = "Activity push failing — reconnecting"
                        injectShim()
                    }
                }
            }
        }
    }

    private fun clearActivity() {
        if (!shimReady) return
        val wv = webView ?: return
        mainHandler.post {
            wv.evaluateJavascript("window.VRCA_clearActivity()") {}
        }
    }

    private fun squashToSquareUrl(url: String): String {
        if (url.isBlank() || url == DEFAULT_VRCHAT_IMAGE_URL) return url
        val encoded = java.net.URLEncoder.encode(url, "UTF-8")
        return "https://images.weserv.nl/?url=$encoded&w=512&h=512&fit=fill"
    }

    private fun buildActivityJson(): JSONObject {
        val vrcPresence = VrchatPipelineState.presence
        val isOnline = vrcPresence?.isOnlineInVRChat == true

        if (isOnline && onlineStartEpochMs == 0L) {
            onlineStartEpochMs = System.currentTimeMillis()
            rpcPrefs.edit().putLong("online_start_epoch", onlineStartEpochMs).apply()
        } else if (!isOnline && onlineStartEpochMs != 0L) {
            onlineStartEpochMs = 0L
            rpcPrefs.edit().putLong("online_start_epoch", 0L).apply()
        }

        return JSONObject().apply {
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
                    if (showWorldDetails && vrcPresence.worldImageUrl.isNotBlank()) {
                        put("large_image", squashToSquareUrl(vrcPresence.worldImageUrl))
                    } else {
                        put("large_image", DEFAULT_VRCHAT_IMAGE_URL)
                    }
                    put("large_text", if (showWorldDetails) vrcPresence.worldName else "VRChat")
                })
            } else {
                put("details", "Not in VRChat")
                put("state", "Using VRC-A")
                put("assets", JSONObject().apply {
                    put("large_image", DEFAULT_VRCHAT_IMAGE_URL)
                    put("large_text", "VRChat")
                })
            }
        }
    }

    private fun teardown() {
        stopPresenceUpdates()
        sessionMonitorJob?.cancel()
        sessionMonitorJob = null
        shimReady = false
        isRunning = false
        onlineStartEpochMs = 0L
        rpcPrefs.edit().putLong("online_start_epoch", 0L).apply()
        DiscordRpcState.reset()
        mainHandler.post {
            webView?.let { wv ->
                wv.evaluateJavascript("window.VRCA_clearActivity()") {}
                mainHandler.postDelayed({
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                    wv.destroy()
                    webView = null
                }, 1500)
            }
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

private const val WS_HOOK_JS = """
(function() {
    if (window._vrca_ws_hooked) return;
    window._vrca_ws_hooked = true;
    window._vrca_gatewayWs = null;
    var OrigWS = window.WebSocket;
    window.WebSocket = function(url, protocols) {
        var ws = protocols ? new OrigWS(url, protocols) : new OrigWS(url);
        if (url && (url.indexOf('gateway.discord.gg') !== -1 || url.indexOf('gateway-us-east') !== -1 || url.indexOf('discord.gg/?') !== -1)) {
            window._vrca_gatewayWs = ws;
        }
        return ws;
    };
    window.WebSocket.prototype = OrigWS.prototype;
    window.WebSocket.CONNECTING = OrigWS.CONNECTING;
    window.WebSocket.OPEN = OrigWS.OPEN;
    window.WebSocket.CLOSING = OrigWS.CLOSING;
    window.WebSocket.CLOSED = OrigWS.CLOSED;
})();
"""

private const val MODULE_FINDER_JS = """
(function() {
    try {
        if (window.VRCA_setActivity) return 'ok';

        var ws = window._vrca_gatewayWs;
        if (!ws) return 'no_gateway(hook=' + !!window._vrca_ws_hooked + ')';
        if (ws.readyState !== 1) return 'gateway_not_open(state=' + ws.readyState + ')';

        window._vrca_dispatcher = true;

        window.VRCA_setActivity = function(jsonStr) {
            try {
                var gw = window._vrca_gatewayWs;
                if (!gw || gw.readyState !== 1) return 'no_gateway';
                var activity = JSON.parse(jsonStr);
                gw.send(JSON.stringify({
                    op: 3,
                    d: {
                        since: 0,
                        activities: [activity],
                        status: 'online',
                        afk: false
                    }
                }));
                return 'ok';
            } catch(e) {
                return 'err:' + e.message;
            }
        };

        window.VRCA_clearActivity = function() {
            try {
                var gw = window._vrca_gatewayWs;
                if (!gw || gw.readyState !== 1) return 'no_gateway';
                gw.send(JSON.stringify({
                    op: 3,
                    d: {
                        since: 0,
                        activities: [],
                        status: 'online',
                        afk: false
                    }
                }));
                return 'ok';
            } catch(e) {
                return 'err:' + e.message;
            }
        };

        return 'ok';
    } catch(e) {
        return 'err:' + e.message;
    }
})();
"""
