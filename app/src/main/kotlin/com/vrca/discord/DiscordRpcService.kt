package com.vrca.discord

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
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import com.vrca.R
import com.vrca.vrchat.VrchatAuthManager
import com.vrca.vrchat.VrchatPipelineState
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

        const val ACTION_START = "com.vrca.DISCORD_RPC_START"
        const val ACTION_STOP = "com.vrca.DISCORD_RPC_STOP"

        private const val MAX_SHIM_RETRIES = 5
        private const val MAX_SESSION_RECOVERIES = 3
        private const val SHIM_RETRY_BASE_DELAY_MS = 3000L
        private const val PUSH_MIN_INTERVAL_MS = 1500L
        private const val PUSH_TIMER_INTERVAL_MS = 10_000L
        // If VRChat presence drops (crash, world-hop hiccup) or the whole app process
        // dies (reboot, OEM kill) and the user is back online within this window, the
        // Discord "elapsed" counter resumes from its original start instead of resetting.
        // 20 min (NOT 15) so it spans the WorkManager watchdog's ~15-min recovery gap:
        // an OEM kill is recovered by PipelineWatchdogWorker on its next cycle, and the
        // counter must still be inside the grace window when that happens so the timer
        // resumes instead of resetting. Applies to BOTH the in-VRChat and the
        // not-in-VRChat counters (shared via resolveElapsedStart).
        private const val ONLINE_GRACE_MS = 20L * 60 * 1000
        private const val SESSION_CHECK_INTERVAL_MS = 30_000L
        private const val MAX_CONSECUTIVE_PUSH_FAILURES = 5

        // WebView cache hygiene. The Discord gateway lives in a long-lived WebView
        // loading the full discord.com app. Chromium splits its storage in TWO
        // places: dataDir/app_webview (profile data — cookies, localStorage,
        // IndexedDB, service workers; counted as app DATA) and cacheDir/WebView
        // (the HTTP cache + compiled-JS Code Cache; counted as app CACHE — this
        // is what the user-visible "Cache" number in App Info / Settings shows,
        // and it's where the runaway growth actually lives). The original cap
        // measured ONLY app_webview, which stays small, so the cap never fired
        // while cacheDir/WebView ballooned past 140 MB — the "insane cache that
        // keeps growing" bug. The check now measures BOTH dirs combined.
        //
        // When the combined footprint crosses the cap we wipe DOM storage /
        // IndexedDB + the HTTP cache, best-effort delete what clearCache can't
        // reach (Chromium's Code Cache lives in cacheDir/WebView and is NOT
        // cleared by clearCache — and cacheDir is fair game, the OS itself may
        // wipe it anytime), and reload. The session survives because the auth
        // COOKIE (CookieManager) is left intact — Discord re-bootstraps the
        // localStorage token from it on reload; if that ever fails it lands on
        // /login and the existing onSessionExpired() recovery kicks in.
        //
        // The cap is deliberately ABOVE Discord's natural working set (~30-50 MB
        // of assets after one full load) — capping below it would clear+reload
        // every check, re-downloading the bundles each time (churn + data usage).
        // First check runs minutes after start (not a full hour) because OEM
        // service restarts used to reset the hourly timer so often that the
        // check could literally never run.
        private const val CACHE_FIRST_CHECK_DELAY_MS = 3L * 60 * 1000   // 3 min
        private const val CACHE_CHECK_INTERVAL_MS = 30L * 60 * 1000     // 30 min
        private const val WEBVIEW_CACHE_CAP_BYTES = 64L * 1024 * 1024   // 64 MB combined

        var isRunning = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var presenceWatchJob: Job? = null
    private var presenceTimerJob: Job? = null
    private var sessionMonitorJob: Job? = null
    private var cacheMaintenanceJob: Job? = null
    private var shimReady = false
    private var shimRetryCount = 0
    private var sessionRecoveryCount = 0
    private var consecutivePushFailures = 0
    // Consecutive session-monitor checks that reported a dead/zombie/bad gateway.
    // We require 2 in a row (~60s) before forcing a reload, so Discord's own client
    // gets a chance to RESUME on its own first.
    private var degradedHealthChecks = 0
    private var lastPushAttemptMs = 0L
    private var lastShimResult = ""

    private var onlineStartEpochMs = 0L
    private var offlineStartEpochMs = 0L
    private val rpcPrefs by lazy {
        applicationContext.getSharedPreferences("discord_rpc_state", Context.MODE_PRIVATE)
    }

    /**
     * Resolves the elapsed-counter start for a given state (in-VRChat or
     * not-in-VRChat) from persisted prefs, applying the [ONLINE_GRACE_MS]
     * grace window so the counter survives an in-process state blip AND a full
     * process death/reboot identically. [startKey] is the canonical start sent
     * as timestamps.start; [lastSeenKey] is refreshed every tick this state is
     * active and freezes the instant we stop ticking, so (now - lastSeen) is the
     * real away-time regardless of WHY we stopped. Within the grace window the
     * original start is kept (counter carries on); beyond it a fresh start is
     * recorded. Both use commit() so they survive a force-kill / SIGKILL that
     * lands moments later — an async apply() can be lost, which reads back as 0
     * and resets the counter on the next launch.
     */
    private fun resolveElapsedStart(startKey: String, lastSeenKey: String, nowMs: Long): Long {
        val savedStart = rpcPrefs.getLong(startKey, 0L)
        val lastSeen = rpcPrefs.getLong(lastSeenKey, 0L)
        // If lastSeen was lost (apply() not flushed before a kill), fall back to
        // savedStart — this state was definitely active at that time.
        val effectiveLastSeen = if (lastSeen > 0L) lastSeen
                                else if (savedStart > 0L) savedStart
                                else 0L
        val carriesOn = savedStart > 0L && effectiveLastSeen > 0L &&
            (nowMs - effectiveLastSeen) <= ONLINE_GRACE_MS
        val start = if (carriesOn) savedStart else {
            rpcPrefs.edit().putLong(startKey, nowMs).commit()
            nowMs
        }
        rpcPrefs.edit().putLong(lastSeenKey, nowMs).commit()
        return start
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
        // A null intent is a START_STICKY restart. If the user swiped the app away,
        // honour the deliberate kill so this WebView-backed service doesn't resurrect
        // itself and keep the app alive. Check both the 15s window and the persistent
        // swipe flag (a late restart after the window expired).
        if (intent == null &&
            (com.vrca.app.AppShutdown.isManualKillFresh(this) ||
                com.vrca.app.AppShutdown.isSwipedAway(this))) {
            teardown()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            // Kill the resurrected process so it doesn't linger in the
            // background; START_NOT_STICKY (returned below) prevents a re-restart.
            val appCtx = applicationContext
            Thread {
                try { Thread.sleep(300) } catch (_: Throwable) {}
                // Sweep the shared persistent notification (id 1001) in case the
                // stopForeground removal races the kill below.
                com.vrca.app.AppShutdown.cancelPersistentNotification(appCtx)
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(0)
            }.start()
            return START_NOT_STICKY
        }
        ensureChannel()

        if (isRunning && webView != null) {
            // Duplicate START while already running (app reopened after Back, or a
            // sticky restart). startForeground must still be called, but post the
            // CURRENT "active" text — not "starting..." — so the shared persistent
            // notification (NOTIF_ID 1001) isn't reset to a stale startup state.
            startForeground(NOTIF_ID, buildNotif("Discord RPC active"))
            return START_STICKY
        }
        startForeground(NOTIF_ID, buildNotif("Discord RPC starting..."))

        sessionRecoveryCount = 0
        // Start from 0; buildActivityJson() re-resolves the start from persisted
        // prefs on the first tick of each state, applying the grace window. Preloading
        // the persisted epoch directly here would ship a stale (e.g. day-old) start to
        // Discord as a bogus timer before the grace check ran.
        onlineStartEpochMs = 0L
        offlineStartEpochMs = 0L
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
        teardown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        // Coordinate the full process kill (no-op if another service already did).
        com.vrca.app.AppShutdown.onTaskSwiped(this)
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

            val wv = object : WebView(applicationContext) {
                // Chromium background-throttles (and eventually freezes) JS timers when
                // it thinks the page is hidden, which kills Discord's gateway heartbeat
                // ~45s after the screen turns off. A detached WebView is always "hidden".
                // Instead of parking it in a 1x1 system overlay (which forces Android's
                // "displaying over other apps" notification and looks sketchy), we simply
                // lie to the engine: always report the window as VISIBLE so timers keep
                // running at full rate. The foreground service + KeepAlive wakelock keep
                // the process itself alive.
                override fun onWindowVisibilityChanged(visibility: Int) {
                    super.onWindowVisibilityChanged(View.VISIBLE)
                }
            }.apply {
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
                            view?.evaluateJavascript(WS_HOOK_JS, null)
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
            // Keep JS timers running in the background (global to the process; never
            // call pauseTimers) and seed the visibility signal as VISIBLE so Chromium
            // doesn't background-throttle the Discord gateway heartbeat. No overlay
            // window is used, so there's no "displaying over other apps" notification.
            wv.resumeTimers()
            wv.dispatchWindowVisibilityChanged(View.VISIBLE)
            isRunning = true
            startCacheMaintenance()
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

    /** Recursive size of a directory in bytes (best-effort). */
    private fun dirSizeBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        val files = dir.listFiles() ?: return 0L
        for (f in files) {
            total += if (f.isDirectory) dirSizeBytes(f) else f.length()
        }
        return total
    }

    /**
     * WebView cache cap (see the constants block for the full story). Measures
     * the COMBINED Chromium footprint — `dataDir/app_webview` (profile data) +
     * `cacheDir/WebView` (HTTP cache + Code Cache, the user-visible "Cache"
     * growth) — and when it crosses [WEBVIEW_CACHE_CAP_BYTES]: wipes web storage
     * + the HTTP cache, best-effort deletes the leftover `cacheDir/WebView`
     * contents that `clearCache` doesn't reach (Code Cache), then reloads. The
     * Discord session survives because the auth cookie (CookieManager) is left
     * untouched and Discord re-bootstraps from it.
     */
    private fun startCacheMaintenance() {
        cacheMaintenanceJob?.cancel()
        cacheMaintenanceJob = scope.launch {
            delay(CACHE_FIRST_CHECK_DELAY_MS)
            while (true) {
                try {
                    val profileDir = File(applicationInfo.dataDir, "app_webview")
                    val cacheWebViewDir = File(cacheDir, "WebView")
                    val size = dirSizeBytes(profileDir) + dirSizeBytes(cacheWebViewDir)
                    if (size > WEBVIEW_CACHE_CAP_BYTES) {
                        Log.i(TAG, "WebView footprint ${size / (1024 * 1024)}MB over cap — clearing (cookie/session preserved)")
                        mainHandler.post {
                            try {
                                WebStorage.getInstance().deleteAllData()
                                webView?.clearCache(true)
                            } catch (e: Throwable) {
                                Log.w(TAG, "WebView cache clear failed", e)
                            }
                            // Code Cache (compiled JS) is NOT cleared by
                            // clearCache(true); delete it directly. Deleting
                            // from cacheDir while Chromium runs is safe — the
                            // OS may wipe cacheDir at any time and Chromium
                            // rebuilds missing cache entries.
                            scope.launch {
                                try {
                                    cacheWebViewDir.listFiles()?.forEach { it.deleteRecursively() }
                                } catch (e: Throwable) {
                                    Log.w(TAG, "WebView cacheDir sweep failed", e)
                                }
                                // Reload so Discord re-bootstraps its token from
                                // the preserved auth cookie; onSessionExpired()
                                // catches the rare case where it can't.
                                mainHandler.post {
                                    try {
                                        webView?.reload()
                                    } catch (_: Throwable) {
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Cache maintenance check failed", e)
                }
                delay(CACHE_CHECK_INTERVAL_MS)
            }
        }
    }

    private fun startSessionMonitor() {
        sessionMonitorJob?.cancel()
        degradedHealthChecks = 0
        sessionMonitorJob = scope.launch {
            while (true) {
                delay(SESSION_CHECK_INTERVAL_MS)
                if (!shimReady) continue

                mainHandler.post {
                    val wv = webView ?: return@post
                    wv.evaluateJavascript(
                        "(window.VRCA_gatewayHealth ? window.VRCA_gatewayHealth() : 'no_probe')"
                    ) { result ->
                        if (!shimReady) return@evaluateJavascript
                        when (result?.trim()?.replace("\"", "") ?: "null") {
                            "alive", "connecting", "no_probe" -> {
                                // Healthy (or socket still opening / probe not ready yet).
                                if (degradedHealthChecks != 0) degradedHealthChecks = 0
                                if (DiscordRpcState.status == DiscordRpcStatus.RECONNECTING) {
                                    DiscordRpcState.status = DiscordRpcStatus.CONNECTED
                                    DiscordRpcState.failureMessage = null
                                }
                            }
                            "no_gateway" -> {
                                // Shim lost the gateway reference entirely — re-inject
                                // (cheaper than a full reload, restores the hook).
                                degradedHealthChecks = 0
                                Log.w(TAG, "Session monitor: gateway reference lost — re-injecting shim")
                                shimReady = false
                                shimRetryCount = 0
                                DiscordRpcState.status = DiscordRpcStatus.RECONNECTING
                                DiscordRpcState.failureMessage = "Reconnecting to Discord..."
                                injectShim()
                            }
                            "dead", "zombie", "bad" -> {
                                // Socket open-but-broken (Discord outage / zombied
                                // session). Give Discord's own client one cycle to
                                // RESUME, then force a reload — which re-establishes a
                                // fresh gateway and auto-re-authenticates from the
                                // persistent token/cookie (no password, like a manual
                                // sign-out/in).
                                degradedHealthChecks++
                                DiscordRpcState.status = DiscordRpcStatus.RECONNECTING
                                DiscordRpcState.failureMessage = "Discord connection unstable — recovering..."
                                if (degradedHealthChecks >= 2) {
                                    degradedHealthChecks = 0
                                    Log.w(TAG, "Session monitor: gateway unhealthy — reloading to recover")
                                    onSessionExpired()
                                }
                            }
                            else -> {}
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
                val cleaned = result?.trim()?.replace("\"", "") ?: "null"
                if (cleaned == "ok" || cleaned == "resolving") {
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

        val nowMs = System.currentTimeMillis()
        // Two independent elapsed counters, each resolved from PERSISTED state on
        // EVERY tick of its state (see resolveElapsedStart):
        //   • in-VRChat   counter → online_start_epoch / last_online_seen
        //   • not-in-VRChat counter → offline_start_epoch / offline_last_seen
        // The two behave deliberately differently:
        //   - The in-VRChat timer CARRIES ON across a brief VRChat-offline blip
        //     (crash/world-hop) within the grace window — its prefs are never
        //     cleared when offline, only superseded after the grace expires.
        //   - The not-in-VRChat timer RESTARTS every time the user leaves VRChat,
        //     so it never bleeds into / inflates the real in-VRChat time. We force
        //     that by clearing its prefs while online; it still survives an app
        //     death *while offline* because this branch isn't running then, so the
        //     prefs persist and a reopen within the grace window resumes them.
        if (isOnline) {
            onlineStartEpochMs = resolveElapsedStart("online_start_epoch", "last_online_seen", nowMs)
            if (rpcPrefs.getLong("offline_start_epoch", 0L) != 0L ||
                rpcPrefs.getLong("offline_last_seen", 0L) != 0L) {
                rpcPrefs.edit()
                    .remove("offline_start_epoch")
                    .remove("offline_last_seen")
                    .commit()
            }
            offlineStartEpochMs = 0L
        } else {
            offlineStartEpochMs = resolveElapsedStart("offline_start_epoch", "offline_last_seen", nowMs)
            onlineStartEpochMs = 0L
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
                if (offlineStartEpochMs > 0) {
                    put("timestamps", JSONObject().apply {
                        put("start", offlineStartEpochMs)
                    })
                }
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
        cacheMaintenanceJob?.cancel()
        cacheMaintenanceJob = null
        shimReady = false
        isRunning = false
        // No bookkeeping needed here: online_start_epoch + last_online_seen are already
        // persisted on every online tick, so the grace-window carry-on works on the next
        // launch whether this teardown ran (swipe/stop) or the process was killed outright.
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
        val pipelineStatus = if (VrchatPipelineState.isConnected) {
            val name = try {
                VrchatAuthManager.getStoredDisplayName(applicationContext) ?: "VRChat"
            } catch (e: Exception) { "VRChat" }
            "Connected as $name"
        } else ""
        val combined = if (pipelineStatus.isNotEmpty()) "$pipelineStatus | $text" else text
        return Notification.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(R.drawable.ic_notif_sync)
            .setContentTitle("VRC-A")
            .setContentText(combined)
            .setOngoing(true)
            .setGroup("vrca_service")
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
    window._vrca_activity = null;
    window._vrca_user_status = 'online';
    window._vrca_intended_status = 'online';
    window._vrca_asset_cache = {};
    window._vrca_token = null;
    // Gateway health tracking. These let the native session monitor tell a
    // genuinely-working gateway from one that is OPEN at the socket level but
    // dead/zombied at the session level (Discord outage), which used to show a
    // false "connected". heartbeat_interval + last heartbeat-ACK come from
    // readable (uncompressed) inbound frames; lifecycle 'open'/'close' work
    // regardless of compression.
    window._vrca_hbInterval = 0;
    window._vrca_lastAckMs = 0;
    window._vrca_gatewayBad = false;
    window._vrca_sessionReady = false;

    // Attach passive observers to a captured gateway socket. We ONLY observe
    // (and re-push our own activity); we never alter Discord's own frames here,
    // so the "real web session" property is preserved. Idempotent per socket.
    window._vrca_attachGatewayListeners = function(ws) {
        if (!ws || ws._vrca_listened) return;
        ws._vrca_listened = true;
        try {
            ws.addEventListener('message', function(ev) {
                try {
                    if (typeof ev.data !== 'string') return; // compressed/binary: skip
                    var m = JSON.parse(ev.data);
                    if (m.op === 11) { window._vrca_lastAckMs = Date.now(); }
                    else if (m.op === 10) {
                        window._vrca_lastAckMs = Date.now();
                        if (m.d && m.d.heartbeat_interval) window._vrca_hbInterval = m.d.heartbeat_interval;
                    }
                    else if (m.op === 7 || m.op === 9) { window._vrca_gatewayBad = true; }
                    else if (m.op === 0 && (m.t === 'READY' || m.t === 'RESUMED')) {
                        window._vrca_sessionReady = true;
                        window._vrca_gatewayBad = false;
                        window._vrca_lastAckMs = Date.now();
                        setTimeout(function(){ try { window._vrca_sendPresence && window._vrca_sendPresence(); } catch(e){} }, 1500);
                    }
                } catch(e) {}
            });
            ws.addEventListener('close', function() { window._vrca_gatewayBad = true; });
            ws.addEventListener('open', function() {
                window._vrca_lastAckMs = Date.now();
                window._vrca_gatewayBad = false;
                // A freshly (re)opened gateway loses our activity from the prior
                // session. After IDENTIFY/READY settles, re-push it so the RPC
                // re-lands automatically. Compression-independent (lifecycle event),
                // so this is the primary auto-recovery for a Discord-initiated
                // reconnect even when inbound frames can't be parsed.
                setTimeout(function(){ try { window._vrca_sendPresence && window._vrca_sendPresence(); } catch(e){} }, 3000);
                setTimeout(function(){ try { window._vrca_sendPresence && window._vrca_sendPresence(); } catch(e){} }, 8000);
            });
        } catch(e) {}
    };

    var origSend = WebSocket.prototype.send;
    window._vrca_origSend = origSend;

    WebSocket.prototype.send = function(data) {
        if (this.url && this.url.indexOf('gateway') !== -1 &&
            this.url.indexOf('discord') !== -1) {
            if (!window._vrca_gatewayWs || window._vrca_gatewayWs.readyState !== 1) {
                window._vrca_gatewayWs = this;
                window._vrca_attachGatewayListeners(this);
            }
            if (typeof data === 'string') {
                try {
                    var parsed = JSON.parse(data);
                    if (parsed.op === 3 && parsed.d) {
                        var st = parsed.d.status;
                        if (st && st !== 'idle') window._vrca_intended_status = st;
                        if (st === 'idle' && window._vrca_intended_status === 'online') {
                            parsed.d.status = 'online';
                            parsed.d.since = 0;
                        }
                        window._vrca_user_status = parsed.d.status;
                        if (window._vrca_activity) {
                            if (!parsed.d.activities) parsed.d.activities = [];
                            parsed.d.activities = parsed.d.activities.filter(function(a) {
                                return a.application_id !== '438274841678872576';
                            });
                            parsed.d.activities.push(window._vrca_activity);
                            return origSend.call(this, JSON.stringify(parsed));
                        }
                    }
                } catch(e) {}
            }
        }
        return origSend.call(this, data);
    };

    var OrigWS = window.WebSocket;
    window.WebSocket = function(url, protocols) {
        var ws = protocols ? new OrigWS(url, protocols) : new OrigWS(url);
        if (url && url.indexOf('gateway') !== -1 && url.indexOf('discord') !== -1) {
            // A new gateway socket = a new session. Reset session-level health so a
            // stale "ready" from the prior socket can't mask a reconnect in progress.
            window._vrca_gatewayWs = ws;
            window._vrca_sessionReady = false;
            window._vrca_gatewayBad = false;
            window._vrca_lastAckMs = Date.now();
            window._vrca_attachGatewayListeners(ws);
        }
        return ws;
    };
    window.WebSocket.prototype = OrigWS.prototype;
    window.WebSocket.CONNECTING = OrigWS.CONNECTING;
    window.WebSocket.OPEN = OrigWS.OPEN;
    window.WebSocket.CLOSING = OrigWS.CLOSING;
    window.WebSocket.CLOSED = OrigWS.CLOSED;

    window._vrca_grabToken = function() {
        if (window._vrca_token) return;
        try {
            var t = localStorage.getItem('token');
            if (t) {
                try { window._vrca_token = JSON.parse(t); } catch(e2) { window._vrca_token = t; }
            }
        } catch(e) {}
    };
    window._vrca_grabToken();

    var origFetch = window.fetch;
    window.fetch = function(url, init) {
        if (!window._vrca_token && init && init.headers) {
            try {
                var auth;
                if (init.headers instanceof Headers) {
                    auth = init.headers.get('authorization');
                } else if (typeof init.headers === 'object') {
                    auth = init.headers['Authorization'] || init.headers['authorization'];
                }
                if (auth && typeof auth === 'string' && !auth.startsWith('Bot ')) {
                    window._vrca_token = auth;
                }
            } catch(e) {}
        }
        return origFetch.apply(this, arguments);
    };
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

        window.VRCA_resolveAsset = function(url) {
            if (!url) return Promise.resolve(null);
            var cached = window._vrca_asset_cache[url];
            if (cached) return Promise.resolve(cached);
            window._vrca_grabToken();
            var token = window._vrca_token;
            if (!token) return Promise.resolve(null);
            return fetch('/api/v9/applications/438274841678872576/external-assets', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', 'Authorization': token },
                body: JSON.stringify({ urls: [url] })
            }).then(function(r) { return r.json(); })
            .then(function(data) {
                if (Array.isArray(data) && data[0] && data[0].external_asset_path) {
                    var p = data[0].external_asset_path;
                    var resolved = p.indexOf('mp:') === 0 ? p : 'mp:' + p;
                    window._vrca_asset_cache[url] = resolved;
                    return resolved;
                }
                return null;
            }).catch(function() { return null; });
        };

        window._vrca_sendPresence = function() {
            var gw = window._vrca_gatewayWs;
            if (!gw || gw.readyState !== 1) return;
            var activity = window._vrca_activity;
            if (!activity) return;
            var origSend = window._vrca_origSend;
            if (!origSend) return;
            origSend.call(gw, JSON.stringify({
                op: 3,
                d: {
                    since: 0,
                    activities: [activity],
                    status: window._vrca_user_status || 'online',
                    afk: false
                }
            }));
        };

        window.VRCA_setActivity = function(jsonStr) {
            try {
                var gw = window._vrca_gatewayWs;
                if (!gw || gw.readyState !== 1) return 'no_gateway';
                var activity = JSON.parse(jsonStr);
                var imageUrl = activity.assets && activity.assets.large_image;
                if (imageUrl && imageUrl.indexOf('http') === 0) {
                    var cached = window._vrca_asset_cache[imageUrl];
                    if (cached) {
                        activity.assets.large_image = cached;
                        window._vrca_activity = activity;
                        window._vrca_sendPresence();
                        return 'ok';
                    }
                    window._vrca_activity = activity;
                    var retries = 0;
                    var tryResolve = function() {
                        VRCA_resolveAsset(imageUrl).then(function(resolved) {
                            if (resolved) {
                                activity.assets.large_image = resolved;
                                window._vrca_activity = activity;
                                window._vrca_sendPresence();
                            } else if (retries < 10) {
                                retries++;
                                setTimeout(tryResolve, 2000);
                            }
                        });
                    };
                    tryResolve();
                    return 'resolving';
                }
                window._vrca_activity = activity;
                window._vrca_sendPresence();
                return 'ok';
            } catch(e) {
                return 'err:' + e.message;
            }
        };

        window.VRCA_clearActivity = function() {
            try {
                window._vrca_activity = null;
                var gw = window._vrca_gatewayWs;
                if (!gw || gw.readyState !== 1) return 'no_gateway';
                var origSend = window._vrca_origSend;
                if (!origSend) return 'no_send';
                origSend.call(gw, JSON.stringify({
                    op: 3,
                    d: {
                        since: 0,
                        activities: [],
                        status: window._vrca_user_status || 'online',
                        afk: false
                    }
                }));
                return 'ok';
            } catch(e) {
                return 'err:' + e.message;
            }
        };

        // Health classification the native session monitor reads:
        //   no_gateway  - shim lost the gateway reference (re-inject shim)
        //   connecting  - socket still opening (wait)
        //   dead        - socket closing/closed (reload to recover)
        //   bad         - Discord sent Reconnect/Invalid-Session, or socket closed (reload)
        //   zombie      - open but no heartbeat ACK for >2.5x the interval (reload)
        //   alive       - healthy (or compressed inbound we can't inspect -> assume alive)
        window.VRCA_gatewayHealth = function() {
            try {
                var gw = window._vrca_gatewayWs;
                if (!gw) return 'no_gateway';
                var rs = gw.readyState;
                if (rs === 0) return 'connecting';
                if (rs === 2 || rs === 3) return 'dead';
                if (window._vrca_gatewayBad) return 'bad';
                var hb = window._vrca_hbInterval;
                var lastAck = window._vrca_lastAckMs;
                if (hb > 0 && lastAck > 0 && (Date.now() - lastAck) > hb * 2.5) return 'zombie';
                return 'alive';
            } catch(e) { return 'alive'; }
        };

        return 'ok';
    } catch(e) {
        return 'err:' + e.message;
    }
})();
"""
