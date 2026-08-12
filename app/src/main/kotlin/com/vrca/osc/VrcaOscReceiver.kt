package com.vrca.osc

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Listens on UDP 9001 for VRChat's OSC output and folds avatar parameters into
 * [VrcaOscState]. VRChat sends `/avatar/parameters/<Name> <value>` + `/avatar/change`
 * on loopback when OSC is enabled, so this only receives anything when VRC-A runs
 * on the SAME device as VRChat (the headset). Binding is harmless elsewhere (it
 * just never receives).
 *
 * Hand-rolled OSC 1.0 parse (address + typetags + args, 4-byte aligned) — the
 * messages are single, simple `,f`/`,i`/`,T`/`,F` params, no bundles.
 */
object VrcaOscReceiver {

    private const val TAG = "VrcaOscReceiver"
    private const val PORT = 9001

    @Volatile private var running = false
    private var socket: DatagramSocket? = null

    fun start() {
        if (running) return
        running = true
        Thread({ loop() }, "vrca-osc-in").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        VrcaOscState.clear()
    }

    private fun loop() {
        try {
            val sock = DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 2000
                bind(InetSocketAddress(PORT))
            }
            socket = sock
            VrcaOscState.diagBound = true
            VrcaOscState.diagBindError = ""
            Log.i(TAG, "OSC-in listening on $PORT")
            val buf = ByteArray(2048)
            while (running) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(packet)
                    VrcaOscState.diagRxPackets++
                    runCatching { parse(packet.data, packet.offset, packet.length) }
                } catch (e: SocketTimeoutException) {
                    VrcaOscState.tickLiveness() // let isLive expire when VRChat is quiet
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "receive error", e)
                }
            }
        } catch (e: Exception) {
            VrcaOscState.diagBound = false
            VrcaOscState.diagBindError = e.javaClass.simpleName + ": " + (e.message ?: "")
            Log.w(TAG, "failed to bind $PORT (another OSC app using it?)", e)
        } finally {
            runCatching { socket?.close() }
            socket = null
        }
    }

    // ---- OSC 1.0 parse -------------------------------------------------------

    private class Cursor(val data: ByteArray, var pos: Int, val end: Int)

    private fun parse(data: ByteArray, offset: Int, length: Int) {
        val c = Cursor(data, offset, offset + length)
        val address = readString(c) ?: return
        VrcaOscState.diagLastAddress = address
        if (address == "#bundle") return // VRChat avatar params aren't bundled
        val tags = readString(c) ?: return
        if (!tags.startsWith(",")) return
        // Avatar params carry a single value — read the first argument.
        val value: Any? = when (tags.getOrNull(1)) {
            'f' -> readFloat(c)
            'i' -> readInt(c)
            'T' -> true
            'F' -> false
            's' -> readString(c)
            'd' -> Double.fromBits(readLong(c))
            else -> null
        }
        when {
            address.startsWith("/avatar/parameters/") ->
                VrcaOscState.onParam(address.removePrefix("/avatar/parameters/"), value)
            address == "/avatar/change" -> VrcaOscState.onParam("__avatarChanged", value)
        }
    }

    /** OSC-string: null-terminated, then padded so the next field is 4-aligned. */
    private fun readString(c: Cursor): String? {
        val start = c.pos
        var p = start
        while (p < c.end && c.data[p].toInt() != 0) p++
        if (p >= c.end) return null
        val s = String(c.data, start, p - start, Charsets.UTF_8)
        val consumed = (p - start) + 1
        c.pos = start + consumed + ((4 - consumed % 4) % 4)
        return s
    }

    private fun readInt(c: Cursor): Int {
        if (c.pos + 4 > c.end) { c.pos = c.end; return 0 }
        val d = c.data; val p = c.pos
        val v = ((d[p].toInt() and 0xff) shl 24) or
            ((d[p + 1].toInt() and 0xff) shl 16) or
            ((d[p + 2].toInt() and 0xff) shl 8) or
            (d[p + 3].toInt() and 0xff)
        c.pos += 4
        return v
    }

    private fun readLong(c: Cursor): Long {
        val hi = readInt(c).toLong() and 0xffffffffL
        val lo = readInt(c).toLong() and 0xffffffffL
        return (hi shl 32) or lo
    }

    private fun readFloat(c: Cursor): Float = Float.fromBits(readInt(c))
}
