package com.vrca.ui.common

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Time-zone helpers shared by [com.vrca.ui.viewmodel.VrcaViewModel] (the Time line)
 * and the Home time-zone picker. The stored `timeMode` string is one of:
 *   - "Device" / "LOCAL"  → the device's current zone
 *   - "UTC"               → UTC
 *   - "UTC+N" / "UTC-N"   → fixed offset (LEGACY format, no DST — kept for back-compat)
 *   - an IANA zone id     → e.g. "Europe/Paris" (DST-correct automatically)
 *
 * New selections store IANA ids so the displayed offset follows daylight savings;
 * old saved "UTC+5" values keep working unchanged.
 */

/** Resolve a stored timeMode to a [ZoneId]. Backward compatible with "UTC±N". */
fun resolveTimeZone(mode: String): ZoneId = when {
    mode == "Device" || mode == "LOCAL" || mode.isBlank() -> ZoneId.systemDefault()
    mode == "UTC" -> ZoneOffset.UTC
    mode.startsWith("UTC+") -> ZoneOffset.ofHours(mode.removePrefix("UTC+").toIntOrNull() ?: 0)
    mode.startsWith("UTC-") -> ZoneOffset.ofHours(-(mode.removePrefix("UTC-").toIntOrNull() ?: 0))
    else -> runCatching { ZoneId.of(mode) }.getOrDefault(ZoneId.systemDefault())
}

/** The device's current IANA zone id (e.g. "Europe/London"). */
fun deviceTimeZoneId(): String = ZoneId.systemDefault().id

/** Pretty city name from a zone id: "America/New_York" → "New York". */
fun timeZoneCity(id: String): String =
    id.substringAfterLast('/').replace('_', ' ')

/** Region prefix from a zone id: "America/New_York" → "America". */
fun timeZoneRegion(id: String): String =
    if (id.contains('/')) id.substringBeforeLast('/').replace('_', ' ') else ""

/**
 * Country display name for a zone id: "Asia/Tokyo" → "Japan", "Europe/Paris" →
 * "France". Covers ALL countries automatically (no hand-maintained list) so a search
 * for a country name resolves to its city zones. Returns "" for non-country zones
 * (e.g. "Etc/UTC").
 */
fun zoneCountryName(id: String): String = zoneToCountryName[id].orEmpty()

/**
 * zoneId → country display name, precomputed once. Built from THREE sources so an
 * ID-format mismatch or ICU quirk on any single device can't silently drop a country
 * (the "searching Japan finds nothing" bug — the previous single-source inverse map
 * keyed on ICU's `getAvailableIDs` ids, which don't always match java.time's ids):
 *   1. Per-id `getRegion(id)` over the EXACT picker ids in [allTimeZoneIds] — no id
 *      mismatch possible since we map the same strings the picker uses.
 *   2. ICU's country→zones inverse (`getAvailableIDs(code)`), with each id ALSO mapped
 *      via its canonical form so a legacy/alias id still tags the canonical zone.
 *   3. A hardcoded fallback ([commonCountryZones]) for the most-searched countries, so
 *      they resolve even if ICU misbehaves on a given device.
 * Earlier sources win; the hardcoded fallback only fills gaps.
 */
private val zoneToCountryName: Map<String, String> by lazy {
    val map = HashMap<String, String>()
    // (1) Per-id getRegion over the actual picker ids.
    for (id in allTimeZoneIds) {
        runCatching {
            val region = android.icu.util.TimeZone.getRegion(id)
            if (!region.isNullOrBlank() && region != "001" && !region.all { it.isDigit() }) {
                val name = Locale("", region).displayCountry
                if (name.isNotBlank()) map[id] = name
            }
        }
    }
    // (2) ICU country→zones inverse, canonicalizing each id.
    runCatching {
        for (code in Locale.getISOCountries()) {
            val name = Locale("", code).displayCountry
            if (name.isBlank()) continue
            runCatching {
                for (z in android.icu.util.TimeZone.getAvailableIDs(code)) {
                    map.putIfAbsent(z, name)
                    runCatching {
                        val canon = android.icu.util.TimeZone.getCanonicalID(z)
                        if (!canon.isNullOrBlank()) map.putIfAbsent(canon, name)
                    }
                }
            }
        }
    }
    // (3) Hardcoded fallback for popular countries (fills any remaining gaps).
    for ((zone, name) in commonCountryZones) map.putIfAbsent(zone, name)
    map
}

/** Guaranteed zone→country for the most-searched countries (ICU-independent). */
private val commonCountryZones: Map<String, String> = mapOf(
    "Asia/Tokyo" to "Japan", "Asia/Seoul" to "South Korea", "Asia/Shanghai" to "China",
    "Asia/Hong_Kong" to "Hong Kong", "Asia/Singapore" to "Singapore",
    "Asia/Bangkok" to "Thailand", "Asia/Jakarta" to "Indonesia", "Asia/Manila" to "Philippines",
    "Asia/Kolkata" to "India", "Asia/Karachi" to "Pakistan", "Asia/Dubai" to "United Arab Emirates",
    "Asia/Tel_Aviv" to "Israel", "Asia/Jerusalem" to "Israel", "Asia/Istanbul" to "Turkey",
    "Europe/Istanbul" to "Turkey", "Europe/London" to "United Kingdom",
    "Europe/Paris" to "France", "Europe/Berlin" to "Germany", "Europe/Madrid" to "Spain",
    "Europe/Rome" to "Italy", "Europe/Amsterdam" to "Netherlands", "Europe/Brussels" to "Belgium",
    "Europe/Zurich" to "Switzerland", "Europe/Vienna" to "Austria", "Europe/Stockholm" to "Sweden",
    "Europe/Oslo" to "Norway", "Europe/Copenhagen" to "Denmark", "Europe/Helsinki" to "Finland",
    "Europe/Warsaw" to "Poland", "Europe/Lisbon" to "Portugal", "Europe/Dublin" to "Ireland",
    "Europe/Athens" to "Greece", "Europe/Moscow" to "Russia", "Europe/Kiev" to "Ukraine",
    "Europe/Kyiv" to "Ukraine", "America/New_York" to "United States",
    "America/Los_Angeles" to "United States", "America/Chicago" to "United States",
    "America/Denver" to "United States", "America/Toronto" to "Canada",
    "America/Vancouver" to "Canada", "America/Mexico_City" to "Mexico",
    "America/Sao_Paulo" to "Brazil", "America/Buenos_Aires" to "Argentina",
    "America/Argentina/Buenos_Aires" to "Argentina", "America/Bogota" to "Colombia",
    "America/Santiago" to "Chile", "Australia/Sydney" to "Australia",
    "Australia/Melbourne" to "Australia", "Australia/Perth" to "Australia",
    "Pacific/Auckland" to "New Zealand", "Africa/Cairo" to "Egypt",
    "Africa/Johannesburg" to "South Africa", "Africa/Lagos" to "Nigeria",
    "Africa/Nairobi" to "Kenya", "Africa/Casablanca" to "Morocco"
)

/**
 * Common alternate/colloquial country names that don't match the ICU display name,
 * mapped to a substring that DOES appear in some zone's country/city/id. So "usa"
 * finds United States zones, "uk"/"england" find United Kingdom, etc.
 */
val countrySynonyms: Map<String, String> = mapOf(
    "usa" to "united states", "us" to "united states", "america" to "united states",
    "uk" to "united kingdom", "england" to "united kingdom", "britain" to "united kingdom",
    "great britain" to "united kingdom", "scotland" to "united kingdom",
    "holland" to "netherlands", "uae" to "united arab emirates", "emirates" to "united arab emirates",
    "south korea" to "korea", "north korea" to "korea", "russia" to "russia",
    "czech republic" to "czechia", "burma" to "myanmar", "ivory coast" to "côte d'ivoire",
    "vatican" to "vatican", "deutschland" to "germany"
)

/** Live, DST-correct offset label for a zone: "UTC+2", "UTC", "UTC+5:30". */
fun zoneOffsetLabel(zone: ZoneId): String {
    val secs = zone.rules.getOffset(Instant.now()).totalSeconds
    if (secs == 0) return "UTC"
    val sign = if (secs < 0) "-" else "+"
    val a = abs(secs)
    val h = a / 3600
    val m = (a % 3600) / 60
    return if (m == 0) "UTC$sign$h" else "UTC$sign$h:${m.toString().padStart(2, '0')}"
}

/** Current numeric offset seconds for sorting (west → east). */
fun zoneOffsetSeconds(id: String): Int =
    runCatching { ZoneId.of(id).rules.getOffset(Instant.now()).totalSeconds }.getOrDefault(0)

/**
 * The picker label: "Paris 3:42 PM (UTC+2)" (or 24h "Paris 15:42 (UTC+2)").
 * Time + offset are computed live so they reflect daylight savings.
 */
fun timeZoneLabel(id: String, use24h: Boolean): String {
    val zone = resolveTimeZone(id)
    val now = LocalDateTime.now(zone)
    val time = if (use24h) DateTimeFormatter.ofPattern("HH:mm").format(now)
    else DateTimeFormatter.ofPattern("h:mm a", Locale.US).format(now)
    val city = timeZoneCity(id)
    return "$city $time (${zoneOffsetLabel(zone)})"
}

/**
 * All selectable IANA zones (cached), filtered to real region/city ids and sorted
 * by live offset then name. Excludes legacy SystemV/POSIX/Etc aliases.
 */
val allTimeZoneIds: List<String> by lazy {
    ZoneId.getAvailableZoneIds()
        .filter { it.contains('/') && !it.startsWith("SystemV") && !it.startsWith("Etc/") && !it.startsWith("US/") }
        .sortedWith(compareBy({ zoneOffsetSeconds(it) }, { it }))
}

/** A curated short list of major-city zones shown before the user searches. */
val commonTimeZoneIds: List<String> = listOf(
    "Pacific/Honolulu", "America/Anchorage", "America/Los_Angeles", "America/Denver",
    "America/Chicago", "America/New_York", "America/Sao_Paulo", "Atlantic/Reykjavik",
    "Europe/London", "Europe/Paris", "Europe/Berlin", "Europe/Madrid", "Europe/Rome",
    "Europe/Athens", "Europe/Moscow", "Africa/Cairo", "Africa/Johannesburg",
    "Asia/Dubai", "Asia/Karachi", "Asia/Kolkata", "Asia/Bangkok", "Asia/Shanghai",
    "Asia/Singapore", "Asia/Tokyo", "Asia/Seoul", "Australia/Sydney", "Pacific/Auckland"
)
