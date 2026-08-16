package com.vrca.osc

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * OSC-in for Quest via **OSCQuery pull** (VRChat won't push avatar params over UDP
 * on Quest — only `/avatar/change` — but it DOES serve every param's live value over
 * its OSCQuery HTTP server, confirmed on-device:
 *   GET /avatar/parameters/MuteSelf -> {"TYPE":"T","VALUE":[true]}
 *
 * So instead of listening for a push that never comes, we:
 *   1. mDNS-discover VRChat's `_oscjson._tcp` service (NsdManager) → host:port.
 *   2. Poll `GET http://host:port/avatar/parameters` (~250ms) — one request returns
 *      the whole avatar-parameter subtree WITH current values.
 *   3. Fold each param's VALUE into [VrcaOscState] so the chatbox tokens
 *      `{mute}` `{afk}` `{movement}` `{scale}` `{param:Name}` resolve.
 *
 * Needs cleartext HTTP (OSCQuery is plain http on the LAN) — permitted on the
 * headset build only. Headset-started (VRChat runs on the same Quest, advertising
 * OSC_IP 127.0.0.1).
 *
 * Robustness (hard-won on-device): discovery RE-ARMS. NsdManager discovery started
 * before the network / VRChat is up either fails or misses VRChat's later launch, so
 * the poll loop (a) re-starts discovery if it isn't active, and (b) periodically
 * restarts it while VRChat hasn't been found — so opening VRChat AFTER VRC-A is
 * picked up without reopening VRC-A. Liveness is grace-based: a single failed poll
 * does NOT drop `host` (one HTTP hiccup would otherwise disable it); `isServiceUp()`
 * ages out `UP_GRACE_MS` after the last OK poll, and only SUSTAINED failure re-arms
 * discovery (VRChat closed / restarted on a new port).
 */
object VrcaOscQuery {

    private const val TAG = "VrcaOscQuery"
    private const val SERVICE_TYPE = "_oscjson._tcp."
    private const val POLL_MS = 250L
    // isServiceUp() stays true this long after the last successful poll — rides out
    // a transient HTTP/network hiccup without flickering "VRChat closed".
    private const val UP_GRACE_MS = 12_000L
    // Re-arm discovery this often while VRChat hasn't been found (catches VRChat
    // opened AFTER VRC-A, even if NsdManager's continuous discovery missed it).
    private const val REARM_MS = 20_000L
    // Sustained poll failure this long → drop host + re-discover (VRChat closed, or
    // restarted on a new OSCQuery port).
    private const val REDISCOVER_AFTER_FAIL_MS = 20_000L

    @Volatile private var started = false
    @Volatile private var host: String? = null
    @Volatile private var port: Int = 0
    @Volatile private var serviceName: String = ""

    // VRChat's ACTUAL OSC-in UDP port, read from the OSCQuery HOST_INFO. On Quest,
    // VRChat and VRC-A share the device, so 9000 can be contended and VRChat picks a
    // DIFFERENT input port, advertising the real one ONLY here. The chatbox sender
    // must target THIS (not a hardcoded 9000) or sends leave the app fine but VRChat
    // never receives them — the headset-only "chatbox stops, reopen fixes it" bug.
    // 0 = unknown yet (sender falls back to 9000). Reset on rediscovery.
    private val _oscInPort = MutableStateFlow(0)
    val oscInPortFlow: StateFlow<Int> = _oscInPort.asStateFlow()
    @Volatile private var oscInIp: String = ""
    @Volatile private var hostInfoFetchedFor: String = ""
    @Volatile private var lastPollOkMs = 0L
    @Volatile private var discoveryActive = false
    @Volatile private var lastDiscoveryStartMs = 0L
    private var appContext: Context? = null

    private var nsd: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /** True while VRChat's OSCQuery service is reachable (VRChat open, OSC on). */
    fun isServiceUp(): Boolean =
        host != null && lastPollOkMs > 0L && System.currentTimeMillis() - lastPollOkMs < UP_GRACE_MS

    /** True once we've had at least one successful poll this process (OSCQuery has
     *  actually worked). Callers use log-staleness only BEFORE this is true. */
    fun hasEverPolledOk(): Boolean = lastPollOkMs > 0L

    /** Idempotent. The poll loop manages discovery (start / re-arm). */
    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        startPollLoop()
    }

    // ---- poll + discovery management ----------------------------------------

    private fun startPollLoop() {
        Thread({
            while (started) {
                val ctx = appContext
                if (ctx != null) {
                    val now = System.currentTimeMillis()
                    // (Re)arm discovery if it isn't running, or periodically while
                    // VRChat still hasn't been found (booted before VRChat / missed).
                    if (!discoveryActive || (host == null && now - lastDiscoveryStartMs > REARM_MS)) {
                        restartDiscovery(ctx)
                    }
                    val h = host; val p = port
                    if (h != null && p > 0) {
                        if (pollParams(h, p)) {
                            lastPollOkMs = System.currentTimeMillis()
                            // Read VRChat's ADVERTISED OSC-in endpoint once per resolved
                            // service. DIAGNOSTIC ONLY for now (the sender still uses the
                            // hardcoded 9000, matching VRC-NEXUS) — this lets us CONFIRM
                            // whether VRChat is actually on 9000 or a different port when
                            // the chatbox "stops" (udp ok but not received).
                            if (hostInfoFetchedFor != "$h:$p") fetchHostInfo(h, p)
                        } else if (lastPollOkMs > 0L &&
                            System.currentTimeMillis() - lastPollOkMs > REDISCOVER_AFTER_FAIL_MS
                        ) {
                            // Sustained failure → VRChat closed / restarted. Drop the
                            // stale host and re-discover (a restart may use a new port).
                            host = null; port = 0; hostInfoFetchedFor = ""
                            restartDiscovery(ctx)
                        }
                    }
                }
                VrcaOscState.tickLiveness()
                try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) { break }
            }
        }, "vrca-oscquery").apply { isDaemon = true; start() }
    }

    private fun restartDiscovery(context: Context) {
        stopDiscovery()
        startDiscovery(context)
    }

    private fun stopDiscovery() {
        val m = nsd; val l = discoveryListener
        if (m != null && l != null) runCatching { m.stopServiceDiscovery(l) }
        discoveryActive = false
        discoveryListener = null
    }

    private fun startDiscovery(context: Context) {
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (manager == null) {
            VrcaOscState.oscQueryDiag = "NsdManager unavailable"
            return
        }
        nsd = manager
        lastDiscoveryStartMs = System.currentTimeMillis()

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
                Log.w(TAG, "resolve failed ${info?.serviceName} err=$errorCode")
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                val h = info.host?.hostAddress ?: return
                host = h
                port = info.port
                serviceName = info.serviceName
                updateDiag("resolved $serviceName @ $h:$port")
            }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String?, e: Int) {
                discoveryActive = false
                updateDiag("discovery start failed err=$e")
            }
            override fun onStopDiscoveryFailed(t: String?, e: Int) { discoveryActive = false }
            override fun onDiscoveryStarted(t: String?) {
                discoveryActive = true
                updateDiag("discovery started")
            }
            override fun onDiscoveryStopped(t: String?) { discoveryActive = false }
            override fun onServiceFound(info: NsdServiceInfo) {
                // Only VRChat's own OSCQuery service (others may exist on the LAN).
                if (!info.serviceName.startsWith("VRChat", ignoreCase = true)) return
                runCatching { nsd?.resolveService(info, resolveListener) }
            }
            override fun onServiceLost(info: NsdServiceInfo?) {
                if (info?.serviceName == serviceName) {
                    host = null; port = 0; hostInfoFetchedFor = ""
                    updateDiag("service lost: ${info?.serviceName}")
                }
            }
        }
        discoveryListener = listener
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            discoveryActive = false
            updateDiag("discover error: ${e.message}")
        }
    }

    /** DIAGNOSTIC: read VRChat's HOST_INFO once per resolved service to learn the
     *  ADVERTISED OSC-in UDP endpoint (OSC_IP/OSC_PORT). The chatbox sender still
     *  targets the hardcoded 9000 (matching VRC-NEXUS); this only lets the debug panel
     *  show whether VRChat is actually on 9000 when the chatbox "stops" (udp ok but not
     *  received). If it turns out VRChat is on a non-9000 port, wiring oscInPortFlow
     *  into the sender is the justified fix. */
    private fun fetchHostInfo(h: String, p: Int) {
        val body = httpGetBody("http://$h:$p/?HOST_INFO") ?: return
        runCatching {
            val o = JSONObject(body)
            val oscPort = o.optInt("OSC_PORT", 0)
            val oscIp = o.optString("OSC_IP", "")
            if (oscPort in 1..65535) {
                _oscInPort.value = oscPort
                oscInIp = oscIp
                hostInfoFetchedFor = "$h:$p"
            }
        }
    }

    /** GET the whole avatar-parameter subtree and fold every value. Returns false
     *  on a network error (VRChat unreachable), true otherwise. */
    private fun pollParams(h: String, p: Int): Boolean {
        val body = httpGetBody("http://$h:$p/avatar/parameters") ?: return false
        return try {
            val contents = JSONObject(body).optJSONObject("CONTENTS") ?: return true
            var count = 0
            var sawEyeHeight = false
            val keys = contents.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val node = contents.optJSONObject(name) ?: continue
                val value = extractValue(node) ?: continue
                VrcaOscState.onParam(name, value)
                if (name == "EyeHeightAsMeters") sawEyeHeight = true
                count++
            }
            // Only when EyeHeightAsMeters isn't a param, fall back to the canonical
            // top-level /avatar/eyeheight node (one extra small GET) so the eye-height
            // read still works. Fed as EyeHeightAsMeters so {scale} + the size
            // control's live read both pick it up.
            if (!sawEyeHeight) {
                httpGetBody("http://$h:$p/avatar/eyeheight")?.let { ehBody ->
                    runCatching {
                        val v = JSONObject(ehBody).optJSONArray("VALUE")?.takeIf { it.length() > 0 }?.get(0)
                        (v as? Number)?.toFloat()?.let { VrcaOscState.onParam("EyeHeightAsMeters", it) }
                    }
                }
            }
            updateDiag(
                "polling $serviceName @ $h:$p — $count params" +
                (if (_oscInPort.value > 0)
                    "\nVRChat OSC-in advertised: ${oscInIp.ifBlank { "?" }}:${_oscInPort.value}" else "")
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "parse /avatar/parameters failed", e); false
        }
    }

    /** VALUE is a 1-element array; TYPE T/F = bool, i = int, f = float. */
    private fun extractValue(node: JSONObject): Any? {
        val arr = node.optJSONArray("VALUE") ?: return null
        if (arr.length() == 0) return null
        return when (val v = arr.get(0)) {
            is Boolean -> v
            is Number -> v
            is String -> v
            else -> null
        }
    }

    private fun httpGetBody(url: String): String? {
        // ALWAYS disconnect in finally. This runs 4x/second on the headset ONLY —
        // an exception between openConnection and disconnect would leak a connection
        // every failed poll, and at that rate the process fd limit is reached fast.
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 2500; readTimeout = 2500; requestMethod = "GET"
            }
            val code = conn.responseCode
            if (code in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun updateDiag(s: String) {
        VrcaOscState.oscQueryDiag = buildString {
            append(s)
            append("\nmute=").append(VrcaOscState.muteSelf)
            append("  afk=").append(VrcaOscState.afk)
            append("  moving=").append(VrcaOscState.moving)
            append("  scale=").append(VrcaOscState.scaleLabel.ifBlank { "(none)" })
        }
    }
}
