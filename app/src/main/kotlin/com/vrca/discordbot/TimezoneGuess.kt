package com.vrca.discordbot

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A person's timezone, worked out for free (no model call), from their own words:
 *  - stated: "i'm on EST", "my timezone is utc+2", "gmt-5 here" (abbreviations map to a real zone so the
 *    shown offset follows daylight saving);
 *  - revealed: "it's 3am here" / "11pm for me" compared with the message's own UTC time;
 *  - where they live / are from: a city that names a zone ("Toronto"), or a country with one offset.
 * Stored as an IANA id or a fixed "UTC±N"; always SHOWN as the live UTC offset.
 */
object TimezoneGuess {
    private val ABBR = mapOf(
        "est" to "America/New_York", "edt" to "America/New_York", "et" to "America/New_York", "eastern" to "America/New_York",
        "cst" to "America/Chicago", "cdt" to "America/Chicago", "central" to "America/Chicago",
        "mst" to "America/Denver", "mdt" to "America/Denver", "mountain" to "America/Denver",
        "pst" to "America/Los_Angeles", "pdt" to "America/Los_Angeles", "pt" to "America/Los_Angeles", "pacific" to "America/Los_Angeles",
        "akst" to "America/Anchorage", "hst" to "Pacific/Honolulu",
        "gmt" to "Europe/London", "bst" to "Europe/London", "wet" to "Europe/Lisbon",
        "cet" to "Europe/Paris", "cest" to "Europe/Paris", "eet" to "Europe/Athens", "eest" to "Europe/Athens",
        "msk" to "Europe/Moscow", "ist" to "Asia/Kolkata", "pkt" to "Asia/Karachi", "wib" to "Asia/Jakarta",
        "sgt" to "Asia/Singapore", "hkt" to "Asia/Hong_Kong", "pht" to "Asia/Manila",
        "jst" to "Asia/Tokyo", "kst" to "Asia/Seoul",
        "awst" to "Australia/Perth", "acst" to "Australia/Adelaide", "aest" to "Australia/Sydney", "aedt" to "Australia/Sydney",
        "nzst" to "Pacific/Auckland", "nzdt" to "Pacific/Auckland",
        "brt" to "America/Sao_Paulo", "art" to "America/Argentina/Buenos_Aires",
    )
    private val OFFSET_RE = Regex("(?i)\\b(?:utc|gmt)\\s*([+-])\\s*(\\d{1,2})(?::?(\\d{2}))?\\b")
    private val TZ_CUE = Regex("(?i)\\b(time ?zone|tz|i'?m (?:on|in)|im (?:on|in)|i am (?:on|in)|i live (?:on|in)|here|for me|my time)\\b")
    private val ABBR_RE = Regex("(?i)(?<![\\p{L}])(" + ABBR.keys.sortedByDescending { it.length }.joinToString("|") + ")(?![\\p{L}])(?:\\s*time)?")

    /** A timezone the person names for themselves, from one of their own lines. */
    fun stated(text: String): String? {
        val t = text.trim()
        OFFSET_RE.find(t)?.let { m ->
            // "utc+2" on its own, or with a cue ("i'm utc+2", "my timezone is gmt-5").
            if (TZ_CUE.containsMatchIn(t) || t.length <= 16) return fixed(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }
        if (!TZ_CUE.containsMatchIn(t)) return null
        // A time zone word only counts written as one (EST/est), not inside a sentence about something else.
        val m = ABBR_RE.find(t) ?: return null
        val word = m.groupValues[1]
        // Lowercase short words collide with normal text ("et", "art", "pt"): need a time-zone cue next to them.
        if (word.length <= 3 && word == word.lowercase() && !Regex("(?i)(time ?zone|tz|\\btime\\b)").containsMatchIn(t) &&
            !Regex("(?i)\\b(i'?m|im|i am) (on|in) " + Regex.escape(word) + "\\b").containsMatchIn(t)) return null
        return ABBR[word.lowercase()]
    }

    private val REVEAL_RE = Regex("(?i)\\b(?:it'?s|its|it is)\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?\\s+(?:here|for me|over here|where i am|at my place)\\b|" +
        "\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\s+(?:here|for me|over here|where i am)\\b")

    /** "it's 3am here" at a message sent [atMs] (UTC) → the offset it implies, rounded to 30 min. */
    fun revealed(text: String, atMs: Long): String? {
        val m = REVEAL_RE.find(text) ?: return null
        val g = m.groupValues
        val h0 = (g[1].ifBlank { g[4] }).toIntOrNull() ?: return null
        val min = (g[2].ifBlank { g[5] }).toIntOrNull() ?: 0
        val ap = g[3].ifBlank { g[6] }.lowercase().replace(".", "")
        if (h0 > 23 || min > 59) return null
        // "it's 3 here" without am/pm is ambiguous unless it's a 24h hour.
        if (ap.isBlank() && h0 <= 12) return null
        val hour = when { ap == "pm" && h0 < 12 -> h0 + 12; ap == "am" && h0 == 12 -> 0; else -> h0 }
        val utc = Instant.ofEpochMilli(atMs).atZone(ZoneOffset.UTC)
        var diff = (hour * 60 + min) - (utc.hour * 60 + utc.minute)
        while (diff > 14 * 60) diff -= 24 * 60
        while (diff < -12 * 60) diff += 24 * 60
        val halfHours = (diff / 30.0).roundToInt()
        val h = halfHours / 2; val half = abs(halfHours % 2) == 1
        return "UTC" + (if (halfHours < 0) "-" else "+") + abs(h) + if (half) ":30" else ""
    }

    private fun fixed(sign: String, h: String, m: String): String? {
        val hh = h.toIntOrNull() ?: return null
        if (hh > 14) return null
        return "UTC$sign$hh" + if (m.isNotBlank() && m != "00") ":$m" else ""
    }

    /** A city that names a zone ("toronto" → America/Toronto), or a country whose zones share one offset. */
    fun fromPlace(place: String): String? = placeCache.getOrPut(place.lowercase().trim()) { lookupPlace(place) ?: "" }.ifBlank { null }
    private val placeCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun lookupPlace(place: String): String? {
        val p = place.lowercase().replace(Regex("[^\\p{L} ,]"), " ").replace(Regex("\\s+"), " ").trim()
        if (p.isBlank()) return null
        val ids = com.vrca.ui.common.allTimeZoneIds
        // Any part ("osaka, japan" → osaka first) that is a zone's city.
        val parts = p.split(',').map { it.trim() }.filter { it.isNotBlank() } + p
        // A state/province after the comma decides it ("paris, texas", "london, ontario", "madison, wi").
        parts.drop(1).dropLast(1).forEach { part ->
            regionOf(part)?.let { return it }
            if (part.length == 2) REGION_CODE[part]?.let { return it }
        }
        for (part in parts) {
            val key = part.replace(' ', '_')
            ids.firstOrNull { it.substringAfterLast('/').equals(key, true) }?.let { return it }
            CITY_ALIAS[part]?.let { return it }
            regionOf(part)?.let { return it }
        }
        // "madison wi": a state code as the last word.
        if (p.contains(' ')) REGION_CODE[p.substringAfterLast(' ')]?.let { return it }
        for (part in parts.reversed()) {
            val country = com.vrca.ui.common.countrySynonyms[part] ?: part
            val zones = ids.filter { com.vrca.ui.common.zoneCountryName(it).equals(country, true) }
                .ifEmpty { ids.filter { com.vrca.ui.common.zoneCountryName(it).lowercase().contains(country) && country.length >= 4 } }
            if (zones.isEmpty()) continue
            val now = Instant.now()
            val offsets = zones.map { ZoneId.of(it).rules.getOffset(now) }.toSet()
            if (offsets.size == 1) return zones.first()
            COUNTRY_MAIN[country]?.let { return it }
        }
        return null
    }
    // A few big places that aren't zone names.
    private val CITY_ALIAS = mapOf(
        "nyc" to "America/New_York", "new york city" to "America/New_York", "la" to "America/Los_Angeles",
        "sf" to "America/Los_Angeles", "san francisco" to "America/Los_Angeles", "seattle" to "America/Los_Angeles",
        "boston" to "America/New_York", "miami" to "America/New_York", "atlanta" to "America/New_York",
        "dallas" to "America/Chicago", "houston" to "America/Chicago", "austin" to "America/Chicago",
        "montreal" to "America/Toronto", "ottawa" to "America/Toronto", "kyoto" to "Asia/Tokyo", "osaka" to "Asia/Tokyo",
        "manchester" to "Europe/London", "glasgow" to "Europe/London", "edinburgh" to "Europe/London",
        "munich" to "Europe/Berlin", "hamburg" to "Europe/Berlin", "barcelona" to "Europe/Madrid", "milan" to "Europe/Rome",
        "mumbai" to "Asia/Kolkata", "delhi" to "Asia/Kolkata", "bangalore" to "Asia/Kolkata",
    )
    // States/provinces of countries with several zones (the zone most of the state is in).
    private val US_STATE = mapOf(
        "alabama" to "America/Chicago", "alaska" to "America/Anchorage", "arizona" to "America/Phoenix", "arkansas" to "America/Chicago",
        "california" to "America/Los_Angeles", "colorado" to "America/Denver", "connecticut" to "America/New_York", "delaware" to "America/New_York",
        "florida" to "America/New_York", "georgia" to "America/New_York", "hawaii" to "Pacific/Honolulu", "idaho" to "America/Boise",
        "illinois" to "America/Chicago", "indiana" to "America/Indiana/Indianapolis", "iowa" to "America/Chicago", "kansas" to "America/Chicago",
        "kentucky" to "America/New_York", "louisiana" to "America/Chicago", "maine" to "America/New_York", "maryland" to "America/New_York",
        "massachusetts" to "America/New_York", "michigan" to "America/Detroit", "minnesota" to "America/Chicago", "mississippi" to "America/Chicago",
        "missouri" to "America/Chicago", "montana" to "America/Denver", "nebraska" to "America/Chicago", "nevada" to "America/Los_Angeles",
        "new hampshire" to "America/New_York", "new jersey" to "America/New_York", "new mexico" to "America/Denver", "new york" to "America/New_York",
        "north carolina" to "America/New_York", "north dakota" to "America/Chicago", "ohio" to "America/New_York", "oklahoma" to "America/Chicago",
        "oregon" to "America/Los_Angeles", "pennsylvania" to "America/New_York", "rhode island" to "America/New_York", "south carolina" to "America/New_York",
        "south dakota" to "America/Chicago", "tennessee" to "America/Chicago", "texas" to "America/Chicago", "utah" to "America/Denver",
        "vermont" to "America/New_York", "virginia" to "America/New_York", "washington" to "America/Los_Angeles", "west virginia" to "America/New_York",
        "wisconsin" to "America/Chicago", "wyoming" to "America/Denver", "washington dc" to "America/New_York", "dc" to "America/New_York",
        "puerto rico" to "America/Puerto_Rico",
    )
    private val US_CODE = mapOf(
        "al" to "alabama", "ak" to "alaska", "az" to "arizona", "ar" to "arkansas", "ca" to "california", "co" to "colorado", "ct" to "connecticut",
        "de" to "delaware", "fl" to "florida", "ga" to "georgia", "hi" to "hawaii", "id" to "idaho", "il" to "illinois", "in" to "indiana",
        "ia" to "iowa", "ks" to "kansas", "ky" to "kentucky", "la" to "louisiana", "me" to "maine", "md" to "maryland", "ma" to "massachusetts",
        "mi" to "michigan", "mn" to "minnesota", "ms" to "mississippi", "mo" to "missouri", "mt" to "montana", "ne" to "nebraska", "nv" to "nevada",
        "nh" to "new hampshire", "nj" to "new jersey", "nm" to "new mexico", "ny" to "new york", "nc" to "north carolina", "nd" to "north dakota",
        "oh" to "ohio", "ok" to "oklahoma", "or" to "oregon", "pa" to "pennsylvania", "ri" to "rhode island", "sc" to "south carolina",
        "sd" to "south dakota", "tn" to "tennessee", "tx" to "texas", "ut" to "utah", "vt" to "vermont", "va" to "virginia", "wa" to "washington",
        "wv" to "west virginia", "wi" to "wisconsin", "wy" to "wyoming",
    )
    private val OTHER_REGION = mapOf(
        // Canada
        "ontario" to "America/Toronto", "quebec" to "America/Toronto", "british columbia" to "America/Vancouver", "alberta" to "America/Edmonton",
        "manitoba" to "America/Winnipeg", "saskatchewan" to "America/Regina", "nova scotia" to "America/Halifax", "new brunswick" to "America/Moncton",
        "newfoundland" to "America/St_Johns", "prince edward island" to "America/Halifax", "yukon" to "America/Whitehorse",
        // Australia
        "new south wales" to "Australia/Sydney", "nsw" to "Australia/Sydney", "victoria" to "Australia/Melbourne", "queensland" to "Australia/Brisbane",
        "western australia" to "Australia/Perth", "south australia" to "Australia/Adelaide", "tasmania" to "Australia/Hobart",
        "northern territory" to "Australia/Darwin",
        // UK nations and a few big regions people name instead of a country
        "england" to "Europe/London", "scotland" to "Europe/London", "wales" to "Europe/London", "northern ireland" to "Europe/London",
        "bavaria" to "Europe/Berlin", "catalonia" to "Europe/Madrid", "sicily" to "Europe/Rome", "siberia" to "Asia/Novosibirsk",
        "sao paulo" to "America/Sao_Paulo", "são paulo" to "America/Sao_Paulo", "rio de janeiro" to "America/Sao_Paulo",
        "hokkaido" to "Asia/Tokyo", "okinawa" to "Asia/Tokyo", "bali" to "Asia/Makassar", "java" to "Asia/Jakarta",
    )
    private val REGION: Map<String, String> = US_STATE + OTHER_REGION
    private fun regionOf(part: String): String? = REGION[part.removePrefix("the ").removeSuffix(" state").trim()]
    private val REGION_CODE: Map<String, String> = US_CODE.mapValues { US_STATE.getValue(it.value) } +
        mapOf("bc" to "America/Vancouver", "ab" to "America/Edmonton", "on" to "America/Toronto", "qc" to "America/Toronto")

    // Countries with several zones where one covers most people only when nothing better is known? No: ambiguous
    // countries stay unknown unless listed here because one zone clearly dominates.
    private val COUNTRY_MAIN = mapOf("india" to "Asia/Kolkata", "china" to "Asia/Shanghai", "spain" to "Europe/Madrid",
        "portugal" to "Europe/Lisbon", "germany" to "Europe/Berlin", "united kingdom" to "Europe/London")

    fun zoneOf(tz: String): ZoneId? {
        val t = tz.trim(); if (t.isBlank()) return null
        Regex("^UTC([+-])(\\d{1,2})(?::(\\d{2}))?$").find(t)?.let { m ->
            val sign = if (m.groupValues[1] == "-") -1 else 1
            return runCatching { ZoneOffset.ofHoursMinutes(sign * m.groupValues[2].toInt(), sign * (m.groupValues[3].toIntOrNull() ?: 0)) }.getOrNull()
        }
        if (t == "UTC") return ZoneOffset.UTC
        return runCatching { ZoneId.of(t) }.getOrNull()
    }

    /** "UTC-4", "UTC+5:30", "UTC" — for the zone right now (daylight saving included). */
    fun offsetLabel(z: ZoneId, nowMs: Long = System.currentTimeMillis()): String {
        val secs = z.rules.getOffset(Instant.ofEpochMilli(nowMs)).totalSeconds
        if (secs == 0) return "UTC"
        val a = abs(secs); val h = a / 3600; val m = (a % 3600) / 60
        return "UTC" + (if (secs < 0) "-" else "+") + h + if (m != 0) ":%02d".format(m) else ""
    }
}
