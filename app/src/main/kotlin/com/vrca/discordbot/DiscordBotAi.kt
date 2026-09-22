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
 *  1. [reply] — the strong model writes the message AND, in a trailing `%%MEM%%` JSON tail, does
 *     ALL the bookkeeping in the SAME call: per-person memory deltas, the rolled conversation
 *     summary, an inline personality note (so the self develops from message one — no reflection
 *     timer), and an optional shared server-culture memory. One call, everything.
 *  2. [director] — the cheap 8B routes an AMBIGUOUS/ambient moment: reply/react/ignore + emoji.
 *  3. [observe] — the cheap 8B CATCH-UP pass, fired only when notable UNREPLIED activity piles up:
 *     refreshes the summary, learns about people who were talked about (even absent ones), notes
 *     server culture, and lightly nudges the self. Event-driven, never on a timer.
 *
 * The prompt is assembled in a deliberate order (identity → self → server culture/topics →
 * people here → summary → instruction+tail spec → transcript last) so the model reads only what's
 * needed, labelled, no redundancy. Routes through **AI Gateway** when a gateway id is set.
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
        data class Ok(
            val text: String,
            val memDeltas: List<MemDelta>,
            val summary: String,
            val selfTrait: String,
            val selfMood: String,
            val serverEvent: String,
        ) : ReplyResult()
        data class Error(val message: String) : ReplyResult()
    }

    enum class Act { REPLY, REACT, IGNORE }
    data class Plan(val action: Act, val short: Boolean, val emoji: String)

    data class Observation(
        val summary: String,
        val memDeltas: List<MemDelta>,
        val serverEvent: String,
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
        "- Match the room's energy: one short line for banter, a little more only when someone genuinely asks something.\n" +
        "- Use the person's preferred name/nickname when you know it."

    private fun endpoint(cfg: DiscordBotStore.Config, model: String): String =
        if (cfg.cfGatewayId.isNotBlank())
            "https://gateway.ai.cloudflare.com/v1/${cfg.cfAccountId}/${cfg.cfGatewayId}/workers-ai/$model"
        else
            "https://api.cloudflare.com/client/v4/accounts/${cfg.cfAccountId}/ai/run/$model"

    /** Everything the reply prompt needs, assembled in the caller and passed as one bundle. */
    data class ReplyCtx(
        val selfDigest: String,
        val serverCulture: String,   // core + retrieved server memories + revived topics
        val cardsBlock: String,      // active participants' cards (retrieval-limited)
        val summary: String,         // rolling channel summary
        val lastBotReplies: List<String>,
        val emojiHint: String,       // usable :shortcodes: (custom + common)
        val langHint: String,        // language to answer in (from the person / their card)
        val shortHint: Boolean,
    )

    suspend fun reply(cfg: DiscordBotStore.Config, model: String, turns: List<Turn>, c: ReplyCtx): ReplyResult {
        val sys = buildString {
            append(PersonalityStore.ANCHOR).append('\n').append(SEED)
            if (c.selfDigest.isNotBlank()) append("\n\n[Who you are right now]\n").append(c.selfDigest)
            if (c.serverCulture.isNotBlank()) append("\n\n[This server's culture / past moments]\n").append(c.serverCulture)
            if (c.cardsBlock.isNotBlank()) append("\n\n[").append(c.cardsBlock)   // block starts "People here you know:"
            if (c.summary.isNotBlank()) append("\n\n[What's going on]\n").append(c.summary)
            if (c.langHint.isNotBlank())
                append("\n\n[Language] Reply in ").append(c.langHint)
                    .append(". Only switch languages if the person does or asks you to. Write any non-English in its NATIVE script (e.g. 日本語, not romaji).")
            if (c.emojiHint.isNotBlank())
                append("\n\n[Emojis you can use] ").append(c.emojiHint)
                    .append(" — write them as :name: and they'll render. Use them naturally, sparingly.")
            if (c.lastBotReplies.isNotEmpty())
                append("\n\n[Don't repeat yourself] You recently said: ")
                    .append(c.lastBotReplies.joinToString(" / ") { "\"${it.take(80)}\"" })
                    .append(". Say something different.")
            if (c.shortHint) append("\n\nKeep it to one short line.")
            append("\n\n[After your reply] On a NEW line output ").append(MEM_DELIM)
            append(" then ONE JSON object (never shown to anyone):\n")
            append("{\"people\":[{\"about\":\"<their EXACT name/nickname as shown>\",\"facts\":[short strings],")
            append("\"bit\":\"\",\"nickname\":\"\",\"preferredName\":\"<what they want to be called, or empty>\",")
            append("\"language\":\"\",\"alsoSpeaks\":[],\"sentiment\":\"\",\"relationship\":\"\",\"howToTreat\":\"\"}],")
            append("\"summary\":\"<=1 line of what's going on now\",")
            append("\"self\":{\"trait\":\"<one short thing you noticed about yourself, or empty>\",\"mood\":\"\"},")
            append("\"event\":\"<a shared server moment/joke worth remembering, or empty>\"}\n")
            append("Attribute every fact to the RIGHT person by name — never mix people up. Only fill fields you're SURE of; use empty/[] otherwise. This line is never shown.")
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
                val idx = r.text.indexOf(MEM_DELIM)
                val text = (if (idx >= 0) r.text.substring(0, idx) else r.text).trim()
                if (text.isBlank()) return ReplyResult.Error("Empty reply")
                val tail = if (idx >= 0) parseTail(r.text.substring(idx + MEM_DELIM.length)) else null
                ReplyResult.Ok(
                    text = text,
                    memDeltas = tail?.first ?: emptyList(),
                    summary = tail?.second?.optString("summary")?.trim().orEmpty(),
                    selfTrait = tail?.second?.optJSONObject("self")?.optString("trait")?.trim().orEmpty(),
                    selfMood = tail?.second?.optJSONObject("self")?.optString("mood")?.trim().orEmpty(),
                    serverEvent = tail?.second?.optString("event")?.trim().orEmpty(),
                )
            }
            is Result.Error -> ReplyResult.Error(r.message)
        }
    }

    /** Parse the tail into (people deltas, the raw object for summary/self/event). Tolerant of an old bare array. */
    private fun parseTail(tail: String): Pair<List<MemDelta>, JSONObject>? = try {
        val s = tail.indexOf('{'); val e = tail.lastIndexOf('}')
        if (s in 0 until e) {
            val o = JSONObject(tail.substring(s, e + 1))
            Pair(peopleFrom(o.optJSONArray("people")), o)
        } else {
            val a = tail.indexOf('['); val az = tail.lastIndexOf(']')
            if (a in 0 until az) Pair(peopleFrom(JSONArray(tail.substring(a, az + 1))), JSONObject()) else null
        }
    } catch (_: Exception) { null }

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
        val sys =
            "You quietly keep notes for a Discord regular named Cardinal (you do NOT write a reply). " +
            "Read the recent chat and output ONLY JSON: {\"summary\":\"<=1 line of what's going on\"," +
            "\"people\":[{\"about\":\"<name>\",\"facts\":[short strings],\"nickname\":\"\",\"language\":\"\",\"sentiment\":\"\"}]," +
            "\"event\":\"<a shared moment/inside joke worth remembering, or empty>\"," +
            "\"self\":{\"trait\":\"<one short thing Cardinal seems to be like, or empty>\",\"mood\":\"\"}}. " +
            "Note people even if they're being talked ABOUT rather than present. Attribute facts to the RIGHT person. " +
            "Only include things clearly true from the chat; empty/[] otherwise. Do NOT record hateful notes about protected groups, or jokes about a real named person's death or crimes."
        val user = "PREVIOUS SUMMARY: ${prevSummary.ifBlank { "(none)" }}\n\nRECENT CHAT:\n$transcript"
        val messages = JSONArray().put(obj("system", sys)).put(obj("user", user))
        return when (val r = call(cfg, DiscordBotLimits.CHEAP_MODEL, messages, 350)) {
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
