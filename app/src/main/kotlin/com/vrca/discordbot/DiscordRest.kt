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
 * Thin Discord REST helper for the bot: send a channel message and fire the typing
 * indicator. Bot-token authenticated (`Authorization: Bot <token>`). Discord requires
 * a real User-Agent on the API.
 */
object DiscordRest {
    private const val API = "https://discord.com/api/v10"
    private const val UA = "DiscordBot (https://github.com/ashoska/vrc-a, 1.0)"
    private const val MAX_CONTENT = 2000  // Discord's hard message-length limit

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** Shows "Bot is typing…" for ~10s (or until the next message) so the AI latency
     *  reads as responsiveness. Best-effort; failures are swallowed. */
    suspend fun triggerTyping(token: String, channelId: String) = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$API/channels/$channelId/typing")
                .addHeader("Authorization", "Bot $token")
                .addHeader("User-Agent", UA)
                .post(ByteArray(0).toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { }
        } catch (_: Exception) { }
    }

    /**
     * Posts [content] to [channelId], optionally as a reply to [replyToMessageId].
     * `allowed_mentions.parse=[]` blocks the model's output from ever @-pinging
     * everyone/roles/users, and `replied_user=false` avoids pinging on a reply.
     * @return null on success, else an error string.
     */
    suspend fun sendMessage(
        token: String,
        channelId: String,
        content: String,
        replyToMessageId: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject()
                .put("content", content.take(MAX_CONTENT))
                .put("allowed_mentions", JSONObject()
                    .put("parse", JSONArray())
                    .put("replied_user", false))
            if (replyToMessageId != null) {
                payload.put("message_reference", JSONObject()
                    .put("message_id", replyToMessageId)
                    .put("fail_if_not_exists", false))
            }
            val req = Request.Builder()
                .url("$API/channels/$channelId/messages")
                .addHeader("Authorization", "Bot $token")
                .addHeader("User-Agent", UA)
                .post(payload.toString().toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) null
                else "send HTTP ${resp.code}: ${resp.body?.string()?.take(160).orEmpty()}"
            }
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message ?: "network error"}"
        }
    }
}
