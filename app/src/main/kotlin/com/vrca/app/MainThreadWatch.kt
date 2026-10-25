package com.vrca.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File

/**
 * Records main-thread freezes during the first [WATCH_MS] of each process, so a freeze on the
 * headset can be traced without adb (Settings → Debug → "Main-thread stalls"). A background
 * thread pings the main thread every [PING_MS]; when a ping goes unanswered for [STALL_MS] it
 * saves what the main thread is running (its top 24 frames), again at 2 s and 5 s if it's still
 * stuck, and the total once it answers (250 ms: the first version's 400 ms missed shorter hitches).
 * Written to disk straight away, so a stall that ends in "isn't responding" → Close is still
 * there on the next open. Added for the power-on
 * "VRC-A isn't responding" reports and the pause before Home after boot.
 */
object MainThreadWatch {
    private const val WATCH_MS = 120_000L
    private const val PING_MS = 100L
    private const val STALL_MS = 250L
    private const val MAX_FILE_BYTES = 48 * 1024L
    private const val FILE = "main_stalls.txt"

    @Volatile private var lastBeat = 0L
    @Volatile private var started = false

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        val beat = Runnable { lastBeat = SystemClock.uptimeMillis() }
        val processStart = SystemClock.uptimeMillis()
        lastBeat = processStart
        Thread({
            var stallStart = 0L
            var snapshots = 0
            while (SystemClock.uptimeMillis() - processStart < WATCH_MS || stallStart != 0L) {
                main.post(beat)
                try { Thread.sleep(PING_MS) } catch (_: InterruptedException) { return@Thread }
                val now = SystemClock.uptimeMillis()
                val silent = now - lastBeat
                if (silent >= STALL_MS) {
                    if (stallStart == 0L) { stallStart = lastBeat; snapshots = 0 }
                    val due = when (snapshots) { 0 -> true; 1 -> silent >= 2_000; 2 -> silent >= 5_000; else -> false }
                    if (due) {
                        snapshots++
                        val at = (stallStart - processStart) / 1000.0
                        write(app, "stall at %.1fs after start, stuck %d ms so far:\n%s".format(at, silent, mainStack()))
                    }
                } else if (stallStart != 0L) {
                    write(app, "  -> lasted %d ms\n".format(lastBeat - stallStart))
                    stallStart = 0L
                }
            }
        }, "vrca-main-watch").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }.start()
    }

    /** Adds a line to the log (e.g. a boot timing worth comparing between builds). */
    fun note(ctx: Context, text: String) = write(ctx.applicationContext, "note: $text")

    /** The saved stalls, newest last (empty if none). */
    fun read(ctx: Context): String = runCatching { File(ctx.filesDir, FILE).readText() }.getOrDefault("")

    fun clear(ctx: Context) { runCatching { File(ctx.filesDir, FILE).delete() } }

    private fun mainStack(): String {
        // Full class names: release builds are shrunk (R8), so names like "hb.p1" only translate
        // back with the package (mapping.txt, kept per CI build as the headset-mapping artifact).
        // The top frames say where it was blocked; deeper ones what it was doing.
        return Looper.getMainLooper().thread.stackTrace.take(24)
            .joinToString("\n") { "    ${it.className}.${it.methodName}:${it.lineNumber}" }
    }

    private fun write(ctx: Context, text: String) {
        runCatching {
            val f = File(ctx.filesDir, FILE)
            if (f.length() > MAX_FILE_BYTES) f.writeText(f.readText().takeLast((MAX_FILE_BYTES / 2).toInt()))
            val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            f.appendText("[$stamp] $text\n")
        }
    }
}
