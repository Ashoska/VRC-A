package com.vrca.uilab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.vrca.app.VrcaApplication
import com.vrca.ui.screen.VrcaScreen
import com.vrca.ui.theme.ThemeMode
import com.vrca.ui.theme.VrcaTheme
import com.vrca.ui.viewmodel.VrcaViewModel
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

/**
 * Offline app for the UI Lab: swaps in a FAKE Firebase project pointed at a dead emulator port
 * before the real [VrcaApplication.onCreate] runs, so nothing the app does can reach production.
 */
class UiLabApp : VrcaApplication() {
    override fun onCreate() {
        // Robolectric has no external storage volume by default; the headset log reader asks for one.
        org.robolectric.shadows.ShadowEnvironment.addExternalDir("sdcard")
        FirebaseApp.getApps(this).forEach { runCatching { it.delete() } }
        FirebaseApp.initializeApp(
            this,
            FirebaseOptions.Builder()
                .setApplicationId("1:000000000000:android:0000000000000000")
                .setProjectId("vrca-uilab-offline")
                .setApiKey("uilab-offline")
                .build()
        )
        FirebaseFirestore.getInstance().useEmulator("127.0.0.1", 1)
        FirebaseAuth.getInstance().useEmulator("127.0.0.1", 1)
        super.onCreate()
        // The app's crash handler kills the process (exit 10); in the lab just log background crashes.
        Thread.setDefaultUncaughtExceptionHandler { t, e -> println("[uilab] background crash on ${t.name}: $e") }
    }
}

/**
 * **UI Lab** — renders the REAL app screens on the JVM (Robolectric native graphics) and writes
 * PNGs to ui-shots/. Gated: only runs with `-PuiLab` (see tools/ui-lab/ui.sh).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UiLabApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class UiLabTest {

    @Test
    fun render() {
        Assume.assumeTrue("UI Lab only runs with -PuiLab", System.getProperty("ui.lab") == "1")
        val t0 = System.currentTimeMillis()
        val qualifiers = System.getProperty("uilab.ui.qualifiers")
            ?: if (com.vrca.BuildConfig.IS_HEADSET_BUILD) "w1024dp-h640dp-land-xhdpi" else "w411dp-h891dp-port-xxhdpi"
        RuntimeEnvironment.setQualifiers(qualifiers)
        val out = File(System.getProperty("uilab.ui.out") ?: "${System.getProperty("ui.lab.root")}/ui-shots/shot.png")
        val settleMs = (System.getProperty("uilab.ui.settle") ?: "1500").toLong()

        val app = RuntimeEnvironment.getApplication() as VrcaApplication
        val vm = ViewModelProvider(app, VrcaViewModel.Factory)[VrcaViewModel::class.java]

        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val act = controller.get()
        act.setContent { VrcaTheme(themeMode = ThemeMode.Dark) { VrcaScreen(chatboxViewModel = vm) } }
        val looper = shadowOf(Looper.getMainLooper())
        var left = settleMs
        while (left > 0) { looper.idleFor(Duration.ofMillis(50)); left -= 50 }
        run {
            val root = act.window.decorView
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            out.parentFile?.mkdirs()
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("[uilab] $qualifiers → ${out.absolutePath} (${bmp.width}x${bmp.height}) in ${System.currentTimeMillis() - t0} ms")
        }
    }
}
