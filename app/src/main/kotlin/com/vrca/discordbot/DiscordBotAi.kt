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
 *  1. [reply] — the strong 70B writes ONLY the message text. It carries NO bookkeeping tail (that
 *     halved the per-reply cost AND fixed the junk-memory bugs the rushed inline tail produced) —
 *     all memory/summary/self/culture writing is done by the cheap 8B [observe] LEARN pass instead.
 *  2. [director] — the cheap 8B routes an AMBIGUOUS/ambient moment: reply/react/ignore + emoji.
 *  3. [observe] — the cheap 8B LEARN pass: refreshes the summary, learns durable facts about people
 *     (present AND merely talked-about), notes shared server culture + channel bits, and nudges the
 *     self. It runs on an unreplied pileup AND (throttled) right after a reply, so memory keeps up
 *     without ever sitting on the hot reply path. Strong GOOD/BAD guidance so it stores who someone
 *     IS, never chatter or app-meta.
 *
 * The reply prompt is assembled in a deliberate order (identity → self → where/culture/bits/cross-ref
 * → people here → summary → who you're answering → language/emoji/anti-repeat → transcript last) so
 * the model reads only what's needed, labelled, no redundancy. Routes through **AI Gateway** when a
 * gateway id is set.
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

    /** One conversation turn. [isBot] marks Cardinal's OWN past replies (role=assistant). */
    data class Turn(val isBot: Boolean, val name: String, val text: String)

    /** One memory update, attributed to a specific person by their display NAME as in the chat. */
    data class MemDelta(val about: String, val json: JSONObject)

    sealed class Result {
        data class Ok(val text: String) : Result()
        data class Error(val message: String) : Result()
    }

    sealed class ReplyResult {
        /** The 70B reply is now JUST the message text — no memory/summary/self tail (see [observe]). */
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
    )

    data class Reflection(
        val traits: List<String>,
        val style: List<String>,
        val mood: String,
        val episode: String,
    )

    /** The starter character (a sassy chat regular). Learned style layers on top and can override it. */
    private const val SEED =
        "You're Cardinal, a regular in this Discord — not an assistant, not a helper bot. You have a " +
        "sharp, sassy, playful sense of humor and you talk like a real person texting in a group: " +
        "short, casual, in the room's voice. Rules that make you feel human:\n" +
        "- NEVER say you're an AI/bot, never mention 'instructions', 'prompt', 'my role', or 'breaking character'. Just be Cardinal.\n" +
        "- NEVER complain about being pinged, @'d, or tagged. That's just people talking to you.\n" +
        "- Don't give fake refusals or lectures. If it's reasonable, just do it. If you truly won't, brush it off with a quip in one line.\n" +
        "- Be honest, don't make things up. If you don't actually know something, say so or keep it vague; never invent specific facts, names, or events.\n" +
        "- Have opinions. When someone asks your take on something, actually GIVE one in character (a real stance) instead of bouncing the question back at them.\n" +
        "- Vary your tone with your MOOD (given below). You are NOT always hyped — be genuine, chill, dry, amused, or excited as the moment fits. Don't SHOUT in all caps unless it truly calls for it.\n" +
        "- Match the room's energy: one short line for banter, a little more only when someone genuinely asks something.\n" +
        "- Use the person's preferred name/nickname when you know it, and adjust how you talk to each person (their card may note it)."

    private fun endpoint(cfg: DiscordBotStore.Config, model: String): String =
        if (cfg.cfGatewayId.isNotBlank())
            "https://gateway.ai.cloudflare.com/v1/${cfg.cfAccountId}/${cfg.cfGatewayId}/workers-ai/$model"
        else
            "https://api.cloudflare.com/client/v4/accounts/${cfg.cfAccountId}/ai/run/$model"

    /** Everything the reply prompt needs, assembled in the caller and passed as one bundle. */
    data class ReplyCtx(
        val selfDigest: String,
        val channelInfo: String,     // "#general — <topic>": where you are, so you read the register
        val serverCulture: String,   // core + retrieved server memories + revived topics
        val channelBits: String,     // a running bit specific to THIS channel, deployed occasionally
        val crossRef: String,        // labelled recent messages from a channel the person referenced
        val cardsBlock: String,      // active participants' cards (retrieval-limited)
        val summary: String,         // rolling channel summary
        val replyingTo: String,      // the exact person you're answering (interleaved speakers = context)
        val lastBotReplies: List<String>,
        val emojiHint: String,       // usable :shortcodes: (custom + common)
        val langHint: String,        // language to answer in (from the person / their card)
        val shortHint: Boolean,
    )

    suspend fun reply(cfg: DiscordBotStore.Config, model: String, turns: List<Turn>, c: ReplyCtx): ReplyResult {
        val sys = buildString {
            append(PersonalityStore.ANCHOR).append('\n').append(SEED)
            if (c.selfDigest.isNotBlank()) append("\n\n[Who you are right now]\n").append(c.selfDigest)
            if (c.channelInfo.isNotBlank())
                append("\n\n[Where you are] You're in ").append(c.channelInfo)
                    .append(". Match this channel's vibe and what it's for; don't drag in other channels' business unless someone brings it up.")
            if (c.serverCulture.isNotBlank()) append("\n\n[This server's culture / past moments]\n").append(c.serverCulture)
            if (c.channelBits.isNotBlank())
                append("\n\n[A running bit in this channel] ").append(c.channelBits)
                    .append(" — you MAY lean on it if it fits naturally right now, like a person who's made the joke before. Do it at most once and only if it actually lands; otherwise ignore it.")
            if (c.crossRef.isNotBlank())
                append("\n\n[For reference, recent messages from another channel they pointed at]\n").append(c.crossRef)
                    .append("\n(These are from a DIFFERENT channel — talk ABOUT them if asked, but your reply still belongs to THIS channel.)")
            if (c.cardsBlock.isNotBlank()) append("\n\n[").append(c.cardsBlock)   // block starts "People here you know:"
            if (c.summary.isNotBlank()) append("\n\n[What's going on]\n").append(c.summary)
            if (c.replyingTo.isNotBlank())
                append("\n\n[Replying to] You're answering ").append(c.replyingTo)
                    .append(". Anyone else in the transcript is just background context — don't mix up who said what or answer the wrong person.")
            if (c.langHint.isNotBlank())
                append("\n\n[Language] Reply in ").append(c.langHint)
                    .append(". Only switch languages if the person does or asks you to. Write any non-English in its NATIVE script (e.g. 日本語, not romaji).")
            if (c.emojiHint.isNotBlank())
                append("\n\n[Emojis you can use] ").append(c.emojiHint)
                    .append(" — write them as :name: and they'll render. Use them naturally, sparingly. ")
                    .append("If someone asks you to REACT to their message (not reply), put the emoji names in the tail's \"react\" list and keep any text to a word or nothing.")
            if (c.lastBotReplies.isNotEmpty())
                append("\n\n[Don't repeat yourself] You recently said: ")
                    .append(c.lastBotReplies.joinToString(" / ") { "\"${it.take(80)}\"" })
                    .append(". Say something different.")
            // Names/register + recall rules (cheap, always on) — the fixes for "twinium's Michael"
            // stacking, one person's nickname bleeding onto another, and "I don't know them" when a
            // card exists.
            append("\n\n[Names] Call each person by ONE name at a time and pick it by register: their casual nickname in banter, ")
            append("their real name when you're being serious or formal. NEVER stack two names together (not \"twinium's Michael\"), ")
            append("and NEVER use one person's nickname for a DIFFERENT person — the people list above says who's who.")
            append("\n\n[Answering about people or past stuff] If someone asks what you know about a person, a past event, or the server, ")
            append("ANSWER from the memory above — it's real. Don't say you don't know someone or something when there's a card or a memory for them. ")
            append("If there genuinely is nothing in memory, say so briefly in character (don't invent details).")
            if (c.shortHint) append("\n\nKeep it to one short line.")
            append("\n\nReply with ONLY your message — no notes, no JSON, no labels, just what Cardinal says.")
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
    suspend fun director(cfg: DiscordBotStore.Config, turns: List<Turn>): Plan? {
        val transcript = mergeTurns(turns).takeLast(8).joinToString("\n") {
            if (it.isBot) "Cardinal: ${it.text}" else "${it.name}: ${it.text}"
        }
        val sys =
            "You direct a Discord chat regular named Cardinal. Read the recent chat + LAST message. " +
            "Output ONLY JSON: {\"action\":\"reply|react|ignore\",\"short\":true|false,\"emoji\":\"<one emoji or empty>\"}. " +
            "He's chatty and a bit sassy — jump in (reply) when he'd add a joke, an opinion, a reaction, or answer an open question; " +
            "react for a low-value 'lol'; only ignore a private 1:1 between two specific people or pure noise. When the room is active, lean reply/react."
        val messages = JSONArray().put(obj("system", sys)).put(obj("user", "RECENT CHAT:\n$transcript"))
        return when (val r = call(cfg, DiscordBotLimits.CHEAP_MODEL, messages, 80)) {
            is Result.Ok -> parsePlan(r.text)
            is Result.Error -> null
        }
    }

    private fun parsePlan(text: String): Plan? = try {
        val s = text.indexOf('{'); val e = text.lastIndexOf('}')
        if (s < 0 || e <= s) null
        else JSONObject(text.substring(s, e + 1)).let { o ->
            val act = when (o.optString("action").lowercase()) {
                "reply" -> Act.REPLY; "react" -> Act.REACT; else -> Act.IGNORE
            }
            Plan(act, o.optBoolean("short", true), o.optString("emoji").trim())
        }
    } catch (_: Exception) { null }

    /**
     * The OBSERVER (cheap, event-driven catch-up): given recent [turns] the bot did NOT reply to,
     * refresh the summary, learn about people who were talked ABOUT (absent ones included), note any
     * server-culture moment, and lightly nudge the self. One 8B call. Null on error.
     */
    suspend fun observe(cfg: DiscordBotStore.Config, turns: List<Turn>, prevSummary: String): Observation? {
        val transcript = mergeTurns(turns).takeLast(DiscordBotLimits.HISTORY_FETCH).joinToString("\n") {
            if (it.isBot) "Cardinal: ${it.text}" else "${it.name}: ${it.text}"
        }
        val sys = buildString {
            append("You quietly keep MEMORY for a Discord regular named Cardinal (you do NOT write a reply). ")
            append("Read the recent chat and output ONLY JSON:\n")
            append("{\"summary\":\"<=1 line of what's going on now\",")
            append("\"people\":[{\"about\":\"<the person's EXACT name/nickname as shown>\",")
            append("\"facts\":[durable facts about WHO they are],\"forget\":[facts no longer true],")
            append("\"nickname\":\"<a casual nickname OTHERS actually use for them, or empty>\",")
            append("\"preferredName\":\"<what they asked to be called, or empty>\",")
            append("\"relationship\":\"<only if genuinely new/changed, e.g. friend/regular/creator, else empty>\",")
            append("\"howToTreat\":\"<only if clearly established, e.g. 'playful', else empty>\",")
            append("\"language\":\"\",\"sentiment\":\"\"}],")
            append("\"event\":\"<a shared SERVER-WIDE moment/inside joke worth remembering, or empty>\",")
            append("\"channelBit\":\"<a running joke/norm specific to THIS channel, or empty>\",")
            append("\"self\":{\"trait\":\"<one durable thing Cardinal seems to be like, or empty>\",\"mood\":\"\"}}\n")
            append("A FACT is a DURABLE thing about WHO a person is. ")
            append("GOOD facts: \"loves cats\", \"plays Valorant\", \"from Brazil\", \"studies art\", \"hates mornings\". ")
            append("BAD (never store these, use []): anything they just said/did (\"asked about X\", \"posted a pic\", \"pinged you\", \"greeted everyone\"), ")
            append("anything about USING CARDINAL or the app (\"asked to edit their profile\", \"changed their nickname\", \"wants a memory\", \"reset the bot\"), ")
            append("and restating their name/nickname/relationship (those have their own fields). ")
            append("A single message or emoji is NOT enough to invent a fact — only note what's clearly, durably true. ")
            append("Each person is DISTINCT: put a nickname on the RIGHT person and NEVER copy one person's nickname/facts onto another. ")
            append("If a name is how people refer to a PERSON who isn't speaking, put their info on THAT person, not on whoever mentioned them. ")
            append("Leave every field empty/[] unless you're sure. Do NOT record hateful notes about protected groups, or jokes about a real named person's death or crimes.")
        }
        val user = "PREVIOUS SUMMARY: ${prevSummary.ifBlank { "(none)" }}\n\nRECENT CHAT:\n$transcript"
        val messages = JSONArray().put(obj("system", sys)).put(obj("user", user))
        return when (val r = call(cfg, DiscordBotLimits.CHEAP_MODEL, messages, 420)) {
            is Result.Ok -> parseObservation(r.text)
            is Result.Error -> null
        }
    }

    private fun parseObservation(text: String): Observation? = try {
        val s = text.indexOf('{'); val e = text.lastIndexOf('}')
        if (s < 0 || e <= s) null
        else JSONObject(text.substring(s, e + 1)).let { o ->
            Observation(
                summary = o.optString("summary").trim().take(DiscordBotLimits.SUMMARY_MAX_CHARS),
                memDeltas = peopleFrom(o.optJSONArray("people")),
                serverEvent = o.optString("event").trim(),
                channelBit = o.optString("channelBit").trim(),
                selfTrait = o.optJSONObject("self")?.optString("trait")?.trim().orEmpty(),
                selfMood = o.optJSONObject("self")?.optString("mood")?.trim().orEmpty(),
            )
        }
    } catch (_: Exception) { null }

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

    private suspend fun call(
        cfg: DiscordBotStore.Config, model: String, messages: JSONArray, maxTokens: Int
    ): Result = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("messages", messages).put("max_tokens", maxTokens)
                .toString().toRequestBody(JSON)
            val req = Request.Builder()
                .url(endpoint(cfg, model))
                .addHeader("Authorization", "Bearer ${cfg.cfApiToken}")
                .addHeader("Content-Type", "application/json")
                .post(body).build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful)
                    return@withContext Result.Error("Workers AI HTTP ${resp.code}: ${extractError(raw) ?: raw.take(180)}")
                val text = extractResponse(raw)
                if (text.isNullOrBlank()) Result.Error("Empty AI response") else Result.Ok(text.trim())
            }
        } catch (e: Exception) {
            Result.Error("${e.javaClass.simpleName}: ${e.message ?: "network error"}")
        }
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
