package com.vrca.discordbot

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Process-lifetime shared state for the Discord bot — everything the revamped admin Bot tab
 * observes: connection status, live counts, current mood, today's estimated neuron spend + the
 * budget-ladder rung, a rolling activity log, and a decision-trace ring buffer (the "Why" view).
 */
object DiscordBotState {
    enum class Status { IDLE, CONNECTING, CONNECTED, RECONNECTING, FAILED }

    /** Budget-degradation rung, derived from today's estimated neuron spend. */
    enum class Rung { FULL, TRIM, CHEAP, REACT_ONLY, SILENT }

    /** One recorded routing decision for the admin "Traces / Why" view. */
    data class Trace(
        val atMs: Long,
        val channel: String,
        val author: String,
        val score: String,       // heuristic score summary
        val plan: String,        // director plan (or "heuristic")
        val action: String,      // reply / react / ignore / queued / dropped
        val detail: String,      // reply preview or reason
    )

    private val _status = MutableStateFlow(Status.IDLE)
    val statusFlow: StateFlow<Status> = _status.asStateFlow()

    private val _detail = MutableStateFlow("")
    val detailFlow: StateFlow<String> = _detail.asStateFlow()

    private val _botName = MutableStateFlow("")
    val botNameFlow: StateFlow<String> = _botName.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val logFlow: StateFlow<List<String>> = _log.asStateFlow()

    private val _mood = MutableStateFlow("")
    val moodFlow: StateFlow<String> = _mood.asStateFlow()

    private val _seen = MutableStateFlow(0)
    val seenFlow: StateFlow<Int> = _seen.asStateFlow()
    private val _replied = MutableStateFlow(0)
    val repliedFlow: StateFlow<Int> = _replied.asStateFlow()
    private val _reacted = MutableStateFlow(0)
    val reactedFlow: StateFlow<Int> = _reacted.asStateFlow()

    private val _neurons = MutableStateFlow(0L)
    val neuronsFlow: StateFlow<Long> = _neurons.asStateFlow()
    private val _rung = MutableStateFlow(Rung.FULL)
    val rungFlow: StateFlow<Rung> = _rung.asStateFlow()

    private val _traces = MutableStateFlow<List<Trace>>(emptyList())
    val tracesFlow: StateFlow<List<Trace>> = _traces.asStateFlow()

    private val ts = SimpleDateFormat("HH:mm:ss", Locale.US)
    @Volatile private var dayOfYear = today()

    @Volatile var isRunning: Boolean = false
        private set

    fun setRunning(running: Boolean) { isRunning = running }

    fun setStatus(status: Status, detail: String = "") {
        _status.value = status
        if (detail.isNotBlank()) _detail.value = detail
    }

    fun setBotName(name: String) { _botName.value = name }
    fun setMood(mood: String) { _mood.value = mood }

    fun log(line: String) {
        val stamped = "${ts.format(Date())}  $line"
        _log.value = (_log.value + stamped).takeLast(DiscordBotLimits.ACTIVITY_LOG_CAP)
    }

    private fun today(): Int = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
    private fun rolloverIfNeeded() {
        val d = today()
        if (d != dayOfYear) {
            dayOfYear = d
            _seen.value = 0; _replied.value = 0; _reacted.value = 0; _neurons.value = 0L
            _rung.value = Rung.FULL
        }
    }

    fun bumpSeen() { rolloverIfNeeded(); _seen.value += 1 }
    fun bumpReplied() { rolloverIfNeeded(); _replied.value += 1 }
    fun bumpReacted() { rolloverIfNeeded(); _reacted.value += 1 }

    /** Record estimated neuron spend and recompute the degradation rung. */
    fun noteNeurons(est: Long) {
        rolloverIfNeeded()
        _neurons.value += est
        _rung.value = computeRung(_neurons.value)
    }

    /** Adopt an AUTHORITATIVE day total (persisted estimate or a real Cloudflare usage sync). */
    fun setNeuronsAbsolute(total: Long) {
        rolloverIfNeeded()
        _neurons.value = total.coerceAtLeast(0L)
        _rung.value = computeRung(_neurons.value)
    }

    fun currentRung(): Rung { rolloverIfNeeded(); return computeRung(_neurons.value) }

    private fun computeRung(spent: Long): Rung {
        val f = spent.toDouble() / DiscordBotLimits.DAILY_NEURON_BUDGET
        return when {
            f < DiscordBotLimits.LADDER_FULL_FRAC -> Rung.FULL
            f < DiscordBotLimits.LADDER_TRIM_FRAC -> Rung.TRIM
            f < DiscordBotLimits.LADDER_CHEAP_FRAC -> Rung.CHEAP
            f < 1.0 -> Rung.REACT_ONLY
            else -> Rung.SILENT
        }
    }

    fun addTrace(t: Trace) {
        _traces.value = (_traces.value + t).takeLast(DiscordBotLimits.TRACE_RING)
    }

    fun reset() {
        _status.value = Status.IDLE
        _detail.value = ""
        _botName.value = ""
        isRunning = false
    }
}
