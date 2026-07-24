package com.vrca.richcontent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared rich-content document model powering the Update popup, Settings "What's
 * New", and the in-app Announcements list (Phase 1 of the Rich Content Revamp).
 *
 * A [RichDoc] is just an ordered list of [RichBlock]s rendered top-to-bottom as a
 * responsive vertical stack (never a freeform/absolute canvas — that breaks across
 * screen sizes). The whole doc serialises to a compact JSON string stored in ONE
 * Firestore field (`bodyDoc`) on announcement / release docs, so it costs a single
 * doc read; media (images/videos) live on GitHub/jsDelivr, never in Firestore.
 *
 * BACK-COMPAT: any doc without a `bodyDoc` falls back to [legacyDoc], which parses
 * the existing plain `text`/`notes` string (numbered headers, `*`/`-` bullets,
 * blank-line paragraphs — the same shape [parseTosBlocks] handled) so old
 * announcements/releases still render with their original look. Use
 * [resolveRichDoc] at every render site: prefer `bodyDoc`, fall back to legacy.
 */
const val RICH_DOC_VERSION = 1

sealed class RichBlock {
    data class Heading(val text: String, val color: String? = null) : RichBlock()
    data class Text(val text: String) : RichBlock()
    data class Bullets(val items: List<String>) : RichBlock()
    data class Image(val url: String) : RichBlock()
    data class Video(val url: String, val poster: String? = null) : RichBlock()
    /** [tone] is one of "info" | "warn" | "success". */
    data class Callout(val tone: String, val text: String) : RichBlock()
    object Divider : RichBlock()
}

data class RichDoc(
    val v: Int = RICH_DOC_VERSION,
    val blocks: List<RichBlock>
) {
    val isEmpty: Boolean get() = blocks.isEmpty()

    /** Every media URL this doc references (image + video + poster), in order. */
    fun mediaUrls(): List<String> {
        val out = ArrayList<String>()
        for (b in blocks) when (b) {
            is RichBlock.Image -> b.url.takeIf { it.isNotBlank() }?.let { out += it }
            is RichBlock.Video -> {
                b.url.takeIf { it.isNotBlank() }?.let { out += it }
                b.poster?.takeIf { it.isNotBlank() }?.let { out += it }
            }
            else -> {}
        }
        return out
    }

    /** Plain-text flattening for legacy fields / collapsed-card previews. */
    fun toPlainText(): String {
        val sb = StringBuilder()
        for (b in blocks) {
            val line = when (b) {
                is RichBlock.Heading -> b.text
                is RichBlock.Text -> stripInlineMarkup(b.text)
                is RichBlock.Bullets -> b.items.joinToString("\n") { "• ${stripInlineMarkup(it)}" }
                is RichBlock.Callout -> stripInlineMarkup(b.text)
                is RichBlock.Image, is RichBlock.Video, RichBlock.Divider -> ""
            }
            if (line.isNotBlank()) { if (sb.isNotEmpty()) sb.append('\n'); sb.append(line) }
        }
        return sb.toString()
    }

    fun toJson(): String {
        val arr = JSONArray()
        for (b in blocks) {
            val o = JSONObject()
            when (b) {
                is RichBlock.Heading -> {
                    o.put("t", "heading"); o.put("text", b.text)
                    b.color?.takeIf { it.isNotBlank() }?.let { o.put("color", it) }
                }
                is RichBlock.Text -> { o.put("t", "text"); o.put("text", b.text) }
                is RichBlock.Bullets -> { o.put("t", "bullets"); o.put("items", JSONArray(b.items)) }
                is RichBlock.Image -> { o.put("t", "image"); o.put("url", b.url) }
                is RichBlock.Video -> {
                    o.put("t", "video"); o.put("url", b.url)
                    b.poster?.takeIf { it.isNotBlank() }?.let { o.put("poster", it) }
                }
                is RichBlock.Callout -> { o.put("t", "callout"); o.put("tone", b.tone); o.put("text", b.text) }
                RichBlock.Divider -> o.put("t", "divider")
            }
            arr.put(o)
        }
        return JSONObject().put("v", v).put("blocks", arr).toString()
    }
}

/** Parses a stored `bodyDoc` JSON string. Returns null on blank/garbage/empty. */
fun parseRichDoc(json: String?): RichDoc? {
    if (json.isNullOrBlank()) return null
    return try {
        val root = JSONObject(json)
        val arr = root.optJSONArray("blocks") ?: return null
        val v = root.optInt("v", RICH_DOC_VERSION)
        val blocks = ArrayList<RichBlock>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            when (o.optString("t")) {
                "heading" -> blocks += RichBlock.Heading(
                    o.optString("text"),
                    o.optString("color").ifBlank { null }
                )
                "text" -> blocks += RichBlock.Text(o.optString("text"))
                "bullets" -> {
                    val itemsArr = o.optJSONArray("items")
                    val items = ArrayList<String>()
                    if (itemsArr != null) for (j in 0 until itemsArr.length()) {
                        itemsArr.optString(j).takeIf { it.isNotBlank() }?.let { items += it }
                    }
                    if (items.isNotEmpty()) blocks += RichBlock.Bullets(items)
                }
                "image" -> o.optString("url").takeIf { it.isNotBlank() }?.let { blocks += RichBlock.Image(it) }
                "video" -> o.optString("url").takeIf { it.isNotBlank() }?.let {
                    blocks += RichBlock.Video(it, o.optString("poster").ifBlank { null })
                }
                "callout" -> blocks += RichBlock.Callout(
                    o.optString("tone").ifBlank { "info" },
                    o.optString("text")
                )
                "divider" -> blocks += RichBlock.Divider
                else -> {} // unknown block type — skip (forward-compat with newer admin builds)
            }
        }
        if (blocks.isEmpty()) null else RichDoc(v, blocks)
    } catch (_: Throwable) {
        null
    }
}

/** Collapses literal markdown links "[label](url)" down to just the URL, once. */
private fun collapseMarkdownLinks(line: String): String =
    Regex("""\[([^\]]*)]\(([^)]*)\)""").replace(line) { m ->
        m.groupValues[2].ifBlank { m.groupValues[1] }
    }

/**
 * Back-compat: turns a legacy plain `notes`/`text` string into blocks using the
 * same rules the old [parseTosBlocks] used — numbered section headers
 * ("3. Privacy"), `*`/`-` bullets, blank-line paragraphs — so existing Firestore
 * content renders with its original structure. Returns null for blank input.
 */
fun legacyDoc(text: String?): RichDoc? {
    val raw = text?.trim().orEmpty()
    if (raw.isBlank()) return null
    val blocks = ArrayList<RichBlock>()
    val para = StringBuilder()
    val bullets = ArrayList<String>()
    fun flushPara() {
        val p = para.toString().trim()
        if (p.isNotBlank()) blocks += RichBlock.Text(p)
        para.clear()
    }
    fun flushBullets() {
        if (bullets.isNotEmpty()) { blocks += RichBlock.Bullets(bullets.toList()); bullets.clear() }
    }
    raw.lines().forEach { rawLine ->
        val line = collapseMarkdownLinks(rawLine.trim())
        when {
            line.isBlank() -> { flushBullets(); flushPara() }
            Regex("""^\d+\.\s+\S""").containsMatchIn(line) -> {
                flushBullets(); flushPara(); blocks += RichBlock.Heading(line)
            }
            line.startsWith("* ") || line.startsWith("- ") -> {
                flushPara(); bullets += line.drop(2).trim()
            }
            else -> {
                flushBullets()
                if (para.isNotEmpty()) para.append(' ')
                para.append(line)
            }
        }
    }
    flushBullets(); flushPara()
    return if (blocks.isEmpty()) null else RichDoc(blocks = blocks)
}

/** The renderable doc for a surface: prefer `bodyDoc` JSON, else the legacy text. */
fun resolveRichDoc(bodyDoc: String?, legacyText: String?): RichDoc? =
    parseRichDoc(bodyDoc) ?: legacyDoc(legacyText)

/** Strips inline markup markers (`**`, `*`, `[c=#hex]…[/c]`) for a clean preview. */
fun stripInlineMarkup(s: String): String =
    s.replace(Regex("""\[/?c(=#[0-9a-fA-F]{3,8})?]"""), "")
        .replace("**", "")
        .replace("*", "")
