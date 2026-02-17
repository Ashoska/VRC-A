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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.scrapw.chatbox.R
import com.scrapw.chatbox.data.SettingsStates

@Composable
fun OverlayDaemon(context: Context) {
    val state = SettingsStates.overlayState()
    val firstTime = remember { mutableStateOf(true) }

    if (firstTime.value && !Settings.canDrawOverlays(context)) {
        Log.d("Service", "No permission at start up, cancel.")
        state.value = false
    } else {
        if (state.value) {
            StartOverlay(context)
        } else {
            StopOverlay(context)
        }
    }

    LaunchedEffect(Unit) {
        firstTime.value = false
    }
}

@Composable
private fun StartOverlay(context: Context) {
    Log.d("Service", "startOverlay")
    val state = SettingsStates.overlayState()

    val startForResult =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
            if (Settings.canDrawOverlays(context)) {
                Log.d("Overlay Permission", "Permission granted")
                startOverlayService(context)
            } else {
                Log.d("Overlay Permission", "Permission denied")
                state.value = false
            }
        }

    if (Settings.canDrawOverlays(context)) {
        startOverlayService(context)
    } else {
        Log.d("Service", "Can't draw Overlays!")
        CheckOverlayPermission(context, startForResult)
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

@Composable
private fun StopOverlay(context: Context) {
    Log.d("Service", "stopOverlay")
    context.stopService(Intent(context, OverlayService::class.java))
}

@Composable
private fun CheckOverlayPermission(
    context: Context,
    result: ManagedActivityResultLauncher<Intent, ActivityResult>
) {
    if (!Settings.canDrawOverlays(context)) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )

        // ✅ FIX: do NOT use stringResource() here; use Context.getString()
        val toast = Toast.makeText(
            context,
            context.getString(R.string.overlay_permission_request),
            Toast.LENGTH_LONG
        )

        LaunchedEffect(Unit) {
            toast.show()
            result.launch(intent)
        }
    }
}
