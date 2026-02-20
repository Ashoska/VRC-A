// app/src/main/kotlin/com/scrapw/chatbox/MainActivity.kt
package com.scrapw.chatbox

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
import com.scrapw.chatbox.keepalive.ChatboxKeepAliveService
import com.scrapw.chatbox.overlay.OverlayDaemon
import com.scrapw.chatbox.ui.theme.ChatboxTheme
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
 * - ANDROID_ID can change on factory reset; thatâ€™s expected.
 * - Some OEMs/work profiles can return blank ANDROID_ID; fallback covers it.
 */
class MainActivity : ComponentActivity() {

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Even if denied, still try to start (some devices allow FGS without notif permission).
        ChatboxKeepAliveService.start(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        // âœ… Ensure device hash exists early (before any screen reads it).
        runCatching { ensureDeviceHash(applicationContext) }

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
                // App routing (ToS gate + Admin-only UI) lives in ChatboxApp.kt
                ChatboxApp()

                // âœ… Overlay MUST stay inside Compose because OverlayDaemon is @Composable
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

    // If already present, return it.
    val existing = prefs.getString(KEY_DEVICE_HASH, "")?.trim().orEmpty()
    if (existing.isNotBlank()) return existing

    val androidId = runCatching {
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
            ?.trim()
            .orEmpty()
    }.getOrDefault("")

    val signingDigest = runCatching { signingCertSha256Hex(ctx) }.getOrDefault("")

    // Primary (reinstall-stable if ANDROID_ID is stable):
    val computedStable = if (androidId.isNotBlank() && signingDigest.isNotBlank()) {
        sha256Hex("v2:$androidId:$signingDigest")
    } else if (androidId.isNotBlank()) {
        // Still stable-ish if we can't read cert for some reason.
        sha256Hex("v2:$androidId:no_signing")
    } else {
        ""
    }

    // Fallback: stored random (NOT reinstall stable, but keeps app functional on devices with blank ANDROID_ID)
    val finalHash = if (computedStable.isNotBlank()) {
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

    prefs.edit().putString(KEY_DEVICE_HASH, finalHash).apply()
    return finalHash
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
