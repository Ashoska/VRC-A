package com.vrca.app

import android.app.Notification
import android.app.Service
import android.util.Log

/**
 * Calls [Service.startForeground] but never lets it crash the process.
 *
 * The app targets SDK 34. On Android 12+ (API 31) starting a foreground service from
 * the background throws `ForegroundServiceStartNotAllowedException`, and on Android 14
 * (API 34) the `dataSync` FGS type is itself background-start-restricted. These
 * services are re-entered from the BACKGROUND by `START_STICKY` restarts, the
 * [com.vrca.keepalive.PipelineWatchdogWorker], and the system's queued sticky restarts
 * after an OEM process kill — so an unguarded `startForeground()` on those paths throws
 * an UNCAUGHT exception and kills the whole (shared) process.
 *
 * After an OEM kill this turned into a crash LOOP: every queued sticky-restart attempt
 * re-crashed the process, which also took down the Activity the moment the user
 * reopened the app, so the app "kept crashing on open" until a phone reboot cleared the
 * pending sticky-restart queue (after which `BootReceiver` — an FGS-start-EXEMPT boot
 * context — started the pipeline cleanly).
 *
 * Returns true if the service successfully entered the foreground. On failure it logs,
 * calls [Service.stopSelf] (which satisfies the `startForegroundService` 5-second
 * contract without a crash), and returns false. The caller should return
 * `START_NOT_STICKY` so the OS does not immediately sticky-restart into the same throw;
 * the watchdog (~15 min) and the next user-foreground launch will start the service
 * again once the OS actually permits a foreground start (app visible / recently
 * visible / battery-optimization exempt).
 */
fun Service.startForegroundSafely(
    id: Int,
    notification: Notification,
    tag: String = "VrcaFGS"
): Boolean {
    return try {
        startForeground(id, notification)
        true
    } catch (t: Throwable) {
        // ForegroundServiceStartNotAllowedException (API 31+) and the API 34 dataSync
        // background-start restriction both land here. Degrade gracefully instead of
        // crashing the shared process.
        Log.w(tag, "startForeground blocked (background FGS start not allowed?) — stopping service", t)
        try { stopSelf() } catch (_: Throwable) {}
        false
    }
}
