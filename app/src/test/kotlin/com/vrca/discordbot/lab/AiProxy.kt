package com.vrca.discordbot.lab

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import java.net.InetAddress
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stands where `api.cloudflare.com/.../ai/run/<model>` stands for the bot. Every call is recorded
 * in full (the exact prompt the bot built, the response, usage, latency, neurons). Two modes:
 *
 *  - DRY: answers instantly-ish with deterministic synthetic text/JSON — zero cost, still exercises
 *    all routing/memory plumbing and captures every prompt for offline analysis.
 *  - LIVE: forwards to real Workers AI (optionally through an AI Gateway with `cf-aig-metadata` so
 *    lab traffic is filterable), with optional model remaps for A/B tests and a HARD neuron cap —
 *    the lab shares the account's 10k/day free tier with the production bot.
 */
internal class AiProxy(private val cfg: LabConfig, private val rec: LabRecorder) {
    private val server = MockWebServer()
    private val callSeq = AtomicInteger(0)
    val inFlight = AtomicInteger(0)
    @Volatile var lastActivityMs: Long = System.currentTimeMillis()
    @Volatile var liveNeurons: Double = 0.0
    @Volatile var capHit: Boolean = false
    private val dryReplySeq = AtomicInteger(0)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS).build()
    }
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = try { handle(request) } catch (e: Exception) {
                rec.note("ai proxy error: ${e.javaClass.simpleName}: ${e.message}")
                respond(500, """{"success":false,"errors":[{"message":"lab proxy error"}]}""")
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    fun stop() { runCatching { server.shutdown() } }

    val base: String get() = "http://127.0.0.1:${server.port}"

    private fun handle(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty().substringBefore('?')
        val i = path.indexOf("/ai/run/")
        if (request.method != "POST" || i < 0) {
            rec.note("ai proxy: unexpected ${request.method} $path")
            return respond(404, """{"success":false,"errors":[{"message":"unknown lab route"}]}""")
        }
        val asked = URLDecoder.decode(path.substring(i + "/ai/run/".length), "UTF-8")
        val bodyText = request.body.readUtf8()
        val req = runCatching { JSONObject(bodyText) }.getOrElse { JSONObject().put("raw", bodyText) }
        val kind = classify(req)
        val served = cfg.remap[asked] ?: cfg.remap[kind]?.takeIf { it.startsWith("@") } ?: asked
        val call = AiCall(callSeq.incrementAndGet(), kind, asked, served, if (cfg.live) "live" else "dry",
            System.currentTimeMillis(), req)
        inFlight.incrementAndGet(); lastActivityMs = System.currentTimeMillis()
        rec.event("ai_start", JSONObject().put("n", call.n).put("kind", kind).put("model", served))
        try {
            val (status, body) = if (cfg.live) live(call, bodyText) else dry(call)
            call.status = status
            call.responseRaw = body
            if (call.text.isEmpty()) call.text = extractText(body)
            call.endMs = System.currentTimeMillis()
            rec.callFinished(call)
            return respond(status, body)
        } finally {
            inFlight.decrementAndGet(); lastActivityMs = System.currentTimeMillis()
        }
    }

    /** Which bot role built this request — recognised from the fixed system-prompt openers. */
    private fun classify(req: JSONObject): String {
        val sys = req.optJSONArray("messages")?.optJSONObject(0)?.optString("content").orEmpty()
        return when {
            // By shape, so rewording a prompt doesn't break the lab (old and new wordings both work).
            sys.contains("\"action\":\"reply|react|ignore\"") -> "director"
            sys.contains("\"notes\":[") || sys.contains("\"people\":[") -> "observe"
            sys.contains("Reply with") && sys.startsWith("You're Cardinal") -> "reply"
            else -> "other"
        }
    }

    // ── LIVE ─────────────────────────────────────────────────────────────

    private fun live(call: AiCall, bodyText: String): Pair<Int, String> {
        if (liveNeurons >= cfg.neuronCap) {
            capHit = true
            call.error = "lab neuron cap reached"
            rec.note("LIVE neuron cap (${cfg.neuronCap}) reached — refusing further model calls")
            return 429 to """{"success":false,"errors":[{"code":0,"message":"Cardinal Lab neuron cap reached (${cfg.neuronCap.toInt()})"}]}"""
        }
        val url = if (cfg.cfGateway.isNotBlank())
            "https://gateway.ai.cloudflare.com/v1/${cfg.cfAccount}/${cfg.cfGateway}/workers-ai/${call.servedModel}"
        else "https://api.cloudflare.com/client/v4/accounts/${cfg.cfAccount}/ai/run/${call.servedModel}"
        val rb = Request.Builder().url(url)
            .header("Content-Type", "application/json")
            .post(bodyText.toRequestBody(jsonType))
        // No token in the environment = the cloud environment's credential proxy injects it for
        // api.cloudflare.com (the secret never enters this process).
        if (cfg.cfToken.isNotBlank()) rb.header("Authorization", "Bearer ${cfg.cfToken}")
        if (cfg.cfGateway.isNotBlank()) {
            rb.header("cf-aig-metadata", JSONObject().put("lab", "cardinal").put("run", cfg.runName)
                .put("kind", call.kind).put("asked", call.askedModel.substringAfterLast('/')).toString())
            if (cfg.aigToken.isNotBlank()) rb.header("cf-aig-authorization", "Bearer ${cfg.aigToken}")
        }
        if (cfg.affinity) rb.header("x-session-affinity", "cardinal-lab-${cfg.runName}")
        return try {
            http.newCall(rb.build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                val usage = runCatching { JSONObject(raw).optJSONObject("result")?.optJSONObject("usage") }.getOrNull()
                if (usage != null) {
                    call.promptTokens = usage.optInt("prompt_tokens")
                    call.completionTokens = usage.optInt("completion_tokens")
                    call.cachedTokens = usage.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens")
                        ?: usage.optInt("cached_tokens", usage.optInt("prompt_cache_hit_tokens", 0))
                } else {
                    call.estimated = true
                    call.promptTokens = TokenEstimate.ofMessages(call.request.optJSONArray("messages"))
                    call.completionTokens = TokenEstimate.of(extractText(raw))
                }
                // Workers AI reports the exact neurons it billed; the price table is only a fallback.
                val billed = usage?.optDouble("neurons", Double.NaN) ?: Double.NaN
                call.neurons = if (!billed.isNaN() && billed > 0.0) billed
                    else Pricing.neurons(call.servedModel, call.promptTokens, call.completionTokens, call.cachedTokens)
                runCatching { JSONObject(raw).optJSONObject("result")?.optString("model") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }?.let { call.backendModel = it }
                synchronized(this) { liveNeurons += call.neurons }
                if (!resp.isSuccessful) call.error = "HTTP ${resp.code}: ${raw.take(200)}"
                resp.code to raw
            }
        } catch (e: Exception) {
            call.error = "${e.javaClass.simpleName}: ${e.message}"
            502 to """{"success":false,"errors":[{"message":"lab upstream error: ${e.javaClass.simpleName}"}]}"""
        }
    }

    // ── DRY ──────────────────────────────────────────────────────────────

    private fun dry(call: AiCall): Pair<Int, String> {
        if (cfg.dryLatencyMs > 0) Thread.sleep(cfg.dryLatencyMs)
        val sys = call.systemPrompt
        val text = when (call.kind) {
            "reply" -> {
                val who = (Regex("\\[Replying to] You're answering (.+?)\\.").find(sys)
                    ?: Regex("\\[(?:You're answering|Answering)] ([^\\s(.—]+)").find(sys))?.groupValues?.get(1) ?: "them"
                "dry reply ${dryReplySeq.incrementAndGet()} to $who"
            }
            "director" -> when (cfg.dryDirector) {
                "react" -> """{"action":"react","short":true,"emoji":"😂"}"""
                "ignore" -> """{"action":"ignore","short":true,"emoji":""}"""
                "mix" -> listOf(
                    """{"action":"reply","short":true,"emoji":""}""",
                    """{"action":"react","short":true,"emoji":"💀"}""",
                    """{"action":"ignore","short":true,"emoji":""}""",
                )[call.n % 3]
                else -> """{"action":"reply","short":true,"emoji":""}"""
            }
            "observe" -> """{"sum":"(dry) the chat continues","notes":[],"me":[],"mood":""}"""
            else -> "(dry)"
        }
        call.estimated = true
        call.promptTokens = TokenEstimate.ofMessages(call.request.optJSONArray("messages"))
        call.completionTokens = TokenEstimate.of(text) + 1
        call.neurons = Pricing.neurons(call.servedModel, call.promptTokens, call.completionTokens)
        call.text = text
        val body = JSONObject().put("result", JSONObject().put("response", text).put("usage", JSONObject()
            .put("prompt_tokens", call.promptTokens).put("completion_tokens", call.completionTokens)
            .put("total_tokens", call.promptTokens + call.completionTokens)))
            .put("success", true).put("errors", org.json.JSONArray()).put("messages", org.json.JSONArray())
        return 200 to body.toString()
    }

    private fun extractText(raw: String): String = runCatching {
        val r = JSONObject(raw).opt("result")
        when (r) {
            is JSONObject -> r.optString("response").ifBlank {
                r.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
            }
            is String -> r
            else -> ""
        }
    }.getOrDefault("")

    private fun respond(status: Int, body: String): MockResponse =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
}
