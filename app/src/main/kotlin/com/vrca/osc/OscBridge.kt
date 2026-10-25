package com.vrca.osc

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import android.util.Log
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.SocketTimeoutException

/**
 * Headset, runs ONLY in the `:oscbridge` process ([OscBridgeService]): owns VRChat's
 * side of the OSC push and never lets it drop while VRChat runs.
 *
 * Why a separate process (device tests 2026-10-25/26): VRChat connects to the first
 * VRC-A per VRChat session, but the moment VRC-A's receive socket closes, even for 3 s
 * with the same port re-bound, no goodbye, and multicast so no "port unreachable" (a
 * self-test proved the re-opened socket worked), VRChat stops sending until VRChat
 * restarts. So the socket must outlive the main app: this small process holds the UDP
 * receive socket, the OSCQuery HTTP server VRChat reads ([hostInfo]/[tree]) and the
 * mDNS announcer ([MdnsAnnouncer], never says goodbye, stable name + ports), and relays
 * every datagram raw to the main process ([OscAnnouncer]) over a local socket. The main
 * app can be swiped, crash or be OS-killed; when it comes back it reconnects here and
 * the data flows again, VRChat never notices. Only an app update (all processes end)
 * still needs one VRChat restart.
 *
 * Relay frames: [type:1][length:4][payload]: type 0 = one OSC datagram, 1 = status JSON
 * (once a second). Only our own uid may connect. The main process sends back type 2 =
 * hand-drag state ("host\nport\nmoving"): VRChat's `/input` axes are sticky, so when
 * the main process dies mid-drag the bridge sends the zeros ([stopDrag]), otherwise
 * the player keeps moving until VRC-A is reopened.
 */
object OscBridge {

    private const val TAG = "OscBridge"
    private const val PREFS = "osc_announce"
    private const val IDLE_STOP_MS = 10 * 60_000L

    @Volatile var running = false
        private set
    private var udp: DatagramSocket? = null
    private var http: ServerSocket? = null
    private var appContext: Context? = null

    @Volatile private var udpPort = 0
    @Volatile private var httpPort = 0
    @Volatile private var ownIp = ""
    @Volatile private var serviceName = ""
    @Volatile private var portNote = ""
    @Volatile private var httpRequests = 0
    @Volatile private var lastHttpPath = ""
    @Volatile private var packets = 0L
    @Volatile private var relayed = 0L
    @Volatile private var lastPacketMs = 0L
    @Volatile private var startedMs = 0L
    @Volatile private var clientSinceMs = 0L
    @Volatile private var dragHost = ""
    @Volatile private var dragPort = 0
    @Volatile private var dragMoving = false
    @Volatile private var dragStops = 0

    private val clientLock = Any()
    private var client: LocalSocket? = null
    private var clientOut: DataOutputStream? = null

    /** VRChat sent us something in the last 10 s. */
    val vrchatConnected: Boolean get() = System.currentTimeMillis() - lastPacketMs < 10_000L

    /** Local socket name both processes use (abstract namespace, per package). */
    fun relayName(context: Context) = context.packageName + ".oscbridge"

    @Synchronized
    fun start(context: Context, onIdle: () -> Unit) {
        if (running) return
        running = true
        appContext = context.applicationContext
        startedMs = System.currentTimeMillis()
        ownIp = localIpv4() ?: "127.0.0.1"
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Stable identity: same name + ports every start (see MdnsAnnouncer).
        serviceName = prefs.getString("name", null) ?: ("VRCA-" + (1000..9999).random())
        val savedUdp = prefs.getInt("udp", 0); val savedHttp = prefs.getInt("http", 0)
        val u = runCatching { bindUdp(savedUdp) }.getOrElse { bindUdp(0) }
        val h = runCatching { bindHttp(savedHttp) }.getOrElse { bindHttp(0) }
        udp = u; http = h
        udpPort = u.localPort; httpPort = h.localPort
        portNote = "saved udp $savedUdp http $savedHttp → bound udp $udpPort http $httpPort"
        prefs.edit().putString("name", serviceName).putInt("udp", udpPort).putInt("http", httpPort).apply()
        Thread({ udpLoop(u) }, "vrca-bridge-udp").apply { isDaemon = true; start() }
        Thread({ httpLoop(h) }, "vrca-bridge-http").apply { isDaemon = true; start() }
        Thread({ relayAcceptLoop(context) }, "vrca-bridge-relay").apply { isDaemon = true; start() }
        Thread({ statusLoop(onIdle) }, "vrca-bridge-status").apply { isDaemon = true; start() }
        MdnsAnnouncer.start(context, serviceName, httpPort, udpPort) { localIpv4() ?: ownIp.ifEmpty { null } }
    }

    private fun bindUdp(port: Int) = DatagramSocket(null).apply {
        reuseAddress = true; soTimeout = 2000; bind(InetSocketAddress(port))
    }

    private fun bindHttp(port: Int) = ServerSocket().apply {
        reuseAddress = true; bind(InetSocketAddress(port))
    }

    // ---- relay to the main process ---------------------------------------------

    private fun relayAcceptLoop(context: Context) {
        val server = runCatching { LocalServerSocket(relayName(context)) }
            .getOrElse { Log.w(TAG, "relay server failed", it); return }
        while (running) {
            val s = try { server.accept() } catch (e: Exception) { if (running) Log.w(TAG, "relay accept", e); continue }
            // Only VRC-A itself (same uid) gets the stream.
            val uid = runCatching { s.peerCredentials.uid }.getOrDefault(-1)
            if (uid != Process.myUid()) { runCatching { s.close() }; continue }
            synchronized(clientLock) {
                runCatching { client?.close() }
                client = s
                clientOut = DataOutputStream(s.outputStream.buffered())
                clientSinceMs = System.currentTimeMillis()
            }
            Thread({ clientReadLoop(s) }, "vrca-bridge-client").apply { isDaemon = true; start() }
        }
    }

    /** Reads the main process's frames; its end (crash, swipe, kill) = EOF here. */
    private fun clientReadLoop(s: LocalSocket) {
        runCatching {
            val input = DataInputStream(s.inputStream.buffered())
            while (running) {
                val type = input.readUnsignedByte()
                val len = input.readInt()
                if (len < 0 || len > 4096) break
                val buf = ByteArray(len); input.readFully(buf)
                if (type == 2) {
                    val p = String(buf, Charsets.UTF_8).split('\n')
                    dragHost = p.getOrNull(0).orEmpty()
                    dragPort = p.getOrNull(1)?.toIntOrNull() ?: 0
                    dragMoving = p.getOrNull(2) == "1"
                }
            }
        }
        synchronized(clientLock) {
            if (client === s) { runCatching { s.close() }; client = null; clientOut = null; clientSinceMs = 0L }
        }
        if (dragMoving) stopDrag()
    }

    /** Main process died mid-drag: put VRChat's movement axes back to 0. */
    private fun stopDrag() {
        dragMoving = false
        val host = dragHost.ifEmpty { "127.0.0.1" }; val port = if (dragPort > 0) dragPort else 9000
        runCatching {
            val out = com.illposed.osc.transport.udp.OSCPortOut(java.net.InetAddress.getByName(host), port)
            repeat(3) {
                out.send(com.illposed.osc.OSCMessage("/input/Vertical", listOf(0f)))
                out.send(com.illposed.osc.OSCMessage("/input/Horizontal", listOf(0f)))
            }
            out.close()
            dragStops++
        }.onFailure { Log.w(TAG, "drag stop failed", it) }
    }

    /** Returns true when the frame reached a connected main process. */
    private fun send(type: Int, payload: ByteArray, off: Int = 0, len: Int = payload.size): Boolean {
        synchronized(clientLock) {
            val out = clientOut ?: return false
            return try {
                out.writeByte(type); out.writeInt(len); out.write(payload, off, len); out.flush()
                true
            } catch (_: Exception) {
                // Main process gone: drop it; it reconnects when it comes back.
                runCatching { client?.close() }
                client = null; clientOut = null; clientSinceMs = 0L
                false
            }
        }
    }

    private fun statusLoop(onIdle: () -> Unit) {
        while (running) {
            try { Thread.sleep(1_000) } catch (_: InterruptedException) { break }
            send(1, status().toByteArray())
            val now = System.currentTimeMillis()
            val noClient = synchronized(clientLock) { client == null }
            // Main app gone AND VRChat silent for a while: nothing to hold, stop.
            if (noClient && now - maxOf(lastPacketMs, startedMs) > IDLE_STOP_MS) { onIdle(); break }
        }
    }

    private fun status(): String = JSONObject().apply {
        put("name", serviceName)
        put("ip", ownIp)
        put("udp", udpPort)
        put("http", httpPort)
        put("portNote", portNote)
        put("httpRequests", httpRequests)
        put("lastHttpPath", lastHttpPath)
        put("packets", packets)
        put("relayed", relayed)
        put("upSec", (System.currentTimeMillis() - startedMs) / 1000)
        put("pid", Process.myPid())
        put("dragStops", dragStops)
        put("mdns", MdnsAnnouncer.diag())
    }.toString()

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
        // Fresh every request: a Wi-Fi change must not hand VRChat a stale address.
        ownIp = localIpv4() ?: ownIp
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

    // ---- OSC UDP receive → relay ------------------------------------------------

    private fun udpLoop(sock: DatagramSocket) {
        val buf = ByteArray(8192)
        while (running && !sock.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                sock.receive(packet)
                packets++
                lastPacketMs = System.currentTimeMillis()
                if (send(0, packet.data, packet.offset, packet.length)) relayed++
            } catch (_: SocketTimeoutException) {
            } catch (e: Exception) { if (running) Log.w(TAG, "udp receive", e) }
        }
    }

    private fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }.getOrNull()
}
