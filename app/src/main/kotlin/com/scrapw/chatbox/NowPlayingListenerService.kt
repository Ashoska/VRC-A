// app/src/main/kotlin/com/scrapw/chatbox/NowPlayingListenerService.kt
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

    // Polling only used as a fallback when Spotify DJ/Ads cause callbacks to stop updating.
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pollRunnablesByPackage = HashMap<String, Runnable>()

    private val lastTitleArtistByPackage = HashMap<String, Pair<String, String>>()
    private val lastPushElapsedByPackage = HashMap<String, Long>()
    private val stallCountByPackage = HashMap<String, Int>()
    private val specialUntilElapsedByPackage = HashMap<String, Long>()
    private val lastPlayingStateByPackage = HashMap<String, Boolean>()

    private fun markSpecialWindow(pkg: String, windowMs: Long) {
        specialUntilElapsedByPackage[pkg] = SystemClock.elapsedRealtime() + windowMs
    }

    private fun isSpecialWindowActive(pkg: String): Boolean {
        val until = specialUntilElapsedByPackage[pkg] ?: 0L
        return SystemClock.elapsedRealtime() < until
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        NowPlayingState.setConnected(true)

        // Prime state from currently active notifications so the UI works immediately.
        try {
            activeNotifications?.forEach { sbn -> onNotificationPosted(sbn) }
        } catch (_: Throwable) { }

        // Also scan active MediaSessions directly. Apps that started playback
        // before the listener connected (or after a listener rebind) may not
        // re-post a notification, so the notification scan misses them.
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
                // Register a callback and push an immediate snapshot.
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

        // Ensure we are listening to this session via MediaController callbacks.
        // Skips/back/pauses often do NOT cause a fresh "posted" notification.
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
                    NowPlayingState.pauseIfActivePackage(pkg)
                }
            }

            controller.registerCallback(cb)
            controllersByPackage[pkg] = ControllerEntry(controller, cb, token)

            // Seed the playing state so the poll can detect the first pause/resume.
            val initState = controller.playbackState
            lastPlayingStateByPackage[pkg] = initState?.state == PlaybackState.STATE_PLAYING

            // Push an immediate snapshot so UI/OSC updates right away.
            pushSnapshot(pkg, controller.metadata, initState, controller)
        } catch (_: Throwable) {
            // If MediaController fails, do nothing (don\u00E2\u20AC\u2122t fall back to non-media notifications).
        }
    }

    private fun teardownController(pkg: String) {
        val entry = controllersByPackage.remove(pkg) ?: return
        try {
            entry.controller.unregisterCallback(entry.callback)
        } catch (_: Throwable) {
            // ignore
        }
        lastPlayingStateByPackage.remove(pkg)
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

    // ---- Snapshot + Spotify DJ/Ad fallback ----

    private enum class SpecialKind { DJ, AD }

    private fun classifySpecial(pkg: String, title: String, artist: String): SpecialKind? {

        val t = title.trim().lowercase()
        val a = artist.trim().lowercase()

        // Common Spotify ad / dj patterns.
        // Ads often show "Advertisement" (or similar) with blank/Spotify artist.
        val looksLikeAd =
            t.contains("advert") ||
                t == "ad" ||
                t.contains("spotify") && t.contains("ad") ||
                (t.contains("advertisement") || t.contains("sponsored"))

        // Spotify DJ often shows "DJ" / "Spotify DJ" / voice segments.
        val looksLikeDj =
            t == "dj" ||
                t.contains("spotify dj") ||
                t.startsWith("dj ") ||
                t.contains(" dj ") ||
                (t.contains("dj") && (a.contains("spotify") || a.isBlank()))

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

        lastPushElapsedByPackage[pkg] = SystemClock.elapsedRealtime()

        // Stall tracking: if metadata stays the same while playback looks stalled, suppress paused flicker.
        val prevTa = lastTitleArtistByPackage[pkg]
        val curTa = title to artist
        val metaSame = prevTa != null && prevTa.first == curTa.first && prevTa.second == curTa.second
        if (!metaSame) {
            stallCountByPackage[pkg] = 0
            lastTitleArtistByPackage[pkg] = curTa
        }

        val special = classifySpecial(pkg, title, artist)
        if (special != null) {
            // Keep a stable label only for Spotify. Other apps keep original metadata.
            if (pkg == "com.spotify.music") {
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
            }

            // Hold special window so UI never shows Paused flicker during DJ/ads.
            markSpecialWindow(pkg, 30000L)

            // Start watchdog polling to catch the first real track after the segment ends.
            if (controller != null) startPollForRealTrack(pkg, controller)
        } else {
            // Suppress paused-flicker ONLY for genuine stalls.
            // STATE_PAUSED = user explicitly paused \u2014 never suppress that.
            // Other non-playing states (buffering, skipping) may be transient stalls.
            val rawState = pb?.state ?: PlaybackState.STATE_NONE
            val isPausedByUser = rawState == PlaybackState.STATE_PAUSED
            if (metaSame && !isPlaying && !isPausedByUser && (title.isNotBlank() || artist.isNotBlank())) {
                val n = (stallCountByPackage[pkg] ?: 0) + 1
                stallCountByPackage[pkg] = n
                if (n >= 2) {
                    markSpecialWindow(pkg, 8000L)
                    if (controller != null) startPollForRealTrack(pkg, controller)
                }
            } else {
                stallCountByPackage[pkg] = 0
            }

            // Keep a general watchdog alive for any detected media.
            if (controller != null && (title.isNotBlank() || artist.isNotBlank())) {
                startPollForRealTrack(pkg, controller)
            } else {
                stopPoll(pkg)
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
                isPlaying = isPlaying,
                specialActive = isSpecialWindowActive(pkg)
            )
        )
    }

    private fun startPollForRealTrack(pkg: String, controller: MediaController) {
        // If already polling, keep it (do not stack runnables).
        if (pollRunnablesByPackage.containsKey(pkg)) return

        val startAt = SystemClock.elapsedRealtime()
        val maxMs = 10 * 60 * 1000L // 10 minutes max per activation
        val intervalMs = 500L

        val r = object : Runnable {
            override fun run() {
                // If controller got replaced, stop; new controller will start its own poll if needed.
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

                // Also detect playback state changes (pause/resume) that callbacks missed.
                val nowPlaying = pb?.state == PlaybackState.STATE_PLAYING
                val statePrev = lastPlayingStateByPackage[pkg]
                val stateChanged = statePrev != null && statePrev != nowPlaying

                // Push if metadata changed, state changed, or stall recovery fallback.
                if (changed || stateChanged || sincePush >= 4000L) {
                    pushSnapshot(pkg, md, pb, controller)
                }
                lastPlayingStateByPackage[pkg] = nowPlaying

                // Give up after maxMs to avoid background churn.
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
