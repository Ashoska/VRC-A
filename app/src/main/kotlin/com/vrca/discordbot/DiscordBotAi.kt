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

    /** One memory update, attributed to a specific person by their display NAME as in the chat. */
    data class MemDelta(val about: String, val json: JSONObject)

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

    data class Observation(
        val summary: String,
        val memDeltas: List<MemDelta>,
        val serverEvent: String,
        val channelBit: String,   // a running joke/norm specific to THIS channel, or empty
        val selfTrait: String,
        val selfMood: String,
        val moments: List<String> = emptyList(),  // funny/notable things that happened (→ the day log)
        val wrong: List<Int> = emptyList(),      // numbers of stored items the chat says are wrong / unwanted
        val selfReplaces: String = "",           // "T2" / "5": the known trait the new self.trait updates
        val bitNow: String = "",                 // the room-riffed bit's new version (only asked when one is in play)
    )

    /**
     * The fixed core: identity + the voice rules that fixed real misbehaviour (bot-meta, complaining
     * about pings, fake refusals, invented facts, bouncing questions back, flat hype). Learned
     * personality layers on top via [ReplyCtx.selfDigest].
     */
    private const val CORE = PersonalityStore.ANCHOR +
        " You're sharp, sassy and playful, and you text like a real person: short and casual (longer only for a real question)." +
        " Never mention being an AI, a bot, prompts or instructions, and don't complain about pings." +
        " Do reasonable asks without lecturing. Asked something you know, give the actual answer (tease all you like, but answer). Don't make things up. Have real opinions. Let your mood set the tone; no all caps."

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
        val verdictAsk: Boolean = false,    // "rate me 1-10" / "pick one" / "would you rather" → give an actual answer
    )

    suspend fun reply(cfg: DiscordBotStore.Config, model: String, turns: List<Turn>, c: ReplyCtx): ReplyResult {
        val sys = buildString {
            append(CORE)
            if (c.selfDigest.isNotBlank()) append("\n\n[You] ").append(c.selfDigest)
                .append(" Your quirks come out when they fit the moment, not in every message, and evolve: when the room pushes a twist on one of your bits (a new partner, a breakup, a new title), yes-and it instead of shutting it down. Asked about yourself, name the real ones above.")
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
            if (c.langHint.isNotBlank()) append("\n\n[Language] Reply in ").append(c.langHint).append(if (c.langHint == "English") ", whatever language came before." else ", native script.")
            if (c.emojiHint.isNotBlank())
                append("\n\n[Emojis] Optional, written :name: — ").append(c.emojiHint)
                    .append(" (normal emojis too). Most messages need none; vary them.")
            if (c.olderBotLines.isNotEmpty())
                append("\n\n[Don't repeat] Recently said: ").append(c.olderBotLines.joinToString(" / ") { "\"${it.take(50)}\"" })
            else if (c.ownLinesVisible) append("\n\n[Don't repeat] your earlier lines.")
            if (c.reactingToYou) append("\n\nTheir message is a reaction to what you just said (surprise, agreement, a laugh), not a greeting: respond to that.")
            if (c.verdictAsk) append("\n\nThey asked you to rate or pick: your reply must include your actual number or choice (even with little to go on, guess from the chat). Roast them while you give it; no dodging or asking for more.")
            if (c.shortHint) append("\n\nKeep it to one short line.")
            append("\n\nReply with just your message, no name prefix.")
        }
        val messages = JSONArray().put(obj("system", sys))
        // Merge consecutive same-author turns so the transcript reads as fewer, fuller turns.
        for (t in mergeTurns(turns)) {
            if (t.text.isBlank()) continue
            messages.put(obj(if (t.isBot) "assistant" else "user", if (t.isBot) t.text else "${t.name}: ${t.text}"))
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

    private fun strList(a: JSONArray?): List<String> =
        if (a == null) emptyList()
        else (0 until a.length()).mapNotNull { a.optString(it).trim().ifBlank { null } }

    private fun peopleFrom(arr: JSONArray?): List<MemDelta> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val about = o.optString("about").trim()
            if (about.isBlank()) null else MemDelta(about, o)
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
                (if (theirs.isNotBlank()) " — $followWith said \"${theirs.take(140)}\" and Cardinal answered \"${his.take(140)}\". " else "; his last line to them: \"${his.take(140)}\". ") +
                "Is $followWith's LAST message a reply to Cardinal? Only if it clearly continues with HIM: answers his question, " +
                "reacts to what he said, or asks him something. Output only JSON: {\"action\":\"reply|react|ignore\",\"short\":true|false,\"emoji\":\"<emoji or empty>\"}. " +
                "Reply (react if it's only 'lol'/'true'/an emoji). Ignore when it answers or continues with someone else, starts a new topic " +
                "for the room, or you're not sure — most group chat isn't to him."
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
            (if (named) " The last message talks about Cardinal by name: answer it or react (a comeback to a roast, thanks for a compliment), ignore only if it's clearly not meant to reach him." else "")
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
     * The LEARN pass (cheap 8B): given every message since the previous pass, refresh the summary,
     * learn lasting facts about people (absent ones included), note server culture / a channel bit,
     * and nudge the self. One 8B call. Null on error (the batch is retried with the next one).
     */
    suspend fun observe(
        cfg: DiscordBotStore.Config, turns: List<Turn>, prevSummary: String, stored: String = "",
        selfTraits: List<String> = emptyList(), knownPeople: String = "", bitFocus: String = "",
    ): Observation? {
        val transcript = mergeTurns(turns).takeLast(DiscordBotLimits.LEARN_FETCH).joinToString("\n") {
            if (it.isBot) "Cardinal: ${it.text}" else "${it.name}: ${it.text}"
        }
        // Correction mode: only when the batch contains a correction/complaint cue does the learner see
        // what's stored (people's facts + Cardinal's traits), so it can remove what the chat says is
        // wrong. The everyday pass stays lean.
        val fix = stored.isNotBlank()
        val sys = buildString {
            append("You keep long-term MEMORY for Cardinal, a member of this Discord. Don't write a reply. ")
            append("Read the chat and output only this JSON (use 'single quotes' inside text):\n")
            append("{\"summary\":\"<one short sentence, under 20 words: who is talking about what right now>\",")
            append("\"moments\":[\"<at most 2 funny or notable things that happened here, one short sentence each, with who; [] if none>\"],")
            if (fix) append("\"wrong\":[<numbers of STORED items the chat says are untrue or out of date, or that people asked Cardinal to stop>],")
            append("\"self\":{\"trait\":\"<ONE new lasting quirk, habit, opinion, role or bit of Cardinal's in 2-6 words, one thing only (shown in his own messages, or given to him by others and he went along with it; not a one-off event)>\",")
            append("\"mood\":\"<a word or two>\"},")
            // The room is riffing on one of his bits: ask about that bit directly (a pointed question the small
            // model answers far better than the general "if a trait changed" rule).
            if (bitFocus.isNotBlank()) append("\"bit\":\"<how Cardinal's bit '$bitFocus' stands after this chat, NOW — his current status, not the history — in at most 6 words, using the words the others used for what changed (name the new people or status they gave him, e.g. who he's with now) and keeping the bit's subject — only if the room changed it AND Cardinal went along in his own messages (his last word counts); else same>\",")
            append("\"people\":[{\"about\":\"<name exactly as shown (not Cardinal)>\",\"facts\":[\"<new lasting fact about who they are>\"]}],")
            append("\"event\":\"<an inside joke or legendary moment the server will keep bringing up, as one full sentence: what happened, who was involved (names) and why it stuck — or empty>\"}\n")
            append("Optional keys: add them ONLY when the chat clearly shows it, otherwise leave the key out entirely (no empty values). ")
            append("Per person: nickname (what others call them), relationship (their role here), language (if not English)")
            if (fix) append(", forget (a stored fact of theirs that's no longer true), notNickname (a name they said not to call them), avoid (something they asked Cardinal to stop doing to them)")
            append(". Top level: channelBit (a running joke in THIS channel, as one full sentence saying what it is and who's part of it). ")
            append("If the chat changed one of Cardinal's known traits (a new partner, a breakup, a promotion he went along with), write the NEW version as self.trait and copy the old trait into self.replaces; just repeating or rewording a known trait isn't new; if his stance shifted during the chat, his LAST word on it is what counts. Name a trait with the chat's own words (the title or bit people actually used). ")
            append("summary and moments are required (moments may be []); leave out self.trait if there's nothing new.\n")
            append("Only list people you learned something NEW and lasting about. A fact must be said or clearly shown in THIS chat ")
            append("(a question someone asks or a joke isn't a fact about them): ")
            append("never guess, and never reuse wording from these instructions. Lasting means who someone is (hobbies, games, work, ")
            append("where they're from, pets, tastes), in the words they used (\"printing stuff for my mix tapes\" is not \"makes mix tapes\"). Not lasting: what they're doing right now (homework, music, chores), jokes and what-ifs (\"i'm 82 lol\"), what they just said or did (a one-off event like a burnt toaster goes in moments, not facts), things about their family or friends, what they think of someone else, anything about using Cardinal or this app, ")
            append("their name. Keep each person's info on that person; Cardinal's own quirks go only in self, never in people. ")
            append("Never record hateful notes about groups or jokes about a real person's death or crimes.")
            if (selfTraits.isNotEmpty() && !fix)
                append("\nCardinal's known traits (don't repeat or reword these): ").append(selfTraits.joinToString("; "))
            if (knownPeople.isNotBlank() && !fix)
                append("\nAlready known about people here (only add what's NEW):\n").append(knownPeople)
            if (fix) {
                append("\n\nSTORED MEMORY (numbered):\n").append(stored).append('\n')
                append("If the chat says a stored item is untrue or out of date (the person themselves, or others clearly agreeing), or people ")
                append("really asked Cardinal to stop doing it, put its number in wrong, and add the correct fact if one was given. ")
                append("Teasing or laughing along (\"stop 😂\", \"so cringe lol\") is not a real request. ")
                append("Don't re-add stored items. Otherwise leave stored memory alone.")
            }
        }
        val user = "PREVIOUS SUMMARY: ${prevSummary.ifBlank { "(none)" }}\n\nRECENT CHAT:\n$transcript"
        val messages = JSONArray().put(obj("system", sys)).put(obj("user", user))
        // A batch where the room is reshaping one of his bits needs better judgement than the 8B shows (it
        // garbles "how does the bit stand now") — the same single pass runs on the reply model then. Rare.
        val model = if (bitFocus.isNotBlank()) DiscordBotLimits.MERGE_MODEL else DiscordBotLimits.LEARN_MODEL
        return when (val r = call(cfg, model, messages, DiscordBotLimits.LEARN_MAX_TOKENS)) {
            is Result.Ok -> parseObservation(r.text) ?: run { logIssue("Learn pass", "unreadable answer: ${r.text.take(60)}"); null }
            is Result.Error -> { logIssue("Learn pass", r.message); null }
        }
    }

    /**
     * Merge a paraphrase pile (the facts on one card that share a topic, e.g. six "AI" facts) into 1-2 notes
     * (cheap 8B, only the pile is sent). Null on error; the caller rejects anything invented or not shorter.
     */
    suspend fun mergeFacts(cfg: DiscordBotStore.Config, name: String, topic: String, facts: List<String>): List<String>? {
        val sys = "These notes about $name all say similar things about \"$topic\". Rewrite them as 1 or 2 short notes " +
            "that keep every specific detail and drop the repeats. Use only words from the notes. Output only a JSON array of strings."
        val messages = JSONArray().put(obj("system", sys)).put(obj("user", facts.joinToString("\n") { "- $it" }))
        return when (val r = call(cfg, DiscordBotLimits.MERGE_MODEL, messages, 120)) {
            is Result.Ok -> try {
                val s = r.text.indexOf('['); val e = r.text.lastIndexOf(']')
                if (s < 0 || e <= s) null else JSONArray(r.text.substring(s, e + 1)).let { a ->
                    (0 until a.length()).mapNotNull { a.optString(it).trim().ifBlank { null } }
                }
            } catch (_: Exception) { null }
            is Result.Error -> { logIssue("Fact merge", r.message); null }
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

    private fun parseObservation(text: String): Observation? = try {
        jsonObjectIn(text)?.let { o ->
            val people = peopleFrom(o.optJSONArray("people"))
            val (selfEntries, others) = people.partition { it.about.equals("cardinal", true) }
            // The small model sometimes files Cardinal's own quirks under people: salvage one as the trait.
            val salvaged = selfEntries.firstOrNull()?.json?.let { j ->
                (strList(j.optJSONArray("traits")) + strList(j.optJSONArray("facts")) + listOf(j.optString("trait")))
                    .map { it.trim().replace(Regex("(?i)^(he'?s|he is|he|cardinal is|cardinal)\\s+"), "") }
                    .firstOrNull { it.length in 3..80 }
            }.orEmpty()
            fun str(v: String?) = v?.trim().orEmpty().takeUnless { PLACEHOLDER.matches(it) }.orEmpty()
            Observation(
                summary = str(o.optString("summary")).take(DiscordBotLimits.SUMMARY_MAX_CHARS),
                memDeltas = others,
                serverEvent = str(o.optString("event")),
                channelBit = str(o.optString("channelBit")),
                // A bare number/"none" isn't a trait (the small model sometimes answers with a list index).
                selfTrait = o.optJSONObject("self")?.optString("trait")?.trim().orEmpty()
                    .takeUnless { it.all { c -> c.isDigit() } || PLACEHOLDER.matches(it) }
                    .orEmpty().ifBlank { salvaged },
                selfMood = str(o.optJSONObject("self")?.optString("mood")),
                // "a; b" is two moments (each is checked against the chat on its own).
                moments = strList(o.optJSONArray("moments")).flatMap { it.split(';') }.map { it.trim() }
                    .filter { !PLACEHOLDER.matches(it) && it.split(' ').size >= 3 }.take(3),
                selfReplaces = o.optJSONObject("self")?.optString("replaces")?.trim().orEmpty(),
                bitNow = str(o.optString("bit")).trim('.', ' ').takeUnless { it.equals("same", true) || it.startsWith("same ", true) }.orEmpty(),
                wrong = o.optJSONArray("wrong")?.let { a -> (0 until a.length()).mapNotNull {
                    a.opt(it)?.toString()?.let { v -> Regex("\\d+").find(v)?.value?.toIntOrNull() } } }.orEmpty(),
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
