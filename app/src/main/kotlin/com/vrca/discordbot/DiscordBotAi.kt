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
 * Calls Cloudflare **Workers AI** for chat replies, plus a cheap 8B "should I chime in?"
 * triage for ambient (unaddressed) messages.
 *
 * When [DiscordBotStore.Config.cfGatewayId] is set the request is routed through
 * **AI Gateway** (`gateway.ai.cloudflare.com/v1/{account}/{gateway}/workers-ai/{model}`),
 * else the direct Workers-AI endpoint. The `@cf/...` model id goes into the path literally.
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

    /** Cheap model for the ambient "is this worth replying to?" gate (~1/10th the neurons
     *  of the reply model), so we only spend the big model when it's actually going to talk. */
    private const val TRIAGE_MODEL = "@cf/meta/llama-3.1-8b-instruct"

    /** Always-on behaviour layer applied on TOP of the admin's persona prompt — it shapes
     *  FORM only (short, in-character, non-preachy) so the bot reads like a person, not an
     *  assistant. The persona/voice itself is the admin's `systemPrompt`. */
    private const val BEHAVIOR =
        "Behaviour rules (follow strictly): reply in 1-2 short lines, casual, like a real " +
        "person texting in a group chat. Never say you are an AI, a bot, a model, or an " +
        "assistant. Never lecture, moralise, or announce that you are 'breaking character'. " +
        "If you won't do something, don't explain yourself — brush it off with a short quip " +
        "and move on. Vary how you open; don't repeat the same phrasing. No @everyone/role pings."

    sealed class Result {
        data class Ok(val text: String) : Result()
        data class Error(val message: String) : Result()
    }

    /** One conversation turn. [isBot] marks the bot's OWN past replies (role=assistant);
     *  everyone else (people AND other bots) is a name-prefixed user turn. */
    data class Turn(val isBot: Boolean, val name: String, val text: String)

    private fun endpoint(cfg: DiscordBotStore.Config, model: String): String =
        if (cfg.cfGatewayId.isNotBlank())
            "https://gateway.ai.cloudflare.com/v1/${cfg.cfAccountId}/${cfg.cfGatewayId}/workers-ai/$model"
        else
            "https://api.cloudflare.com/client/v4/accounts/${cfg.cfAccountId}/ai/run/$model"

    /** Generate a reply from the conversation [history] (chronological; last = the message
     *  being answered). System = behaviour rules + the admin's persona. */
    suspend fun reply(cfg: DiscordBotStore.Config, history: List<Turn>): Result {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system")
                .put("content", "$BEHAVIOR\n\n${cfg.systemPrompt}"))
        for (t in history) {
            if (t.text.isBlank()) continue
            messages.put(JSONObject()
                .put("role", if (t.isBot) "assistant" else "user")
                .put("content", if (t.isBot) t.text else "${t.name}: ${t.text}"))
        }
        return call(cfg, cfg.model, messages, 256)
    }

    /** Cheap 8B gate: should the bot jump into an UNADDRESSED conversation? Defaults to
     *  false (stay quiet) on any error — ambient chatter is optional, never worth spending on. */
    suspend fun shouldChimeIn(cfg: DiscordBotStore.Config, history: List<Turn>): Boolean {
        val transcript = history.takeLast(6).joinToString("\n") {
            if (it.isBot) "You: ${it.text}" else "${it.name}: ${it.text}"
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content",
                "You decide whether a chat regular should reply to the LAST message in this " +
                "snippet. Answer with ONLY 'yes' or 'no'. Say yes only if the last message is " +
                "interesting, a question, or clearly invites a response; say no for a private " +
                "back-and-forth between others, or when butting in would be annoying."))
            .put(JSONObject().put("role", "user").put("content", transcript))
        return when (val r = call(cfg, TRIAGE_MODEL, messages, 4)) {
            is Result.Ok -> r.text.trim().lowercase().startsWith("y")
            is Result.Error -> false
        }
    }

    private suspend fun call(
        cfg: DiscordBotStore.Config, model: String, messages: JSONArray, maxTokens: Int
    ): Result = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject()
                .put("messages", messages)
                .put("max_tokens", maxTokens)
                .toString()
                .toRequestBody(JSON)
            val req = Request.Builder()
                .url(endpoint(cfg, model))
                .addHeader("Authorization", "Bearer ${cfg.cfApiToken}")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.Error(
                        "Workers AI HTTP ${resp.code}: ${extractError(raw) ?: raw.take(180)}"
                    )
                }
                val text = extractResponse(raw)
                if (text.isNullOrBlank()) Result.Error("Empty AI response") else Result.Ok(text.trim())
            }
        } catch (e: Exception) {
            Result.Error("${e.javaClass.simpleName}: ${e.message ?: "network error"}")
        }
    }

    /** Workers AI returns `{"result":{"response":"..."},...}`; AI Gateway mirrors it. */
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
