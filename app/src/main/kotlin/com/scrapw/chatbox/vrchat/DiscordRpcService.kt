package com.scrapw.chatbox.vrchat

import android.annotation.SuppressLint
import android.app.Notification
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject

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

        var isRunning = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var presenceWatchJob: Job? = null
    private var presenceTimerJob: Job? = null
    private var shimReady = false
    private var shimFailCount = 0

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

        onlineStartEpochMs = rpcPrefs.getLong("online_start_epoch", 0L)

        val hasCookies = CookieManager.getInstance()
            .getCookie("https://discord.com")
            ?.isNotBlank() == true

        if (!hasCookies) {
            Log.w(TAG, "No Discord cookies — user must log in via WebView first")
            updateNotif("Not signed into Discord — sign in from settings")
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
            if (webView != null) return@post
            val wv = WebView(applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.userAgentString =
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        val u = url ?: ""
                        if (u.contains("discord.com/channels") || u.contains("discord.com/app")) {
                            injectShimDelayed()
                        } else if (u.contains("discord.com/login")) {
                            Log.w(TAG, "Discord session expired — landed on login page")
                            updateNotif("Discord session expired — sign in again from settings")
                        }
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
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

    private fun injectShimDelayed() {
        mainHandler.postDelayed({
            injectShim()
        }, 3000)
    }

    private fun injectShim() {
        val wv = webView ?: return
        wv.evaluateJavascript(MODULE_FINDER_JS) { result ->
            val ok = result?.trim()?.replace("\"", "") == "ok"
            if (ok) {
                Log.i(TAG, "JS shim injected — module finder succeeded")
                shimReady = true
                shimFailCount = 0
                updateNotif("Discord RPC active")
                startPresenceUpdates()
            } else {
                shimFailCount++
                Log.w(TAG, "JS shim injection failed (attempt $shimFailCount): $result")
                if (shimFailCount < 5) {
                    mainHandler.postDelayed({ injectShim() }, 5000)
                } else {
                    updateNotif("Discord RPC: module finder failed — needs update")
                }
            }
        }
    }

    private fun startPresenceUpdates() {
        presenceWatchJob?.cancel()
        presenceTimerJob?.cancel()

        var lastPushMs = 0L

        presenceWatchJob = scope.launch {
            VrchatPipelineState.presenceFlow.collectLatest {
                val elapsed = System.currentTimeMillis() - lastPushMs
                if (elapsed < 1500) delay(1500 - elapsed)
                pushActivity()
                lastPushMs = System.currentTimeMillis()
            }
        }

        presenceTimerJob = scope.launch {
            while (true) {
                delay(10_000)
                pushActivity()
                lastPushMs = System.currentTimeMillis()
            }
        }
    }

    private fun pushActivity() {
        if (!shimReady) return
        val wv = webView ?: return
        val activity = buildActivityJson()
        val escaped = activity.toString()
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        mainHandler.post {
            wv.evaluateJavascript("window.VRCA_setActivity('$escaped')") { result ->
                val ok = result?.trim()?.replace("\"", "") == "ok"
                if (!ok) {
                    Log.w(TAG, "pushActivity failed: $result")
                    shimReady = false
                    shimFailCount = 0
                    injectShim()
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
        presenceWatchJob?.cancel()
        presenceTimerJob?.cancel()
        shimReady = false
        isRunning = false
        onlineStartEpochMs = 0L
        rpcPrefs.edit().putLong("online_start_epoch", 0L).apply()
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

/**
 * JavaScript injected into the Discord WebView after page load.
 * Locates Discord's internal Flux dispatcher and activity action types
 * via webpack module scanning, then exposes bridge functions.
 *
 * Returns "ok" if the dispatcher and action types were found.
 *
 * window.VRCA_setActivity(jsonString) — dispatches LOCAL_ACTIVITY_UPDATE
 * window.VRCA_clearActivity() — dispatches with null activity
 */
private const val MODULE_FINDER_JS = """
(function() {
    try {
        if (window._vrca_dispatcher) return 'ok';

        var chunks = window.webpackChunkdiscord_app;
        if (!chunks || !chunks.length) return 'no_webpack';

        var modules = {};
        var fakeReq = function(id) { return modules[id] || {}; };
        fakeReq.c = modules;
        fakeReq.m = {};
        fakeReq.d = function(t, k, g) {
            if (!t.hasOwnProperty(k)) {
                Object.defineProperty(t, k, { enumerable: true, get: g });
            }
        };
        fakeReq.r = function(t) {};
        fakeReq.n = function(m) { return function() { return m; }; };

        for (var i = 0; i < chunks.length; i++) {
            var chunk = chunks[i];
            if (!chunk || !chunk[1]) continue;
            var mods = chunk[1];
            var keys = Object.keys(mods);
            for (var j = 0; j < keys.length; j++) {
                var key = keys[j];
                if (modules[key]) continue;
                var m = { exports: {} };
                try {
                    mods[key](m, m.exports, fakeReq);
                    modules[key] = m.exports;
                } catch(e) {}
            }
        }

        var dispatcher = null;
        var mkeys = Object.keys(modules);
        for (var k = 0; k < mkeys.length; k++) {
            var exp = modules[mkeys[k]];
            if (!exp) continue;
            var target = exp.default || exp;
            if (target && typeof target.dispatch === 'function' &&
                typeof target.subscribe === 'function' &&
                typeof target._actionHandlers !== 'undefined') {
                dispatcher = target;
                break;
            }
            if (!dispatcher && target && typeof target.dispatch === 'function' &&
                typeof target.subscribe === 'function' &&
                typeof target.wait === 'function') {
                dispatcher = target;
            }
        }

        if (!dispatcher) return 'no_dispatcher';
        window._vrca_dispatcher = dispatcher;

        window.VRCA_setActivity = function(jsonStr) {
            try {
                var activity = JSON.parse(jsonStr);
                dispatcher.dispatch({
                    type: 'LOCAL_ACTIVITY_UPDATE',
                    activity: activity,
                    socketId: 'vrca',
                    pid: 0
                });
                return 'ok';
            } catch(e) {
                return 'err:' + e.message;
            }
        };

        window.VRCA_clearActivity = function() {
            try {
                dispatcher.dispatch({
                    type: 'LOCAL_ACTIVITY_UPDATE',
                    activity: null,
                    socketId: 'vrca',
                    pid: 0
                });
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
