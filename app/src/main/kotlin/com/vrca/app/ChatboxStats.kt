package com.vrca.app

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.atomic.AtomicLong

/**
 * Lifetime chatbox-send counter, shown on the boot screen ("You've sent N
 * chatbox updates"). Counts every non-blank /chatbox/input send at the
 * [com.vrca.osc.VrcaOsc.sendOscMessage] chokepoint — manual, pinned, cycle,
 * music and realtime sends all route through it (typing signals and blank
 * clears are excluded).
 *
 * Increments are in-memory ([AtomicLong]) and flushed to SharedPreferences
 * every [FLUSH_EVERY] sends — music updates fire once per second, so a
 * per-send disk write would be wasteful. Worst case a process kill loses the
 * last <25 sends, which is irrelevant for a vanity counter. [attach] is called
 * once from [VrcaApplication.onCreate]; increments before attach are kept in
 * memory and folded in on the next flush.
 */
object ChatboxStats {
    private const val PREFS = "vrca_stats"
    private const val KEY_SENDS = "chatbox_sends"
    private const val FLUSH_EVERY = 25L

    private val count = AtomicLong(0)
    private val unflushed = AtomicLong(0)

    @Volatile
    private var prefs: SharedPreferences? = null

    fun attach(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Fold any pre-attach increments into the persisted base.
        count.addAndGet(p.getLong(KEY_SENDS, 0L))
        prefs = p
    }

    fun increment() {
        val total = count.incrementAndGet()
        if (unflushed.incrementAndGet() >= FLUSH_EVERY) {
            unflushed.set(0)
            prefs?.edit()?.putLong(KEY_SENDS, total)?.apply()
        }
    }

    /** Lifetime total (persisted base + this process's unflushed increments). */
    fun totalSends(): Long = count.get()
}
