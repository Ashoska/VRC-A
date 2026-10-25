package com.vrca.ui.onboarding

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The image-store repo's `manifest.json` (user, 2026-10-25: pictures update from the repo without an app
 * update, and never leave old ones behind). The repo keeps every picture under `images/<group>/<name>`;
 * its `Rebuild manifest` Action lists each one's key (`tos/platform_rules`), path, SHA-256 and size here.
 * The app only ever asks for KEYS and takes the file (and whether it changed) from this list.
 *
 * Fetched at most once a minute; the last good copy is saved (`filesDir/image_manifest.json`) so pictures
 * already on the device still show offline.
 */
object ImageManifest {
    private const val TAG = "ImageManifest"
    const val BASE = "https://raw.githubusercontent.com/Ashoska/VRC-A-Image-store/main"
    private const val FILE = "image_manifest.json"
    private const val FRESH_MS = 60_000L

    data class Entry(val key: String, val path: String, val sha256: String, val bytes: Long) {
        /** The picture's own name in its group ("platform_rules" for "tos/platform_rules"). */
        val name: String get() = key.substringAfterLast('/')
        val ext: String get() = path.substringAfterLast('.', "png")
        val url: String get() = "$BASE/$path"
    }

    @Volatile private var entries: Map<String, Entry>? = null
    @Volatile private var fetchedAt = 0L
    private val lock = Mutex()

    /** The list as last known (memory, else the saved copy), without going to the network. */
    fun known(ctx: Context): Map<String, Entry>? = entries ?: runCatching {
        File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText()?.let(::parse)
    }.getOrNull()?.also { entries = it }

    /** The current list (fetched if older than a minute), or the last known one if the network fails. */
    suspend fun get(ctx: Context): Map<String, Entry>? = lock.withLock {
        val now = System.currentTimeMillis()
        entries?.let { if (now - fetchedAt < FRESH_MS) return@withLock it }
        val fresh = withContext(Dispatchers.IO) {
            try {
                val conn = (URL("$BASE/manifest.json").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000; readTimeout = 8000; useCaches = false
                }
                try {
                    if (conn.responseCode != 200) null
                    else conn.inputStream.bufferedReader().use { it.readText() }.let { text ->
                        parse(text).also { File(ctx.filesDir, FILE).writeText(text) }
                    }
                } finally { conn.disconnect() }
            } catch (e: Exception) {
                Log.w(TAG, "manifest fetch failed", e); null
            }
        }
        if (fresh != null) { entries = fresh; fetchedAt = now; fresh } else known(ctx)
    }

    /** Every entry of [group] ("tos"), by name. */
    fun group(all: Map<String, Entry>, group: String): List<Entry> = all.values.filter { it.key.startsWith("$group/") }

    /** A public URL for [key] that changes with the picture (for services that cache by URL, like Discord). */
    fun versionedUrl(ctx: Context, key: String): String? = known(ctx)?.get(key)?.let { "${it.url}?v=${it.sha256.take(12)}" }

    private fun parse(text: String): Map<String, Entry> {
        val images = JSONObject(text).getJSONObject("images")
        return images.keys().asSequence().associateWith { k ->
            val o = images.getJSONObject(k)
            Entry(k, o.getString("path"), o.getString("sha256"), o.optLong("bytes"))
        }
    }
}

/**
 * One group of on-demand pictures from the image-store repo ([ImageManifest]), downloaded into
 * `filesDir/<dirName>` only while a screen needs them and deleted when it's done. The tutorial
 * ([TutorialImageStore]) and the Terms of Service ([TosImageStore]) both use it with the same lifecycle:
 * fetched during the boot screen when the screen is coming up, fetched again by the screen as a fallback,
 * cleared when the user finishes it.
 *
 * Never shows an old picture (user, 2026-10-25): a picture is saved as `<name>.<sha12>.<ext>`, so a
 * picture replaced in the repo gets a new file; [cachedFileFor] only returns the file matching the current
 * list, and each [ensureDownloaded] deletes every other file in the folder (replaced or removed pictures,
 * half downloads). Downloads are checked against the list's SHA-256 and run side by side.
 */
class OnDemandImages(private val tag: String, private val dirName: String, private val group: String) {
    companion object {
        /** How long a download may go without a single byte before it counts as failed. */
        const val STALL_MS = 10_000L
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    }

    private fun dir(ctx: Context): File = File(ctx.filesDir, dirName)
    private fun fileOf(ctx: Context, e: ImageManifest.Entry) = File(dir(ctx), "${e.name}.${e.sha256.take(12)}.${e.ext}")

    /** The names in this group as last known (memory or the saved list), without going to the network. */
    fun knownNames(ctx: Context): List<String> =
        ImageManifest.known(ctx)?.let { ImageManifest.group(it, group).map { e -> e.name }.sorted() }.orEmpty()

    /** The downloaded, up-to-date file for [name], else null (also null while the list isn't known). */
    fun cachedFileFor(ctx: Context, name: String): File? {
        val e = ImageManifest.known(ctx)?.get("$group/$name") ?: return null
        return fileOf(ctx, e).takeIf { it.exists() && it.length() == e.bytes }
    }

    /**
     * Brings the folder in line with the current list: downloads what's missing or changed, deletes
     * everything else. True when every picture of the group is present afterwards; false if the list
     * couldn't be read, a download failed, or nothing arrived for [stallMs] (a slow but moving download
     * is never cut off: user, 2026-10-25, people on bad internet). A stop keeps the half downloads, so
     * the next call resumes them. Idempotent; cancelling it stops the downloads at once.
     */
    suspend fun ensureDownloaded(ctx: Context, stallMs: Long = STALL_MS): Boolean {
        val all = ImageManifest.get(ctx) ?: return false
        val want = ImageManifest.group(all, group)
        return withContext(Dispatchers.IO) {
            val d = dir(ctx).apply { mkdirs() }
            // Keep the current pictures and their half downloads (a slow connection resumes them).
            val keep = want.flatMap { fileOf(ctx, it).name.let { n -> listOf(n, "$n.part") } }.toSet()
            d.listFiles()?.forEach { if (it.name !in keep) it.delete() }
            val pending = want.filterNot { e -> fileOf(ctx, e).let { it.exists() && it.length() == e.bytes } }
            if (pending.isEmpty()) return@withContext true
            val lastByte = AtomicLong(System.nanoTime())
            val stalled = AtomicBoolean(false)
            try {
                coroutineScope {
                    val watch = launch {
                        while (true) {
                            delay(500)
                            if ((System.nanoTime() - lastByte.get()) / 1_000_000 > stallMs) { stalled.set(true); this@coroutineScope.cancel() }
                        }
                    }
                    pending.map { e -> async { download(ctx, e, lastByte) } }.awaitAll().all { it }.also { watch.cancel() }
                }
            } catch (c: CancellationException) {
                if (stalled.get() && isActive) { Log.w(tag, "$group: no progress for $stallMs ms"); false } else throw c
            }
        }
    }

    /**
     * Just the picture [name], ahead of the rest of the group: what a screen asks for when it needs that
     * picture now (on a slow connection it would otherwise share the line with the whole group). Null if
     * it couldn't be fetched (same stall rule as [ensureDownloaded]).
     */
    suspend fun fetch(ctx: Context, name: String, stallMs: Long = STALL_MS): File? {
        val e = ImageManifest.get(ctx)?.get("$group/$name") ?: return null
        cachedFileFor(ctx, name)?.let { return it }
        val lastByte = AtomicLong(System.nanoTime())
        val ok = withContext(Dispatchers.IO) {
            dir(ctx).mkdirs()
            val stalled = AtomicBoolean(false)
            try {
                coroutineScope {
                    val watch = launch {
                        while (true) {
                            delay(500)
                            if ((System.nanoTime() - lastByte.get()) / 1_000_000 > stallMs) { stalled.set(true); this@coroutineScope.cancel() }
                        }
                    }
                    download(ctx, e, lastByte).also { watch.cancel() }
                }
            } catch (c: CancellationException) {
                if (stalled.get() && isActive) false else throw c
            }
        }
        return if (ok) cachedFileFor(ctx, name) else null
    }

    private suspend fun download(ctx: Context, e: ImageManifest.Entry, lastByte: AtomicLong): Boolean =
        // One download per file at a time (the group fetch and a screen's own fetch can ask together);
        // whoever waited finds the file done.
        locks.getOrPut(fileOf(ctx, e).path) { Mutex() }.withLock {
            fileOf(ctx, e).let { if (it.exists() && it.length() == e.bytes) return@withLock true }
            downloadNow(ctx, e, lastByte)
        }

    private suspend fun downloadNow(ctx: Context, e: ImageManifest.Entry, lastByte: AtomicLong): Boolean {
        val target = fileOf(ctx, e)
        val tmp = File(target.parentFile, target.name + ".part")
        val job = currentCoroutineContext()[Job]
        return try {
            // Resume a half download (a stalled boot check or a dropped connection keeps what it got, so
            // on a slow connection each retry gets further instead of starting over).
            val have = tmp.length().takeIf { tmp.exists() && it < e.bytes } ?: run { tmp.delete(); 0L }
            val conn = (URL(e.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 8000; useCaches = false
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            // Cancelling (a stall, or the screen going away) closes the connection, which ends a blocked read.
            val closer = job?.invokeOnCompletion { if (it != null) conn.disconnect() }
            try {
                val resumed = have > 0 && conn.responseCode == 206
                val sha = MessageDigest.getInstance("SHA-256")
                if (resumed) tmp.inputStream().use { input -> hashInto(sha, input) }
                // Written to a .part file and checked before the rename, so a partial or wrong download
                // never counts as the picture.
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(tmp, resumed).use { out ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf); if (n < 0) break
                            ImageLab.throttle(n)
                            sha.update(buf, 0, n); out.write(buf, 0, n)
                            lastByte.set(System.nanoTime())
                        }
                    }
                }
                val got = sha.digest().joinToString("") { "%02x".format(it) }
                if (got == e.sha256) tmp.renameTo(target) else { Log.w(tag, "${e.key}: checksum mismatch"); tmp.delete(); false }
            } finally { closer?.dispose(); conn.disconnect() }
        } catch (c: CancellationException) {
            throw c
        } catch (ex: Exception) {
            currentCoroutineContext().ensureActive()
            Log.w(tag, "download ${e.key} stopped at ${tmp.length()} of ${e.bytes} bytes", ex); false
        }
    }

    private fun hashInto(sha: MessageDigest, input: java.io.InputStream) {
        val buf = ByteArray(16 * 1024)
        while (true) { val n = input.read(buf); if (n < 0) break; sha.update(buf, 0, n) }
    }

    /** Deletes every downloaded picture of this group (frees the disk once the screen is done). */
    fun clear(ctx: Context) {
        try {
            dir(ctx).takeIf { it.exists() }?.deleteRecursively()
        } catch (e: Exception) {
            Log.w(tag, "clear failed", e)
        }
    }
}

/**
 * The Terms of Service pictures (`images/tos/` in the image-store repo; the terms refer to them by name,
 * so a new picture there needs no app update). Lifecycle, like the tutorial's:
 *  - Boot: when the terms need accepting, the Terms row fetches them while the boot screen is up
 *    (AppBoot `terms`); a failure holds boot on Retry like any check.
 *  - The terms screen fetches any still missing as a fallback (TosImage).
 *  - Accepting the terms clears them (VrcaApp); strays (killed between accepting and the clear) go at
 *    the next boot whose Terms check finds them accepted.
 */
object TosImageStore {
    val images = OnDemandImages("TosImageStore", "tos_images", "tos")
    /** Just this picture, ahead of the others (the screen showing it now). */
    suspend fun fetch(ctx: Context, name: String): File? = images.fetch(ctx, name)
    suspend fun ensureDownloaded(ctx: Context): Boolean = images.ensureDownloaded(ctx)
    fun cachedFileFor(ctx: Context, name: String): File? = images.cachedFileFor(ctx, name)
    fun clear(ctx: Context) = images.clear(ctx)
}

/**
 * UI Lab only (never set by the app): slows the picture downloads to a given speed, shared by every
 * download at once like a real slow connection, and can cut the connection after some bytes, so the lab
 * can show what people on bad internet get.
 */
object ImageLab {
    @JvmField @Volatile var bytesPerSecond = 0L
    /** After this many bytes (all downloads together) nothing more arrives until it's set back to -1. */
    @JvmField @Volatile var dropAfterBytes = -1L
    private var budgetAt = 0L
    private var total = 0L

    /** Waits as long as [n] more bytes would take at [bytesPerSecond], or while the connection is "dropped". */
    suspend fun throttle(n: Int) {
        if (bytesPerSecond <= 0 && dropAfterBytes < 0) return
        val passed = synchronized(this) { total += n; total }
        while (dropAfterBytes in 0..passed) delay(200)
        val bps = bytesPerSecond
        if (bps <= 0) return
        val wait = synchronized(this) {
            val now = System.nanoTime()
            if (budgetAt < now) budgetAt = now
            budgetAt += n * 1_000_000_000L / bps
            (budgetAt - now) / 1_000_000L
        }
        if (wait > 0) delay(wait)
    }

    fun reset() = synchronized(this) { bytesPerSecond = 0; dropAfterBytes = -1; budgetAt = 0; total = 0 }
}
