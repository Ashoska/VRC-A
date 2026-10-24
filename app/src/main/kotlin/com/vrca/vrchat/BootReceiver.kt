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

        // A reboot is a fresh start for an always-on companion app — clear any
        // lingering swipe-dismissal flag so the app/services below are allowed to run.
        runCatching { com.vrca.app.AppShutdown.clearSwipedAway(context) }

        // The next open is the first since a restart: it shows the boot screen and runs its checks
        // (MainActivity reads this note).
        runCatching { com.vrca.app.MainActivity.markRebooted(context) }

        // Headset (Meta Quest): OPEN the app when the headset turns on — a VR companion should
        // come up with the headset, not sit invisible in the background. VRC-A holds
        // SYSTEM_ALERT_WINDOW, which grants the background-activity-launch exemption needed to
        // start an Activity from a boot receiver on Android 10+. Best-effort.
        if (com.vrca.BuildConfig.IS_HEADSET_BUILD) {
            runCatching {
                context.startActivity(
                    Intent(context, com.vrca.app.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        // Everything else runs OFF the main thread. It reads the encrypted VRChat store, and right
        // after power-on the Keystore can take seconds: done here on the main thread it held up
        // the app window just launched above, so Horizon showed "VRC-A isn't responding" until
        // the user pressed Wait (user reports, only on the auto-open at power-on).
        val pending = goAsync()
        Thread({
            try {
                // Not during a safe start after a crash (CrashReport): the crash screen opens first.
                if (!com.vrca.app.CrashReport.safeStart) runCatching { startServices(context.applicationContext) }
            } finally {
                pending.finish()
            }
        }, "vrca-boot-receiver").start()
    }

    private fun startServices(context: Context) {
        if (!VrchatAuthManager.isLoggedIn(context)) return

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
