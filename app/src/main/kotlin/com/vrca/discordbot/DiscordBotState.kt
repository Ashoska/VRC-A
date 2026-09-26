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

    // Cardinal's own exact spend today (the account total can include other use of the same account).
    private val _own = MutableStateFlow(0.0)
    val ownNeuronsFlow: StateFlow<Double> = _own.asStateFlow()
    fun noteOwn(n: Double) { rolloverIfNeeded(); _own.value += n }
    private val _neurons = MutableStateFlow(0L)
    val neuronsFlow: StateFlow<Long> = _neurons.asStateFlow()
    private val _rung = MutableStateFlow(Rung.FULL)
    val rungFlow: StateFlow<Rung> = _rung.asStateFlow()

    private val _traces = MutableStateFlow<List<Trace>>(emptyList())
    val tracesFlow: StateFlow<List<Trace>> = _traces.asStateFlow()

    private val ts = SimpleDateFormat("HH:mm:ss", Locale.US)
    @Volatile private var dayOfYear = today()

    // Admin-configurable budget + which saver rungs are active (set from DiscordBotStore.Config at start).
    @Volatile private var dailyBudget: Long = DiscordBotLimits.DAILY_NEURON_BUDGET
    @Volatile private var trimEnabled: Boolean = true
    @Volatile private var cheapEnabled: Boolean = true
    @Volatile private var reactOnlyEnabled: Boolean = true
    @Volatile private var hardStopEnabled: Boolean = true

    /** Push the admin's budget + ladder config so [computeRung] reflects it live. */
    fun configureLadder(
        budget: Long, trim: Boolean, cheap: Boolean, reactOnly: Boolean, hardStop: Boolean,
    ) {
        dailyBudget = budget.coerceAtLeast(100L)
        trimEnabled = trim; cheapEnabled = cheap; reactOnlyEnabled = reactOnly; hardStopEnabled = hardStop
        _rung.value = computeRung(_neurons.value)
    }

    /** For the Cost/Controls UI: the budget the ladder is currently computed against. */
    fun budget(): Long = dailyBudget

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
            _seen.value = 0; _replied.value = 0; _reacted.value = 0; _neurons.value = 0L; _own.value = 0.0
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

    /**
     * The current degradation rung against the ADMIN's budget, honoring which saver stages are
     * enabled. A disabled rung is SKIPPED — the previous still-enabled rung persists over its band —
     * so turning them all off keeps Cardinal at FULL quality right up to the hard stop, and turning
     * the hard stop off means it never goes SILENT (spends past the budget). It only ever escalates
     * through ENABLED rungs, so the ladder stays monotonic.
     */
    private fun computeRung(spent: Long): Rung {
        val f = spent.toDouble() / dailyBudget
        if (f >= 1.0 && hardStopEnabled) return Rung.SILENT
        var rung = Rung.FULL
        if (trimEnabled && f >= DiscordBotLimits.LADDER_FULL_FRAC) rung = Rung.TRIM
        if (cheapEnabled && f >= DiscordBotLimits.LADDER_TRIM_FRAC) rung = Rung.CHEAP
        if (reactOnlyEnabled && f >= DiscordBotLimits.LADDER_CHEAP_FRAC) rung = Rung.REACT_ONLY
        return rung
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
