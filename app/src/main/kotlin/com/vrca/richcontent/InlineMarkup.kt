package com.vrca.richcontent

import androidx.compose.ui.graphics.Color

/**
 * Inline markup for [RichBlock.Text] / [RichBlock.Callout] / bullet items:
 *  - `**bold**`, `*italic*`
 *  - `[c=#RRGGBB]colored[/c]` (also #RGB / #AARRGGBB) and the quick form `/c#hex=word`
 *  - bare `https://…` URLs are auto-linkified.
 *
 * Parsed into a flat list of [InlineRun]s. The renderer draws each run as its OWN
 * `Text` with **parameter-level** fontWeight / fontStyle / color — NOT `SpanStyle`.
 * Some devices (Samsung system fonts) silently ignore per-SPAN weight/style (only
 * color rendered), so parameter-level styling on a real font is the reliable path.
 */

data class InlineRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val color: Color? = null,
    val url: String? = null
)

private val URL_REGEX = Regex("""https?://[^\s\])]+""")

/** Parses a hex color ("#fff" / "#ffffff" / "#ffffffff"), or null if malformed. */
fun parseHexColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    val h = hex.trim().removePrefix("#")
    val clean = when (h.length) {
        3 -> h.map { "$it$it" }.joinToString("")
        6, 8 -> h
        else -> return null
    }
    return try {
        val argb = when (clean.length) {
            6 -> 0xFF000000L or clean.toLong(16)
            else -> clean.toLong(16)
        }
        Color(argb.toInt())
    } catch (_: Exception) {
        null
    }
}

/**
 * Scans [raw] into styled runs. Bold/italic toggle on `**`/`*`; `[c=#hex]…[/c]`
 * pushes/pops a color; `/c#hex=word` colors one word; bare URLs become link runs.
 */
fun buildInlineRuns(raw: String): List<InlineRun> {
    val runs = ArrayList<InlineRun>()
    var bold = false
    var italic = false
    val colorStack = ArrayDeque<Color>()
    val buf = StringBuilder()

    fun emit(text: String, color: Color?) {
        if (text.isEmpty()) return
        var last = 0
        for (m in URL_REGEX.findAll(text)) {
            if (m.range.first > last) runs += InlineRun(text.substring(last, m.range.first), bold, italic, color)
            val matchText = m.value
            val trimmed = matchText.trimEnd('.', ',', ')', ']', '!', '?', ';', ':')
            runs += InlineRun(trimmed, bold, italic, color, url = trimmed)
            if (trimmed.length < matchText.length) runs += InlineRun(matchText.substring(trimmed.length), bold, italic, color)
            last = m.range.last + 1
        }
        if (last < text.length) runs += InlineRun(text.substring(last), bold, italic, color)
    }

    fun flush() {
        if (buf.isEmpty()) return
        val text = buf.toString()
        buf.clear()
        emit(text, colorStack.lastOrNull())
    }

    var i = 0
    while (i < raw.length) {
        when {
            raw.startsWith("**", i) -> { flush(); bold = !bold; i += 2 }
            raw.startsWith("[c=", i) -> {
                val end = raw.indexOf(']', i)
                val color = if (end > i + 3) parseHexColor(raw.substring(i + 3, end)) else null
                if (color != null) { flush(); colorStack.addLast(color); i = end + 1 }
                else { buf.append(raw[i]); i++ }
            }
            raw.startsWith("[/c]", i) -> { flush(); if (colorStack.isNotEmpty()) colorStack.removeLast(); i += 4 }
            raw.startsWith("/c#", i) -> {
                // Quick single-word color: /c#RRGGBB=word (colors one word, no closing tag).
                val eq = raw.indexOf('=', i)
                val color = if (eq > i + 3) parseHexColor(raw.substring(i + 3, eq)) else null
                if (color != null) {
                    var end = eq + 1
                    while (end < raw.length && !raw[end].isWhitespace()) end++
                    flush()
                    runs += InlineRun(raw.substring(eq + 1, end), bold, italic, color)
                    i = end
                } else { buf.append(raw[i]); i++ }
            }
            raw[i] == '*' -> { flush(); italic = !italic; i += 1 }
            else -> { buf.append(raw[i]); i++ }
        }
    }
    flush()
    return runs
}
