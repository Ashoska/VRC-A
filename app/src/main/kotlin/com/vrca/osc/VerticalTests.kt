package com.vrca.osc

import com.illposed.osc.OSCMessage
import com.illposed.osc.transport.udp.OSCPortOut
import java.net.InetAddress

/**
 * Headset debug: one-shot tests of the two VRChat OSC inputs that might affect the
 * view vertically. `/input/ToggleSitStand` (VRChat lists it as bool, "Toggles between
 * Sitting and Standing modes. Only has an effect in VR") did nothing when sent as
 * false/true/false, so it is also tried as int 0/1/0 (VRChat's own docs send buttons as
 * ints). `/input/LookVertical` (axis, +1 up / -1 down) is held for [HOLD_MS] then
 * released. Each call is blocking: run off the main thread.
 */
object VerticalTests {

    private const val HOLD_MS = 1000L

    @Volatile var lastResult = ""
        private set

    private fun <T> withPort(block: (OSCPortOut) -> T): String {
        val target = VrcaOscQuery.oscSendTargetFlow.value ?: ("127.0.0.1" to 9000)
        return runCatching {
            val out = OSCPortOut(InetAddress.getByName(target.first), target.second)
            try { block(out) } finally { runCatching { out.close() } }
            "to ${target.first}:${target.second}"
        }.getOrElse { "failed: ${it.message}" }
    }

    fun sitStandInt() {
        lastResult = "ToggleSitStand int 0/1/0 " + withPort { out ->
            out.send(OSCMessage("/input/ToggleSitStand", listOf(0)))
            Thread.sleep(100)
            out.send(OSCMessage("/input/ToggleSitStand", listOf(1)))
            Thread.sleep(200)
            out.send(OSCMessage("/input/ToggleSitStand", listOf(0)))
        }
    }

    fun sitStandBool() {
        lastResult = "ToggleSitStand bool F/T/F " + withPort { out ->
            out.send(OSCMessage("/input/ToggleSitStand", listOf(false)))
            Thread.sleep(100)
            out.send(OSCMessage("/input/ToggleSitStand", listOf(true)))
            Thread.sleep(200)
            out.send(OSCMessage("/input/ToggleSitStand", listOf(false)))
        }
    }

    fun lookVertical(value: Float) {
        lastResult = "LookVertical $value for ${HOLD_MS}ms " + withPort { out ->
            val end = System.currentTimeMillis() + HOLD_MS
            while (System.currentTimeMillis() < end) {
                out.send(OSCMessage("/input/LookVertical", listOf(value)))
                Thread.sleep(50)
            }
            repeat(3) { out.send(OSCMessage("/input/LookVertical", listOf(0f))) }
        }
    }
}
