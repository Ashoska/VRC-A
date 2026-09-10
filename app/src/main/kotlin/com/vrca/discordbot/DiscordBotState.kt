package com.vrca.discordbot

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Process-lifetime shared state for the Discord bot, mirroring [com.vrca.discord.DiscordRpcState].
 * The admin Discord Bot tab observes these flows via `collectAsState()` for a live status
 * dot + a rolling activity log (last ~40 events) so the operator can see the gateway
 * connecting, messages coming in, and AI replies going out.
 */
object DiscordBotState {
    enum class Status { IDLE, CONNECTING, CONNECTED, RECONNECTING, FAILED }

    private val _status = MutableStateFlow(Status.IDLE)
    val statusFlow: StateFlow<Status> = _status.asStateFlow()

    private val _detail = MutableStateFlow("")
    val detailFlow: StateFlow<String> = _detail.asStateFlow()

    /** Bot's own display name from the gateway READY payload (blank until connected). */
    private val _botName = MutableStateFlow("")
    val botNameFlow: StateFlow<String> = _botName.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val logFlow: StateFlow<List<String>> = _log.asStateFlow()

    private const val LOG_CAP = 40
    private val ts = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile var isRunning: Boolean = false
        private set

    fun setRunning(running: Boolean) { isRunning = running }

    fun setStatus(status: Status, detail: String = "") {
        _status.value = status
        if (detail.isNotBlank()) _detail.value = detail
    }

    fun setBotName(name: String) { _botName.value = name }

    /** Append a line to the rolling activity log (newest last, capped). */
    fun log(line: String) {
        val stamped = "${ts.format(Date())}  $line"
        _log.value = (_log.value + stamped).takeLast(LOG_CAP)
    }

    fun reset() {
        _status.value = Status.IDLE
        _detail.value = ""
        _botName.value = ""
        isRunning = false
    }
}
