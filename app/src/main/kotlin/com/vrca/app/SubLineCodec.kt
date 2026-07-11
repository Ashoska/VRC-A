package com.vrca.app

/**
 * A single chatbox ROW inside a Pinned block or a Cycle slide.
 *
 * The Pinned message and each Cycle line can now hold up to [SubLineCodec.MAX_SUB_LINES]
 * of these — each renders as its own line in the VRChat chatbox. A sub-line can be
 * [hidden] (kept in the editor + synced so an admin can see it, but NOT sent),
 * and their list order is their on-screen / in-chatbox order.
 */
data class ChatboxSubLine(val text: String, val hidden: Boolean = false)

/**
 * Encodes up to 3 sub-lines into the SAME string field that already syncs
 * (`afkMessage` for Pinned, each slide of `cycleLinesText` for Cycle), so the
 * hide/order/hidden-text state rides existing writes — ZERO new Firestore
 * fields, zero extra reads/writes — while the admin, decoding the same format,
 * sees every sub-line (hidden included) and can moderate them.
 *
 * Format (control chars users can never type — [sanitize] strips them from input):
 *   - A lone VISIBLE sub-line encodes as PLAIN text (no markers) → byte-for-byte
 *     backward compatible with older readers and the common single-line case.
 *   - Otherwise: SENTINEL + sub0 + SEP + sub1 + SEP + sub2, where each subN is
 *     (HIDDEN prefix if hidden) + text.
 *
 * The SENT output ([renderVisible]) is only the visible, non-blank rows joined by
 * real newlines — hidden rows cost nothing against VRChat's 144-char budget.
 *
 * Cycle note: slides are joined into `cycleLinesText` by '\n'. An encoded slide
 * contains SENTINEL/SEP/HIDDEN but NEVER '\n', so the slide-level '\n' split is
 * unaffected and cross-device / admin parsing of `cycleLinesText` still works.
 */
object SubLineCodec {
    const val MAX_SUB_LINES = 3

    private const val SENTINEL = '\u0001' // line starts with this => encoded multi/hidden form
    private const val SEP = '\u001E'      // between sub-lines
    private const val HIDDEN = '\u0002'   // prefixes a hidden sub-line's text
    private const val ROW_SEP = '\u2028'  // reserved; stripped from input so it can't leak

    /** Human-readable markers used ONLY in the admin text boxes (never on the
     *  public side, never in the chatbox). Non-typeable so a round-trip is safe. */
    const val ADMIN_ROW_GLYPH = '⏎'          // ⏎ separates rows of one slide/block
    const val ADMIN_HIDDEN_GLYPH = '⊘'       // ⊘ prefixes a hidden row
    val ADMIN_ROW_SEP = " $ADMIN_ROW_GLYPH "      // " ⏎ "

    /** Chars a sub-line's text may never contain (they would corrupt the encoding). */
    fun sanitize(text: String): String =
        text.replace('\n', ' ').replace('\r', ' ')
            .filter { it != SENTINEL && it != SEP && it != HIDDEN && it != ROW_SEP }

    /** Encode sub-lines into a single stored/synced string. */
    fun encode(subs: List<ChatboxSubLine>): String {
        val cleaned = subs.map { it.copy(text = sanitize(it.text)) }
        if (cleaned.isEmpty()) return ""
        if (cleaned.size == 1 && !cleaned[0].hidden) return cleaned[0].text // plain, back-compat
        return SENTINEL + cleaned.joinToString(SEP.toString()) {
            (if (it.hidden) HIDDEN.toString() else "") + it.text
        }
    }

    /** Decode a stored string into sub-lines. Legacy / plain text => one visible row.
     *  Always returns at least one element. */
    fun decode(stored: String): List<ChatboxSubLine> {
        if (stored.isEmpty()) return listOf(ChatboxSubLine("", false))
        if (stored[0] != SENTINEL) return listOf(ChatboxSubLine(stored, false))
        val body = stored.substring(1)
        return body.split(SEP).map { part ->
            val hidden = part.startsWith(HIDDEN)
            ChatboxSubLine(if (hidden) part.substring(1) else part, hidden)
        }.ifEmpty { listOf(ChatboxSubLine("", false)) }
    }

    /** The text actually sent to VRChat: visible, non-blank rows joined by newline. */
    fun renderVisible(stored: String): String =
        decode(stored).asSequence()
            .filter { !it.hidden && it.text.isNotBlank() }
            .map { it.text }
            .joinToString("\n")

    /** How many rows the rendered output occupies (for the 9-line budget). */
    fun visibleRowCount(stored: String): Int =
        decode(stored).count { !it.hidden && it.text.isNotBlank() }

    /** True if the stored value has any content that would send. */
    fun hasVisible(stored: String): Boolean = visibleRowCount(stored) > 0

    // ---- Admin readable round-trip (one stored string <-> one editable box) ----

    /** Decode a stored value to a readable, editable single string for the admin:
     *  rows joined by " ⏎ ", hidden rows prefixed with "⊘". */
    fun toAdminText(stored: String): String =
        decode(stored).joinToString(ADMIN_ROW_SEP) {
            (if (it.hidden) ADMIN_HIDDEN_GLYPH.toString() else "") + it.text
        }

    /** Re-encode an admin-edited readable string back into the stored format.
     *  Splits on the ⏎ glyph (ignoring surrounding spaces); a row starting with
     *  ⊘ is hidden. */
    fun fromAdminText(adminText: String): String {
        val subs = adminText.split(ADMIN_ROW_GLYPH).map { raw ->
            val t = raw.trim()
            val hidden = t.startsWith(ADMIN_HIDDEN_GLYPH)
            ChatboxSubLine(if (hidden) t.substring(1).trim() else t, hidden)
        }.take(MAX_SUB_LINES)
        return encode(subs)
    }

    /** Cycle variants: a `cycleLinesText` value is many slides joined by '\n'; each
     *  slide is decoded to its own admin-readable line (rows shown with " ⏎ ",
     *  hidden rows tagged "⊘"), and re-encoded slide-by-slide on save. Slides stay
     *  '\n'-delimited both ways (the row glyph is never a newline). */
    fun toAdminCycleText(cycleLinesText: String): String =
        cycleLinesText.split("\n").joinToString("\n") { toAdminText(it) }

    fun fromAdminCycleText(adminText: String): String =
        adminText.split("\n").joinToString("\n") { fromAdminText(it) }
}
