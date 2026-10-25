package com.vrca.osc

import android.content.Context

/**
 * Headset debug, READ-ONLY: the ADB shell tests for the vertical-drag research
 * (`docs/osc-endpoints.md`, "Vertical drag: research handoff"). [FloorProbe] showed a
 * normal app can't see where Quest keeps the floor height; these run the same hunt with
 * `shell` rights over [AdbShell]: binder/dumpsys/cmd service lists, every system property
 * and setting, processes, packages, the matching services' dumpsys, and logcat, all
 * filtered to [WORDS]. [snapshotBefore] + [snapshotAfter] bracket a floor change in Quick
 * Settings and report only what changed, which names the service that holds the floor.
 * No probe writes anything. Every call blocks: run off the main thread.
 */
object AdbProbes {

    private val WORDS = listOf(
        "floor", "guardian", "boundary", "tracking", "space", "height", "stage", "recenter",
        "playarea", "play_area", "anchor", "origin",
    )
    private val PACKAGE_WORDS = WORDS + listOf("oculus", "horizon", "vros", "meta")

    /** Most matching services whose full `dumpsys` is read (each can be long). */
    private const val MAX_DUMPSYS = 12

    private class Probe(val title: String, val command: String, val words: List<String>? = WORDS)

    private val PROBES = listOf(
        Probe("shell identity", "id; getprop ro.build.fingerprint; getprop ro.build.version.release", words = null),
        Probe("binder services (service list)", "service list"),
        Probe("dumpsys services (dumpsys -l)", "dumpsys -l"),
        Probe("cmd services (cmd -l)", "cmd -l"),
        Probe("system properties (getprop)", "getprop"),
        Probe("settings system", "settings list system"),
        Probe("settings secure", "settings list secure"),
        Probe("settings global", "settings list global"),
        Probe("processes (ps -A)", "ps -A -o USER,PID,NAME"),
        Probe("packages (pm list packages)", "pm list packages", PACKAGE_WORDS),
    )

    @Volatile var lastReport = ""
        private set

    private var before: Map<String, List<String>>? = null
    private var beforeLogTime: String? = null

    private fun matches(line: String, words: List<String>): Boolean =
        line.lowercase().let { l -> words.any { it in l } }

    private fun lines(ctx: Context, probe: Probe): List<String> {
        val out = AdbShell.run(ctx, probe.command).lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        return if (probe.words == null) out else out.filter { matches(it, probe.words) }
    }

    /** Services from `dumpsys -l` whose names match [WORDS] (safe names only). */
    private fun matchingServices(dumpsysList: List<String>): List<String> =
        dumpsysList.map { it.trim() }
            .filter { it.matches(Regex("[A-Za-z0-9._-]+")) && matches(it, WORDS) }
            .distinct()
            .take(MAX_DUMPSYS)

    /** Every probe as filtered lines, plus each matching service's dumpsys, keyed by title. */
    private fun collect(ctx: Context): Map<String, List<String>> {
        val result = LinkedHashMap<String, List<String>>()
        for (p in PROBES) result[p.title] = lines(ctx, p)
        for (svc in matchingServices(result["dumpsys services (dumpsys -l)"].orEmpty())) {
            result["dumpsys $svc"] = AdbShell.run(ctx, "dumpsys $svc", timeoutMs = 10_000L)
                .lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        }
        return result
    }

    private fun notConnected(): String? =
        if (AdbShell.isConnected()) null else "Not connected: pair and connect first."

    /** One full read of every probe, plus recent logcat lines that match [WORDS]. */
    fun runAll(ctx: Context): String {
        notConnected()?.let { return it }
        val report = buildString {
            for ((title, body) in collect(ctx)) {
                append("== ").append(title).append('\n')
                if (body.isEmpty()) append("(nothing matching)\n") else body.forEach { append(it).append('\n') }
            }
            append("== logcat (last 3000 lines, matching)\n")
            val log = AdbShell.run(ctx, "logcat -d -v time -t 3000").lines().filter { matches(it, WORDS) }
            if (log.isEmpty()) append("(nothing matching)\n") else log.forEach { append(it).append('\n') }
        }
        lastReport = report
        return report
    }

    /** Step 1 of the floor test: remember every probe's lines and the log time. */
    fun snapshotBefore(ctx: Context): String {
        notConnected()?.let { return it }
        beforeLogTime = AdbShell.run(ctx, "date +'%m-%d %H:%M:%S.000'").trim().takeIf { it.matches(Regex("\\d\\d-\\d\\d .*")) }
        val snap = collect(ctx)
        before = snap
        return "Before: ${snap.values.sumOf { it.size }} lines saved. Now change the floor height " +
            "(Quick Settings > Boundary > floor level), come back and press After."
    }

    /** Step 2: read again and report lines that appeared/disappeared, plus new matching logcat. */
    fun snapshotAfter(ctx: Context): String {
        notConnected()?.let { return it }
        val old = before ?: return "Press Before first."
        val now = collect(ctx)
        val report = buildString {
            append("######## Floor change diff (- before only, + after only)\n")
            var changed = 0
            for (title in (old.keys + now.keys).distinct()) {
                val a = old[title].orEmpty()
                val b = now[title].orEmpty()
                val gone = a - b.toSet()
                val added = b - a.toSet()
                if (gone.isEmpty() && added.isEmpty()) continue
                changed++
                append("== ").append(title).append('\n')
                gone.forEach { append("- ").append(it).append('\n') }
                added.forEach { append("+ ").append(it).append('\n') }
            }
            if (changed == 0) append("(no probe changed)\n")
            append("== logcat since Before (matching)\n")
            val since = beforeLogTime
            val log = if (since == null) emptyList()
            else AdbShell.run(ctx, "logcat -d -v time -T '$since'").lines().filter { matches(it, WORDS) }
            when {
                since == null -> append("(no start time recorded)\n")
                log.isEmpty() -> append("(nothing matching)\n")
                else -> log.forEach { append(it).append('\n') }
            }
        }
        lastReport = report
        return report
    }

    /** A command typed in the debug panel, run as-is. */
    fun runCommand(ctx: Context, command: String): String {
        notConnected()?.let { return it }
        val out = AdbShell.run(ctx, command)
        lastReport = "$ $command\n$out"
        return lastReport
    }
}
