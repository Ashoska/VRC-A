package com.vrca.discordbot.lab

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress

/**
 * Localhost control API for driving the running bot by hand (or by an agent) — no Discord, no
 * screenshots. Every response is plain text unless the path ends in `.json`.
 *
 *   POST /script        body = one or more script lines (see [LabScript]) → rendered outcome
 *   POST /say           JSON {as, text, channel?, replyTo?, image?, wait?} → rendered outcome
 *   GET  /state         what every store holds + would inject     (GET /state.json = exact dump)
 *   GET  /transcript?channel=general&n=40
 *   GET  /calls?last=10&kind=reply    (GET /calls.json?…&full=1 for full request/response bodies)
 *   GET  /prompt?n=<call#>            the exact prompt + response of one model call (default: last reply)
 *   GET  /events?n=60   recent raw events
 *   POST /config        JSON {ambient, cooldown, context, shadow, model, budget, spent} → restart bot
 *   POST /report        write report.md now      POST /quit   finish the run
 */
internal class LabControl(private val d: LabDriver, private val onQuit: () -> Unit) {
    private val server = MockWebServer()
    @Volatile var lastRequestMs: Long = System.currentTimeMillis()
    private val script = LabScript(d)

    fun start(port: Int): Int {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                lastRequestMs = System.currentTimeMillis()
                return try { route(request) } catch (e: Exception) {
                    send(500, "error: ${e.javaClass.simpleName}: ${e.message}\n")
                } finally { lastRequestMs = System.currentTimeMillis() }
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), port)
        return server.port
    }

    fun stop() { runCatching { server.shutdown() } }

    private fun route(req: RecordedRequest): MockResponse {
        val url = req.requestUrl ?: return send(400, "bad request\n")
        val path = url.encodedPath
        val q = url.queryParameterNames.associateWith { url.queryParameter(it).orEmpty() }
        val body = if (req.method == "POST") req.body.readUtf8() else ""
        return when (path) {
            "/", "/help" -> send(200, HELP)
            "/health" -> send(200, "ok ${d.cfg.mode}\n")
            "/script" -> send(200, synchronized(script) { script.run("interactive", body) } + "\n")
            "/say" -> {
                val o = JSONObject(body)
                val out = d.say(o.getString("as"), o.getString("text"), channel = o.optString("channel", d.currentChannel),
                    replyTo = o.optString("replyTo").ifBlank { null }, image = o.optBoolean("image"),
                    wait = if (o.has("wait")) o.optBoolean("wait") else true)
                send(200, d.renderOutcome(out) + "\n")
            }
            "/state" -> send(200, d.renderState() + "\n")
            "/state.json" -> send(200, d.dumpState().toString(2), json = true)
            "/transcript" -> send(200, d.transcript(q["channel"] ?: d.currentChannel, q["n"]?.toIntOrNull() ?: 40) + "\n")
            "/calls", "/calls.json" -> {
                val list = d.rec.calls.toList().filter { c -> q["kind"]?.let { it == c.kind } ?: true }
                    .takeLast(q["last"]?.toIntOrNull() ?: 10)
                if (path.endsWith(".json")) send(200, JSONArray(list.map { it.toJson(full = q["full"] == "1") }).toString(2), json = true)
                else send(200, list.joinToString("\n") { c ->
                    "#${c.n} ${c.kind} ${c.servedModel.substringAfterLast('/')} in=${c.promptTokens} out=${c.completionTokens}" +
                        "${if (c.estimated) "~" else ""} ${c.latencyMs}ms ${"%.2f".format(c.neurons)}n → ${c.text.replace('\n', ' ').take(100)}" +
                        (c.error?.let { "  ERR $it" } ?: "")
                } + "\n")
            }
            "/prompt" -> {
                val calls = d.rec.calls.toList()
                val c = q["n"]?.toIntOrNull()?.let { n -> calls.firstOrNull { it.n == n } }
                    ?: calls.lastOrNull { it.kind == (q["kind"] ?: "reply") }
                if (c == null) send(404, "no such call\n") else send(200, renderPrompt(c))
            }
            "/events" -> send(200, d.rec.events.toList().takeLast(q["n"]?.toIntOrNull() ?: 60).joinToString("\n") {
                "${it.seq} ${it.type} ${it.data}"
            } + "\n")
            "/config" -> { d.applyConfig(JSONObject(body.ifBlank { "{}" })); send(200, "config applied, bot restarted\n") }
            "/report" -> send(200, "wrote ${LabReport(d).write().path}\n")
            "/quit" -> { onQuit(); send(200, "bye — writing report\n") }
            else -> send(404, "unknown path $path\n$HELP")
        }
    }

    private fun renderPrompt(c: AiCall): String {
        val sb = StringBuilder("#${c.n} ${c.kind} · ${c.servedModel} · in=${c.promptTokens} out=${c.completionTokens}" +
            "${if (c.estimated) " (est)" else ""} · ${c.latencyMs}ms · ${"%.2f".format(c.neurons)} neurons · max_tokens=${c.request.optInt("max_tokens")}\n")
        val msgs = c.request.optJSONArray("messages")
        if (msgs != null) for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            sb.append("\n===== ").append(m.optString("role")).append(" =====\n").append(m.optString("content")).append('\n')
        }
        sb.append("\n===== response =====\n").append(c.text.ifBlank { c.error ?: c.responseRaw }).append('\n')
        return sb.toString()
    }

    private fun send(status: Int, text: String, json: Boolean = false): MockResponse =
        MockResponse().setResponseCode(status)
            .setHeader("Content-Type", if (json) "application/json; charset=utf-8" else "text/plain; charset=utf-8")
            .setBody(text)

    companion object {
        val HELP = """
            Cardinal Lab control API
              POST /script      body: script lines, e.g.  alice: hey @Cardinal how's it going
              POST /say         {"as":"alice","text":"hi @Cardinal","channel":"general","replyTo":"bot","wait":true}
              GET  /state  /state.json  /transcript?channel=general&n=40  /calls?last=10&kind=reply  /prompt?n=12  /events?n=60
              POST /config      {"ambient":50,"context":8,"shadow":false,"model":"...","spent":7000}
              POST /report      POST /quit
        """.trimIndent() + "\n"
    }
}
