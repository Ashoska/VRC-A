package com.vrca.ui.onboarding

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * A set of pictures that are NOT bundled in the APK: they're downloaded from the VRC-A image-store repo
 * into `filesDir/<dirName>` only while a screen needs them, and deleted when it's done. The tutorial
 * ([TutorialImageStore]) and the Terms of Service ([TosImageStore]) both use it, with the same
 * lifecycle: fetched during the boot screen when the screen is coming up, fetched again by the screen
 * itself as a fallback, cleared when the user finishes it (user, 2026-10-25: the terms' pictures use the
 * tutorial's system). Updating a picture = replacing the file in the image-store repo.
 *
 * [names] are file names inside [remoteDir] of the repo ("" = its root).
 */
class OnDemandImages(private val tag: String, private val dirName: String, private val remoteDir: String, val names: List<String>) {
    private fun dir(ctx: Context): File = File(ctx.filesDir, dirName)

    /** Local file for [name], whether or not it exists yet. */
    fun fileFor(ctx: Context, name: String): File = File(dir(ctx), name)

    /** The downloaded file for [name] if present and non-empty, else null. */
    fun cachedFileFor(ctx: Context, name: String): File? = fileFor(ctx, name).takeIf { it.exists() && it.length() > 0 }

    /** True when every picture is present and non-empty. */
    fun isCached(ctx: Context): Boolean = names.all { cachedFileFor(ctx, it) != null }

    /**
     * Downloads any missing picture. Idempotent (a cached one is skipped), so boot's prefetch, the
     * screen's entry and its fallback can all call it. True when all are present afterwards. Safe to
     * wrap in `withTimeoutOrNull` so a slow or missing network never hangs boot.
     */
    suspend fun ensureDownloaded(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val d = dir(ctx)
        if (!d.exists()) d.mkdirs()
        val base = if (remoteDir.isEmpty()) BASE else "$BASE/$remoteDir"
        for (name in names) {
            if (cachedFileFor(ctx, name) != null) continue
            try {
                val conn = (URL("$base/$name").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    useCaches = false
                    requestMethod = "GET"
                }
                try {
                    // Write to a .part file then rename, so a partial/failed download never leaves a
                    // truncated picture that isCached trusts.
                    val tmp = File(d, "$name.part")
                    conn.inputStream.use { input -> tmp.outputStream().use { out -> input.copyTo(out) } }
                    if (tmp.length() > 0) tmp.renameTo(fileFor(ctx, name)) else tmp.delete()
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(tag, "download $name failed", e)
            }
        }
        isCached(ctx)
    }

    /** Deletes every downloaded picture of this set (frees the disk once the screen is done). */
    fun clear(ctx: Context) {
        try {
            val d = dir(ctx)
            if (d.exists()) d.deleteRecursively()
        } catch (e: Exception) {
            Log.w(tag, "clear failed", e)
        }
    }

    companion object {
        // The image-store repo (renamed from VRChat-rpc-display); the Discord RPC default image uses it too.
        private const val BASE = "https://raw.githubusercontent.com/Ashoska/VRC-A-Image-store/main"
    }
}

/**
 * The Terms of Service pictures (`tos/` in the image-store repo). Lifecycle, like the tutorial's:
 *  - Boot: when the terms need accepting, the Terms row fetches them while the boot screen is up
 *    (AppBoot `terms`); a failure holds boot on Retry like any check.
 *  - The terms screen fetches any still missing as a fallback (TosImage).
 *  - Accepting the terms clears them (VrcaApp); strays (killed between accepting and the clear) go at
 *    the next boot whose Terms check finds them accepted.
 * The names are fixed here until the admin-edited terms document lists its own pictures.
 */
object TosImageStore {
    val images = OnDemandImages("TosImageStore", "tos_images", "tos", listOf("tos_platform_rules.png", "tos_login_centre.png", "tos_bugs_reports.png"))
    suspend fun ensureDownloaded(ctx: Context): Boolean = images.ensureDownloaded(ctx)
    fun cachedFileFor(ctx: Context, name: String): File? = images.cachedFileFor(ctx, name)
    fun clear(ctx: Context) = images.clear(ctx)
}
