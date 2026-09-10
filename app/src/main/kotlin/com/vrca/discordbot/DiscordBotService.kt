package com.vrca.discordbot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.vrca.BuildConfig
import com.vrca.R
import com.vrca.app.MainActivity
import com.vrca.app.startForegroundSafely
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Admin-only foreground service that runs a **Discord bot gateway client** natively
 * (raw gateway protocol over an OkHttp WebSocket — NOT a WebView, NOT discord.js).
 * It mirrors [com.vrca.vrchat.VrchatPipelineService]'s foreground + reconnect skeleton:
 * HELLO → heartbeat loop → IDENTIFY, RESUME on reconnect, backoff, START_STICKY, and
 * the shared swipe/OEM-kill guards so it stays alive like the rest of the app.
 *
 * On MESSAGE_CREATE it decides whether to respond (always for a mention or a reply to
 * the bot; probabilistically for ambient chatter with a per-channel cooldown), fires
 * the typing indicator, asks Cloudflare Workers AI ([DiscordBotAi]) for a reply, and
 * posts it back ([DiscordRest]). No conversation memory yet (v1 = single-turn).
 */
class DiscordBotService : Service() {

    companion object {
        private const val TAG = "DiscordBotService"
        const val ACTION_START = "com.vrca.DISCORD_BOT_START"
        const val ACTION_STOP = "com.vrca.DISCORD_BOT_STOP"

        private const val NOTIF_CHANNEL = "vrca_discord_bot"
        private const val NOTIF_ID = 1010  // distinct from the shared 1001 service notif

        private const val GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"

        // GUILDS(1<<0) | GUILD_MESSAGES(1<<9) | DIRECT_MESSAGES(1<<12) | MESSAGE_CONTENT(1<<15)
        private const val INTENTS = 1 or 512 or 4096 or 32768  // = 37377

        private const val MAX_BACKOFF_MS = 30_000L

        // Wait this long after the last triggering message in a channel before replying, so
        // a burst of quick messages is read together and answered ONCE (more human + cheaper).
        private const val DEBOUNCE_MS = 1500L

        // How often the personality reflection/mutation runs (reads recent chat, evolves the
        // trait store via the cheap 8B model). Periodic, NOT per-message, so cost stays tiny.
        private const val REFLECT_INTERVAL_MS = 20 * 60 * 1000L

        fun start(context: Context) {
            if (!BuildConfig.IS_ADMIN_BUILD) return
            context.startService(Intent(context, DiscordBotService::class.java).apply {
                action = ACTION_START
            })
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DiscordBotService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val okClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)   // gateway is long-lived; app-level heartbeat keeps it
            .retryOnConnectionFailure(true)
            .build()
    }

    @Volatile private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var reflectionJob: Job? = null

    // Newest channel we've seen a real (human) message in — the reflection loop reads it.
    @Volatile private var lastActiveChannel: String = ""

    // Gateway session state
    @Volatile private var lastSeq: Int? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var resumeUrl: String? = null
    @Volatile private var botId: String = ""
    @Volatile private var heartbeatIntervalMs: Long = 41_250L
    @Volatile private var ackPending: Boolean = false
    @Volatile private var reconnectAttempt: Int = 0

    private lateinit var cfg: DiscordBotStore.Config

    // Per-channel cooldown for ambient (unaddressed) replies.
    private val ambientCooldown = ConcurrentHashMap<String, Long>()
    // Per-channel pending (debounced) reply job — a new trigger cancels + reschedules it.
    private val pendingReplyByChannel = ConcurrentHashMap<String, Job>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                DiscordBotStore.setEnabled(this, false)
                teardown("Stopped")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // Null intent = Android sticky-restart. Honour a deliberate swipe / OEM
                // kill guard exactly like the pipeline service so a swiped app stays dead.
                if (intent == null &&
                    (com.vrca.app.AppShutdown.isManualKillFresh(this) ||
                        com.vrca.app.AppShutdown.isSwipedAway(this))) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!BuildConfig.IS_ADMIN_BUILD) { stopSelf(); return START_NOT_STICKY }
                // A sticky restart must respect the admin having switched the bot OFF.
                if (intent == null && !DiscordBotStore.isEnabled(this)) {
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
                }

                cfg = DiscordBotStore.load(this)
                if (!cfg.isComplete) {
                    DiscordBotState.setStatus(
                        DiscordBotState.Status.FAILED,
                        "Add your bot token + Cloudflare account id and Workers AI token"
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }

                val started = startForegroundSafely(NOTIF_ID, buildNotif("Connecting…"), TAG)
                if (!started) return START_NOT_STICKY

                // Already connected (duplicate ACTION_START / sticky restart while alive) → no-op.
                if (webSocket == null) {
                    DiscordBotStore.setEnabled(this, true)
                    DiscordBotState.setRunning(true)
                    DiscordBotState.setStatus(DiscordBotState.Status.CONNECTING, "Connecting to Discord…")
                    DiscordBotState.log("Starting bot")
                    reconnectAttempt = 0
                    openSocket(resume = false)
                    startReflectionLoop()
                }
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        teardown("Destroyed")
        scope.cancel()
        super.onDestroy()
    }

    // ── Gateway connection ────────────────────────────────────────────────

    private fun openSocket(resume: Boolean) {
        val base = if (resume && resumeUrl != null) "${resumeUrl!!.trimEnd('/')}/?v=10&encoding=json"
        else GATEWAY_URL
        val req = Request.Builder().url(base).build()
        ackPending = false
        webSocket = okClient.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) {
                try { handleFrame(text, resumeWanted = resume) } catch (_: Exception) { }
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                scheduleReconnect(resume = code != 1000)
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                DiscordBotState.log("Gateway error: ${t.message ?: t.javaClass.simpleName}")
                scheduleReconnect(resume = true)
            }
        })
    }

    private fun handleFrame(text: String, resumeWanted: Boolean) {
        val frame = JSONObject(text)
        val op = frame.optInt("op", -1)
        if (!frame.isNull("s")) lastSeq = frame.optInt("s")

        when (op) {
            10 -> { // HELLO
                heartbeatIntervalMs = frame.optJSONObject("d")?.optLong("heartbeat_interval")
                    ?.takeIf { it > 1000 } ?: 41_250L
                startHeartbeat()
                if (resumeWanted && sessionId != null && lastSeq != null) sendResume() else sendIdentify()
            }
            11 -> ackPending = false            // heartbeat ACK
            1 -> sendHeartbeat()                // server asked for an immediate heartbeat
            7 -> scheduleReconnect(resume = true)   // Reconnect
            9 -> {                                  // Invalid Session
                val resumable = frame.optBoolean("d", false)
                if (!resumable) { sessionId = null; lastSeq = null }
                scheduleReconnect(resume = resumable, delayMs = Random.nextLong(1000, 5000))
            }
            0 -> dispatch(frame.optString("t"), frame.optJSONObject("d"))
        }
    }

    private fun dispatch(type: String?, d: JSONObject?) {
        when (type) {
            "READY" -> {
                botId = d?.optJSONObject("user")?.optString("id").orEmpty()
                val name = d?.optJSONObject("user")?.optString("username").orEmpty()
                sessionId = d?.optString("session_id")
                resumeUrl = d?.optString("resume_gateway_url")?.takeIf { it.isNotBlank() }
                reconnectAttempt = 0
                DiscordBotState.setBotName(name)
                DiscordBotState.setStatus(DiscordBotState.Status.CONNECTED, "Connected as $name")
                DiscordBotState.log("Ready as $name")
            }
            "RESUMED" -> {
                reconnectAttempt = 0
                DiscordBotState.setStatus(DiscordBotState.Status.CONNECTED, "Reconnected")
                DiscordBotState.log("Session resumed")
            }
            "MESSAGE_CREATE" -> if (d != null) handleMessage(d)
        }
    }

    private fun sendIdentify() {
        val payload = JSONObject().put("op", 2).put("d", JSONObject()
            .put("token", cfg.botToken)
            .put("intents", INTENTS)
            .put("properties", JSONObject()
                .put("os", "android").put("browser", "vrc-a").put("device", "vrc-a")))
        webSocket?.send(payload.toString())
    }

    private fun sendResume() {
        val payload = JSONObject().put("op", 6).put("d", JSONObject()
            .put("token", cfg.botToken)
            .put("session_id", sessionId)
            .put("seq", lastSeq ?: 0))
        webSocket?.send(payload.toString())
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            // Initial jittered delay per Discord's guidance.
            delay((heartbeatIntervalMs * Random.nextDouble(0.0, 1.0)).toLong())
            while (true) {
                if (ackPending) {
                    // No ACK since the last beat → zombie socket. Force a resume-reconnect.
                    DiscordBotState.log("Heartbeat not ACKed — reconnecting")
                    scheduleReconnect(resume = true)
                    return@launch
                }
                sendHeartbeat()
                delay(heartbeatIntervalMs)
            }
        }
    }

    private fun sendHeartbeat() {
        ackPending = true
        webSocket?.send(JSONObject().put("op", 1).put("d", lastSeq ?: JSONObject.NULL).toString())
    }

    private fun scheduleReconnect(resume: Boolean, delayMs: Long = -1L) {
        if (reconnectJob?.isActive == true) return   // dedup (onFailure + onClosed both fire)
        heartbeatJob?.cancel()
        try { webSocket?.close(if (resume) 4000 else 1000, "reconnect") } catch (_: Exception) {}
        webSocket = null
        DiscordBotState.setStatus(DiscordBotState.Status.RECONNECTING, "Reconnecting…")
        reconnectJob = scope.launch {
            val backoff = if (delayMs >= 0) delayMs
            else (1000L shl reconnectAttempt.coerceAtMost(5)).coerceAtMost(MAX_BACKOFF_MS) +
                Random.nextLong(0, 1000)
            reconnectAttempt++
            delay(backoff)
            openSocket(resume = resume && sessionId != null)
        }
    }

    // ── Message handling / trigger gate ───────────────────────────────────

    private fun handleMessage(d: JSONObject) {
        val author = d.optJSONObject("author") ?: return
        if (author.optBoolean("bot", false)) return               // never reply to bots (incl. self)
        val authorId = author.optString("id")
        if (authorId.isBlank() || authorId == botId) return

        val channelId = d.optString("channel_id")
        val messageId = d.optString("id")
        if (channelId.isBlank()) return
        lastActiveChannel = channelId
        val rawContent = d.optString("content")

        val mentioned = messageMentionsBot(d, rawContent)
        val ref = d.optJSONObject("referenced_message")
        val repliedToBot = botId.isNotBlank() &&
            ref?.optJSONObject("author")?.optString("id") == botId
        val addressed = mentioned || repliedToBot

        if (!addressed) {
            // Ambient eligibility: readable text + a live dice roll + channel off cooldown.
            val eligible = rawContent.isNotBlank() &&
                cfg.ambientPercent > 0 &&
                Random.nextInt(100) < cfg.ambientPercent &&
                ambientCooldownOk(channelId)
            if (!eligible) return
            ambientCooldown[channelId] = System.currentTimeMillis()
        }

        // Text fed to the model: drop the bot's own mention, resolve other @mentions to names.
        val userText = DiscordRest.resolveMentions(
            stripBotMentions(rawContent), d.optJSONArray("mentions")
        ).ifBlank { "(they pinged you with no message)" }
        val authorName = author.optString("global_name").ifBlank { author.optString("username") }
            .ifBlank { "someone" }

        // Reply-chain: capture the exact message being replied to (even if it's old).
        var refTurn: DiscordBotAi.Turn? = null
        var refId: String? = null
        if (ref != null) {
            val rAuthor = ref.optJSONObject("author")
            val rText = DiscordRest.resolveMentions(
                stripBotMentions(ref.optString("content")), ref.optJSONArray("mentions")
            )
            if (rText.isNotBlank() && rAuthor != null) {
                refId = ref.optString("id")
                refTurn = DiscordBotAi.Turn(
                    isBot = rAuthor.optString("id") == botId,
                    name = rAuthor.optString("global_name").ifBlank { rAuthor.optString("username") }
                        .ifBlank { "someone" },
                    text = rText
                )
            }
        }

        // Debounce: coalesce a burst into ONE reply to the latest triggering message. A new
        // trigger cancels the pending job and reschedules; the stale (completed/cancelled)
        // map entry is simply overwritten — cancel() on a finished job is a no-op.
        pendingReplyByChannel[channelId]?.cancel()
        pendingReplyByChannel[channelId] = scope.launch {
            delay(DEBOUNCE_MS)
            respondNow(channelId, messageId, addressed, userText, authorName, refTurn, refId)
        }
    }

    private suspend fun respondNow(
        channelId: String, messageId: String, addressed: Boolean,
        userText: String, authorName: String,
        refTurn: DiscordBotAi.Turn?, refId: String?
    ) {
        // A trivial addressed message ("lol", an emoji) → react like a person, no AI call.
        if (addressed && isTrivial(userText)) {
            DiscordRest.addReaction(cfg.botToken, channelId, messageId, pickEmoji(userText))
            DiscordBotState.log("reacted to $authorName")
            return
        }

        val history = buildHistory(channelId, messageId, authorName, userText, refTurn, refId)

        // Ambient: a cheap 8B gate decides whether chiming in is worth it (else stay quiet).
        if (!addressed && !DiscordBotAi.shouldChimeIn(cfg, history)) {
            DiscordBotState.log("stayed quiet")
            return
        }

        DiscordRest.triggerTyping(cfg.botToken, channelId)
        when (val res = DiscordBotAi.reply(cfg, history, PersonalityStore.snapshot(this))) {
            is DiscordBotAi.Result.Ok -> {
                // Human pacing: a short beat scaled to reply length before sending.
                delay((res.text.length * 20L).coerceIn(400L, 2500L))
                val err = DiscordRest.sendMessage(
                    cfg.botToken, channelId, res.text,
                    replyToMessageId = if (addressed) messageId else null
                )
                DiscordBotState.log(
                    if (err == null) "↩ $authorName: ${res.text.take(60)}" else "Send failed: $err"
                )
            }
            is DiscordBotAi.Result.Error -> DiscordBotState.log("AI error: ${res.message}")
        }
    }

    // ── Human-touch helpers ───────────────────────────────────────────────

    private val triviaWords = setOf(
        "lol", "lmao", "lmfao", "lel", "kek", "ok", "kk", "k", "nice", "fr", "real",
        "bruh", "true", "yep", "yup", "nah", "w", "l", "based", "same", "mood"
    )
    /** A message not worth a full generated reply (react instead): blank, ≤2 chars, a known
     *  filler word, or pure punctuation/emoji. */
    private fun isTrivial(text: String): Boolean {
        val n = text.trim().lowercase().trimEnd('!', '.', '?', ' ')
        if (n.isEmpty()) return true
        if (n in triviaWords) return true
        return n.length <= 2 || n.none { it.isLetterOrDigit() }
    }
    private val reactEmojis = listOf("👍", "😂", "💀", "👀", "🔥", "😭", "🙏")
    private fun pickEmoji(text: String): String {
        val t = text.lowercase()
        return when {
            t.contains("lol") || t.contains("lmao") || t.contains("😂") || t.contains("🤣") -> "😂"
            t.contains("💀") -> "💀"
            else -> reactEmojis.random()
        }
    }

    // ── Personality reflection (mutation loop) ────────────────────────────

    private fun startReflectionLoop() {
        if (reflectionJob?.isActive == true) return
        reflectionJob = scope.launch {
            while (true) {
                delay(REFLECT_INTERVAL_MS)
                try { runReflection() } catch (_: Exception) { }
            }
        }
    }

    /** One mutation pass: read recent chat in the most-active channel, ask the cheap model to
     *  evolve the personality, and merge it (reinforce/decay) into [PersonalityStore]. */
    private suspend fun runReflection() {
        val ch = lastActiveChannel
        if (ch.isBlank() || !::cfg.isInitialized || !cfg.isComplete) return
        val recent = DiscordRest.fetchRecentMessages(cfg.botToken, ch, 20)
        if (recent.size < 4) return   // not enough chat to learn anything yet
        val transcript = recent.joinToString("\n") {
            "${it.authorName}: ${stripBotMentions(it.content)}"
        }.take(3000)
        val proposed = DiscordBotAi.reflect(cfg, PersonalityStore.load(this).map { it.text }, transcript)
        if (proposed.isNotEmpty()) {
            PersonalityStore.applyReflection(this, proposed)
            DiscordBotState.log("personality evolved (${proposed.size} traits)")
        }
    }

    /** The bot's short-term memory: the last N channel messages (chronological), plus the
     *  replied-to message when it's outside that window (reply-chain), with the triggering
     *  message appended last. Only OUR bot's messages become assistant turns; other bots
     *  are name-prefixed user turns. `historyLimit<=0`/failed fetch → single-turn. */
    private suspend fun buildHistory(
        channelId: String, currentMsgId: String, authorName: String, currentText: String,
        refTurn: DiscordBotAi.Turn?, refId: String?
    ): List<DiscordBotAi.Turn> {
        val turns = ArrayList<DiscordBotAi.Turn>()
        val seen = HashSet<String>()
        if (cfg.historyLimit > 0) {
            val recent = DiscordRest.fetchRecentMessages(cfg.botToken, channelId, cfg.historyLimit)
            for (m in recent) {
                if (m.id == currentMsgId) continue          // appended last, deterministically
                seen.add(m.id)
                val text = stripBotMentions(m.content)      // content already mention-resolved
                if (text.isBlank()) continue
                turns.add(DiscordBotAi.Turn(isBot = m.authorId == botId, name = m.authorName, text = text))
            }
        }
        // Ensure the replied-to message is present even if older than the fetched window.
        if (refTurn != null && (refId == null || refId !in seen)) turns.add(refTurn)
        turns.add(DiscordBotAi.Turn(false, authorName, currentText))
        return turns
    }

    private fun messageMentionsBot(d: JSONObject, content: String): Boolean {
        if (botId.isBlank()) return false
        val mentions = d.optJSONArray("mentions") ?: JSONArray()
        for (i in 0 until mentions.length()) {
            if (mentions.optJSONObject(i)?.optString("id") == botId) return true
        }
        return content.contains("<@$botId>") || content.contains("<@!$botId>")
    }

    private fun stripBotMentions(content: String): String =
        content.replace("<@$botId>", "").replace("<@!$botId>", "").trim()

    private fun ambientCooldownOk(channelId: String): Boolean {
        val last = ambientCooldown[channelId] ?: 0L
        return System.currentTimeMillis() - last >= cfg.ambientCooldownSec * 1000L
    }

    // ── Foreground plumbing ───────────────────────────────────────────────

    private fun teardown(reason: String) {
        heartbeatJob?.cancel(); heartbeatJob = null
        reconnectJob?.cancel(); reconnectJob = null
        reflectionJob?.cancel(); reflectionJob = null
        try { webSocket?.close(1000, reason) } catch (_: Exception) {}
        webSocket = null
        DiscordBotState.setRunning(false)
        DiscordBotState.setStatus(DiscordBotState.Status.IDLE, reason)
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        NotificationChannel(NOTIF_CHANNEL, "Discord Bot", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Shows while the Discord AI bot is connected"
            setShowBadge(false)
            setSound(null, null)
            nm.createNotificationChannel(this)
        }
    }

    private fun buildNotif(status: String): Notification {
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(R.drawable.ic_notif_sync)
            .setContentTitle("VRC-A Discord Bot")
            .setContentText(status)
            .setContentIntent(tap)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setGroup("vrca_service")
            .build()
    }
}
