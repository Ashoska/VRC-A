package com.vrca.osc

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * OSCQuery discovery PROBE (verification step, not the full client yet).
 *
 * VRChat won't PUSH avatar-parameter OSC output on Quest (only `/avatar/change`),
 * but its OSCQuery wiki says VRChat on **Android is not limited like Windows** and
 * can SERVE "all the available OSC addresses and their readable values" over HTTP.
 * So the plan is to PULL params instead of waiting for a push: discover VRChat's
 * `_oscjson._tcp` service via mDNS, then HTTP-GET its namespace JSON (which carries
 * each node's current VALUE) and poll `MuteSelf`/`AFK`/`ScaleFactor`.
 *
 * This probe just confirms the hypothesis on-device: does VRChat on THIS Quest
 * advertise an OSCQuery service, and does its JSON include readable param values?
 * Results are written to [VrcaOscState.oscQueryDiag] for the Settings -> Debug
 * readout. If it works, the full poll-based client replaces this.
 */
object VrcaOscQuery {

    private const val TAG = "VrcaOscQuery"
    private const val SERVICE_TYPE = "_oscjson._tcp."

    @Volatile private var scanning = false

    /** Kick off a one-shot mDNS discovery + HTTP probe. Safe to call repeatedly. */
    fun probe(context: Context) {
        if (scanning) return
        scanning = true
        VrcaOscState.oscQueryDiag = "scanning for $SERVICE_TYPE …"

        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) {
            VrcaOscState.oscQueryDiag = "NsdManager unavailable"
            scanning = false
            return
        }

        val found = StringBuilder()
        var resolvedAny = false

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                found.append("\nresolve failed: ${serviceInfo?.serviceName} err=$errorCode")
                VrcaOscState.oscQueryDiag = found.toString()
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                resolvedAny = true
                val host = serviceInfo.host?.hostAddress ?: "?"
                val port = serviceInfo.port
                found.append("\nRESOLVED ${serviceInfo.serviceName} @ $host:$port")
                VrcaOscState.oscQueryDiag = found.toString()
                // HTTP-probe the OSCQuery root + HOST_INFO on an IO thread.
                Thread {
                    val rootInfo = httpGet("http://$host:$port/")
                    val hostInfo = httpGet("http://$host:$port/?HOST_INFO")
                    found.append("\n  HOST_INFO: ").append(hostInfo.take(180))
                    val hasParams = rootInfo.contains("/avatar/parameters", true) ||
                        rootInfo.contains("MuteSelf", true)
                    found.append("\n  root ${rootInfo.length}B  hasParams=$hasParams")
                    // Try reading MuteSelf's node directly (its VALUE tells us pull works).
                    val mute = httpGet("http://$host:$port/avatar/parameters/MuteSelf")
                    if (mute.isNotBlank()) found.append("\n  MuteSelf node: ").append(mute.take(160))
                    VrcaOscState.oscQueryDiag = found.toString()
                }.start()
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                VrcaOscState.oscQueryDiag = "discovery start failed err=$errorCode"
                scanning = false
            }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String?) {
                found.append("discovery started")
                VrcaOscState.oscQueryDiag = found.toString()
            }
            override fun onDiscoveryStopped(serviceType: String?) {
                if (!resolvedAny) {
                    found.append("\n(no $SERVICE_TYPE services found — VRChat OSCQuery not advertised?)")
                    VrcaOscState.oscQueryDiag = found.toString()
                }
                scanning = false
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                found.append("\nfound: ${serviceInfo.serviceName}")
                VrcaOscState.oscQueryDiag = found.toString()
                runCatching { nsd.resolveService(serviceInfo, resolveListener) }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
        }

        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            // Stop the scan after 8s (mDNS answers arrive within a couple seconds).
            Thread {
                Thread.sleep(8000)
                runCatching { nsd.stopServiceDiscovery(discoveryListener) }
                scanning = false
            }.start()
        } catch (e: Exception) {
            VrcaOscState.oscQueryDiag = "discover error: ${e.message}"
            scanning = false
        }
    }

    private fun httpGet(url: String): String = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 3000; readTimeout = 3000; requestMethod = "GET"
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        conn.disconnect()
        "[$code] $body"
    } catch (e: Exception) {
        Log.w(TAG, "httpGet $url failed", e)
        "ERR ${e.javaClass.simpleName}: ${e.message}"
    }
}
