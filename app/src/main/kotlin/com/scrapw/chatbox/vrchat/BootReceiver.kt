package com.scrapw.chatbox.vrchat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.scrapw.chatbox.ChatboxApplication

/**
 * Restarts VrchatPipelineService after device reboot so notifications
 * keep working even if the user never reopens the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return

        if (!VrchatAuthManager.isLoggedIn(context)) return

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
