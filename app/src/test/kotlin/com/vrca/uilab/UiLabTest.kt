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
 * The UI Lab's app. By default Firebase is a FAKE project pointed at a dead emulator port, so
 * nothing reaches production. With `--firestore` it uses the real project under a fixed lab
 * device id (one doc per build variant, never a new one per run). With a VRChat session from
 * tools/ui-lab/vrc-login.sh, the alt is signed in before the app starts.
 */
class UiLabApp : VrcaApplication() {
    override fun onCreate() {
        // Robolectric has no external storage volume by default; the headset log reader asks for one.
        org.robolectric.shadows.ShadowEnvironment.addExternalDir("sdcard")
        // EncryptedSharedPreferences (VRChat session store) needs an AndroidKeyStore.
        com.vrca.discordbot.lab.FakeAndroidKeyStore.install()
        val root = System.getProperty("ui.lab.root") ?: "."
        val realFirestore = System.getProperty("uilab.ui.firestore") == "real"
        if (realFirestore) {
            val flavor = com.vrca.BuildConfig.FLAVOR
            getSharedPreferences("vrca_remote", MODE_PRIVATE).edit()
                .putString("device_id_hash", sha256("uilab:$flavor")).commit()
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseOptions.fromResource(this)?.let { FirebaseApp.initializeApp(this, it) }
            }
        } else {
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
        }
        if (System.getProperty("uilab.ui.vrchat") != "off") seedVrchatSession(File("$root/ui-shots/.vrc/session.json"))
        super.onCreate()
        // The app's crash handler kills the process (exit 10); in the lab just log background crashes.
        Thread.setDefaultUncaughtExceptionHandler { t, e -> println("[uilab] background crash on ${t.name}: $e") }
    }

    private fun seedVrchatSession(f: File) {
        if (!f.isFile) return
        val j = org.json.JSONObject(f.readText())
        val auth = j.optString("auth"); val uid = j.optString("userId")
        if (auth.isBlank() || uid.isBlank()) return
        val m = com.vrca.vrchat.VrchatAuthManager::class.java.getDeclaredMethod(
            "saveSession", android.content.Context::class.java, String::class.java, String::class.java,
            String::class.java, String::class.java
        ).apply { isAccessible = true }
        m.invoke(com.vrca.vrchat.VrchatAuthManager, this, auth, j.optString("twoFactorAuth").ifBlank { null },
            uid, j.optString("displayName"))
        println("[uilab] VRChat alt signed in: ${j.optString("displayName")}")
    }

    private fun sha256(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
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
        // Real VRChat presence for the signed-in alt (what the pipeline service would publish).
        if (com.vrca.vrchat.VrchatAuthManager.isLoggedIn(app)) {
            val p = kotlinx.coroutines.runBlocking { com.vrca.vrchat.VrchatAuthManager.fetchPresence(app) }
            println("[uilab] presence: ${p?.let { "${it.displayName} · ${it.state} · ${it.location}" } ?: "fetch failed"}")
            if (p != null) com.vrca.vrchat.VrchatPipelineState.presence = p
            com.vrca.vrchat.VrchatPipelineState.isConnected = p != null
        }

        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val act = controller.get()
        act.setContent { VrcaTheme(themeMode = ThemeMode.Dark) { VrcaScreen(chatboxViewModel = vm) } }
        val looper = shadowOf(Looper.getMainLooper())
        var left = settleMs
        // Real time too: network replies (VRChat, Firestore) arrive on background threads.
        while (left > 0) { Thread.sleep(50); looper.idleFor(Duration.ofMillis(50)); left -= 50 }
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
