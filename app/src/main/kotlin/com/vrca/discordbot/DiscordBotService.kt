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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Admin-only foreground service running **Cardinal** — a self-hosted Discord gateway bot
 * (raw gateway over OkHttp WebSocket) with a self-grown personality, per-user memory, and a
 * cheap heuristic→director→reply routing path. See [PersonalityStore]/[UserMemoryStore]/
 * [DiscordBotAi]/[DiscordBotLimits].
 *
 * Routing per message: a FREE heuristic decides ignore / react / reply / consider-ambient.
 * A single-person addressed message goes straight to the 70B [DiscordBotAi.reply] (whose
 * `%%MEM%%` tail also updates memory). An ambient/ambiguous message pays one cheap 8B
 * [DiscordBotAi.director] call first. Per-USER debounce buckets mean B messaging never cancels
 * A's pending reply, and a per-channel reply mutex sequences them (finish-the-thought). A daily
 * neuron budget degrades gracefully. Reactions to the bot's own posts feed sentiment back.
 */
class DiscordBotService : Service() {

    companion object {
        private const val TAG = "DiscordBotService"
        const val ACTION_START = "com.vrca.DISCORD_BOT_START"
        const val ACTION_STOP = "com.vrca.DISCORD_BOT_STOP"

        private const val NOTIF_CHANNEL = "vrca_discord_bot"
        private const val NOTIF_ID = 1010

        private const val GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"

        // GUILDS(1) | GUILD_MESSAGES(1<<9) | GUILD_MESSAGE_REACTIONS(1<<10) |
        // DIRECT_MESSAGES(1<<12) | MESSAGE_CONTENT(1<<15) = 38401
        private const val INTENTS = 1 or 512 or 1024 or 4096 or 32768

        private const val MAX_BACKOFF_MS = 30_000L

        private val URL_RE = Regex("""https?://\S+""")

        fun start(context: Context) {
            if (!BuildConfig.IS_ADMIN_BUILD) return
            context.startService(Intent(context, DiscordBotService::class.java).apply { action = ACTION_START })
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DiscordBotService::class.java).apply { action = ACTION_STOP })
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val okClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Volatile private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var reflectionJob: Job? = null

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

    // Routing state
    private val ambientCooldown = ConcurrentHashMap<String, Long>()   // channel -> last ambient ms
    private val lastBotPostMs = ConcurrentHashMap<String, Long>()      // channel -> last bot post ms
    private val perUserReplyAt = ConcurrentHashMap<String, Long>()     // channel:user -> last reply ms
    private val threadSummary = ConcurrentHashMap<String, String>()    // channel -> rolling summary
    private val channelMutex = ConcurrentHashMap<String, Mutex>()      // channel -> reply serialiser
    private val pendingByUser = ConcurrentHashMap<String, Job>()       // channel:user -> debounce job
    // Our recent message ids (reaction-learning): id -> the user we replied to.
    private val recentBotMsgIds = object : LinkedHashMap<String, String>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 80
    }

    private data class MsgCtx(
        val channelId: String, val messageId: String, val authorId: String, val authorName: String,
        val userText: String, val addressed: Boolean,
        val refTurn: DiscordBotAi.Turn?, val refId: String?,
    )

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
                if (intent == null &&
                    (com.vrca.app.AppShutdown.isManualKillFresh(this) ||
                        com.vrca.app.AppShutdown.isSwipedAway(this))) {
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
                }
                if (!BuildConfig.IS_ADMIN_BUILD) { stopSelf(); return START_NOT_STICKY }
                if (intent == null && !DiscordBotStore.isEnabled(this)) {
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
                }

                cfg = DiscordBotStore.load(this)
                if (!cfg.isComplete) {
                    DiscordBotState.setStatus(
                        DiscordBotState.Status.FAILED,
                        "Add your bot token + Cloudflare account id and Workers AI token"
                    )
                    stopSelf(); return START_NOT_STICKY
                }

                val started = startForegroundSafely(NOTIF_ID, buildNotif("Connecting…"), TAG)
                if (!started) return START_NOT_STICKY

                if (webSocket == null) {
                    DiscordBotStore.setEnabled(this, true)
                    DiscordBotState.setRunning(true)
                    DiscordBotState.setStatus(DiscordBotState.Status.CONNECTING, "Connecting to Discord…")
                    DiscordBotState.setMood(PersonalityStore.mood(this))
                    DiscordBotState.log("Starting Cardinal")
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
        val base = if (resume && resumeUrl != null) "${resumeUrl!!.trimEnd('/')}/?v=10&encoding=json" else GATEWAY_URL
        val req = Request.Builder().url(base).build()
        ackPending = false
        webSocket = okClient.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) {
                try { handleFrame(text, resumeWanted = resume) } catch (_: Exception) { }
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { scheduleReconnect(resume = code != 1000) }
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
            10 -> {
                heartbeatIntervalMs = frame.optJSONObject("d")?.optLong("heartbeat_interval")
                    ?.takeIf { it > 1000 } ?: 41_250L
                startHeartbeat()
                if (resumeWanted && sessionId != null && lastSeq != null) sendResume() else sendIdentify()
            }
            11 -> ackPending = false
            1 -> sendHeartbeat()
            7 -> scheduleReconnect(resume = true)
            9 -> {
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
            "MESSAGE_REACTION_ADD" -> if (d != null) handleReactionAdd(d)
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
            .put("token", cfg.botToken).put("session_id", sessionId).put("seq", lastSeq ?: 0))
        webSocket?.send(payload.toString())
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            delay((heartbeatIntervalMs * Random.nextDouble(0.0, 1.0)).toLong())
            while (true) {
                if (ackPending) { DiscordBotState.log("Heartbeat not ACKed — reconnecting"); scheduleReconnect(resume = true); return@launch }
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
        if (reconnectJob?.isActive == true) return
        heartbeatJob?.cancel()
        try { webSocket?.close(if (resume) 4000 else 1000, "reconnect") } catch (_: Exception) {}
        webSocket = null
        DiscordBotState.setStatus(DiscordBotState.Status.RECONNECTING, "Reconnecting…")
        reconnectJob = scope.launch {
            val backoff = if (delayMs >= 0) delayMs
            else (1000L shl reconnectAttempt.coerceAtMost(5)).coerceAtMost(MAX_BACKOFF_MS) + Random.nextLong(0, 1000)
            reconnectAttempt++
            delay(backoff)
            openSocket(resume = resume && sessionId != null)
        }
    }

    // ── Message routing ───────────────────────────────────────────────────

    private fun handleMessage(d: JSONObject) {
        val author = d.optJSONObject("author") ?: return
        if (author.optBoolean("bot", false)) return
        val authorId = author.optString("id")
        if (authorId.isBlank() || authorId == botId) return

        val channelId = d.optString("channel_id")
        val messageId = d.optString("id")
        if (channelId.isBlank()) return
        if (channelId in cfg.mutedChannels) return
        lastActiveChannel = channelId
        DiscordBotState.bumpSeen()

        val rawContent = d.optString("content")
        val mentioned = messageMentionsBot(d, rawContent)
        val ref = d.optJSONObject("referenced_message")
        val repliedToBot = botId.isNotBlank() && ref?.optJSONObject("author")?.optString("id") == botId
        val addressed = mentioned || repliedToBot

        // ── Free heuristic prefilter ──
        if (!addressed) {
            // Clearly aimed at someone else (a reply to another user, no bot mention) → stay out.
            val repliesToOther = ref != null && !repliedToBot
            if (repliesToOther) return
            val eligible = rawContent.isNotBlank() &&
                cfg.ambientPercent > 0 &&
                Random.nextInt(100) < cfg.ambientPercent &&
                ambientCooldownOk(channelId) &&
                !postedRecently(channelId)
            if (!eligible) return
        }

        val userText = DiscordRest.resolveMentions(stripBotMentions(rawContent), d.optJSONArray("mentions"))
            .ifBlank { "(they pinged you with no message)" }
        val authorName = author.optString("global_name").ifBlank { author.optString("username") }.ifBlank { "someone" }

        var refTurn: DiscordBotAi.Turn? = null
        var refId: String? = null
        if (ref != null) {
            val rAuthor = ref.optJSONObject("author")
            val rText = DiscordRest.resolveMentions(stripBotMentions(ref.optString("content")), ref.optJSONArray("mentions"))
            if (rText.isNotBlank() && rAuthor != null) {
                refId = ref.optString("id")
                refTurn = DiscordBotAi.Turn(
                    isBot = rAuthor.optString("id") == botId,
                    name = rAuthor.optString("global_name").ifBlank { rAuthor.optString("username") }.ifBlank { "someone" },
                    text = tokenize(rText),
                )
            }
        }

        val ctx = MsgCtx(channelId, messageId, authorId, authorName, tokenize(userText), addressed, refTurn, refId)

        // ── Per-USER debounce bucket: B's message never cancels A's pending reply. ──
        val bucket = "$channelId:$authorId"
        pendingByUser[bucket]?.cancel()
        pendingByUser[bucket] = scope.launch {
            delay(DiscordBotLimits.PER_USER_DEBOUNCE_MS)
            try { route(ctx) } catch (_: Exception) { }
        }
    }

    private suspend fun route(ctx: MsgCtx) {
        val rung = DiscordBotState.currentRung()
        if (rung == DiscordBotState.Rung.SILENT) { trace(ctx, "silent", "heuristic", "dropped", "budget spent"); return }

        // Trivial addressed message → a human reacts, no model call.
        if (ctx.addressed && isTrivial(ctx.userText)) { reactTo(ctx, pickEmoji(ctx.userText)); return }
        if (rung == DiscordBotState.Rung.REACT_ONLY) {
            if (ctx.addressed) reactTo(ctx, pickEmoji(ctx.userText))
            else trace(ctx, "budget", "ladder", "dropped", "react-only rung")
            return
        }

        val turns = buildContext(ctx)
        var short = rung == DiscordBotState.Rung.TRIM || rung == DiscordBotState.Rung.CHEAP

        if (!ctx.addressed) {
            // Ambient → one cheap director call decides + emits the rolling summary.
            val plan = DiscordBotAi.director(cfg, turns.first, addressed = false, prevSummary = threadSummary[ctx.channelId] ?: "")
            DiscordBotState.noteNeurons(DiscordBotLimits.EST_NEURONS_CHEAP)
            if (plan != null && plan.summary.isNotBlank()) threadSummary[ctx.channelId] = plan.summary
            when (plan?.action) {
                null, DiscordBotAi.Act.IGNORE -> { trace(ctx, "ambient", "director", "ignore", plan?.summary ?: "no plan"); return }
                DiscordBotAi.Act.REACT -> { reactTo(ctx, plan.emoji.ifBlank { pickEmoji(ctx.userText) }); return }
                DiscordBotAi.Act.REPLY -> { ambientCooldown[ctx.channelId] = System.currentTimeMillis(); if (plan.lengthShort) short = true }
            }
        }

        generateAndSend(ctx, turns, rung, short)
    }

    private suspend fun generateAndSend(
        ctx: MsgCtx, ctxData: Pair<List<DiscordBotAi.Turn>, Set<String>>,
        rung: DiscordBotState.Rung, short: Boolean,
    ) {
        // Per-person cooldown so one user can't monopolise the bot.
        val ukey = "${ctx.channelId}:${ctx.authorId}"
        val lastReply = perUserReplyAt[ukey] ?: 0L
        if (System.currentTimeMillis() - lastReply < DiscordBotLimits.PER_USER_REPLY_COOLDOWN_MS) {
            trace(ctx, "cooldown", "heuristic", "dropped", "per-user cooldown"); return
        }

        val mutex = channelMutex.getOrPut(ctx.channelId) { Mutex() }
        mutex.withLock {   // finish-the-thought: A completes before B in this channel
            val model = if (rung == DiscordBotState.Rung.CHEAP) DiscordBotLimits.CHEAP_MODEL else cfg.model
            val turns = if (rung == DiscordBotState.Rung.TRIM || rung == DiscordBotState.Rung.CHEAP)
                ctxData.first.takeLast(4) else ctxData.first
            val digest = PersonalityStore.snapshot(this)
            val cards = UserMemoryStore.activeCardsBlock(this, ctxData.second)
            val summary = threadSummary[ctx.channelId] ?: ""

            if (!cfg.shadowMode) DiscordRest.triggerTyping(cfg.botToken, ctx.channelId)
            when (val res = DiscordBotAi.reply(cfg, model, digest, cards, summary, turns, short)) {
                is DiscordBotAi.ReplyResult.Ok -> {
                    DiscordBotState.noteNeurons(DiscordBotLimits.EST_NEURONS_REPLY)
                    delay((res.text.length * 20L).coerceIn(400L, 2500L))   // human pacing
                    if (cfg.shadowMode) {
                        DiscordBotState.log("shadow ↩ ${ctx.authorName}: ${res.text.take(60)}")
                        trace(ctx, "reply", "shadow", "shadow", res.text.take(90))
                    } else {
                        val out = DiscordRest.send(cfg.botToken, ctx.channelId, res.text,
                            replyToMessageId = if (ctx.addressed) ctx.messageId else null)
                        if (out.error == null) {
                            out.messageId?.let { synchronized(recentBotMsgIds) { recentBotMsgIds[it] = ctx.authorId } }
                            lastBotPostMs[ctx.channelId] = System.currentTimeMillis()
                            trace(ctx, "reply", "70B", "reply", res.text.take(90))
                        } else {
                            DiscordBotState.log("Send failed: ${out.error}")
                            trace(ctx, "reply", "70B", "send-fail", out.error)
                        }
                    }
                    // Memory: touch + apply the reply's own deltas tail (no extra model call).
                    UserMemoryStore.touch(this, ctx.authorId, ctx.authorName)
                    res.memDelta?.let { UserMemoryStore.applyDelta(this, ctx.authorId, ctx.authorName, it) }
                    DiscordBotState.bumpReplied()
                    perUserReplyAt[ukey] = System.currentTimeMillis()
                }
                is DiscordBotAi.ReplyResult.Error -> {
                    DiscordBotState.log("AI error: ${res.message}")
                    trace(ctx, "reply", "70B", "error", res.message)
                }
            }
        }
    }

    private suspend fun reactTo(ctx: MsgCtx, emoji: String) {
        if (cfg.shadowMode) { trace(ctx, "react", "heuristic", "shadow", emoji); return }
        DiscordRest.addReaction(cfg.botToken, ctx.channelId, ctx.messageId, emoji)
        DiscordBotState.bumpReacted()
        trace(ctx, "react", "heuristic", "react", emoji)
    }

    /**
     * Build the reply context: a tight, tokenised transcript (URLs stripped, per-message capped)
     * plus the set of participant ids so only their memory cards get loaded. Returns (turns, ids).
     */
    private suspend fun buildContext(ctx: MsgCtx): Pair<List<DiscordBotAi.Turn>, Set<String>> {
        val turns = ArrayList<DiscordBotAi.Turn>()
        val ids = HashSet<String>().apply { add(ctx.authorId) }
        val seen = HashSet<String>()
        if (cfg.contextTurns > 0) {
            val recent = DiscordRest.fetchRecentMessages(cfg.botToken, ctx.channelId, cfg.contextTurns)
            for (m in recent) {
                if (m.id == ctx.messageId) continue
                seen.add(m.id)
                val text = tokenize(stripBotMentions(m.content))
                if (text.isBlank()) continue
                ids.add(m.authorId)
                turns.add(DiscordBotAi.Turn(m.authorId == botId, m.authorName, text))
            }
        }
        if (ctx.refTurn != null && (ctx.refId == null || ctx.refId !in seen)) turns.add(ctx.refTurn)
        turns.add(DiscordBotAi.Turn(false, ctx.authorName, ctx.userText))
        return turns to ids
    }

    private fun handleReactionAdd(d: JSONObject) {
        val messageId = d.optString("message_id")
        val reactorId = d.optString("user_id")
        if (messageId.isBlank() || reactorId.isBlank() || reactorId == botId) return
        val mine = synchronized(recentBotMsgIds) { recentBotMsgIds.containsKey(messageId) }
        if (!mine) return
        val emoji = d.optJSONObject("emoji")?.optString("name").orEmpty()
        val sentiment = when {
            emoji in POSITIVE_REACTS -> "warm (reacted ${emoji})"
            emoji in NEGATIVE_REACTS -> "cool (reacted ${emoji})"
            else -> return
        }
        // Learn from how the room reacts to Cardinal: nudge the reactor's card sentiment.
        scope.launch {
            val cur = UserMemoryStore.load(this@DiscordBotService, reactorId) ?: UserMemoryStore.Card(id = reactorId)
            UserMemoryStore.save(this@DiscordBotService, cur.copy(sentiment = sentiment))
            DiscordBotState.log("$reactorId reacted ${emoji} to Cardinal")
        }
    }

    // ── Reflection (self-evolution, off the hot path) ─────────────────────

    private fun startReflectionLoop() {
        if (reflectionJob?.isActive == true) return
        reflectionJob = scope.launch {
            while (true) {
                delay(DiscordBotLimits.REFLECT_INTERVAL_MS)
                try { runReflection() } catch (_: Exception) { }
            }
        }
    }

    private suspend fun runReflection() {
        val ch = lastActiveChannel
        if (ch.isBlank() || !::cfg.isInitialized || !cfg.isComplete) return
        val recent = DiscordRest.fetchRecentMessages(cfg.botToken, ch, DiscordBotLimits.REFLECT_SAMPLE_MSGS)
        if (recent.size < 4) return
        val transcript = recent.joinToString("\n") { "${it.authorName}: ${tokenize(stripBotMentions(it.content))}" }.take(3000)
        val self = PersonalityStore.load(this)
        val r = DiscordBotAi.reflect(cfg, self.traits.map { it.text }, self.style, self.mood, transcript) ?: return
        DiscordBotState.noteNeurons(DiscordBotLimits.EST_NEURONS_CHEAP)
        PersonalityStore.applyReflection(this, r.traits, r.style, r.mood, r.episode.ifBlank { null })
        DiscordBotState.setMood(PersonalityStore.mood(this))
        DiscordBotState.log("personality evolved (${r.traits.size} traits)")
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private val triviaWords = setOf(
        "lol", "lmao", "lmfao", "lel", "kek", "ok", "kk", "k", "nice", "fr", "real",
        "bruh", "true", "yep", "yup", "nah", "w", "l", "based", "same", "mood"
    )
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

    private fun tokenize(content: String): String =
        URL_RE.replace(content, "[link]").trim().take(DiscordBotLimits.MAX_MSG_CHARS)

    private fun messageMentionsBot(d: JSONObject, content: String): Boolean {
        if (botId.isBlank()) return false
        val mentions = d.optJSONArray("mentions") ?: JSONArray()
        for (i in 0 until mentions.length()) if (mentions.optJSONObject(i)?.optString("id") == botId) return true
        return content.contains("<@$botId>") || content.contains("<@!$botId>")
    }

    private fun stripBotMentions(content: String): String =
        content.replace("<@$botId>", "").replace("<@!$botId>", "").trim()

    private fun ambientCooldownOk(channelId: String): Boolean =
        System.currentTimeMillis() - (ambientCooldown[channelId] ?: 0L) >= cfg.ambientCooldownSec * 1000L

    private fun postedRecently(channelId: String): Boolean =
        System.currentTimeMillis() - (lastBotPostMs[channelId] ?: 0L) < DiscordBotLimits.SELF_RECENT_QUIET_MS

    private fun trace(ctx: MsgCtx, score: String, plan: String, action: String, detail: String) {
        DiscordBotState.addTrace(DiscordBotState.Trace(
            atMs = System.currentTimeMillis(),
            channel = ctx.channelId,
            author = ctx.authorName,
            score = if (ctx.addressed) "addressed/$score" else "ambient/$score",
            plan = plan, action = action, detail = detail,
        ))
    }

    // ── Foreground plumbing ───────────────────────────────────────────────

    private fun teardown(reason: String) {
        heartbeatJob?.cancel(); heartbeatJob = null
        reconnectJob?.cancel(); reconnectJob = null
        reflectionJob?.cancel(); reflectionJob = null
        pendingByUser.values.forEach { it.cancel() }; pendingByUser.clear()
        try { webSocket?.close(1000, reason) } catch (_: Exception) {}
        webSocket = null
        DiscordBotState.setRunning(false)
        DiscordBotState.setStatus(DiscordBotState.Status.IDLE, reason)
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        NotificationChannel(NOTIF_CHANNEL, "Discord Bot", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Shows while the Discord AI bot is connected"
            setShowBadge(false); setSound(null, null)
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
            .setOngoing(true).setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setGroup("vrca_service")
            .build()
    }
}

private val POSITIVE_REACTS = setOf("👍", "😂", "🤣", "🔥", "❤️", "🙏", "😭", "💯", "😎", "✅")
private val NEGATIVE_REACTS = setOf("👎", "🤡", "💩", "🙄", "❌")
