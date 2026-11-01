package com.vrca.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Installs a downloaded update APK through Android's own installer (PackageInstaller session, 2026-11-01).
 *
 * Before, the APK was opened with ACTION_VIEW + the APK mime type, which on Quest resolves to the Quest file manager
 * (it just showed the file) instead of the installer. A session goes straight to the system installer: if "Install
 * unknown apps" is off for VRC-A, Android shows its own permission popup (Settings / Cancel) and the install carries
 * on once it's allowed; otherwise the usual "Update this app?" confirmation.
 */
object ApkInstaller {
    private const val TAG = "ApkInstaller"

    /** Starts the install. Returns false if the session couldn't be set up (the caller falls back to ACTION_VIEW). */
    fun install(ctx: Context, apk: File): Boolean = runCatching {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        params.setSize(apk.length())
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val result = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallResultReceiver::class.java), flags)
            s.commit(result.intentSender)
        }
        true
    }.onFailure { Log.w(TAG, "session install failed", it) }.getOrDefault(false)
}

/** The installer's answers: shows its popup (permission or confirmation) when it needs the user. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm != null) runCatching { ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { InstallStatus.error.value = "Couldn't open the installer. Tap the download link to install manually." }
            }
            PackageInstaller.STATUS_SUCCESS -> {}   // the app is replaced and restarts
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallStatus.error.value = "Install cancelled. Tap Download to try again."
            else -> InstallStatus.error.value = "Install failed" +
                (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.let { ": $it" } ?: "") +
                ". Tap the download link to install manually."
        }
    }
}

/** The last install problem, shown in the update popup (watched state). */
object InstallStatus {
    val error = androidx.compose.runtime.mutableStateOf<String?>(null)
}
