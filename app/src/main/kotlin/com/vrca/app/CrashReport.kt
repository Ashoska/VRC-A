package com.vrca.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import com.vrca.BuildConfig
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The last crash, and the safe start after it (user, 2026-10-23: after a crash VRC-A either
 * crashed again straight away or flashed the crash log and crashed; the log only showed after
 * several crashes).
 *
 * Why it failed before: (1) the log was saved with `apply()`, which writes LATER, and the process
 * died first, so usually nothing was saved; (2) when it was saved, everything still started on the
 * next open (services, receivers, the overlay, feature restore) before the crash screen could show,
 * so a crash in any of those crashed it again.
 *
 * Now: [record] writes synchronously (`commit()`), and a launch with a crash waiting is a SAFE
 * START ([safeStart]): VrcaApplication and MainActivity skip everything optional, services stop
 * themselves, and only the crash screen runs. "Start VRC-A" clears the report and [release]s the
 * skipped work. Crashes the handler can't catch (native, the app not responding) come from
 * Android's record of why the process last ended (API 30+).
 */
object CrashReport {
    private const val PREFS = VrcaApplication.CRASH_PREFS_FILE
    private const val KEY_TEXT = VrcaApplication.CRASH_KEY_TEXT
    /** Android's exit records already looked at (newest timestamp), so one isn't shown twice. */
    private const val KEY_EXIT_SEEN = "exit_seen_ms"
    /** Recent crashes (user, 2026-10-23: the past 3 days, up to 5), newest first, as a JSON array of
     *  {at, text, sent}. Kept after Start VRC-A, for the "crashed N times" line and the report file. */
    private const val KEY_HISTORY = "history"
    private const val KEEP_MS = 3L * 24 * 60 * 60 * 1000
    private const val KEEP_COUNT = 5
    private const val HISTORY_CHARS = 20_000

    /** True from launch while a crash report is waiting; the app's optional start-up is held.
     *  Watched state, so the screen's overlay appears once the safe start ends. */
    var safeStart by mutableStateOf(false)
        private set

    private val onRelease = mutableListOf<() -> Unit>()

    /** Called from the crash handler, on the crashing thread, right before the process dies. */
    fun record(ctx: Context, t: Thread, e: Throwable) {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        header(pw, "VRC-A LAST CRASH", ctx)
        pw.println("Where: ${where(e)}")
        pw.println("What: ${what(e)}")
        pw.println("Thread: ${t.name}")
        runCatching {
            val rt = Runtime.getRuntime()
            pw.println("Memory: ${(rt.totalMemory() - rt.freeMemory()) shr 20} MB used of ${rt.maxMemory() shr 20} MB")
        }
        runCatching {
            val sending = ctx.getSharedPreferences(AppShutdown.PREFS, Context.MODE_PRIVATE).getBoolean("feature_osc_sending", false)
            pw.println("Chatbox sending: ${if (sending) "on" else "off"}")
            pw.println("Discord: ${com.vrca.discord.DiscordRpcState.status}")
        }
        pw.println()
        e.printStackTrace(pw)
        pw.flush()
        save(ctx, sw.toString())
    }

    /** Saves a new report as the waiting one AND into the history, in one synchronous write. */
    private fun save(ctx: Context, text: String, at: Long = System.currentTimeMillis()) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = history(prefs).toMutableList()
        list.add(0, Entry(at, text.take(HISTORY_CHARS), false))
        // commit(), not apply(): apply() writes later, and the process is killed before then.
        prefs.edit().putString(KEY_TEXT, text.take(MAX_CHARS)).putString(KEY_HISTORY, encode(list)).commit()
    }

    /** One remembered crash. [sent] = already uploaded to the developer (CrashUpload). */
    class Entry(val at: Long, val text: String, val sent: Boolean)

    /** The crashes of the past 3 days, up to 5, newest first. */
    fun recent(ctx: Context): List<Entry> = history(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    private fun history(prefs: android.content.SharedPreferences): List<Entry> = runCatching {
        val a = org.json.JSONArray(prefs.getString(KEY_HISTORY, "[]"))
        val now = System.currentTimeMillis()
        (0 until a.length()).map { a.getJSONObject(it) }
            .map { Entry(it.getLong("at"), it.optString("text"), it.optBoolean("sent")) }
            .filter { now - it.at in 0..KEEP_MS }
            .sortedByDescending { it.at }
            .take(KEEP_COUNT)
    }.getOrDefault(emptyList())

    private fun encode(list: List<Entry>): String {
        val now = System.currentTimeMillis()
        val a = org.json.JSONArray()
        list.filter { now - it.at in 0..KEEP_MS }.take(KEEP_COUNT).forEach {
            a.put(org.json.JSONObject().put("at", it.at).put("text", it.text).put("sent", it.sent))
        }
        return a.toString()
    }

    /** Marks these crashes (by time) as uploaded. */
    fun markSent(ctx: Context, ats: Set<Long>) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = history(prefs).map { if (it.at in ats) Entry(it.at, it.text, true) else it }
        prefs.edit().putString(KEY_HISTORY, encode(list)).apply()
    }

    /**
     * The part of VRC-A that crashed, in plain words, from the first frame in VRC-A's own code. Release
     * builds rename classes but keep their packages (`-keeppackagenames com.vrca.**`), so this works there.
     */
    private fun where(e: Throwable): String {
        var c: Throwable? = e
        var depth = 0
        while (c != null && depth++ < 8) {
            val pkg = c.stackTrace.firstOrNull { it.className.startsWith("com.vrca.") }?.className
                ?.removePrefix("com.vrca.")?.substringBefore('.')
            if (pkg != null) return AREAS[pkg] ?: "VRC-A"
            c = c.cause
        }
        return "Android or a library"
    }

    private val AREAS = mapOf(
        "admin" to "Admin tools", "app" to "the main app", "data" to "Saved settings",
        "discord" to "Discord", "discordbot" to "Discord bot", "keepalive" to "Running in the background",
        "nowplaying" to "Now Playing", "osc" to "Chatbox", "overlay" to "Overlay", "richcontent" to "Announcements",
        "speech" to "Voice to text", "sync" to "Syncing", "ui" to "the app's screens", "update" to "Updates",
        "vrchat" to "VRChat connection",
    )

    /** What went wrong, in plain words (the Java/Android error name only; never a message or our code). */
    private fun what(e: Throwable): String {
        val n = e.javaClass.name
        val plain = when {
            e is OutOfMemoryError -> "Ran out of memory"
            e is StackOverflowError -> "Got stuck in a loop"
            e is NullPointerException || e is KotlinNullPointerException -> "Something it needed was missing"
            e is SecurityException -> "Android blocked a permission"
            e is IllegalStateException -> "Something was in the wrong state"
            e is IllegalArgumentException -> "Got a value it didn't expect"
            e is IndexOutOfBoundsException || e is NoSuchElementException -> "Looked for something that wasn't there"
            e is ConcurrentModificationException -> "Two things changed the same data at once"
            e is ClassCastException -> "Got the wrong kind of data"
            e is UnsatisfiedLinkError -> "A built-in library failed to load"
            else -> "Unexpected error"
        }
        val known = n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("android.") || n.startsWith("kotlin.")
        return if (known) "$plain (${n.substringAfterLast('.')})" else plain
    }

    /**
     * Run once at launch (VrcaApplication.onCreate, main thread): is a crash report waiting? A
     * crash Android recorded but the handler couldn't (native code, not responding) becomes one.
     */
    fun checkAtLaunch(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_TEXT, "").isNullOrBlank()) {
            fromExitInfo(ctx)?.let { save(ctx, it.second, it.first) }
        }
        safeStart = !prefs.getString(KEY_TEXT, "").isNullOrBlank()
        if (safeStart) loadForScreen(ctx)
    }

    /** The waiting report and the recent crashes, read once before any UI (the crash screen shows these,
     *  never reading storage while drawing). Set at launch by [checkAtLaunch]; the UI Lab calls it too. */
    var screenReport: String = ""
        private set
    var screenHistory: List<Entry> = emptyList()
        private set

    fun loadForScreen(ctx: Context) {
        screenReport = text(ctx)
        screenHistory = recent(ctx)
    }

    /** The waiting report's full text ("" if none). */
    fun text(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TEXT, "").orEmpty()

    /** Runs [block] now, or when the safe start ends ("Start VRC-A"). Main thread. */
    fun whenStarted(block: () -> Unit) {
        if (safeStart) synchronized(onRelease) { onRelease += block } else block()
    }

    /**
     * For a service Android restarts on its own (sticky) during a safe start: stops it, so a
     * crash inside it can't take the crash screen down. True = held; the caller returns.
     */
    fun holdService(s: android.app.Service): Boolean {
        if (!safeStart) return false
        runCatching { s.stopSelf() }
        return true
    }

    /** "Start VRC-A": clear the waiting report (the history keeps it) and run the start-up work that was held. Main thread. */
    fun release(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_TEXT).commit()
        safeStart = false
        val held = synchronized(onRelease) { onRelease.toList().also { onRelease.clear() } }
        held.forEach { runCatching(it) }
        // Now that the app runs normally, send the developer any crashes not sent yet.
        runCatching { CrashUpload.sendPending(ctx) }
    }

    /** A report header line's value ("" if missing), e.g. field(text, "Where"). */
    fun field(text: String, name: String): String =
        text.lineSequence().firstOrNull { it.startsWith("$name: ") }?.removePrefix("$name: ").orEmpty()

    /** Where it crashed, in plain words, for the screen (never code names). */
    fun whereOf(text: String): String = when {
        text.startsWith("=== VRC-A NOT RESPONDING") -> "VRC-A stopped responding"
        text.startsWith("=== VRC-A NATIVE CRASH") -> "A built-in library"
        else -> field(text, "Where").ifBlank { "VRC-A" }
    }

    /** What went wrong, in plain words, for the screen. */
    fun whatOf(text: String): String = when {
        text.startsWith("=== VRC-A NOT RESPONDING") -> "The app froze and Android closed it"
        text.startsWith("=== VRC-A NATIVE CRASH") -> "A crash in native code"
        else -> field(text, "What").ifBlank { "Unexpected error" }
    }

    /** The build this report came from, in plain words: "v1.4.80 · Quest". */
    fun versionOf(text: String): String {
        val v = field(text, "VersionName").ifBlank { BuildConfig.VERSION_NAME }
        val b = field(text, "Build")
        return if (b.isBlank()) v else "$v · $b"
    }

    /** When the crash happened, from the report's "Time:" line ("" if missing). */
    fun time(text: String): String =
        text.lineSequence().firstOrNull { it.startsWith("Time: ") }?.removePrefix("Time: ").orEmpty()

    private fun header(pw: PrintWriter, title: String, ctx: Context, at: Long = System.currentTimeMillis()) {
        pw.println("=== $title ===")
        pw.println("Time: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(at)))
        pw.println("Process: ${Process.myPid()}")
        pw.println("SDK: ${Build.VERSION.SDK_INT}")
        pw.println("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        pw.println("AppId: ${ctx.packageName}")
        pw.println("VersionName: ${BuildConfig.VERSION_NAME}")
        pw.println("VersionCode: ${BuildConfig.VERSION_CODE}")
        pw.println("Build: ${if (BuildConfig.IS_HEADSET_BUILD) "Quest" else if (BuildConfig.IS_ADMIN_BUILD) "Admin" else "Phone"}")
    }

    /** The newest native crash / not-responding exit since the last one seen, as report text. */
    private fun fromExitInfo(ctx: Context): Pair<Long, String>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val am = ctx.getSystemService(ActivityManager::class.java) ?: return null
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val seen = prefs.getLong(KEY_EXIT_SEEN, 0L)
            val info = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull() ?: return null
            if (info.timestamp <= seen) return null
            prefs.edit().putLong(KEY_EXIT_SEEN, info.timestamp).commit()
            // First run of this code: just note where Android's record is up to, show nothing old.
            if (seen == 0L) return null
            val title = when (info.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "VRC-A NATIVE CRASH"
                ApplicationExitInfo.REASON_ANR -> "VRC-A NOT RESPONDING"
                else -> return null
            }
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            header(pw, title, ctx, info.timestamp)
            pw.println("Reason: ${info.description.orEmpty()}")
            pw.println()
            info.traceInputStream?.use { pw.print(it.bufferedReader().readText().take(MAX_CHARS)) }
            pw.flush()
            info.timestamp to sw.toString()
        }.getOrNull()
    }

    private const val MAX_CHARS = 80_000
}
