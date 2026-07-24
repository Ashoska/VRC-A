package com.vrca.richcontent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * On-demand cache for rich-content media (announcement/update images, videos,
 * posters). Modeled on [com.vrca.vrchat.AlertImageStore] +
 * [com.vrca.ui.onboarding.TutorialImageStore]: download ONCE into app files,
 * `.part`-then-rename so a partial never looks complete, keyed by SHA-1 of the URL.
 *
 * TWO scopes with independent lifecycles (kept in separate subdirs so their GC
 * never fights):
 *  - [Scope.ANNOUNCEMENT] — persistent, admin-controlled. References = every media
 *    URL across the currently-active announcements. [gcAnnouncements] deletes any
 *    `ann/` file not referenced, so an admin removing/swapping media culls it from
 *    the user's storage immediately.
 *  - [Scope.UPDATE] — ephemeral, view-scoped. Downloaded when the update popup /
 *    Settings "What's New" opens; [clearUpdateMedia] wipes it on close. The patch
 *    notes TEXT stays cached elsewhere (tiny); only the heavy media is transient.
 *
 * Media lives on GitHub/jsDelivr (public) so no auth cookie is attached.
 */
object RichMediaStore {
    private const val TAG = "RichMediaStore"
    private const val ROOT = "rich_media"

    enum class Scope(val dirName: String) { ANNOUNCEMENT("ann"), UPDATE("upd") }

    private fun root(ctx: Context): File = File(ctx.filesDir, ROOT)
    private fun scopeDir(ctx: Context, scope: Scope): File =
        File(root(ctx), scope.dirName).apply { mkdirs() }

    private fun keyFor(url: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun fileFor(ctx: Context, url: String, scope: Scope): File =
        File(scopeDir(ctx, scope), "${keyFor(url)}.bin")

    /** Cached file for [url] in EITHER scope, or null if not downloaded yet. */
    fun resolve(ctx: Context, url: String?): File? {
        if (url.isNullOrBlank()) return null
        for (s in Scope.entries) {
            val f = fileFor(ctx, url, s)
            if (f.exists() && f.length() > 0) return f
        }
        return null
    }

    /**
     * Downloads [url] into [scope] if not already present IN THAT SCOPE. Returns the
     * file, or null on failure (the UI falls back to a plain Coil network load).
     * Scope-local on purpose: a URL used by both an announcement and an update keeps
     * two copies so [clearUpdateMedia] can never delete an announcement's media.
     */
    suspend fun ensureCached(ctx: Context, url: String?, scope: Scope): File? =
        withContext(Dispatchers.IO) {
            if (url.isNullOrBlank() || !url.startsWith("http")) return@withContext null
            val f = fileFor(ctx, url, scope)
            if (f.exists() && f.length() > 0) return@withContext f
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    useCaches = false
                    setRequestProperty("User-Agent", "VRC-A/1.0")
                }
                try {
                    if (conn.responseCode != 200) {
                        Log.w(TAG, "download ${conn.responseCode} for ${url.take(80)}")
                        return@withContext null
                    }
                    val tmp = File(f.parentFile, "${f.name}.part")
                    conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    if (tmp.length() > 0 && tmp.renameTo(f)) f else { tmp.delete(); null }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "download failed for ${url.take(80)}", e)
                null
            }
        }

    /** Fire-and-forget [ensureCached] from non-suspend contexts. */
    fun ensureCachedAsync(ctx: Context, url: String?, scope: Scope) {
        if (url.isNullOrBlank()) return
        val app = ctx.applicationContext
        Thread {
            try { kotlinx.coroutines.runBlocking { ensureCached(app, url, scope) } } catch (_: Throwable) {}
        }.start()
    }

    /** Deletes announcement-scoped files whose URL isn't in [referencedUrls]. */
    fun gcAnnouncements(ctx: Context, referencedUrls: Set<String>) {
        Thread {
            try {
                val keep = referencedUrls.asSequence()
                    .filter { it.isNotBlank() }
                    .map { keyFor(it) }
                    .toHashSet()
                val files = scopeDir(ctx, Scope.ANNOUNCEMENT).listFiles() ?: return@Thread
                var removed = 0
                for (file in files) {
                    val key = file.name.removeSuffix(".bin").removeSuffix(".part")
                    if (key !in keep) { if (file.delete()) removed++ }
                }
                if (removed > 0) Log.i(TAG, "gc removed $removed unreferenced media file(s)")
            } catch (e: Throwable) {
                Log.w(TAG, "gcAnnouncements failed", e)
            }
        }.start()
    }

    /** Wipes all update-scoped media (called on update popup / What's New close). */
    fun clearUpdateMedia(ctx: Context) {
        try {
            val d = File(root(ctx), Scope.UPDATE.dirName)
            if (d.exists()) d.deleteRecursively()
        } catch (e: Exception) {
            Log.w(TAG, "clearUpdateMedia failed", e)
        }
    }
}
