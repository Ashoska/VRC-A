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
import kotlinx.coroutines.async
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
 * message goes straight to the 70B [DiscordBotAi.reply], whose prompt carries only what this moment
 * needs (who it's answering, the channel, and — when relevant — people named or talking, server
 * memories, the earlier-conversation summary). An ambient moment pays one cheap 8B
 * [DiscordBotAi.director] (at most every [DiscordBotLimits.DIRECTOR_MIN_GAP_MS] per channel). Memory is
 * written OFF the reply path by the 8B learn pass ([DiscordBotAi.observe]), which reads EVERY message
 * since the previous pass in batches, with the learned facts checked against what was actually said.
 * A per-channel reply mutex keeps it fluid; exact per-call neuron billing drives a daily budget that
 * degrades gracefully. Custom + standard emojis render via [EmojiConvert].
 */
class DiscordBotService : Service() {

    companion object {
        private const val TAG = "DiscordBotService"
        const val ACTION_START = "com.vrca.DISCORD_BOT_START"
        const val ACTION_STOP = "com.vrca.DISCORD_BOT_STOP"

        private const val NOTIF_CHANNEL = "vrca_discord_bot"
        private const val NOTIF_ID = 1010

        // GUILDS(1) | GUILD_EMOJIS(1<<3) | GUILD_MESSAGES(1<<9) | GUILD_MESSAGE_REACTIONS(1<<10) |
        // DIRECT_MESSAGES(1<<12) | MESSAGE_CONTENT(1<<15) = 38409
        private const val INTENTS = 1 or 8 or 512 or 1024 or 4096 or 32768

        private const val MAX_BACKOFF_MS = 30_000L
        private val URL_RE = Regex("""https?://\S+""")
        // "Stop" aimed at Cardinal: the whole (punctuation-free) message is a stop request ("stop",
        // "ok shut up pls", "go away"), or it contains an unmistakable one ("stop replying", "leave me
        // alone") that isn't negated ("don't stop", "can't shut up"). "stop by the event" is neither.
        private val STOP_WHOLE_RE = Regex("^((ok|okay|pls|please|just|bro|dude|omg|god|cardinal|lol|seriously|nah|no|yo|hey|man|now) )*" +
            "(stop|stop it|stop that|stop talking|stop replying|stop responding|stop pinging|stop now|shut up|shutup|shush|hush|" +
            "be quiet|go away|leave me alone|not now|quit it|enough|stfu|pipe down)" +
            "( (please|pls|now|cardinal|bro|dude|lol|already|omg|man|thanks|thx))*$")
        private val STOP_PHRASE_RE = Regex("\\b(stop (replying|responding|talking|pinging|messaging|posting)|shut up|stfu|leave me alone|go away|be quiet|pipe down)\\b")
        private val NEGATIONS = setOf("don't", "dont", "never", "can't", "cant", "won't", "wont", "not", "didn't", "didnt",
            "couldn't", "couldnt", "shouldn't", "shouldnt", "wouldn't", "wouldnt", "doesn't", "doesnt")
        // Short or everyday-word nicknames ("ali", "boss", "mom") only count as naming someone when the
        // message clearly points at a person (@name, name's, a question) — otherwise they pulled a
        // card into unrelated messages.
        private val COMMON_NICK_WORDS = setOf(
            "boss", "mom", "mum", "dad", "bro", "bruh", "dude", "man", "king", "queen", "babe", "baby", "sis", "kid",
            "chief", "doc", "cap", "captain", "champ", "buddy", "pal", "mate", "sir", "lady", "love", "hun", "bestie",
            "homie", "fam", "friend", "guy", "girl", "boy", "gamer", "legend", "goat", "daddy", "mommy", "papa", "mama",
            "honey", "sweetie", "sugar", "angel", "bear", "bunny", "cat", "dog", "fox", "wolf", "cutie", "nerd", "noob",
            "god", "lord", "master", "admin", "mod", "owner", "boo", "bae", "chat", "team", "crew", "gang",
        )
        // Someone asking Cardinal to recall a person / event / memory (drives full-card injection).
        private val RECALL_RE = Regex("(?i)(who is|who'?s |what do you know|know about|tell me about|info(rmation)? (on|about)|(^|\\b(you|u|hey|yo|cardinal|do you|dyou)\\W+)remember (when|that|the|how|what|who)|do you (know|remember)|about (him|her|them|me)\\b|from your (profile|memory|notes)|in your (profile|memory))")
        // Questions about the asker themself ("what do you remember about me", "what do i do again").
        private val SELF_RECALL_RE = Regex("(?i)(about me\\b|know about me|my (profile|info|memory)|remember me|who am i|what am i like|" +
            "where (do i live|am i from)|what('?s| is) my (job|name|cat|dog|pet|game|hobby|work|thing|deal)\\b|" +
            "what do i do( again| for (a )?living| for work)?\\s*(lol|lmao|haha)?\\s*\\??\\s*$)")
        // "who runs the events here?" — a question about people with nobody named → search the cards.
        private val WHO_Q_RE = Regex("(?i)(^|\\s)(who|whose)\\b[^?]*\\?")
        private const val EMOJI_PREFS = "vrca_discord_emoji"
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
    /** Bumped for every new socket AND whenever we abandon one, so late callbacks from an old
     *  socket (its close/failure can land seconds later) can never touch the live connection's state. */
    private val socketGen = java.util.concurrent.atomic.AtomicInteger(0)
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var budgetJob: Job? = null

    // Gateway session state
    @Volatile private var lastSeq: Int? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var resumeUrl: String? = null
    @Volatile private var botId: String = ""
    @Volatile private var botName: String = ""
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
    private val learnPending = ConcurrentHashMap<String, Int>()          // channel -> msgs since the last learn pass
    private val lastLearnAt = ConcurrentHashMap<String, Long>()          // channel -> last learn pass started
    private val lastLearnedId = ConcurrentHashMap<String, Long>()        // channel -> newest message id a pass has read
    private val learnInFlight = ConcurrentHashMap.newKeySet<String>()    // channels with a pass running
    private val learnLullJobs = ConcurrentHashMap<String, Job>()         // channel -> pending "went quiet" pass
    private val directorAt = ConcurrentHashMap<String, Long>()           // channel -> last director call
    private val ambientReactAt = ConcurrentHashMap<String, Long>()       // channel -> last unprompted reaction
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
                DiscordBotState.configureLadder(
                    cfg.dailyBudget, cfg.trimEnabled, cfg.cheapEnabled, cfg.reactOnlyEnabled, cfg.hardStopEnabled)
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
                    DiscordBotAi.billingSink = { n -> chargeExact(n) }
                    EmojiConvert.restoreUsage(getSharedPreferences(EMOJI_PREFS, MODE_PRIVATE).getString("usage", null))
                    ConversationStore.restore(this)
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
        val base = if (resume && resumeUrl != null) "${resumeUrl!!.trimEnd('/')}/?v=10&encoding=json" else BotEndpoints.gatewayUrl
        val req = Request.Builder().url(base).build()
        ackPending = false
        val gen = socketGen.incrementAndGet()
        val closeHandled = java.util.concurrent.atomic.AtomicBoolean(false)
        fun live() = gen == socketGen.get()
        webSocket = okClient.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) {
                if (!live()) return
                try { handleFrame(text, resumeWanted = resume) } catch (_: Exception) { }
            }
            // Discord closed the socket: answer the close right away (OkHttp won't on its own) and
            // act on the code now, instead of sitting deaf until a heartbeat goes un-ACKed.
            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                try { ws.close(1000, null) } catch (_: Exception) { }
                if (live() && closeHandled.compareAndSet(false, true)) handleServerClose(code, reason)
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (live() && closeHandled.compareAndSet(false, true)) handleServerClose(code, reason)
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (!live() || !closeHandled.compareAndSet(false, true)) return
                DiscordBotState.log("Gateway error: ${t.message ?: t.javaClass.simpleName}")
                scheduleReconnect(resume = true)
            }
        })
    }

    /**
     * Act on a gateway close code. Codes Discord documents as "don't reconnect" (bad token, bad
     * shard/version, invalid or not-allowed intents) stop with a plain reason in the admin tab
     * instead of retrying forever; 4007/4009 need a fresh session; everything else resumes.
     */
    private fun handleServerClose(code: Int, reason: String) {
        val fatal = when (code) {
            4004 -> "Discord rejected the bot token (4004). Paste a fresh token in Config, then press Start."
            4010, 4011 -> "Discord needs sharding for this bot ($code). Cardinal doesn't support that."
            4012 -> "Discord rejected the gateway version (4012)."
            4013 -> "Discord rejected the bot's intents (4013)."
            4014 -> "Discord refused the Message Content intent (4014). Turn on MESSAGE CONTENT INTENT " +
                "(and SERVER MEMBERS if asked) in the Discord Developer Portal → Bot, then press Start."
            else -> null
        }
        if (fatal != null) {
            heartbeatJob?.cancel(); reconnectJob?.cancel()
            socketGen.incrementAndGet(); webSocket = null
            DiscordBotState.log("Gateway closed $code: $fatal")
            DiscordBotState.setStatus(DiscordBotState.Status.FAILED, fatal)
            return
        }
        if (code == 4007 || code == 4009) { sessionId = null; lastSeq = null }
        DiscordBotState.log("Gateway closed $code${if (reason.isNotBlank()) " ($reason)" else ""} — reconnecting")
        scheduleReconnect(resume = code != 1000 && code != 1001 && sessionId != null)
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
                botName = d?.optJSONObject("user")?.optString("global_name")?.takeIf { it.isNotBlank() && it != "null" } ?: name
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
        val old = webSocket
        socketGen.incrementAndGet()   // the old socket's late callbacks are now ignored
        webSocket = null
        try { old?.close(if (resume) 4000 else 1000, "reconnect") } catch (_: Exception) {}
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

    // Workers AI reports the exact neurons of every call; whole neurons go to the day's budget and
    // the fraction carries over so tiny 8B calls (~0.3-2 neurons) still add up.
    private var neuronFraction = 0.0
    @Synchronized private fun chargeExact(neurons: Double) {
        if (neurons <= 0.0 || neurons.isNaN()) return
        neuronFraction += neurons
        val whole = kotlin.math.floor(neuronFraction).toLong()
        if (whole >= 1) { neuronFraction -= whole; charge(whole) }
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
        ConversationStore.touch(this, channelId, now)
        learnPending.merge(channelId, 1, Int::plus)

        val rawContent = d.optString("content")
        EmojiConvert.noteUsage(rawContent)
        persistEmojiUsageIfDirty()
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

        // Learn from the channel in batches (cheap, off the reply path).
        maybeLearn(channelId, now)

        // "Stop" directed at the bot → back off in this channel (don't be annoying).
        if (addressed && isStopRequest(userTextRaw)) {
            backoffUntil[channelId] = now + DiscordBotLimits.BACKOFF_MS
            DiscordBotState.log("backing off in $channelId")
            val who = author.optString("global_name").ifBlank { author.optString("username") }.ifBlank { "someone" }
            DiscordBotState.addTrace(DiscordBotState.Trace(now, channelId, who, "addressed/stop", "heuristic", "back-off",
                "quiet here for ${DiscordBotLimits.BACKOFF_MS / 60_000} min"))
            return
        }

        // ── Free heuristic prefilter ──
        if (!addressed) {
            if (now < (backoffUntil[channelId] ?: 0L)) return
            val eligible = rawContent.isNotBlank() &&
                cfg.ambientPercent > 0 &&
                now - (directorAt[channelId] ?: 0L) >= DiscordBotLimits.DIRECTOR_MIN_GAP_MS &&
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
                for (e in asked) { reactTo(ctx, e, record = false); delay(250) }
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
        val keywords: Set<String>,     // words of the recent conversation → what's "relevant now"
        val targetIsLatest: Boolean,   // nothing newer than the message we're answering
        val maxSeenId: Long,           // highest message id folded in (for the "already covered" check)
        val emphasizeIds: Set<String>, // people the message ASKED ABOUT → inject their FULL card
        val namedIds: List<String>,    // people the message names (not the author), in order
        val otherSpeakers: List<String>, // other humans talking in the transcript, most recent first
        val recall: Boolean,           // a memory question ("what do you know about…", "who runs…?")
        val asking: Boolean,           // any question (or a memory question)
        val windowFull: Boolean,       // the conversation goes back further than the transcript
        val refOutOfWindow: Boolean,   // they replied to a message older than the transcript
    )

    private suspend fun generateLocked(ctx: MsgCtx, rung: DiscordBotState.Rung) {
        var short = rung == DiscordBotState.Rung.TRIM || rung == DiscordBotState.Rung.CHEAP

        if (ctx.addressed) {
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
            // Typing dots the moment we know we'll answer; the history fetch + model run behind them.
            showTyping(ctx.channelId)
        }

        // A channel the message points at is fetched alongside the history, not after it.
        val crossRefJob = if (ctx.refChannels.any { it != ctx.channelId }) scope.async { buildCrossRef(ctx) } else null
        val built = buildContext(ctx)   // fresh: folds in anything sent while this was queued

        if (!ctx.addressed) {
            val now = System.currentTimeMillis()
            directorAt[ctx.channelId] = now
            val plan = DiscordBotAi.director(cfg, built.turns, ChannelInfoStore.describe(ctx.channelId))
            when (plan?.action) {
                null, DiscordBotAi.Act.IGNORE -> {
                    crossRefJob?.cancel()
                    trace(ctx, "ambient", "director", "ignore", plan?.let { "no add" } ?: "no plan"); return
                }
                DiscordBotAi.Act.REACT -> {
                    crossRefJob?.cancel()
                    if (now - (ambientReactAt[ctx.channelId] ?: 0L) < DiscordBotLimits.AMBIENT_REACT_COOLDOWN_MS) {
                        trace(ctx, "ambient", "director", "ignore", "reacted recently"); return
                    }
                    ambientReactAt[ctx.channelId] = now
                    reactTo(ctx, plan.emoji.ifBlank { pickEmoji(ctx.userText) }); return
                }
                DiscordBotAi.Act.REPLY -> {
                    ambientCooldown[ctx.channelId] = now
                    if (plan.short) short = true
                    showTyping(ctx.channelId)
                }
            }
        }

        val model = if (rung == DiscordBotState.Rung.CHEAP) DiscordBotLimits.CHEAP_MODEL else cfg.model
        val turns = if (rung == DiscordBotState.Rung.TRIM || rung == DiscordBotState.Rung.CHEAP)
            built.turns.takeLast(DiscordBotLimits.CONTEXT_RAW_TURNS / 2) else built.turns

        val rc = buildReplyCtx(ctx, built, turns, short, crossRefJob?.await().orEmpty())

        when (val res = DiscordBotAi.reply(cfg, model, turns, rc)) {
            is DiscordBotAi.ReplyResult.Ok -> {
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
                        learnPending.merge(ctx.channelId, 1, Int::plus)   // its own line is part of what gets learned
                    } else {
                        DiscordBotState.log("Send failed: ${out.error}")
                        trace(ctx, "reply", "70B", "send-fail", out.error)
                    }
                }
                UserMemoryStore.touch(this, ctx.authorId, ctx.authorName)
                maybeLearn(ctx.channelId, now)
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

    /** Fire the typing indicator without waiting on it. */
    private fun showTyping(channelId: String) {
        if (cfg.shadowMode) return
        scope.launch { DiscordRest.triggerTyping(cfg.botToken, channelId) }
    }

    // ── Learning (cheap 8B pass: memory / summary / culture / self) ────────

    /**
     * Decide whether a channel is due a learn pass. Every message counts (replied or not, Cardinal's
     * own too): a pass runs after [DiscordBotLimits.LEARN_TRIGGER_MSGS] new messages, sooner in a very
     * busy channel, and when the channel goes quiet with a few unlearned messages left. Only on the
     * FULL/TRIM budget rungs — the cheaper rungs exist to save neurons.
     */
    private fun maybeLearn(channelId: String, now: Long) {
        val pending = learnPending[channelId] ?: 0
        if (pending <= 0 || channelId in learnInFlight) return
        val rung = DiscordBotState.currentRung()
        if (rung != DiscordBotState.Rung.FULL && rung != DiscordBotState.Rung.TRIM) return
        val since = now - (lastLearnAt[channelId] ?: 0L)
        val due = (pending >= DiscordBotLimits.LEARN_TRIGGER_MSGS && since >= DiscordBotLimits.LEARN_MIN_INTERVAL_MS) ||
            (pending >= DiscordBotLimits.LEARN_FORCE_MSGS && since >= DiscordBotLimits.LEARN_FORCE_MIN_GAP_MS)
        if (due) { startLearn(channelId, now); return }
        // Not due yet: learn whatever is left once the channel goes quiet.
        learnLullJobs.remove(channelId)?.cancel()
        learnLullJobs[channelId] = scope.launch {
            delay(DiscordBotLimits.LEARN_LULL_MS)
            // Right after a pass, wait out the minimum gap instead of skipping (else the tail is lost).
            val wait = (lastLearnAt[channelId] ?: 0L) + DiscordBotLimits.LEARN_LULL_MIN_GAP_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            if ((learnPending[channelId] ?: 0) >= DiscordBotLimits.LEARN_LULL_MIN_MSGS) startLearn(channelId, System.currentTimeMillis())
        }
    }

    private fun startLearn(channelId: String, now: Long) {
        if (!learnInFlight.add(channelId)) return
        lastLearnAt[channelId] = now
        learnLullJobs.remove(channelId)?.cancel()
        scope.launch {
            try { runLearn(channelId) } catch (_: Exception) { } finally {
                learnInFlight.remove(channelId)
                // Messages that arrived during the pass still need one (or a quiet-room pass).
                if ((learnPending[channelId] ?: 0) > 0) maybeLearn(channelId, System.currentTimeMillis())
            }
        }
    }

    /** One learn pass over every message since the last pass (up to [DiscordBotLimits.LEARN_FETCH]). */
    private suspend fun runLearn(channelId: String) {
        if (!::cfg.isInitialized || !cfg.isComplete) return
        val taken = learnPending.put(channelId, 0) ?: 0
        val recent = DiscordRest.fetchRecentMessages(cfg.botToken, channelId, DiscordBotLimits.LEARN_FETCH)
        val after = lastLearnedId[channelId] ?: 0L
        val fresh = recent.filter { (it.id.toLongOrNull() ?: 0L) > after }
        val turns = fresh.mapNotNull { m ->
            val text = tokenize(ChannelInfoStore.resolveMentions(stripBotMentions(m.content)))
            if (text.isBlank()) null else DiscordBotAi.Turn(m.authorId == botId, m.authorName, text)
        }
        if (turns.size < 3) { learnPending.merge(channelId, taken, Int::plus); return }
        val nameToId = HashMap<String, String>()
        recent.forEach { if (it.authorId != botId) nameToId[it.authorName.lowercase().trim()] = it.authorId }
        val obs = DiscordBotAi.observe(cfg, turns, ConversationStore.summary(channelId))
        if (obs == null) { learnPending.merge(channelId, taken, Int::plus); return }   // retried with the next batch
        lastLearnedId[channelId] = maxOf(after, fresh.maxOf { it.id.toLongOrNull() ?: 0L })
        applyObservation(channelId, obs, nameToId, System.currentTimeMillis(), turns)
        DiscordBotState.log("learned $channelId (${turns.size} msgs, ${obs.memDeltas.size} people)")
    }

    /** Persist one learn pass: summary, people, server culture, channel bit, self. */
    private fun applyObservation(
        channelId: String, obs: DiscordBotAi.Observation, nameToId: Map<String, String>, now: Long,
        turns: List<DiscordBotAi.Turn>,
    ) {
        val globalIndex = UserMemoryStore.nameIndex(this)
        if (obs.summary.isNotBlank()) ConversationStore.updateSummary(this, channelId, obs.summary, now)
        val dropped = ArrayList<String>()
        for (md in obs.memDeltas) {
            val id = resolveObserveAbout(md.about, nameToId, globalIndex)
            if (id != null && id != botId)
                UserMemoryStore.applyDelta(this, id, md.about, groundDelta(md, id, turns, nameToId) { dropped.add(it) })
        }
        if (dropped.isNotEmpty())
            DiscordBotState.log("learn: skipped ${dropped.size} unsupported fact(s), e.g. \"${dropped.first().take(60)}\"")
        if (obs.serverEvent.isNotBlank()) ServerMemoryStore.remember(this, obs.serverEvent, now)
        if (obs.channelBit.isNotBlank()) ChannelMemoryStore.remember(this, channelId, obs.channelBit, now)
        if (obs.selfTrait.isNotBlank() || obs.selfMood.isNotBlank()) {
            PersonalityStore.noteSelf(this, obs.selfTrait.ifBlank { null }, null, obs.selfMood.ifBlank { null })
            DiscordBotState.setMood(PersonalityStore.mood(this))
        }
    }

    /**
     * Keep only what the chat actually supports. A learned fact must mostly use words that were said
     * by that person or by people talking about them in this batch; a nickname / preferred name must
     * appear in the chat. Catches the cheap model inventing specifics (a hometown or job nobody
     * mentioned) without any extra model call. Facts with no checkable words pass.
     */
    private fun groundDelta(
        md: DiscordBotAi.MemDelta, id: String, turns: List<DiscordBotAi.Turn>, nameToId: Map<String, String>,
        onDrop: (String) -> Unit,
    ): JSONObject {
        val out = JSONObject(md.json.toString())
        val card = UserMemoryStore.load(this, id)
        val names = (listOf(md.about) + nameCandidates(md.about) + listOfNotNull(card?.name, card?.preferredNick) +
            card?.nicknames.orEmpty()).map { it.lowercase().trim() }.filter { it.length >= 2 }.toSet()
        val humans = turns.filter { !it.isBot }
        val about = humans.filter { t ->
            nameToId[t.name.lowercase().trim()] == id ||
                names.any { n -> Regex("(^|[^\\p{L}\\p{N}])" + Regex.escape(n) + "([^\\p{L}\\p{N}]|$)").containsMatchIn(t.text.lowercase()) }
        }
        val corpus = groundWords(about.joinToString(" ") { it.text })
        val nameWords = names.flatMap { groundWords(it) }.toSet()
        md.json.optJSONArray("facts")?.let { arr ->
            val kept = JSONArray()
            for (i in 0 until arr.length()) {
                val f = arr.optString(i).trim(); if (f.isBlank()) continue
                val words = groundWords(f) - nameWords
                val need = (words.size + 1) / 2
                if (words.isEmpty() || words.count { it in corpus } >= need) kept.put(f)
                else onDrop("${md.about}: $f")
            }
            out.put("facts", kept)
        }
        val chat = " " + humans.joinToString(" ") { it.text.lowercase() } + " "
        fun said(v: String) = v.isNotBlank() && chat.contains(v.lowercase().trim())
        if (!said(out.optString("nickname"))) out.remove("nickname")
        if (!said(out.optString("preferredName"))) out.remove("preferredName")
        return out
    }

    private val GROUND_STOP = setOf(
        "likes", "like", "loves", "love", "really", "always", "usually", "often", "they", "their", "them", "have",
        "been", "being", "with", "from", "into", "about", "some", "very", "much", "lots", "also", "still", "just",
        "that", "this", "what", "when", "where", "which", "while", "would", "could", "should", "does", "doing",
        "make", "made", "getting", "gets", "goes", "going", "enjoy", "enjoys", "person", "someone", "thing", "things",
    )
    private fun groundWords(s: String): Set<String> =
        Regex("[\\p{L}\\p{N}]+").findAll(s.lowercase()).map { it.value }
            .filter { it.length >= 4 && it !in GROUND_STOP }.map { discordStem(it) }.toSet()

    private suspend fun reactTo(ctx: MsgCtx, emoji: String, record: Boolean = true) {
        if (cfg.shadowMode) { if (record) trace(ctx, "react", "heuristic", "shadow", emoji); return }
        DiscordRest.addReaction(cfg.botToken, ctx.channelId, ctx.messageId, EmojiConvert.reactionToken(emoji))
        DiscordBotState.bumpReacted()
        if (record) trace(ctx, "react", "heuristic", "react", emoji)
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
        var fetchedCount = 0
        val speakerOrder = ArrayList<String>()
        if (cfg.contextTurns > 0) {
            val recent = DiscordRest.fetchRecentMessages(cfg.botToken, ctx.channelId, cfg.contextTurns)
            fetchedCount = recent.size
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
                if (m.authorId != botId) { nameToId[m.authorName.lowercase().trim()] = m.authorId; speakerOrder.add(m.authorId) }
                turns.add(DiscordBotAi.Turn(m.authorId == botId, m.authorName, text))
            }
        }
        val windowFull = cfg.contextTurns > 0 && fetchedCount >= cfg.contextTurns
        val refOutOfWindow = ctx.refTurn != null && (ctx.refId == null || ctx.refId !in seen)
        // A Discord reply says which message it answers — inline, so the transcript stays in order and
        // an old quoted message is clearly a quote, not a new turn.
        val target = ctx.refTurn?.let { r ->
            val who = if (r.isBot) "you" else r.name
            if (refOutOfWindow) "(replying to $who: \"${r.text.take(160)}\") ${ctx.userText}"
            else "(replying to $who) ${ctx.userText}"
        } ?: ctx.userText
        turns.add(DiscordBotAi.Turn(false, ctx.authorName, target))
        // Newest message first, so the words of what's being answered can never be cut off by older chat.
        val keywords = LinkedHashSet<String>().apply {
            for (t in turns.takeLast(DiscordBotLimits.RELEVANCE_WINDOW_TURNS).asReversed()) addAll(keywordList(t.text))
        }.take(DiscordBotLimits.RELEVANCE_KEYWORDS_MAX).toHashSet()
        val otherSpeakers = LinkedHashSet<String>().apply {
            for (i in speakerOrder.indices.reversed()) {
                val uid = speakerOrder[i]
                if (uid != ctx.authorId && uid != botId) add(uid)
            }
        }.toList()

        // Absent-person / recall injection: if the message NAMES someone Cardinal has a card for
        // (even if they aren't speaking), pull their card into context so it can actually answer
        // instead of claiming it doesn't know them. On a recall query ("who is X", "info about X",
        // "from your profile") the named card renders FULL (all facts).
        val emphasize = HashSet<String>()
        val named = ArrayList<String>()
        val selfRecall = SELF_RECALL_RE.containsMatchIn(ctx.userText)
        val isRecall = selfRecall || RECALL_RE.containsMatchIn(ctx.userText) || WHO_Q_RE.containsMatchIn(ctx.userText)
        val asking = isRecall || ctx.userText.contains('?')
        val nameKeys = UserMemoryStore.nameEntries(this)
        if (nameKeys.isNotEmpty()) {
            val lower = ctx.userText.lowercase()
            val normMsg = " " + lower
                .replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
            var injected = 0
            for (nk in nameKeys) {
                val uid = nk.id
                if (uid == botId || uid == ctx.authorId || nk.key.length < 3 || uid in named) continue
                if (!normMsg.contains(" ${nk.key} ")) continue
                // A short nickname or an everyday word only counts when it clearly points at a person.
                val weak = nk.key in COMMON_NICK_WORDS || (!nk.isRealName && nk.key.length < 4)
                if (weak && !asking && !lower.contains("@${nk.key}") &&
                    !Regex("(^|[^\\p{L}])" + Regex.escape(nk.key) + "['’]s\\b").containsMatchIn(lower)) continue
                ids.add(uid); if (isRecall) emphasize.add(uid)
                named.add(uid)
                if (++injected >= 3) break   // bound the prompt
            }
        }
        if (selfRecall) emphasize.add(ctx.authorId)

        return Built(turns, ids, nameToId, keywords, targetIsLatest = !newerExists, maxSeenId = maxSeenId,
            emphasizeIds = emphasize, namedIds = named, otherSpeakers = otherSpeakers, recall = isRecall,
            asking = asking, windowFull = windowFull, refOutOfWindow = refOutOfWindow)
    }

    /**
     * Decide what this reply's prompt carries. Always: personality, channel, who you're answering,
     * the server's most-used emojis. Only when relevant now: server memories, other people, the
     * earlier-conversation summary, the names / memory-question rules. All local and free.
     */
    private fun buildReplyCtx(
        ctx: MsgCtx, built: Built, turns: List<DiscordBotAi.Turn>, short: Boolean, crossRef: String,
    ): DiscordBotAi.ReplyCtx {
        val now = System.currentTimeMillis()
        val selfRecall = built.recall && SELF_RECALL_RE.containsMatchIn(ctx.userText)
        val answering = UserMemoryStore.answeringLine(this, ctx.authorId, ctx.authorName, built.keywords, selfRecall)

        // Other people: the ones the message names first (whole card when asked about), then — for a
        // "who …?" question with nobody named — the cards that best match it, then other speakers
        // but only when there's something worth knowing about them right now.
        val others = ArrayList<UserMemoryStore.PromptLine>()
        val used = HashSet<String>().apply { add(ctx.authorId); add(botId) }
        fun addOther(uid: String, full: Boolean, namedLimit: Int = 0) {
            if (others.size >= DiscordBotLimits.OTHER_PEOPLE_MAX || !used.add(uid)) return
            val name = built.nameToId.entries.firstOrNull { it.value == uid }?.key.orEmpty()
            UserMemoryStore.otherLine(this, uid, name, built.keywords, full, namedLimit)?.let { others.add(it) }
        }
        // Named people are the topic: always what Cardinal knows about them (more for a question).
        built.namedIds.forEach { addOther(it, full = it in built.emphasizeIds, namedLimit = if (built.asking) 4 else 2) }
        if (built.recall && built.namedIds.isEmpty() && !selfRecall)
            UserMemoryStore.searchCards(this, keywordsOf(ctx.userText), used, DiscordBotLimits.RECALL_SEARCH_MAX)
                .forEach { addOther(it, full = false) }
        built.otherSpeakers.forEach { addOther(it, full = false) }

        // The model already sees its own lines that are in the transcript; only list older ones.
        val visible = turns.filter { it.isBot }.map { normLine(it.text) }.toSet()
        val older = (recentBotReplies[ctx.channelId]?.toList() ?: emptyList()).filter { normLine(it) !in visible }

        return DiscordBotAi.ReplyCtx(
            selfDigest = PersonalityStore.snapshot(this),
            channelInfo = ChannelInfoStore.describe(ctx.channelId),
            serverCulture = buildServerCulture(ctx.channelId, built.keywords, built.recall),
            channelBits = ChannelMemoryStore.pickDeployable(this, ctx.channelId, ctx.userText, situationCues(ctx), now).orEmpty(),
            crossRef = crossRef,
            answering = answering.text,
            othersPresent = built.otherSpeakers.isNotEmpty(),
            othersBlock = others.joinToString("\n") { it.text },
            summary = if (built.windowFull || built.refOutOfWindow)
                ConversationStore.freshSummary(ctx.channelId, now, DiscordBotLimits.CONVO_GAP_MS) else "",
            olderBotLines = older,
            ownLinesVisible = visible.isNotEmpty(),
            emojiHint = emojiHint(),
            langHint = detectLang(ctx.userText),
            namesRule = answering.hasNick || others.any { it.hasNick },
            recall = built.recall,
            shortHint = short,
        )
    }

    private fun normLine(s: String): String = EmojiConvert.normalizeForPrompt(s).lowercase().replace(Regex("\\s+"), " ").trim().take(80)

    private fun persistEmojiUsageIfDirty() {
        val snap = EmojiConvert.usageSnapshotIfDirty(20) ?: return
        getSharedPreferences(EMOJI_PREFS, MODE_PRIVATE).edit().putString("usage", snap).apply()
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

    /**
     * Server memories relevant to the recent conversation (+ any revived dead topic). No always-on
     * "core" memories: forcing the same two into every reply made Cardinal bring them up constantly.
     * A memory question lowers the bar to one shared word.
     */
    private fun buildServerCulture(channelId: String, keywords: Set<String>, recall: Boolean): String {
        val mem = ServerMemoryStore.retrieveFor(this, keywords, if (recall) 1 else 2)
        val topics = ConversationStore.reviveFor(this, channelId, keywords.joinToString(" "))
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
        for (key in nameCandidates(about)) {
            nameToId[key]?.let { return it }
            global[key]?.let { return it }
        }
        return null
    }

    /** "@Alice", "Alice (ali)", "alice's" → the plain forms to look up (each still an EXACT match). */
    private fun nameCandidates(about: String): List<String> {
        val out = LinkedHashSet<String>()
        fun add(s: String) {
            val k = s.trim().trim('"', '\'', '.', ',', ':', ';').trim().removePrefix("@")
                .removeSuffix("'s").removeSuffix("’s").trim()
            if (k.isNotBlank()) out.add(k)
        }
        val base = about.lowercase().trim()
        add(base)
        Regex("^(.*?)\\s*\\((.*?)\\)\\s*$").find(base)?.let { m -> add(m.groupValues[1]); add(m.groupValues[2]) }
        return out.toList()
    }

    private fun handleReactionAdd(d: JSONObject) {
        val messageId = d.optString("message_id")
        val reactorId = d.optString("user_id")
        if (messageId.isBlank() || reactorId.isBlank() || reactorId == botId) return
        val mine = synchronized(recentBotMsgIds) { recentBotMsgIds.containsKey(messageId) }
        if (!mine) return
        val emoji = d.optJSONObject("emoji")?.optString("name").orEmpty()
        if (!d.optJSONObject("emoji")?.optString("id").isNullOrBlank()) EmojiConvert.noteReaction(emoji)
        val sentiment = when {
            emoji in POSITIVE_REACTS -> "warm (reacted ${emoji})"
            emoji in NEGATIVE_REACTS -> "cool (reacted ${emoji})"
            else -> return
        }
        scope.launch {
            val cur = UserMemoryStore.load(this@DiscordBotService, reactorId) ?: UserMemoryStore.Card(id = reactorId)
            // A reaction only fills an empty (or reaction-derived) vibe — never overwrites what was learned.
            if (cur.sentiment.isBlank() || cur.sentiment.contains("(reacted"))
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
        EmojiConvert.topNames(DiscordBotLimits.EMOJI_HINT_MAX).joinToString(", ") { ":$it:" }

    // ── Helpers ───────────────────────────────────────────────────────────

    private val triviaWords = setOf(
        "lol", "lmao", "lmfao", "lel", "kek", "ok", "kk", "k", "nice", "fr", "real",
        "bruh", "true", "yep", "yup", "nah", "w", "l", "based", "same", "mood"
    )
    private fun isStopRequest(text: String): Boolean {
        val t = text.lowercase().replace(Regex("[^\\p{L}\\p{N}'’ ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return false
        if (STOP_WHOLE_RE.matches(t)) return true
        val m = STOP_PHRASE_RE.find(t) ?: return false
        val before = t.substring(0, m.range.first).trimEnd().substringAfterLast(' ').replace('’', '\'')
        return before !in NEGATIONS
    }

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
        "that","it","you","your","they","just","like","lol","cardinal","what","why","how","when",
        "who","has","had","have","his","her","him","she","its","our","out","one","now","too","see",
        "say","let","not","all","any","can","did","get","got","yes","yep","nah","lmao","omg","bro",
        "haha","hey","hi","ok","okay","yeah","wow","way","own","off","yet","also","then","than",
        "there","here","about","know","does","doing","from","into","were","been","being","some",
        "really","very","much","more","most","would","could","should","will","dont","cant","thats",
        "im","ive","its","youre","whats","who's","what's","me","my","we","us","them","their","do"
    )
    // 3+ letters so short-but-meaningful words (cat, dog, gym, art) still drive relevance.
    private fun keywordList(s: String): List<String> =
        Regex("[\\p{L}\\p{N}]+").findAll(s.lowercase()).map { it.value }
            .filter { it.length >= 3 && it !in KW_STOP }.distinct().toList()
    private fun keywordsOf(s: String): Set<String> = keywordList(s).take(20).toHashSet()

    private fun tokenize(content: String): String =
        EmojiConvert.normalizeForPrompt(URL_RE.replace(content, "[link]"))
            .replace(Regex("[ \\t]{2,}"), " ").trim().take(DiscordBotLimits.MAX_MSG_CHARS)

    private fun messageMentionsBot(d: JSONObject, content: String): Boolean {
        if (botId.isBlank()) return false
        val mentions = d.optJSONArray("mentions") ?: JSONArray()
        for (i in 0 until mentions.length()) if (mentions.optJSONObject(i)?.optString("id") == botId) return true
        return content.contains("<@$botId>") || content.contains("<@!$botId>")
    }

    // History text arrives with mentions already resolved to "@Name", the live message as "<@id>";
    // strip both so the transcript is consistent (and a few tokens shorter).
    private fun stripBotMentions(content: String): String {
        var t = content.replace("<@$botId>", "").replace("<@!$botId>", "")
        val name = botName
        if (name.isNotBlank()) t = t.replace(Regex("(?i)@" + Regex.escape(name) + "\\b"), "")
        return t.replace(Regex("[ \\t]{2,}"), " ").trim()
    }

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
        val old = webSocket
        socketGen.incrementAndGet()
        webSocket = null
        try { old?.close(1000, reason) } catch (_: Exception) {}
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
