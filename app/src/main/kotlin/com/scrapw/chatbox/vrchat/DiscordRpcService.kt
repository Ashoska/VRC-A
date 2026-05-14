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
                        "(function(){ return window._vrca_dispatcher && window._vrca_dispatchMethod ? 'alive' : 'dead'; })()"
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

private const val MODULE_FINDER_JS = """
(function() {
    try {
        if (window._vrca_dispatcher) return 'ok';

        var wpChunks = null;
        var wpName = '';
        var candidates = Object.getOwnPropertyNames(window);
        for (var i = 0; i < candidates.length; i++) {
            var name = candidates[i];
            if (name.indexOf('webpackChunk') === 0) {
                var val = window[name];
                if (val && typeof val.push === 'function' && val.length > 0) {
                    wpChunks = val;
                    wpName = name;
                    break;
                }
            }
        }
        if (!wpChunks) return 'no_webpack(scanned=' + candidates.length + ')';

        var realRequire = null;
        try {
            wpChunks.push([[Symbol()], {}, function(req) { realRequire = req; }]);
            wpChunks.pop();
        } catch(e) {}
        if (!realRequire) return 'no_require';

        // Phase 1: Search module factory source code for key strings.
        // String literals survive minification even when method names don't.
        var factoryKeys = Object.keys(realRequire.m || {});
        var activityModuleIds = [];
        var fluxModuleIds = [];
        var dispatcherModuleIds = [];

        for (var fi = 0; fi < factoryKeys.length; fi++) {
            try {
                var src = realRequire.m[factoryKeys[fi]].toString();
                if (src.indexOf('LOCAL_ACTIVITY_UPDATE') !== -1) {
                    activityModuleIds.push(factoryKeys[fi]);
                }
                if (src.indexOf('FluxDispatcher') !== -1 || src.indexOf('fluxDispatcher') !== -1) {
                    fluxModuleIds.push(factoryKeys[fi]);
                }
                if (src.indexOf('actionHandler') !== -1 && src.indexOf('dispatch') !== -1) {
                    dispatcherModuleIds.push(factoryKeys[fi]);
                }
            } catch(e) {}
        }

        // Phase 2: Load the identified modules and find the dispatcher.
        // The FluxDispatcher module likely exports the dispatcher instance directly.
        var dispatcher = null;

        function findDispatcherInModule(mod) {
            if (!mod) return null;
            var targets = [mod, mod.default, mod.Z, mod.ZP];
            try {
                var ek = Object.keys(mod);
                for (var ei = 0; ei < ek.length && ei < 50; ei++) {
                    try { if (mod[ek[ei]]) targets.push(mod[ek[ei]]); } catch(e) {}
                }
            } catch(e) {}
            for (var ti = 0; ti < targets.length; ti++) {
                try {
                    var t = targets[ti];
                    if (!t || typeof t !== 'object') continue;
                    // Look for any function that could be dispatch (minified name).
                    // A Flux dispatcher typically has 5+ methods and some internal state.
                    var funcs = [];
                    var allProps = [];
                    try {
                        allProps = Object.getOwnPropertyNames(t);
                    } catch(e) {
                        try { allProps = Object.keys(t); } catch(e2) {}
                    }
                    // Also check prototype
                    try {
                        var proto = Object.getPrototypeOf(t);
                        if (proto && proto !== Object.prototype) {
                            var protoProps = Object.getOwnPropertyNames(proto);
                            for (var pp = 0; pp < protoProps.length; pp++) {
                                if (allProps.indexOf(protoProps[pp]) === -1) allProps.push(protoProps[pp]);
                            }
                        }
                    } catch(e) {}
                    for (var pi = 0; pi < allProps.length; pi++) {
                        try {
                            if (typeof t[allProps[pi]] === 'function') funcs.push(allProps[pi]);
                        } catch(e) {}
                    }
                    // Dispatcher: many methods (5+), has internal state
                    if (funcs.length >= 5) {
                        // Try to find 'dispatch' by checking each function's behavior:
                        // call it with a test action and see if it doesn't throw
                        // OR check constructor name
                        try {
                            var cname = t.constructor && t.constructor.name;
                            if (cname && (cname.indexOf('Dispatcher') !== -1 || cname.indexOf('Flux') !== -1)) {
                                return t;
                            }
                        } catch(e) {}
                        // Check if any method source mentions actionHandler
                        for (var fni = 0; fni < funcs.length; fni++) {
                            try {
                                var fsrc = t[funcs[fni]].toString();
                                if (fsrc.indexOf('actionHandler') !== -1 || fsrc.indexOf('_dispatch') !== -1) {
                                    return t;
                                }
                            } catch(e) {}
                        }
                    }
                } catch(e) {}
            }
            return null;
        }

        // Try FluxDispatcher modules first (most likely)
        var searchOrder = fluxModuleIds.concat(dispatcherModuleIds).concat(activityModuleIds);
        var seen = {};
        for (var si = 0; si < searchOrder.length; si++) {
            var mid = searchOrder[si];
            if (seen[mid]) continue;
            seen[mid] = true;
            try {
                var mod = realRequire(mid);
                dispatcher = findDispatcherInModule(mod);
                if (dispatcher) break;
            } catch(e) {}
        }

        // Phase 3: If still not found, scan ALL cached modules for constructor name
        if (!dispatcher && realRequire.c) {
            var ckeys = Object.keys(realRequire.c);
            for (var ck = 0; ck < ckeys.length; ck++) {
                try {
                    var entry = realRequire.c[ckeys[ck]];
                    var exp = entry && (entry.exports || entry);
                    if (!exp) continue;
                    var etgts = [exp, exp.default, exp.Z, exp.ZP];
                    for (var et = 0; et < etgts.length; et++) {
                        try {
                            var tg = etgts[et];
                            if (!tg || typeof tg !== 'object') continue;
                            var cn = tg.constructor && tg.constructor.name;
                            if (cn && (cn.indexOf('Dispatcher') !== -1 || cn.indexOf('Flux') !== -1)) {
                                dispatcher = tg;
                                break;
                            }
                        } catch(e) {}
                    }
                    if (dispatcher) break;
                } catch(e) {}
            }
        }

        if (!dispatcher) {
            return 'no_dispatcher(factories=' + factoryKeys.length +
                ',flux=' + fluxModuleIds.length +
                ',actMods=' + activityModuleIds.length +
                ',dispMods=' + dispatcherModuleIds.length + ')';
        }

        window._vrca_dispatcher = dispatcher;

        // Find the actual dispatch method name (it's minified).
        // Look for a method whose source code references actionHandler or _dispatch.
        var dispatchMethodName = null;
        var allMethods = [];
        try {
            var ap = Object.getOwnPropertyNames(dispatcher);
            var proto = Object.getPrototypeOf(dispatcher);
            if (proto && proto !== Object.prototype) {
                var pp = Object.getOwnPropertyNames(proto);
                for (var ppi = 0; ppi < pp.length; ppi++) {
                    if (ap.indexOf(pp[ppi]) === -1) ap.push(pp[ppi]);
                }
            }
            for (var ai = 0; ai < ap.length; ai++) {
                try {
                    if (typeof dispatcher[ap[ai]] === 'function') {
                        allMethods.push(ap[ai]);
                        var msrc = dispatcher[ap[ai]].toString();
                        if (msrc.indexOf('actionHandler') !== -1 || msrc.indexOf('.type') !== -1) {
                            if (!dispatchMethodName) dispatchMethodName = ap[ai];
                        }
                    }
                } catch(e) {}
            }
        } catch(e) {}

        // Fallback: try 'dispatch' in case it exists but wasn't enumerable
        if (!dispatchMethodName && typeof dispatcher.dispatch === 'function') {
            dispatchMethodName = 'dispatch';
        }

        if (!dispatchMethodName) {
            return 'found_dispatcher_no_dispatch(methods=[' + allMethods.join(',') + '])';
        }

        window._vrca_dispatchMethod = dispatchMethodName;

        window.VRCA_setActivity = function(jsonStr) {
            try {
                var activity = JSON.parse(jsonStr);
                dispatcher[dispatchMethodName]({
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
                dispatcher[dispatchMethodName]({
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
