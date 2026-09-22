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
 * Cloudflare **Workers AI** layer for Cardinal. Three roles:
 *
 *  1. [reply] — the strong model writes the actual message AND, in a trailing `%%MEM%%` JSON
 *     tail, proposes what to remember about the person it answered — so ONE call does the reply
 *     and the memory update (no separate model call on the hot path).
 *  2. [director] — the cheap 8B model routes an AMBIGUOUS/multi-person moment: reply/react/ignore,
 *     fuse simultaneous people or answer separately, a length hint, an emoji, and it emits the
 *     updated rolling thread summary as a byproduct.
 *  3. [reflect] — the cheap model evolves the self (traits/style/mood/episode) off the hot path.
 *     The single content boundary lives in this prompt (no message-scanning filter).
 *
 * Routes through **AI Gateway** when a gateway id is set, else the direct Workers-AI endpoint.
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

    sealed class Result {
        data class Ok(val text: String) : Result()
        data class Error(val message: String) : Result()
    }

    sealed class ReplyResult {
        data class Ok(val text: String, val memDelta: JSONObject?) : ReplyResult()
        data class Error(val message: String) : ReplyResult()
    }

    enum class Act { REPLY, REACT, IGNORE }
    data class Plan(
        val action: Act,
        val fuse: Boolean,
        val lengthShort: Boolean,
        val emoji: String,
        val summary: String,
    )

    data class Reflection(
        val traits: List<String>,
        val style: List<String>,
        val mood: String,
        val episode: String,
    )

    /** Tiny, non-stiff seed. Texture, not a rulebook — the learned style layers on top of it. */
    private const val SEED =
        "You're a regular in this chat, not an assistant. Talk like a person texting in a group: " +
        "short, in the room's voice. Don't narrate what you're doing, don't say you're an AI, " +
        "don't lecture. If you won't do something, brush it off with a quip. No @everyone/role pings."

    private fun endpoint(cfg: DiscordBotStore.Config, model: String): String =
        if (cfg.cfGatewayId.isNotBlank())
            "https://gateway.ai.cloudflare.com/v1/${cfg.cfAccountId}/${cfg.cfGatewayId}/workers-ai/$model"
        else
            "https://api.cloudflare.com/client/v4/accounts/${cfg.cfAccountId}/ai/run/$model"

    /**
     * Write a reply as Cardinal. [selfDigest] = [PersonalityStore.snapshot]; [cardsBlock] = the
     * active participants' [UserMemoryStore] cards; [summary] = the rolling thread summary;
     * [turns] = the last few raw turns (target last). [model] lets the caller drop to the cheap
     * model under budget pressure. Parses the `%%MEM%%` tail into [ReplyResult.Ok.memDelta].
     */
    suspend fun reply(
        cfg: DiscordBotStore.Config,
        model: String,
        selfDigest: String,
        cardsBlock: String,
        summary: String,
        turns: List<Turn>,
        shortHint: Boolean,
    ): ReplyResult {
        val sys = buildString {
            append(PersonalityStore.ANCHOR).append('\n').append(SEED)
            if (selfDigest.isNotBlank()) append("\n\n").append(selfDigest)
            if (cardsBlock.isNotBlank()) append("\n\n").append(cardsBlock)
            if (summary.isNotBlank()) append("\n\nSo far: ").append(summary)
            if (shortHint) append("\n\nKeep it to one short line.")
            append("\n\nAfter your reply, on a NEW line output ").append(MEM_DELIM)
            append(" then a compact JSON object of anything worth remembering about the person you ")
            append("answered (keys: facts [array of short strings], bit, sentiment, relationship, ")
            append("howToTreat) — or ").append(MEM_DELIM).append("{} if nothing. That line is never shown.")
        }
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", sys))
        for (t in turns) {
            if (t.text.isBlank()) continue
            messages.put(JSONObject()
                .put("role", if (t.isBot) "assistant" else "user")
                .put("content", if (t.isBot) t.text else "${t.name}: ${t.text}"))
        }
        return when (val r = call(cfg, model, messages, DiscordBotLimits.REPLY_MAX_TOKENS)) {
            is Result.Ok -> {
                val idx = r.text.indexOf(MEM_DELIM)
                val text = (if (idx >= 0) r.text.substring(0, idx) else r.text).trim()
                val delta = if (idx >= 0) parseMem(r.text.substring(idx + MEM_DELIM.length)) else null
                if (text.isBlank()) ReplyResult.Error("Empty reply") else ReplyResult.Ok(text, delta)
            }
            is Result.Error -> ReplyResult.Error(r.message)
        }
    }

    private fun parseMem(tail: String): JSONObject? = try {
        val s = tail.indexOf('{'); val e = tail.lastIndexOf('}')
        if (s < 0 || e <= s) null else JSONObject(tail.substring(s, e + 1))
    } catch (_: Exception) { null }

    /**
     * The cheap DIRECTOR. Given the recent [turns] and whether the bot was [addressed], decide how
     * a chat regular should respond (reply/react/ignore, fuse simultaneous people, length, emoji)
     * and emit the updated one-line thread [Plan.summary]. Null on error (caller picks a fallback).
     */
    suspend fun director(
        cfg: DiscordBotStore.Config,
        turns: List<Turn>,
        addressed: Boolean,
        prevSummary: String,
    ): Plan? {
        val transcript = turns.takeLast(8).joinToString("\n") {
            if (it.isBot) "Cardinal: ${it.text}" else "${it.name}: ${it.text}"
        }
        val sys =
            "You direct a Discord chat regular named Cardinal. Read the recent chat and the LAST " +
            "message. Output ONLY a JSON object: {\"action\":\"reply|react|ignore\"," +
            "\"fuse\":true|false,\"short\":true|false,\"emoji\":\"<one emoji or empty>\"," +
            "\"summary\":\"<=1 line of what's going on>\"}. " +
            (if (addressed)
                "He was addressed, so usually reply; react for a trivial 'lol'; fuse=true only if two+ people are asking him about the SAME thing at once."
            else
                "He was NOT addressed. Only reply if the last message is genuinely interesting or invites it; otherwise ignore. Don't butt into a private back-and-forth.")
        val user = "PREVIOUS SUMMARY: ${prevSummary.ifBlank { "(none)" }}\n\nRECENT CHAT:\n$transcript"
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", sys))
            .put(JSONObject().put("role", "user").put("content", user))
        return when (val r = call(cfg, DiscordBotLimits.CHEAP_MODEL, messages, 120)) {
            is Result.Ok -> parsePlan(r.text)
            is Result.Error -> null
        }
    }

    private fun parsePlan(text: String): Plan? = try {
        val s = text.indexOf('{'); val e = text.lastIndexOf('}')
        if (s < 0 || e <= s) null
        else JSONObject(text.substring(s, e + 1)).let { o ->
            val act = when (o.optString("action").lowercase()) {
                "reply" -> Act.REPLY
                "react" -> Act.REACT
                else -> Act.IGNORE
            }
            Plan(
                action = act,
                fuse = o.optBoolean("fuse", false),
                lengthShort = o.optBoolean("short", true),
                emoji = o.optString("emoji").trim(),
                summary = o.optString("summary").trim().take(DiscordBotLimits.SUMMARY_MAX_CHARS),
            )
        }
    } catch (_: Exception) { null }

    /**
     * The MUTATION step (off the hot path): given Cardinal's current self and a recent [transcript],
     * the cheap model proposes his updated traits/style/mood + maybe one memorable episode. The one
     * content boundary lives here (no keyword filter).
     */
    suspend fun reflect(
        cfg: DiscordBotStore.Config,
        currentTraits: List<String>,
        currentStyle: List<String>,
        currentMood: String,
        transcript: String,
    ): Reflection? {
        val sys =
            "You maintain the evolving personality of a Discord chat regular named Cardinal, based on " +
            "the room's vibe. Output ONLY a JSON object: {\"traits\":[8-14 short strings]," +
            "\"style\":[3-5 first-person lines on how he talks],\"mood\":\"one short line\"," +
            "\"episode\":\"<a memorable server moment worth remembering, or empty>\"}. " +
            "Keep traits that still fit, drop stale ones, add ones the room clearly vibes with (likes, " +
            "dislikes, running jokes, opinions). Do NOT add traits about hating a protected group, or " +
            "about jokes aimed at a real named person's death or crimes."
        val user = buildString {
            append("CURRENT TRAITS: ").append(currentTraits.ifEmpty { listOf("(none)") }.joinToString("; "))
            append("\nCURRENT STYLE: ").append(currentStyle.ifEmpty { listOf("(none)") }.joinToString("; "))
            append("\nCURRENT MOOD: ").append(currentMood.ifBlank { "(none)" })
            append("\n\nRECENT CHAT:\n").append(transcript)
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", sys))
            .put(JSONObject().put("role", "user").put("content", user))
        return when (val r = call(cfg, DiscordBotLimits.CHEAP_MODEL, messages, 400)) {
            is Result.Ok -> parseReflection(r.text)
            is Result.Error -> null
        }
    }

    private fun parseReflection(text: String): Reflection? = try {
        val s = text.indexOf('{'); val e = text.lastIndexOf('}')
        if (s < 0 || e <= s) null
        else JSONObject(text.substring(s, e + 1)).let { o ->
            Reflection(
                traits = strArr(o.optJSONArray("traits")).take(DiscordBotLimits.MAX_TRAITS),
                style = strArr(o.optJSONArray("style")).take(6),
                mood = o.optString("mood").trim(),
                episode = o.optString("episode").trim(),
            )
        }
    } catch (_: Exception) { null }

    private fun strArr(a: JSONArray?): List<String> =
        if (a == null) emptyList()
        else (0 until a.length()).mapNotNull { a.optString(it).trim().ifBlank { null } }

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
                .post(body)
                .build()
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
                result.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content") ?: ""
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
