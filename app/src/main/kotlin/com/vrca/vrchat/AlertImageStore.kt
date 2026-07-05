package com.vrca.vrchat

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Persistent image store for in-app alert banners/thumbnails (event + announcement
 * banners, invite/instance-history world images). Images are downloaded ONCE when
 * the notification fires (or when info first resolves) into app files, keyed by a
 * hash of their URL — so reopening the notifications menu never re-hits the network
 * and the image can't flash/reload.
 *
 * Lifecycle mirrors the alerts themselves: a file lives until NOTHING references
 * its URL anymore — alert dismissal and the instance-history 24h prune drop the
 * references, and [gc] then deletes the orphaned files. One file per unique URL, so
 * two alerts sharing a world image share one file (which is why deletion is a
 * reference sweep rather than delete-on-dismiss). Size is naturally tiny (VRChat
 * thumbnails are ~50-150 KB; even hundreds of undismissed alerts is single-digit MB),
 * so there is deliberately NO cap.
 */
object AlertImageStore {
    private const val TAG = "AlertImageStore"
    private const val DIR = "alert_images"

    private fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }

    private fun keyFor(url: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun fileFor(ctx: Context, url: String): File = File(dir(ctx), "${keyFor(url)}.img")

    /** The cached file for [url], or null if it hasn't been downloaded (yet). */
    fun resolve(ctx: Context, url: String?): File? {
        if (url.isNullOrBlank()) return null
        val f = fileFor(ctx, url)
        return if (f.exists() && f.length() > 0) f else null
    }

    /**
     * Downloads [url] into the store if not already present. VRChat-hosted images
     * (api.vrchat.cloud file endpoints) need the session cookie; other hosts are
     * fetched plain. Failures are non-fatal (the UI falls back to a network load
     * through Coil). Returns the file, or null on failure.
     */
    suspend fun ensureCached(ctx: Context, url: String?): File? = withContext(Dispatchers.IO) {
        if (url.isNullOrBlank() || !url.startsWith("http")) return@withContext null
        val f = fileFor(ctx, url)
        if (f.exists() && f.length() > 0) return@withContext f
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", "VRC-A/1.0")
                val host = URL(url).host.orEmpty()
                if (host.endsWith("vrchat.cloud") || host.endsWith("vrchat.com")) {
                    VrchatAuthManager.getCookieHeader(ctx)?.let { setRequestProperty("Cookie", it) }
                }
            }
            if (conn.responseCode != 200) {
                Log.w(TAG, "download ${conn.responseCode} for ${url.take(80)}")
                return@withContext null
            }
            val tmp = File(f.parentFile, "${f.name}.tmp")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (tmp.length() > 0 && tmp.renameTo(f)) f else { tmp.delete(); null }
        } catch (e: Exception) {
            Log.w(TAG, "download failed for ${url.take(80)}", e)
            null
        }
    }

    /** Fire-and-forget [ensureCached] from non-suspend contexts. */
    fun ensureCachedAsync(ctx: Context, url: String?) {
        if (url.isNullOrBlank()) return
        Thread {
            try {
                kotlinx.coroutines.runBlocking { ensureCached(ctx.applicationContext, url) }
            } catch (_: Throwable) {}
        }.start()
    }

    /**
     * Deletes every stored file whose URL is no longer referenced by (a) any in-app
     * alert event or (b) any instance-history entry (its 24h prune drops old
     * references naturally). Called after dismiss / dismiss-all and once at load.
     */
    fun gc(ctx: Context) {
        Thread {
            try {
                val referenced = HashSet<String>()
                for (g in InAppAlertState.groups.value) {
                    for (e in g.events) e.imageUrl?.takeIf { it.isNotBlank() }?.let { referenced.add(keyFor(it)) }
                }
                for (entry in InstanceHistoryStore.list(ctx)) {
                    entry.imageUrl.takeIf { it.isNotBlank() }?.let { referenced.add(keyFor(it)) }
                }
                val files = dir(ctx).listFiles() ?: return@Thread
                var removed = 0
                for (f in files) {
                    val key = f.name.removeSuffix(".img").removeSuffix(".tmp")
                    if (key !in referenced) { if (f.delete()) removed++ }
                }
                if (removed > 0) Log.i(TAG, "gc removed $removed unreferenced image(s)")
            } catch (e: Throwable) {
                Log.w(TAG, "gc failed", e)
            }
        }.start()
    }
}
