package com.vrca.discordbot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What happened on each day, server-wide, so "what happened yesterday?" / "anything funny today?" /
 * "what did I miss" can be answered.
 *
 * Fed for free by the learn pass, which already reads EVERY message (replied to or not): each pass
 * adds its one-line "what's going on" summary and the notable/funny moments it saw. Once a day with
 * enough entries is over, one cheap 8B call condenses it into a short digest ([needsDigest] →
 * [setDigest]); small days are read as-is. Days are UTC (+0) dates; times are shown in UTC.
 *
 * Plain SharedPreferences (`vrca_discord_days`), one JSON object per `d_<yyyy-MM-dd>`. Raw entries
 * are dropped once a day is older than [DiscordBotLimits.DAY_RAW_KEEP_DAYS] and has a digest; whole
 * days older than [DiscordBotLimits.DAY_KEEP_DAYS] are removed.
 */
object DayLogStore {
    private const val PREFS = "vrca_discord_days"
    private const val PREFIX = "d_"

    /** [moment] = a funny/notable thing (vs the running what-we-talked-about summary). */
    data class Entry(val atMs: Long, val channel: String, val text: String, val moment: Boolean)
    data class Day(val date: LocalDate, val entries: List<Entry>, val digest: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    // Days are UTC (+0) so a "day" is the same for everyone in the server, whatever the phone's zone.
    private val zone: ZoneId = java.time.ZoneOffset.UTC
    fun dateOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    private fun key(d: LocalDate) = PREFIX + d.toString()

    @Synchronized
    fun load(ctx: Context, d: LocalDate): Day? {
        val raw = prefs(ctx).getString(key(d), null) ?: return null
        return try {
            val o = JSONObject(raw)
            val a = o.optJSONArray("e") ?: JSONArray()
            val es = (0 until a.length()).mapNotNull { i ->
                val e = a.optJSONObject(i) ?: return@mapNotNull null
                val t = e.optString("t").trim(); if (t.isBlank()) return@mapNotNull null
                Entry(e.optLong("at"), e.optString("c"), t, e.optBoolean("m"))
            }
            Day(d, es, o.optString("dg").trim())
        } catch (_: Exception) { null }
    }

    private fun save(ctx: Context, day: Day) {
        val a = JSONArray()
        day.entries.forEach { a.put(JSONObject().put("at", it.atMs).put("c", it.channel).put("t", it.text).put("m", it.moment)) }
        prefs(ctx).edit().putString(key(day.date), JSONObject().put("e", a).put("dg", day.digest).toString()).apply()
    }

    private fun words(s: String): Set<String> =
        Regex("[\\p{L}\\p{N}]+").findAll(s.lowercase()).map { discordStem(it.value) }.filter { it.length >= 3 }.toSet()
    private fun similar(a: String, b: String): Boolean {
        val wa = words(a); val wb = words(b)
        if (wa.isEmpty() || wb.isEmpty()) return a.equals(b, true)
        return wa.count { it in wb }.toDouble() / (wa + wb).size >= 0.6
    }

    /**
     * One learn pass's worth: the running [summary] (skipped when it just restates the channel's last
     * one) and up to a couple of [moments]. Cheap, sync.
     */
    @Synchronized
    fun record(ctx: Context, channel: String, nowMs: Long, summary: String, moments: List<String>) {
        val d = dateOf(nowMs)
        val cur = load(ctx, d) ?: Day(d, emptyList(), "")
        val add = ArrayList<Entry>()
        val s = summary.trim().take(200)
        val lastSummary = cur.entries.lastOrNull { !it.moment && it.channel == channel }
        if (s.isNotBlank() && (lastSummary == null || !similar(lastSummary.text, s))) add.add(Entry(nowMs, channel, s, false))
        for (m in moments.map { it.trim().take(200) }.filter { it.length >= 8 }.take(3)) {
            if (cur.entries.none { it.moment && similar(it.text, m) } && add.none { similar(it.text, m) })
                add.add(Entry(nowMs, channel, m, true))
        }
        if (add.isEmpty()) return
        // A day can't grow without bound: keep every moment, thin the oldest summaries first.
        var entries = cur.entries + add
        while (entries.size > DiscordBotLimits.DAY_ENTRIES_MAX) {
            val i = entries.indexOfFirst { !it.moment }.takeIf { it >= 0 } ?: 0
            entries = entries.filterIndexed { j, _ -> j != i }
        }
        save(ctx, cur.copy(entries = entries))
        prune(ctx, d)
    }

    private var lastPrune: LocalDate? = null
    private fun prune(ctx: Context, today: LocalDate) {
        if (lastPrune == today) return
        lastPrune = today
        val p = prefs(ctx); val e = p.edit()
        for (k in p.all.keys) {
            if (!k.startsWith(PREFIX)) continue
            val d = runCatching { LocalDate.parse(k.removePrefix(PREFIX)) }.getOrNull() ?: continue
            val age = today.toEpochDay() - d.toEpochDay()
            if (age > DiscordBotLimits.DAY_KEEP_DAYS) { e.remove(k); continue }
            if (age > DiscordBotLimits.DAY_RAW_KEEP_DAYS) {
                val day = load(ctx, d) ?: continue
                if (day.digest.isNotBlank() && day.entries.isNotEmpty())
                    e.putString(k, JSONObject().put("e", JSONArray()).put("dg", day.digest).toString())
            }
        }
        e.apply()
    }

    /** The most recent FINISHED day (within a week) big enough to be worth condensing and not yet condensed. */
    @Synchronized
    fun needsDigest(ctx: Context, nowMs: Long): Day? {
        val today = dateOf(nowMs)
        for (back in 1..7) {
            val day = load(ctx, today.minusDays(back.toLong())) ?: continue
            if (day.digest.isBlank() && day.entries.size >= DiscordBotLimits.DAY_DIGEST_MIN_ENTRIES) return day
        }
        return null
    }

    @Synchronized
    fun setDigest(ctx: Context, d: LocalDate, digest: String) {
        val cur = load(ctx, d) ?: return
        save(ctx, cur.copy(digest = digest.trim().take(DiscordBotLimits.DAY_DIGEST_MAX_CHARS)))
    }

    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val LABEL = DateTimeFormatter.ofPattern("EEEE MMM d", Locale.US)
    fun label(d: LocalDate, today: LocalDate): String = when (today.toEpochDay() - d.toEpochDay()) {
        0L -> "Today (${d.format(LABEL)}, UTC)"
        1L -> "Yesterday (${d.format(LABEL)}, UTC)"
        else -> "${today.toEpochDay() - d.toEpochDay()} days ago (${d.format(LABEL)}, UTC)"
    }

    /** The day as the digest-writer sees it: every entry with its time and channel. */
    fun digestInput(day: Day): String = day.entries.joinToString("\n") { e ->
        val t = Instant.ofEpochMilli(e.atMs).atZone(zone).format(TIME).lowercase()
        "- $t #${e.channel}: ${if (e.moment) "(moment) " else ""}${e.text}"
    }

    /**
     * Prompt block for the asked-about [days] (oldest first). A condensed day renders its digest; an
     * unfinished/small one its moments first, then the most recent topics. [funny] = they asked for
     * the funny stuff → moments only when there are any. [maxChars] caps the whole block.
     */
    fun render(ctx: Context, days: List<LocalDate>, nowMs: Long, funny: Boolean, maxChars: Int): String {
        val today = dateOf(nowMs)
        val multi = days.size > 1
        val sb = StringBuilder()
        for (d in days.sorted()) {
            val day = load(ctx, d)
            val part = StringBuilder(label(d, today)).append(":\n")
            when {
                day == null || (day.entries.isEmpty() && day.digest.isBlank()) -> part.append("- (nothing noted)\n")
                day.digest.isNotBlank() && !(funny && day.entries.any { it.moment }) ->
                    part.append(day.digest.lines().filter { it.isNotBlank() }
                        .take(if (multi) 3 else 8).joinToString("\n") { "- " + it.trim().removePrefix("- ").removePrefix("• ") }).append('\n')
                else -> {
                    val moments = day.entries.filter { it.moment }
                    val topics = day.entries.filter { !it.moment }
                    val pick = if (funny && moments.isNotEmpty()) moments.takeLast(if (multi) 3 else 8)
                        else (moments.takeLast(if (multi) 2 else 5) + topics.takeLast(if (multi) 1 else 4)).sortedBy { it.atMs }
                    pick.forEach { e ->
                        val t = Instant.ofEpochMilli(e.atMs).atZone(zone).format(TIME).lowercase()
                        part.append("- ").append(t).append(" #").append(e.channel).append(": ").append(e.text).append('\n')
                    }
                }
            }
            if (sb.length + part.length > maxChars && sb.isNotEmpty()) break
            sb.append(part)
        }
        return sb.toString().trim().take(maxChars)
    }

    // ── admin ──
    fun days(ctx: Context): List<Day> =
        prefs(ctx).all.keys.filter { it.startsWith(PREFIX) }
            .mapNotNull { runCatching { LocalDate.parse(it.removePrefix(PREFIX)) }.getOrNull() }
            .sortedDescending().mapNotNull { load(ctx, it) }

    fun clear(ctx: Context) { prefs(ctx).edit().clear().apply() }
    fun delete(ctx: Context, d: LocalDate) { prefs(ctx).edit().remove(key(d)).apply() }

    /** Lab/admin seeding: add an entry on a specific [date] at [hour]. */
    fun seed(ctx: Context, date: LocalDate, hour: Int, channel: String, text: String, moment: Boolean) {
        val at = date.atTime(hour.coerceIn(0, 23), 0).atZone(zone).toInstant().toEpochMilli()
        val cur = load(ctx, date) ?: Day(date, emptyList(), "")
        save(ctx, cur.copy(entries = (cur.entries + Entry(at, channel, text, moment)).sortedBy { it.atMs }))
    }
}

/**
 * Which day(s) a message asks about ("what happened yesterday", "anything funny today", "what did I
 * miss", "what went on monday", "last weekend", "on the 21st", "3 days ago"). Empty = not a day
 * question. Pure (the caller passes today's date), so it's easy to test.
 */
internal object DayQuestion {
    private val ASK = Regex(
        "(?i)(what('?s| has)? happened|what (went|was going) on|what did (we|y'?all|you guys|everyone|people|i miss)\\b|" +
            "what (was|were) (everyone|people|y'?all|we|you guys) (doing|talking|saying|up to)|what (got|was) (said|talked about)|" +
            "what did i miss|what i missed|did i miss|catch me up|fill me in|recap|summar(y|ise|ize)|" +
            "anything (fun|funny|interesting|new|happen|cool|crazy|wild|notable)|" +
            "(funny|funniest|best|craziest|wildest|notable) (thing|moment|stuff|part)s?|" +
            "what (was|were) (the )?(drama|tea|chaos)|what('?s| is) the (tea|drama))"
    )
    private val TODAYISH = Regex("(?i)\\b(today|tonight|this (morning|afternoon|evening)|so far|earlier)\\b|" +
        "what did i miss|what i missed|did i miss|catch me up|fill me in|recap|anything (fun|funny|interesting|new|cool|crazy|wild|notable)|" +
        "(funny|funniest|craziest|wildest) (thing|moment|stuff|part)s?")
    private val FUNNY = Regex("(?i)\\b(funny|funniest|lol|hilarious|laugh|best moment|craziest|wildest|chaos)\\b")
    private val WEEKDAYS = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    data class Ask(val days: List<LocalDate>, val funny: Boolean)

    fun parse(text: String, today: LocalDate): Ask? {
        val t = text.lowercase()
        if (!ASK.containsMatchIn(t)) return null
        val funny = FUNNY.containsMatchIn(t)
        fun one(d: LocalDate) = Ask(listOf(d), funny)
        Regex("\\b(\\d{1,2}) days? ago\\b").find(t)?.let { m ->
            val n = m.groupValues[1].toLong(); if (n in 1..30) return one(today.minusDays(n))
        }
        if (Regex("\\b(day before yesterday)\\b").containsMatchIn(t)) return one(today.minusDays(2))
        if (Regex("\\b(yesterday|yday|last night)\\b").containsMatchIn(t)) return one(today.minusDays(1))
        if (Regex("\\b(last|past) week\\b").containsMatchIn(t) || Regex("\\bthis week\\b").containsMatchIn(t))
            return Ask((0L..6L).map { today.minusDays(it) }, funny)
        if (Regex("\\b(last |this |the )?weekend\\b").containsMatchIn(t)) {
            // Most recent Saturday+Sunday (today counts when it's the weekend).
            var sat = today; while (sat.dayOfWeek.value != 6) sat = sat.minusDays(1)
            return Ask(listOf(sat, sat.plusDays(1)).filter { !it.isAfter(today) }, funny)
        }
        for ((i, w) in WEEKDAYS.withIndex()) {
            if (!Regex("\\b${w}s?\\b").containsMatchIn(t)) continue
            var d = today.minusDays(1)
            while (d.dayOfWeek.value != i + 1) d = d.minusDays(1)
            return one(d)
        }
        // "on the 21st", "sept 21", "9/21"
        Regex("\\b(${MONTHS.joinToString("|")})[a-z]*\\.? (\\d{1,2})(st|nd|rd|th)?\\b").find(t)?.let { m ->
            val mo = MONTHS.indexOf(m.groupValues[1].take(3)) + 1
            dateIn(today, mo, m.groupValues[2].toInt())?.let { return one(it) }
        }
        Regex("\\b(\\d{1,2})/(\\d{1,2})\\b").find(t)?.let { m ->
            dateIn(today, m.groupValues[1].toInt(), m.groupValues[2].toInt())?.let { return one(it) }
        }
        Regex("\\bthe (\\d{1,2})(st|nd|rd|th)\\b").find(t)?.let { m ->
            val day = m.groupValues[1].toInt()
            val cand = runCatching { today.withDayOfMonth(day) }.getOrNull()
            val d = if (cand != null && !cand.isAfter(today)) cand else runCatching { today.minusMonths(1).withDayOfMonth(day) }.getOrNull()
            if (d != null) return one(d)
        }
        // No date named: only a clear "catch me up" / "today" question means today (a bare "what
        // happened?" is usually about something just said, and the transcript already covers that).
        return if (TODAYISH.containsMatchIn(t)) Ask(listOf(today), funny) else null
    }

    /** A month/day in the past year (this year, or last year if it would be in the future). */
    private fun dateIn(today: LocalDate, month: Int, day: Int): LocalDate? {
        val d = runCatching { LocalDate.of(today.year, month, day) }.getOrNull() ?: return null
        return if (d.isAfter(today)) runCatching { LocalDate.of(today.year - 1, month, day) }.getOrNull() else d
    }
}
