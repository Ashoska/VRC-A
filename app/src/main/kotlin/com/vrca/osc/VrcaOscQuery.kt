package com.vrca.osc

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
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
 *   2. Poll `GET http://host:port/avatar/parameters` (~1s) — one request returns the
 *      whole avatar-parameter subtree WITH current values.
 *   3. Fold each param's VALUE into [VrcaOscState] so the chatbox tokens
 *      `{mute}` `{afk}` `{movement}` `{scale}` `{param:Name}` resolve.
 *
 * Needs cleartext HTTP (OSCQuery is plain http on the LAN) — permitted on the
 * headset build only. Headset-started (VRChat runs on the same Quest, advertising
 * OSC_IP 127.0.0.1). The old UDP receiver ([VrcaOscReceiver]) stays as a no-cost
 * fallback for the `/avatar/change` event and PC push setups.
 */
object VrcaOscQuery {

    private const val TAG = "VrcaOscQuery"
    private const val SERVICE_TYPE = "_oscjson._tcp."
    private const val POLL_MS = 1000L

    @Volatile private var started = false
    @Volatile private var host: String? = null
    @Volatile private var port: Int = 0
    @Volatile private var serviceName: String = ""

    private var nsd: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /** Idempotent. Starts mDNS discovery + the poll loop. */
    fun start(context: Context) {
        if (started) return
        started = true
        startDiscovery(context.applicationContext)
        startPollLoop()
    }

    // ---- discovery -----------------------------------------------------------

    private fun startDiscovery(context: Context) {
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (manager == null) {
            VrcaOscState.oscQueryDiag = "NsdManager unavailable"
            return
        }
        nsd = manager

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
            override fun onStartDiscoveryFailed(t: String?, e: Int) { updateDiag("discovery start failed err=$e") }
            override fun onStopDiscoveryFailed(t: String?, e: Int) {}
            override fun onDiscoveryStarted(t: String?) { updateDiag("discovery started") }
            override fun onDiscoveryStopped(t: String?) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                // Only VRChat's own OSCQuery service (others may exist on the LAN).
                if (!info.serviceName.startsWith("VRChat", ignoreCase = true)) return
                runCatching { nsd?.resolveService(info, resolveListener) }
            }
            override fun onServiceLost(info: NsdServiceInfo?) {
                if (info?.serviceName == serviceName) {
                    host = null; port = 0
                    updateDiag("service lost: ${info?.serviceName}")
                }
            }
        }
        discoveryListener = listener
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    // ---- poll ----------------------------------------------------------------

    private fun startPollLoop() {
        Thread({
            while (started) {
                val h = host
                val p = port
                if (h != null && p > 0) {
                    val ok = pollParams(h, p)
                    if (!ok) { host = null; port = 0 } // dropped → wait for re-discovery
                }
                VrcaOscState.tickLiveness()
                try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) { break }
            }
        }, "vrca-oscquery").apply { isDaemon = true; start() }
    }

    /** GET the whole avatar-parameter subtree and fold every value. Returns false
     *  on a network error (host gone), true otherwise. */
    private fun pollParams(h: String, p: Int): Boolean {
        val body = httpGetBody("http://$h:$p/avatar/parameters") ?: return false
        return try {
            val contents = JSONObject(body).optJSONObject("CONTENTS") ?: return true
            var count = 0
            val keys = contents.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val node = contents.optJSONObject(name) ?: continue
                val value = extractValue(node) ?: continue
                VrcaOscState.onParam(name, value)
                count++
            }
            updateDiag("polling $serviceName @ $h:$p — $count params")
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

    private fun httpGetBody(url: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 2500; readTimeout = 2500; requestMethod = "GET"
        }
        val code = conn.responseCode
        val body = if (code in 200..299) conn.inputStream.bufferedReader().readText() else null
        conn.disconnect()
        body
    } catch (e: Exception) {
        null
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
