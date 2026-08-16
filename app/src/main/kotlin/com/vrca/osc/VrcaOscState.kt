package com.vrca.osc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Live avatar-parameter state received FROM VRChat over OSC (port 9001).
 *
 * VRChat, when OSC is enabled, streams the local player's avatar parameters out
 * as `/avatar/parameters/<Name>` messages — including the built-ins (MuteSelf,
 * AFK, Velocity*, Grounded, Upright, and the newer avatar-scaling params
 * ScaleFactor / EyeHeightAsMeters). [VrcaOscReceiver] folds them here; the
 * chatbox token resolver reads them for `{mute}` `{afk}` `{movement}` `{scale}`
 * `{param:Name}`. Headset-only in practice (OSC-out is loopback, so VRC-A must
 * run on the same device as VRChat).
 */
object VrcaOscState {

    private val params = ConcurrentHashMap<String, Any>()
    @Volatile private var lastRxMs = 0L

    // True while we've received at least one OSC message recently (= VRChat is
    // running with OSC enabled on this device). Drives a "VRChat OSC live" signal.
    private val _live = MutableStateFlow(false)
    val liveFlow: StateFlow<Boolean> = _live.asStateFlow()
    val isLive: Boolean get() = System.currentTimeMillis() - lastRxMs < LIVE_WINDOW_MS

    private const val LIVE_WINDOW_MS = 8_000L
    private const val MOVING_THRESHOLD = 0.10f // m/s magnitude to read as "moving"

    // ---- diagnostics (headset OSC-in debugging) ------------------------------
    /** OSCQuery discovery-probe results (VrcaOscQuery). */
    @Volatile var oscQueryDiag = "(not scanned)"
    @Volatile var diagBound = false
    @Volatile var diagBindError = ""
    @Volatile var diagRxPackets = 0L
    @Volatile var diagLastAddress = ""

    /** Open file-descriptor count for THIS process. Climbing toward ~1024 over a
     *  session = a socket/connection leak (the suspected "chatbox randomly stops"
     *  cause — sends fail once the fd limit is hit, until an app/headset restart). */
    fun openFdCount(): Int = runCatching {
        java.io.File("/proc/self/fd").list()?.size ?: -1
    }.getOrDefault(-1)

    // ---- outbound send telemetry (chatbox / eyeheight fail diagnosis) --------
    // The VM's "last send" only records when sendMessage was CALLED (synchronous),
    // not whether the async UDP packet actually left — so a silently-failing send
    // looked healthy. These record the async result: `dispatched` = the send was
    // handed to the IO coroutine; `ok` = OSCPortOut.send() returned without throwing.
    // If dispatched is fresh but ok is stale with no error → the coroutine never ran
    // (IO-pool starvation). If sendError is set → send() is throwing (with the cause).
    // If ok is fresh but VRChat is stale → it left but didn't land (target/VRChat side).
    @Volatile var sendDispatchedMs = 0L
    @Volatile var sendOkMs = 0L
    @Volatile var sendError = ""
    @Volatile var sendFailStreak = 0

    fun recordSendDispatch() { sendDispatchedMs = System.currentTimeMillis() }
    fun recordSendOk() { sendOkMs = System.currentTimeMillis(); sendError = ""; sendFailStreak = 0 }
    fun recordSendFail(e: Throwable) {
        sendError = "${e.javaClass.simpleName}: ${e.message}"
        if (sendFailStreak < Int.MAX_VALUE) sendFailStreak++
    }

    // ---- REAL delivery canary (eyeheight round-trip) -------------------------
    // `udp ok` only means the OS accepted our datagram — for UDP that says NOTHING
    // about VRChat receiving it, so a dead outbound path still reads "healthy".
    // The ONE positive-confirmation channel the headset gives us: when we send
    // /avatar/eyeheight, VRChat APPLIES it and the new value comes back as the
    // EyeHeightAsMeters param over OSCQuery. So if the readback MOVES after a send,
    // VRChat genuinely received it (outbound OSC alive); if it never moves, our
    // send left the app but VRChat never got it (the exact "chatbox stops but
    // OSCQuery-in still works" fault, now provable instead of guessed).
    private const val EYE_CONFIRM_WINDOW_MS = 4_000L
    private const val EYE_CONFIRM_EPS = 0.01f
    @Volatile var eyeHeightSentMs = 0L
    @Volatile var eyeHeightSentTarget = 0f
    @Volatile private var eyeHeightPreSend: Float? = null
    @Volatile var eyeHeightConfirmedMs = 0L
    @Volatile private var eyeHeightCanaryArmed = false

    /** Arm the canary on an eyeheight send. Only a send that actually CHANGES the
     *  current height can be confirmed (a no-op resize produces no readback move). */
    fun recordEyeHeightSend(target: Float) {
        eyeHeightSentMs = System.currentTimeMillis()
        eyeHeightSentTarget = target
        val pre = _eyeHeight.value
        eyeHeightPreSend = pre
        eyeHeightConfirmedMs = 0L
        eyeHeightCanaryArmed = pre == null || kotlin.math.abs(target - pre) > EYE_CONFIRM_EPS
    }

    /** Human-readable delivery verdict for the diag panel. */
    fun deliveryDiag(): String {
        val sent = eyeHeightSentMs
        if (sent == 0L) return "size probe: (change your avatar size to test delivery)"
        val now = System.currentTimeMillis()
        val sentAgo = (now - sent) / 1000
        if (!eyeHeightCanaryArmed) return "size probe: sent ${sentAgo}s ago (no size change — can't confirm)"
        val conf = eyeHeightConfirmedMs
        return when {
            conf >= sent -> "size probe: DELIVERED ✓ (VRChat applied it ${(now - conf) / 1000}s ago)"
            now - sent > EYE_CONFIRM_WINDOW_MS ->
                "size probe: NOT DELIVERED ✗ (sent ${sentAgo}s ago, readback never moved = outbound OSC dead)"
            else -> "size probe: sent ${sentAgo}s ago, awaiting VRChat readback…"
        }
    }

    fun diagString(): String = buildString {
        append("fds=").append(openFdCount()).append('\n')
        append("bound=").append(diagBound)
        if (diagBindError.isNotBlank()) append("  bindErr=").append(diagBindError)
        append("  rxPackets=").append(diagRxPackets)
        append("  live=").append(isLive)
        append("  params=").append(params.size)
        if (diagLastAddress.isNotBlank()) append("\nlastAddr=").append(diagLastAddress)
        append("\nmute=").append(muteSelf)
        append("  afk=").append(afk)
        append("  moving=").append(moving)
        append("  scale=").append(scaleLabel.ifBlank { "(none)" })
        if (params.isNotEmpty()) {
            append("\nkeys: ").append(params.keys.take(12).joinToString(", "))
        }
    }

    // Live avatar eye height (meters) from EyeHeightAsMeters, exposed reactively so
    // the VRChat-tab size control can mirror the IN-GAME height.
    private val _eyeHeight = MutableStateFlow<Float?>(null)
    val eyeHeightFlow: StateFlow<Float?> = _eyeHeight.asStateFlow()
    val eyeHeightMeters: Float? get() = _eyeHeight.value

    /**
     * The avatar's DEFAULT (creator) eye height in meters = current / ScaleFactor.
     * VRChat's ScaleFactor is 1.0 at the avatar's native height and scales linearly,
     * so current/ScaleFactor is invariant as you resize — it's the TRUE default, not
     * whatever our app last set. Null when ScaleFactor isn't published (no scaling).
     */
    val defaultEyeHeightMeters: Float? get() {
        val eh = _eyeHeight.value ?: return null
        val sf = float("ScaleFactor")
        return if (sf != null && sf > 0.01f) eh / sf else null
    }

    fun onParam(name: String, value: Any?) {
        if (value != null) params[name] = value
        lastRxMs = System.currentTimeMillis()
        if (!_live.value) _live.value = true
        if (name == "EyeHeightAsMeters") {
            (value as? Number)?.toFloat()?.let { f ->
                _eyeHeight.value = f
                // Delivery canary: a pending eyeheight send is CONFIRMED the moment
                // the readback moves off its pre-send value (VRChat received+applied).
                val sent = eyeHeightSentMs
                if (eyeHeightCanaryArmed && eyeHeightConfirmedMs < sent &&
                    System.currentTimeMillis() - sent in 0..EYE_CONFIRM_WINDOW_MS
                ) {
                    val pre = eyeHeightPreSend
                    if (pre == null || kotlin.math.abs(f - pre) > EYE_CONFIRM_EPS) {
                        eyeHeightConfirmedMs = System.currentTimeMillis()
                    }
                }
            }
        }
    }

    /** Called periodically so `isLive` can flip false when OSC stops. */
    fun tickLiveness() { _live.value = isLive }

    fun clear() { params.clear(); lastRxMs = 0L; _live.value = false }

    // ---- typed accessors -----------------------------------------------------

    fun bool(name: String): Boolean? = when (val v = params[name]) {
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        else -> null
    }

    fun float(name: String): Float? = (params[name] as? Number)?.toFloat()

    /** Raw value for `{param:Name}` — bool/int/float rendered compactly. */
    fun rawString(name: String): String = when (val v = params[name]) {
        null -> ""
        is Boolean -> if (v) "true" else "false"
        is Float -> if (v == v.toInt().toFloat()) v.toInt().toString() else "%.2f".format(v)
        is Double -> "%.2f".format(v)
        else -> v.toString()
    }

    // ---- built-ins -----------------------------------------------------------

    val muteSelf: Boolean get() = bool("MuteSelf") ?: false
    val afk: Boolean get() = bool("AFK") ?: false

    val moving: Boolean get() {
        val x = float("VelocityX") ?: 0f
        val y = float("VelocityY") ?: 0f
        val z = float("VelocityZ") ?: 0f
        return kotlin.math.sqrt(x * x + y * y + z * z) > MOVING_THRESHOLD
    }

    /** Human avatar-scale string: eye height in meters if VRChat sends it, else
     *  the ScaleFactor ratio as a percentage. "" when unknown. */
    val scaleLabel: String get() {
        float("EyeHeightAsMeters")?.let { return "%.2fm".format(it) }
        float("ScaleFactor")?.let { return "${(it * 100).toInt()}%" }
        return ""
    }
}
