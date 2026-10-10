package com.vrca.speech

/**
 * How easily dictation picks up speech (voice-language picker; global, live-switchable).
 * Normal is the default and the balance we recommend; the other two trade the other way.
 *
 * - [vadThreshold]: Silero speech probability needed to start a phrase (higher = stricter).
 * - Short weak blips: a phrase with fewer than [minVoiced] voiced 32 ms windows AND shorter
 *   than [blipMaxSec] is dropped as noise (breath, click) without decoding. Longer quiet
 *   phrases always reach the model, which simply returns nothing for real noise.
 * - [maxGain]: the mic boost limit (it only learns from voiced windows). Every phrase is
 *   evened to the same loudness before decoding, so this changes what gets PICKED UP, not
 *   accuracy; Normal keeps the 6x that tested breath-free on the headset.
 *
 * Noisy room = the strict settings from the breathing fix (VAD 0.6, every unvoiced
 * phrase dropped). Normal relaxed them because soft words were being missed.
 */
enum class MicSensitivity(
    val label: String,
    val vadThreshold: Float,
    val minVoiced: Int,
    val blipMaxSec: Float,
    val maxGain: Float,
) {
    SOFT("Soft voice", 0.45f, 2, 0.5f, 10f),
    NORMAL("Normal", 0.5f, 3, 0.8f, 6f),
    NOISY("Noisy room", 0.6f, 3, Float.MAX_VALUE, 6f),
}
