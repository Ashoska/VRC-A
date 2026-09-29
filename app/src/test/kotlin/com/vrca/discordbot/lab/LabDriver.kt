package com.vrca.discordbot.lab

import android.content.Context
import com.vrca.discordbot.ChannelMemoryStore
import com.vrca.discordbot.ConversationStore
import com.vrca.discordbot.DiscordBotState
import com.vrca.discordbot.PersonalityStore
import com.vrca.discordbot.ServerMemoryStore
import com.vrca.discordbot.UserMemoryStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The verbs the lab speaks — shared by the script runner and the interactive control API — plus
 * the "settle" logic that decides when the bot is done reacting to something, and the state
 * dump/restore of every Cardinal store.
 */
internal class LabDriver(
    val cfg: LabConfig,
    val rec: LabRecorder,
    val discord: FakeDiscord,
    val ai: AiProxy,
    val ctx: Context,
    private val pumpMain: () -> Unit,
    private val restartService: (() -> Unit),
) {
    @Volatile var currentChannel: String = "general"

    data class Outcome(val human: FakeDiscord.Msg?, val events: List<LabRecorder.Event>, val timedOut: Boolean)

    // ── Human verbs ──────────────────────────────────────────────────────

    /**
     * Post as [user]. [replyTo] = "bot" (the bot's last message here), a user name (their last
     * message here) or a raw message id. [wait]: null = wait only when the bot is addressed.
     */
    fun say(
        user: String, text: String, channel: String = currentChannel, replyTo: String? = null,
        image: Boolean = false, wait: Boolean? = null, timeoutMs: Long = 90_000,
    ): Outcome {
        val ch = discord.channel(channel)
        val refId = replyTo?.let { resolveTarget(ch.id, it) }
        if (replyTo != null && refId == null) rec.note("reply target '$replyTo' not found in #${ch.name} — sending as a plain message")
        val t0 = System.currentTimeMillis()
        val msg = discord.say(ch.name, user, text, refId, image)
        val addressed = msg.mentions.any { it.bot } || (refId != null && discord.message(refId)?.author?.bot == true)
        val shouldWait = wait ?: addressed
        if (!shouldWait) return Outcome(msg, emptyList(), false)
        val timedOut = !settle(t0, msg.author.globalName, ch.id, needDecision = addressed, timeoutMs = timeoutMs)
        return Outcome(msg, rec.eventsSince(t0), timedOut)
    }

    fun react(user: String, target: String, emoji: String, channel: String = currentChannel) {
        val ch = discord.channel(channel)
        val id = resolveTarget(ch.id, target) ?: run { rec.note("react target '$target' not found"); return }
        discord.react(user, id, emoji)
    }

    fun resolveTarget(channelId: String, spec: String): String? = when {
        spec.equals("bot", true) || spec.equals("cardinal", true) -> discord.lastBot(channelId)?.id
        spec.all { it.isDigit() } && spec.length > 10 -> spec.takeIf { discord.message(it) != null }
        else -> discord.knownUser(spec)?.let { discord.lastBy(channelId, it.id)?.id }
    }

    /**
     * Wait until the bot has finished reacting: (addressed → a routing decision for this author
     * exists) AND no model call is in flight AND nothing new happened for [LabConfig.quietMs].
     * Returns false on timeout.
     */
    fun settle(sinceMs: Long, author: String?, channelId: String?, needDecision: Boolean, timeoutMs: Long = 90_000): Boolean {
        val deadline = sinceMs + timeoutMs
        val minEnd = sinceMs + 400
        while (System.currentTimeMillis() < deadline) {
            pumpMain()
            val now = System.currentTimeMillis()
            val decided = !needDecision || hasDecision(sinceMs, author, channelId)
            val quiet = ai.inFlight.get() == 0 &&
                now - maxOf(rec.lastEventMs, ai.lastActivityMs) >= cfg.quietMs
            if (now >= minEnd && decided && quiet) return true
            Thread.sleep(40)
        }
        rec.note("settle timed out after ${timeoutMs}ms (author=$author)")
        return false
    }

    /** Wait for the model proxy to go idle (background learn/observe passes). */
    fun idle(timeoutMs: Long = 60_000): Boolean = settle(System.currentTimeMillis(), null, null, false, timeoutMs)

    private fun hasDecision(sinceMs: Long, author: String?, channelId: String?): Boolean =
        rec.eventsSince(sinceMs).any { e ->
            when (e.type) {
                "trace" -> (author == null || e.data.optString("author") == author) &&
                    (channelId == null || e.data.optString("channel") == channelId)
                "bot_send", "bot_react" -> channelId == null || e.data.optString("channelId") == channelId
                // Silent-by-design outcomes that leave no routing trace (so we don't wait out the timeout).
                "log" -> channelId != null && e.data.optString("line").startsWith("backing off in $channelId")
                else -> false
            }
        }

    // ── Rendering ────────────────────────────────────────────────────────

    fun renderOutcome(o: Outcome): String {
        val sb = StringBuilder()
        o.human?.let { h ->
            sb.append("→ #").append(discord.channelById(h.channelId)?.name).append("  ").append(h.author.globalName)
                .append(": ").append(h.content.replace(Regex("<@${discord.bot.id}>"), "@Cardinal")).append('\n')
        }
        var any = false
        for (e in o.events) {
            val d = e.data
            val dt = o.human?.let { "+${(e.t - it.atMs)}ms" } ?: ""
            when (e.type) {
                "bot_typing" -> sb.append("   $dt typing…\n")
                "bot_send" -> { any = true; sb.append("   $dt CARDINAL").append(if (!d.isNull("replyTo")) " (↩ ${d.optString("replyToAuthor")})" else "")
                    .append(": ").append(d.optString("text")).append('\n') }
                "bot_react" -> { any = true; sb.append("   $dt react ").append(d.optString("emoji")).append(" on ").append(d.optString("target")).append('\n') }
                "trace" -> sb.append("   $dt trace ").append(d.optString("score")).append(" | ").append(d.optString("plan"))
                    .append(" | ").append(d.optString("action")).append(" | ").append(d.optString("detail").take(80)).append('\n')
                "ai" -> sb.append("   $dt ai#").append(d.optInt("n")).append(' ').append(d.optString("kind")).append(" ")
                    .append(d.optString("served").substringAfterLast('/')).append("  in=").append(d.optInt("promptTokens"))
                    .append(" out=").append(d.optInt("completionTokens")).append(if (d.optBoolean("estimated")) "~" else "")
                    .append("  ").append(d.optLong("latencyMs")).append("ms  ").append(d.optDouble("neurons")).append("n")
                    .append(if (!d.isNull("error")) "  ERROR ${d.optString("error")}" else "").append('\n')
                "log" -> sb.append("   $dt log ").append(d.optString("line")).append('\n')
                "note" -> sb.append("   $dt note ").append(d.optString("text")).append('\n')
            }
        }
        if (!any) sb.append("   (no bot action)\n")
        if (o.timedOut) sb.append("   (timed out waiting)\n")
        return sb.toString().trimEnd()
    }

    fun transcript(channel: String, n: Int = 40): String {
        val ch = discord.channel(channel)
        return discord.messages(ch.id).takeLast(n).joinToString("\n") { m ->
            val who = if (m.author.bot) "CARDINAL" else m.author.globalName
            val ref = m.replyTo?.let { r -> discord.message(r)?.let { " ↩${if (it.author.bot) "Cardinal" else it.author.globalName}" } }.orEmpty()
            val text = m.content.replace("<@${discord.bot.id}>", "@Cardinal")
            "[$who$ref] $text"
        }
    }

    // ── State ────────────────────────────────────────────────────────────

    val prefsFiles = listOf(
        "vrca_discord_personality", "vrca_discord_user_mem", "vrca_discord_server_mem",
        "vrca_discord_convo", "vrca_discord_channel_mem", "vrca_discord_budget",
    )

    /** Exact, typed dump of every store (restorable with [restore]) + in-memory summaries. */
    fun dumpState(): JSONObject {
        val out = JSONObject()
        val prefs = JSONObject()
        for (name in prefsFiles) {
            val o = JSONObject()
            ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all.toSortedMap().forEach { (k, v) ->
                o.put(k, JSONObject().put("type", when (v) {
                    is String -> "string"; is Long -> "long"; is Int -> "int"; is Boolean -> "bool"; is Float -> "float"
                    is Set<*> -> "set"; else -> "string" }).put("value", if (v is Set<*>) JSONArray(v.toList()) else v))
            }
            prefs.put(name, o)
        }
        out.put("prefs", prefs)
        val summaries = JSONObject()
        discord.channels().forEach { c -> ConversationStore.summary(c.id).takeIf { it.isNotBlank() }?.let { summaries.put(c.id, it) } }
        out.put("summaries", summaries)
        out.put("channels", JSONObject().apply { discord.channels().forEach { put(it.id, it.name) } })
        return out
    }

    fun restore(file: File) {
        val root = JSONObject(file.readText())
        val prefs = root.optJSONObject("prefs") ?: JSONObject()
        for (name in prefs.keys()) {
            val ed = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            val o = prefs.getJSONObject(name)
            for (k in o.keys()) {
                val e = o.getJSONObject(k)
                when (e.optString("type")) {
                    "long" -> ed.putLong(k, e.getLong("value"))
                    "int" -> ed.putInt(k, e.getInt("value"))
                    "bool" -> ed.putBoolean(k, e.getBoolean("value"))
                    "float" -> ed.putFloat(k, e.getDouble("value").toFloat())
                    "set" -> ed.putStringSet(k, e.getJSONArray("value").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
                    else -> ed.putString(k, e.get("value").toString())
                }
            }
            ed.commit()
        }
        root.optJSONObject("summaries")?.let { s ->
            for (ch in s.keys()) ConversationStore.updateSummary(ctx, ch, s.getString(ch), System.currentTimeMillis())
        }
        rec.note("restored state from ${file.path}")
    }

    /** Human-readable state — exactly what the stores would inject, via the production renderers. */
    fun renderState(): String {
        val sb = StringBuilder()
        sb.append("== Bot ==\n")
        sb.append("status=").append(DiscordBotState.statusFlow.value).append("  rung=").append(DiscordBotState.currentRung())
            .append("  neurons(est,app)=").append(DiscordBotState.neuronsFlow.value)
            .append("  seen=").append(DiscordBotState.seenFlow.value).append(" replied=").append(DiscordBotState.repliedFlow.value)
            .append(" reacted=").append(DiscordBotState.reactedFlow.value).append('\n')
        sb.append("lab live neurons=").append("%.1f".format(ai.liveNeurons)).append(" / cap ").append(cfg.neuronCap.toInt()).append('\n')
        sb.append("\n== Personality digest (injected verbatim) ==\n").append(PersonalityStore.snapshot(ctx).ifBlank { "(empty)" }).append('\n')
        val self = PersonalityStore.load(ctx)
        sb.append("traits stored: ").append(self.traits.size).append("  style: ").append(self.style.size)
            .append("  episodes: ").append(self.episodes.size).append('\n')
        self.traits.forEach { sb.append("  [").append(it.strength).append(if (it.pinned) "📌" else "").append("] ").append(it.text).append('\n') }
        sb.append("\n== User cards ==\n")
        val cards = UserMemoryStore.list(ctx)
        if (cards.isEmpty()) sb.append("(none)\n")
        cards.forEach { c ->
            sb.append("• ").append(c.name.ifBlank { c.id }).append("  (id ").append(c.id).append(", interactions ").append(c.interactions).append(")\n")
            val full = UserMemoryStore.renderForPrompt(c, emptySet(), full = true)
            sb.append(if (full.isBlank()) "    (renders empty — nothing injected)" else full.prependIndent("    ")).append('\n')
        }
        sb.append("\n== Server memories ==\n")
        val mems = ServerMemoryStore.list(ctx)
        if (mems.isEmpty()) sb.append("(none)\n")
        mems.forEach { sb.append("  [").append(it.strength).append("] ").append(it.text).append("  kw=").append(it.keywords).append('\n') }
        sb.append("\n== Channel bits ==\n")
        val bits = ChannelMemoryStore.listAll(ctx)
        if (bits.isEmpty()) sb.append("(none)\n")
        bits.forEach { (ch, list) ->
            sb.append("  #").append(discord.channelById(ch)?.name ?: ch).append('\n')
            list.forEach { sb.append("    [").append(it.strength).append("] ").append(it.text).append('\n') }
        }
        sb.append("\n== Conversation ==\n")
        discord.channels().forEach { c ->
            val s = ConversationStore.summary(c.id)
            val topics = ConversationStore.topicsFor(ctx, c.id)
            if (s.isNotBlank() || topics.isNotEmpty()) {
                sb.append("  #").append(c.name).append(" summary: ").append(s.ifBlank { "(none)" }).append('\n')
                topics.forEach { sb.append("    archived: ").append(it.summary).append('\n') }
            }
        }
        sb.append("\n== Day log ==\n")
        val days = com.vrca.discordbot.DayLogStore.days(ctx)
        if (days.isEmpty()) sb.append("(none)\n")
        days.take(5).forEach { d ->
            sb.append("  ").append(d.date).append(" (").append(d.entries.size).append(" notes)\n")
            if (d.digest.isNotBlank()) sb.append(d.digest.prependIndent("    recap ")).append('\n')
            d.entries.forEach { e -> sb.append("    ").append(if (e.moment) "★ " else "· ").append("#").append(e.channel).append(" ").append(e.text).append('\n') }
        }
        return sb.toString().trimEnd()
    }

    fun checkpoint(label: String) {
        idle(30_000)
        val snap = JSONObject().put("label", label).put("t", System.currentTimeMillis())
            .put("render", renderState()).put("state", dumpState())
        rec.checkpoints.add(label to snap)
        rec.event("checkpoint", JSONObject().put("label", label))
        File(cfg.runDir, "checkpoint-${label.replace(Regex("[^A-Za-z0-9_-]"), "_")}.txt").writeText(renderState())
    }

    fun applyConfig(o: JSONObject) {
        val cur = com.vrca.discordbot.DiscordBotStore.load(ctx)
        com.vrca.discordbot.DiscordBotStore.save(ctx,
            botToken = cur.botToken, cfAccountId = cur.cfAccountId, cfApiToken = cur.cfApiToken,
            cfGatewayId = cur.cfGatewayId, analyticsToken = cur.analyticsToken,
            model = o.optString("model", cur.model),
            ambientPercent = o.optInt("ambient", cur.ambientPercent),
            ambientCooldownSec = o.optInt("cooldown", cur.ambientCooldownSec),
            contextTurns = o.optInt("context", cur.contextTurns))
        if (o.has("shadow")) com.vrca.discordbot.DiscordBotStore.setShadowMode(ctx, o.optBoolean("shadow"))
        if (o.has("budget")) com.vrca.discordbot.DiscordBotStore.setDailyBudget(ctx, o.optLong("budget"))
        if (o.has("spent")) com.vrca.discordbot.NeuronBudget.add(ctx, o.optLong("spent"))
        rec.event("config", o)
        restartService()
    }
}
