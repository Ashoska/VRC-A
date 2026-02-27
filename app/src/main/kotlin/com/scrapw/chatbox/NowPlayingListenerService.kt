package com.scrapw.chatbox

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

    // Optional: only accept these apps (keeps it clean).
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

    /**
     * Polling is used as a watchdog fallback when some players stop firing callbacks
     * (Spotify DJ/ads is the worst offender, but OEM ROMs can also drop callbacks).
     *
     * We keep polling scoped and time-bounded to avoid permanent background churn.
     */
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pollRunnablesByPackage = HashMap<String, Runnable>()

    // Watchdog state (per package)
    private val lastPushElapsedByPackage = HashMap<String, Long>()
    private val lastTitleArtistByPackage = HashMap<String, Pair<String, String>>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        NowPlayingState.setConnected(true)

        // Prime state from currently active notifications so the UI works immediately
        try {
            activeNotifications?.forEach { sbn ->
                onNotificationPosted(sbn)
            }
        } catch (_: Throwable) {
            // ignore
        }
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

        // Ensure we are listening to this session via MediaController callbacks.
        // Skips/back/pauses often do NOT cause a fresh "posted" notification.
        ensureControllerForPackage(pkg, token)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return

        teardownController(pkg)
        stopPoll(pkg)

        // IMPORTANT: don't blank the UI when a player hides its notification.
        // Keep the last known track and mark it paused instead.
        NowPlayingState.pauseIfActivePackage(pkg)
    }

    // ---- MediaController wiring ----

    private fun ensureControllerForPackage(pkg: String, token: MediaSession.Token) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return

        val existing = controllersByPackage[pkg]
        if (existing != null && tokensEqual(existing.token, token)) {
            // Already listening to this exact session; just push a fresh snapshot.
            pushSnapshot(pkg, existing.controller.metadata, existing.controller.playbackState, existing.controller)
            return
        }

        // Token changed or none exists: recreate.
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
                    // Same rule as notification removal: pause but keep last known track.
                    NowPlayingState.pauseIfActivePackage(pkg)
                }
            }

            controller.registerCallback(cb)
            controllersByPackage[pkg] = ControllerEntry(controller, cb, token)

            // Push an immediate snapshot so UI/OSC updates right away.
            pushSnapshot(pkg, controller.metadata, controller.playbackState, controller)
        } catch (_: Throwable) {
            // If MediaController fails, do nothing (donâ€™t fall back to non-media notifications).
        }
    }

    private fun teardownController(pkg: String) {
        val entry = controllersByPackage.remove(pkg) ?: return
        try {
            entry.controller.unregisterCallback(entry.callback)
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun teardownAllControllers() {
        val keys = controllersByPackage.keys.toList()
        keys.forEach { teardownController(it) }
        controllersByPackage.clear()
    }

    private fun tokensEqual(a: MediaSession.Token, b: MediaSession.Token): Boolean {
        // Token equality is inconsistent across OEMs; safest check is toString() + hashCode().
        // This stays in-service and avoids additional dependencies.
        return (a == b) || (a.toString() == b.toString() && a.hashCode() == b.hashCode())
    }

    // ---- Snapshot + watchdog fallback ----

    private enum class SpecialKind { DJ, AD }

    /**
     * Spotify-only heuristic labeling (optional).
     * We still run the watchdog fallback even if we can't detect DJ/AD by text.
     */
    private fun classifySpotifySpecial(title: String, artist: String): SpecialKind? {
        val t = title.trim().lowercase()
        val a = artist.trim().lowercase()

        val looksLikeAd =
            t.contains("advert") ||
                t == "ad" ||
                (t.contains("spotify") && t.contains("ad")) ||
                t.contains("advertisement") ||
                t.contains("sponsored")

        val looksLikeDj =
            t == "dj" ||
                t.contains("spotify dj") ||
                (t.startsWith("dj ") && (a.contains("spotify") || a.isBlank()))

        return when {
            looksLikeAd -> SpecialKind.AD
            looksLikeDj -> SpecialKind.DJ
            else -> null
        }
    }

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

        // lastPositionUpdateTime is based on elapsedRealtime.
        val snapshotUpdateTime =
            if (lastUpdate > 0L) lastUpdate else SystemClock.elapsedRealtime()

        // Remember last pushed state for watchdog decisions
        lastPushElapsedByPackage[pkg] = SystemClock.elapsedRealtime()
        lastTitleArtistByPackage[pkg] = title to artist

        // Optional Spotify label override during DJ/AD, to keep UI stable.
        if (pkg == "com.spotify.music") {
            val special = classifySpotifySpecial(title, artist)
            if (special != null) {
                when (special) {
                    SpecialKind.DJ -> {
                        title = "DJ"
                        artist = ""
                    }
                    SpecialKind.AD -> {
                        title = "AD"
                        artist = ""
                    }
                }
                // Start watchdog polling to catch the first real track after DJ/AD ends.
                if (controller != null) startWatchdogPoll(pkg, controller)
            }
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
                isPlaying = isPlaying
            )
        )

        // General-case watchdog: if we're "playing" we keep a time-bounded poll alive
        // to recover from callback stalls on ANY media app.
        if (controller != null && detected) {
            startWatchdogPoll(pkg, controller)
        } else {
            stopPoll(pkg)
        }
    }

    /**
     * Watchdog poll:
     * - runs at 2s interval
     * - stops automatically if the controller is replaced, no longer active, or max time reached
     * - forces a metadata/state refresh if callbacks stop (common in Spotify DJ/ads)
     */
    private fun startWatchdogPoll(pkg: String, controller: MediaController) {
        // If already polling, don't stack.
        if (pollRunnablesByPackage.containsKey(pkg)) return

        val startAt = SystemClock.elapsedRealtime()
        val maxMs = 10 * 60 * 1000L // 10 minutes max watchdog per activation

        val r = object : Runnable {
            override fun run() {
                // If controller got replaced, stop; new controller will start its own poll if needed.
                val current = controllersByPackage[pkg]?.controller
                if (current != controller) {
                    stopPoll(pkg)
                    return
                }

                // If this package isn't the active package anymore, stop polling.
                val activePkg = NowPlayingState.state.value.activePackage
                if (activePkg.isNotBlank() && activePkg != pkg) {
                    stopPoll(pkg)
                    return
                }

                val md = runCatching { controller.metadata }.getOrNull()
                val pb = runCatching { controller.playbackState }.getOrNull()

                val t = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
                val a = md?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()

                // If something changed OR we haven't pushed in a while, push a snapshot.
                val last = lastTitleArtistByPackage[pkg]
                val lastPush = lastPushElapsedByPackage[pkg] ?: 0L
                val sincePush = SystemClock.elapsedRealtime() - lastPush

                val changed = last == null || last.first != t || last.second != a

                if (changed || sincePush >= 4000L) {
                    pushSnapshot(pkg, md, pb, controller)
                }

                // Give up after maxMs to avoid background churn.
                if (SystemClock.elapsedRealtime() - startAt >= maxMs) {
                    stopPoll(pkg)
                    return
                }

                mainHandler.postDelayed(this, 2000L)
            }
        }

        pollRunnablesByPackage[pkg] = r
        mainHandler.postDelayed(r, 2000L)
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
