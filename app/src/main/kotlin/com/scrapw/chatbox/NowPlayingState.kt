package com.scrapw.chatbox

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

data class NowPlayingSnapshot(
    val listenerConnected: Boolean = false,
    val activePackage: String = "",
    val detected: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val durationMs: Long = 0L,

    // Raw position snapshot from PlaybackState
    val positionMs: Long = 0L,

    // elapsedRealtime at the moment positionMs was measured
    val positionUpdateTimeMs: Long = 0L,
    val playbackSpeed: Float = 1f,

    // This may be wrong during skips/seek on some players.
    // We may override it in NowPlayingState.update() based on motion.
    val isPlaying: Boolean = false,

    // True when the service marks the current media as a special segment
    // (e.g., Spotify DJ / Ads). While active, we suppress paused inference to avoid flicker.
    val specialActive: Boolean = false
)

object NowPlayingState {
    private val _state = MutableStateFlow(NowPlayingSnapshot())
    val state: StateFlow<NowPlayingSnapshot> = _state.asStateFlow()

    // Heuristic knobs (tuned for your 2s refresh + common media-session jitter)
    private const val MOVING_POS_DELTA_MS = 250L     // position must advance by at least this to be "moving"
    private const val STALLED_POS_DELTA_MS = 60L     // treat <= this as "not moving"
    private const val STALLED_TIME_MS = 1400L        // if not moving for >= this, call it paused

    // YouTube-specific stall tracking to fix false PLAYING state
    private val youtubeStallCountByPackage = HashMap<String, Int>()

    fun update(snapshot: NowPlayingSnapshot) {
        val prev = _state.value

        // Reset motion history if app changed (prevents false "paused" on app switch)
        val samePkg = prev.activePackage == snapshot.activePackage && snapshot.activePackage.isNotBlank()

        // If specialActive is true (DJ/ads), never show paused. This prevents flicker.
        val inferredIsPlaying = if (snapshot.specialActive) {
            true
        } else {
            inferIsPlayingFromMotion(
                prev = prev,
                cur = snapshot,
                samePkg = samePkg
            )
        }

        var finalIsPlaying = inferredIsPlaying

        // Hard override for YouTube pause detection
        if (
            snapshot.activePackage == "com.google.android.youtube" ||
            snapshot.activePackage == "com.google.android.apps.youtube.music"
        ) {
            val sameMeta = prev.title == snapshot.title && prev.artist == snapshot.artist
            val posDelta = abs(snapshot.positionMs - prev.positionMs)

            if (sameMeta && posDelta < 50L) {
                val count = (youtubeStallCountByPackage[snapshot.activePackage] ?: 0) + 1
                youtubeStallCountByPackage[snapshot.activePackage] = count

                if (count >= 2) {
                    finalIsPlaying = false
                }
            } else {
                youtubeStallCountByPackage[snapshot.activePackage] = 0
            }
        } else {
            youtubeStallCountByPackage.clear()
        }

        _state.value = snapshot.copy(isPlaying = finalIsPlaying)
    }

    fun setConnected(connected: Boolean) {
        _state.value = _state.value.copy(listenerConnected = connected)
    }

    // When a media notification/session disappears, do NOT blank the UI.
    // Keep the last known title/artist and simply mark it paused.
    fun pauseIfActivePackage(pkg: String) {
        val cur = _state.value
        if (cur.activePackage == pkg && (cur.title.isNotBlank() || cur.artist.isNotBlank())) {
            _state.value = cur.copy(
                detected = true,
                playbackSpeed = 0f,
                isPlaying = false,
                specialActive = false
            )
        }
    }

    fun clearIfActivePackage(pkg: String) {
        val cur = _state.value
        if (cur.activePackage == pkg) {
            _state.value = cur.copy(
                detected = false,
                title = "",
                artist = "",
                durationMs = 0L,
                positionMs = 0L,
                positionUpdateTimeMs = 0L,
                playbackSpeed = 1f,
                isPlaying = false,
                specialActive = false
            )
        }
    }

    private fun inferIsPlayingFromMotion(
        prev: NowPlayingSnapshot,
        cur: NowPlayingSnapshot,
        samePkg: Boolean
    ): Boolean {
        // If nothing detected, we're not playing.
        if (!cur.detected) return false

        // If we don't have timing info, fall back to whatever the service reported.
        if (cur.positionUpdateTimeMs <= 0L) return cur.isPlaying

        // If we can't compare to a previous sample (first sample or app changed), fall back.
        if (!samePkg || prev.positionUpdateTimeMs <= 0L) return cur.isPlaying

        val dt = cur.positionUpdateTimeMs - prev.positionUpdateTimeMs
        if (dt <= 0L) return cur.isPlaying

        val dp = cur.positionMs - prev.positionMs
        val adp = abs(dp)

        // If speed is 0, assume paused (some services report this reliably)
        if (cur.playbackSpeed == 0f) return false

        // If position moved enough, it's playing.
        if (adp >= MOVING_POS_DELTA_MS) return true

        // If position is basically unchanged for long enough, call it paused.
        if (adp <= STALLED_POS_DELTA_MS && dt >= STALLED_TIME_MS) return false

        // Otherwise don't fight the service too hard.
        return cur.isPlaying
    }
}
