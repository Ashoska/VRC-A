package com.vrca.speech

import kotlin.math.sqrt

/**
 * Tells voiced speech from breathing, clicks and broadband noise, per 32 ms window
 * (512 samples at 16 kHz). Voiced speech has a pitch — the waveform repeats every
 * 2.5–14 ms (70–400 Hz) — so its normalised autocorrelation peaks high at that lag;
 * breath and noise don't repeat. Level-independent, so it works on a quiet mic.
 *
 * Used to keep "Hearing you…" (and VRChat's typing dots) for real speech, and to skip
 * decoding VAD segments that hold no voice: the VAD alone fired on breaths at the
 * headset mic, flickering the UI and spending the recognizer on silence.
 */
object Voicing {
    /** A phrase needs ~0.1 s of voicing (3 windows) to count as speech. */
    const val MIN_VOICED_WINDOWS = 3
    private const val WINDOW = 512
    private const val MIN_LAG = 40   // 400 Hz
    private const val MAX_LAG = 228  // ~70 Hz
    private const val THRESHOLD = 0.6f
    // 2nd-order Butterworth high-pass, 250 Hz at 16 kHz (RBJ biquad, a0 normalised).
    private const val HB0 = 0.93293216
    private const val HB1 = -1.86586431
    private const val HB2 = 0.93293216
    private const val HA1 = -1.86136115
    private const val HA2 = 0.87036748

    /** Whether one window (any length ≥ MAX_LAG×2) is voiced. */
    fun isVoiced(x: FloatArray, from: Int = 0, len: Int = x.size - from): Boolean {
        if (len < MAX_LAG * 2) return false
        // High-pass at 250 Hz first: low rumble (breath blowing straight onto the mic) is
        // smooth enough to look "periodic" to a raw autocorrelation, while a voice keeps
        // its pitch in the harmonics above 250 Hz.
        val y = FloatArray(len)
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        var energy = 0.0
        for (i in 0 until len) {
            val v = x[from + i].toDouble()
            val o = HB0 * v + HB1 * x1 + HB2 * x2 - HA1 * y1 - HA2 * y2
            x2 = x1; x1 = v; y2 = y1; y1 = o
            y[i] = o.toFloat(); energy += o * o
        }
        if (sqrt(energy / len) < 1e-4) return false // (near) digital silence
        // The pitch shows as a real PEAK in the autocorrelation (it falls, then rises
        // again at the period); noise just decays. Interior local maxima only.
        val r = DoubleArray(MAX_LAG + 2)
        for (lag in MIN_LAG - 1..MAX_LAG + 1) {
            var num = 0.0; var e0 = 0.0; var e1 = 0.0
            for (i in 0 until len - lag) {
                val a = y[i]; val b = y[i + lag]
                num += a * b; e0 += a * a; e1 += b * b
            }
            r[lag] = if (e0 > 0 && e1 > 0) num / sqrt(e0 * e1) else 0.0
        }
        var best = 0.0
        for (lag in MIN_LAG..MAX_LAG) if (r[lag] > best && r[lag] >= r[lag - 1] && r[lag] >= r[lag + 1]) best = r[lag]
        return best >= THRESHOLD
    }

    /** Voiced 32 ms windows in a whole segment. */
    fun voicedWindows(x: FloatArray): Int {
        var count = 0; var i = 0
        while (i + WINDOW <= x.size) { if (isVoiced(x, i, WINDOW)) count++; i += WINDOW }
        return count
    }
}
