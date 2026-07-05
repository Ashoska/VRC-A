package com.vrca.vrchat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.vrca.keepalive.KeepAliveService
import com.vrca.keepalive.PipelineWatchdogWorker
/**
 * Restarts the foreground services after device reboot so notifications and the
 * chatbox keep working even if the user never reopens the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return

        if (!VrchatAuthManager.isLoggedIn(context)) return

        // A reboot is a fresh start for an always-on companion app — clear any
        // lingering swipe-dismissal flag so the services below are allowed to run.
        runCatching { com.vrca.app.AppShutdown.clearSwipedAway(context) }

        // Always-on keep-alive (holds the wakelock) — previously NOT restarted on boot.
        runCatching { KeepAliveService.start(context) }

        // Re-arm the periodic watchdog after reboot.
        runCatching { PipelineWatchdogWorker.ensureScheduled(context) }

        // Reboot clears AlarmManager — re-arm signed-up event reminders.
        runCatching { EventReminderScheduler.rescheduleAll(context) }

        val deviceHash = context
            .getSharedPreferences("vrca_remote", Context.MODE_PRIVATE)
            .getString("device_id_hash", "") ?: ""

        val serviceIntent = Intent(context, VrchatPipelineService::class.java).apply {
            action = VrchatPipelineService.ACTION_START
            putExtra(VrchatPipelineService.EXTRA_DEVICE_HASH, deviceHash)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
