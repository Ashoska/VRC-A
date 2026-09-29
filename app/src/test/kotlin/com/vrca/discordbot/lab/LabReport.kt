package com.vrca.discordbot.lab

import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds `report.md` (+ `prompts.md`, `state-final.json`) from everything the recorder captured. */
internal class LabReport(private val d: LabDriver) {
    private val rec get() = d.rec
    private val hms = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val metaRe = Regex("(?i)\\b(as an ai|language model|i'?m (an? )?(ai|bot|assistant)\\b|my (instructions|prompt|programming)|breaking character|system prompt|my role)")
    private val leakRe = Regex("%%MEM%%|^\\s*[\\[{]|\"action\"\\s*:|\\breact:\\s|\\[(Replying to|Names|Language|Emojis|Who you are)|^\\s*cardinal\\s*:", RegexOption.IGNORE_CASE)
    private val shortcodeRe = Regex(":[a-z0-9_]{2,32}:(?!\\d)")   // `<:name:id>` is rendered, not flagged
    private fun script(s: String): String = when {
        s.any { it in '぀'..'ヿ' } -> "ja"
        s.any { it in '가'..'힣' } -> "ko"
        s.any { it in '一'..'鿿' } -> "zh"
        s.any { it in 'Ѐ'..'ӿ' } -> "ru"
        s.any { it in '؀'..'ۿ' } -> "ar"
        else -> "latin"
    }

    fun write(): File {
        val calls = rec.calls.toList().sortedBy { it.n }
        val events = rec.events.toList()
        val humans = events.filter { it.type == "human" }
        val sends = events.filter { it.type == "bot_send" }
        val reacts = events.filter { it.type == "bot_react" }
        val traces = events.filter { it.type == "trace" }
        val sb = StringBuilder()
        val durS = (System.currentTimeMillis() - rec.startedMs) / 1000
        sb.append("# Cardinal Lab — ${d.cfg.runName}\n\n")
        sb.append("- AI mode: **${d.cfg.mode}**").append(if (!d.cfg.live) " (synthetic replies; token counts are ESTIMATES)" else "").append('\n')
        if (d.cfg.remap.isNotEmpty()) sb.append("- Model remaps: ${d.cfg.remap}\n")
        sb.append("- Scripts: ${d.cfg.scripts.joinToString { it.name }.ifBlank { "(interactive)" }}\n")
        sb.append("- Duration: ${durS}s · human messages: ${humans.size} (addressed ${humans.count { it.data.optBoolean("addressed") }})")
        sb.append(" · bot replies: ${sends.size} · reactions: ${reacts.size}\n")
        if (d.discord.unhandled.isNotEmpty()) sb.append("- ⚠ Unhandled Discord REST calls: ${d.discord.unhandled.distinct()}\n")
        if (d.ai.capHit) sb.append("- ⚠ LIVE neuron cap hit (${d.cfg.neuronCap.toInt()})\n")
        sb.append('\n')

        // ── Cost & speed by call kind ──
        sb.append("## Model calls\n\n| kind | model | calls | avg in | avg out | p50 ms | p95 ms | neurons | n/call |\n|---|---|---:|---:|---:|---:|---:|---:|---:|\n")
        calls.groupBy { it.kind to it.servedModel }.toSortedMap(compareBy({ it.first }, { it.second })).forEach { (k, list) ->
            val lat = list.map { it.latencyMs }.sorted()
            val n = list.sumOf { it.neurons }
            sb.append("| ${k.first} | ${k.second.substringAfterLast('/')} | ${list.size} | ${list.map { it.promptTokens }.average().toInt()} | ")
                .append("${list.map { it.completionTokens }.average().toInt()} | ${pct(lat, 50)} | ${pct(lat, 95)} | ")
                .append("${"%.1f".format(n)} | ${"%.1f".format(n / list.size)} |\n")
        }
        val totalN = calls.sumOf { it.neurons }
        sb.append("\n**Total ≈ ${"%.1f".format(totalN)} neurons** for ${humans.size} messages")
        if (humans.isNotEmpty()) {
            val per1k = totalN / humans.size * 1000
            sb.append(" → ≈ ${"%.0f".format(per1k)} neurons per 1,000 messages at this traffic mix (≈ $${"%.3f".format(Pricing.dollars(per1k))} beyond the free tier)")
        }
        if (sends.isNotEmpty()) sb.append(" · ≈ ${"%.1f".format(totalN / sends.size)} neurons per bot reply (all calls ÷ replies)")
        sb.append(".\n")
        val errs = calls.filter { it.error != null || it.status !in 200..299 }
        if (errs.isNotEmpty()) sb.append("\n⚠ ${errs.size} failed model call(s): ${errs.take(5).joinToString { "#${it.n} ${it.kind} ${it.servedModel.substringAfterLast('/')}: ${it.error ?: it.status}" }}\n")

        // ── Reply prompt anatomy ──
        val replies = calls.filter { it.kind == "reply" }
        if (replies.isNotEmpty()) {
            sb.append("\n## Reply prompt anatomy (avg tokens per section, estimated)\n\n| section | present in | avg tokens | share |\n|---|---:|---:|---:|\n")
            val agg = LinkedHashMap<String, MutableList<Int>>()
            var totalTok = 0.0
            replies.forEach { c ->
                sections(c.systemPrompt).forEach { (name, text) -> agg.getOrPut(name) { mutableListOf() }.add(TokenEstimate.of(text)) }
                val msgs = c.request.optJSONArray("messages")
                var tr = 0
                if (msgs != null) for (i in 1 until msgs.length()) tr += 4 + TokenEstimate.of(msgs.optJSONObject(i)?.optString("content").orEmpty())
                agg.getOrPut("(transcript turns)") { mutableListOf() }.add(tr)
                totalTok += TokenEstimate.ofMessages(msgs)
            }
            val avgTotal = totalTok / replies.size
            agg.forEach { (name, list) ->
                val avg = list.sum().toDouble() / replies.size
                sb.append("| $name | ${list.size}/${replies.size} | ${"%.0f".format(avg)} | ${"%.0f".format(avg / avgTotal * 100)}% |\n")
            }
            sb.append("\nAvg reply prompt ≈ ${"%.0f".format(avgTotal)} tokens (estimate). Static-rule sections that never change between calls are prefix-cache candidates.\n")
        }

        // ── Quality flags ──
        val flags = ArrayList<String>()
        val seen = HashMap<String, MutableList<String>>()
        sends.forEach { s ->
            val t = s.data.optString("text"); val ch = s.data.optString("channel")
            if (metaRe.containsMatchIn(t)) flags.add("bot-meta: \"$t\"")
            if (leakRe.containsMatchIn(t)) flags.add("format leak: \"$t\"")
            if (t.length > 240) flags.add("long (${t.length} chars): \"${t.take(80)}…\"")
            shortcodeRe.findAll(t).forEach { flags.add("unrendered shortcode ${it.value}: \"$t\"") }
            val norm = t.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), "").trim()
            val prev = seen.getOrPut(ch) { mutableListOf() }
            if (norm.isNotBlank() && prev.contains(norm)) flags.add("repeat in #$ch: \"$t\"")
            prev.add(norm)
            val human = humans.lastOrNull { it.t <= s.t && it.data.optString("channel") == ch }
            if (human != null) {
                val hs = script(human.data.optString("text")); val rs = script(t)
                if (hs != "latin" && rs == "latin" && !t.contains("dry reply")) flags.add("language: human wrote $hs, reply is latin: \"$t\"")
            }
        }
        sb.append("\n## Quality flags (heuristic)\n\n")
        if (flags.isEmpty()) sb.append("none\n") else flags.forEach { sb.append("- ").append(it).append('\n') }

        // ── Expectations ──
        if (rec.expectations.isNotEmpty()) {
            val pass = rec.expectations.count { it.optBoolean("pass") }
            sb.append("\n## Expectations: $pass/${rec.expectations.size} passed\n\n")
            rec.expectations.forEach { e ->
                sb.append(if (e.optBoolean("pass")) "- ✓ " else "- ✗ ").append(e.optString("spec"))
                if (!e.optBoolean("pass")) sb.append(" — got: ").append(e.optString("detail"))
                sb.append('\n')
            }
        }

        // ── Turn log ──
        sb.append("\n## Turns\n\n")
        humans.forEachIndexed { i, h ->
            val next = humans.getOrNull(i + 1)?.seq ?: Int.MAX_VALUE
            val window = events.filter { it.seq > h.seq && it.seq < next }
            val hd = h.data
            sb.append("**${hms.format(Date(h.t))} #${hd.optString("channel")} ${hd.optString("author")}**")
            if (hd.optBoolean("addressed")) sb.append(" (addressed)")
            if (hd.optBoolean("image")) sb.append(" [img]")
            sb.append(": ").append(hd.optString("text").take(200)).append("  \n")
            window.forEach { e ->
                val dd = e.data
                when (e.type) {
                    "bot_send" -> sb.append("  ↳ **Cardinal** (+${e.t - h.t}ms)").append(if (!dd.isNull("replyTo")) " ↩${dd.optString("replyToAuthor")}" else "")
                        .append(": ").append(dd.optString("text")).append("  \n")
                    "bot_react" -> sb.append("  ↳ react ").append(dd.optString("emoji")).append(" (+${e.t - h.t}ms)  \n")
                    "trace" -> sb.append("  · ").append(dd.optString("score")).append(" → ").append(dd.optString("action"))
                        .append(" (").append(dd.optString("plan")).append(") ").append(dd.optString("detail").take(80)).append("  \n")
                    "ai" -> sb.append("  · ai#").append(dd.optInt("n")).append(' ').append(dd.optString("kind")).append(' ')
                        .append(dd.optString("served").substringAfterLast('/')).append(" in=").append(dd.optInt("promptTokens"))
                        .append(" out=").append(dd.optInt("completionTokens")).append(" ").append(dd.optLong("latencyMs")).append("ms")
                        .append(if (!dd.isNull("error")) " ERR ${dd.optString("error").take(60)}" else "").append("  \n")
                    "expect" -> sb.append("  ").append(if (dd.optBoolean("pass")) "✓" else "✗").append(" expect ").append(dd.optString("spec")).append("  \n")
                    "note" -> sb.append("  · note: ").append(dd.optString("text")).append("  \n")
                }
            }
            if (window.none { it.type == "bot_send" || it.type == "bot_react" || it.type == "trace" }) sb.append("  · (no decision — free prefilter)  \n")
        }

        // ── Final state ──
        sb.append("\n## Final memory state\n\n```\n").append(d.renderState()).append("\n```\n")
        rec.checkpoints.forEach { (label, snap) -> sb.append("\n### Checkpoint: $label\n\n```\n").append(snap.optString("render")).append("\n```\n") }

        File(d.cfg.runDir, "state-final.json").writeText(d.dumpState().toString(2))
        writePrompts(calls)
        val f = File(d.cfg.runDir, "report.md")
        f.writeText(sb.toString())
        return f
    }

    /** Split a reply system prompt into its labelled sections. */
    fun sections(sys: String): List<Pair<String, String>> {
        val parts = sys.split(Regex("\\n\\n(?=\\[|Keep it to one short line|Reply with)"))
        return parts.mapIndexed { i, p ->
            val name = when {
                i == 0 -> "fixed core (identity + voice rules)"
                p.startsWith("[People here you know") -> "[People here you know] (cards)"
                p.startsWith("[") -> p.substring(0, p.indexOf(']').coerceAtLeast(1) + 1)
                p.startsWith("Keep it") -> "short hint"
                p.startsWith("Reply with") -> "output rule"
                else -> "other"
            }
            name to p
        }
    }

    private fun writePrompts(calls: List<AiCall>) {
        val sb = StringBuilder("# Every model call, verbatim\n")
        calls.forEach { c ->
            sb.append("\n---\n\n## #${c.n} ${c.kind} · ${c.servedModel}").append(if (c.servedModel != c.askedModel) " (asked ${c.askedModel})" else "")
                .append(" · in=${c.promptTokens} out=${c.completionTokens}${if (c.estimated) " (est)" else ""} · ${c.latencyMs}ms · ${"%.2f".format(c.neurons)} neurons · max_tokens=${c.request.optInt("max_tokens")}\n\n")
            val msgs = c.request.optJSONArray("messages")
            if (msgs != null) for (i in 0 until msgs.length()) {
                val m = msgs.optJSONObject(i) ?: continue
                sb.append("**").append(m.optString("role")).append("**\n```\n").append(m.optString("content")).append("\n```\n")
            }
            sb.append("**→ response**\n```\n").append(c.text.ifBlank { c.error ?: c.responseRaw.take(500) }).append("\n```\n")
        }
        File(d.cfg.runDir, "prompts.md").writeText(sb.toString())
    }

    private fun pct(sorted: List<Long>, p: Int): Long =
        if (sorted.isEmpty()) 0 else sorted[((sorted.size - 1) * p / 100.0).toInt()]

    @Suppress("unused")
    private fun JSONObject.str(k: String) = optString(k)
}
