package com.scrapw.chatbox.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.scrapw.chatbox.R
import com.scrapw.chatbox.data.SettingsStates

/**
 * ✅ Safe overlay daemon:
 * - No starting/stopping services directly inside the composable body (prevents crashy recomposition loops)
 * - Requests overlay permission only when overlay toggle is ON
 * - Automatically disables overlay toggle if permission is missing/denied
 */
@Composable
fun OverlayDaemon(context: Context) {
    // Use app context for starting/stopping services (safer than Activity context)
    val appCtx = context.applicationContext

    val overlayEnabledState = SettingsStates.overlayState()

    // Permission launcher (only used if user enabled overlay)
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { _: ActivityResult ->
            val granted = Settings.canDrawOverlays(context)
            if (granted) {
                Log.d("OverlayDaemon", "Overlay permission granted")
                // Start happens via LaunchedEffect below
            } else {
                Log.d("OverlayDaemon", "Overlay permission denied -> disabling overlay toggle")
                overlayEnabledState.value = false
            }
        }

    // If permission is missing at any time, force toggle off (prevents boot crash + inconsistent state)
    LaunchedEffect(Unit) {
        if (!Settings.canDrawOverlays(context) && overlayEnabledState.value) {
            overlayEnabledState.value = false
        }
    }

    // If user turns overlay ON but permission is missing, request it once.
    LaunchedEffect(overlayEnabledState.value) {
        val wantsOverlay = overlayEnabledState.value
        val hasPermission = Settings.canDrawOverlays(context)

        if (wantsOverlay && !hasPermission) {
            requestOverlayPermission(context, permissionLauncher)
        }
    }

    // Start/Stop overlay service ONLY as a side-effect, not during composition.
    LaunchedEffect(overlayEnabledState.value) {
        val wantsOverlay = overlayEnabledState.value
        val hasPermission = Settings.canDrawOverlays(context)

        if (wantsOverlay && hasPermission) {
            Log.d("OverlayDaemon", "Starting overlay service")
            startOverlayService(appCtx)
        } else {
            Log.d("OverlayDaemon", "Stopping overlay service")
            stopOverlayService(appCtx)
        }
    }
}

private fun startOverlayService(context: Context) {
    val intent = Intent(context, OverlayService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}

private fun stopOverlayService(context: Context) {
    context.stopService(Intent(context, OverlayService::class.java))
}

@Composable
private fun requestOverlayPermission(
    context: Context,
    launcher: ManagedActivityResultLauncher<Intent, ActivityResult>
) {
    val toastText = stringResource(R.string.overlay_permission_request)

    // Only launch once per composition trigger (the LaunchedEffect in OverlayDaemon controls repeats)
    LaunchedEffect(Unit) {
        Toast.makeText(context, toastText, Toast.LENGTH_LONG).show()

        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        launcher.launch(intent)
    }
}
