package com.vrca.osc

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * Headset: our own minimal mDNS announcer for [OscAnnouncer], replacing Android's
 * NsdManager. Why (vrc-oscquery-lib `MeaModDiscovery` + device tests 2026-10-25):
 * VRChat only forgets an OSCQuery app on an mDNS GOODBYE, and once it has forgotten
 * one it won't pick up a returning app until VRChat restarts. NsdManager sends that
 * goodbye whenever our process ends (swipe, OS kill, update), which broke the push
 * until a VRChat restart. This announcer NEVER sends a goodbye: with a stable name and
 * stable ports, VRChat keeps its entry through a VRC-A restart and keeps sending to the
 * same port, which the new process re-binds.
 *
 * Packet shape (what `MeaModDiscovery.OnRemoteServiceInfo` matches): a response whose
 * answers include a PTR named `_oscjson._tcp.local` / `_osc._udp.local`, the SRV
 * records (instance → port, target host) and the host's A record. Sent unsolicited
 * every [ANNOUNCE_MS] (a VRChat that starts later finds us within seconds) and right
 * away when a query for `_osc*` arrives (if port 5353 could be bound).
 */
object MdnsAnnouncer {

    private const val TAG = "MdnsAnnouncer"
    private const val GROUP = "224.0.0.251"
    private const val PORT = 5353
    private const val ANNOUNCE_MS = 5_000L
    private const val TTL_SEC = 4500

    @Volatile private var running = false
    @Volatile private var sent = 0
    @Volatile private var answered = 0
    @Volatile private var bound5353 = false
    @Volatile private var lastError = ""
    private var socket: MulticastSocket? = null
    private var lock: WifiManager.MulticastLock? = null

    private var name = ""
    private var httpPort = 0
    private var udpPort = 0
    private var ipSupplier: () -> String? = { null }

    @Synchronized
    fun start(context: Context, serviceName: String, oscQueryHttpPort: Int, oscUdpPort: Int, ip: () -> String?) {
        if (running) return
        running = true
        name = serviceName; httpPort = oscQueryHttpPort; udpPort = oscUdpPort; ipSupplier = ip
        runCatching {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            lock = wm?.createMulticastLock("vrca-mdns")?.apply { setReferenceCounted(false); acquire() }
        }.onFailure { lastError = "multicast lock: ${it.message}" }
        Thread({ loop() }, "vrca-mdns").apply { isDaemon = true; start() }
    }

    private fun openSocket(): MulticastSocket {
        val s = runCatching {
            MulticastSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(PORT)) }.also { bound5353 = true }
        }.getOrElse {
            bound5353 = false
            lastError = "5353 busy (${it.message}); announcing only"
            MulticastSocket(0)
        }
        s.timeToLive = 255
        wifiInterface()?.let { ni ->
            runCatching { s.networkInterface = ni }
            if (bound5353) runCatching { s.joinGroup(InetSocketAddress(GROUP, PORT), ni) }
                .onFailure { lastError = "joinGroup: ${it.message}" }
        }
        return s
    }

    private fun loop() {
        val s = runCatching { openSocket() }.getOrElse { lastError = "socket: ${it.message}"; running = false; return }
        socket = s
        if (bound5353) Thread({ receiveLoop(s) }, "vrca-mdns-rx").apply { isDaemon = true; start() }
        // A burst first (VRChat may be mid-start), then steady.
        var n = 0
        while (running) {
            announce(s)
            n++
            try { Thread.sleep(if (n < 3) 1_000L else ANNOUNCE_MS) } catch (_: InterruptedException) { break }
        }
    }

    private fun receiveLoop(s: MulticastSocket) {
        val buf = ByteArray(9000)
        while (running) {
            val p = DatagramPacket(buf, buf.size)
            try {
                s.receive(p)
                // A query (QR bit clear) that mentions an _osc service → answer now.
                if (p.length > 12 && (buf[p.offset + 2].toInt() and 0x80) == 0 && containsOsc(buf, p.offset, p.length)) {
                    announce(s); answered++
                }
            } catch (e: Exception) {
                if (running) { lastError = "receive: ${e.message}"; Thread.sleep(1_000) }
            }
        }
    }

    private fun containsOsc(d: ByteArray, off: Int, len: Int): Boolean {
        val needle = "_osc".toByteArray()
        val end = off + len - needle.size
        var i = off + 12
        while (i <= end) {
            var hit = true
            for (j in needle.indices) if ((d[i + j].toInt() or 0x20) != (needle[j].toInt() or 0x20)) { hit = false; break }
            if (hit) return true
            i++
        }
        return false
    }

    private fun announce(s: MulticastSocket) {
        val ip = ipSupplier() ?: return
        runCatching {
            val bytes = buildResponse(ip)
            s.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(GROUP), PORT))
            sent++
        }.onFailure { lastError = "send: ${it.message}"; Log.w(TAG, "announce failed", it) }
    }

    // ---- DNS response (RFC 6762/6763), no name compression --------------------

    private fun buildResponse(ip: String): ByteArray {
        val host = "$name.local"
        val out = ByteArrayOutputStream()
        val answers = mutableListOf<ByteArray>()
        for ((type, port) in listOf("_oscjson._tcp.local" to httpPort, "_osc._udp.local" to udpPort)) {
            val instance = "$name.$type"
            answers += record(type, 12, 1, nameBytes(instance))                       // PTR (shared)
            answers += record(instance, 33, 0x8001, srv(port, host))                    // SRV
            answers += record(instance, 16, 0x8001, byteArrayOf(0))                      // TXT (empty)
        }
        answers += record(host, 1, 0x8001, (InetAddress.getByName(ip) as Inet4Address).address) // A
        // Header: id 0, flags 0x8400 (authoritative response), 0 questions.
        out.write(byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 0, 0, answers.size.toByte(), 0, 0, 0, 0))
        answers.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun nameBytes(n: String): ByteArray {
        val o = ByteArrayOutputStream()
        n.split('.').filter { it.isNotEmpty() }.forEach { label ->
            val b = label.toByteArray(Charsets.UTF_8)
            o.write(b.size); o.write(b)
        }
        o.write(0)
        return o.toByteArray()
    }

    private fun srv(port: Int, target: String): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(byteArrayOf(0, 0, 0, 0)) // priority, weight
        o.write(port shr 8); o.write(port and 0xff)
        o.write(nameBytes(target))
        return o.toByteArray()
    }

    private fun record(owner: String, type: Int, cls: Int, rdata: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(nameBytes(owner))
        o.write(type shr 8); o.write(type and 0xff)
        o.write(cls shr 8); o.write(cls and 0xff)
        o.write(TTL_SEC ushr 24); o.write((TTL_SEC shr 16) and 0xff); o.write((TTL_SEC shr 8) and 0xff); o.write(TTL_SEC and 0xff)
        o.write(rdata.size shr 8); o.write(rdata.size and 0xff)
        o.write(rdata)
        return o.toByteArray()
    }

    private fun wifiInterface(): NetworkInterface? = runCatching {
        val ip = ipSupplier() ?: return null
        NetworkInterface.getByInetAddress(InetAddress.getByName(ip))
    }.getOrNull()

    fun diag(): String = buildString {
        append("mDNS: ").append(if (running) "announcing" else "off")
        append(" (port 5353 ").append(if (bound5353) "bound" else "not bound").append(")")
        append("  sent ").append(sent).append("  answered queries ").append(answered)
        if (lastError.isNotEmpty()) append("\nmDNS note: ").append(lastError)
    }
}
