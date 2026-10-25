package com.vrca.osc

import android.content.Context
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
 * UDP port. Pushed params/avatar info feed [VrcaOscState]; poses feed [HandDrag].
 *
 * Keeping the push across VRC-A restarts (vrc-oscquery-lib `MeaModDiscovery` +
 * device tests 2026-10-25): VRChat finds the FIRST VRC-A per VRChat session whichever
 * opens first, but once an app it knew says goodbye it ignores every returning app
 * (any name, re-announced 5x) until VRChat restarts; it only re-searches on its own
 * start or a network-adapter change. Android's NsdManager says that goodbye whenever
 * our process ends. So [MdnsAnnouncer] announces instead and NEVER says goodbye, under
 * a STABLE name with STABLE ports (saved per install): VRChat keeps its entry and keeps
 * sending to the same port, which a restarted VRC-A (reopen, OS-kill revival, update)
 * re-binds. Started once per process from `VrcaApplication`; the debug button only
 * pauses/resumes.
 */
object OscAnnouncer {

    private const val TAG = "OscAnnouncer"
    private const val PREFS = "osc_announce"

    @Volatile var running = false
        private set
    /** Soft stop: still announced and bound, readers just see nothing. */
    @Volatile var paused = false
        private set
    @Volatile private var portNote = ""

    // ---- multicast receive (test) ----------------------------------------------
    // Suspected reason a reopened VRC-A never gets the push back (device-tested: no
    // goodbye, same name, same port re-bound, still 0 packets): while VRC-A is down,
    // VRChat keeps sending to a port nothing is bound to, the kernel answers each
    // datagram with "port unreachable", and VRChat's sender stops for the session.
    // Linux never sends "port unreachable" for MULTICAST destinations, so in this mode
    // HOST_INFO gives VRChat the group [MULTICAST_GROUP] (+ our port) as OSC_IP and
    // we join it: while VRC-A is down the datagrams are just dropped, silently.
    // Switching modes only reaches VRChat on its next start (it reads HOST_INFO once).
    private const val MULTICAST_GROUP = "239.255.65.82"
    @Volatile private var multicastMode = true
    @Volatile private var blinkNote = ""
    @Volatile private var selfTestSeenMs = 0L
    @Volatile private var selfTestNote = ""

    /** Debug: send ourselves one OSC packet to the address VRChat sends to (group or
     *  our IP + our port) and report whether the receive socket got it within 2 s.
     *  Separates "our socket isn't receiving" from "VRChat stopped sending".
     *  Blocking: run off the main thread. */
    fun selfTest(): String {
        if (!running) return "not running".also { selfTestNote = it }
        val sentAt = System.currentTimeMillis()
        val dest = if (multicastMode) MULTICAST_GROUP else (localIpv4() ?: ownIp)
        val note = runCatching {
            // OSC message "/vrca/selftest" with no arguments (address + ",").
            val msg = "/vrca/selftest".toByteArray().let { a -> a + ByteArray(4 - a.size % 4) } + ",\u0000\u0000\u0000".toByteArray()
            java.net.MulticastSocket().use { out ->
                localIpv4()?.let { ip -> runCatching { out.networkInterface = NetworkInterface.getByInetAddress(java.net.InetAddress.getByName(ip)) } }
                out.send(DatagramPacket(msg, msg.size, java.net.InetAddress.getByName(dest), udpPort))
            }
            Thread.sleep(2_000)
            if (selfTestSeenMs >= sentAt) "self-test to $dest:$udpPort RECEIVED (our socket works)"
            else "self-test to $dest:$udpPort NOT received (our socket isn't receiving)"
        }.getOrElse { "self-test failed: ${it.message}" }
        selfTestNote = note
        return note
    }

    /** Debug switch; persisted. VRChat picks the new destination up on its next start. */
    fun setMulticast(context: Context, on: Boolean) {
        multicastMode = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("multicast", on).apply()
    }
    val isMulticast: Boolean get() = multicastMode
    private var udp: DatagramSocket? = null
    private var http: ServerSocket? = null

    @Volatile private var udpPort = 0
    @Volatile private var httpPort = 0
    @Volatile private var ownIp = ""
    @Volatile private var serviceName = ""
    @Volatile private var httpRequests = 0
    @Volatile private var lastHttpPath = ""
    @Volatile private var packets = 0L
    @Volatile private var lastAddress = ""
    @Volatile private var startedMs = 0L
    @Volatile private var lastHttpMs = 0L
    @Volatile private var lastPacketMs = 0L
    /** VRChat service (by its OSCQuery name) that last sent us a packet. */
    @Volatile private var pushVrchat: String? = null

    /** True while the VRChat instance that's running now is pushing to us, so its
     *  avatar params / avatar info arrive the moment they change. VRChat only sends a
     *  param on change, so silence here doesn't mean "dead": the poll stays on (slower). */
    val pushLive: Boolean get() {
        val v = pushVrchat ?: return false
        return running && !paused && v == VrcaOscQuery.vrchatServiceName()
    }
    private const val STUCK_MS = 10_000L

    /** VRChat read our OSCQuery info more than [STUCK_MS] ago but has sent nothing since. */
    val stuck: Boolean get() {
        val read = lastHttpMs
        return read > 0L && lastPacketMs < read && System.currentTimeMillis() - read > STUCK_MS
    }
    private val counts = ConcurrentHashMap<String, Int>()
    private val poses = ConcurrentHashMap<String, FloatArray>()
    private val poseMs = ConcurrentHashMap<String, Long>()
    private val params = ConcurrentHashMap<String, Float>()
    private val others = ConcurrentHashMap<String, String>()

    /** Latest pushed pose for `head` / `leftwrist` / `rightwrist` (x y z pitch yaw roll,
     *  meters + degrees), or null if none arrived within [maxAgeMs]. */
    fun pose(part: String, maxAgeMs: Long = 500): FloatArray? {
        if (paused) return null
        val at = poseMs[part] ?: return null
        return if (System.currentTimeMillis() - at <= maxAgeMs) poses[part] else null
    }

    /** Latest pushed avatar parameter as a float (bools 0/1), or null. */
    fun param(name: String): Float? = if (paused) null else params[name]

    /** Debug button: first tap starts; later taps pause/resume (never re-announce). */
    fun toggle(context: Context) {
        if (!running) start(context) else paused = !paused
    }

    @Synchronized
    fun start(context: Context) {
        paused = false
        if (running) return
        running = true
        startedMs = System.currentTimeMillis()
        packets = 0; httpRequests = 0; counts.clear(); poses.clear(); poseMs.clear(); params.clear(); others.clear()
        lastAddress = ""; lastHttpPath = ""
        ownIp = localIpv4() ?: "127.0.0.1"
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Stable identity: the same name + ports every start, so VRChat's entry for us
        // (never withdrawn, see the class note) stays valid across restarts.
        serviceName = prefs.getString("name", null) ?: ("VRCA-" + (1000..9999).random())
        val savedUdp = prefs.getInt("udp", 0); val savedHttp = prefs.getInt("http", 0)
        multicastMode = prefs.getBoolean("multicast", true)
        val u = runCatching { bindUdp(savedUdp) }.getOrElse { bindUdp(0) }
        val h = runCatching { bindHttp(savedHttp) }.getOrElse { bindHttp(0) }
        udp = u; http = h
        udpPort = u.localPort; httpPort = h.localPort
        portNote = "saved udp $savedUdp http $savedHttp → bound udp $udpPort http $httpPort"
        prefs.edit().putString("name", serviceName).putInt("udp", udpPort).putInt("http", httpPort).apply()
        Thread({ udpLoop(u) }, "vrca-announce-udp").apply { isDaemon = true; start() }
        Thread({ httpLoop(h) }, "vrca-announce-http").apply { isDaemon = true; start() }
        MdnsAnnouncer.start(context, serviceName, httpPort, udpPort) { localIpv4() ?: ownIp.ifEmpty { null } }
    }

    private fun bindUdp(port: Int): DatagramSocket {
        if (!multicastMode) {
            return DatagramSocket(null).apply { reuseAddress = true; soTimeout = 2000; bind(InetSocketAddress(port)) }
        }
        val s = java.net.MulticastSocket(null).apply { reuseAddress = true; soTimeout = 2000; bind(InetSocketAddress(port)) }
        val ni = localIpv4()?.let { runCatching { NetworkInterface.getByInetAddress(java.net.InetAddress.getByName(it)) }.getOrNull() }
        s.joinGroup(InetSocketAddress(MULTICAST_GROUP, s.localPort), ni)
        return s
    }

    /** Debug "port blink": close the receive socket for [ms], then re-bind the SAME port
     *  (and rejoin the group). Packets coming back afterwards = a dead port alone doesn't
     *  stop VRChat's sending (in this mode). Blocking: run off the main thread. */
    fun blinkPort(ms: Long = 3_000L) {
        if (!running) return
        val port = udpPort
        val before = packets
        runCatching { udp?.close() }
        blinkNote = "port $port closed for ${ms / 1000}s…"
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
        val u = runCatching { bindUdp(port) }.getOrElse { blinkNote = "re-bind failed: ${it.message}"; return }
        udp = u
        Thread({ udpLoop(u) }, "vrca-announce-udp").apply { isDaemon = true; start() }
        val reopenedAt = System.currentTimeMillis()
        blinkNote = "re-bound $port after ${ms / 1000}s (packets before: $before); watch the count"
        Thread({
            try { Thread.sleep(1_000) } catch (_: InterruptedException) {}
            selfTest()
            try { Thread.sleep(7_000) } catch (_: InterruptedException) {}
            val fresh = lastPacketMs > reopenedAt && packets - before > 1
            blinkNote = "blink $port: " + (if (fresh) "VRChat packets CAME BACK (${packets - before} since)" else "NO VRChat packets in 10 s after re-bind") +
                "; " + selfTestNote
        }, "vrca-blink-check").apply { isDaemon = true; start() }
    }

    private fun bindHttp(port: Int) = ServerSocket().apply {
        reuseAddress = true; bind(InetSocketAddress(port))
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
                    lastHttpMs = System.currentTimeMillis()
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
        // Fresh every request: a Wi-Fi change must not hand VRChat a stale address.
        ownIp = localIpv4() ?: ownIp
        put("OSC_IP", if (multicastMode) MULTICAST_GROUP else ownIp)
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
        while (running && !sock.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                sock.receive(packet)
                packets++
                lastPacketMs = System.currentTimeMillis()
                pushVrchat = VrcaOscQuery.vrchatServiceName()
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
        if (address == "/vrca/selftest") { selfTestSeenMs = System.currentTimeMillis(); return }
        lastAddress = address
        val isParam = address.startsWith("/avatar/parameters/")
        counts.merge(if (isParam) "/avatar/parameters/*" else address, 1, Int::plus)
        val tags = str() ?: return
        if (address.startsWith("/tracking/vrsystem/")) {
            val floats = tags.drop(1).filter { it == 'f' }.map { Float.fromBits(int()) }
            val part = address.removePrefix("/tracking/vrsystem/").removeSuffix("/pose")
            poses[part] = floats.toFloatArray()
            poseMs[part] = System.currentTimeMillis()
        } else {
            // First argument, typed like the OSCQuery poll delivers it.
            val v: Any? = when (tags.getOrNull(1)) {
                'f' -> Float.fromBits(int())
                'i' -> int()
                'T' -> true
                'F' -> false
                's' -> str()
                else -> null
            } ?: return
            if (isParam) {
                val name = address.removePrefix("/avatar/parameters/")
                params[name] = when (v) { is Boolean -> if (v) 1f else 0f; is Number -> v.toFloat(); else -> return }
                // The push IS the app's live avatar-param source (instant, on change);
                // the OSCQuery poll slows to a 1 s safety net while it's live.
                if (!paused) VrcaOscState.onParam(name, v)
            } else {
                others[address] = if (v is Float) "%.3f".format(v) else v.toString()
                // Avatar id/name, eye height + the world's size limits.
                if (!paused && address.startsWith("/avatar/")) VrcaOscState.onAvatarInfo(address, v)
            }
        }
    }

    // ---- diag ------------------------------------------------------------------

    fun diag(): String = buildString {
        if (!running && startedMs == 0L) { append("(not started)"); return@buildString }
        append(
            when {
                !running -> "STOPPED"
                paused -> "PAUSED (still registered)"
                else -> "ANNOUNCING"
            }
        ).append(" as ").append(serviceName)
        append("\nVRC-A OSCQuery http://").append(ownIp).append(':').append(httpPort)
        append("  OSC in ").append(ownIp).append(':').append(udpPort)
        if (portNote.isNotEmpty()) append("\n").append(portNote)
        append("\nreceive: ").append(if (multicastMode) "multicast $MULTICAST_GROUP:$udpPort" else "direct $ownIp:$udpPort")
        if (blinkNote.isNotEmpty()) append("\n").append(blinkNote)
        if (selfTestNote.isNotEmpty() && !blinkNote.contains(selfTestNote)) append("\n").append(selfTestNote)
        append("\n").append(MdnsAnnouncer.diag())
        append("\nVRChat read our tree: ").append(httpRequests).append(" requests")
        if (lastHttpPath.isNotEmpty()) append(" (last ").append(lastHttpPath).append(')')
        if (stuck) append("\n!! VRChat found VRC-A but is not sending")
        append("\npush live: ").append(pushLive)
        append("\npackets received: ").append(packets)
        if (lastAddress.isNotEmpty()) append("  last ").append(lastAddress)
        counts.entries.sortedBy { it.key }.forEach { (k, v) -> append("\n  ").append(k).append(" x").append(v) }
        poses.entries.sortedBy { it.key }.forEach { (k, v) ->
            append("\n").append(k).append(": ").append(v.joinToString(" ") { "%.3f".format(it) })
        }
        if (others.isNotEmpty()) {
            append("\npushed values:")
            others.entries.sortedBy { it.key }.forEach { (k, v) -> append("\n  ").append(k).append(" = ").append(v) }
        }
        if (params.isNotEmpty()) {
            append("\npushed params: ").append(params.size).append(" distinct")
            listOf("GestureLeft", "GestureRight", "VelocityX", "VelocityY", "VelocityZ", "Grounded", "AFK", "MuteSelf")
                .forEach { n -> params[n]?.let { append("\n  ").append(n).append(" = ").append("%.2f".format(it)) } }
        }
    }

    private fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }.getOrNull()
}
