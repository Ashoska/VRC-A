package com.vrca.osc

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.android.AdbMdns
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Headset debug: an ADB client that talks to the Quest's OWN adbd over Wireless debugging
 * (no PC), so [AdbProbes] can run research commands with `shell` rights (dumpsys, full
 * getprop/settings, logcat) that a normal app can't. The user pairs once with the 6-digit
 * code from Developer options > Wireless debugging > Pair device with pairing code; the
 * key + certificate are kept in `files/adb/` so later connects need no code. Ports are
 * found over mDNS (`_adb-tls-pairing._tcp` / `_adb-tls-connect._tcp`) when not typed.
 * Every call blocks: run off the main thread. Built on libadb-android (headset only);
 * the public/admin stubs report [SUPPORTED] = false.
 */
object AdbShell {
    const val SUPPORTED = true

    private const val LOCAL = "127.0.0.1"
    private const val FIND_MS = 8000L
    private const val OUTPUT_CAP = 256 * 1024

    @Volatile var status = "Not connected"
        private set

    private var manager: Manager? = null

    private class Manager(ctx: Context) : AbsAdbConnectionManager() {
        private val key: PrivateKey
        private val cert: Certificate

        init {
            setApi(Build.VERSION.SDK_INT)
            setTimeout(10, TimeUnit.SECONDS)
            val dir = File(ctx.filesDir, "adb").apply { mkdirs() }
            val keyFile = File(dir, "private.key")
            val certFile = File(dir, "cert.der")
            val stored = runCatching {
                KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes())) to
                    certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            }.getOrNull()
            if (stored != null) {
                key = stored.first
                cert = stored.second
            } else {
                val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
                val name = X500Name("CN=VRC-A")
                val now = System.currentTimeMillis()
                val holder = JcaX509v3CertificateBuilder(
                    name, BigInteger.valueOf(SecureRandom().nextLong() and Long.MAX_VALUE),
                    Date(now - 86_400_000L), Date(now + 10 * 365 * 86_400_000L), name, pair.public
                ).build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
                key = pair.private
                cert = JcaX509CertificateConverter().getCertificate(holder)
                keyFile.writeBytes(key.encoded)
                certFile.writeBytes(cert.encoded)
            }
        }

        override fun getPrivateKey(): PrivateKey = key
        override fun getCertificate(): Certificate = cert
        override fun getDeviceName(): String = "VRC-A"
    }

    @Synchronized
    private fun manager(ctx: Context): Manager =
        manager ?: Manager(ctx.applicationContext).also { manager = it }

    /** First mDNS-advertised port of [type] (an [AdbMdns] service type), or -1. */
    private fun findPort(ctx: Context, type: String): Int {
        val port = AtomicInteger(-1)
        val found = CountDownLatch(1)
        val mdns = AdbMdns(ctx.applicationContext, type) { _, p ->
            if (p > 0 && port.compareAndSet(-1, p)) found.countDown()
        }
        mdns.start()
        try { found.await(FIND_MS, TimeUnit.MILLISECONDS) } finally { mdns.stop() }
        return port.get()
    }

    fun isConnected(): Boolean = manager?.isConnected == true

    /** Pairs with Wireless debugging's "pair with code" dialog. [port] null = find it over mDNS. */
    fun pair(ctx: Context, code: String, port: Int?): String {
        status = "Pairing…"
        val result = runCatching {
            val p = port ?: findPort(ctx, AdbMdns.SERVICE_TYPE_TLS_PAIRING)
            if (p <= 0) return@runCatching "Pair failed: no pairing port found (keep the pair-with-code dialog open, or type its port)"
            if (manager(ctx).pair(LOCAL, p, code.trim())) "Paired (port $p). Now Connect." else "Pair failed (port $p): code refused"
        }.getOrElse { "Pair failed: ${it.javaClass.simpleName} ${it.message ?: ""}" }
        status = result
        return result
    }

    /** Connects to adbd. [port] null = Wireless debugging's port over mDNS, then legacy 5555. */
    fun connect(ctx: Context, port: Int?): String {
        status = "Connecting…"
        val result = runCatching {
            val m = manager(ctx)
            if (m.isConnected) return@runCatching "Connected"
            val p = port ?: findPort(ctx, AdbMdns.SERVICE_TYPE_TLS_CONNECT).takeIf { it > 0 } ?: 5555
            if (m.connect(LOCAL, p)) "Connected (port $p)" else "Connect failed (port $p)"
        }.getOrElse {
            val name = it.javaClass.simpleName
            if ("Pairing" in name) "Connect failed: not paired yet (pair with code first)"
            else "Connect failed: $name ${it.message ?: ""}"
        }
        status = result
        return result
    }

    fun disconnect() {
        runCatching { manager?.disconnect() }
        status = "Not connected"
    }

    /**
     * Runs [command] in `shell:` (stdout + stderr together) and returns its output, cut at
     * [OUTPUT_CAP]. The stream is closed after [timeoutMs] so a command that never ends
     * (e.g. `logcat` without `-d`) can't hang the caller.
     */
    fun run(ctx: Context, command: String, timeoutMs: Long = 15_000L): String {
        val m = manager(ctx)
        if (!m.isConnected) return "(not connected)"
        val stream = runCatching { m.openStream("shell:$command") }
            .getOrElse { return "(shell failed: ${it.javaClass.simpleName} ${it.message ?: ""})" }
        val out = ByteArrayOutputStream()
        var timedOut = false
        val reader = Thread {
            runCatching {
                stream.openInputStream().use { input ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        synchronized(out) { if (out.size() < OUTPUT_CAP) out.write(buf, 0, n) }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        reader.join(timeoutMs)
        if (reader.isAlive) {
            timedOut = true
            runCatching { stream.close() }
            reader.join(1000)
        }
        val text = synchronized(out) { out.toString(Charsets.UTF_8.name()) }
        return buildString {
            append(text)
            if (text.length >= OUTPUT_CAP) append("\n(output cut at ${OUTPUT_CAP / 1024} KB)")
            if (timedOut) append("\n(stopped after ${timeoutMs / 1000}s)")
        }
    }
}
