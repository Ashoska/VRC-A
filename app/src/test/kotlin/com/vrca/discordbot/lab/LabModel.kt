package com.vrca.discordbot.lab

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

/**
 * Workers AI neuron prices (neurons per 1M tokens), from developers.cloudflare.com/workers-ai/platform/pricing
 * as of 2026-09. 1,000 neurons = $0.011 beyond the 10,000/day free allocation. `cachedIn` is the
 * discounted rate for prefix-cached input tokens — only models that list one get the discount.
 */
internal object Pricing {
    data class Rate(val input: Double, val output: Double, val cachedIn: Double? = null)

    private val rates = mapOf(
        "@cf/meta/llama-3.3-70b-instruct-fp8-fast" to Rate(26668.0, 204805.0),
        "@cf/meta/llama-3.1-70b-instruct-fp8-fast" to Rate(26668.0, 204805.0),
        "@cf/meta/llama-3.1-8b-instruct" to Rate(25608.0, 75147.0),
        "@cf/meta/llama-3.1-8b-instruct-fp8" to Rate(13778.0, 26128.0),
        "@cf/meta/llama-3.1-8b-instruct-fp8-fast" to Rate(4119.0, 34868.0),
        "@cf/meta/llama-3.1-8b-instruct-awq" to Rate(11161.0, 24215.0),
        "@cf/meta/llama-3.2-1b-instruct" to Rate(2457.0, 18252.0),
        "@cf/meta/llama-3.2-3b-instruct" to Rate(4625.0, 30475.0),
        "@cf/meta/llama-4-scout-17b-16e-instruct" to Rate(24545.0, 77273.0),
        "@cf/mistralai/mistral-small-3.1-24b-instruct" to Rate(31876.0, 50488.0),
        "@cf/google/gemma-3-12b-it" to Rate(31371.0, 50560.0),
        "@cf/google/gemma-4-26b-a4b-it" to Rate(9091.0, 27273.0),
        "@cf/qwen/qwen3-30b-a3b-fp8" to Rate(4625.0, 30475.0),
        "@cf/openai/gpt-oss-20b" to Rate(18182.0, 27273.0),
        "@cf/openai/gpt-oss-120b" to Rate(31818.0, 68182.0),
        "@cf/ibm-granite/granite-4.0-h-micro" to Rate(1542.0, 10158.0),
        "@cf/zai-org/glm-4.7-flash" to Rate(5500.0, 36400.0),
        "@cf/zai-org/glm-5.3-flash" to Rate(13636.0, 45455.0, 2727.0),
        "@cf/deepseek-ai/deepseek-v4-flash-0731" to Rate(40000.0, 120000.0, 1273.0),
        "@cf/moonshotai/kimi-k2.5" to Rate(54545.0, 272727.0, 9091.0),
    )

    fun rate(model: String): Rate? = rates[model]

    fun neurons(model: String, prompt: Int, completion: Int, cached: Int = 0): Double {
        val r = rates[model] ?: return 0.0
        val cachedUsed = if (r.cachedIn != null) cached.coerceIn(0, prompt) else 0
        return ((prompt - cachedUsed) * r.input + cachedUsed * (r.cachedIn ?: 0.0) + completion * r.output) / 1_000_000.0
    }

    /** Dollars for neurons ABOVE the daily free tier (so the marginal cost of a call). */
    fun dollars(neurons: Double): Double = neurons / 1000.0 * 0.011
}

/**
 * Rough token estimate for DRY runs (Llama-3 tokenizer ≈ 3.8 chars/token for English chat text;
 * CJK ≈ 1 token/char) + ~4 tokens of chat-template overhead per message. LIVE runs use the real
 * `usage` Cloudflare returns; the report marks which numbers are estimates.
 */
internal object TokenEstimate {
    fun of(text: String): Int {
        var ascii = 0; var wide = 0
        for (ch in text) if (ch.code < 0x2E80) ascii++ else wide++
        return ceil(ascii / 3.8 + wide * 1.0).toInt()
    }

    fun ofMessages(messages: JSONArray?): Int {
        if (messages == null) return 0
        var total = 3
        for (i in 0 until messages.length()) total += 4 + of(messages.optJSONObject(i)?.optString("content").orEmpty())
        return total
    }
}

/** One recorded Workers AI call (request + response + usage + cost), dry or live. */
internal class AiCall(
    val n: Int,
    val kind: String,
    val askedModel: String,
    val servedModel: String,
    val source: String,
    val startMs: Long,
    val request: JSONObject,
) {
    @Volatile var endMs: Long = 0
    @Volatile var status: Int = 0
    @Volatile var responseRaw: String = ""
    @Volatile var text: String = ""
    @Volatile var promptTokens: Int = 0
    @Volatile var completionTokens: Int = 0
    @Volatile var cachedTokens: Int = 0
    @Volatile var estimated: Boolean = false
    @Volatile var neurons: Double = 0.0
    @Volatile var error: String? = null

    val latencyMs: Long get() = if (endMs > 0) endMs - startMs else -1

    val systemPrompt: String
        get() = request.optJSONArray("messages")?.optJSONObject(0)
            ?.takeIf { it.optString("role") == "system" }?.optString("content").orEmpty()

    fun toJson(full: Boolean = true): JSONObject = JSONObject()
        .put("n", n).put("kind", kind).put("model", askedModel).put("served", servedModel)
        .put("source", source).put("startMs", startMs).put("latencyMs", latencyMs).put("status", status)
        .put("promptTokens", promptTokens).put("completionTokens", completionTokens)
        .put("cachedTokens", cachedTokens).put("estimated", estimated)
        .put("neurons", Math.round(neurons * 100) / 100.0)
        .put("maxTokens", request.optInt("max_tokens"))
        .put("text", text).put("error", error ?: JSONObject.NULL)
        .apply { if (full) { put("request", request); put("response", responseRaw) } }
}

/**
 * The run's single source of truth: every human message, bot action, routing trace, activity-log
 * line and AI call, in order, streamed to `events.jsonl` / `calls.jsonl` as they happen (so a
 * crashed or killed run still leaves its evidence behind).
 */
internal class LabRecorder(val cfg: LabConfig) {
    data class Event(val seq: Int, val t: Long, val type: String, val data: JSONObject)

    private val seqGen = AtomicInteger(0)
    val events = CopyOnWriteArrayList<Event>()
    val calls = CopyOnWriteArrayList<AiCall>()
    val checkpoints = CopyOnWriteArrayList<Pair<String, JSONObject>>()
    val expectations = CopyOnWriteArrayList<JSONObject>()
    @Volatile var lastEventMs: Long = System.currentTimeMillis()
    val startedMs: Long = System.currentTimeMillis()

    private val eventsOut: BufferedWriter = File(cfg.runDir, "events.jsonl").bufferedWriter()
    private val callsOut: BufferedWriter = File(cfg.runDir, "calls.jsonl").bufferedWriter()

    fun event(type: String, data: JSONObject = JSONObject()): Event {
        val now = System.currentTimeMillis()
        val e = Event(seqGen.incrementAndGet(), now, type, data)
        events.add(e)
        lastEventMs = now
        synchronized(eventsOut) {
            eventsOut.write(JSONObject().put("seq", e.seq).put("t", e.t).put("type", type).put("data", data).toString())
            eventsOut.newLine(); eventsOut.flush()
        }
        return e
    }

    fun note(text: String) { event("note", JSONObject().put("text", text)); println("[lab] $text") }

    fun callFinished(c: AiCall) {
        calls.add(c)
        synchronized(callsOut) { callsOut.write(c.toJson(full = true).toString()); callsOut.newLine(); callsOut.flush() }
        event("ai", c.toJson(full = false))
    }

    fun eventsSince(ms: Long): List<Event> = events.filter { it.t >= ms }

    fun close() {
        runCatching { synchronized(eventsOut) { eventsOut.close() } }
        runCatching { synchronized(callsOut) { callsOut.close() } }
    }
}
