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
 * Admin-only foreground service running **Cardinal** — a self-hosted Discord gateway bot with a
 * self-grown personality, per-user memory, shared server culture, a revivable topic archive, cheap
 * event-driven routing, and a real Cloudflare neuron budget.
 *
 * Per message: a FREE heuristic decides ignore / react / reply / consider-ambient. An addressed
 * single-person message goes straight to the 70B [DiscordBotAi.reply] (whose `%%MEM%%` tail does
 * ALL bookkeeping in one call — memory, summary, self-nudge, server culture). An ambient moment
 * pays one cheap 8B [DiscordBotAi.director]. When unreplied activity piles up, a cheap 8B
 * [DiscordBotAi.observe] catches up memory/summary/culture. Per-USER debounce + per-channel reply
 * mutex keep it fluid; a daily neuron budget degrades gracefully. Custom + standard emojis render
 * via [EmojiConvert]; the gateway subscribes GUILD_EMOJIS to learn the server's set.
 */
class DiscordBotService : Service() {

    companion object {
        private const val TAG = "DiscordBotService"
        const val ACTION_START = "com.vrca.DISCORD_BOT_START"
        const val ACTION_STOP = "com.vrca.DISCORD_BOT_STOP"

        private const val NOTIF_CHANNEL = "vrca_discord_bot"
        private const val NOTIF_ID = 1010

        private const val GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"

        // GUILDS(1) | GUILD_EMOJIS(1<<3) | GUILD_MESSAGES(1<<9) | GUILD_MESSAGE_REACTIONS(1<<10) |
        // DIRECT_MESSAGES(1<<12) | MESSAGE_CONTENT(1<<15) = 38409
        private const val INTENTS = 1 or 8 or 512 or 1024 or 4096 or 32768

        private const val MAX_BACKOFF_MS = 30_000L
        private val URL_RE = Regex("""https?://\S+""")
        private val STOP_RE = Regex("(?i)\\b(stop|shut ?up|shush|be quiet|leave me alone|stop replying|go away|not now|quit it)\\b")

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
    private var budgetJob: Job? = null

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
    private val ambientCooldown = ConcurrentHashMap<String, Long>()     // channel -> last ambient reply ms
    private val lastBotPostMs = ConcurrentHashMap<String, Long>()        // channel -> last bot post ms
    private val perUserReplyAt = ConcurrentHashMap<String, Long>()       // channel:user -> last reply ms
    private val channelMutex = ConcurrentHashMap<String, Mutex>()        // channel -> reply serialiser
    private val pendingByUser = ConcurrentHashMap<String, Job>()         // channel:user -> debounce job
    private val backoffUntil = ConcurrentHashMap<String, Long>()         // channel -> back-off deadline
    private val observeUnreplied = ConcurrentHashMap<String, Int>()      // channel -> msgs since last observe
    private val lastObserveAt = ConcurrentHashMap<String, Long>()        // channel -> last observe ms
    private val activityWindow = ConcurrentHashMap<String, ArrayDeque<Long>>() // channel -> recent msg times
    private val recentBotReplies = ConcurrentHashMap<String, ArrayDeque<String>>() // channel -> last replies
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
                    DiscordBotState.setNeuronsAbsolute(NeuronBudget.current(this))
                    DiscordBotState.log("Starting Cardinal")
                    reconnectAttempt = 0
                    openSocket(resume = false)
                    startBudgetLoop()
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
            "GUILD_CREATE" -> {
                EmojiConvert.putGuildEmojis(d?.optJSONArray("emojis"))
                DiscordBotState.log("Loaded ${EmojiConvert.customNames().size} server emojis")
            }
            "GUILD_EMOJIS_UPDATE" -> EmojiConvert.putGuildEmojis(d?.optJSONArray("emojis"))
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

    // ── Budget sync (real Cloudflare usage) ───────────────────────────────

    private fun startBudgetLoop() {
        if (budgetJob?.isActive == true) return
        budgetJob = scope.launch {
            while (true) {
                delay(DiscordBotLimits.USAGE_SYNC_INTERVAL_MS)
                try {
                    val real = NeuronBudget.syncReal(this@DiscordBotService, cfg)
                    if (real != null) DiscordBotState.setNeuronsAbsolute(real)
                } catch (_: Exception) { }
            }
        }
    }

    /** Charge a model call to the persisted budget + reflect the authoritative total in the UI. */
    private fun charge(est: Long) {
        DiscordBotState.setNeuronsAbsolute(NeuronBudget.add(this, est))
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
        DiscordBotState.bumpSeen()

        val now = System.currentTimeMillis()
        noteActivity(channelId, now)
        ConversationStore.touch(channelId, now)
        observeUnreplied.merge(channelId, 1, Int::plus)

        val rawContent = d.optString("content")
        val mentioned = messageMentionsBot(d, rawContent)
        val ref = d.optJSONObject("referenced_message")
        val repliedToBot = botId.isNotBlank() && ref?.optJSONObject("author")?.optString("id") == botId
        val addressed = mentioned || repliedToBot

        val userTextRaw = DiscordRest.resolveMentions(stripBotMentions(rawContent), d.optJSONArray("mentions"))

        // Recognise + reinforce any shared-culture reference in what people say.
        ServerMemoryStore.reinforceReferenced(this, userTextRaw, now)

        // "Stop" directed at the bot → back off in this channel (don't be annoying).
        if (addressed && STOP_RE.containsMatchIn(userTextRaw)) {
            backoffUntil[channelId] = now + DiscordBotLimits.BACKOFF_MS
            DiscordBotState.log("backing off in $channelId"); return
        }

        // Consider an event-driven observer catch-up (cheap, off the reply path).
        maybeObserve(channelId, now)

        // ── Free heuristic prefilter ──
        if (!addressed) {
            if (now < (backoffUntil[channelId] ?: 0L)) return
            val eligible = rawContent.isNotBlank() &&
                cfg.ambientPercent > 0 &&
                Random.nextInt(100) < effectiveAmbient(channelId) &&
                ambientCooldownOk(channelId, now) &&
                !postedRecently(channelId, now)
            if (!eligible) return
        }

        val userText = userTextRaw.ifBlank { "(they pinged you with no message)" }
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

        // Trivial addressed message → a human just reacts, no model call.
        if (ctx.addressed && isTrivial(ctx.userText)) { reactTo(ctx, pickEmoji(ctx.userText)); return }
        if (rung == DiscordBotState.Rung.REACT_ONLY) {
            if (ctx.addressed) reactTo(ctx, pickEmoji(ctx.userText))
            else trace(ctx, "budget", "ladder", "dropped", "react-only rung")
            return
        }

        val built = buildContext(ctx)
        var short = rung == DiscordBotState.Rung.TRIM || rung == DiscordBotState.Rung.CHEAP

        if (!ctx.addressed) {
            val plan = DiscordBotAi.director(cfg, built.turns)
            charge(DiscordBotLimits.EST_NEURONS_CHEAP)
            when (plan?.action) {
                null, DiscordBotAi.Act.IGNORE -> { trace(ctx, "ambient", "director", "ignore", plan?.let { "no add" } ?: "no plan"); return }
                DiscordBotAi.Act.REACT -> { reactTo(ctx, plan.emoji.ifBlank { pickEmoji(ctx.userText) }); return }
                DiscordBotAi.Act.REPLY -> { ambientCooldown[ctx.channelId] = System.currentTimeMillis(); if (plan.short) short = true }
            }
        }

        generateAndSend(ctx, built, rung, short)
    }

    private class Built(
        val turns: List<DiscordBotAi.Turn>,
        val ids: Set<String>,
        val nameToId: Map<String, String>,
        val keywords: Set<String>,
    )

    private suspend fun generateAndSend(ctx: MsgCtx, built: Built, rung: DiscordBotState.Rung, short: Boolean) {
        val ukey = "${ctx.channelId}:${ctx.authorId}"
        val lastReply = perUserReplyAt[ukey] ?: 0L
        if (System.currentTimeMillis() - lastReply < DiscordBotLimits.PER_USER_REPLY_COOLDOWN_MS) {
            trace(ctx, "cooldown", "heuristic", "dropped", "per-user cooldown"); return
        }

        val mutex = channelMutex.getOrPut(ctx.channelId) { Mutex() }
        mutex.withLock {   // finish-the-thought: A completes before B in this channel
            val model = if (rung == DiscordBotState.Rung.CHEAP) DiscordBotLimits.CHEAP_MODEL else cfg.model
            val turns = if (rung == DiscordBotState.Rung.TRIM || rung == DiscordBotState.Rung.CHEAP)
                built.turns.takeLast(DiscordBotLimits.CONTEXT_RAW_TURNS / 2) else built.turns

            val rc = DiscordBotAi.ReplyCtx(
                selfDigest = PersonalityStore.snapshot(this),
                serverCulture = buildServerCulture(ctx.channelId, ctx.userText),
                cardsBlock = UserMemoryStore.activeCardsBlock(this, built.ids, built.keywords),
                summary = ConversationStore.summary(ctx.channelId),
                lastBotReplies = recentBotReplies[ctx.channelId]?.toList() ?: emptyList(),
                emojiHint = emojiHint(),
                langHint = detectLang(ctx.userText),
                shortHint = short,
            )

            if (!cfg.shadowMode) DiscordRest.triggerTyping(cfg.botToken, ctx.channelId)  // instant typing
            when (val res = DiscordBotAi.reply(cfg, model, turns, rc)) {
                is DiscordBotAi.ReplyResult.Ok -> {
                    charge(DiscordBotLimits.EST_NEURONS_REPLY)
                    delay((res.text.length * 16L).coerceIn(DiscordBotLimits.REPLY_DELAY_MIN_MS, DiscordBotLimits.REPLY_DELAY_MAX_MS))
                    val outText = EmojiConvert.convert(res.text)
                    val now = System.currentTimeMillis()
                    if (cfg.shadowMode) {
                        DiscordBotState.log("shadow ↩ ${ctx.authorName}: ${outText.take(60)}")
                        trace(ctx, "reply", "shadow", "shadow", outText.take(90))
                    } else {
                        val out = DiscordRest.send(cfg.botToken, ctx.channelId, outText,
                            replyToMessageId = if (ctx.addressed) ctx.messageId else null)
                        if (out.error == null) {
                            out.messageId?.let { synchronized(recentBotMsgIds) { recentBotMsgIds[it] = ctx.authorId } }
                            lastBotPostMs[ctx.channelId] = now
                            rememberBotReply(ctx.channelId, res.text)
                            // Real Discord reactions the model asked for (e.g. "react with fire + a server emoji").
                            for (e in res.reactEmojis) {
                                DiscordRest.addReaction(cfg.botToken, ctx.channelId, ctx.messageId, EmojiConvert.reactionToken(e))
                                DiscordBotState.bumpReacted()
                                delay(300)
                            }
                            trace(ctx, "reply", if (model == cfg.model) "70B" else "8B", "reply", outText.take(90))
                        } else {
                            DiscordBotState.log("Send failed: ${out.error}")
                            trace(ctx, "reply", "70B", "send-fail", out.error)
                        }
                    }
                    applyReplyTail(ctx, built, res, now)
                    observeUnreplied[ctx.channelId] = 0   // a reply IS a catch-up
                    DiscordBotState.bumpReplied()
                    perUserReplyAt[ukey] = now
                }
                is DiscordBotAi.ReplyResult.Error -> {
                    DiscordBotState.log("AI error: ${res.message}")
                    trace(ctx, "reply", "70B", "error", res.message)
                }
            }
        }
    }

    /** Apply everything the ONE reply call proposed: memory, summary, self-nudge, server culture. */
    private fun applyReplyTail(ctx: MsgCtx, built: Built, res: DiscordBotAi.ReplyResult.Ok, now: Long) {
        UserMemoryStore.touch(this, ctx.authorId, ctx.authorName)
        val globalIndex by lazy { UserMemoryStore.nameIndex(this) }
        for (md in res.memDeltas) {
            val id = resolveAbout(md.about, built.nameToId, globalIndex, ctx)
            if (id != null && id != botId) UserMemoryStore.applyDelta(this, id, md.about, md.json)
        }
        if (res.summary.isNotBlank()) ConversationStore.updateSummary(this, ctx.channelId, res.summary, now)
        if (res.selfTrait.isNotBlank() || res.selfStyle.isNotBlank() || res.selfMood.isNotBlank())
            PersonalityStore.noteSelf(this, res.selfTrait.ifBlank { null }, res.selfStyle.ifBlank { null }, res.selfMood.ifBlank { null })
        if (res.selfMood.isNotBlank()) DiscordBotState.setMood(res.selfMood)
        if (res.serverEvent.isNotBlank()) ServerMemoryStore.remember(this, res.serverEvent, now)
    }

    private suspend fun reactTo(ctx: MsgCtx, emoji: String) {
        if (cfg.shadowMode) { trace(ctx, "react", "heuristic", "shadow", emoji); return }
        DiscordRest.addReaction(cfg.botToken, ctx.channelId, ctx.messageId, EmojiConvert.reactionToken(emoji))
        DiscordBotState.bumpReacted()
        trace(ctx, "react", "heuristic", "react", emoji)
    }

    // ── Observer (event-driven memory/summary/culture catch-up) ───────────

    private fun maybeObserve(channelId: String, now: Long) {
        val unreplied = observeUnreplied[channelId] ?: 0
        if (unreplied < DiscordBotLimits.OBSERVER_MIN_NEW_MSGS) return
        if (now - (lastObserveAt[channelId] ?: 0L) < DiscordBotLimits.OBSERVER_MIN_INTERVAL_MS) return
        if (DiscordBotState.currentRung() == DiscordBotState.Rung.SILENT) return
        lastObserveAt[channelId] = now
        observeUnreplied[channelId] = 0
        scope.launch { try { runObserve(channelId) } catch (_: Exception) { } }
    }

    private suspend fun runObserve(channelId: String) {
        if (!::cfg.isInitialized || !cfg.isComplete) return
        val recent = DiscordRest.fetchRecentMessages(cfg.botToken, channelId, DiscordBotLimits.HISTORY_FETCH)
        if (recent.size < 4) return
        val turns = recent.filter { tokenize(stripBotMentions(it.content)).isNotBlank() }
            .map { DiscordBotAi.Turn(it.authorId == botId, it.authorName, tokenize(stripBotMentions(it.content))) }
        val globalIndex = UserMemoryStore.nameIndex(this)
        val nameToId = HashMap<String, String>()
        recent.forEach { if (it.authorId != botId) nameToId[it.authorName.lowercase().trim()] = it.authorId }
        val obs = DiscordBotAi.observe(cfg, turns, ConversationStore.summary(channelId)) ?: return
        charge(DiscordBotLimits.EST_NEURONS_CHEAP)
        val now = System.currentTimeMillis()
        if (obs.summary.isNotBlank()) ConversationStore.updateSummary(this, channelId, obs.summary, now)
        for (md in obs.memDeltas) {
            val id = resolveObserveAbout(md.about, nameToId, globalIndex)
            if (id != null && id != botId) UserMemoryStore.applyDelta(this, id, md.about, md.json)
        }
        if (obs.serverEvent.isNotBlank()) ServerMemoryStore.remember(this, obs.serverEvent, now)
        if (obs.selfTrait.isNotBlank() || obs.selfMood.isNotBlank())
            PersonalityStore.noteSelf(this, obs.selfTrait.ifBlank { null }, null, obs.selfMood.ifBlank { null })
        if (obs.selfMood.isNotBlank()) DiscordBotState.setMood(obs.selfMood)
        DiscordBotState.log("observed $channelId (${obs.memDeltas.size} people)")
    }

    // ── Context assembly (ordered, retrieval-limited) ─────────────────────

    private suspend fun buildContext(ctx: MsgCtx): Built {
        val turns = ArrayList<DiscordBotAi.Turn>()
        val ids = HashSet<String>().apply { add(ctx.authorId) }
        val nameToId = HashMap<String, String>()
        nameToId[ctx.authorName.lowercase().trim()] = ctx.authorId
        val seen = HashSet<String>()
        if (cfg.contextTurns > 0) {
            val recent = DiscordRest.fetchRecentMessages(cfg.botToken, ctx.channelId, cfg.contextTurns)
            for (m in recent) {
                if (m.id == ctx.messageId) continue
                seen.add(m.id)
                val text = tokenize(stripBotMentions(m.content))
                if (text.isBlank()) continue
                ids.add(m.authorId)
                if (m.authorId != botId) nameToId[m.authorName.lowercase().trim()] = m.authorId
                turns.add(DiscordBotAi.Turn(m.authorId == botId, m.authorName, text))
            }
        }
        if (ctx.refTurn != null && (ctx.refId == null || ctx.refId !in seen)) turns.add(ctx.refTurn)
        turns.add(DiscordBotAi.Turn(false, ctx.authorName, ctx.userText))
        val keywords = keywordsOf(buildString { turns.takeLast(4).forEach { append(it.text).append(' ') }; append(ctx.userText) })
        return Built(turns, ids, nameToId, keywords)
    }

    /** Server culture block = strongest core memories + those relevant now + any revived dead topic. */
    private fun buildServerCulture(channelId: String, text: String): String {
        val core = ServerMemoryStore.core(this)
        val rel = ServerMemoryStore.retrieveFor(this, text)
        val mem = LinkedHashSet<String>().apply { addAll(core); addAll(rel) }.toList()
        val topics = ConversationStore.reviveFor(this, channelId, text)
        val sb = StringBuilder()
        mem.forEach { sb.append("- ").append(it).append('\n') }
        if (topics.isNotEmpty()) {
            sb.append("Earlier here:\n")
            topics.forEach { sb.append("- ").append(it).append('\n') }
        }
        return sb.toString().trim()
    }

    /** Resolve a reply-tail "about" NAME/nickname to a Discord id (conversation first, then global). */
    private fun resolveAbout(about: String, nameToId: Map<String, String>, global: Map<String, String>, ctx: MsgCtx): String? {
        val key = about.lowercase().trim()
        if (key.isBlank()) return null
        nameToId[key]?.let { return it }
        global[key]?.let { return it }
        val hits = nameToId.entries.filter { key in it.key || it.key in key }
        if (hits.size == 1) return hits.first().value
        return if (key in ctx.authorName.lowercase()) ctx.authorId else null
    }

    private fun resolveObserveAbout(about: String, nameToId: Map<String, String>, global: Map<String, String>): String? {
        val key = about.lowercase().trim(); if (key.isBlank()) return null
        nameToId[key]?.let { return it }
        global[key]?.let { return it }
        val hits = nameToId.entries.filter { key in it.key || it.key in key }
        return if (hits.size == 1) hits.first().value else null
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
        scope.launch {
            val cur = UserMemoryStore.load(this@DiscordBotService, reactorId) ?: UserMemoryStore.Card(id = reactorId)
            UserMemoryStore.save(this@DiscordBotService, cur.copy(sentiment = sentiment))
            DiscordBotState.log("$reactorId reacted ${emoji} to Cardinal")
        }
    }

    // ── Chattiness + activity ─────────────────────────────────────────────

    private fun noteActivity(channelId: String, now: Long) {
        val dq = activityWindow.getOrPut(channelId) { ArrayDeque() }
        synchronized(dq) {
            dq.addLast(now)
            while (dq.isNotEmpty() && now - dq.first() > 60_000L) dq.removeFirst()
        }
    }

    private fun channelActivity(channelId: String): Int =
        activityWindow[channelId]?.let { synchronized(it) { it.size } } ?: 0

    /** Ambient chance scales with how busy the channel is (more chatter → a little more likely). */
    private fun effectiveAmbient(channelId: String): Int {
        val base = cfg.ambientPercent
        val boost = channelActivity(channelId).coerceAtMost(10)   // +0..+50%
        return (base * (1.0 + boost / 20.0)).toInt().coerceIn(0, 100)
    }

    private fun rememberBotReply(channelId: String, text: String) {
        val dq = recentBotReplies.getOrPut(channelId) { ArrayDeque() }
        synchronized(dq) {
            dq.addLast(text.take(DiscordBotLimits.MAX_MSG_CHARS))
            while (dq.size > DiscordBotLimits.ANTI_REPEAT_REPLIES) dq.removeFirst()
        }
    }

    // ── Language / emoji hints ────────────────────────────────────────────

    /** Nudge the reply language from the message's script (the person switching, per the rule). */
    private fun detectLang(text: String): String {
        val byScript = when {
            text.any { it in '぀'..'ヿ' || it in 'ㇰ'..'ㇿ' } -> "Japanese (日本語)"
            text.any { it in '가'..'힣' } -> "Korean (한국어)"
            text.any { it in '一'..'鿿' } -> "Chinese (中文)"
            text.any { it in 'Ѐ'..'ӿ' } -> "Russian (Русский)"
            text.any { it in '؀'..'ۿ' } -> "Arabic (العربية)"
            else -> ""
        }
        if (byScript.isNotBlank()) return byScript
        return ""   // Latin script → let Cardinal match naturally (English or the person's language)
    }

    private fun emojiHint(): String {
        val custom = EmojiConvert.customNames().take(20).joinToString(", ") { ":$it:" }
        return custom
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

    private val KW_STOP = setOf(
        "the","a","an","and","or","but","to","of","in","on","for","with","is","are","was","this",
        "that","it","you","your","they","just","like","lol","cardinal","what","why","how","when"
    )
    private fun keywordsOf(s: String): Set<String> =
        Regex("[\\p{L}\\p{N}]+").findAll(s.lowercase()).map { it.value }
            .filter { it.length >= 4 && it !in KW_STOP }.take(16).toHashSet()

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

    private fun ambientCooldownOk(channelId: String, now: Long): Boolean =
        now - (ambientCooldown[channelId] ?: 0L) >= cfg.ambientCooldownSec * 1000L

    private fun postedRecently(channelId: String, now: Long): Boolean =
        now - (lastBotPostMs[channelId] ?: 0L) < DiscordBotLimits.SELF_RECENT_QUIET_MS

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
        budgetJob?.cancel(); budgetJob = null
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
