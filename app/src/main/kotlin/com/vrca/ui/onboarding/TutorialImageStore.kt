package com.vrca.ui.onboarding

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * On-demand store for the OSC-tutorial instruction images. They are NOT bundled
 * in the APK — they only appear during first-run onboarding / replay, so shipping
 * ~600 KB of PNGs for a one-time screen was pure bloat (removing them nearly
 * halved the APK once before). Instead they're downloaded from the VRC-A image
 * store GitHub repo into `filesDir/tutorial_images` and deleted the moment the
 * tutorial is done.
 *
 * Lifecycle (owned by the callers):
 *  - New-user boot (onboarding not complete): [ensureDownloaded] prefetches them
 *    during the boot screen — this REPLACES the old fixed "new user" floor with
 *    real work (VrcaApp).
 *  - Replay (Settings): [ensureDownloaded] re-downloads on tutorial entry
 *    (OnboardingFlow).
 *  - Tutorial COMPLETE or replay EXIT: [clear] deletes them (VrcaApp `onFinish`).
 *  - A new user who closes/kills the app MID-tutorial keeps the images (onboarding
 *    isn't complete, so no cleanup runs) → resume works.
 *  - A replay killed mid-way leaves strays: on the next fresh process onboarding
 *    is already complete, so [clear] wipes them (VrcaApplication.onCreate).
 */
object TutorialImageStore {
    private const val TAG = "TutorialImageStore"
    private const val DIR = "tutorial_images"
    const val COUNT = 4
    // The image-store repo (renamed from VRChat-rpc-display). Same host the
    // Discord RPC default image uses.
    private const val BASE =
        "https://raw.githubusercontent.com/Ashoska/VRC-A-Image-store/main"

    private fun dir(ctx: Context): File = File(ctx.filesDir, DIR)

    /** Local file for tutorial image [n] (1-based), whether or not it exists yet. */
    fun fileFor(ctx: Context, n: Int): File = File(dir(ctx), "osc_tutorial_$n.png")

    /** The downloaded file for [n] if present and non-empty, else null. */
    fun cachedFileFor(ctx: Context, n: Int): File? =
        fileFor(ctx, n).takeIf { it.exists() && it.length() > 0 }

    /** True when all [COUNT] images are present and non-empty. */
    fun isCached(ctx: Context): Boolean =
        (1..COUNT).all { cachedFileFor(ctx, it) != null }

    /**
     * Downloads any missing tutorial image into `filesDir`. Idempotent — an
     * already-cached image is skipped, so this is cheap to call repeatedly (boot
     * prefetch, replay entry, and the composable's own fallback all call it).
     * Returns true when all images are present afterwards. Safe to wrap in
     * `withTimeoutOrNull` so a slow/no network never hangs boot.
     */
    suspend fun ensureDownloaded(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val d = dir(ctx)
        if (!d.exists()) d.mkdirs()
        for (n in 1..COUNT) {
            if (cachedFileFor(ctx, n) != null) continue
            try {
                val conn = (URL("$BASE/osc_tutorial_$n.png").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    useCaches = false
                    requestMethod = "GET"
                }
                try {
                    // Write to a .part file then rename, so a partial/failed
                    // download never leaves a truncated image that isCached trusts.
                    val tmp = File(d, "osc_tutorial_$n.png.part")
                    conn.inputStream.use { input ->
                        tmp.outputStream().use { out -> input.copyTo(out) }
                    }
                    if (tmp.length() > 0) tmp.renameTo(fileFor(ctx, n)) else tmp.delete()
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "download osc_tutorial_$n failed", e)
            }
        }
        isCached(ctx)
    }

    /** Deletes all downloaded tutorial images (frees disk once the tutorial ends). */
    fun clear(ctx: Context) {
        try {
            val d = dir(ctx)
            if (d.exists()) d.deleteRecursively()
        } catch (e: Exception) {
            Log.w(TAG, "clear failed", e)
        }
    }
}
