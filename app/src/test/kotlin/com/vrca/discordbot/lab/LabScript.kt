package com.vrca.discordbot.lab

import com.vrca.discordbot.ConversationStore
import com.vrca.discordbot.PersonalityStore
import com.vrca.discordbot.ServerMemoryStore
import com.vrca.discordbot.UserMemoryStore
import org.json.JSONObject

/**
 * Plain-text chat scripts — fast to write by the hundred, readable as a transcript:
 *
 * ```
 * # comment
 * @set ambient=0 context=8 shadow=0     (bot config; restarts the service like the admin tab would)
 * @gap 1200                             (ms between non-waited lines)
 * @channel memes shitposts only         (declare / retopic a channel)
 * [general]                             (switch channel)
 * alice: hey @Cardinal what's up        (a message; @Cardinal = real mention; @bob / #media resolve too)
 * alice ^bot: lol no                    (reply to Cardinal's last message here; ^bob = bob's last)
 * alice +img: look at this              (attach an image)   · !wait / !nowait force waiting
 * > wait | > sleep 3000 | > quiet (learn pass runs) | > follow-expire | > react carol bot 😂 | > checkpoint name | > drop-gateway | > note text
 * > expect reply | no-reply | react | respond (reply or react) | quiet (neither) | contains <re> | not-contains <re>
 * > expect cards contains|not-contains <re>   (all memory card names)
 * > expect card <name> contains|not-contains <re> | server contains <re> | self contains <re> | summary contains <re>
 * ```
 * Addressed lines (mention / ^bot) wait for the bot to settle; other lines just pace by the gap.
 */
internal class LabScript(private val d: LabDriver) {
    private val msgRe = Regex("^([A-Za-z0-9_.~-]+)((?:\\s+[\\^+!][^\\s:]*)*)\\s*:\\s?(.*)$")
    private var gapMs = d.cfg.gapMs
    private var lastHumanAt = 0L
    private var lastHumanAuthor: String? = null
    /** Highest event seq already printed, so a later `> wait` shows what !nowait lines triggered. */
    private var printedSeq = 0

    fun run(name: String, text: String): String {
        d.rec.event("script", JSONObject().put("name", name).put("event", "start"))
        d.rec.note("script $name — ${text.lines().count { it.isNotBlank() && !it.trimStart().startsWith("#") }} lines")
        val out = StringBuilder()
        for ((i, raw) in text.lines().withIndex()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (d.ai.capHit) { out.append("!! neuron cap hit — stopping script at line ${i + 1}\n"); break }
            try { step(line, out) } catch (e: Exception) {
                out.append("!! line ${i + 1} failed: ${e.javaClass.simpleName}: ${e.message}\n")
                d.rec.note("script line ${i + 1} failed: $line — ${e.message}")
            }
        }
        d.idle(60_000)
        d.rec.event("script", JSONObject().put("name", name).put("event", "end"))
        return out.toString().trimEnd()
    }

    private fun step(line: String, out: StringBuilder) {
        when {
            line.startsWith("@set ") -> {
                val o = JSONObject()
                line.removePrefix("@set ").trim().split(Regex("\\s+")).forEach { kv ->
                    val (k, v) = kv.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                    when (k) {
                        "shadow" -> o.put("shadow", v == "1" || v == "true")
                        "model" -> o.put("model", v)
                        else -> v.toLongOrNull()?.let { o.put(k, it) }
                    }
                }
                d.applyConfig(o); out.append("@set $o\n")
            }
            line.startsWith("@gap ") -> gapMs = line.removePrefix("@gap ").trim().toLong()
            line.startsWith("@channel ") -> {
                val parts = line.removePrefix("@channel ").trim().split(Regex("\\s+"), limit = 2)
                d.discord.channel(parts[0], parts.getOrNull(1).orEmpty())
            }
            line.startsWith("[") && line.endsWith("]") -> d.currentChannel = line.trim('[', ']').trim().trimStart('#')
            line.startsWith(">") -> directive(line.removePrefix(">").trim(), out)
            else -> {
                val m = msgRe.matchEntire(line) ?: run { out.append("?? unparsed: $line\n"); return }
                val user = m.groupValues[1]
                val mods = m.groupValues[2].trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                val text = m.groupValues[3]
                val replyTo = mods.firstOrNull { it.startsWith("^") }?.removePrefix("^")
                val image = mods.any { it == "+img" }
                val wait = when { mods.contains("!wait") -> true; mods.contains("!nowait") -> false; else -> null }
                val o = d.say(user, text, replyTo = replyTo, image = image, wait = wait)
                lastHumanAt = o.human?.atMs ?: System.currentTimeMillis()
                lastHumanAuthor = user
                if (o.events.isNotEmpty() || o.timedOut) {
                    out.append(d.renderOutcome(o)).append('\n')
                    printedSeq = maxOf(printedSeq, o.events.maxOfOrNull { it.seq } ?: 0)
                } else {
                    out.append("→ #").append(d.currentChannel).append("  ").append(user).append(": ").append(text).append('\n')
                    printedSeq = maxOf(printedSeq, d.rec.events.lastOrNull { it.type == "human" }?.seq ?: 0)
                    Thread.sleep(gapMs)
                }
            }
        }
    }

    private fun directive(cmd: String, out: StringBuilder) {
        val parts = cmd.split(Regex("\\s+"))
        when (parts[0]) {
            "wait" -> {
                d.settle(System.currentTimeMillis(), null, null, needDecision = false)
                val tail = d.rec.events.filter { it.seq > printedSeq && it.type in setOf("human", "bot_send", "bot_react", "trace", "ai") }
                tail.forEach { e ->
                    if (e.type == "human") out.append("  ⋯ ").append(e.data.optString("author")).append(": ").append(e.data.optString("text")).append('\n')
                    else out.append(d.renderOutcome(LabDriver.Outcome(null, listOf(e), false)).lineSequence().first()).append('\n')
                }
                printedSeq = d.rec.events.lastOrNull()?.seq ?: printedSeq
            }
            "sleep" -> Thread.sleep(parts.getOrNull(1)?.toLongOrNull() ?: 1000)
            // The room goes quiet long enough for the learn pass to run (real timing, or ~10x faster with fast=1).
            "quiet" -> Thread.sleep(maxOf(com.vrca.discordbot.DiscordBotLimits.LEARN_LULL_MS, com.vrca.discordbot.DiscordBotLimits.LEARN_LULL_MIN_GAP_MS) + 10_000L)
            // Long enough for every conversation window to close.
            "time-skip" -> d.discord.skipTime(parts.getOrNull(1)?.toLongOrNull() ?: 60)
            "follow-expire" -> Thread.sleep(com.vrca.discordbot.DiscordBotLimits.FOLLOW_WINDOW_MS + 15_000L)
            "react" -> d.react(parts[1], parts[2], parts.getOrElse(3) { "👍" })
            "checkpoint" -> { d.checkpoint(parts.getOrElse(1) { "cp" }); out.append("[checkpoint ${parts.getOrElse(1) { "cp" }}]\n") }
            "drop-gateway" -> {
                val code = parts.getOrNull(1)?.toIntOrNull() ?: 4000
                d.discord.dropGateway(code); out.append("[gateway dropped with $code]\n")
            }
            "gateway-reject" -> { d.discord.rejectWith = parts.getOrNull(1)?.toIntOrNull() ?: 4014; out.append("[gateway rejects sessions with ${d.discord.rejectWith}]\n") }
            "gateway-accept" -> { d.discord.rejectWith = null; out.append("[gateway accepts sessions]\n") }
            "gateway-stats" -> out.append("[gateway connects=${d.discord.connects.get()} identifies=${d.discord.identifies.get()}]\n")
            "gateway-heartbeat" -> {
                d.discord.heartbeatIntervalMs = parts.getOrNull(1)?.toLongOrNull() ?: 41_250L
                out.append("[gateway heartbeat_interval=${d.discord.heartbeatIntervalMs}ms for new sessions]\n")
            }
            "log" -> {
                // Bot log lines + gateway events since the last printed line (what the admin would see).
                val tail = d.rec.events.filter { it.seq > printedSeq && it.type in setOf("log", "gateway") }
                tail.forEach { e ->
                    if (e.type == "log") out.append("   log ").append(e.data.optString("line")).append('\n')
                    else out.append("   gateway ").append(e.data.toString()).append('\n')
                }
                if (tail.isEmpty()) out.append("   (no log lines)\n")
                printedSeq = d.rec.events.lastOrNull()?.seq ?: printedSeq
            }
            "note" -> d.rec.note(cmd.removePrefix("note").trim())
            "expect" -> expect(cmd.removePrefix("expect").trim(), out)
            "teach" -> teach(cmd.removePrefix("teach").trim(), out)
            else -> out.append("?? unknown directive: $cmd\n")
        }
    }

    /**
     * Seed memory through the SAME store APIs the bot/admin use (facts still pass the poisoning
     * guards), so a dry run can measure prompts with a realistic, lived-in memory:
     * `> teach self|style|mood|pinned <text>` · `> teach card|nick|prefer|rel <name> <text>` ·
     * `> teach server <text>` · `> teach bit <channel> <text>` · `> teach summary <text>`
     */
    private fun teach(spec: String, out: StringBuilder) {
        val (what, rest) = spec.split(Regex("\\s+"), limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val now = System.currentTimeMillis()
        val ctx = d.ctx
        fun person(): Pair<FakeDiscord.User, String> {
            val (name, text) = rest.split(Regex("\\s+"), limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            return d.discord.user(name) to text
        }
        when (what) {
            "self" -> PersonalityStore.noteSelf(ctx, rest, null, null)
            "style" -> PersonalityStore.noteSelf(ctx, null, rest, null)
            "mood" -> PersonalityStore.noteSelf(ctx, null, null, rest)
            "pinned" -> PersonalityStore.teachTrait(ctx, rest)
            "card", "nick", "prefer", "rel", "lang" -> {
                val (u, text) = person()
                val delta = JSONObject()
                if (what == "card") { UserMemoryStore.noteFromText(ctx, u.id, u.globalName, text); d.rec.event("teach", JSONObject().put("what", what).put("text", rest)); return }
                when (what) {
                    "nick" -> delta.put("nickname", text)
                    "prefer" -> delta.put("preferredName", text)
                    "rel" -> delta.put("relationship", text)
                    "lang" -> delta.put("language", text)
                }
                UserMemoryStore.applyDelta(ctx, u.id, u.globalName, delta)
            }
            "server" -> ServerMemoryStore.remember(ctx, rest, now)
            "bit" -> {
                val (ch, text) = rest.split(Regex("\\s+"), limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                com.vrca.discordbot.ChannelMemoryStore.remember(ctx, d.discord.channel(ch).id, text, now)
            }
            "summary" -> ConversationStore.updateSummary(ctx, d.discord.channel(d.currentChannel).id, rest, now)
            // > teach day -1 21 general moment|topic <text>   (days back, hour, channel, kind, text)
            "day" -> {
                val p = rest.split(Regex("\\s+"), limit = 5)
                val date = com.vrca.discordbot.DayLogStore.dateOf(now).plusDays(p[0].toLong())
                com.vrca.discordbot.DayLogStore.seed(ctx, date, p[1].toInt(), p[2], p.getOrElse(4) { "" }, p[3] == "moment")
            }
            else -> { out.append("?? unknown teach: $spec\n"); return }
        }
        d.rec.event("teach", JSONObject().put("what", what).put("text", rest))
    }

    private fun expect(spec: String, out: StringBuilder) {
        d.settle(System.currentTimeMillis(), null, null, needDecision = false, timeoutMs = 60_000)
        val since = lastHumanAt
        val evs = d.rec.eventsSince(since)
        val replies = evs.filter { it.type == "bot_send" }.map { it.data.optString("text") }
        val reacts = evs.filter { it.type == "bot_react" }
        val parts = spec.split(Regex("\\s+"), limit = 4)
        fun re(s: String) = Regex(s)
        val (pass, detail) = when (parts[0]) {
            "reply" -> (replies.isNotEmpty()) to (replies.lastOrNull() ?: "no reply")
            "no-reply" -> (replies.isEmpty()) to (replies.lastOrNull() ?: "silent")
            // "threaded <name>" = his last reply quote-replies <name>'s message; "plain" = no quote.
            "threaded", "plain" -> {
                val last = evs.lastOrNull { it.type == "bot_send" }
                val to = last?.data?.takeUnless { it.isNull("replyToAuthor") }?.optString("replyToAuthor")
                val ok = last != null && if (parts[0] == "plain") to == null else (to != null && (parts.size < 2 || to.equals(parts[1], true)))
                ok to (last?.let { "↩ ${to ?: "plain"}: ${it.data.optString("text").take(60)}" } ?: "no reply")
            }
            "respond" -> (replies.isNotEmpty() || reacts.isNotEmpty()) to (replies.lastOrNull() ?: reacts.joinToString { it.data.optString("emoji") }.ifBlank { "nothing" })
            "quiet" -> (replies.isEmpty() && reacts.isEmpty()) to (replies.lastOrNull() ?: reacts.joinToString { it.data.optString("emoji") }.ifBlank { "nothing" })
            "cards" -> {
                val all = UserMemoryStore.list(d.ctx).joinToString(" | ") { it.name }
                val body = spec.removePrefix("cards").trim()
                val neg = body.startsWith("not-contains")
                val hit = re(body.removePrefix("not-contains").removePrefix("contains").trim()).containsMatchIn(all)
                (if (neg) !hit else hit) to all.take(200)
            }
            "react" -> (reacts.isNotEmpty()) to (reacts.joinToString { it.data.optString("emoji") }.ifBlank { "no react" })
            "contains" -> replies.any { re(spec.removePrefix("contains").trim()).containsMatchIn(it) } to (replies.lastOrNull() ?: "no reply")
            "not-contains" -> replies.none { re(spec.removePrefix("not-contains").trim()).containsMatchIn(it) } to (replies.lastOrNull() ?: "no reply")
            "card" -> {
                val who = parts.getOrElse(1) { "" }
                val user = d.discord.knownUser(who)
                val card = user?.let { UserMemoryStore.load(d.ctx, it.id) }
                val rendered = card?.let { UserMemoryStore.renderForPrompt(it, emptySet(), full = true) } ?: "(no card)"
                val op = parts.getOrElse(2) { "contains" }
                val hit = parts.getOrNull(3)?.let { re(it).containsMatchIn(rendered) } ?: false
                (if (op == "not-contains") !hit else hit) to rendered.replace('\n', ' ').take(200)
            }
            "server" -> {
                val all = ServerMemoryStore.list(d.ctx).joinToString(" | ") { it.text }
                re(spec.removePrefix("server").trim().removePrefix("contains").trim()).containsMatchIn(all) to all.take(200)
            }
            "self" -> {
                val s = PersonalityStore.load(d.ctx).traits.joinToString(" | ") { "${it.text} (${it.strength})" }
                val body = spec.removePrefix("self").trim()
                val neg = body.startsWith("not-contains")
                val hit = re(body.removePrefix("not-contains").removePrefix("contains").trim()).containsMatchIn(s)
                (if (neg) !hit else hit) to s.take(300)
            }
            "day" -> {
                // > expect day -1 contains <regex>   (checks that day's digest + entries)
                val back = parts.getOrElse(1) { "0" }.toLong()
                val date = com.vrca.discordbot.DayLogStore.dateOf(System.currentTimeMillis()).plusDays(back)
                val day = com.vrca.discordbot.DayLogStore.load(d.ctx, date)
                val all = ((day?.digest ?: "") + " || " + day?.entries.orEmpty().joinToString(" | ") { it.text })
                val body = spec.substringAfter(parts.getOrElse(1) { "0" }).trim()
                val neg = body.startsWith("not-contains")
                val hit = re(body.removePrefix("not-contains").removePrefix("contains").trim()).containsMatchIn(all)
                (if (neg) !hit else hit) to all.take(300)
            }
            "status" -> {
                Thread.sleep(3000)   // let late socket callbacks land
                val st = com.vrca.discordbot.DiscordBotState.statusFlow.value.name
                st.equals(parts.getOrElse(1) { "CONNECTED" }, true) to "$st (${com.vrca.discordbot.DiscordBotState.detailFlow.value})"
            }
            "summary" -> {
                val s = ConversationStore.summary(d.discord.channel(d.currentChannel).id)
                re(spec.removePrefix("summary").trim().removePrefix("contains").trim()).containsMatchIn(s) to s.take(200)
            }
            else -> false to "unknown expectation"
        }
        val rec = JSONObject().put("spec", spec).put("pass", pass).put("detail", detail)
            .put("after", lastHumanAuthor ?: JSONObject.NULL)
        d.rec.expectations.add(rec)
        d.rec.event("expect", rec)
        out.append(if (pass) "   ✓ expect $spec\n" else "   ✗ expect $spec  — got: $detail\n")
    }
}
