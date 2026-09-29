package com.vrca.discordbot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Thin Discord REST helper for the bot: send a channel message and fire the typing
 * indicator. Bot-token authenticated (`Authorization: Bot <token>`). Discord requires
 * a real User-Agent on the API.
 */
object DiscordRest {
    private val API: String get() = BotEndpoints.discordApi
    private const val UA = "DiscordBot (https://github.com/ashoska/vrc-a, 1.0)"
    private const val MAX_CONTENT = 2000  // Discord's hard message-length limit

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** One recent channel message, oldest-relevant fields only. */
    data class HistMsg(val id: String, val authorId: String, val authorName: String, val isBot: Boolean, val content: String)

    /**
     * Reads the last [limit] messages in a channel (Discord returns them newest-first;
     * we reverse to chronological). This is the bot's short-term MEMORY — free, stateless,
     * per-channel, and survives process kills, since Discord itself is the store.
     * Returns empty on any failure (the caller falls back to single-turn).
     */
    suspend fun fetchRecentMessages(token: String, channelId: String, limit: Int): List<HistMsg> =
        withContext(Dispatchers.IO) {
            try {
                val n = limit.coerceIn(1, 50)
                val req = Request.Builder()
                    .url("$API/channels/$channelId/messages?limit=$n")
                    .addHeader("Authorization", "Bot $token")
                    .addHeader("User-Agent", UA)
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    val arr = JSONArray(resp.body?.string().orEmpty())
                    val out = ArrayList<HistMsg>(arr.length())
                    for (i in 0 until arr.length()) {
                        val m = arr.optJSONObject(i) ?: continue
                        val author = m.optJSONObject("author") ?: continue
                        val name = displayName(author, "user")
                        out.add(HistMsg(
                            id = m.optString("id"),
                            authorId = author.optString("id"),
                            authorName = name,
                            isBot = author.optBoolean("bot", false),
                            content = withStickers(resolveMentions(m.optString("content"), m.optJSONArray("mentions")), m)
                        ))
                    }
                    out.reversed()   // chronological (oldest first)
                }
            } catch (_: Exception) { emptyList() }
        }

    /** A Discord user's display name: global_name, else username. Discord sends `global_name: null`
     *  for users without one, which org.json's optString turns into the string "null" — so that is
     *  treated as missing (this is what produced a memory card literally named "null"). */
    fun displayName(u: JSONObject?, fallback: String): String {
        fun clean(k: String) = u?.optString(k).orEmpty().trim().takeUnless { it.equals("null", true) }.orEmpty()
        return clean("global_name").ifBlank { clean("username") }.ifBlank { fallback }
    }

    /** A sticker arrives as a message with no text and a `sticker_items` list; without this Cardinal
     *  saw an empty message ("not even a hello?"). Appends "(sent a sticker: name)" so it reads like a
     *  reaction image. Free. */
    fun withStickers(content: String, m: JSONObject?): String {
        val items = m?.optJSONArray("sticker_items") ?: return content
        val names = (0 until items.length()).mapNotNull { items.optJSONObject(it)?.optString("name")?.trim()?.takeIf { n -> n.isNotBlank() && n != "null" } }
        if (names.isEmpty()) return content
        return (content.trim() + " " + names.joinToString(" ") { "(sent a sticker: $it)" }).trim()
    }

    /** Replaces inline user mentions (`<@id>` / `<@!id>`) in message text with `@DisplayName`
     *  using the message's own `mentions` array — so the model sees names, not raw ids. Free. */
    fun resolveMentions(content: String, mentions: JSONArray?): String {
        if (content.isBlank() || mentions == null || mentions.length() == 0) return content
        var out = content
        for (i in 0 until mentions.length()) {
            val u = mentions.optJSONObject(i) ?: continue
            val id = u.optString("id"); if (id.isBlank()) continue
            val name = displayName(u, "user")
            out = out.replace("<@$id>", "@$name").replace("<@!$id>", "@$name")
        }
        return out
    }

    /** Adds an emoji reaction to a message (unicode emoji). Best-effort. */
    suspend fun addReaction(token: String, channelId: String, messageId: String, emoji: String) =
        withContext(Dispatchers.IO) {
            try {
                val e = URLEncoder.encode(emoji, "UTF-8")
                val req = Request.Builder()
                    .url("$API/channels/$channelId/messages/$messageId/reactions/$e/@me")
                    .addHeader("Authorization", "Bot $token")
                    .addHeader("User-Agent", UA)
                    .put(ByteArray(0).toRequestBody(null))
                    .build()
                client.newCall(req).execute().use { }
            } catch (_: Exception) { }
        }

    /** Shows "Bot is typing…" for ~10s (or until the next message) so the AI latency
     *  reads as responsiveness. Best-effort; failures are swallowed. */
    suspend fun triggerTyping(token: String, channelId: String) = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$API/channels/$channelId/typing")
                .addHeader("Authorization", "Bot $token")
                .addHeader("User-Agent", UA)
                .post(ByteArray(0).toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { }
        } catch (_: Exception) { }
    }

    /** Result of a send: the created message id (for reaction-learning) + an error string. */
    data class SendOutcome(val messageId: String?, val error: String?)

    /**
     * Posts [content] to [channelId], optionally as a reply to [replyToMessageId].
     * `allowed_mentions.parse=[]` blocks the model's output from ever @-pinging
     * everyone/roles/users, and `replied_user=false` avoids pinging on a reply.
     * Returns the new message id (so the bot can learn from reactions to its OWN posts).
     */
    suspend fun send(
        token: String,
        channelId: String,
        content: String,
        replyToMessageId: String? = null,
    ): SendOutcome = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject()
                .put("content", content.take(MAX_CONTENT))
                .put("allowed_mentions", JSONObject()
                    .put("parse", JSONArray())
                    .put("replied_user", false))
            if (replyToMessageId != null) {
                payload.put("message_reference", JSONObject()
                    .put("message_id", replyToMessageId)
                    .put("fail_if_not_exists", false))
            }
            val req = Request.Builder()
                .url("$API/channels/$channelId/messages")
                .addHeader("Authorization", "Bot $token")
                .addHeader("User-Agent", UA)
                .post(payload.toString().toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    val id = try { JSONObject(raw).optString("id").ifBlank { null } } catch (_: Exception) { null }
                    SendOutcome(id, null)
                } else SendOutcome(null, "send HTTP ${resp.code}: ${raw.take(160)}")
            }
        } catch (e: Exception) {
            SendOutcome(null, "${e.javaClass.simpleName}: ${e.message ?: "network error"}")
        }
    }
}
