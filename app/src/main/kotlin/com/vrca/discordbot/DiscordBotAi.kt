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
 * Calls Cloudflare **Workers AI** for a chat reply.
 *
 * When [DiscordBotStore.Config.cfGatewayId] is set the request is routed through
 * **AI Gateway** (`gateway.ai.cloudflare.com/v1/{account}/{gateway}/workers-ai/{model}`),
 * which adds observability, retries and provider-fallback — with the same bearer token.
 * Otherwise it hits the direct Workers-AI endpoint
 * (`api.cloudflare.com/client/v4/accounts/{account}/ai/run/{model}`). Either way the
 * `@cf/...` model id goes into the path literally (Cloudflare expects it unencoded).
 *
 * v1 is single-turn: system prompt + the one user message. Conversation memory /
 * per-user profiles are a deliberate later increment (stored server-side in a Worker).
 */
object DiscordBotAi {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)   // 70B generation can take a few seconds
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
    private val JSON = "application/json; charset=utf-8".toMediaType()

    sealed class Result {
        data class Ok(val text: String) : Result()
        data class Error(val message: String) : Result()
    }

    /** One conversation turn fed to the model. [isBot] marks the bot's OWN past
     *  replies (role=assistant); everyone else is a user turn prefixed with their name. */
    data class Turn(val isBot: Boolean, val name: String, val text: String)

    private fun endpoint(cfg: DiscordBotStore.Config): String =
        if (cfg.cfGatewayId.isNotBlank())
            "https://gateway.ai.cloudflare.com/v1/${cfg.cfAccountId}/${cfg.cfGatewayId}/workers-ai/${cfg.model}"
        else
            "https://api.cloudflare.com/client/v4/accounts/${cfg.cfAccountId}/ai/run/${cfg.model}"

    /**
     * @param history recent conversation turns in chronological order; the last one is
     *   the message being answered. User turns are name-prefixed so the model can tell
     *   speakers apart; the bot's own turns are role=assistant (its memory).
     */
    suspend fun reply(cfg: DiscordBotStore.Config, history: List<Turn>): Result =
        withContext(Dispatchers.IO) {
            try {
                val messages = JSONArray()
                    .put(JSONObject().put("role", "system").put("content", cfg.systemPrompt))
                for (t in history) {
                    if (t.text.isBlank()) continue
                    messages.put(JSONObject()
                        .put("role", if (t.isBot) "assistant" else "user")
                        .put("content", if (t.isBot) t.text else "${t.name}: ${t.text}"))
                }
                val body = JSONObject()
                    .put("messages", messages)
                    .put("max_tokens", 512)
                    .toString()
                    .toRequestBody(JSON)

                val req = Request.Builder()
                    .url(endpoint(cfg))
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
                    if (text.isNullOrBlank()) Result.Error("Empty AI response")
                    else Result.Ok(text.trim())
                }
            } catch (e: Exception) {
                Result.Error("${e.javaClass.simpleName}: ${e.message ?: "network error"}")
            }
        }

    /** Workers AI returns `{"result":{"response":"..."},...}`; AI Gateway mirrors it. */
    private fun extractResponse(raw: String): String? = try {
        val root = JSONObject(raw)
        val result = root.opt("result")
        when (result) {
            is JSONObject -> result.optString("response").ifBlank {
                // some models return {"result":{"choices":[{"message":{"content":...}}]}}
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
