package com.vrca.keepalive

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.vrca.BuildConfig
import com.vrca.app.AppShutdown
import com.vrca.vrchat.VrchatAuthManager
import com.vrca.vrchat.VrchatPipelineService
import com.vrca.vrchat.VrchatPipelineState
import java.util.concurrent.TimeUnit

/**
 * Periodic safety net. The app keeps everything alive via foreground services, but
 * aggressive OEM task killers (Samsung/Xiaomi/etc.) can still kill the whole process —
 * and there is otherwise nothing to bring it back until the user reopens the app or
 * reboots. This worker runs roughly every 15 minutes (the WorkManager minimum) and, if
 * the user is logged into VRChat, ensures [VrchatPipelineService] (and the always-on
 * [KeepAliveService]) are running again.
 *
 * It does NOT fight a deliberate swipe-kill: if the manual-kill flag is fresh it backs
 * off, so it never resurrects the app the user just dismissed.
 */
class PipelineWatchdogWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        try {
            // Don't resurrect a process the user deliberately swiped away. The 15s
            // freshness window is for immediate sticky restarts; this worker fires
            // ~15 min after the swipe, so we rely on the PERSISTENT swipe flag (set
            // in AppShutdown.onTaskSwiped, cleared only on a real reopen/reboot) to
            // stay dead. An OS/OEM kill never sets it, so recovery still works.
            if (AppShutdown.isManualKillFresh(ctx) || AppShutdown.isSwipedAway(ctx)) {
                return Result.success()
            }

            // Admin build doesn't run the pipeline; just keep the wakelock service up.
            if (BuildConfig.IS_ADMIN_BUILD) {
                KeepAliveService.start(ctx)
                return Result.success()
            }

            if (!VrchatAuthManager.isLoggedIn(ctx)) return Result.success()

            // KeepAliveService is cheap to (re)start and is idempotent.
            KeepAliveService.start(ctx)

            // If the pipeline isn't connected, (re)start the service. onStartCommand
            // guards against double-starts when it's already connected.
            if (!VrchatPipelineState.isConnected) {
                val deviceHash = ctx
                    .getSharedPreferences("vrca_remote", Context.MODE_PRIVATE)
                    .getString("device_id_hash", "") ?: ""
                val intent = Intent(ctx, VrchatPipelineService::class.java).apply {
                    action = VrchatPipelineService.ACTION_START
                    putExtra(VrchatPipelineService.EXTRA_DEVICE_HASH, deviceHash)
                }
                ContextCompat.startForegroundService(ctx, intent)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "watchdog failed", t)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "PipelineWatchdog"
        private const val WORK_NAME = "vrca_pipeline_watchdog"

        /** Enqueue the periodic watchdog. Safe to call repeatedly (KEEP policy). */
        fun ensureScheduled(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
                val request = PeriodicWorkRequestBuilder<PipelineWatchdogWorker>(
                    15, TimeUnit.MINUTES
                ).setConstraints(constraints).build()
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniquePeriodicWork(
                        WORK_NAME,
                        ExistingPeriodicWorkPolicy.KEEP,
                        request
                    )
            } catch (t: Throwable) {
                Log.w(TAG, "could not schedule watchdog", t)
            }
        }

        /** Cancel the periodic watchdog (best-effort, called on swipe-away). */
        fun cancel(context: Context) {
            try {
                WorkManager.getInstance(context.applicationContext)
                    .cancelUniqueWork(WORK_NAME)
            } catch (t: Throwable) {
                Log.w(TAG, "could not cancel watchdog", t)
            }
        }
    }
}
