package com.vrca.nowplaying

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
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

    // True when the active package is in a known "special" state (e.g. Spotify DJ or Ad).
    // Set by NowPlayingListenerService; used downstream to suppress flicker.
    val specialActive: Boolean = false,

    // For ads: the player's own ad index parsed from the original metadata
    // (e.g. "1 of 1", "2 of 3") BEFORE the title was redacted to "AD". Blank
    // when the player didn't expose one. Used to render "Ad 1 of 1".
    val adInfo: String = "",

    // True for a YouTube live stream (non-seekable session with unknown/zero
    // duration). Distinguished from an ad (non-seekable but FINITE duration) so
    // the chatbox shows a LIVE marker instead of a broken progress bar.
    val isLive: Boolean = false
)

object NowPlayingState {
    private val _state = MutableStateFlow(NowPlayingSnapshot())
    val state: StateFlow<NowPlayingSnapshot> = _state.asStateFlow()

    // --- Persistence of the last-known track ---------------------------------
    // The snapshot lives only in process memory, so after an OS-initiated process
    // kill + headless ViewModel revival (KeepAliveService) there is no active
    // MediaSession to re-push a PAUSED track — the chatbox NowPlaying line just
    // blanked out. We persist the last detected, non-special track to disk and
    // re-seed it (as paused) on process start so it survives revival / cold start
    // instead of disappearing. Future-proof: keyed on whatever media app was
    // active, not Spotify-specific.
    private const val PREFS = "vrca_nowplaying"
    private const val KEY_SNAPSHOT = "last_snapshot"
    @Volatile private var prefs: SharedPreferences? = null

    /**
     * Seed the last-known track from disk (as paused) on process start so a
     * headless revival or plain cold start shows the previous track immediately
     * instead of blanking until a live MediaSession re-pushes. Idempotent; only
     * restores while the in-memory state is still blank (never clobbers a live one).
     * Call once, as early as possible (Application.onCreate).
     */
    fun attach(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        if (_state.value.detected) return
        val raw = p.getString(KEY_SNAPSHOT, null) ?: return
        try {
            val o = JSONObject(raw)
            val title = o.optString("title")
            val artist = o.optString("artist")
            if (title.isBlank() && artist.isBlank()) return
            _state.value = _state.value.copy(
                activePackage = o.optString("activePackage"),
                detected = true,
                title = title,
                artist = artist,
                durationMs = o.optLong("durationMs"),
                positionMs = o.optLong("positionMs"),
                // elapsedRealtime resets on reboot; re-anchor to "now" and mark
                // paused so the downstream builder renders the frozen snapshot.
                positionUpdateTimeMs = SystemClock.elapsedRealtime(),
                playbackSpeed = 0f,
                isPlaying = false,
                specialActive = false,
                adInfo = ""
            )
        } catch (_: Throwable) { }
    }

    private fun persist(s: NowPlayingSnapshot) {
        val p = prefs ?: return
        // Only persist real tracks — never ads / DJ / special windows (restoring
        // "AD" on next launch would be nonsense).
        if (!s.detected || s.specialActive || s.adInfo.isNotBlank() || s.isLive) return
        if (s.title.isBlank() && s.artist.isBlank()) return
        try {
            val o = JSONObject()
                .put("activePackage", s.activePackage)
                .put("title", s.title)
                .put("artist", s.artist)
                .put("durationMs", s.durationMs)
                .put("positionMs", s.positionMs)
            p.edit().putString(KEY_SNAPSHOT, o.toString()).apply()
        } catch (_: Throwable) { }
    }

    private fun clearPersisted() {
        try { prefs?.edit()?.remove(KEY_SNAPSHOT)?.apply() } catch (_: Throwable) { }
    }

    // Heuristic knobs (tuned for 0.5s refresh + common media-session jitter)
    private const val MOVING_POS_DELTA_MS = 250L     // position must advance by at least this to be "moving"
    private const val STALLED_POS_DELTA_MS = 60L     // treat <= this as "not moving"
    private const val STALLED_TIME_MS = 1400L        // if not moving for >= this, call it paused

    // YouTube-specific stall detection: 2 consecutive cycles with no position movement
    private val YOUTUBE_PACKAGES = setOf(
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music"
    )
    private const val YOUTUBE_STALL_DELTA_MS = 50L   // position must NOT change more than this
    private const val YOUTUBE_STALL_CYCLES_NEEDED = 2

    // State for YouTube stall tracking (mutable object-level state)
    private var ytLastMetaKey: String = ""
    private var ytLastPosMs: Long = -1L
    private var ytStallCycles: Int = 0

    fun update(snapshot: NowPlayingSnapshot) {
        val prev = _state.value

        // A blank-metadata push (the media app being closed / its MediaSession torn
        // down often fires onMetadataChanged(null) or a poll reads empty metadata)
        // must NOT wipe the chatbox. YouTube tears its session down on close and emits
        // such a frame; Spotify keeps its session alive, which is the only reason the
        // text "disappears on YouTube but not Spotify". Freeze the last known track as
        // paused instead of clearing — matching pauseIfActivePackage's keep-last
        // behavior. A genuine hard stop still goes through clearIfActivePackage().
        if (!snapshot.detected && prev.detected && (prev.title.isNotBlank() || prev.artist.isNotBlank())) {
            _state.value = prev.copy(
                listenerConnected = snapshot.listenerConnected || prev.listenerConnected,
                playbackSpeed = 0f,
                isPlaying = false,
                specialActive = false,
                adInfo = ""
            )
            persist(_state.value)
            return
        }

        // Reset motion history if app changed (prevents false "paused" on app switch)
        val samePkg = prev.activePackage == snapshot.activePackage && snapshot.activePackage.isNotBlank()

        var inferredIsPlaying = inferIsPlayingFromMotion(
            prev = prev,
            cur = snapshot,
            samePkg = samePkg
        )

        // The YouTube VIDEO app keeps reporting STATE_PLAYING + speed=1f even when
        // paused, so the only reliable pause signal THERE is the raw position stalling
        // for 2 consecutive cycles. YouTube MUSIC is the opposite case: its raw position
        // is unreliable — it can ADVANCE while paused and FREEZE while playing, which
        // INVERTED the motion/stall detector (playing showed paused, paused showed
        // playing). YT Music's reported PlaybackState.state IS honest, so trust it
        // directly and skip every position heuristic for it.
        if (snapshot.activePackage in YOUTUBE_PACKAGES && snapshot.detected) {
            if (snapshot.activePackage == "com.google.android.apps.youtube.music") {
                inferredIsPlaying = snapshot.isPlaying
                ytLastMetaKey = ""
                ytLastPosMs = -1L
                ytStallCycles = 0
            } else {
                val metaKey = "${snapshot.title.trim()}|${snapshot.artist.trim()}|${snapshot.durationMs}"
                val metaChanged = metaKey != ytLastMetaKey
                val posDelta = abs(snapshot.positionMs - ytLastPosMs)

                if (metaChanged || !samePkg) {
                    // New track or new app: reset stall counter, treat as playing
                    ytLastMetaKey = metaKey
                    ytLastPosMs = snapshot.positionMs
                    ytStallCycles = 0
                } else if (ytLastPosMs >= 0L && posDelta < YOUTUBE_STALL_DELTA_MS) {
                    ytStallCycles++
                    ytLastPosMs = snapshot.positionMs
                } else {
                    // Position moved: clear stall, definitely playing
                    ytStallCycles = 0
                    ytLastPosMs = snapshot.positionMs
                }

                if (ytStallCycles >= YOUTUBE_STALL_CYCLES_NEEDED) {
                    inferredIsPlaying = false
                }
            }
        } else if (snapshot.activePackage !in YOUTUBE_PACKAGES) {
            // Reset YouTube state when not on a YouTube app
            ytLastMetaKey = ""
            ytLastPosMs = -1L
            ytStallCycles = 0
        }

        _state.value = snapshot.copy(isPlaying = inferredIsPlaying)
        persist(_state.value)
    }

    fun setConnected(connected: Boolean) {
        _state.value = _state.value.copy(listenerConnected = connected)
    }

    /**
     * When a media notification/session disappears, DON'T blank the UI.
     * Keep the last known title/artist and simply mark it as paused.
     * This prevents the UI/OSC text from randomly disappearing when players hide notifications.
     */
    fun pauseIfActivePackage(pkg: String) {
        val cur = _state.value
        if (cur.activePackage == pkg && (cur.title.isNotBlank() || cur.artist.isNotBlank())) {
            _state.value = cur.copy(
                detected = true,
                // Keep title/artist/duration/position as the last known state
                playbackSpeed = 0f,
                isPlaying = false
            )
            persist(_state.value)
        } else if (cur.activePackage == pkg) {
            // If we have nothing meaningful, clear like before.
            clearIfActivePackage(pkg)
        }
    }

    /**
     * Hard clear (used when you really want to remove the "Now Playing" info).
     */
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
                isPlaying = false
            )
            // A genuine clear means there's no track to restore on next launch.
            clearPersisted()
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
