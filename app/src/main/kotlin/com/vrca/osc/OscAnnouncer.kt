package com.vrca.osc

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Headset, main process: VRChat's OSC push as the rest of the app sees it.
 *
 * VRC-A announces itself to VRChat as an OSCQuery app so VRChat SENDS it its output:
 * head + wrist poses (~75/s each, [HandDrag]) and every avatar parameter + avatar info
 * (folded into [VrcaOscState], so tokens, the Listen trigger and the size tool update the
 * instant VRChat changes them; [VrcaOscQuery] polls slower while [pushLive]).
 *
 * The connection itself lives in the `:oscbridge` process ([OscBridge] via
 * [OscBridgeService]) because VRChat stops sending for the rest of its session the
 * moment our receive socket closes, which a swipe, crash or OS kill of the main
 * process would cause. This side connects to the bridge over a local socket, reads the
 * relayed datagrams and parses them here, and reconnects (restarting the bridge if it
 * died) whenever the link drops. [start] is called once per main process from
 * `VrcaApplication`; the debug button only pauses/resumes.
 */
object OscAnnouncer {

    private const val TAG = "OscAnnouncer"

    @Volatile var running = false
        private set
    /** Soft stop: the bridge keeps the connection, readers here just see nothing. */
    @Volatile var paused = false
        private set
    private var appContext: Context? = null

    @Volatile private var linked = false
    @Volatile private var linkNote = "connecting to bridge…"
    @Volatile private var bridgeStatus: JSONObject? = null
    @Volatile private var bridgeStatusMs = 0L
    @Volatile private var packets = 0L
    @Volatile private var lastAddress = ""
    @Volatile private var lastPacketMs = 0L
    /** VRChat service (by its OSCQuery name) that last sent us a packet. */
    @Volatile private var pushVrchat: String? = null
    @Volatile private var selfTestSeenMs = 0L
    @Volatile private var selfTestNote = ""
    private val linkLock = Any()
    private var linkOut: DataOutputStream? = null
    /** Latest [reportDrag] state, re-sent to the bridge on every (re)link. */
    @Volatile private var dragState = ""

    /** True while the VRChat instance that's running now is pushing to us, so its
     *  avatar params / avatar info arrive the moment they change. VRChat only sends a
     *  param on change, so silence here doesn't mean "dead": the poll stays on (slower). */
    val pushLive: Boolean get() {
        val v = pushVrchat ?: return false
        return running && linked && !paused && v == VrcaOscQuery.vrchatServiceName()
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

    /** Latest pushed avatar parameter as a float (bools 0/1), or null. Until VRChat pushes it
     *  (after a bridge relink nothing is held), the OSCQuery poll's fresh value stands in. */
    fun param(name: String): Float? = if (paused) null else params[name] ?: VrcaOscState.freshFloat(name)

    // OFF by default (user, 2026-10-31: the bridge caused too many issues for everyone; only testers use it).
    // Settings → Debug turns it on; the choice is saved, and the bridge process itself refuses to start while off.
    private const val PREFS = "vrca_osc_bridge"
    private const val KEY_ON = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    /** Debug switch: on = start announcing (and keep doing it on every launch); off = end the bridge now. */
    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).commit()
        if (on) start(context) else stop(context)
    }

    /** Ends announcing: the link loop stops and the bridge process is told to quit. */
    @Synchronized
    fun stop(context: Context) {
        running = false
        OscBridgeService.stop(context.applicationContext)
    }

    @Synchronized
    fun start(context: Context) {
        paused = false
        if (running) return
        running = true
        appContext = context.applicationContext
        OscBridgeService.start(context.applicationContext)
        val g = ++linkGen   // an off→on flip mustn't leave the old link loop running next to the new one
        Thread({ linkLoop(g) }, "vrca-bridge-link").apply { isDaemon = true; start() }
    }

    /** Stay connected to the bridge; (re)start it when it isn't there. */
    @Volatile private var linkGen = 0
    private fun linkLoop(gen: Int) {
        val ctx = appContext ?: return
        var failures = 0
        while (running && gen == linkGen) {
            val sock = LocalSocket()
            try {
                sock.connect(LocalSocketAddress(OscBridge.relayName(ctx)))
                linked = true; failures = 0
                linkNote = "linked to bridge"
                synchronized(linkLock) { linkOut = DataOutputStream(sock.outputStream.buffered()) }
                if (dragState.isNotEmpty()) writeToBridge(2, dragState)
                readFrames(DataInputStream(sock.inputStream.buffered()))
            } catch (e: Exception) {
                failures++
                linkNote = "bridge not reachable (${e.javaClass.simpleName}), retry $failures"
                // Bridge process not running (first start, update, Stop button): start it.
                if (failures % 5 == 1) OscBridgeService.start(ctx)
            } finally {
                linked = false
                // VRChat pushes a param only when it CHANGES, so anything held from before the link
                // dropped may be out of date by the time it's back (e.g. a fist released meanwhile
                // would restart a drag). Forget it all; pushLive stays off until VRChat sends again,
                // and the OSCQuery poll carries the tokens in between.
                pushVrchat = null
                params.clear(); poses.clear(); poseMs.clear()
                synchronized(linkLock) { linkOut = null }
                runCatching { sock.close() }
            }
            try { Thread.sleep(1_000) } catch (_: InterruptedException) { break }
        }
    }

    /** [HandDrag]: tell the bridge whether a drag is moving the player and where its
     *  OSC goes. The `/input` axes are sticky in VRChat, so if this process dies
     *  mid-drag the bridge (which outlives it) sends the zeros instead. */
    fun reportDrag(host: String, port: Int, moving: Boolean) {
        dragState = "$host\n$port\n${if (moving) 1 else 0}"
        writeToBridge(2, dragState)
    }

    private fun writeToBridge(type: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        synchronized(linkLock) {
            val out = linkOut ?: return
            runCatching { out.writeByte(type); out.writeInt(bytes.size); out.write(bytes); out.flush() }
        }
    }

    private fun readFrames(input: DataInputStream) {
        var buf = ByteArray(8192)
        while (running) {
            val type = input.readUnsignedByte()
            val len = input.readInt()
            if (len < 0 || len > 1 shl 20) throw java.io.IOException("bad frame length $len")
            if (len > buf.size) buf = ByteArray(len)
            input.readFully(buf, 0, len)
            when (type) {
                0 -> {
                    packets++
                    lastPacketMs = System.currentTimeMillis()
                    pushVrchat = VrcaOscQuery.vrchatServiceName()
                    runCatching { parse(buf, 0, len) }
                }
                1 -> runCatching {
                    bridgeStatus = JSONObject(String(buf, 0, len, Charsets.UTF_8))
                    bridgeStatusMs = System.currentTimeMillis()
                }
            }
        }
    }

    /** Debug: send one OSC packet to the bridge's receive port (the address VRChat sends
     *  to) and report whether it came back through the relay within 2 s. Blocking. */
    fun selfTest(): String {
        val st = bridgeStatus ?: return "no bridge status yet".also { selfTestNote = it }
        val ip = st.optString("ip"); val port = st.optInt("udp")
        val sentAt = System.currentTimeMillis()
        selfTestNote = runCatching {
            // OSC message "/vrca/selftest" with no arguments (address + ",").
            val msg = "/vrca/selftest".toByteArray().let { a -> a + ByteArray(4 - a.size % 4) } + ",\u0000\u0000\u0000".toByteArray()
            java.net.DatagramSocket().use { it.send(DatagramPacket(msg, msg.size, InetAddress.getByName(ip), port)) }
            Thread.sleep(2_000)
            if (selfTestSeenMs >= sentAt) "self-test to $ip:$port RECEIVED through the bridge"
            else "self-test to $ip:$port NOT received (bridge or relay not working)"
        }.getOrElse { "self-test failed: ${it.message}" }
        return selfTestNote
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
        val key = if (isParam) "/avatar/parameters/*" else address
        if (counts.size < 64 || counts.containsKey(key)) counts.merge(key, 1, Int::plus)
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
                // New avatar: its params replace the old one's (VRChat re-sends them), so
                // names from past avatars don't pile up. Other paths are a fixed set.
                if (address == "/avatar/change") params.clear()
                if (others.size < 64 || others.containsKey(address)) {
                    others[address] = if (v is Float) "%.3f".format(v) else v.toString()
                }
                // Avatar id/name, eye height + the world's size limits.
                if (!paused && address.startsWith("/avatar/")) VrcaOscState.onAvatarInfo(address, v)
            }
        }
    }

    // ---- diag ------------------------------------------------------------------

    fun diag(): String = buildString {
        if (!running) { append("(not started)"); return@buildString }
        append(if (paused) "PAUSED (bridge still connected)" else "RUNNING").append("  ").append(linkNote)
        val st = bridgeStatus
        if (st != null) {
            val age = (System.currentTimeMillis() - bridgeStatusMs) / 1000
            append("\nbridge pid ").append(st.optInt("pid")).append(" up ").append(st.optLong("upSec")).append(" s")
                .append("  address changes: ").append(st.optInt("ipChanges"))
                .append("  drag stops after a crash: ").append(st.optInt("dragStops"))
            if (age > 3) append("  (status ").append(age).append(" s old)")
            append("\nannouncing as ").append(st.optString("name"))
            append("  OSCQuery http://").append(st.optString("ip")).append(':').append(st.optInt("http"))
            append("  OSC in ").append(st.optString("ip")).append(':').append(st.optInt("udp"))
            append("\n").append(st.optString("portNote"))
            append("\n").append(st.optString("mdns"))
            append("\nVRChat read our tree: ").append(st.optInt("httpRequests")).append(" requests")
            st.optString("lastHttpPath").takeIf { it.isNotEmpty() }?.let { append(" (last ").append(it).append(')') }
            append("\nbridge received ").append(st.optLong("packets")).append(", relayed ").append(st.optLong("relayed"))
        }
        if (selfTestNote.isNotEmpty()) append("\n").append(selfTestNote)
        append("\npush live: ").append(pushLive)
        append("\npackets here: ").append(packets)
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
}
