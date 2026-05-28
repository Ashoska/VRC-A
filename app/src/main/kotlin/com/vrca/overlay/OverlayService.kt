// app/src/main/kotlin/com/vrca/overlay/OverlayService.kt
package com.vrca.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.vrca.R
import com.vrca.app.VrcaApplication
import com.vrca.overlay.ui.ButtonOverlay
import com.vrca.overlay.ui.MessengerOverlay
import com.vrca.ui.viewmodel.VrcaViewModel
import com.vrca.ui.theme.OverlayTheme
import kotlin.math.roundToInt

class OverlayService : Service() {

    companion object {
        private const val NOTIF_CHANNEL_ID = "vrca_pipeline"
        private const val NOTIF_CHANNEL_NAME = "Chatbox Overlay"
        private const val NOTIF_ID = 1001
    }

    private lateinit var buttonComposeView: ComposeView
    private lateinit var msgComposeView: ComposeView

    private val lifecycleOwner = MyLifecycleOwner()

    private val buttonDefaultPos = Offset(1f, 0.7f)
    private val msgDefaultPos = Offset(0f, 0.1f)

    enum class Window {
        NONE,
        BUTTON,
        MESSENGER
    }

    private var currentWindow = Window.NONE

    private val windowManager get() = getSystemService(WINDOW_SERVICE) as WindowManager

    private val defaultOverlayParams
        get() = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            format = PixelFormat.TRANSLUCENT
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            windowAnimations = android.R.style.Animation_Dialog
            gravity = Gravity.START or Gravity.TOP
            flags = (WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                    or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }

    private var buttonWindowParams = defaultOverlayParams
    private var msgWindowParams = defaultOverlayParams.apply {
        gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
        flags = flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
    }

    // ---- Foreground + WakeLock ----
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("Service", "onCreate()")

        // ✅ Keep service alive while screen off:
        startAsForeground()
        acquireWakeLock()

        buttonComposeView = ComposeView(this)
        msgComposeView = ComposeView(this)

        orientation.value = resources.configuration.orientation

        onOrientationChange()
        initOverlay()

        switchOverlay(Window.BUTTON)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("Service", "onDestroy()")

        try {
            if (currentWindow == Window.BUTTON) {
                windowManager.removeViewImmediate(buttonComposeView)
            } else if (currentWindow == Window.MESSENGER) {
                windowManager.removeViewImmediate(msgComposeView)
            }
        } catch (_: Throwable) {
        }

        releaseWakeLock()

        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= 26) {
            val existing = nm.getNotificationChannel(NOTIF_CHANNEL_ID)
            if (existing == null) {
                val existing = nm.getNotificationChannel(NOTIF_CHANNEL_ID)
                if (existing == null) {
                    val channel = NotificationChannel(
                        NOTIF_CHANNEL_ID,
                        "VRC-A Background",
                        NotificationManager.IMPORTANCE_MIN
                    ).apply {
                        setShowBadge(false)
                        setSound(null, null)
                    }
                    nm.createNotificationChannel(channel)
                }
            }
        }

        val builder =
            if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, NOTIF_CHANNEL_ID)
            else Notification.Builder(this)

        val notif = builder
            .setSmallIcon(R.drawable.ic_notif_sync)
            .setContentTitle("Chatbox running")
            .setContentText("Keeping OSC updates alive while screen is off")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()

        // On Android 14+ this helps avoid stricter background limits
        startForeground(NOTIF_ID, notif)
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Chatbox:OverlayService")
            wl.setReferenceCounted(false)
            wl.acquire()
            wakeLock = wl
        } catch (t: Throwable) {
            Log.w("Service", "WakeLock acquire failed: ${t.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { wl ->
                if (wl.isHeld) wl.release()
            }
        } catch (_: Throwable) {
        } finally {
            wakeLock = null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initOverlay() {
        buttonComposeView.setContent {
            OverlayTheme {
                OverlayDraggableContainer {
                    ButtonOverlay {
                        switchOverlay(Window.MESSENGER)
                    }
                }

                val configuration = LocalConfiguration.current
                LaunchedEffect(configuration) {
                    orientation.value = configuration.orientation
                    onOrientationChange()
                }
            }
        }

        msgComposeView.setContent {
            // Always resolve the SAME process-scoped runtime ViewModel the rest of
            // the app uses. Prefer the already-initialized singleton; otherwise create
            // it against the Application's ViewModelStore (never the ComposeView's local
            // store) so the overlay and the main UI share one instance.
            val chatboxViewModel: VrcaViewModel =
                if (VrcaViewModel.isInstanceInitialized()) {
                    VrcaViewModel.getInstance()
                } else {
                    val appOwner = applicationContext as VrcaApplication
                    viewModel(viewModelStoreOwner = appOwner, factory = VrcaViewModel.Factory)
                }

            OverlayTheme {
                MessengerOverlay(chatboxViewModel) {
                    switchOverlay(Window.BUTTON)
                }

                val configuration = LocalConfiguration.current
                LaunchedEffect(configuration) {
                    orientation.value = configuration.orientation
                    onOrientationChange()
                }
            }
        }

        msgComposeView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                switchOverlay(Window.BUTTON)
            }
            true
        }

        val viewModelStoreOwner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore
                get() = ViewModelStore()
        }

        lifecycleOwner.performRestore(null)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        buttonComposeView.setViewTreeLifecycleOwner(lifecycleOwner)
        buttonComposeView.setViewTreeViewModelStoreOwner(viewModelStoreOwner)
        buttonComposeView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)

        msgComposeView.setViewTreeLifecycleOwner(lifecycleOwner)
        msgComposeView.setViewTreeViewModelStoreOwner(viewModelStoreOwner)
        msgComposeView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)

        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    private fun switchOverlay(destinationWindow: Window) {
        when (currentWindow) {
            Window.BUTTON -> windowManager.removeView(buttonComposeView)
            Window.MESSENGER -> windowManager.removeView(msgComposeView)
            Window.NONE -> {}
        }

        when (destinationWindow) {
            Window.BUTTON -> {
                buttonComposeView.removeAllViews()
                windowManager.addView(buttonComposeView, buttonWindowParams)
            }
            Window.MESSENGER -> {
                msgComposeView.removeAllViews()
                windowManager.addView(msgComposeView, msgWindowParams)
            }
            Window.NONE -> {}
        }

        currentWindow = destinationWindow
    }

    private fun update() {
        try {
            if (currentWindow == Window.BUTTON) {
                windowManager.updateViewLayout(buttonComposeView, buttonWindowParams)
            } else if (currentWindow == Window.MESSENGER) {
                windowManager.updateViewLayout(msgComposeView, msgWindowParams)
            }
        } catch (_: Exception) {
        }
    }

    var orientation = mutableStateOf(Configuration.ORIENTATION_PORTRAIT)

    private fun isPortrait(): Boolean {
        return orientation.value != Configuration.ORIENTATION_LANDSCAPE
    }

    private var buttonPortraitPos: Offset? = null
    private var buttonLandscapePos: Offset? = null

    private var msgPortraitPos: Offset? = null
    private var msgLandscapePos: Offset? = null

    private fun onOrientationChange() {
        val f = Rect().also { buttonComposeView.getWindowVisibleDisplayFrame(it) }

        Log.d("isPortrait()", isPortrait().toString())
        Log.d("width()", f.width().toString())
        Log.d("height()", f.height().toString())

        if (isPortrait()) {
            if (buttonPortraitPos == null) {
                val fr = Rect().also { buttonComposeView.getWindowVisibleDisplayFrame(it) }
                buttonPortraitPos = Offset(
                    x = fr.width() * buttonDefaultPos.x,
                    y = fr.height() * buttonDefaultPos.y,
                )
            }

            buttonWindowParams.apply {
                x = buttonPortraitPos!!.x.toInt()
                y = buttonPortraitPos!!.y.toInt()
            }

            overlayOffset = buttonPortraitPos as Offset

            if (msgPortraitPos == null) {
                val fr = Rect().also { msgComposeView.getWindowVisibleDisplayFrame(it) }
                msgPortraitPos = Offset(
                    x = fr.width() * msgDefaultPos.x,
                    y = fr.height() * msgDefaultPos.y,
                )
            }

            msgWindowParams.apply {
                x = msgPortraitPos!!.x.toInt()
                y = msgPortraitPos!!.y.toInt()
            }
        } else {
            if (buttonLandscapePos == null) {
                val fr = Rect().also { buttonComposeView.getWindowVisibleDisplayFrame(it) }
                buttonLandscapePos = Offset(
                    x = fr.width() * buttonDefaultPos.x,
                    y = fr.height() * buttonDefaultPos.y,
                )
            }

            buttonWindowParams.apply {
                x = buttonLandscapePos!!.x.toInt()
                y = buttonLandscapePos!!.y.toInt()
            }

            overlayOffset = buttonLandscapePos as Offset

            if (msgLandscapePos == null) {
                val fr = Rect().also { msgComposeView.getWindowVisibleDisplayFrame(it) }
                msgLandscapePos = Offset(
                    x = fr.width() * msgDefaultPos.x,
                    y = fr.height() * msgDefaultPos.y,
                )
            }

            msgWindowParams.apply {
                x = msgLandscapePos!!.x.toInt()
                y = msgLandscapePos!!.y.toInt()
            }
        }
        update()
    }

    private var overlayOffset: Offset by mutableStateOf(Offset.Zero)

    @Composable
    fun OverlayDraggableContainer(
        modifier: Modifier = Modifier,
        content: @Composable BoxScope.() -> Unit
    ) = Box(
        modifier = modifier.pointerInput(Unit) {
            detectDragGestures { change, dragAmount ->
                change.consume()

                val newOffset = Offset(
                    if (buttonWindowParams.gravity and Gravity.END == Gravity.END) {
                        overlayOffset.x - dragAmount.x
                    } else {
                        overlayOffset.x + dragAmount.x
                    },
                    if (buttonWindowParams.gravity and Gravity.BOTTOM == Gravity.BOTTOM) {
                        overlayOffset.y - dragAmount.y
                    } else {
                        overlayOffset.y + dragAmount.y
                    }
                )

                overlayOffset = newOffset

                buttonWindowParams.apply {
                    x = overlayOffset.x.roundToInt()
                    y = overlayOffset.y.roundToInt()
                }

                if (isPortrait()) {
                    buttonPortraitPos = overlayOffset
                } else {
                    buttonLandscapePos = overlayOffset
                }

                windowManager.updateViewLayout(buttonComposeView, buttonWindowParams)
            }
        },
        content = content
    )

    override fun onBind(intent: Intent): IBinder? = null
}
