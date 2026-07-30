package com.vrca.discord

import android.annotation.SuppressLint
import com.vrca.app.startForegroundSafely
import android.app.Notification
import android.graphics.Bitmap
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
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
            "https://raw.githubusercontent.com/Ashoska/VRC-A-Image-store/main/vrchat-1102x620.jpg"

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
        // The not-in-VRChat counter's persisted start is only cleared once the
        // online session has been sustained at least this long. This stops a
        // transient presence flicker to "online" (a stale REST read / racy WS
        // event on a flaky connection) from wiping the offline counter and
        // restarting it from 0 — a genuine visit still clears it after ~3 min.
        private const val OFFLINE_CLEAR_CONFIRM_MS = 3L * 60 * 1000
        // A raw "in VRChat" presence must hold this long before the RPC acts on it.
        // Right after an app restart / UPDATE the app can briefly report a STALE
        // "in a world" (a persisted/replayed presence) for a second before the true
        // state lands — that 1s blip would flip the RPC online and disturb the
        // not-in-VRChat counter. Requiring the online state to persist ignores the
        // blip entirely (display AND counter). Not update-specific — same guard for
        // an OEM-kill revival / reboot / any restart with a stale first presence.
        private const val ONLINE_FLICKER_DEBOUNCE_MS = 10_000L
        private const val SESSION_CHECK_INTERVAL_MS = 30_000L
        private const val MAX_CONSECUTIVE_PUSH_FAILURES = 5
        // How many CONSECUTIVE 30s health checks a state must persist before we do a
        // disruptive full WebView reload (onSessionExpired) — a reload drops the RPC
        // for its reload+re-inject window, so we only pay it when the signal is real.
        // `dead` is readyState CLOSING/CLOSED: compression-independent and reliable,
        // so 2 checks (~60s, one cycle of RESUME grace) is enough. `zombie`/`bad` are
        // derived from heartbeat-ACK / OP7/OP9 tracking that needs READABLE inbound
        // frames — under Discord's zlib-stream compression those frames are binary and
        // unparseable, so the probe can FALSE-positive; require more confirmations
        // (~2 min) before reloading on them. A socket that's genuinely closed will
        // degrade to `dead` and take the faster path anyway. `no_gateway` (shim lost
        // the gateway ref) is usually transient — re-inject first, only reload if it
        // persists. This replaces the prior flat "reload after 2 checks for everything"
        // that turned transient blips into frequent, visible RPC drops.
        private const val DEAD_RELOAD_THRESHOLD = 2
        private const val DEGRADED_RELOAD_THRESHOLD = 4
        private const val NO_GATEWAY_RELOAD_THRESHOLD = 3
        // JS engine freeze detection. If no evaluateJavascript callback fires
        // within this window, the Chromium JS engine is likely completely frozen
        // by background throttling (despite resumeTimers / visibility overrides).
        // Set below Discord's heartbeat_interval × 2 (~80s server close timeout)
        // but above 2 health-check cycles (60s) so a single slow callback
        // doesn't false-trigger.
        private const val JS_FREEZE_RELOAD_MS = 120_000L
        // After sustained healthy health checks for this long, reset the session
        // recovery counter so intermittent drops over a multi-hour session don't
        // exhaust MAX_SESSION_RECOVERIES.
        private const val HEALTHY_RESET_MS = 300_000L

        // WebView cache hygiene. Chromium's cacheDir/WebView (HTTP cache + Code
        // Cache) is what the user-visible "Cache" number shows and where runaway
        // growth lives. app_webview (profile data — cookies, localStorage, IndexedDB)
        // is tiny and MUST NOT be cleared: Discord's localStorage.token is the
        // gateway auth — wiping it kills the session even though the CookieManager
        // cookie survives, because Discord's JS client uses the token (not the
        // cookie) for the gateway Identify and API calls. The cap measures ONLY
        // cacheDir/WebView; when over cap we clear the HTTP cache IN PLACE
        // (clearCache(true)) so the live page/gateway/RPC are untouched — no
        // reload, no visible RPC drop. A disruptive rebuild is a last resort,
        // fired only if in-place clearing can't hold the cap for 2 cycles.
        private const val CACHE_FIRST_CHECK_DELAY_MS = 3L * 60 * 1000   // 3 min
        private const val CACHE_CHECK_INTERVAL_MS = 30L * 60 * 1000     // 30 min
        private const val WEBVIEW_CACHE_CAP_BYTES = 64L * 1024 * 1024   // 64 MB cacheDir only
        // Consecutive over-cap cycles (each already cleared HTTP cache in place)
        // before paying the disruptive delete+reload to reclaim Code Cache.
        private const val CACHE_HEAVY_CLEAR_STREAK = 2

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
    // Consecutive maintenance cycles found over cap after an in-place HTTP clear.
    // Drives the last-resort heavy rebuild (CACHE_HEAVY_CLEAR_STREAK).
    private var cacheOverCapStreak = 0
    private var shimReady = false
    private var shimRetryCount = 0
    private var sessionRecoveryCount = 0
    private var consecutivePushFailures = 0
    // Consecutive monitor checks reporting `dead` (readyState CLOSING/CLOSED — the
    // reliable, compression-independent "truly closed" signal). Reloads after
    // DEAD_RELOAD_THRESHOLD.
    private var deadHealthChecks = 0
    // Consecutive monitor checks reporting `zombie`/`bad` (heartbeat-ACK / OP7-9
    // derived — can false-positive under zlib-stream compression). Reloads only after
    // the higher DEGRADED_RELOAD_THRESHOLD so a transient/misclassified blip doesn't
    // trigger a disruptive reload; a truly-closed socket degrades to `dead` first.
    private var degradedHealthChecks = 0
    // Counts consecutive monitor cycles reporting `no_gateway`. Re-injecting the shim
    // recaptures a gateway only if Discord's JS still has a live one; if it keeps
    // coming back missing (gateway genuinely gone / flapping), escalate to a reload.
    private var noGatewayChecks = 0
    private var lastPushAttemptMs = 0L
    private var lastShimResult = ""
    @Volatile private var lastJsResponseMs = 0L
    @Volatile private var healthySinceMs = 0L

    // Set true when recovery is blocked purely because the device is offline (WiFi
    // off / no network). While true we DON'T burn MAX_SESSION_RECOVERIES on doomed
    // reloads; the network callback re-kicks recovery the instant connectivity
    // returns — so the user no longer has to reopen the app to fix "auth expired".
    @Volatile private var awaitingNetwork = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var onlineStartEpochMs = 0L
    private var offlineStartEpochMs = 0L
    // Wall-clock time the CURRENT continuous "in VRChat" run began (0 = offline).
    // Used to debounce a brief stale-online blip on restart (ONLINE_FLICKER_DEBOUNCE_MS).
    private var onlineSinceMs = 0L
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
        // Auto-recover the RPC when connectivity returns (idempotent — registers once).
        registerNetworkCallback()

        if (isRunning && webView != null) {
            // Duplicate START while already running (app reopened after Back, or a
            // sticky restart). startForeground must still be called, but post the
            // CURRENT "active" text — not "starting..." — so the shared persistent
            // notification (NOTIF_ID 1001) isn't reset to a stale startup state.
            startForegroundSafely(NOTIF_ID, buildNotif("Discord RPC active"), TAG)
            return START_STICKY
        }
        // FRESH start. A background-initiated start (sticky restart / pipeline onOpen
        // after an OEM kill) on API 31+/34 throws ForegroundServiceStartNotAllowedException;
        // wrap it so the process doesn't crash. On failure, don't sticky-restart into the throw.
        if (!startForegroundSafely(NOTIF_ID, buildNotif("Discord RPC starting..."), TAG)) {
            return START_NOT_STICKY
        }

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
            // No validated network → don't load. The page would come from the HTTP
            // cache and the shim would inject "ok" against it, falsely reporting
            // CONNECTED while the gateway socket can't open. Park and wait instead.
            if (!hasNetwork()) {
                Log.i(TAG, "loadWebView skipped — offline; waiting for network")
                parkOffline()
                return@post
            }
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

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        // Only the MAIN frame (the discord.com page itself) failing
                        // matters — sub-resource errors are noise. When it fails with no
                        // network, park in the offline-wait state instead of letting the
                        // health/login paths burn recovery attempts; the network callback
                        // reloads once connectivity is back.
                        if (request?.isForMainFrame != true) return
                        Log.w(TAG, "WebView main-frame load error: ${error?.errorCode} ${error?.description}")
                        if (!hasNetwork()) parkOffline()
                    }

                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: RenderProcessGoneDetail?
                    ): Boolean {
                        // The OS reclaimed this WebView's renderer process — common for a
                        // long-running BACKGROUND WebView under memory pressure. If we do
                        // NOT handle this (return true), the system may terminate our whole
                        // service process; and even if it doesn't, the page is blank and the
                        // RPC is dead until the 120s JS-freeze detector eventually reloads.
                        // Handle it: destroy the dead WebView and recreate from scratch NOW
                        // (fresh gateway), so a renderer reclaim recovers in seconds instead
                        // of leaving the RPC silently gone for up to two minutes.
                        Log.w(TAG, "WebView renderer gone (didCrash=${detail?.didCrash()}) — recreating WebView")
                        shimReady = false
                        stopPresenceUpdates()
                        if (view === webView) webView = null
                        try { view?.destroy() } catch (_: Throwable) {}
                        DiscordRpcState.status = DiscordRpcStatus.RECONNECTING
                        DiscordRpcState.failureMessage = "Discord connection lost — recovering..."
                        // loadWebView re-checks network (parks if offline). Post so we're not
                        // recreating while this callback is still on the WebView's stack.
                        mainHandler.post { loadWebView() }
                        return true
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
            lastJsResponseMs = System.currentTimeMillis()
            startCacheMaintenance()
            updateNotif("Discord RPC connecting...")
        }
    }

    private fun hasNetwork(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true // can't tell — assume online so we don't wedge recovery
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Registers a default-network callback (once) so the RPC auto-recovers when
     *  connectivity returns instead of requiring an app reopen. */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            // onAvailable fires the instant a network REGISTERS — before Android has
            // confirmed it actually reaches the internet (captive portal, connecting
            // Wi-Fi). Reloading then loads the cached page and false-"connects". So we
            // only recover on onCapabilitiesChanged once NET_CAPABILITY_VALIDATED is
            // set (Android probed real connectivity). onLost parks us immediately.
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    mainHandler.post { onNetworkAvailable() }
                }
            }

            override fun onLost(network: Network) {
                mainHandler.post { if (isRunning && !hasNetwork()) parkOffline() }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "Could not register network callback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                ?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {}
    }

    /** Connectivity came back. If the RPC was stuck offline (awaiting network) or
     *  had gone terminal, re-establish it from scratch — the automatic equivalent of
     *  the manual "reopen the app" fix. A healthy/normally-recovering session is left
     *  alone so a Wi-Fi handoff doesn't churn a working RPC. */
    private fun onNetworkAvailable() {
        if (!isRunning) return
        if (!hasNetwork()) return // not actually validated-online yet
        val status = DiscordRpcState.status
        val stuck = awaitingNetwork ||
            status == DiscordRpcStatus.SESSION_EXPIRED ||
            status == DiscordRpcStatus.FAILED
        if (!stuck) return
        Log.i(TAG, "Network available — re-establishing Discord RPC")
        awaitingNetwork = false
        sessionRecoveryCount = 0
        shimRetryCount = 0
        consecutivePushFailures = 0
        DiscordRpcState.status = DiscordRpcStatus.RECONNECTING
        DiscordRpcState.failureMessage = "Reconnecting to Discord..."
        updateNotif("Reconnecting to Discord...")
        // Full reload re-auths from the persisted localStorage token + cookies.
        loadWebView()
    }

    private fun onSessionExpired() {
        shimReady = false
        stopPresenceUpdates()

        // If we're offline, a WebView reload can only fail (the page can't load) —
        // don't waste a recovery attempt on it and don't go terminal "sign in again".
        // Park in RECONNECTING and let the network callback re-establish the RPC the
        // moment connectivity returns. This is the fix for "WiFi drops -> Discord
        // shows auth expired and only reopening the app fixes it".
        if (!hasNetwork()) {
            Log.i(TAG, "Session expired but offline — waiting for network before recovering")
            parkOffline()
            return
        }

        if (sessionRecoveryCount < MAX_SESSION_RECOVERIES) {
            sessionRecoveryCount++
            Log.i(TAG, "Session expired — attempting recovery ($sessionRecoveryCount/$MAX_SESSION_RECOVERIES)")
            DiscordRpcState.status = DiscordRpcStatus.RECONNECTING
            DiscordRpcState.failureMessage = "Session expired — reconnecting ($sessionRecoveryCount/$MAX_SESSION_RECOVERIES)"
            updateNotif("Discord session expired — reconnecting...")

            scope.launch {
                delay(2000L * sessionRecoveryCount)
                mainHandler.post {
                    CookieManager.getInstance().flush()
                    // FULL WebView recreation (loadWebView), NOT a same-page loadUrl on
                    // the existing WebView. Discord is a SPA already sitting at
                    // /channels/@me, so a loadUrl to the same route can soft-navigate
                    // WITHOUT tearing down and reopening the gateway WebSocket — the shim
                    // then recaptures the SAME half-dead gateway and presence pushes never
                    // resume (the user-reported "RPC doesn't start sending to Discord after
                    // reconnection; only fully reopening the app fixes it"). Recreating the
                    // WebView from scratch is exactly what reopening the app does: fresh
                    // Discord JS -> fresh gateway -> shim recapture -> startPresenceUpdates
                    // pushes again, so recovery behaves "like nothing happened".
                    loadWebView()
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
            // A `no_gateway` result means the hook IS installed but Discord's gateway
            // socket is gone, and re-running the module finder on the SAME page can't
            // recapture it — only restarting Discord's own JS (a page reload) opens a
            // fresh gateway. This was the user-reported dead end: the RPC silently
            // dropped, the app showed "Discord setup failed: no_gateway(hook=true)",
            // and only a manual sign-out/in (which reloads the page) brought it back.
            // Escalate to the reload path (the automatic equivalent of that manual fix)
            // instead of going terminal-FAILED. onSessionExpired() has its own recovery
            // cap, so this can't loop forever — after the cap it asks to sign in again.
            if (lastShimResult.contains("no_gateway")) {
                Log.w(TAG, "Gateway never reappeared — reloading WebView to re-establish it")
                shimRetryCount = 0
                onSessionExpired()
                return
            }
            DiscordRpcState.status = DiscordRpcStatus.FAILED
            DiscordRpcState.failureMessage = "Discord setup failed: $lastShimResult"
            updateNotif("Discord RPC: connection setup failed — needs update")
            return
        }

        val wv = webView ?: return
        wv.evaluateJavascript(MODULE_FINDER_JS) { result ->
            lastJsResponseMs = System.currentTimeMillis()
            val cleaned = result?.trim()?.replace("\"", "") ?: "null"
            lastShimResult = cleaned
            if (cleaned == "ok" && !hasNetwork()) {
                // Module finder found Discord's modules on the CACHED page, but
                // there's no network so the gateway socket isn't real. Don't declare
                // CONNECTED — park and wait for connectivity (cached-page false
                // "connected" was the offline flicker).
                Log.i(TAG, "Shim ok but offline — parking instead of declaring connected")
                parkOffline()
            } else if (cleaned == "ok") {
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
                // Diagnostic: log the REAL gateway health ~4s after a (re)connect so
                // logcat confirms the socket was actually captured after a recovery
                // (vs a false CONNECTED that reports no_gateway). Read-only probe.
                mainHandler.postDelayed({
                    webView?.evaluateJavascript(
                        "window.VRCA_gatewayHealth ? window.VRCA_gatewayHealth() : 'no_fn'"
                    ) { h -> Log.i(TAG, "post-connect gateway health: ${h?.trim()?.replace("\"", "")}") }
                }, 4000)
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

    /** Park the RPC quietly in the offline-wait state. The whole point: while there
     *  is genuinely no network, the WebView still serves discord.com from its HTTP
     *  CACHE, so a page-load + shim-inject "succeeds" and we'd otherwise declare the
     *  RPC CONNECTED even though the gateway WebSocket can never open — then a health
     *  check finds the dead gateway, we reload, the cached page loads again, and the
     *  status flickers CONNECTED -> RECONNECTING every ~10s. parkOffline stops all of
     *  that: presence pushes off, shim marked not-ready (so health probes/pushes are
     *  no-ops), no reload/reinject. The validated-network callback re-establishes from
     *  scratch the moment real connectivity returns. Idempotent. */
    private fun parkOffline() {
        awaitingNetwork = true
        shimReady = false
        stopPresenceUpdates()
        // Don't let the JS-freeze watchdog trip a reload while parked / on return.
        lastJsResponseMs = System.currentTimeMillis()
        deadHealthChecks = 0
        degradedHealthChecks = 0
        noGatewayChecks = 0
        consecutivePushFailures = 0
        DiscordRpcState.status = DiscordRpcStatus.RECONNECTING
        DiscordRpcState.failureMessage =
            "Waiting for network — Discord will reconnect automatically"
        updateNotif("Waiting for network — Discord will reconnect...")
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
     * WebView cache cap. Measures ONLY `cacheDir/WebView` (HTTP cache + Code
     * Cache — the user-visible "Cache" growth). `app_webview` (profile data) is
     * deliberately excluded: it holds `localStorage` (including Discord's auth
     * token) and is tiny. Wiping it kills the Discord session.
     */
    private fun startCacheMaintenance() {
        cacheMaintenanceJob?.cancel()
        cacheMaintenanceJob = scope.launch {
            delay(CACHE_FIRST_CHECK_DELAY_MS)
            while (true) {
                try {
                    val cacheWebViewDir = File(cacheDir, "WebView")
                    val size = dirSizeBytes(cacheWebViewDir)
                    if (size > WEBVIEW_CACHE_CAP_BYTES) {
                        cacheOverCapStreak++
                        // SEAMLESS PATH (the default, and in practice the only one that ever
                        // runs): clear the disk HTTP cache IN PLACE. clearCache(true) empties
                        // the HTTP cache buckets (Discord's assets — the dominant grower)
                        // WITHOUT navigating, reloading, or terminating the renderer, so the
                        // live gateway socket + injected shim + presence loop are UNTOUCHED and
                        // the RPC never blinks off the user's Discord profile. This replaces the
                        // old proactive stopPresenceUpdates()+loadWebView(), which fixed the cap
                        // but visibly dropped the RPC for a few seconds on EVERY over-cap cycle
                        // (the "it fixes itself but you can see it disappear" problem). The
                        // session-monitor's own recovery ladder still handles any genuine gateway
                        // hiccup — we no longer manufacture one just for disk hygiene.
                        Log.i(TAG, "WebView cache ${size / (1024 * 1024)}MB over cap — clearing HTTP cache in place (RPC preserved, streak=$cacheOverCapStreak)")
                        mainHandler.post {
                            try {
                                webView?.clearCache(true)
                            } catch (e: Throwable) {
                                Log.w(TAG, "WebView cache clear failed", e)
                            }
                        }

                        // HEAVY FALLBACK (rare — basically never): only if an in-place clear on
                        // the PREVIOUS cycle STILL left us over cap does Code Cache alone exceed
                        // the cap (clearCache can't reclaim Code Cache; the only way is deleting
                        // files under a live renderer, which forces a reload). Gated behind a
                        // 2-cycle streak so a single stale over-cap reading (clearCache's disk
                        // delete not yet settled) can never trigger a visible RPC drop. This is
                        // the ONE path that rebuilds the WebView — the session survives in
                        // app_webview (cookies + localStorage.token) so it re-auths silently.
                        if (cacheOverCapStreak >= CACHE_HEAVY_CLEAR_STREAK) {
                            cacheOverCapStreak = 0
                            Log.w(TAG, "WebView cache still over cap after in-place clear — heavy rebuild to reclaim Code Cache")
                            scope.launch {
                                try {
                                    cacheWebViewDir.listFiles()?.forEach { it.deleteRecursively() }
                                } catch (e: Throwable) {
                                    Log.w(TAG, "WebView cacheDir sweep failed", e)
                                }
                                mainHandler.post {
                                    // Deliberately do NOT flip status to RECONNECTING/DISCONNECTED
                                    // here. This is a SCHEDULED maintenance rebuild — surfacing
                                    // "reconnecting" would make users think something broke when
                                    // it's just disk hygiene. Leave the status showing CONNECTED:
                                    // shimReady=false makes the session monitor skip probing while
                                    // the page rebuilds, and on the normal successful re-inject
                                    // injectShim re-asserts CONNECTED (a no-op from CONNECTED), so
                                    // the whole cycle is invisible. A GENUINE failure still
                                    // surfaces — injectShim flips to RECONNECTING on its retries
                                    // and FAILED after exhausting them — so nothing real is masked.
                                    shimReady = false
                                    stopPresenceUpdates()
                                    loadWebView()
                                }
                            }
                        }
                    } else {
                        cacheOverCapStreak = 0
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
        deadHealthChecks = 0
        degradedHealthChecks = 0
        noGatewayChecks = 0
        lastJsResponseMs = System.currentTimeMillis()
        healthySinceMs = 0L
        sessionMonitorJob = scope.launch {
            while (true) {
                delay(SESSION_CHECK_INTERVAL_MS)

                // Offline → park quietly and skip ALL health-probe / freeze / reload
                // logic. Probing the cached page would churn no_gateway -> reinject ->
                // false CONNECTED. The validated-network callback recovers us.
                if (!hasNetwork()) {
                    if (!awaitingNetwork) Log.i(TAG, "Session monitor: offline — parking")
                    parkOffline()
                    continue
                }

                // Re-assert JS timer and visibility state every cycle. Chromium
                // can throttle/freeze a detached WebView's JS when backgrounded
                // despite the onWindowVisibilityChanged override; periodic
                // resumeTimers + visibility dispatch combats that.
                mainHandler.post {
                    webView?.let { w ->
                        w.resumeTimers()
                        w.dispatchWindowVisibilityChanged(View.VISIBLE)
                    }
                }

                // Detect frozen JS engine: if no evaluateJavascript callback has
                // fired for 2+ minutes, the engine is completely frozen and the
                // Discord gateway is dead or dying. Force a reload to recover.
                if (lastJsResponseMs > 0) {
                    val jsSilence = System.currentTimeMillis() - lastJsResponseMs
                    if (jsSilence > JS_FREEZE_RELOAD_MS) {
                        Log.w(TAG, "JS engine unresponsive for ${jsSilence / 1000}s — forcing reload")
                        lastJsResponseMs = System.currentTimeMillis()
                        mainHandler.post { onSessionExpired() }
                        continue
                    }
                }

                if (!shimReady) continue

                mainHandler.post {
                    val wv = webView ?: return@post
                    wv.evaluateJavascript(
                        "(window.VRCA_gatewayHealth ? window.VRCA_gatewayHealth() : 'no_probe')"
                    ) { result ->
                        lastJsResponseMs = System.currentTimeMillis()
                        if (!shimReady) return@evaluateJavascript
                        when (result?.trim()?.replace("\"", "") ?: "null") {
                            "alive", "connecting" -> {
                                if (deadHealthChecks != 0) deadHealthChecks = 0
                                if (degradedHealthChecks != 0) degradedHealthChecks = 0
                                if (noGatewayChecks != 0) noGatewayChecks = 0
                                if (DiscordRpcState.status == DiscordRpcStatus.RECONNECTING) {
                                    DiscordRpcState.status = DiscordRpcStatus.CONNECTED
                                    DiscordRpcState.failureMessage = null
                                }
                                val nowMs = System.currentTimeMillis()
                                if (healthySinceMs == 0L) healthySinceMs = nowMs
                                if (sessionRecoveryCount > 0 &&
                                    (nowMs - healthySinceMs) > HEALTHY_RESET_MS) {
                                    Log.i(TAG, "Gateway healthy for ${(nowMs - healthySinceMs) / 1000}s — resetting recovery counter")
                                    sessionRecoveryCount = 0
                                }
                            }
                            // `no_probe` = window.VRCA_gatewayHealth is UNDEFINED. Because this
                            // branch only runs while shimReady==true, an undefined probe means
                            // the shim was destroyed out from under us (a page reload — e.g.
                            // cache maintenance — that blew away the injected functions). This
                            // was previously grouped with "alive", so a reloaded/dead page kept
                            // reporting CONNECTED and never recovered on its own (only a toggle
                            // fixed it). Treat it exactly like a lost gateway: re-inject, then
                            // reload after the threshold.
                            "no_gateway", "no_probe" -> {
                                healthySinceMs = 0L
                                deadHealthChecks = 0
                                degradedHealthChecks = 0
                                noGatewayChecks++
                                if (noGatewayChecks >= NO_GATEWAY_RELOAD_THRESHOLD) {
                                    noGatewayChecks = 0
                                    Log.w(TAG, "Session monitor: gateway still missing after re-inject — reloading")
                                    onSessionExpired()
                                } else {
                                    Log.w(TAG, "Session monitor: gateway reference lost — re-injecting shim")
                                    shimReady = false
                                    shimRetryCount = 0
                                    DiscordRpcState.status = DiscordRpcStatus.RECONNECTING
                                    DiscordRpcState.failureMessage = "Reconnecting to Discord..."
                                    injectShim()
                                }
                            }
                            "dead" -> {
                                healthySinceMs = 0L
                                degradedHealthChecks = 0
                                noGatewayChecks = 0
                                deadHealthChecks++
                                DiscordRpcState.status = DiscordRpcStatus.RECONNECTING
                                DiscordRpcState.failureMessage = "Discord connection lost — recovering..."
                                if (deadHealthChecks >= DEAD_RELOAD_THRESHOLD) {
                                    deadHealthChecks = 0
                                    Log.w(TAG, "Session monitor: gateway closed — reloading to recover")
                                    onSessionExpired()
                                }
                            }
                            "zombie", "bad" -> {
                                healthySinceMs = 0L
                                deadHealthChecks = 0
                                noGatewayChecks = 0
                                degradedHealthChecks++
                                DiscordRpcState.status = DiscordRpcStatus.RECONNECTING
                                DiscordRpcState.failureMessage = "Discord connection unstable — recovering..."
                                if (degradedHealthChecks >= DEGRADED_RELOAD_THRESHOLD) {
                                    degradedHealthChecks = 0
                                    Log.w(TAG, "Session monitor: gateway unhealthy (${DEGRADED_RELOAD_THRESHOLD}x) — reloading to recover")
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
            wv.resumeTimers()
            wv.evaluateJavascript("window.VRCA_setActivity('$escaped')") { result ->
                lastJsResponseMs = System.currentTimeMillis()
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
        val nowMs = System.currentTimeMillis()
        // Debounce a BRIEF stale "in VRChat" blip (e.g. a presence reported for a
        // second right after an app restart / update) — require the online state to
        // hold for ONLINE_FLICKER_DEBOUNCE_MS before the RPC treats it as online.
        // A 1s flicker never reaches the threshold, so it can't flip the display
        // online or restart the not-in-VRChat counter. onlineSinceMs is in-memory
        // (resets on process start), so a stale first presence must sustain to count.
        val rawOnline = vrcPresence?.isOnlineInVRChat == true
        if (rawOnline) { if (onlineSinceMs == 0L) onlineSinceMs = nowMs } else onlineSinceMs = 0L
        val isOnline = rawOnline && (nowMs - onlineSinceMs) >= ONLINE_FLICKER_DEBOUNCE_MS
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
            // Only clear the not-in-VRChat counter once the online session is
            // CONFIRMED sustained (online_start_epoch is at least
            // OFFLINE_CLEAR_CONFIRM_MS old). A transient presence flicker to
            // "online" resolves a FRESH online_start_epoch (the prior session
            // went stale > grace ago), so nowMs - onlineStartEpochMs ~= 0 and
            // we leave the offline prefs intact — the offline counter then
            // CARRIES ON across the blip within its own grace window instead of
            // restarting from 0. A genuine sustained visit (or one that carried
            // its start across a real brief blip) clears them, so leaving
            // VRChat restarts the offline clock as intended. Fix for "the
            // not-in-VRChat RPC timer randomly restarts on connection blips";
            // the in-VRChat timer was never affected (it has its own grace
            // carry and its prefs are never cleared), which is why it's stable
            // during active play.
            val onlineConfirmed = onlineStartEpochMs > 0L &&
                (nowMs - onlineStartEpochMs) >= OFFLINE_CLEAR_CONFIRM_MS
            if (onlineConfirmed &&
                (rpcPrefs.getLong("offline_start_epoch", 0L) != 0L ||
                    rpcPrefs.getLong("offline_last_seen", 0L) != 0L)) {
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
        unregisterNetworkCallback()
        awaitingNetwork = false
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
    // RESTORE the last activity from localStorage so a PAGE RELOAD (session
    // recovery / cache maintenance) doesn't leave _vrca_activity null. The in-page
    // re-assert interval (_vrca_pushInterval) runs even while the app is
    // backgrounded — but only re-pushes when _vrca_activity is set. After a reload
    // the fresh page had it null, and the native push that would set it is throttled
    // by Chromium while backgrounded, so the RPC stayed absent until the app was
    // foregrounded (the "reconnected but didn't reappear until I reopened" bug).
    // Restoring here lets the JS re-assert re-land the RPC with no native push.
    try {
        var _saved = localStorage.getItem('_vrca_last_activity');
        window._vrca_activity = _saved ? JSON.parse(_saved) : null;
    } catch(e) { window._vrca_activity = null; }
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
    window._vrca_lastSeq = null;
    // Last time ANY inbound frame arrived (string OR binary). The message event
    // fires regardless of zlib-stream compression, so this is a
    // compression-independent liveness signal: a healthy Discord gateway sends
    // heartbeat ACKs (~every 41s) plus event frames continuously, so a long
    // silence means the socket is a zombie even though readyState is still 1.
    window._vrca_lastFrameMs = 0;

    // Attach passive observers to a captured gateway socket. We ONLY observe
    // (and re-push our own activity); we never alter Discord's own frames here,
    // so the "real web session" property is preserved. Idempotent per socket.
    window._vrca_attachGatewayListeners = function(ws) {
        if (!ws || ws._vrca_listened) return;
        ws._vrca_listened = true;
        try {
            ws.addEventListener('message', function(ev) {
                try {
                    // Count EVERY inbound frame (compression-independent liveness).
                    window._vrca_lastFrameMs = Date.now();
                    if (typeof ev.data !== 'string') return; // compressed/binary: can't parse, but counted above
                    var m = JSON.parse(ev.data);
                    if (m.s != null) window._vrca_lastSeq = m.s;
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
            var origSend = window._vrca_origSend;
            if (!origSend) return;
            // If the Discord heartbeat has stalled (JS timers throttled by
            // Chromium background policy), send OP 1 manually to keep the
            // server from closing the gateway (~80s timeout). The native side
            // calls this every 10s via VRCA_setActivity, so the heartbeat
            // stays alive even when Discord's own setInterval is frozen.
            var hb = window._vrca_hbInterval;
            var lastAck = window._vrca_lastAckMs;
            if (hb > 0 && lastAck > 0 && (Date.now() - lastAck) > hb * 1.5) {
                try { origSend.call(gw, JSON.stringify({ op: 1, d: window._vrca_lastSeq })); } catch(e) {}
            }
            var activity = window._vrca_activity;
            if (!activity) return;
            origSend.call(gw, JSON.stringify({
                op: 3,
                d: {
                    since: 0,
                    activities: [activity],
                    status: window._vrca_user_status || 'online',
                    afk: false
                }
            }));
            window._vrca_lastSentMs = Date.now();
            // Persist so a page reload can restore it (see the shim init) and
            // re-assert the RPC without waiting on a throttled native push.
            try { localStorage.setItem('_vrca_last_activity', JSON.stringify(activity)); } catch(e) {}
        };

        window.VRCA_setActivity = function(jsonStr) {
            try {
                var gw = window._vrca_gatewayWs;
                if (!gw || gw.readyState !== 1) return 'no_gateway';
                var activity = JSON.parse(jsonStr);
                // Each call supersedes any in-flight image resolution. Bump a
                // generation token so a SLOW external-assets resolve for a PREVIOUS
                // world can't land late and overwrite _vrca_activity with the stale
                // world — the rapid-world-switch flicker / wrong-world / re-send
                // churn (worse the faster the user hops instances).
                window._vrca_activityGen = (window._vrca_activityGen || 0) + 1;
                var myGen = window._vrca_activityGen;
                var imageUrl = activity.assets && activity.assets.large_image;
                if (imageUrl && imageUrl.indexOf('http') === 0) {
                    var cached = window._vrca_asset_cache[imageUrl];
                    if (cached) {
                        activity.assets.large_image = cached;
                        window._vrca_activity = activity;
                        window._vrca_sendPresence();
                        return 'ok';
                    }
                    // CRITICAL: send the activity NOW without the unresolved image
                    // so the RPC shows immediately, then upgrade it once the image
                    // resolves. Previously presence was sent ONLY inside the resolve
                    // callback — so if external-assets resolution failed (token not
                    // grabbed, fetch deferred under background throttling, or the
                    // URL just won't resolve), the activity was NEVER sent even on a
                    // perfectly healthy socket: the app showed "connected" while the
                    // RPC was silently absent from Discord. Discord rejects a raw
                    // https large_image, so we strip it for the immediate send and
                    // add the resolved mp: path on the upgrade.
                    var immediate = JSON.parse(JSON.stringify(activity));
                    if (immediate.assets) { delete immediate.assets.large_image; }
                    window._vrca_activity = immediate;
                    window._vrca_sendPresence();
                    var retries = 0;
                    var tryResolve = function() {
                        VRCA_resolveAsset(imageUrl).then(function(resolved) {
                            // Superseded by a newer world? Abandon — do not overwrite
                            // the current activity or keep retrying for a stale world.
                            if (myGen !== window._vrca_activityGen) return;
                            if (resolved) {
                                activity.assets.large_image = resolved;
                                window._vrca_activity = activity;
                                window._vrca_sendPresence();
                            } else if (retries < 10) {
                                retries++;
                                setTimeout(tryResolve, 2000);
                            }
                            // On permanent failure we keep the image-less activity
                            // (already sent + stored), so the RPC stays visible.
                        });
                    };
                    tryResolve();
                    return 'ok';
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
                // Supersede any in-flight image resolution so it can't re-add an
                // activity after we've cleared it.
                window._vrca_activityGen = (window._vrca_activityGen || 0) + 1;
                window._vrca_activity = null;
                // Don't let a reload restore a cleared activity.
                try { localStorage.removeItem('_vrca_last_activity'); } catch(e) {}
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
                // Compression-independent zombie check: a healthy gateway delivers
                // heartbeat ACKs + events continuously, so no inbound frame of ANY
                // kind for 90s means the socket is dead-but-open (the case where
                // zlib-stream compression hides the op11 ACKs from the check above,
                // so it would otherwise falsely report 'alive').
                var lastFrame = window._vrca_lastFrameMs;
                if (lastFrame > 0 && (Date.now() - lastFrame) > 90000) return 'zombie';
                return 'alive';
            } catch(e) { return 'alive'; }
        };

        // JS-side presence re-assertion (the "works then disappears while still
        // connected" fix). Presence was re-asserted ONLY by the native 10s timer
        // via evaluateJavascript — but a backgrounded WebView has those native->JS
        // calls throttled/queued by Chromium, while Discord's OWN internal
        // setInterval heartbeat keeps the socket OPEN (so the app shows connected).
        // The instant Discord dropped our activity (idle transition / session
        // resume / its own status change) our native re-push couldn't run, so the
        // RPC vanished permanently on a live socket. Running the re-push from a
        // setInterval INSIDE the page couples it to the same JS engine that keeps
        // the heartbeat alive: if the socket stays up, our re-assert stays running.
        // Idempotent (same activity payload; Discord coalesces); guarded against
        // duplicate intervals on shim re-injection.
        if (window._vrca_pushInterval) { try { clearInterval(window._vrca_pushInterval); } catch(e) {} }
        window._vrca_pushInterval = setInterval(function() {
            try {
                if (!window._vrca_activity) return;
                // Skip if a native push already re-asserted within the last 7s, so
                // this only fires when native evaluateJavascript pushes are being
                // throttled (background) — avoids doubling OP 3 in the foreground.
                var lastSent = window._vrca_lastSentMs || 0;
                if (Date.now() - lastSent < 7000) return;
                window._vrca_sendPresence();
            } catch(e) {}
        }, 10000);

        return 'ok';
    } catch(e) {
        return 'err:' + e.message;
    }
})();
"""
