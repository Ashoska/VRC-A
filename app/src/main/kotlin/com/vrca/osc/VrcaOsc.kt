package com.vrca.osc

import android.util.Log
import com.illposed.osc.OSCMessage
import com.illposed.osc.transport.udp.OSCPortOut
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.UnknownHostException

class VrcaOsc(
    ipAddress: String,
    var port: Int
) {

    val TAG: String
        get() = "OSC@$ipAddress:$port"

    var addressResolvable = true
        private set

    var ipAddress = ipAddress
        set(value) {
            Log.d(TAG, "IP Address $field -> $value")

            field = value
            // DNS resolution must run off the main thread — InetAddress.getByName
            // for non-literal hostnames performs a network lookup which throws
            // NetworkOnMainThreadException if invoked on the UI thread. For IP
            // literals the parse is fast, but we still defer for consistency.
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    inetAddress = InetAddress.getByName(value)
                    Log.d(TAG, "Resolve to $inetAddress.address")
                    addressResolvable = true
                } catch (e: UnknownHostException) {
                    Log.d(TAG, "Can't resolve $value")
                    addressResolvable = false
                } catch (e: Exception) {
                    Log.d(TAG, "Resolve failed for $value: ${e.message}")
                    addressResolvable = false
                }
            }
        }

    init {
        CoroutineScope(Dispatchers.IO).launch {
            this@VrcaOsc.ipAddress = ipAddress
        }
    }

    // Default to loopback so sendOscMessage never crashes on uninitialized
    // inetAddress when the user supplies an unresolvable host.
    private var inetAddress: InetAddress = InetAddress.getLoopbackAddress()

    // Hard transmission gate. When true, EVERY outgoing OSC message is dropped
    // at this single chokepoint (typing, input, realtime all route through
    // sendOscMessage), regardless of which caller/loop triggered it. Used to
    // enforce a forced app update: a pending required update sets this true so
    // the user cannot keep driving the VRChat chatbox (even backgrounded) until
    // they update. @Volatile so a background sender loop sees the flip instantly.
    @Volatile
    var blocked = false

    // VRChat "minimal background" trick (same as VRCOSC / MagicChatbox):
    // appending U+0003 (END OF TEXT) + U+001F (UNIT SEPARATOR) corrupts the
    // chatbox bubble's text-bounds measurement, collapsing the background to
    // its minimum pill while the text still renders normally. Applied at this
    // chokepoint so every /chatbox/input path gets it and it never leaks into
    // the in-app preview. @Volatile — flipped from the VM, read by sender loops.
    @Volatile
    var minimalBackground = false

    private fun withMinimalBackground(text: String): String {
        // Blank stays blank: a chatbox CLEAR must remain a truly empty payload,
        // otherwise the suffix would keep an empty mini-bubble alive in VRChat.
        if (!minimalBackground || text.isBlank()) return text
        // Cap content at 142 so the 2 control chars always survive VRChat's
        // 144-char limit — they are appended AFTER the cap, never trimmed off.
        val capped = if (text.length > 142) text.substring(0, 142) else text
        return capped + "\u0003\u001F"
    }

    var typing = false
        set(value) {
            // With the invisible/minimal-background chatbox ON, the VRChat typing
            // indicator causes in-game UI issues — never show it. A false/clear
            // still goes through so any indicator left over from before the trick
            // was enabled is cleared. (Only manual-send paths set typing; the
            // automated combined senders never do.)
            val effective = value && !minimalBackground
            field = effective
            sendOscMessage("/chatbox/typing", listOf(effective))
        }

    private fun sendOscMessage(address: String, arguments: List<Any?>, delay: Long = 0) {
        if (blocked) return
        // Lifetime "chatbox updates sent" counter (boot screen stat). Counts
        // every non-blank content send; typing signals and blank clears don't.
        if (address == "/chatbox/input" && (arguments.firstOrNull() as? String)?.isNotBlank() == true) {
            com.vrca.app.ChatboxStats.increment()
        }
        CoroutineScope(Dispatchers.IO).launch {

            val message = OSCMessage(address, arguments)
            val sender = OSCPortOut(inetAddress, port)
            delay(delay)
            try {
                sender.send(message)
                Log.d(TAG, "Message: ${message.address}  ${message.arguments}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed send Message: $message")
            }
            sender.close()
        }
    }

    /** Set the local player's avatar size via VRChat's `/avatar/eyeheight` OSC
     *  input (meters; VRChat clamps 0.2–5.0). A deliberate one-shot control, so it
     *  bypasses the chatbox `blocked` gate. */
    fun sendEyeHeight(meters: Float) {
        val clamped = meters.coerceIn(0.2f, 5.0f)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val sender = OSCPortOut(inetAddress, port)
                sender.send(OSCMessage("/avatar/eyeheight", listOf(clamped)))
                sender.close()
                Log.d(TAG, "eyeheight -> $clamped")
            } catch (e: Exception) {
                Log.e(TAG, "eyeheight send failed", e)
            }
        }
    }

    /** Send text to the chatbox VERBATIM (no minimal-background suffix, no manual
     *  hold) — for the width-calibration harness, where the exact glyph run matters. */
    fun sendRaw(text: String) {
        sendOscMessage("/chatbox/input", listOf(text, true, false))
    }

    fun sendMessage(text: String, sendImmediately: Boolean, triggerSFX: Boolean) {
        sendOscMessage("/chatbox/input", listOf(withMinimalBackground(text), sendImmediately, triggerSFX))
        latestMsgTimestamp = System.currentTimeMillis()
    }

    private var realtimeMsgJob: Job? = null
    private var latestMsgTimestamp: Long = 0
    private var realtimeMsgInterval = 1500


    fun sendRealtimeMessage(text: String) {
        realtimeMsgJob?.cancel()

        Log.d(
            "Chatbox",
            "$latestMsgTimestamp  ${System.currentTimeMillis()}  ${(System.currentTimeMillis() - latestMsgTimestamp)}"
        )

        realtimeMsgJob = CoroutineScope(Dispatchers.IO).launch {
            val timeStamp = System.currentTimeMillis()

            if (timeStamp - latestMsgTimestamp < realtimeMsgInterval) {
                delay(realtimeMsgInterval - (timeStamp - latestMsgTimestamp))
            }

            sendOscMessage("/chatbox/input", listOf(withMinimalBackground(text), true, false))
            sendOscMessage("/chatbox/typing", listOf(text.isNotEmpty()), 50)

            latestMsgTimestamp = System.currentTimeMillis()
        }
    }
}
