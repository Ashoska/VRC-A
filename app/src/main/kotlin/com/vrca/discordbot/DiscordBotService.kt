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
        // Someone asking Cardinal to recall a person / event / memory (drives full-card injection).
        private val RECALL_RE = Regex("(?i)(who is|who'?s |what do you know|know about|tell me about|info(rmation)? (on|about)|remember (when|that)|about (him|her|them|me)\\b|from your (profile|memory|notes)|in your (profile|memory))")
        private val SELF_RECALL_RE = Regex("(?i)(about me\\b|know about me|my (profile|info|memory)|remember me|who am i)")
        // Explicit "react to my message with X" request → react, no reply.
        private val REACT_REQ_RE = Regex("(?i)^\\s*(can you |could you |please |pls |plz )?react\\b|react (to )?(this|that|my|the)\\b")
        private val SHORTCODE_RE = Regex(":([a-zA-Z0-9_]{2,32}):|<a?:([a-zA-Z0-9_]{2,32}):\\d+>")
        private val UNICODE_EMOJI_RE = Regex("[\\uD83C-\\uDBFF][\\uDC00-\\uDFFF]|[\\u2600-\\u27BF\\u2B00-\\u2BFF\\u2190-\\u21FF\\u2900-\\u297F]")

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
    private val backoffUntil = ConcurrentHashMap<String, Long>()         // channel -> back-off deadline
    private val observeUnreplied = ConcurrentHashMap<String, Int>()      // channel -> msgs since last observe
    private val lastObserveAt = ConcurrentHashMap<String, Long>()        // channel -> last observe ms
    private val activityWindow = ConcurrentHashMap<String, ArrayDeque<Long>>() // channel -> recent msg times
    private val recentBotReplies = ConcurrentHashMap<String, ArrayDeque<String>>() // channel -> last replies
    private val lastCoveredId = ConcurrentHashMap<String, Long>()        // channel -> max message id a reply has already seen/answered
    private val routeJobs = ConcurrentHashMap.newKeySet<Job>()           // in-flight route coroutines (cancelled on teardown)
    // Our recent message ids (reaction-learning): id -> the user we replied to.
    private val recentBotMsgIds = object : LinkedHashMap<String, String>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 80
    }

    private data class MsgCtx(
        val channelId: String, val messageId: String, val authorId: String, val authorName: String,
        val userText: String, val addressed: Boolean,
        val refTurn: DiscordBotAi.Turn?, val refId: String?,
        val hasImage: Boolean, val refChannels: List<String>,
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
                ChannelInfoStore.putGuildChannels(d?.optJSONArray("channels"))
                DiscordBotState.log("Loaded ${EmojiConvert.customNames().size} emojis, ${ChannelInfoStore.size()} channels")
            }
            "CHANNEL_CREATE", "CHANNEL_UPDATE" ->
                if (d != null) ChannelInfoStore.putGuildChannels(JSONArray().put(d))
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

        val userMentionsResolved = DiscordRest.resolveMentions(stripBotMentions(rawContent), d.optJSONArray("mentions"))
        // Which other channels does this message point at (e.g. "did you see that in #media")?
        val refChannels = ChannelInfoStore.referencedIds(userMentionsResolved)
        // Resolve <#id> to #name for readability once the ids are captured.
        val userTextRaw = ChannelInfoStore.resolveMentions(userMentionsResolved)
        val hasImage = imageAttached(d.optJSONArray("attachments"))

        // Recognise + reinforce any shared-culture / channel-bit reference in what people say.
        ServerMemoryStore.reinforceReferenced(this, userTextRaw, now)
        ChannelMemoryStore.reinforceReferenced(this, channelId, userTextRaw, now)

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

        val userTextBase = userTextRaw.ifBlank { if (hasImage) "" else "(they pinged you with no message)" }
        // A situational cue so the model + channel-bit retrieval know an image was posted.
        val userText = if (hasImage) (userTextBase + " (posted an image)").trim() else userTextBase
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

        val ctx = MsgCtx(channelId, messageId, authorId, authorName, tokenize(userText), addressed, refTurn, refId, hasImage, refChannels)

        // No upfront wait: fire immediately. Ordering is the per-channel reply mutex; a follow-up
        // that lands while a reply is generating is either folded into the fresh in-lock context or
        // caught by the free "already covered" check — so latency is never the coalescing mechanism.
        val job = scope.launch { try { route(ctx) } catch (_: Exception) { } }
        routeJobs.add(job)
        job.invokeOnCompletion { routeJobs.remove(job) }
    }

    /** True if the message carries an image attachment (the "posted an image" situational cue). */
    private fun imageAttached(attachments: JSONArray?): Boolean {
        if (attachments == null) return false
        for (i in 0 until attachments.length()) {
            val a = attachments.optJSONObject(i) ?: continue
            if (a.optString("content_type").startsWith("image")) return true
            if (Regex("(?i)\\.(png|jpe?g|gif|webp)$").containsMatchIn(a.optString("filename"))) return true
        }
        return false
    }

    private suspend fun route(ctx: MsgCtx) {
        val rung = DiscordBotState.currentRung()
        if (rung == DiscordBotState.Rung.SILENT) { trace(ctx, "silent", "heuristic", "dropped", "budget spent"); return }

        // Trivial addressed message → a human just reacts, no model call.
        if (ctx.addressed && isTrivial(ctx.userText)) { reactTo(ctx, pickEmoji(ctx.userText)); return }

        // Explicit "react to my message with X" → do exactly that (custom :name: or unicode), no
        // reply, no model call. Fixes Cardinal typing `react: [:mpreg:]` as a MESSAGE instead of reacting.
        if (ctx.addressed) {
            val asked = parseReactRequest(ctx.userText)
            if (asked != null) {
                for (e in asked) { reactTo(ctx, e); delay(250) }
                trace(ctx, "react-req", "heuristic", "react", asked.joinToString(" "))
                return
            }
        }
        if (rung == DiscordBotState.Rung.REACT_ONLY) {
            if (ctx.addressed) reactTo(ctx, pickEmoji(ctx.userText))
            else trace(ctx, "budget", "ladder", "dropped", "react-only rung")
            return
        }

        // Everything real happens under the per-channel lock (finish-the-thought), and the context
        // is fetched INSIDE it — so a message that queued while a reply was generating sees the
        // freshest state, and the ambient/covered decision is made against reality, not a stale snapshot.
        val mutex = channelMutex.getOrPut(ctx.channelId) { Mutex() }
        mutex.withLock { generateLocked(ctx, rung) }
    }

    private class Built(
        val turns: List<DiscordBotAi.Turn>,
        val ids: Set<String>,
        val nameToId: Map<String, String>,
        val keywords: Set<String>,
        val targetIsLatest: Boolean,   // nothing newer than the message we're answering
        val maxSeenId: Long,           // highest message id folded in (for the "already covered" check)
        val emphasizeIds: Set<String>, // people the message ASKED ABOUT → inject their FULL card
    )

    private suspend fun generateLocked(ctx: MsgCtx, rung: DiscordBotState.Rung) {
        val built = buildContext(ctx)   // fresh: folds in anything sent while this was queued
        var short = rung == DiscordBotState.Rung.TRIM || rung == DiscordBotState.Rung.CHEAP

        if (!ctx.addressed) {
            val plan = DiscordBotAi.director(cfg, built.turns)
            charge(DiscordBotLimits.EST_NEURONS_CHEAP)
            when (plan?.action) {
                null, DiscordBotAi.Act.IGNORE -> { trace(ctx, "ambient", "director", "ignore", plan?.let { "no add" } ?: "no plan"); return }
                DiscordBotAi.Act.REACT -> { reactTo(ctx, plan.emoji.ifBlank { pickEmoji(ctx.userText) }); return }
                DiscordBotAi.Act.REPLY -> { ambientCooldown[ctx.channelId] = System.currentTimeMillis(); if (plan.short) short = true }
            }
        } else {
            // Addressed dequeue re-check (zero cost, no upfront wait): if we JUST replied to this
            // person, only continue when THIS message is genuinely new — i.e. it arrived AFTER the
            // last reply's context. A message the last reply already saw is a fragment we've covered.
            val ukey = "${ctx.channelId}:${ctx.authorId}"
            if (System.currentTimeMillis() - (perUserReplyAt[ukey] ?: 0L) < DiscordBotLimits.PER_USER_REPLY_COOLDOWN_MS) {
                val thisId = ctx.messageId.toLongOrNull() ?: 0L
                if (thisId <= (lastCoveredId[ctx.channelId] ?: 0L)) {
                    trace(ctx, "covered", "heuristic", "dropped", "already answered in last reply"); return
                }
                short = true   // a real follow-up since the last reply → keep it tight, like a person
            }
        }

        val model = if (rung == DiscordBotState.Rung.CHEAP) DiscordBotLimits.CHEAP_MODEL else cfg.model
        val turns = if (rung == DiscordBotState.Rung.TRIM || rung == DiscordBotState.Rung.CHEAP)
            built.turns.takeLast(DiscordBotLimits.CONTEXT_RAW_TURNS / 2) else built.turns

        val rc = DiscordBotAi.ReplyCtx(
            selfDigest = PersonalityStore.snapshot(this),
            channelInfo = ChannelInfoStore.describe(ctx.channelId),
            serverCulture = buildServerCulture(ctx.channelId, ctx.userText),
            channelBits = ChannelMemoryStore.pickDeployable(this, ctx.channelId, ctx.userText, situationCues(ctx), System.currentTimeMillis()).orEmpty(),
            crossRef = buildCrossRef(ctx),
            cardsBlock = UserMemoryStore.activeCardsBlock(this, built.ids, built.keywords, built.emphasizeIds),
            summary = ConversationStore.summary(ctx.channelId),
            replyingTo = ctx.authorName,
            lastBotReplies = recentBotReplies[ctx.channelId]?.toList() ?: emptyList(),
            emojiHint = emojiHint(),
            langHint = detectLang(ctx.userText),
            shortHint = short,
        )

        if (!cfg.shadowMode) DiscordRest.triggerTyping(cfg.botToken, ctx.channelId)  // instant typing
        when (val res = DiscordBotAi.reply(cfg, model, turns, rc)) {
            is DiscordBotAi.ReplyResult.Ok -> {
                charge(DiscordBotLimits.EST_NEURONS_REPLY)
                val outText = EmojiConvert.convert(res.text)
                val now = System.currentTimeMillis()
                if (cfg.shadowMode) {
                    DiscordBotState.log("shadow ↩ ${ctx.authorName}: ${outText.take(60)}")
                    trace(ctx, "reply", "shadow", "shadow", outText.take(90))
                } else {
                    // Only @-reply-thread when the convo moved past their message (out-of-order);
                    // if we're answering the latest message, just talk plainly like a person.
                    val out = DiscordRest.send(cfg.botToken, ctx.channelId, outText,
                        replyToMessageId = if (ctx.addressed && !built.targetIsLatest) ctx.messageId else null)
                    if (out.error == null) {
                        out.messageId?.let { synchronized(recentBotMsgIds) { recentBotMsgIds[it] = ctx.authorId } }
                        lastBotPostMs[ctx.channelId] = now
                        rememberBotReply(ctx.channelId, res.text)
                        trace(ctx, "reply", if (model == cfg.model) "70B" else "8B", "reply", outText.take(90))
                    } else {
                        DiscordBotState.log("Send failed: ${out.error}")
                        trace(ctx, "reply", "70B", "send-fail", out.error)
                    }
                }
                afterReply(ctx, built, now)
                DiscordBotState.bumpReplied()
                perUserReplyAt["${ctx.channelId}:${ctx.authorId}"] = now
                // Everything up to here is answered — so an earlier same-person fragment won't
                // re-fire, while a genuinely newer message still will (the "already covered" check).
                lastCoveredId[ctx.channelId] = maxOf(lastCoveredId[ctx.channelId] ?: 0L, built.maxSeenId)
            }
            is DiscordBotAi.ReplyResult.Error -> {
                DiscordBotState.log("AI error: ${res.message}")
                trace(ctx, "reply", "70B", "error", res.message)
            }
        }
    }

    /**
     * After a reply lands: mark the author seen and schedule the cheap 8B LEARN pass (throttled) so
     * memory/summary/self/culture are written OFF the reply's hot path. The 70B reply itself no
     * longer carries any bookkeeping tail — halving its cost and killing the rushed-inline junk-memory
     * bugs. The learn pass reuses the reply's OWN transcript (no extra fetch).
     */
    private fun afterReply(ctx: MsgCtx, built: Built, now: Long) {
        UserMemoryStore.touch(this, ctx.authorId, ctx.authorName)
        observeUnreplied[ctx.channelId] = 0   // a reply IS a catch-up for the pileup path
        if (DiscordBotState.currentRung() == DiscordBotState.Rung.SILENT) return
        if (now - (lastObserveAt[ctx.channelId] ?: 0L) < DiscordBotLimits.OBSERVER_MIN_INTERVAL_MS) return
        lastObserveAt[ctx.channelId] = now
        val turns = built.turns
        val nameToId = built.nameToId
        val channelId = ctx.channelId
        scope.launch { try { runLearn(channelId, turns, nameToId) } catch (_: Exception) { } }
    }

    /** The LEARN pass over an already-built transcript (reply path): one 8B call, no extra fetch. */
    private suspend fun runLearn(channelId: String, turns: List<DiscordBotAi.Turn>, nameToId: Map<String, String>) {
        if (!::cfg.isInitialized || !cfg.isComplete) return
        val usable = turns.filter { it.text.isNotBlank() }
        if (usable.size < 3) return
        val obs = DiscordBotAi.observe(cfg, usable, ConversationStore.summary(channelId)) ?: return
        charge(DiscordBotLimits.EST_NEURONS_CHEAP)
        applyObservation(channelId, obs, nameToId, System.currentTimeMillis())
        DiscordBotState.log("learned $channelId (${obs.memDeltas.size} people)")
    }

    /** Persist one observation (shared by the reply-driven learn pass AND the pileup observer). */
    private fun applyObservation(
        channelId: String, obs: DiscordBotAi.Observation, nameToId: Map<String, String>, now: Long,
    ) {
        val globalIndex = UserMemoryStore.nameIndex(this)
        if (obs.summary.isNotBlank()) ConversationStore.updateSummary(this, channelId, obs.summary, now)
        for (md in obs.memDeltas) {
            val id = resolveObserveAbout(md.about, nameToId, globalIndex)
            if (id != null && id != botId) UserMemoryStore.applyDelta(this, id, md.about, md.json)
        }
        if (obs.serverEvent.isNotBlank()) ServerMemoryStore.remember(this, obs.serverEvent, now)
        if (obs.channelBit.isNotBlank()) ChannelMemoryStore.remember(this, channelId, obs.channelBit, now)
        if (obs.selfTrait.isNotBlank() || obs.selfMood.isNotBlank())
            PersonalityStore.noteSelf(this, obs.selfTrait.ifBlank { null }, null, obs.selfMood.ifBlank { null })
        if (obs.selfMood.isNotBlank()) DiscordBotState.setMood(obs.selfMood)
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
        val nameToId = HashMap<String, String>()
        recent.forEach { if (it.authorId != botId) nameToId[it.authorName.lowercase().trim()] = it.authorId }
        val obs = DiscordBotAi.observe(cfg, turns, ConversationStore.summary(channelId)) ?: return
        charge(DiscordBotLimits.EST_NEURONS_CHEAP)
        applyObservation(channelId, obs, nameToId, System.currentTimeMillis())
        DiscordBotState.log("observed $channelId (${obs.memDeltas.size} people)")
    }

    // ── Context assembly (ordered, retrieval-limited) ─────────────────────

    private suspend fun buildContext(ctx: MsgCtx): Built {
        val turns = ArrayList<DiscordBotAi.Turn>()
        val ids = HashSet<String>().apply { add(ctx.authorId) }
        val nameToId = HashMap<String, String>()
        nameToId[ctx.authorName.lowercase().trim()] = ctx.authorId
        val seen = HashSet<String>()
        var newerExists = false
        val ctxIdL = ctx.messageId.toLongOrNull() ?: Long.MAX_VALUE
        var maxSeenId = ctx.messageId.toLongOrNull() ?: 0L
        if (cfg.contextTurns > 0) {
            val recent = DiscordRest.fetchRecentMessages(cfg.botToken, ctx.channelId, cfg.contextTurns)
            for (m in recent) {
                val mid = m.id.toLongOrNull() ?: 0L
                if (mid > maxSeenId) maxSeenId = mid
                if (m.id == ctx.messageId) continue
                // A higher snowflake id = a message that arrived AFTER the one we're answering.
                if (mid > ctxIdL) newerExists = true
                seen.add(m.id)
                val text = tokenize(ChannelInfoStore.resolveMentions(stripBotMentions(m.content)))
                if (text.isBlank()) continue
                ids.add(m.authorId)
                if (m.authorId != botId) nameToId[m.authorName.lowercase().trim()] = m.authorId
                turns.add(DiscordBotAi.Turn(m.authorId == botId, m.authorName, text))
            }
        }
        if (ctx.refTurn != null && (ctx.refId == null || ctx.refId !in seen)) turns.add(ctx.refTurn)
        turns.add(DiscordBotAi.Turn(false, ctx.authorName, ctx.userText))
        val keywords = keywordsOf(buildString { turns.takeLast(4).forEach { append(it.text).append(' ') }; append(ctx.userText) })

        // Absent-person / recall injection: if the message NAMES someone Cardinal has a card for
        // (even if they aren't speaking), pull their card into context so it can actually answer
        // instead of claiming it doesn't know them. On a recall query ("who is X", "info about X",
        // "from your profile") the named card renders FULL (all facts).
        val emphasize = HashSet<String>()
        val isRecall = RECALL_RE.containsMatchIn(ctx.userText)
        val nameIndex = UserMemoryStore.nameIndex(this)
        if (nameIndex.isNotEmpty()) {
            val normMsg = " " + ctx.userText.lowercase()
                .replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
            var injected = 0
            for ((nameKey, uid) in nameIndex) {
                if (uid == botId || uid == ctx.authorId || nameKey.length < 3) continue
                if (normMsg.contains(" $nameKey ")) {
                    ids.add(uid); if (isRecall) emphasize.add(uid)
                    if (++injected >= 3) break   // bound the prompt
                }
            }
        }
        if (isRecall && SELF_RECALL_RE.containsMatchIn(ctx.userText)) emphasize.add(ctx.authorId)

        return Built(turns, ids, nameToId, keywords, targetIsLatest = !newerExists, maxSeenId = maxSeenId, emphasizeIds = emphasize)
    }

    /** Situational cue words for channel-bit retrieval (e.g. someone posted an image). */
    private fun situationCues(ctx: MsgCtx): Set<String> =
        if (ctx.hasImage) setOf("image", "images", "pic", "post", "posting") else emptySet()

    /**
     * When a message points at another channel ("did you see that in #media"), pull that channel's
     * recent messages as a clearly-labelled block so Cardinal can talk ABOUT it without confusing it
     * with the current channel. Bounded (one channel, [DiscordBotLimits.CROSSREF_FETCH] messages) and
     * permission-aware: if the bot can't read it, it says so instead of hallucinating.
     */
    private suspend fun buildCrossRef(ctx: MsgCtx): String {
        val targetCh = ctx.refChannels.firstOrNull { it != ctx.channelId } ?: return ""
        val name = ChannelInfoStore.name(targetCh) ?: return ""   // unknown → skip silently
        val msgs = DiscordRest.fetchRecentMessages(cfg.botToken, targetCh, DiscordBotLimits.CROSSREF_FETCH)
        if (msgs.isEmpty()) return "#$name: (you can't see this channel right now)"
        val sb = StringBuilder("#").append(name).append(":\n")
        for (m in msgs) {
            val t = tokenize(ChannelInfoStore.resolveMentions(stripBotMentions(m.content)))
            if (t.isBlank()) continue
            sb.append("- ").append(if (m.authorId == botId) "Cardinal" else m.authorName).append(": ").append(t).append('\n')
        }
        return sb.toString().trim()
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

    /**
     * Resolve a learn-pass "about" NAME/nickname to a Discord id — EXACT match only (conversation
     * names first, then the global name/nickname index). A fuzzy substring fallback was the cause of
     * one person's facts/nicknames landing on a DIFFERENT person (John Pork getting Michael's nicks),
     * so an unresolved name is DROPPED rather than guessed — safer than contaminating a card.
     */
    private fun resolveObserveAbout(about: String, nameToId: Map<String, String>, global: Map<String, String>): String? {
        val key = about.lowercase().trim(); if (key.isBlank()) return null
        nameToId[key]?.let { return it }
        global[key]?.let { return it }
        return null
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

    private fun emojiHint(): String =
        EmojiConvert.customNames().take(12).joinToString(", ") { ":$it:" }

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
    /**
     * Parse an explicit react request into emoji tokens (custom `:name:` / `<:name:id>` or unicode).
     * Returns null unless the message clearly asks Cardinal to REACT (so normal chat is untouched) AND
     * carries at least one emoji. The tokens pass through [EmojiConvert.reactionToken] at react time.
     */
    private fun parseReactRequest(text: String): List<String>? {
        if (!REACT_REQ_RE.containsMatchIn(text)) return null
        val out = LinkedHashSet<String>()
        SHORTCODE_RE.findAll(text).forEach { m ->
            val name = m.groupValues[1].ifBlank { m.groupValues[2] }
            if (name.isNotBlank()) out.add(name)
        }
        UNICODE_EMOJI_RE.findAll(text).forEach { out.add(it.value) }
        return if (out.isEmpty()) null else out.take(4).toList()
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
        routeJobs.forEach { it.cancel() }; routeJobs.clear()
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
