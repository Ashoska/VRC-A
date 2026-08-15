package com.vrca.osc

import android.util.Log
import com.illposed.osc.OSCMessage
import com.illposed.osc.transport.udp.OSCPortOut
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Executors

class VrcaOsc(
    ipAddress: String,
    var port: Int
) {

    companion object {
        // Send-stall self-heal cadence. STALL_MS is comfortably above the sender's
        // ~3s content-dedup ceiling, so only a REAL stall (actively dispatching but
        // nothing succeeding) trips it — never a paused/idle app.
        private const val SEND_WATCHDOG_MS = 4_000L
        private const val SEND_STALL_MS = 8_000L
    }

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
    // inetAddress when the user supplies an unresolvable host. @Volatile: written by
    // the ipAddress setter's async resolve (an IO thread), read by the send thread.
    @Volatile private var inetAddress: InetAddress = InetAddress.getLoopbackAddress()

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

    // ---- ONE reused socket on ONE dedicated send thread ----------------------
    // Replaces the old "new OSCPortOut + new CoroutineScope(Dispatchers.IO) per
    // send", whose churn is the root of the "chatbox randomly stops (restart fixes
    // it)" faults: a send whose finally didn't close leaked an fd toward EMFILE, and
    // blocked/piled-up send coroutines could exhaust the SHARED 64-thread IO pool so
    // new sends never ran (UDP dies while the OSCQuery HTTP poll — on its own
    // long-lived coroutine — keeps working, exactly the observed symptom). One reused
    // socket keeps the fd count flat, serialises sends (no socket race), and can't
    // starve the IO pool. It self-heals: rebuilt on a target (ip/port) change and
    // dropped+rebuilt after any send error, so the NEXT send recovers with no restart.
    private val sendDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "vrca-osc-send").apply { isDaemon = true } }
            .asCoroutineDispatcher()
    private val sendScope = CoroutineScope(sendDispatcher)
    // Touched ONLY on sendDispatcher (single thread) → no locking needed.
    private var persistentSender: OSCPortOut? = null
    private var boundAddr: InetAddress? = null
    private var boundPort: Int = -1

    /** (Re)use one socket for the current target; rebuild on a target change or after
     *  a prior error dropped it. Runs on [sendDispatcher] only. */
    private fun sender(): OSCPortOut? {
        val addr = inetAddress
        val p = port
        val cur = persistentSender
        if (cur != null && boundAddr == addr && boundPort == p) return cur
        runCatching { cur?.close() }
        persistentSender = null
        return try {
            OSCPortOut(addr, p).also { persistentSender = it; boundAddr = addr; boundPort = p }
        } catch (e: Exception) {
            VrcaOscState.recordSendFail(e)
            Log.e(TAG, "OSC socket open failed", e)
            null
        }
    }

    /** Serialised send on the dedicated thread with the reused socket. `recordSendDispatch`
     *  is stamped SYNCHRONOUSLY (in the caller thread) so the diag can tell a wedged/
     *  never-run send (dispatch fresh, ok stale) from a throwing one (sendError set). */
    private fun dispatchSend(message: OSCMessage, delay: Long = 0) {
        VrcaOscState.recordSendDispatch()
        sendScope.launch {
            if (delay > 0) delay(delay)
            val s = sender() ?: return@launch
            try {
                s.send(message)
                VrcaOscState.recordSendOk()
                Log.d(TAG, "Message: ${message.address}  ${message.arguments}")
            } catch (e: Exception) {
                VrcaOscState.recordSendFail(e)
                Log.e(TAG, "Failed send Message: $message", e)
                // Drop the socket so the NEXT send rebuilds it (self-heal).
                runCatching { persistentSender?.close() }
                persistentSender = null
            }
        }
    }

    // Self-heal watchdog: if we're actively DISPATCHING sends but none have SUCCEEDED
    // for a while, the socket/address has gone bad (or a send is wedged) — drop the
    // socket so the next send rebuilds it, and re-resolve the target. This turns a
    // stalled send path into a ~seconds recovery instead of the "wait an hour / restart
    // the app" behaviour. Only fires when we're genuinely trying (dispatched recently)
    // yet nothing lands, so an idle/paused app never triggers it. Runs on the send
    // thread, so persistentSender is touched safely (single-threaded).
    init {
        sendScope.launch {
            while (true) {
                delay(SEND_WATCHDOG_MS)
                val now = System.currentTimeMillis()
                val tryingNow = now - VrcaOscState.sendDispatchedMs < SEND_STALL_MS
                val notLanding = now - VrcaOscState.sendOkMs > SEND_STALL_MS
                if (tryingNow && notLanding) {
                    Log.w(TAG, "send stall (>${SEND_STALL_MS}ms dispatching with no ok) → rebuild socket + re-resolve")
                    runCatching { persistentSender?.close() }
                    persistentSender = null
                    runCatching { InetAddress.getByName(ipAddress) }
                        .onSuccess { inetAddress = it; addressResolvable = true }
                }
            }
        }
    }

    private fun sendOscMessage(address: String, arguments: List<Any?>, delay: Long = 0) {
        if (blocked) return
        // Lifetime "chatbox updates sent" counter (boot screen stat). Counts
        // every non-blank content send; typing signals and blank clears don't.
        if (address == "/chatbox/input" && (arguments.firstOrNull() as? String)?.isNotBlank() == true) {
            com.vrca.app.ChatboxStats.increment()
        }
        dispatchSend(OSCMessage(address, arguments), delay)
    }

    /** Set the local player's avatar size via VRChat's `/avatar/eyeheight` OSC
     *  input (meters). We DON'T impose an app-side min/max — the user can type any
     *  value; only VRChat's own absolute safety bounds (0.01–10000 m per its OSC
     *  docs) are applied so we never send garbage. VRChat further clamps to what the
     *  avatar/world actually permit. A deliberate one-shot control, so it bypasses
     *  the chatbox `blocked` gate (routes straight to dispatchSend). */
    fun sendEyeHeight(meters: Float) {
        dispatchSend(OSCMessage("/avatar/eyeheight", listOf(meters.coerceIn(0.01f, 10000f))))
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
