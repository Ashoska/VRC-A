package com.vrca.osc

import android.content.Context

/**
 * adminApp stub. The ADB shell tests are a HEADSET debug feature (libadb-android, Conscrypt and
 * BouncyCastle are `headsetAppImplementation` dependencies), so SUPPORTED is false and the
 * debug section is hidden. Signature MUST match the headsetApp AdbShell.
 */
object AdbShell {
    const val SUPPORTED = false

    val status: String get() = "Not available on this build"

    fun isConnected(): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun pair(ctx: Context, code: String, port: Int?): String = status

    @Suppress("UNUSED_PARAMETER")
    fun connect(ctx: Context, port: Int?): String = status

    fun disconnect() {}

    @Suppress("UNUSED_PARAMETER")
    fun run(ctx: Context, command: String, timeoutMs: Long = 15_000L): String = "(not available)"
}
