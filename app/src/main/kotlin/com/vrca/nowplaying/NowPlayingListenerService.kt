// app/src/main/kotlin/com/vrca/NowPlayingListenerService.kt
package com.vrca.nowplaying

import android.app.Notification
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NowPlayingListenerService : NotificationListenerService() {

    private val allowedPackages = setOf(
        "com.spotify.music",
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.apple.android.music",
        "deezer.android.app",
        "com.soundcloud.android",
        "com.amazon.mp3",
        "com.bandcamp.android"
    )

    private data class ControllerEntry(
        val controller: MediaController,
        val callback: MediaController.Callback,
        val token: MediaSession.Token
    )

    private val controllersByPackage = HashMap<String, ControllerEntry>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pollRunnablesByPackage = HashMap<String, Runnable>()

    private val lastTitleArtistByPackage = HashMap<String, Pair<String, String>>()
    private val lastPushElapsedByPackage = HashMap<String, Long>()
    private val lastPlayingStateByPackage = HashMap<String, Boolean>()

    // Spotify ad/DJ: only activate special window when metadata explicitly says "ad" or "dj".
    // Stall-based detection is removed entirely — it caused false positives on normal skips.
    private val specialUntilElapsedByPackage = HashMap<String, Long>()

    // Debounce: when metadata changes rapidly (skip/seek), wait before pushing to avoid flicker.
    private val lastMetaChangeElapsedByPackage = HashMap<String, Long>()

    private fun markSpecialWindow(pkg: String, windowMs: Long) {
        specialUntilElapsedByPackage[pkg] = SystemClock.elapsedRealtime() + windowMs
    }

    private fun isSpecialWindowActive(pkg: String): Boolean {
        val until = specialUntilElapsedByPackage[pkg] ?: 0L
        return SystemClock.elapsedRealtime() < until
    }

    private fun clearSpecialWindow(pkg: String) {
        specialUntilElapsedByPackage.remove(pkg)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        NowPlayingState.setConnected(true)

        try {
            activeNotifications?.forEach { sbn -> onNotificationPosted(sbn) }
        } catch (_: Throwable) { }

        scanActiveMediaSessions()
    }

    private fun scanActiveMediaSessions() {
        try {
            val msm = getSystemService(android.media.session.MediaSessionManager::class.java)
                ?: return
            val controllers = msm.getActiveSessions(
                android.content.ComponentName(this, NowPlayingListenerService::class.java)
            ) ?: return
            for (controller in controllers) {
                val pkg = controller.packageName ?: continue
                if (allowedPackages.isNotEmpty() && pkg !in allowedPackages) continue
                val token = controller.sessionToken ?: continue
                ensureControllerForPackage(pkg, token)
            }
        } catch (_: Throwable) { }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        NowPlayingState.setConnected(false)
        teardownAllControllers()
        stopAllPolls()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val pkg = sbn.packageName ?: return
        val notif = sbn.notification ?: return
        val extras = notif.extras ?: return

        if (allowedPackages.isNotEmpty() && pkg !in allowedPackages) return

        val token = getMediaSessionToken(extras) ?: return

        ensureControllerForPackage(pkg, token)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return

        teardownController(pkg)
        stopPoll(pkg)
        NowPlayingState.pauseIfActivePackage(pkg)
    }

    // ---- MediaController wiring ----

    private fun ensureControllerForPackage(pkg: String, token: MediaSession.Token) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return

        val existing = controllersByPackage[pkg]
        if (existing != null && tokensEqual(existing.token, token)) {
            pushSnapshot(pkg, existing.controller.metadata, existing.controller.playbackState, existing.controller)
            return
        }

        teardownController(pkg)
        stopPoll(pkg)

        try {
            val controller = MediaController(this, token)

            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    pushSnapshot(pkg, controller.metadata, state, controller)
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    pushSnapshot(pkg, metadata, controller.playbackState, controller)
                }

                override fun onSessionDestroyed() {
                    teardownController(pkg)
                    stopPoll(pkg)
                    NowPlayingState.pauseIfActivePackage(pkg)
                }
            }

            controller.registerCallback(cb)
            controllersByPackage[pkg] = ControllerEntry(controller, cb, token)

            val initState = controller.playbackState
            lastPlayingStateByPackage[pkg] = initState?.state == PlaybackState.STATE_PLAYING

            pushSnapshot(pkg, controller.metadata, initState, controller)
        } catch (_: Throwable) {
        }
    }

    private fun teardownController(pkg: String) {
        val entry = controllersByPackage.remove(pkg) ?: return
        try {
            entry.controller.unregisterCallback(entry.callback)
        } catch (_: Throwable) {
        }
        lastPlayingStateByPackage.remove(pkg)
    }

    private fun teardownAllControllers() {
        val keys = controllersByPackage.keys.toList()
        keys.forEach { teardownController(it) }
        controllersByPackage.clear()
    }

    private fun tokensEqual(a: MediaSession.Token, b: MediaSession.Token): Boolean {
        return (a == b) || (a.toString() == b.toString() && a.hashCode() == b.hashCode())
    }

    // ---- Ad/DJ classification (Spotify only, metadata-based only) ----

    private enum class SpecialKind { DJ, AD }

    /**
     * Only classifies based on explicit metadata strings from Spotify.
     * No stall-based heuristics — those caused false positives on song skips.
     */
    private fun classifySpecial(pkg: String, title: String, artist: String): SpecialKind? {
        val t = title.trim().lowercase()
        val a = artist.trim().lowercase()

        // Player-agnostic ad detection. The now-playing UI for an ad surfaces the
        // ADVERTISER in the title/artist — e.g. "Advertisement • 1 of 1 — IONOS UK"
        // (Spotify now puts the brand in the artist field). Showing that would leak
        // brand/targeting info, so ANY title or artist beginning with "advertisement"
        // is treated as an ad regardless of player or which field the brand lands in.
        // A normal song's now-playing line doesn't start with that word, so false
        // positives are very unlikely.
        if (t.startsWith("advertisement") || a.startsWith("advertisement")) return SpecialKind.AD

        // Beyond that universal case, only Spotify gets the looser keyword matching —
        // other players legitimately use these words in real song titles.
        if (pkg != "com.spotify.music") return null

        val looksLikeAd =
            t == "ad" ||
                t == "spotify" ||
                t == "spotify ad" ||
                // No longer gated on the artist field: Spotify puts the advertiser
                // brand there during ads, which previously let the ad leak through.
                t.startsWith("advert")

        val looksLikeDj =
            (t == "dj" && (a.isBlank() || a == "spotify")) ||
                t == "spotify dj"

        return when {
            looksLikeAd -> SpecialKind.AD
            looksLikeDj -> SpecialKind.DJ
            else -> null
        }
    }

    // ---- Snapshot push ----

    private fun pushSnapshot(
        pkg: String,
        metadata: MediaMetadata?,
        pb: PlaybackState?,
        controller: MediaController?
    ) {
        var title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        var artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

        val rawPos = pb?.position ?: 0L
        val lastUpdate = pb?.lastPositionUpdateTime ?: 0L
        val speed = pb?.playbackSpeed ?: 1f

        val isPlaying = pb?.state == PlaybackState.STATE_PLAYING

        val snapshotUpdateTime =
            if (lastUpdate > 0L) lastUpdate else SystemClock.elapsedRealtime()

        lastPushElapsedByPackage[pkg] = SystemClock.elapsedRealtime()

        // Track metadata changes for debounce
        val prevTa = lastTitleArtistByPackage[pkg]
        val curTa = title to artist
        val metaChanged = prevTa == null || prevTa.first != curTa.first || prevTa.second != curTa.second
        if (metaChanged) {
            lastTitleArtistByPackage[pkg] = curTa
            lastMetaChangeElapsedByPackage[pkg] = SystemClock.elapsedRealtime()
        }

        // Spotify ad/DJ: only trigger on explicit metadata match
        val special = classifySpecial(pkg, title, artist)
        var adInfo = ""
        if (special != null) {
            when (special) {
                SpecialKind.DJ -> { title = "DJ"; artist = "" }
                SpecialKind.AD -> {
                    // Parse the player's own ad index (e.g. "1 of 1", "2 of 3")
                    // from the ORIGINAL metadata BEFORE we blank it, so the chatbox
                    // can show "Ad 1 of 1" instead of leaking the advertiser brand.
                    adInfo = parseAdIndex("$title $artist")
                    title = "AD"; artist = ""
                }
            }
            markSpecialWindow(pkg, 10_000L)
            if (controller != null) startPollForRealTrack(pkg, controller)
        } else if (isSpecialWindowActive(pkg)) {
            // The ad/DJ segment just ended. As soon as a real track with genuine
            // metadata appears, end the special window IMMEDIATELY — otherwise the
            // downstream builder keeps showing "Ad N" (and hides the progress bar)
            // for the remainder of the 10s window even though a normal song is now
            // playing. While the metadata is still blank (mid-transition), keep the
            // window + polling so the chatbox doesn't flicker to empty/"Paused".
            if (title.isNotBlank() || artist.isNotBlank()) {
                clearSpecialWindow(pkg)
                stopPoll(pkg)
            } else if (pkg == "com.spotify.music" && controller != null) {
                startPollForRealTrack(pkg, controller)
            }
        } else {
            // Normal track — stop polling if we were
            stopPoll(pkg)
        }

        val detected = title.isNotBlank() || artist.isNotBlank()

        NowPlayingState.update(
            NowPlayingSnapshot(
                listenerConnected = true,
                activePackage = pkg,
                detected = detected,
                title = title,
                artist = artist,
                durationMs = duration,
                positionMs = rawPos,
                positionUpdateTimeMs = snapshotUpdateTime,
                playbackSpeed = speed,
                isPlaying = isPlaying,
                specialActive = isSpecialWindowActive(pkg),
                adInfo = adInfo
            )
        )
    }

    private val adIndexRegex = Regex("""(\d+)\s*of\s*(\d+)""", RegexOption.IGNORE_CASE)

    /** Extracts a "X of Y" ad index from the raw ad metadata, or "" if none. */
    private fun parseAdIndex(raw: String): String {
        val m = adIndexRegex.find(raw) ?: return ""
        return "${m.groupValues[1]} of ${m.groupValues[2]}"
    }

    private fun startPollForRealTrack(pkg: String, controller: MediaController) {
        if (pollRunnablesByPackage.containsKey(pkg)) return

        val startAt = SystemClock.elapsedRealtime()
        val maxMs = 10 * 60 * 1000L
        val intervalMs = 500L

        val r = object : Runnable {
            override fun run() {
                val current = controllersByPackage[pkg]?.controller
                if (current != controller) {
                    stopPoll(pkg)
                    return
                }

                val md = runCatching { controller.metadata }.getOrNull()
                val pb = runCatching { controller.playbackState }.getOrNull()

                val t = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
                val a = md?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()

                val last = lastTitleArtistByPackage[pkg]
                val changed = last == null || last.first != t || last.second != a

                val lastPush = lastPushElapsedByPackage[pkg] ?: 0L
                val sincePush = SystemClock.elapsedRealtime() - lastPush

                val nowPlaying = pb?.state == PlaybackState.STATE_PLAYING
                val statePrev = lastPlayingStateByPackage[pkg]
                val stateChanged = statePrev != null && statePrev != nowPlaying

                if (changed || stateChanged || sincePush >= 4000L) {
                    pushSnapshot(pkg, md, pb, controller)
                }
                lastPlayingStateByPackage[pkg] = nowPlaying

                if (SystemClock.elapsedRealtime() - startAt >= maxMs) {
                    stopPoll(pkg)
                    return
                }

                mainHandler.postDelayed(this, intervalMs)
            }
        }

        pollRunnablesByPackage[pkg] = r
        mainHandler.postDelayed(r, intervalMs)
    }

    private fun stopPoll(pkg: String) {
        val r = pollRunnablesByPackage.remove(pkg) ?: return
        mainHandler.removeCallbacks(r)
    }

    private fun stopAllPolls() {
        pollRunnablesByPackage.keys.toList().forEach { stopPoll(it) }
        pollRunnablesByPackage.clear()
    }

    private fun getMediaSessionToken(extras: android.os.Bundle): MediaSession.Token? {
        return if (Build.VERSION.SDK_INT >= 33) {
            extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
        }
    }
}
