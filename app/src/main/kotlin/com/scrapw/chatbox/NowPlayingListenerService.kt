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
        NowPlayingState.clearIfActivePackage(pkg)
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
                    NowPlayingState.clearIfActivePackage(pkg)
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

    // ---- Snapshot + Spotify DJ/Ad fallback ----

    private enum class SpecialKind { DJ, AD }

    private fun classifySpecial(pkg: String, title: String, artist: String): SpecialKind? {
        if (pkg != "com.spotify.music") return null

        val tRaw = title.trim()
        val aRaw = artist.trim()
        val t = tRaw.lowercase()
        val a = aRaw.lowercase()

        // Ads often show "Advertisement" (or similar) with blank/Spotify artist.
        val looksLikeAd =
            t == "advertisement" ||
                t == "ad" ||
                t.contains("advert") ||
                t.contains("sponsored") ||
                (t.contains("spotify") && t.contains("ad"))

        // Spotify DJ: be strict to avoid false positives (e.g. "DJ Khaled").
        // Common patterns:
        // - Title exactly "DJ"
        // - Title contains "Spotify DJ"
        // - Artist is Spotify / Spotify DJ and title is short/blankish
        val looksLikeDj =
            t == "dj" ||
                t.contains("spotify dj") ||
                a == "spotify dj" ||
                (a == "spotify" && t.length <= 6 && t.contains("dj"))

        return when {
            looksLikeAd -> SpecialKind.AD
            looksLikeDj -> SpecialKind.DJ
            else -> null
        }
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

        val special = classifySpecial(pkg, title, artist)
        if (special != null) {
            // Instead of freezing at the last real track, show a stable label.
            // This is ONLY for Spotify and ONLY when it looks like DJ/AD.
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

            // IMPORTANT: when Spotify goes into DJ/AD, callbacks sometimes stop updating
            // when it transitions back to a real track. Start a short poll to catch the next track.
            if (controller != null) startPollForRealTrack(pkg, controller)
        } else {
            // If we got real metadata, stop any poll.
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
                isPlaying = isPlaying
            )
        )
    }

    private fun startPollForRealTrack(pkg: String, controller: MediaController) {
        // If already polling, keep it (donâ€™t stack runnables).
        if (pollRunnablesByPackage.containsKey(pkg)) return

        val startAt = SystemClock.elapsedRealtime()
        val maxMs = 30 * 60_000L // up to 30 minutes; DJ segments can be long

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

                val special = classifySpecial(pkg, t, a)
                if (special == null && (t.isNotBlank() || a.isNotBlank())) {
                    // We found a real track again; push it and stop polling.
                    pushSnapshot(pkg, md, pb, controller)
                    stopPoll(pkg)
                    return
                }

                // Give up after maxMs to avoid background churn.
                if (SystemClock.elapsedRealtime() - startAt >= maxMs) {
                    stopPoll(pkg)
                    return
                }

                mainHandler.postDelayed(this, 2_000L)
            }
        }

        pollRunnablesByPackage[pkg] = r
        mainHandler.postDelayed(r, 2_000L)
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
