package com.vrca.discordbot.lab

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * A small, faithful fake of the slice of Discord the bot touches: the v10 gateway (HELLO →
 * IDENTIFY → READY + GUILD_CREATE, heartbeats/ACKs, RESUME, MESSAGE_CREATE / MESSAGE_REACTION_ADD
 * dispatch) and the four REST calls (history fetch, send, typing, add-reaction). Messages carry
 * real snowflake ids so the bot's "newer message exists / already covered" ordering logic behaves
 * exactly as in production. Every bot action is recorded with a timestamp.
 */
internal class FakeDiscord(private val rec: LabRecorder, val botToken: String) {
    data class User(val id: String, val username: String, val globalName: String, val bot: Boolean = false)
    data class Chan(val id: String, val name: String, val topic: String)
    data class Msg(
        val id: String, val channelId: String, val author: User, val content: String,
        val mentions: List<User>, val replyTo: String?, val image: Boolean, val atMs: Long,
    )

    private object Snowflake {
        private const val DISCORD_EPOCH = 1420070400000L
        private var last = 0L
        @Synchronized fun next(): String {
            val candidate = (System.currentTimeMillis() - DISCORD_EPOCH) shl 22
            last = maxOf(candidate, last + 1)
            return last.toString()
        }
    }

    val server = MockWebServer()
    val bot = User("1100000000000000001", "Cardinal", "Cardinal", bot = true)
    val guildId = "1000000000000000001"

    private val userSeq = AtomicInteger(0)
    private val chanSeq = AtomicInteger(0)
    private val users = ConcurrentHashMap<String, User>()          // lowercase name -> user
    private val chans = ConcurrentHashMap<String, Chan>()          // name -> channel
    private val history = ConcurrentHashMap<String, MutableList<Msg>>() // channelId -> messages
    private val byId = ConcurrentHashMap<String, Msg>()
    private val gatewaySeq = AtomicInteger(0)
    @Volatile private var socket: WebSocket? = null
    @Volatile var ready = CountDownLatch(1)
        private set
    val identifies = AtomicInteger(0)
    val unhandled = CopyOnWriteArrayList<String>()
    /** Dispatches sent while the bot was disconnected; replayed on RESUME like real Discord. */
    private val missed = CopyOnWriteArrayList<Pair<String, JSONObject>>()
    /** When set, every IDENTIFY/RESUME is answered with this close code (e.g. 4014 disallowed intents). */
    @Volatile var rejectWith: Int? = null
    /** heartbeat_interval sent in HELLO to NEW sessions (Discord uses ~41.25 s; shorten to make gateway tests fast). */
    @Volatile var heartbeatIntervalMs: Long = 41_250L
    val connects = AtomicInteger(0)

    /** Custom server emojis (more than the bot's 12-name hint cap, on purpose). */
    val emojis: List<Triple<String, String, Boolean>> = listOf(
        "kekw", "pepehands", "catjam", "sadge", "pog", "lul", "monkas", "hmm", "yippee",
        "bonk", "copium", "stare", "wave", "salute", "clueless", "aware",
    ).mapIndexed { i, n -> Triple((1400000000000000000L + i).toString(), n, n == "catjam") }

    fun start() {
        listOf(
            "general" to "main hangout — anything goes",
            "memes" to "shitposts and memes only",
            "vrchat" to "VRChat worlds, avatars, meetups",
            "media" to "pics, clips, screenshots",
        ).forEach { (n, t) -> channel(n, t) }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.getHeader("Upgrade").equals("websocket", ignoreCase = true)) gatewayUpgrade()
                else try { rest(request) } catch (e: Exception) {
                    rec.note("fake discord REST error: ${e.javaClass.simpleName}: ${e.message}")
                    MockResponse().setResponseCode(500)
                }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    val gatewayUrl: String get() = "ws://127.0.0.1:${server.port}/gateway/?v=10&encoding=json"
    val apiBase: String get() = "http://127.0.0.1:${server.port}/api/v10"

    fun shutdown() { runCatching { socket?.close(1001, "lab done") }; runCatching { server.shutdown() } }

    // ── Directory ────────────────────────────────────────────────────────

    fun user(name: String): User = users.getOrPut(name.lowercase()) {
        val n = userSeq.incrementAndGet()
        // "~name" = a user with no display name (Discord sends global_name: null).
        User((1200000000000000000L + n).toString(), name.lowercase().removePrefix("~").replace(' ', '_'), name)
    }

    fun knownUser(name: String): User? = users[name.lowercase()]

    fun channel(name: String, topic: String? = null): Chan {
        val key = name.trimStart('#').lowercase()
        val existing = chans[key]
        if (existing != null) {
            if (topic != null && topic != existing.topic) {
                val upd = existing.copy(topic = topic); chans[key] = upd
                emit("CHANNEL_UPDATE", chanJson(upd)); return upd
            }
            return existing
        }
        val c = Chan((1300000000000000000L + chanSeq.incrementAndGet()).toString(), key, topic.orEmpty())
        chans[key] = c
        history[c.id] = CopyOnWriteArrayList()
        if (socket != null) emit("CHANNEL_CREATE", chanJson(c))
        return c
    }

    fun channelById(id: String): Chan? = chans.values.firstOrNull { it.id == id }
    fun channels(): List<Chan> = chans.values.sortedBy { it.id }
    fun message(id: String): Msg? = byId[id]
    fun messages(channelId: String): List<Msg> = history[channelId].orEmpty()

    fun lastBy(channelId: String, userId: String): Msg? = history[channelId]?.lastOrNull { it.author.id == userId }
    fun lastBot(channelId: String): Msg? = lastBy(channelId, bot.id)

    // ── Human actions ────────────────────────────────────────────────────

    /**
     * A human posts [raw] in [channelName]. `@Cardinal` becomes a real bot mention, `@name` a user
     * mention for known users, `#chan` a channel mention for known channels.
     */
    fun say(channelName: String, userName: String, raw: String, replyTo: String? = null, image: Boolean = false): Msg {
        val ch = channel(channelName)
        val u = user(userName)
        val mentions = ArrayList<User>()
        var content = Regex("(?i)@cardinal\\b").replace(raw) { mentions.add(bot); "<@${bot.id}>" }
        content = Regex("@([A-Za-z0-9_.-]+)").replace(content) { m ->
            users[m.groupValues[1].lowercase()]?.let { mentions.add(it); "<@${it.id}>" } ?: m.value
        }
        content = Regex("(?<![\\w<])#([a-z0-9_-]+)").replace(content) { m ->
            chans[m.groupValues[1].lowercase()]?.let { "<#${it.id}>" } ?: m.value
        }
        val msg = Msg(Snowflake.next(), ch.id, u, content, mentions.distinct(), replyTo, image, System.currentTimeMillis())
        append(msg)
        rec.event("human", JSONObject().put("id", msg.id).put("channel", ch.name).put("channelId", ch.id)
            .put("author", u.globalName).put("authorId", u.id).put("text", raw).put("wire", content)
            .put("replyTo", replyTo ?: JSONObject.NULL).put("image", image)
            .put("addressed", mentions.any { it.bot } || (replyTo != null && byId[replyTo]?.author?.bot == true)))
        emit("MESSAGE_CREATE", msgJson(msg, withRef = true))
        return msg
    }

    fun react(userName: String, messageId: String, emoji: String) {
        val u = user(userName)
        val m = byId[messageId] ?: run { rec.note("react: unknown message $messageId"); return }
        rec.event("human_react", JSONObject().put("author", u.globalName).put("messageId", messageId).put("emoji", emoji))
        emit("MESSAGE_REACTION_ADD", JSONObject().put("user_id", u.id).put("channel_id", m.channelId)
            .put("message_id", messageId).put("guild_id", guildId)
            .put("emoji", JSONObject().put("id", JSONObject.NULL).put("name", emoji)))
    }

    /** Force the gateway to drop (tests the bot's reconnect/RESUME path). */
    fun dropGateway(code: Int = 4000) { socket?.close(code, "lab-forced drop"); socket = null; ready = CountDownLatch(1) }

    // ── Gateway ──────────────────────────────────────────────────────────

    private fun gatewayUpgrade(): MockResponse = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            connects.incrementAndGet()
            webSocket.send(frame(10, JSONObject().put("heartbeat_interval", heartbeatIntervalMs)))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val f = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (f.optInt("op", -1)) {
                1 -> webSocket.send(frame(11, null))
                2 -> {
                    val tok = f.optJSONObject("d")?.optString("token")
                    rejectWith?.let { code ->
                        rec.event("gateway", JSONObject().put("event", "identify-rejected").put("code", code))
                        webSocket.close(code, "lab reject"); return
                    }
                    if (tok != botToken) { webSocket.close(4004, "Authentication failed"); return }
                    missed.clear()
                    identifies.incrementAndGet()
                    dispatch(webSocket, "READY", JSONObject().put("v", 10).put("user", userJson(bot))
                        .put("session_id", "lab-session-${identifies.get()}")
                        .put("resume_gateway_url", "ws://127.0.0.1:${server.port}/gateway")
                        .put("guilds", JSONArray().put(JSONObject().put("id", guildId).put("unavailable", true))))
                    dispatch(webSocket, "GUILD_CREATE", guildJson())
                    rec.event("gateway", JSONObject().put("event", "identified"))
                    ready.countDown()
                }
                6 -> {
                    rejectWith?.let { code ->
                        rec.event("gateway", JSONObject().put("event", "resume-rejected").put("code", code))
                        webSocket.close(code, "lab reject"); return
                    }
                    dispatch(webSocket, "RESUMED", JSONObject())
                    val replay = missed.toList(); missed.clear()
                    replay.forEach { (t, d) -> dispatch(webSocket, t, d) }
                    rec.event("gateway", JSONObject().put("event", "resumed").put("replayed", replay.size))
                    ready.countDown()
                }
            }
        }

        // Like Discord: answer the client's close frame promptly (completes the handshake).
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
            if (socket === webSocket) socket = null
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (socket === webSocket) socket = null
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (socket === webSocket) socket = null
        }
    })

    private fun frame(op: Int, d: Any?): String =
        JSONObject().put("op", op).put("d", d ?: JSONObject.NULL).put("s", JSONObject.NULL).put("t", JSONObject.NULL).toString()

    private fun dispatch(ws: WebSocket, t: String, d: JSONObject) {
        ws.send(JSONObject().put("op", 0).put("t", t).put("s", gatewaySeq.incrementAndGet()).put("d", d).toString())
    }

    private fun emit(t: String, d: JSONObject) {
        val ws = socket ?: run { missed.add(t to d); rec.note("gateway down — queued $t for RESUME replay"); return }
        dispatch(ws, t, d)
    }

    // ── REST ─────────────────────────────────────────────────────────────

    private fun rest(req: RecordedRequest): MockResponse {
        val url = req.requestUrl ?: return MockResponse().setResponseCode(400)
        if (req.getHeader("Authorization") != "Bot $botToken") return MockResponse().setResponseCode(401)
        val s = url.pathSegments   // api, v10, channels, {id}, ...
        if (s.size < 4 || s[0] != "api" || s[2] != "channels") return unhandled(req)
        val chId = s[3]
        val tail = s.drop(4)
        return when {
            req.method == "GET" && tail == listOf("messages") -> {
                val limit = (url.queryParameter("limit")?.toIntOrNull() ?: 50).coerceIn(1, 100)
                val list = history[chId].orEmpty().takeLast(limit).reversed()
                rec.event("rest", JSONObject().put("op", "history").put("channelId", chId).put("limit", limit).put("returned", list.size))
                json(JSONArray(list.map { msgJson(it, withRef = true) }))
            }
            req.method == "POST" && tail == listOf("messages") -> botSend(chId, JSONObject(req.body.readUtf8()))
            req.method == "POST" && tail == listOf("typing") -> {
                rec.event("bot_typing", JSONObject().put("channelId", chId).put("channel", channelById(chId)?.name))
                MockResponse().setResponseCode(204)
            }
            req.method == "PUT" && tail.size == 5 && tail[0] == "messages" && tail[2] == "reactions" && tail[4] == "@me" -> {
                val target = byId[tail[1]]
                rec.event("bot_react", JSONObject().put("channelId", chId).put("channel", channelById(chId)?.name)
                    .put("messageId", tail[1]).put("emoji", tail[3])
                    .put("target", target?.author?.globalName ?: JSONObject.NULL))
                if (target != null) emit("MESSAGE_REACTION_ADD", JSONObject().put("user_id", bot.id).put("channel_id", chId)
                    .put("message_id", tail[1]).put("guild_id", guildId).put("emoji", JSONObject().put("id", JSONObject.NULL).put("name", tail[3])))
                MockResponse().setResponseCode(204)
            }
            else -> unhandled(req)
        }
    }

    private fun botSend(chId: String, body: JSONObject): MockResponse {
        val content = body.optString("content")
        val replyTo = body.optJSONObject("message_reference")?.optString("message_id")?.takeIf { it.isNotBlank() }
        val msg = Msg(Snowflake.next(), chId, bot, content, emptyList(), replyTo, false, System.currentTimeMillis())
        append(msg)
        rec.event("bot_send", JSONObject().put("id", msg.id).put("channelId", chId).put("channel", channelById(chId)?.name)
            .put("text", content).put("replyTo", replyTo ?: JSONObject.NULL)
            .put("replyToAuthor", replyTo?.let { byId[it]?.author?.globalName } ?: JSONObject.NULL)
            .put("allowedMentions", body.optJSONObject("allowed_mentions") ?: JSONObject()))
        emit("MESSAGE_CREATE", msgJson(msg, withRef = true))
        return json(msgJson(msg, withRef = true))
    }

    private fun unhandled(req: RecordedRequest): MockResponse {
        val line = "${req.method} ${req.path}"
        unhandled.add(line)
        rec.note("fake discord: UNHANDLED REST $line")
        return MockResponse().setResponseCode(404).setBody("""{"message":"Unknown (lab)","code":0}""")
    }

    private fun json(body: Any) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/json").setBody(body.toString())

    // ── JSON shapes ──────────────────────────────────────────────────────

    private fun append(m: Msg) { history.getOrPut(m.channelId) { CopyOnWriteArrayList() }.add(m); byId[m.id] = m }

    private fun userJson(u: User) = JSONObject().put("id", u.id).put("username", u.username)
        .put("global_name", if (u.globalName.startsWith("~")) JSONObject.NULL else u.globalName).put("bot", u.bot).put("discriminator", "0")

    private fun chanJson(c: Chan) = JSONObject().put("id", c.id).put("name", c.name).put("type", 0)
        .put("topic", c.topic).put("guild_id", guildId)

    private fun guildJson() = JSONObject().put("id", guildId).put("name", "Lab Server")
        .put("channels", JSONArray(chans.values.sortedBy { it.id }.map { chanJson(it) }))
        .put("emojis", JSONArray(emojis.map { (id, n, anim) -> JSONObject().put("id", id).put("name", n).put("animated", anim) }))

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun msgJson(m: Msg, withRef: Boolean): JSONObject {
        val o = JSONObject().put("id", m.id).put("channel_id", m.channelId).put("guild_id", guildId)
            .put("author", userJson(m.author)).put("content", m.content)
            .put("timestamp", synchronized(iso) { iso.format(Date(m.atMs)) })
            .put("mentions", JSONArray(m.mentions.map { userJson(it) }))
            .put("attachments", JSONArray().apply {
                if (m.image) put(JSONObject().put("id", m.id).put("filename", "image.png").put("content_type", "image/png"))
            })
        if (withRef && m.replyTo != null) {
            o.put("message_reference", JSONObject().put("message_id", m.replyTo).put("channel_id", m.channelId))
            byId[m.replyTo]?.let { o.put("referenced_message", msgJson(it, withRef = false)) }
        }
        return o
    }
}
