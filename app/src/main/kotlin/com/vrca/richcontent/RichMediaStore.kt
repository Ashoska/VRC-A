package com.vrca.richcontent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

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

    /**
     * Shared OkHttp client that FOLLOWS REDIRECTS across hosts + protocols. This is
     * essential: media URLs are GitHub release-download links
     * (`github.com/.../releases/download/rich-media/<file>`) that 302-redirect to
     * `objects.githubusercontent.com`. HttpURLConnection would not reliably follow
     * that cross-host redirect, so a video never cached locally and MediaPlayer
     * couldn't stream the redirect either. Generous timeouts so a multi-MB video on
     * a slow connection isn't cut off mid-download.
     */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }

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
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "VRC-A/1.0")
                    .header("Accept", "*/*")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "download ${resp.code} for ${url.take(80)}")
                        return@withContext null
                    }
                    val body = resp.body ?: run {
                        Log.w(TAG, "empty body for ${url.take(80)}")
                        return@withContext null
                    }
                    val tmp = File(f.parentFile, "${f.name}.part")
                    body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    if (tmp.length() > 0 && tmp.renameTo(f)) {
                        Log.i(TAG, "cached ${tmp.length()}B for ${url.take(80)}")
                        f
                    } else {
                        tmp.delete(); null
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "download failed for ${url.take(80)}", e)
                null
            }
        }

    /**
     * Reconcile a scope to EXACTLY the currently-referenced media: delete every file
     * whose URL isn't in [referencedUrls], and nothing else. This is the whole culling
     * model — purely REFERENCE-based, NO size cap / LRU eviction, so active media is
     * NEVER evicted (required now that video plays from the local file only, with no
     * URL-streaming fallback — an evicted active video would break). Storage is thus
     * exactly "the media of your active content"; when the admin removes/swaps
     * content, the next reconcile frees it. [gcAnnouncements] is this for ann/.
     */
    private fun reconcile(ctx: Context, scope: Scope, referencedUrls: Set<String>) {
        try {
            val keep = referencedUrls.asSequence()
                .filter { it.isNotBlank() }
                .map { keyFor(it) }
                .toHashSet()
            val files = scopeDir(ctx, scope).listFiles() ?: return
            var removed = 0
            for (file in files) {
                val key = file.name.removeSuffix(".bin").removeSuffix(".part")
                if (key !in keep) { if (file.delete()) removed++ }
            }
            if (removed > 0) Log.i(TAG, "reconcile($scope) removed $removed unreferenced file(s)")
        } catch (e: Throwable) {
            Log.w(TAG, "reconcile($scope) failed", e)
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

    /**
     * Cull ann/ down to EXACTLY the media referenced by the active announcements.
     * Purely reference-based (no size limits) — [referencedUrls] is the FULL set of
     * media URLs across every active announcement, so anything else is genuinely
     * orphaned (a removed/swapped announcement) and safe to delete.
     *
     * [confirmed] MUST be true — the caller has actually loaded the announcements
     * list. Never cull on an unconfirmed/empty set (that would delete all cached
     * media before the list loads, forcing a needless re-download of active media).
     */
    fun gcAnnouncements(ctx: Context, referencedUrls: Set<String>, confirmed: Boolean = true) {
        if (!confirmed) return
        Thread { reconcile(ctx, Scope.ANNOUNCEMENT, referencedUrls) }.start()
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
