package com.vrca.osc

import android.content.Context
import android.provider.Settings

/**
 * Headset debug, READ-ONLY: where does Quest keep the floor / boundary / tracking
 * values? The user proved VRChat follows the system floor height live (floor below
 * feet = float, above = crouch), so this lists every system setting and system
 * property whose name mentions one of [WORDS], to see whether any is visible to a
 * normal app. Changes nothing. Blocking (spawns `getprop`): run off the main thread.
 */
object FloorProbe {

    private val WORDS = listOf("floor", "guardian", "boundary", "tracking", "space", "height", "stage", "recenter")

    private fun matches(name: String): Boolean = name.lowercase().let { n -> WORDS.any { it in n } }

    fun dump(context: Context): String = buildString {
        for ((label, uri) in listOf(
            "Settings.System" to Settings.System.CONTENT_URI,
            "Settings.Secure" to Settings.Secure.CONTENT_URI,
            "Settings.Global" to Settings.Global.CONTENT_URI,
        )) {
            append("== ").append(label).append('\n')
            val rows = runCatching {
                val out = mutableListOf<String>()
                context.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(0) ?: continue
                        if (matches(name)) out += "$name = ${c.getString(1)}"
                    }
                }
                out
            }
            rows.onSuccess { list -> if (list.isEmpty()) append("(no matching keys)\n") else list.sorted().forEach { append(it).append('\n') } }
            rows.onFailure { append("not readable: ").append(it.javaClass.simpleName).append(' ').append(it.message ?: "").append('\n') }
        }
        append("== system properties\n")
        runCatching {
            val p = ProcessBuilder("getprop").redirectErrorStream(true).start()
            val lines = p.inputStream.bufferedReader().use { it.readLines() }
            p.waitFor()
            lines.filter { matches(it.substringBefore("]:")) }
        }.onSuccess { list -> if (list.isEmpty()) append("(no matching properties)\n") else list.forEach { append(it).append('\n') } }
            .onFailure { append("getprop failed: ").append(it.message ?: "").append('\n') }
    }
}
