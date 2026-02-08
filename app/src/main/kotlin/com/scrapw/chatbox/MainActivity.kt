// app/src/main/kotlin/com/scrapw/chatbox/MainActivity.kt
package com.scrapw.chatbox

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.scrapw.chatbox.keepalive.ChatboxKeepAliveService
import com.scrapw.chatbox.overlay.OverlayDaemon
import com.scrapw.chatbox.ui.theme.ChatboxTheme

fun Context.getActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.getActivity()
    else -> null
}

class MainActivity : ComponentActivity() {

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Even if denied, try to start anyway (some devices still allow FGS).
        ChatboxKeepAliveService.start(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        // Keep-alive for screen-off reliability.
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (granted) {
                ChatboxKeepAliveService.start(applicationContext)
            } else {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            ChatboxKeepAliveService.start(applicationContext)
        }

        setContent {
            ChatboxTheme {
                // ✅ UI redesign lives in ChatboxScreen.kt; keep app wiring stable.
                ChatboxApp()
            }
            // ✅ Overlay stays as-is (no feature change).
            OverlayDaemon(this)
        }
    }
}
