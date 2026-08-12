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

    fun diagString(): String = buildString {
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
            (value as? Number)?.toFloat()?.let { _eyeHeight.value = it }
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
