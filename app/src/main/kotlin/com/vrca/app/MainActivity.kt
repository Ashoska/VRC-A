// app/src/main/kotlin/com/vrca/MainActivity.kt
package com.vrca.app

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.vrca.BuildConfig
import com.vrca.keepalive.KeepAliveService
import com.vrca.keepalive.PipelineWatchdogWorker
import com.vrca.overlay.OverlayDaemon
import com.vrca.ui.theme.VrcaTheme
import java.security.MessageDigest
import java.security.SecureRandom

fun Context.getActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.getActivity()
    else -> null
}

/**
 * Boot responsibilities (final):
 * - Start keep-alive service (notif permission on 33+).
 * - Ensure a stable device hash is available in SharedPreferences "vrca_remote" under key "device_id_hash".
 *
 * Device hash strategy:
 * - Primary: SHA-256("v2:<ANDROID_ID>:<SIGNING_CERT_SHA256>")
 *   This is stable across reinstall (same device/user + same signing key).
 * - Fallback: stored random (only used if ANDROID_ID is unavailable/blank).
 *
 * Notes:
 * - ANDROID_ID can change on factory reset; that's expected.
 * - Some OEMs/work profiles can return blank ANDROID_ID; fallback covers it.
 */
class MainActivity : ComponentActivity() {

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Even if denied, still try to start (some devices allow FGS without notif permission).
        KeepAliveService.start(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        //Ensure device hash exists early (before any screen reads it).
        runCatching { ensureDeviceHash(applicationContext) }

        // A real user open clears the persistent swipe-dismissal flag so the
        // watchdog and services may run again (a swipe set it true to keep the app
        // dead; opening the app is the explicit "bring it back"). Must run BEFORE
        // starting KeepAliveService / scheduling the watchdog below.
        runCatching { AppShutdown.clearSwipedAway(applicationContext) }

        // Keep-alive for screen-off reliability.
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (granted) {
                KeepAliveService.start(applicationContext)
            } else {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            KeepAliveService.start(applicationContext)
        }

        // Periodic watchdog: re-arms the pipeline/keep-alive if an OEM kills the process.
        PipelineWatchdogWorker.ensureScheduled(applicationContext)

        setContent {
            VrcaTheme(themeMode = com.vrca.ui.theme.ThemeMode.Dark) {
                // App routing (ToS gate + Admin-only UI) lives in VrcaApp.kt
                VrcaApp()

                //Overlay MUST stay inside Compose because OverlayDaemon is @Composable
                OverlayDaemon(this@MainActivity)
            }
        }
    }
}

/* =========================================================
   Device hash (stable across reinstall)
   ========================================================= */

private const val REMOTE_PREFS = "vrca_remote"
private const val KEY_DEVICE_HASH = "device_id_hash"
private const val KEY_DEVICE_HASH_FALLBACK_RANDOM = "device_id_hash_fallback_random"

/**
 * Ensures prefs contain a stable device hash.
 * Returns the current hash (existing or generated).
 */
private fun ensureDeviceHash(ctx: Context): String {
    val prefs = ctx.getSharedPreferences(REMOTE_PREFS, Context.MODE_PRIVATE)
    val existing = prefs.getString(KEY_DEVICE_HASH, "")?.trim().orEmpty()

    if (BuildConfig.IS_ADMIN_BUILD) {
        // The admin build must use a DISTINCT device hash from the public build.
        // Both share the same ANDROID_ID + signing key, so without this they'd
        // resolve to the same Firestore doc — the two installs on one phone would
        // overwrite each other's presets/content, and the admin self-filter
        // (it.id != myDeviceHash) would hide the public install from the admin
        // user list. We namespace the admin hash and force-normalise it (the
        // admin app may have previously cached the public-equal hash). Public
        // users are unaffected. The admin build never writes a user doc, so no
        // orphan doc is created under this hash.
        val adminHash = sha256Hex("admin:" + computeBaseDeviceHash(ctx, prefs))
        if (existing != adminHash) {
            prefs.edit().putString(KEY_DEVICE_HASH, adminHash).apply()
        }
        return adminHash
    }

    // Public build: unchanged — return the cached hash if present for continuity.
    if (existing.isNotBlank()) return existing
    val base = computeBaseDeviceHash(ctx, prefs)
    prefs.edit().putString(KEY_DEVICE_HASH, base).apply()
    return base
}

/** The reinstall-stable base hash (ANDROID_ID + signing cert), with a stored
 *  random fallback for devices that report a blank ANDROID_ID. */
private fun computeBaseDeviceHash(ctx: Context, prefs: android.content.SharedPreferences): String {
    val androidId = runCatching {
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
            ?.trim()
            .orEmpty()
    }.getOrDefault("")

    val signingDigest = runCatching { signingCertSha256Hex(ctx) }.getOrDefault("")

    val computedStable = if (androidId.isNotBlank() && signingDigest.isNotBlank()) {
        sha256Hex("v2:$androidId:$signingDigest")
    } else if (androidId.isNotBlank()) {
        sha256Hex("v2:$androidId:no_signing")
    } else {
        ""
    }

    return if (computedStable.isNotBlank()) {
        computedStable
    } else {
        val fallbackExisting = prefs.getString(KEY_DEVICE_HASH_FALLBACK_RANDOM, "")?.trim().orEmpty()
        if (fallbackExisting.isNotBlank()) fallbackExisting
        else {
            val r = secureRandomHex(32)
            prefs.edit().putString(KEY_DEVICE_HASH_FALLBACK_RANDOM, r).apply()
            r
        }
    }
}

/**
 * Returns SHA-256 of the app signing certificate (hex).
 * Helps make the device hash app-signing-specific.
 */
private fun signingCertSha256Hex(ctx: Context): String {
    val pm = ctx.packageManager
    val pkg = ctx.packageName

    val certBytes: ByteArray = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            val signingInfo = info.signingInfo
            val sigs = signingInfo.apkContentsSigners
            sigs.firstOrNull()?.toByteArray() ?: byteArrayOf()
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.toByteArray() ?: byteArrayOf()
        }
    } catch (_: Throwable) {
        byteArrayOf()
    }

    if (certBytes.isEmpty()) return ""
    return sha256HexBytes(certBytes)
}

private fun sha256Hex(input: String): String =
    sha256HexBytes(input.toByteArray(Charsets.UTF_8))

private fun sha256HexBytes(bytes: ByteArray): String {
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(bytes)
    val sb = StringBuilder(digest.size * 2)
    for (b in digest) {
        val v = b.toInt() and 0xff
        if (v < 16) sb.append('0')
        sb.append(v.toString(16))
    }
    return sb.toString()
}

private fun secureRandomHex(numBytes: Int): String {
    val rng = SecureRandom()
    val bytes = ByteArray(numBytes)
    rng.nextBytes(bytes)
    return sha256HexBytes(bytes) // compact + already-hex
}
