package com.vrca.osc

import android.content.Context
import com.illposed.osc.OSCMessage
import com.illposed.osc.transport.udp.OSCPortOut
import java.net.InetAddress
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Headset debug PROTOTYPE: OVR-style hand drag (horizontal only) over OSC.
 * Make a fist (pushed avatar param `GestureRight`/`GestureLeft` == 1) and the wrist's
 * movement is flipped (pull back = move forward), turned into the head's facing
 * (yaw from the pushed head pose) and sent as `/input/Vertical` (forward) +
 * `/input/Horizontal` (right). Open the hand → both inputs go back to 0.
 * Poses come from [OscAnnouncer] (VRChat pushes them only to an announced app), in
 * raw VR tracking space, so our own locomotion doesn't feed back into the hand delta.
 * The stick value is hand speed x [strength] / [REF_SPEED]; VRChat then applies the
 * world's own walk/run speed, so it is not 1:1 yet (the live readout shows VRChat's
 * actual VelocityX/Z for tuning). No vertical: OSC has no up/down movement input.
 * The axes are sticky in VRChat (they hold until 0 is sent), so a crash mid-drag would
 * keep the player moving: each grab is reported to the bridge ([OscAnnouncer.reportDrag]),
 * which sends the zeros if this process dies, and a saved flag makes the next start
 * send them too ([clearStale]) for when the bridge wasn't running.
 */
object HandDrag {

    private const val TICK_MS = 16L
    private const val REF_SPEED = 4f      // m/s of hand speed that maps to a full stick at strength 1
    private const val SMOOTH = 0.35f      // low-pass on hand velocity (0..1, higher = snappier)
    private const val DEADZONE = 0.04f    // stick values below this are sent as 0
    private const val GESTURE_FIST = 1f
    private const val PREFS = "hand_drag"

    @Volatile var enabled = false
        private set
    @Volatile var strength = 1.5f

    @Volatile private var grabHand = ""
    @Volatile private var stickF = 0f
    @Volatile private var stickR = 0f
    @Volatile private var handSpeed = 0f
    @Volatile private var sendError = ""

    private var appContext: Context? = null

    /** Main-process start: if the last process died mid-drag, stop VRChat's movement. */
    fun clearStale(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val target = prefs.getString("moving", null) ?: return
        val host = target.substringBefore(':'); val port = target.substringAfter(':').toIntOrNull() ?: 9000
        runCatching {
            val o = OSCPortOut(InetAddress.getByName(host), port)
            repeat(3) {
                o.send(OSCMessage("/input/Vertical", listOf(0f)))
                o.send(OSCMessage("/input/Horizontal", listOf(0f)))
            }
            o.close()
        }
        prefs.edit().remove("moving").apply()
    }

    private fun markMoving(host: String, port: Int, moving: Boolean) {
        OscAnnouncer.reportDrag(host, port, moving)
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.apply {
            if (moving) putString("moving", "$host:$port") else remove("moving")
        }?.apply()
    }

    fun setEnabled(context: Context, on: Boolean) {
        if (on == enabled) return
        enabled = on
        appContext = context.applicationContext
        if (on) {
            if (!OscAnnouncer.running) OscAnnouncer.start(context)
            Thread({ loop() }, "vrca-hand-drag").apply { isDaemon = true; start() }
        }
    }

    private fun loop() {
        var out: OSCPortOut? = null
        var prev: FloatArray? = null
        var prevMs = 0L
        var velX = 0f; var velZ = 0f
        var lastSentF = 0f; var lastSentR = 0f; var lastSentMs = 0L
        var target = "127.0.0.1" to 9000
        fun send(f: Float, r: Float) {
            val o = out ?: return
            runCatching {
                o.send(OSCMessage("/input/Vertical", listOf(f)))
                o.send(OSCMessage("/input/Horizontal", listOf(r)))
                sendError = ""
            }.onFailure { sendError = it.message ?: it.javaClass.simpleName }
            lastSentF = f; lastSentR = r; lastSentMs = System.currentTimeMillis()
        }
        fun release() {
            if (out != null) {
                repeat(3) { send(0f, 0f) }; runCatching { out?.close() }
                markMoving(target.first, target.second, false)
            }
            out = null; prev = null; velX = 0f; velZ = 0f
            grabHand = ""; stickF = 0f; stickR = 0f; handSpeed = 0f
        }
        while (enabled) {
            val now = System.currentTimeMillis()
            val hand = when {
                OscAnnouncer.param("GestureRight") == GESTURE_FIST -> "rightwrist"
                OscAnnouncer.param("GestureLeft") == GESTURE_FIST -> "leftwrist"
                else -> ""
            }
            val wrist = if (hand.isNotEmpty()) OscAnnouncer.pose(hand) else null
            val head = OscAnnouncer.pose("head")
            if (hand.isEmpty() || wrist == null || head == null || (grabHand.isNotEmpty() && hand != grabHand)) {
                release()
            } else {
                if (out == null) {
                    target = VrcaOscQuery.oscSendTargetFlow.value ?: ("127.0.0.1" to 9000)
                    out = runCatching { OSCPortOut(InetAddress.getByName(target.first), target.second) }
                        .onFailure { sendError = it.message ?: "open failed" }.getOrNull()
                    grabHand = hand
                    if (out != null) markMoving(target.first, target.second, true)
                }
                val p = prev
                if (p != null && now > prevMs) {
                    val dt = (now - prevMs) / 1000f
                    val vx = (wrist[0] - p[0]) / dt
                    val vz = (wrist[2] - p[2]) / dt
                    velX += (vx - velX) * SMOOTH
                    velZ += (vz - velZ) * SMOOTH
                }
                prev = wrist.copyOf(); prevMs = now
                handSpeed = kotlin.math.sqrt(velX * velX + velZ * velZ)
                // Drag the world: the player moves OPPOSITE to the hand.
                val mx = -velX; val mz = -velZ
                val yaw = Math.toRadians(head[4].toDouble())
                val fwd = (mx * sin(yaw) + mz * cos(yaw)).toFloat()
                val right = (mx * cos(yaw) - mz * sin(yaw)).toFloat()
                fun stick(v: Float): Float {
                    val s = (v * strength / REF_SPEED).coerceIn(-1f, 1f)
                    return if (abs(s) < DEADZONE) 0f else s
                }
                stickF = stick(fwd); stickR = stick(right)
                if (abs(stickF - lastSentF) > 0.01f || abs(stickR - lastSentR) > 0.01f || now - lastSentMs > 100) {
                    send(stickF, stickR)
                }
            }
            try { Thread.sleep(TICK_MS) } catch (_: InterruptedException) { break }
        }
        release()
    }

    fun diag(): String = buildString {
        append(if (enabled) "ON" else "off").append("  strength ").append("%.2f".format(strength))
        append("\ngrab: ").append(grabHand.ifEmpty { "(open hands)" })
        append("  GestureL=").append(OscAnnouncer.param("GestureLeft")?.toInt() ?: "-")
        append(" GestureR=").append(OscAnnouncer.param("GestureRight")?.toInt() ?: "-")
        append("\nhand speed ").append("%.2f".format(handSpeed)).append(" m/s")
        append("  stick fwd ").append("%.2f".format(stickF)).append(" right ").append("%.2f".format(stickR))
        append("\nVRChat velocity X ").append("%.2f".format(OscAnnouncer.param("VelocityX") ?: 0f))
        append(" Z ").append("%.2f".format(OscAnnouncer.param("VelocityZ") ?: 0f))
        if (OscAnnouncer.pose("head") == null) append("\n(no head pose: start announcing, VRChat head/wrist OSC on)")
        if (sendError.isNotEmpty()) append("\nsend error: ").append(sendError)
    }
}
