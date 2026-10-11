package com.vrca.speech

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import java.util.Locale

/**
 * Voice to text after Android closes VRC-A on its own (low memory next to VRChat, a crash).
 * Everything else comes back by itself (FeatureSessionStore, warm resume: the screen and
 * toggles look untouched), so dictation was the one thing that quietly stayed off
 * (user-reported: "the model turns itself off" after a long time in the background and a
 * lag spike). Android only lets a mic start while VRC-A is on screen, so it's put back then
 * (within [RESUME_WINDOW_MS]), and the card says why it stopped, from Android's own record
 * of how the process ended (ApplicationExitInfo, Android 11+).
 *
 * "on" is set while a session runs and cleared by every stop inside the process, so finding
 * it set at the next process start means that process died mid-dictation. A swipe clears it
 * (FeatureSessionStore.disarm), and a reboot never brings it back (boot count).
 */
object DictationRestore {
    private const val KEY_ON = "restore_on"
    private const val KEY_PID = "restore_pid"
    private const val KEY_BOOT = "restore_boot"
    private const val KEY_LOCAL = "restore_local"
    private const val KEY_AT = "restore_stopped_at"
    private const val KEY_WHY = "restore_why"
    private const val KEY_LOW_MEM = "restore_low_memory"
    private const val KEY_RESUME = "restore_resume"
    private val KEYS = listOf(KEY_ON, KEY_PID, KEY_BOOT, KEY_LOCAL, KEY_AT, KEY_WHY, KEY_LOW_MEM, KEY_RESUME)

    /** Back on only if VRC-A is opened within this long of the stop (never hours later). */
    const val RESUME_WINDOW_MS = 30 * 60_000L
    /** Older stops aren't worth mentioning any more. */
    private const val FORGET_MS = 12 * 60 * 60_000L

    /**
     * A session that ended with its process: when (wall clock), [why] (a clause: "VRC-A
     * crashed"), whether to put it back, and [fresh] = found at this process start (not
     * already reported by an earlier one).
     */
    class Stopped(
        val atMs: Long, val why: String, val lowMemory: Boolean,
        val local: Boolean, val resume: Boolean, val fresh: Boolean,
    )

    private class Exit(val atMs: Long, val why: String, val lowMemory: Boolean, val resume: Boolean)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(SpeechPacks.PREFS, Context.MODE_PRIVATE)

    private fun bootCount(ctx: Context): Int =
        runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)

    /** A session started in this process (replaces an older stop not put back yet). */
    fun started(ctx: Context, local: Boolean) {
        runCatching {
            val e = prefs(ctx).edit()
            KEYS.forEach { e.remove(it) }
            e.putBoolean(KEY_ON, true).putInt(KEY_PID, android.os.Process.myPid())
                .putInt(KEY_BOOT, bootCount(ctx)).putBoolean(KEY_LOCAL, local).apply()
        }
    }

    /** The session ended inside the process (Stop, an error): nothing to put back. */
    fun ended(ctx: Context) {
        runCatching {
            val p = prefs(ctx)
            if (p.getBoolean(KEY_ON, false)) p.edit().putBoolean(KEY_ON, false).apply()
        }
    }

    /** Forget it all: a swipe, a tap on the mic, or it was put back. */
    fun clear(ctx: Context) {
        runCatching {
            val p = prefs(ctx)
            if (KEYS.none { p.contains(it) }) return // never used (phone builds): don't write
            val e = p.edit()
            KEYS.forEach { e.remove(it) }
            e.apply()
        }
    }

    /**
     * At process start: the dictation an earlier process ended in, or null. A session still
     * marked "on" becomes a stop with Android's reason (once); a stop not put back yet is
     * kept, so it survives another restart before VRC-A is opened.
     */
    fun check(ctx: Context): Stopped? = runCatching {
        val p = prefs(ctx)
        if (!p.getBoolean(KEY_ON, false) && !p.contains(KEY_AT)) return null
        if (p.getInt(KEY_PID, -1) == android.os.Process.myPid()) return null // this process's own
        if (p.getInt(KEY_BOOT, -1) != bootCount(ctx)) { clear(ctx); return null } // headset restarted
        var fresh = false
        if (p.getBoolean(KEY_ON, false)) {
            val exit = exitReason(ctx, p.getInt(KEY_PID, -1))
            p.edit().putBoolean(KEY_ON, false)
                .putLong(KEY_AT, exit?.atMs ?: System.currentTimeMillis())
                .putString(KEY_WHY, exit?.why ?: "Android closed VRC-A")
                .putBoolean(KEY_LOW_MEM, exit?.lowMemory == true)
                .putBoolean(KEY_RESUME, exit?.resume != false)
                .apply()
            fresh = true
        }
        val at = p.getLong(KEY_AT, 0L)
        val age = System.currentTimeMillis() - at
        if (at <= 0L || age > FORGET_MS || age < -60_000L) { clear(ctx); return null }
        Stopped(
            atMs = at, why = p.getString(KEY_WHY, null) ?: "Android closed VRC-A",
            lowMemory = p.getBoolean(KEY_LOW_MEM, false), local = p.getBoolean(KEY_LOCAL, false),
            resume = p.getBoolean(KEY_RESUME, true) && age <= RESUME_WINDOW_MS, fresh = fresh,
        )
    }.getOrNull()

    /** How process [pid] ended, in words, from Android's exit record (null before Android 11). */
    private fun exitReason(ctx: Context, pid: Int): Exit? =
        if (pid > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching { exitReason30(ctx, pid) }.getOrNull() else null

    @RequiresApi(Build.VERSION_CODES.R)
    private fun exitReason30(ctx: Context, pid: Int): Exit? {
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return null
        val e = am.getHistoricalProcessExitReasons(ctx.packageName, pid, 1).firstOrNull() ?: return null
        val kb = if (e.pss > 0) e.pss else e.rss
        val used = if (kb > 0) " (it was using ${size(kb)})" else ""
        val why = when (e.reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> "the headset ran low on memory and closed VRC-A$used"
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> "VRC-A crashed"
            ApplicationExitInfo.REASON_ANR -> "VRC-A froze and Android closed it"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Android closed VRC-A for using too much in the background"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "the microphone permission was changed"
            ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED -> "VRC-A was force-stopped"
            ApplicationExitInfo.REASON_PACKAGE_UPDATED, ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "VRC-A was updated"
            // Anything else (a plain kill signal, …): Android's own words, for the next report.
            else -> "Android closed VRC-A" + (e.description?.trim()?.takeIf { it.isNotEmpty() }?.let { " (${it.take(60)})" } ?: "")
        }
        // Force-stopped on purpose: leave it off.
        val resume = e.reason != ApplicationExitInfo.REASON_USER_REQUESTED && e.reason != ApplicationExitInfo.REASON_USER_STOPPED
        return Exit(e.timestamp, why, e.reason == ApplicationExitInfo.REASON_LOW_MEMORY, resume)
    }

    private fun size(kb: Long): String {
        val mb = kb / 1024
        return if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024f) else "$mb MB"
    }
}
