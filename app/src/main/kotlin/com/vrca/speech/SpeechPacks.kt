package com.vrca.speech

import android.content.Context
import android.os.StatFs
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Install/uninstall of offline voice packs (see [SpeechCatalog]). Plain Android code —
 * no speech engine — so it lives in the shared source set.
 *
 * Packs live in `filesDir/stt/<packId>/` (NOT cacheDir: the OS may wipe cache, and a
 * pack is hundreds of MB). A pack counts as installed only when a `.ok` marker exists
 * AND every file has its exact catalog size; the marker is written only after every
 * file passed its SHA-256 check. Downloads resume from a `.part` file after a drop.
 */
object SpeechPacks {
    private const val TAG = "SpeechPacks"
    private const val PREFS = "vrca_speech"
    private const val KEY_LANG = "lang"
    private const val OK_MARKER = ".ok"
    private const val FREE_SPACE_MARGIN = 50L * 1024 * 1024

    private val downloading = AtomicBoolean(false)
    @Volatile private var cancelRequested = false

    fun isDownloading(): Boolean = downloading.get()

    private fun baseDir(ctx: Context) = File(ctx.filesDir, "stt")
    fun packDir(ctx: Context, packId: String) = File(baseDir(ctx), packId)
    fun filePath(ctx: Context, packId: String, name: String): String = File(packDir(ctx, packId), name).absolutePath

    fun isInstalled(ctx: Context, packId: String): Boolean {
        val pack = SpeechCatalog.pack(packId) ?: return false
        val dir = packDir(ctx, packId)
        if (!File(dir, OK_MARKER).exists()) return false
        return pack.files.all { File(dir, it.name).length() == it.size }
    }

    /** The tier (pack) the user chose for [langCode]; defaults to its best tier. */
    fun selectedPackId(ctx: Context, langCode: String): String? {
        val lang = SpeechCatalog.lang(langCode) ?: return null
        val saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("tier_$langCode", null)
        return lang.tier(saved).packId
    }

    fun selectedPack(ctx: Context, langCode: String): SpeechCatalog.Pack? =
        SpeechCatalog.packFor(langCode, selectedPackId(ctx, langCode))

    /** Ready to dictate in [langCode]: its chosen pack + the shared voice detector. */
    fun isLanguageReady(ctx: Context, langCode: String): Boolean {
        val pack = selectedPack(ctx, langCode) ?: return false
        return isInstalled(ctx, pack.id) && isInstalled(ctx, SpeechCatalog.VAD.id)
    }

    fun installedPacks(ctx: Context): List<SpeechCatalog.Pack> =
        SpeechCatalog.packs.values.filter { isInstalled(ctx, it.id) }

    fun selectedLanguage(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANG, null)
            ?.takeIf { SpeechCatalog.lang(it) != null } ?: SpeechCatalog.defaultLanguage()

    /** Live words (re-read while you talk) vs phrases only (cheapest). Default: live. */
    fun liveEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("live", true)

    fun setLiveEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("live", on).commit()
    }

    /** Mic sensitivity (Soft voice / Normal / Noisy room). Default: Normal. */
    fun sensitivity(ctx: Context): MicSensitivity =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("sensitivity", null)
            ?.let { n -> MicSensitivity.entries.firstOrNull { it.name == n } } ?: MicSensitivity.NORMAL

    fun setSensitivity(ctx: Context, s: MicSensitivity) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("sensitivity", s.name).commit()
    }

    /** Listen trigger: the VRChat avatar parameter (OSCQuery) that gates dictation, null =
     *  always listen; [listenWhenOn] = listen while it's on (else while it's off). */
    fun listenParam(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("listen_param", null)

    fun listenWhenOn(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("listen_when_on", true)

    fun setListenTrigger(ctx: Context, param: String?, whenOn: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("listen_param", param).putBoolean("listen_when_on", whenOn).commit()
    }

    /** Select [code] as the dictation language, using tier [packId] for it. */
    fun setSelected(ctx: Context, code: String, packId: String) {
        // commit (not apply): written before returning, so closing the app right after
        // picking can't lose the choice.
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LANG, code).putString("tier_$code", packId).commit()
    }

    /** Remove a language pack; the shared voice detector goes too once no pack is left. */
    fun deletePack(ctx: Context, packId: String) {
        packDir(ctx, packId).deleteRecursively()
        if (installedPacks(ctx).isEmpty()) packDir(ctx, SpeechCatalog.VAD.id).deleteRecursively()
    }

    /** Free the old Vosk models from the first voice-to-text build (up to ~200 MB). */
    fun cleanupLegacy(ctx: Context) {
        ctx.filesDir.listFiles()?.forEach { f ->
            if (f.isDirectory && (f.name == "vosk-model" || f.name.startsWith("vosk-model-"))) f.deleteRecursively()
        }
        // Packs the catalog no longer offers (e.g. GigaAM v2, replaced by v3) would sit
        // invisible in storage forever (Settings only lists catalog packs): delete them.
        baseDir(ctx).listFiles()?.forEach { d ->
            if (d.isDirectory && SpeechCatalog.pack(d.name) == null) d.deleteRecursively()
        }
    }

    fun cancelDownload() { cancelRequested = true }

    /**
     * Download + verify everything [langCode]'s chosen tier needs, on a background thread.
     * [onProgress] gets (percent 0..100, verifying) — percent covers all files by bytes.
     * Callbacks arrive on the worker thread.
     */
    fun downloadLanguage(
        ctx: Context,
        langCode: String,
        onProgress: (Int, Boolean) -> Unit,
        onDone: (Boolean, String?) -> Unit,
    ) {
        val pack = selectedPack(ctx, langCode) ?: return onDone(false, "Unknown language")
        if (!downloading.compareAndSet(false, true)) return onDone(false, "A download is already running")
        cancelRequested = false
        val app = ctx.applicationContext
        Thread({
            try {
                val todo = listOf(SpeechCatalog.VAD, pack).filter { !isInstalled(app, it.id) }
                val total = todo.sumOf { it.sizeBytes }.coerceAtLeast(1)
                val have = todo.sumOf { p -> p.files.sumOf { f -> partialBytes(app, p.id, f) } }
                val free = StatFs(app.filesDir.absolutePath).availableBytes
                if (free < (total - have) + FREE_SPACE_MARGIN) {
                    onDone(false, "Not enough storage: needs ${mb(total - have + FREE_SPACE_MARGIN)} free, ${mb(free)} available")
                    return@Thread
                }
                var done = have
                var lastPct = -1
                fun report(verifying: Boolean) {
                    val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                    if (pct != lastPct || verifying) { lastPct = pct; onProgress(pct, verifying) }
                }
                for (p in todo) {
                    val dir = packDir(app, p.id).apply { mkdirs() }
                    File(dir, OK_MARKER).delete()
                    for (f in p.files) {
                        val dest = File(dir, f.name)
                        if (dest.length() == f.size) continue // already complete from an earlier run
                        val before = partialBytes(app, p.id, f)
                        val base = done
                        fetch(f, dest) { got -> done = base + (got - before); report(false) }
                        if (cancelRequested) { onDone(false, "Cancelled"); return@Thread }
                        done = base + (f.size - before)
                    }
                    onProgress(lastPct.coerceAtLeast(0), true)
                    for (f in p.files) {
                        val dest = File(dir, f.name)
                        if (sha256(dest) != f.sha256) {
                            dest.delete()
                            onDone(false, "Download of ${f.name} was corrupted — please try again")
                            return@Thread
                        }
                    }
                    File(dir, OK_MARKER).writeText(System.currentTimeMillis().toString())
                }
                onProgress(100, false)
                onDone(true, null)
            } catch (e: Throwable) {
                Log.e(TAG, "pack download failed", e)
                onDone(false, if (cancelRequested) "Cancelled" else (e.message ?: e.javaClass.simpleName))
            } finally {
                downloading.set(false)
            }
        }, "stt-download").start()
    }

    private fun partialBytes(ctx: Context, packId: String, f: SpeechCatalog.PackFile): Long {
        val dir = packDir(ctx, packId)
        val full = File(dir, f.name)
        if (full.length() == f.size) return f.size
        return File(dir, f.name + ".part").length()
    }

    /** Resumable GET into `dest.part`, renamed to [dest] when complete. Redirects are
     *  followed by hand so the Range header survives the hop to the CDN. */
    private fun fetch(f: SpeechCatalog.PackFile, dest: File, onBytes: (Long) -> Unit) {
        val part = File(dest.path + ".part")
        if (part.length() > f.size) part.delete()
        var url = URL(f.url)
        var conn: HttpURLConnection
        var hops = 0
        while (true) {
            conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "VRC-A")
                if (part.length() > 0) setRequestProperty("Range", "bytes=${part.length()}-")
            }
            val code = conn.responseCode
            if (code in 300..399 && hops++ < 8) {
                val loc = conn.getHeaderField("Location") ?: throw IllegalStateException("Redirect without location")
                url = URL(url, loc); conn.disconnect(); continue
            }
            if (code != 200 && code != 206) throw IllegalStateException("Download failed (HTTP $code)")
            if (code == 200 && part.length() > 0) part.delete() // server ignored Range: start over
            break
        }
        var got = part.length()
        conn.inputStream.use { input ->
            FileOutputStream(part, true).use { out ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    if (cancelRequested) return
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    got += n
                    onBytes(got)
                }
            }
        }
        conn.disconnect()
        if (part.length() != f.size) throw IllegalStateException("Incomplete download of ${f.name}")
        if (!part.renameTo(dest)) throw IllegalStateException("Couldn't save ${f.name}")
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun mb(bytes: Long): String = "${(bytes + 500_000) / 1_000_000} MB"
}
