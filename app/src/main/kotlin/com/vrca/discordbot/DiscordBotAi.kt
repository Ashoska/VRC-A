package com.vrca.discordbot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cloudflare **Workers AI** layer for Cardinal. Three roles, cheapest-first:
 *
 *  1. [reply] — the reply model writes ONLY the message text. It carries NO bookkeeping tail (that
 *     halved the per-reply cost AND fixed the junk-memory bugs the rushed inline tail produced) —
 *     all memory/summary/self/culture writing is done by the cheap 8B [observe] LEARN pass instead.
 *  2. [director] — the cheap 8B routes an AMBIGUOUS/ambient moment: reply/react/ignore + emoji.
 *  3. [observe] — the cheap 8B LEARN pass: refreshes the summary, learns durable facts about people
 *     (present AND merely talked-about), notes shared server culture + channel bits, and nudges the
 *     self. It runs in batches over EVERY message since the previous pass (replied or not), so memory
 *     keeps up with the whole channel without ever sitting on the hot reply path. Strong GOOD/BAD guidance so it stores who someone
 *     IS, never chatter or app-meta.
 *
 * The reply prompt is **lean by construction**: a short fixed core (who Cardinal is + how he talks),
 * then ONLY the context the app decided is relevant right now — who he's answering (always), the
 * channel, server memories / other people / the earlier-conversation summary / rules only when this
 * moment needs them, and the server's most-used emojis (always, so he can use one whenever he wants).
 * The app picks what goes in (free, instant); the model never has to ask. Transcript last. Routes
 * through **AI Gateway** when a gateway id is set.
 */
object DiscordBotAi {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private const val MEM_DELIM = "%%MEM%%"

    /**
     * Receives the neurons each call actually cost. Workers AI returns the exact figure in
     * `result.usage.neurons`; when it's missing we price the reported tokens instead. Set by the service.
     */
    @Volatile var billingSink: ((Double) -> Unit)? = null

    // Same problem logged at most once per window, so a dead model can't flood the activity log.
    private val lastIssueLog = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun logIssue(what: String, detail: String) {
        val key = "$what|${detail.take(40)}"
        val now = System.currentTimeMillis()
        val prev = lastIssueLog[key] ?: 0L
        if (now - prev < 5 * 60_000L) return
        lastIssueLog[key] = now
        DiscordBotState.log("$what failed: ${detail.take(140)}")
    }

    /** One conversation turn. [isBot] marks Cardinal's OWN past replies (role=assistant). */
    data class Turn(val isBot: Boolean, val name: String, val text: String)

    sealed class Result {
        data class Ok(val text: String) : Result()
        data class Error(val message: String) : Result()
    }

    sealed class ReplyResult {
        /** The reply is now JUST the message text — no memory/summary/self tail (see [observe]). */
        data class Ok(val text: String) : ReplyResult()
        data class Error(val message: String) : ReplyResult()
    }

    enum class Act { REPLY, REACT, IGNORE }
    data class Plan(val action: Act, val short: Boolean, val emoji: String)

    /** A claim the learner makes, with the numbered chat lines that show it. */
    data class LineClaim(val text: String, val lines: List<Int>)
    /** One typed note about a person: [type] is a slot (work, game, likes…), [line] the line that shows it. */
    data class Note(val about: String, val type: String, val value: String, val line: Int)
    /** Something about Cardinal: [kind] taste/title/bit/habit; [was] = the known trait it updates, if any. */
    data class SelfNote(val kind: String, val text: String, val lines: List<Int>, val was: String)

    data class Observation(
        val summary: String,
        val notes: List<Note>,
        val self: List<SelfNote>,
        val selfMood: String,
        val moment: LineClaim?,
        val joke: LineClaim?,
        val wrong: List<Int> = emptyList(),      // numbers of stored items the chat says are wrong / unwanted
        val bitNow: String = "",                 // the room-riffed bit's new version (only asked when one is in play)
    )

    /**
     * The fixed core: identity + the voice rules that fixed real misbehaviour (bot-meta, complaining
     * about pings, fake refusals, invented facts, bouncing questions back, flat hype). Learned
     * personality layers on top via [ReplyCtx.selfDigest].
     */
    // Rules only, no personality: how he talks (sass, length, caps…) comes from his learned traits and mood.
    private const val CORE = PersonalityStore.ANCHOR +
        " You chat here like anyone else. Go along with what people ask for fun (write something, pick, rate, roleplay," +
        " jokes about anyone, politicians of any side included) in your own voice, quirks and mood; no lecturing." +
        " If they keep pushing after you dodged, do it. If you know the answer, give it." +
        " Don't make up real-world facts. Have real opinions. Play along with jokes about you instead of denying them." +
        " Don't complain about pings."

    private val TRAIT_LABEL = Regex("\\b(Titles|Bits|Tastes|Habits|Traits):")

    private fun endpoint(cfg: DiscordBotStore.Config, model: String): String =
        if (cfg.cfGatewayId.isNotBlank())
            "${BotEndpoints.aiGateway}/${cfg.cfAccountId}/${cfg.cfGatewayId}/workers-ai/$model"
        else
            "${BotEndpoints.cfApi}/accounts/${cfg.cfAccountId}/ai/run/$model"

    /**
     * Everything the reply prompt needs, decided by the caller (the service picks what's relevant;
     * blank/false = leave that section out entirely).
     */
    data class ReplyCtx(
        val selfDigest: String,      // compact learned personality (mood + top traits)
        val channelInfo: String,     // "#general — <topic>": where you are, so you read the register
        val serverCulture: String,   // server memories relevant to this moment (+ revived topics)
        val channelBits: String,     // a running bit specific to THIS channel, deployed occasionally
        val crossRef: String,        // labelled recent messages from a channel the person referenced
        val answering: String,       // who you're answering + what you know about them (always)
        val othersPresent: Boolean,  // other people are talking too → don't answer the wrong person
        val othersBlock: String,     // other people worth knowing about right now
        val summary: String,         // earlier in the conversation, beyond the transcript
        val olderBotLines: List<String>, // own recent lines NOT already visible in the transcript
        val ownLinesVisible: Boolean,    // own lines are in the transcript (anti-repeat rule applies)
        val emojiHint: String,       // the server's most-used custom emojis (always, when it has any)
        val langHint: String,        // language to answer in (from the message's script)
        val namesRule: Boolean,      // nicknames/aliases in play → one-name-at-a-time rule
        val recall: Boolean,         // they asked what Cardinal knows → answer-from-memory rule
        val shortHint: Boolean,
        val dayLog: String = "",     // "what happened <day>?" → that day's log/recap
        val aboutSelf: Boolean = false,  // they're asking about Cardinal himself (his job/role/what he's known for)
        val bitCue: String = "",         // one of his bits the message is riffing on → yes-and it
        val reactingToYou: Boolean = false, // a short reaction ("ohh shit", "no way") to what Cardinal just said
        val clock: String = "",             // asked the time/date/timezone → the real UTC clock
        val verdictAsk: Boolean = false,    // "rate me 1-10" / "pick one" / "would you rather" → give an actual answer
        val nameHint: String = "",          // a name in their message Cardinal doesn't know (and who it most likely is)
        val pronouns: Map<String, String> = emptyMap(), // lowercased speaker name → the pronouns they asked for
    )

    suspend fun reply(cfg: DiscordBotStore.Config, model: String, turns: List<Turn>, c: ReplyCtx): ReplyResult {
        val sys = buildString {
            append(CORE)
            // Only what he actually has: the quirks line appears only when there are traits to talk about.
            if (c.selfDigest.isNotBlank()) append("\n\n[You] ").append(c.selfDigest)
                .append(if (TRAIT_LABEL.containsMatchIn(c.selfDigest)) " Use them only when they fit; if the chat twists one, go with it." else "")
                .append(if (c.aboutSelf) " They're asking about you right now: say your role or quirk plainly (its actual name), then add flavour." else "")
                .append(if (c.bitCue.isNotBlank()) " They're riffing on your bit \"${c.bitCue}\": yes-and where they take it (a new twist is fun), don't shut it down." else "")
            if (c.channelInfo.isNotBlank()) append("\n\n[Channel] ").append(c.channelInfo)
            // Who he's talking to first, then background knowledge, then rules.
            if (c.answering.isNotBlank()) {
                append("\n\n[Answering] ").append(c.answering)
                if (c.othersPresent) append(" (others in the chat are background)")
            }
            if (c.othersBlock.isNotBlank()) append("\n\n[Others]\n").append(c.othersBlock)
            if (c.summary.isNotBlank()) append("\n\n[Earlier] ").append(c.summary)
            if (c.serverCulture.isNotBlank()) append("\n\n[Server memories]\n").append(c.serverCulture)
            if (c.channelBits.isNotBlank()) append("\n\n[Running bit here, only if it fits] ").append(c.channelBits)
            if (c.crossRef.isNotBlank()) append("\n\n[Another channel they mentioned]\n").append(c.crossRef)
            if (c.dayLog.isNotBlank()) append("\n\n[What happened, from your notes — other people's doings unless it says Cardinal]\n").append(c.dayLog)
            if (c.namesRule) append("\n\n[Names] Use one name per person; never swap nicknames between people.")
            if (c.recall) append("\n\n[Memory question] Answer from what's above: say plainly what happened (who did what), as if they'd forgotten, not a hint or a vague 'yeah I saw'. If there's nothing, say so. Don't invent.")
            if (c.langHint.isNotBlank()) append("\n\n[Language] Reply in ").append(c.langHint).append(if (c.langHint == "English") ", whatever language came before." else ", native script, and answer what they actually said.")
            if (c.emojiHint.isNotBlank())
                append("\n\n[Emojis] Optional, written :name: — ").append(c.emojiHint)
                    .append(" (normal emojis too). Most messages need none; vary them.")
            if (c.olderBotLines.isNotEmpty())
                append("\n\n[Don't repeat] Recently said: ").append(c.olderBotLines.joinToString(" / ") { "\"${it.take(50)}\"" })
            else if (c.ownLinesVisible) append("\n\n[Don't repeat] your earlier lines.")
            if (c.reactingToYou) append("\n\nTheir message is a reaction to what you just said (surprise, agreement, a laugh), not a greeting: respond to that.")
            if (c.clock.isNotBlank()) append("\n\n[Clock] ").append(c.clock)
            if (c.verdictAsk) append("\n\nThey asked you to rate or pick: your reply must include your actual number or choice (even with little to go on, guess from the chat). Roast them while you give it; no dodging or asking for more.")
            if (c.shortHint) append("\n\nKeep it to one short line.")
            // A detail question still gets a chat message, not a guide cut off at the token limit.
            else append("\n\nA few lines at most, like a chat message (no headings or long lists).")
            // Last before the output line: the model weighs the end of the prompt most, and these are the
            // "who is who" calls it kept getting wrong ("don't encourage him" about itself).
            if (c.nameHint.isNotBlank()) append("\n\n[Who] ").append(c.nameHint)
            append("\n\nReply with just your message, no name prefix.")
        }
        val messages = JSONArray().put(obj("system", sys))
        // Merge consecutive same-author turns so the transcript reads as fewer, fuller turns.
        for (t in mergeTurns(turns)) {
            if (t.text.isBlank()) continue
            // Each speaker's own pronouns ride next to their name, so the reply never has to guess.
            val who = c.pronouns[t.name.lowercase().trim()]?.let { "${t.name} ($it)" } ?: t.name
            messages.put(obj(if (t.isBot) "assistant" else "user", if (t.isBot) t.text else "$who: ${t.text}"))
        }
        val maxTok = if (c.shortHint) DiscordBotLimits.SHORT_REPLY_MAX_TOKENS else DiscordBotLimits.REPLY_MAX_TOKENS
        return when (val r = call(cfg, model, messages, maxTok)) {
            is Result.Ok -> {
                // Defensive: strip any stray tail if the model still emits one out of habit.
                val idx = r.text.indexOf(MEM_DELIM)
                val text = (if (idx >= 0) r.text.substring(0, idx) else r.text).trim()
                if (text.isBlank()) ReplyResult.Error("Empty reply") else ReplyResult.Ok(text)
            }
            is Result.Error -> ReplyResult.Error(r.message)
        }
    }

    /**
     * The cheap DIRECTOR — an ambient reply/react/ignore call. He's a chatty regular, so lean toward
     * joining in unless it's a genuine private 1:1 or pure noise. Null on error (caller stays quiet).
     */
    suspend fun director(
        cfg: DiscordBotStore.Config, turns: List<Turn>, channelInfo: String = "", named: Boolean = false,
        followWith: String = "", exchange: Pair<String, String>? = null,
    ): Plan? {
        val transcript = mergeTurns(turns).takeLast(8).joinToString("\n") {
            if (it.isBot) "Cardinal: ${it.text}" else "${it.name}: ${it.text}"
        }
        // Follow-up mode: someone Cardinal was just talking with wrote again without @-ing him.
        if (followWith.isNotBlank()) {
            val (theirs, his) = exchange ?: ("" to turns.lastOrNull { it.isBot }?.text.orEmpty())
            val sys = "Cardinal (a member of this Discord) was talking with $followWith" +
                (if (theirs.isNotBlank()) " — $followWith said \"${theirs.take(140)}\" and Cardinal answered \"${his.take(140)}\". " else "; Cardinal's last line to them: \"${his.take(140)}\". ") +
                "Is $followWith's LAST message a reply to Cardinal? Only if it clearly continues with Cardinal: answers Cardinal's question, " +
                "reacts to what Cardinal said, or asks Cardinal something. Output only JSON: {\"action\":\"reply|react|ignore\",\"short\":true|false,\"emoji\":\"<emoji or empty>\"}. " +
                "Reply (react if it's only 'lol'/'true'/an emoji). Ignore when it answers or continues with someone else, starts a new topic " +
                "for the room, or you're not sure — most group chat isn't to Cardinal."
            val messages = JSONArray().put(obj("system", sys)).put(obj("user", "RECENT CHAT:\n$transcript"))
            return when (val r = call(cfg, DiscordBotLimits.CHEAP_MODEL, messages, 80, DiscordBotLimits.DIRECTOR_TEMPERATURE)) {
                is Result.Ok -> parsePlan(r.text) ?: run { logIssue("Follow-up check", "unreadable answer: ${r.text.take(60)}"); null }
                is Result.Error -> { logIssue("Follow-up check", r.message); null }
            }
        }
        val sys =
            "Decide whether Cardinal, a chatty, sassy member of this Discord, joins in after the LAST message. " +
            (if (channelInfo.isNotBlank()) "Channel: $channelInfo. " else "") +
            "Output only JSON: {\"action\":\"reply|react|ignore\",\"short\":true|false,\"emoji\":\"<emoji or empty>\"}. " +
            "Reply to add a joke, an opinion or an answer; react to a low-value 'lol'; ignore private 1:1 talk or noise. " +
            "In an active room lean reply/react." +
            (if (named) " The last message talks about Cardinal by name: answer it or react (a comeback to a roast, thanks for a compliment), ignore only if it's clearly not meant to reach Cardinal." else "")
        val messages = JSONArray().put(obj("system", sys)).put(obj("user", "RECENT CHAT:\n$transcript"))
        return when (val r = call(cfg, DiscordBotLimits.CHEAP_MODEL, messages, 80, DiscordBotLimits.DIRECTOR_TEMPERATURE)) {
            is Result.Ok -> parsePlan(r.text) ?: run { logIssue("Director", "unreadable answer: ${r.text.take(60)}"); null }
            is Result.Error -> { logIssue("Director", r.message); null }
        }
    }

    /**
     * The first JSON object in [text], tolerating what the small model gets wrong: prose around it and a
     * missing tail (it sometimes stops before the final `}` / `]` or mid-string) — unbalanced brackets and
     * an open string are closed. Null when there's no object at all.
     */
    internal fun jsonObjectIn(text: String): JSONObject? {
        val start = text.indexOf('{'); if (start < 0) return null
        val end = text.lastIndexOf('}')
        if (end > start) try { return JSONObject(text.substring(start, end + 1)) } catch (_: Exception) { }
        val raw = text.substring(start)
        val cleaned = escapeStrayQuotes(raw)
        if (cleaned != raw) {
            val e2 = cleaned.lastIndexOf('}')
            if (e2 > 0) try { return JSONObject(cleaned.substring(0, e2 + 1)) } catch (_: Exception) { }
        }
        return closeUnbalanced(cleaned) ?: closeUnbalanced(raw)
    }

    /**
     * `"summary":"bob's "fork soup" incident"` — a quote inside a value that the model forgot to escape.
     * A quote inside a string only CLOSES it when the next non-space char is `:` `,` `}` `]` (or the end);
     * any other one is escaped. Heuristic, but it rescues the common case instead of dropping the pass.
     */
    private fun escapeStrayQuotes(t: String): String {
        val sb = StringBuilder(t.length + 8)
        var inStr = false; var esc = false
        for (i in t.indices) {
            val ch = t[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    ch == '\\' -> esc = true
                    ch == '"' -> {
                        var j = i + 1
                        while (j < t.length && t[j].isWhitespace()) j++
                        if (j >= t.length || t[j] in ":,}]") inStr = false else { sb.append('\\') }
                    }
                }
            } else if (ch == '"') inStr = true
            sb.append(ch)
        }
        return sb.toString()
    }

    /**
     * Structural repair for the small model's usual slips: a missing closer at the end (cut off), a key
     * written inside an array (`[{..},"event":""}` — the array was never closed), or a closer of the wrong
     * kind. Tracks strings and brackets; closes what's open in the right order.
     */
    private fun closeUnbalanced(text: String): JSONObject? {
        val sb = StringBuilder(); val stack = ArrayDeque<Char>()
        var inStr = false; var esc = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (inStr) {
                sb.append(ch)
                if (esc) esc = false else if (ch == '\\') esc = true else if (ch == '"') inStr = false
                i++; continue
            }
            when (ch) {
                '"' -> {
                    // A string followed by ':' is a key — keys never live in an array, so close the array.
                    if (stack.lastOrNull() == ']' && isKeyAt(text, i)) {
                        var k = sb.length
                        while (k > 0 && sb[k - 1].isWhitespace()) k--
                        val hadComma = k > 0 && sb[k - 1] == ','
                        if (hadComma) sb.setLength(k - 1)
                        sb.append(']'); stack.removeLast()
                        if (hadComma) sb.append(',')
                    }
                    sb.append(ch); inStr = true
                }
                '{' -> { sb.append(ch); stack.addLast('}') }
                '[' -> { sb.append(ch); stack.addLast(']') }
                '}', ']' -> {
                    // Close anything left open inside before this closer (a wrong-kind closer).
                    while (stack.isNotEmpty() && stack.last() != ch) { trimComma(sb); sb.append(stack.removeLast()) }
                    trimComma(sb); sb.append(ch)
                    if (stack.isNotEmpty()) stack.removeLast()
                    if (stack.isEmpty()) break
                }
                else -> sb.append(ch)
            }
            i++
        }
        if (inStr) sb.append('"')
        trimComma(sb)
        while (stack.isNotEmpty()) { trimComma(sb); sb.append(stack.removeLast()) }
        return try { JSONObject(sb.toString()) } catch (_: Exception) { null }
    }

    private fun isKeyAt(t: String, quoteAt: Int): Boolean {
        var j = quoteAt + 1; var e = false
        while (j < t.length) { val c = t[j]; if (e) e = false else if (c == '\\') e = true else if (c == '"') break; j++ }
        j++
        while (j < t.length && t[j].isWhitespace()) j++
        return j < t.length && t[j] == ':'
    }

    private fun trimComma(sb: StringBuilder) {
        var k = sb.length
        while (k > 0 && sb[k - 1].isWhitespace()) k--
        if (k > 0 && sb[k - 1] == ',') sb.setLength(k - 1)
    }

    private fun parsePlan(text: String): Plan? = try {
        jsonObjectIn(text)?.let { o ->
            val act = when (o.optString("action").lowercase()) {
                "reply" -> Act.REPLY; "react" -> Act.REACT; else -> Act.IGNORE
            }
            Plan(act, o.optBoolean("short", true), o.optString("emoji").trim())
        }
    } catch (_: Exception) { null }

    /**
     * The LEARN pass (cheap 8B) over a numbered chat: a one-line summary, typed notes about people (each naming
     * the line that shows it, checked by the app before anything is stored), Cardinal's own tastes / titles / bits
     * / habits, a mood, and at most one moment + one inside joke — also line-referenced. [stored] (numbered) only
     * when the chat has a correction in it. Null on error (the batch is retried with the next one).
     */
    suspend fun observe(
        cfg: DiscordBotStore.Config, turns: List<Turn>, prevSummary: String, stored: String = "",
        selfTraits: List<String> = emptyList(), knownPeople: String = "", bitFocus: String = "",
    ): Observation? {
        val transcript = turns.withIndex().joinToString("\n") { (i, t) ->
            "${i + 1} ${if (t.isBot) "Cardinal" else t.name}: ${t.text.replace('\n', ' ').take(DiscordBotLimits.LEARN_LINE_MAX_CHARS)}"
        }
        val fix = stored.isNotBlank()
        val sys = buildString {
            append("You keep memory for Cardinal, a member of this Discord (\"Cardinal\" lines are theirs). Read the numbered chat; output only JSON:\n")
            append("{\"sum\":\"<who is talking about what, under 15 words>\",")
            append("\"notes\":[{\"p\":\"<name as shown>\",\"t\":\"<type>\",\"v\":\"<1-6 words, their words>\",\"l\":<line that shows it>}],")
            append("\"me\":[{\"t\":\"taste|title|bit|habit\",\"v\":\"<3-8 words about Cardinal>\",\"l\":[<lines>]}],")
            append("\"mood\":\"<Cardinal's mood, a word>\"")
            if (fix) append(",\"wrong\":[<numbers of STORED items>]")
            if (bitFocus.isNotBlank()) append(",\"bit\":\"<Cardinal's bit '$bitFocus' as it stands NOW, max 6 words, in the others' words; else same>\"")
            append("}\nTypes: from, lives, tz, work (job or study), game, hobby, likes, dislikes, pet, role (their role in this server), nick (a name others call them), about (said about themselves, fits no other type)")
            if (fix) append(", notnick (a name they asked not to be called), avoid (what they asked Cardinal to stop)")
            append(".\nA note needs a line where the person says it about themselves or someone says it plainly about them. ")
            append("Not notes: questions, jokes, what-ifs, what someone is doing right now or will do, family or friends' things, anything about Cardinal. Few chats have notes; [] is fine.\n")
            append("me: only what Cardinal's own lines show, or a title/bit two people give Cardinal; add \"was\":\"<known trait>\" if it changes one.\n")
            append("Optional, only if clearly true: \"moment\":{\"v\":\"<one sentence with names>\",\"l\":[<lines>]} (something people will bring up later), ")
            append("\"joke\":{\"v\":\"<the inside joke, who, why>\",\"l\":[<lines>]} (a running joke several people kept up).")
            if (selfTraits.isNotEmpty() && !fix) append("\nCardinal already: ").append(selfTraits.joinToString("; "))
            if (knownPeople.isNotBlank() && !fix) append("\nKnown (don't repeat):\n").append(knownPeople)
            if (fix) {
                append("\n\nSTORED:\n").append(stored).append('\n')
                append("Put an item's number in wrong if the chat says it's untrue or out of date, or people really asked Cardinal to stop it (teasing isn't asking). Add the correct note if one was given.")
            }
        }
        val user = (if (prevSummary.isNotBlank()) "Before: $prevSummary\n\n" else "") + transcript
        val messages = JSONArray().put(obj("system", sys)).put(obj("user", user))
        // A batch where the room is reshaping one of Cardinal's bits needs better judgement than the 8B shows.
        val model = if (bitFocus.isNotBlank()) DiscordBotLimits.MERGE_MODEL else DiscordBotLimits.LEARN_MODEL
        return when (val r = call(cfg, model, messages, DiscordBotLimits.LEARN_MAX_TOKENS)) {
            is Result.Ok -> parseObservation(r.text) ?: run { logIssue("Learn pass", "unreadable answer: ${r.text.take(60)}"); null }
            is Result.Error -> { logIssue("Learn pass", r.message); null }
        }
    }

    /**
     * End-of-day recap (cheap 8B, once per finished busy day): condenses the day log's summaries and
     * moments into a few lines so "what happened yesterday?" reads well and stays small. Null on error.
     */
    suspend fun digestDay(cfg: DiscordBotStore.Config, dayLabel: String, entries: String): String? {
        val sys = "Write a short recap of one day in a Discord server, for Cardinal's memory. 3 to 6 lines, each starting with \"- \". " +
            "Funny and notable moments first, keep who did what by name, then the main topics. Stay close to the notes: don't add details, feelings or consequences that aren't listed. No intro."
        val messages = JSONArray().put(obj("system", sys)).put(obj("user", "DAY: $dayLabel\n$entries"))
        return when (val r = call(cfg, DiscordBotLimits.CHEAP_MODEL, messages, DiscordBotLimits.DAY_DIGEST_MAX_TOKENS)) {
            is Result.Ok -> r.text.lines().map { it.trim() }.filter { it.startsWith("-") || it.startsWith("•") || it.startsWith("*") }
                .joinToString("\n") { "- " + it.trimStart('-', '•', '*', ' ') }.ifBlank { null }
            is Result.Error -> { logIssue("Day recap", r.message); null }
        }
    }

    private fun ints(v: Any?): List<Int> = when (v) {
        is JSONArray -> (0 until v.length()).mapNotNull { Regex("\\d+").find(v.opt(it)?.toString().orEmpty())?.value?.toIntOrNull() }
        null -> emptyList()
        else -> Regex("\\d+").findAll(v.toString()).mapNotNull { it.value.toIntOrNull() }.toList()
    }

    private fun claim(o: JSONObject?): LineClaim? {
        o ?: return null
        val v = o.optString("v").trim().ifBlank { o.optString("text").trim() }
        if (PLACEHOLDER.matches(v) || v.split(' ').size < 3) return null
        return LineClaim(v, ints(o.opt("l")))
    }

    private fun parseObservation(text: String): Observation? = try {
        jsonObjectIn(text)?.let { o ->
            fun str(v: String?) = v?.trim().orEmpty().takeUnless { PLACEHOLDER.matches(it) }.orEmpty()
            val notes = o.optJSONArray("notes")?.let { a -> (0 until a.length()).mapNotNull { i ->
                val n = a.optJSONObject(i) ?: return@mapNotNull null
                val about = n.optString("p").ifBlank { n.optString("about") }.trim()
                val v = str(n.optString("v").ifBlank { n.optString("value") })
                if (about.isBlank() || v.isBlank()) null
                else Note(about, n.optString("t").ifBlank { n.optString("type") }.trim(), v, ints(n.opt("l")).firstOrNull() ?: 0)
            } }.orEmpty()
            // "me" is a list; the small model sometimes sends one object, or files a note about Cardinal under notes.
            val meArr = o.optJSONArray("me") ?: o.optJSONObject("me")?.let { JSONArray().put(it) } ?: JSONArray()
            val self = (0 until meArr.length()).mapNotNull { i ->
                val m = meArr.optJSONObject(i) ?: return@mapNotNull null
                val v = str(m.optString("v")).replace(Regex("(?i)^(cardinal|they|they'?re|they are|he|he'?s)\\s+"), "").trim()
                if (v.isBlank() || v.all { it.isDigit() }) null else SelfNote(m.optString("t").trim().lowercase(), v, ints(m.opt("l")), m.optString("was").trim())
            } + notes.filter { it.about.equals("cardinal", true) }.map { SelfNote("habit", it.value, listOf(it.line), "") }
            Observation(
                summary = str(o.optString("sum").ifBlank { o.optString("summary") }).take(DiscordBotLimits.SUMMARY_MAX_CHARS),
                notes = notes.filterNot { it.about.equals("cardinal", true) },
                self = self,
                selfMood = str(o.optString("mood").ifBlank { o.optJSONObject("self")?.optString("mood").orEmpty() }),
                moment = claim(o.optJSONObject("moment")),
                joke = claim(o.optJSONObject("joke")),
                bitNow = str(o.optString("bit")).trim('.', ' ').takeUnless { it.equals("same", true) || it.startsWith("same ", true) }.orEmpty(),
                wrong = ints(o.opt("wrong")),
            )
        }
    } catch (_: Exception) { null }

    // "[none]", "none", "n/a", "-" — the small model's way of saying there's nothing.
    private val PLACEHOLDER = Regex("(?i)^\\W*(none|null|nil|n/?a|nothing|no|empty|unknown|not applicable|no (moments?|event|mood|trait))?\\W*$")

    private fun obj(role: String, content: String) = JSONObject().put("role", role).put("content", content)

    /** Merge runs of consecutive same-author, same-side turns into one turn (fewer, fuller turns). */
    private fun mergeTurns(turns: List<Turn>): List<Turn> {
        if (turns.size < 2) return turns
        val out = ArrayList<Turn>(turns.size)
        for (t in turns) {
            val last = out.lastOrNull()
            if (last != null && last.isBot == t.isBot && last.name == t.name) {
                out[out.size - 1] = last.copy(text = "${last.text}\n${t.text}".take(DiscordBotLimits.MAX_MSG_CHARS * 2))
            } else out.add(t)
        }
        return out
    }

    /** [temperature] null = the model's default. Only the director's one-word decision runs cooler; the
     *  learn pass stays at the default (a low temperature made the small model loop until max_tokens). */
    private suspend fun call(
        cfg: DiscordBotStore.Config, model: String, messages: JSONArray, maxTokens: Int, temperature: Double? = null,
    ): Result = withContext(Dispatchers.IO) {
        // One quick retry on a transient failure (5xx / network): a failed call isn't billed, and without it
        // a single upstream hiccup meant a message went unanswered.
        val first = callOnce(cfg, model, messages, maxTokens, temperature)
        if (first is Result.Error && TRANSIENT_ERR.containsMatchIn(first.message)) {
            kotlinx.coroutines.delay(600)
            callOnce(cfg, model, messages, maxTokens, temperature)
        } else first
    }

    // Per-model "don't think, just answer" switches (verified LIVE): these accept chat_template_kwargs;
    // qwen3 answers in the wrong field with that, so it gets the /no_think prompt flag instead.
    private val THINKING_KWARG_MODELS = listOf("gemma-4", "glm-4", "glm-5")
    private fun withoutThinking(model: String, messages: JSONArray): JSONArray {
        if (!model.contains("qwen3")) return messages
        val out = JSONArray(messages.toString())
        out.optJSONObject(0)?.takeIf { it.optString("role") == "system" }?.let { it.put("content", "/no_think " + it.optString("content")) }
        return out
    }

    private val TRANSIENT_ERR = Regex("HTTP (500|502|503|504|520|522|524)\\b|IOException|SocketTimeout|timeout|reset", RegexOption.IGNORE_CASE)

    private fun callOnce(
        cfg: DiscordBotStore.Config, model: String, messages: JSONArray, maxTokens: Int, temperature: Double?,
    ): Result {
        return try {
            val payload = JSONObject().put("messages", withoutThinking(model, messages)).put("max_tokens", maxTokens)
            if (temperature != null) payload.put("temperature", temperature)
            // Reasoning models spend the whole budget "thinking" (and answer nothing) unless it's switched off.
            if (THINKING_KWARG_MODELS.any { model.contains(it) })
                payload.put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            val body = payload.toString().toRequestBody(JSON)
            val req = Request.Builder()
                .url(endpoint(cfg, model))
                .addHeader("Authorization", "Bearer ${cfg.cfApiToken}")
                .addHeader("Content-Type", "application/json")
                .post(body).build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful)
                    return Result.Error("Workers AI HTTP ${resp.code}: ${extractError(raw) ?: raw.take(180)}")
                billingSink?.invoke(billedNeurons(raw, model, messages))
                val text = extractResponse(raw)?.replace(Regex("(?s)<think>.*?</think>"), "")?.trim()
                if (text.isNullOrBlank()) Result.Error("Empty AI response") else Result.Ok(text.trim())
            }
        } catch (e: Exception) {
            Result.Error("${e.javaClass.simpleName}: ${e.message ?: "network error"}")
        }
    }

    /**
     * What this call cost: Workers AI's own `usage.neurons` when present (exact), else the reported
     * tokens priced per model, else a rough character-based estimate. Replaces the old flat per-call
     * guesses (which over-charged replies ~1.6x and under-charged the learn pass ~4x).
     */
    private fun billedNeurons(raw: String, model: String, messages: JSONArray): Double {
        val usage = try { JSONObject(raw).optJSONObject("result")?.optJSONObject("usage") } catch (_: Exception) { null }
        val exact = usage?.optDouble("neurons", Double.NaN) ?: Double.NaN
        if (!exact.isNaN() && exact > 0.0) return exact
        val (inRate, outRate) = when {
            model.contains("70b") -> 0.026668 to 0.204805
            model.contains("gemma-4") -> 0.009091 to 0.027273
            model.contains("8b-instruct-fp8") && !model.contains("fast") -> 0.013778 to 0.026128
            model.contains("8b") -> 0.004119 to 0.034868
            else -> 0.026668 to 0.204805   // unknown: price like the 70B (conservative)
        }
        val pin = usage?.optInt("prompt_tokens", -1) ?: -1
        val pout = usage?.optInt("completion_tokens", -1) ?: -1
        return if (pin >= 0 && pout >= 0) pin * inRate + pout * outRate
        else messages.toString().length / 4.0 * inRate + 60 * outRate
    }

    private fun extractResponse(raw: String): String? = try {
        val root = JSONObject(raw)
        when (val result = root.opt("result")) {
            is JSONObject -> result.optString("response").ifBlank {
                result.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content") ?: ""
            }
            is String -> result
            else -> root.optString("response").takeIf { it.isNotBlank() }
        }
    } catch (_: Exception) { null }

    private fun extractError(raw: String): String? = try {
        val root = JSONObject(raw)
        root.optJSONArray("errors")?.optJSONObject(0)?.optString("message")?.takeIf { it.isNotBlank() }
            ?: root.optString("error").takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }
}
