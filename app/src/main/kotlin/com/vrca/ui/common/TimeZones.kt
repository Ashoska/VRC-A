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
