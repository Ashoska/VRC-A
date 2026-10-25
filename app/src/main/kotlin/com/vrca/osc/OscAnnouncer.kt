package com.vrca.osc

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/**
 * Headset: announce VRC-A to VRChat as an OSCQuery app so VRChat SENDS us its
 * output. Device-proven (Quest 3, 2026-10-25): polling `/tracking/vrsystem/.../pose`
 * only ever reads `[null]`, but once VRC-A advertises `_oscjson._tcp` and lists the
 * addresses it wants, VRChat shows "Sending OSC data to VRCA-…" and pushes head +
 * wrist poses (~75/s each) AND every avatar parameter (Gesture*, Velocity*, …) to our
 * UDP port. Feeds [HandDrag]; avatar params are kept here only (not folded into
 * [VrcaOscState], which still polls).
 *
 * Stable identity (device-proven fix): stopping then starting with a NEW name/ports
 * left VRChat "sending" to the old address (0 packets until VRChat restarted). The
 * service name and both ports are saved per install and re-bound on every start, so
 * VRChat's remembered address stays valid.
 */
object OscAnnouncer {

    private const val TAG = "OscAnnouncer"
    private const val PREFS = "osc_announce"

    @Volatile var running = false
        private set
    private var udp: DatagramSocket? = null
    private var http: ServerSocket? = null
    private var nsd: NsdManager? = null
    private val registrations = mutableListOf<NsdManager.RegistrationListener>()

    @Volatile private var udpPort = 0
    @Volatile private var httpPort = 0
    @Volatile private var ownIp = ""
    @Volatile private var serviceName = ""
    @Volatile private var registerStatus = ""
    @Volatile private var httpRequests = 0
    @Volatile private var lastHttpPath = ""
    @Volatile private var packets = 0L
    @Volatile private var lastAddress = ""
    @Volatile private var startedMs = 0L
    private val counts = ConcurrentHashMap<String, Int>()
    private val poses = ConcurrentHashMap<String, FloatArray>()
    private val poseMs = ConcurrentHashMap<String, Long>()
    private val params = ConcurrentHashMap<String, Float>()

    /** Latest pushed pose for `head` / `leftwrist` / `rightwrist` (x y z pitch yaw roll,
     *  meters + degrees), or null if none arrived within [maxAgeMs]. */
    fun pose(part: String, maxAgeMs: Long = 500): FloatArray? {
        val at = poseMs[part] ?: return null
        return if (System.currentTimeMillis() - at <= maxAgeMs) poses[part] else null
    }

    /** Latest pushed avatar parameter as a float (bools 0/1), or null. */
    fun param(name: String): Float? = params[name]

    fun start(context: Context) {
        if (running) return
        running = true
        startedMs = System.currentTimeMillis()
        packets = 0; httpRequests = 0; counts.clear(); poses.clear(); poseMs.clear(); params.clear()
        lastAddress = ""; lastHttpPath = ""; registerStatus = "registering…"
        ownIp = localIpv4() ?: "127.0.0.1"
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        serviceName = prefs.getString("name", null) ?: ("VRCA-" + (1000..9999).random())
        val u = runCatching { bindUdp(prefs.getInt("udp", 0)) }.getOrElse { bindUdp(0) }
        val h = runCatching { bindHttp(prefs.getInt("http", 0)) }.getOrElse { bindHttp(0) }
        udp = u; http = h
        udpPort = u.localPort; httpPort = h.localPort
        prefs.edit().putString("name", serviceName).putInt("udp", udpPort).putInt("http", httpPort).apply()
        Thread({ udpLoop(u) }, "vrca-announce-udp").apply { isDaemon = true; start() }
        Thread({ httpLoop(h) }, "vrca-announce-http").apply { isDaemon = true; start() }
        register(context, "_oscjson._tcp.", httpPort)
        register(context, "_osc._udp.", udpPort)
    }

    private fun bindUdp(port: Int) = DatagramSocket(null).apply {
        reuseAddress = true; soTimeout = 2000; bind(InetSocketAddress(port))
    }

    private fun bindHttp(port: Int) = ServerSocket().apply {
        reuseAddress = true; bind(InetSocketAddress(port))
    }

    fun stop() {
        running = false
        val m = nsd
        synchronized(registrations) {
            registrations.forEach { l -> runCatching { m?.unregisterService(l) } }
            registrations.clear()
        }
        runCatching { udp?.close() }; runCatching { http?.close() }
        udp = null; http = null
        registerStatus = "stopped"
    }

    private fun register(context: Context, type: String, port: Int) {
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: run {
            registerStatus = "NsdManager unavailable"; return
        }
        nsd = manager
        val info = NsdServiceInfo().apply { serviceName = this@OscAnnouncer.serviceName; serviceType = type; setPort(port) }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { registerStatus += "\nregistered $type as ${i.serviceName}" }
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) { registerStatus += "\n$type register failed err=$e" }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        synchronized(registrations) { registrations += listener }
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { registerStatus += "\n$type register error: ${it.message}" }
    }

    // ---- OSCQuery HTTP (HOST_INFO + root tree) ---------------------------------

    private fun httpLoop(server: ServerSocket) {
        while (running) {
            val s = try { server.accept() } catch (e: Exception) { break }
            runCatching {
                s.soTimeout = 2000
                s.use { sock ->
                    val reader = sock.getInputStream().bufferedReader()
                    val requestLine = reader.readLine() ?: return@use
                    while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }
                    val path = requestLine.split(' ').getOrNull(1) ?: "/"
                    httpRequests++; lastHttpPath = path
                    val body = if (path.contains("HOST_INFO")) hostInfo() else tree()
                    val bytes = body.toByteArray()
                    val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    sock.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
                }
            }.onFailure { Log.w(TAG, "http request failed", it) }
        }
    }

    private fun hostInfo(): String = JSONObject().apply {
        put("NAME", serviceName)
        put("OSC_IP", ownIp)
        put("OSC_PORT", udpPort)
        put("OSC_TRANSPORT", "UDP")
        put("EXTENSIONS", JSONObject().apply {
            put("ACCESS", true); put("CLIPMODE", false); put("RANGE", true); put("TYPE", true); put("VALUE", true)
        })
    }.toString()

    private fun node(path: String, type: String? = null, kids: Map<String, JSONObject> = emptyMap()) = JSONObject().apply {
        put("FULL_PATH", path)
        put("ACCESS", if (type == null) 0 else 2)
        if (type != null) put("TYPE", type)
        if (kids.isNotEmpty()) put("CONTENTS", JSONObject().apply { kids.forEach { (k, v) -> put(k, v) } })
    }

    private fun tree(): String {
        fun pose(part: String) = node(
            "/tracking/vrsystem/$part", kids = mapOf("pose" to node("/tracking/vrsystem/$part/pose", "ffffff"))
        )
        return node(
            "/", kids = mapOf(
                "avatar" to node("/avatar", kids = mapOf("change" to node("/avatar/change", "s"))),
                "tracking" to node(
                    "/tracking", kids = mapOf(
                        "vrsystem" to node(
                            "/tracking/vrsystem",
                            kids = mapOf("head" to pose("head"), "leftwrist" to pose("leftwrist"), "rightwrist" to pose("rightwrist"))
                        )
                    )
                )
            )
        ).toString()
    }

    // ---- OSC UDP receive (messages + bundles) ----------------------------------

    private fun udpLoop(sock: DatagramSocket) {
        val buf = ByteArray(8192)
        while (running) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                sock.receive(packet)
                packets++
                runCatching { parse(packet.data, packet.offset, packet.offset + packet.length) }
            } catch (_: SocketTimeoutException) {
            } catch (e: Exception) { if (running) Log.w(TAG, "udp receive", e) }
        }
    }

    private fun parse(d: ByteArray, start: Int, end: Int) {
        var p = start
        fun str(): String? {
            val s = p
            while (p < end && d[p].toInt() != 0) p++
            if (p >= end) return null
            val out = String(d, s, p - s, Charsets.UTF_8)
            val used = p - s + 1
            p = s + used + ((4 - used % 4) % 4)
            return out
        }
        fun int(): Int {
            if (p + 4 > end) { p = end; return 0 }
            val v = ((d[p].toInt() and 0xff) shl 24) or ((d[p + 1].toInt() and 0xff) shl 16) or
                ((d[p + 2].toInt() and 0xff) shl 8) or (d[p + 3].toInt() and 0xff)
            p += 4
            return v
        }
        val address = str() ?: return
        if (address == "#bundle") {
            p += 8 // time tag
            while (p + 4 <= end) {
                val size = int()
                if (size <= 0 || p + size > end) return
                parse(d, p, p + size)
                p += size
            }
            return
        }
        lastAddress = address
        val isParam = address.startsWith("/avatar/parameters/")
        counts.merge(if (isParam) "/avatar/parameters/*" else address, 1, Int::plus)
        val tags = str() ?: return
        if (address.startsWith("/tracking/vrsystem/")) {
            val floats = tags.drop(1).filter { it == 'f' }.map { Float.fromBits(int()) }
            val part = address.removePrefix("/tracking/vrsystem/").removeSuffix("/pose")
            poses[part] = floats.toFloatArray()
            poseMs[part] = System.currentTimeMillis()
        } else if (isParam) {
            val v = when (tags.getOrNull(1)) {
                'f' -> Float.fromBits(int())
                'i' -> int().toFloat()
                'T' -> 1f
                'F' -> 0f
                else -> null
            }
            if (v != null) params[address.removePrefix("/avatar/parameters/")] = v
        }
    }

    // ---- diag ------------------------------------------------------------------

    fun diag(): String = buildString {
        if (!running && startedMs == 0L) { append("(not started)"); return@buildString }
        append(if (running) "ANNOUNCING" else "stopped").append(" as ").append(serviceName)
        append("\nVRC-A OSCQuery http://").append(ownIp).append(':').append(httpPort)
        append("  OSC in ").append(ownIp).append(':').append(udpPort)
        append(registerStatus.prependIndent("  ").let { "\n" + it.trimStart('\n') })
        append("\nVRChat read our tree: ").append(httpRequests).append(" requests")
        if (lastHttpPath.isNotEmpty()) append(" (last ").append(lastHttpPath).append(')')
        append("\npackets received: ").append(packets)
        if (lastAddress.isNotEmpty()) append("  last ").append(lastAddress)
        counts.entries.sortedBy { it.key }.forEach { (k, v) -> append("\n  ").append(k).append(" x").append(v) }
        poses.entries.sortedBy { it.key }.forEach { (k, v) ->
            append("\n").append(k).append(": ").append(v.joinToString(" ") { "%.3f".format(it) })
        }
    }

    private fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }.getOrNull()
}
