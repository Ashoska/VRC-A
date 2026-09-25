package com.vrca.discordbot

import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/**
 * Lets Cardinal **know where it is** and **cross-reference other channels** — the two halves of
 * "understand the channel it's in" without confusing separate conversations.
 *
 *  - Channel identity: id → name + topic, captured from each `GUILD_CREATE` (its `channels` array).
 *    Injected into the reply prompt so Cardinal reads the room's register (a shitpost channel vs a
 *    help channel) instead of treating every channel the same.
 *  - Cross-reference: [resolveMentions] turns `<#id>` in a message into a readable `#name`, and
 *    [referencedIds] lists the channels a message points at — so "did you see that in #media" can
 *    trigger a bounded, labelled pull of #media's recent messages (done in the service).
 *
 * Process-global + in-memory (a bot is in one server here); re-seeded on every connect's
 * GUILD_CREATE, exactly like [EmojiConvert]. Text channels only (name present).
 */
object ChannelInfoStore {
    data class Info(val id: String, val name: String, val topic: String)

    private val byId = ConcurrentHashMap<String, Info>()
    private val serverById = ConcurrentHashMap<String, String>()   // channel id → server name ("can you see this server's name?")

    /** `<#id>` — a Discord channel mention. */
    private val MENTION_RE = Regex("<#(\\d+)>")

    /** Ingest a guild's `channels` array (from GUILD_CREATE). Keeps anything with a name. */
    fun putGuildChannels(channels: JSONArray?, serverName: String = "") {
        if (channels == null) return
        if (serverName.isNotBlank()) for (i in 0 until channels.length())
            channels.optJSONObject(i)?.optString("id")?.trim()?.takeIf { it.isNotBlank() }?.let { serverById[it] = serverName }
        for (i in 0 until channels.length()) {
            val c = channels.optJSONObject(i) ?: continue
            val id = c.optString("id").trim()
            val name = c.optString("name").trim()
            if (id.isBlank() || name.isBlank()) continue
            byId[id] = Info(id, name, c.optString("topic").trim())
        }
    }

    fun name(id: String): String? = byId[id]?.name

    /** The server a channel belongs to, if known. */
    fun serverName(channelId: String): String? = serverById[channelId]

    /** "#general — <topic>" (topic trimmed) for the channel-identity line, or just "#general". */
    fun describe(channelId: String): String {
        val info = byId[channelId] ?: return ""
        val t = info.topic.take(180)
        val server = serverById[channelId]?.let { "server \"$it\", " }.orEmpty()
        return server + (if (t.isBlank()) "#${info.name}" else "#${info.name} — $t")
    }

    /** Rewrite `<#id>` mentions in [text] to `#name` so the model reads names, not raw ids. */
    fun resolveMentions(text: String): String {
        if (text.isBlank() || !text.contains("<#")) return text
        return MENTION_RE.replace(text) { m ->
            byId[m.groupValues[1]]?.let { "#${it.name}" } ?: m.value
        }
    }

    /** The channel ids a message points at via `<#id>` (for a cross-channel pull). */
    fun referencedIds(text: String): List<String> {
        if (!text.contains("<#")) return emptyList()
        return MENTION_RE.findAll(text).map { it.groupValues[1] }.distinct().toList()
    }

    fun size(): Int = byId.size
    fun clear() = byId.clear()
}
