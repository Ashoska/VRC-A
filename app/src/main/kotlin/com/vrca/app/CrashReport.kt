package com.vrca.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
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
        pw.println("Thread: ${t.name}")
        pw.println()
        e.printStackTrace(pw)
        pw.flush()
        // commit(), not apply(): apply() writes later, and the process is killed before then.
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TEXT, sw.toString().take(MAX_CHARS)).commit()
    }

    /**
     * Run once at launch (VrcaApplication.onCreate, main thread): is a crash report waiting? A
     * crash Android recorded but the handler couldn't (native code, not responding) becomes one.
     */
    fun checkAtLaunch(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_TEXT, "").isNullOrBlank()) {
            fromExitInfo(ctx)?.let { prefs.edit().putString(KEY_TEXT, it.take(MAX_CHARS)).commit() }
        }
        safeStart = !prefs.getString(KEY_TEXT, "").isNullOrBlank()
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

    /** "Start VRC-A": clear the report and run the start-up work that was held. Main thread. */
    fun release(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_TEXT).commit()
        safeStart = false
        val held = synchronized(onRelease) { onRelease.toList().also { onRelease.clear() } }
        held.forEach { runCatching(it) }
    }

    /**
     * A short line for the crash screen: what failed and where, e.g. "NullPointerException in
     * HomePage.kt:212" (the first frame in VRC-A's own code), or the kind of failure.
     */
    fun summary(text: String): String {
        if (text.startsWith("=== VRC-A NOT RESPONDING")) return "VRC-A stopped responding"
        if (text.startsWith("=== VRC-A NATIVE CRASH")) return "A crash in native code"
        val lines = text.lines()
        val ex = lines.firstOrNull { Regex("""^[\w.$]+(Exception|Error|Throwable)\b""").containsMatchIn(it.trim()) }
            ?.trim()?.substringBefore(':')?.substringAfterLast('.')
        val at = lines.firstOrNull { it.trim().startsWith("at com.vrca.") }
            ?.substringAfterLast('(')?.substringBefore(')')
        return when {
            ex != null && at != null -> "$ex in $at"
            ex != null -> ex
            else -> "Unknown error"
        }
    }

    /** When the crash happened, from the report's "Time:" line ("" if missing). */
    fun time(text: String): String =
        text.lineSequence().firstOrNull { it.startsWith("Time: ") }?.removePrefix("Time: ").orEmpty()

    /**
     * Saves [text] as a .txt in the device's Downloads folder, returning its name, or null if it
     * couldn't. Android 10+ only (MediaStore, no permission needed); older phones use the system
     * file picker instead (CrashScreen).
     */
    fun saveToDownloads(ctx: Context, text: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val name = fileName()
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: return null
            name
        }.getOrNull()
    }

    fun fileName(): String =
        "VRC-A crash " + SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(Date()) + ".txt"

    private fun header(pw: PrintWriter, title: String, ctx: Context, at: Long = System.currentTimeMillis()) {
        pw.println("=== $title ===")
        pw.println("Time: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(at)))
        pw.println("Process: ${Process.myPid()}")
        pw.println("SDK: ${Build.VERSION.SDK_INT}")
        pw.println("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        pw.println("AppId: ${ctx.packageName}")
        pw.println("VersionName: ${BuildConfig.VERSION_NAME}")
        pw.println("VersionCode: ${BuildConfig.VERSION_CODE}")
    }

    /** The newest native crash / not-responding exit since the last one seen, as report text. */
    private fun fromExitInfo(ctx: Context): String? {
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
            sw.toString()
        }.getOrNull()
    }

    private const val MAX_CHARS = 80_000
}
