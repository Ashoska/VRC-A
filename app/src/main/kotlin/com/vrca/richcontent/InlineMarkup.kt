package com.vrca.richcontent

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/**
 * Inline markup for [RichBlock.Text] / [RichBlock.Callout] / bullet items.
 *
 * Supported markers (chosen to extend the app's existing text conventions without
 * a heavy WYSIWYG editor):
 *  - `**bold**`
 *  - `*italic*`
 *  - `[c=#RRGGBB]colored[/c]`  (also #RGB and #AARRGGBB)
 *  - bare `https://…` URLs are auto-linkified into clickable, underlined,
 *    primary-tinted spans (tag = "URL"); the renderer opens them via UriHandler.
 *
 * No button/link block — a raw URL in the text just becomes tappable.
 */

private val URL_REGEX = Regex("""https?://[^\s\])]+""")
private const val URL_TAG = "URL"

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
 * Builds the styled + link-annotated string for [raw]. Pure (takes colors as
 * params) so it can be `remember`ed and unit-tested. URL spans carry a [URL_TAG]
 * string annotation the renderer looks up on tap.
 */
fun buildInlineAnnotated(raw: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var bold = false
    var italic = false
    val colorStack = ArrayDeque<Color>()
    val buf = StringBuilder()

    fun styleNow(): SpanStyle = SpanStyle(
        fontWeight = if (bold) FontWeight.Bold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        color = colorStack.lastOrNull() ?: Color.Unspecified
    )

    fun flush() {
        if (buf.isEmpty()) return
        val text = buf.toString()
        buf.clear()
        val base = styleNow()
        var last = 0
        for (m in URL_REGEX.findAll(text)) {
            if (m.range.first > last) withStyle(base) { append(text.substring(last, m.range.first)) }
            val matchText = m.value
            val trimmed = matchText.trimEnd('.', ',', ')', ']', '!', '?', ';', ':')
            pushStringAnnotation(URL_TAG, trimmed)
            withStyle(base.copy(color = linkColor, textDecoration = TextDecoration.Underline)) {
                append(trimmed)
            }
            pop()
            if (trimmed.length < matchText.length) {
                withStyle(base) { append(matchText.substring(trimmed.length)) }
            }
            last = m.range.last + 1
        }
        if (last < text.length) withStyle(base) { append(text.substring(last)) }
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
                // Quick single-word color: /c#RRGGBB=word  (colors one word, no closing tag)
                val eq = raw.indexOf('=', i)
                val color = if (eq > i + 3) parseHexColor(raw.substring(i + 3, eq)) else null
                if (color != null) {
                    var end = eq + 1
                    while (end < raw.length && !raw[end].isWhitespace()) end++
                    val word = raw.substring(eq + 1, end)
                    flush()
                    withStyle(
                        SpanStyle(
                            fontWeight = if (bold) FontWeight.Bold else null,
                            fontStyle = if (italic) FontStyle.Italic else null,
                            color = color
                        )
                    ) { append(word) }
                    i = end
                } else { buf.append(raw[i]); i++ }
            }
            raw[i] == '*' -> { flush(); italic = !italic; i += 1 }
            else -> { buf.append(raw[i]); i++ }
        }
    }
    flush()
}

/** Returns the URL at [offset] within [annotated], if a link span sits there. */
fun AnnotatedString.urlAt(offset: Int): String? =
    getStringAnnotations(URL_TAG, offset, offset).firstOrNull()?.item
