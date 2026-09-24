package com.vrca.discordbot.lab

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cardinal Lab run configuration. Non-secret knobs arrive as `-Plab.<key>=<value>` Gradle
 * properties (mapped to `cardinal.lab.<key>` system properties by app/build.gradle); Cloudflare
 * credentials arrive ONLY through the environment (CF_ACCOUNT_ID / CF_API_TOKEN / CF_GATEWAY_ID /
 * CF_AIG_TOKEN) so they never appear on a command line or in a report.
 */
internal class LabConfig(
    val root: File,
    val runName: String,
    val runDir: File,
    val scripts: List<File>,
    val interactive: Boolean,
    /** live = forward model calls to real Workers AI (spends neurons). dry = synthetic replies (free). */
    val live: Boolean,
    val cfAccount: String,
    val cfToken: String,
    val cfGateway: String,
    val aigToken: String,
    /** Hard ceiling on LIVE neurons this run may spend (the lab shares the account's daily free tier). */
    val neuronCap: Double,
    /** Model remaps applied at the proxy (A/B a model without touching the app): from -> to. */
    val remap: Map<String, String>,
    val port: Int,
    val stateIn: File?,
    val ambient: Int,
    val ambientCooldownSec: Int,
    val contextTurns: Int,
    val replyModel: String,
    val spent: Long,
    val shadow: Boolean,
    val idleMinutes: Long,
    val gapMs: Long,
    val dryDirector: String,
    val dryLatencyMs: Long,
    val affinity: Boolean,
    val quietMs: Long,
) {
    val mode: String get() = if (live) "LIVE" else "DRY"

    companion object {
        private fun prop(k: String, d: String = ""): String =
            System.getProperty("cardinal.lab.$k")?.trim()?.takeIf { it.isNotEmpty() } ?: d

        private fun env(vararg keys: String): String =
            keys.firstNotNullOfOrNull { System.getenv(it)?.trim()?.takeIf { v -> v.isNotEmpty() } }.orEmpty()

        fun load(): LabConfig {
            val root = File(System.getProperty("cardinal.lab.root") ?: ".").absoluteFile
            val runName = prop("run", "run-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()))
            val runDir = File(root, "lab-runs/$runName").apply { mkdirs() }
            val scripts = prop("script").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .map { p -> File(p).let { if (it.isAbsolute) it else File(root, p) } }
            val remap = prop("remap").split(',').mapNotNull { pair ->
                val i = pair.indexOf('=')
                if (i <= 0) null else pair.substring(0, i).trim() to pair.substring(i + 1).trim()
            }.toMap()
            val live = prop("ai", "dry").equals("live", ignoreCase = true)
            return LabConfig(
                root = root,
                runName = runName,
                runDir = runDir,
                scripts = scripts,
                interactive = prop("interactive", if (scripts.isEmpty()) "1" else "0") == "1",
                live = live,
                cfAccount = env("CF_ACCOUNT_ID", "CLOUDFLARE_ACCOUNT_ID").ifBlank { prop("account") },
                cfToken = env("CF_API_TOKEN", "CF_AI_TOKEN", "CLOUDFLARE_API_TOKEN"),
                cfGateway = env("CF_GATEWAY_ID"),
                aigToken = env("CF_AIG_TOKEN"),
                neuronCap = prop("cap", "1500").toDouble(),
                remap = remap,
                port = prop("port", "18750").toInt(),
                stateIn = prop("state").takeIf { it.isNotEmpty() }?.let { p -> File(p).let { if (it.isAbsolute) it else File(root, p) } },
                ambient = prop("ambient", "22").toInt(),
                ambientCooldownSec = prop("cooldown", "60").toInt(),
                contextTurns = prop("context", "8").toInt(),
                replyModel = prop("model", ""),
                spent = prop("spent", "0").toLong(),
                shadow = prop("shadow", "0") == "1",
                idleMinutes = prop("idle", "20").toLong(),
                gapMs = prop("gap", "1500").toLong(),
                dryDirector = prop("dry.director", "reply"),
                dryLatencyMs = prop("dry.latency", "250").toLong(),
                affinity = prop("affinity", "0") == "1",
                quietMs = prop("quiet", "1500").toLong(),
            )
        }
    }
}
