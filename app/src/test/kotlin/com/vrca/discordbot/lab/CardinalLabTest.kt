package com.vrca.discordbot.lab

import android.app.Application
import android.content.Intent
import android.os.Looper
import com.vrca.discordbot.BotEndpoints
import com.vrca.discordbot.DiscordBotService
import com.vrca.discordbot.DiscordBotState
import com.vrca.discordbot.DiscordBotStore
import com.vrca.discordbot.NeuronBudget
import org.json.JSONObject
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * **Cardinal Lab** — runs the REAL [DiscordBotService] (routing, prompts, memory stores, learning,
 * budget ladder) on the JVM against [FakeDiscord] + [AiProxy], driven by chat scripts or by the
 * localhost [LabControl] API. Gated: only runs with `-PcardinalLab` (see tools/cardinal-lab/lab.sh).
 * Production code is untouched apart from the [BotEndpoints] seam this points at the fakes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class CardinalLabTest {

    private val mainTasks = LinkedBlockingQueue<Runnable>()
    private var controller: ServiceController<DiscordBotService>? = null

    @Test
    fun lab() {
        Assume.assumeTrue("Cardinal Lab only runs with -PcardinalLab", System.getProperty("cardinal.lab") == "1")
        val cfg = LabConfig.load()
        if (cfg.live && (cfg.cfAccount.isBlank() || cfg.cfToken.isBlank()))
            error("LIVE mode needs CF_ACCOUNT_ID + CF_API_TOKEN in the environment (see tools/cardinal-lab/README.md)")
        FakeAndroidKeyStore.install()
        val ctx = RuntimeEnvironment.getApplication()
        val rec = LabRecorder(cfg)
        val botToken = "lab-bot-token"
        val discord = FakeDiscord(rec, botToken).apply { start() }
        val ai = AiProxy(cfg, rec).apply { start() }

        // Point the production seam at the fakes.
        BotEndpoints.gatewayUrl = discord.gatewayUrl
        BotEndpoints.discordApi = discord.apiBase
        BotEndpoints.cfApi = "${ai.base}/client/v4"
        BotEndpoints.aiGateway = "${ai.base}/gateway/v1"

        DiscordBotStore.save(ctx,
            botToken = botToken, cfAccountId = "lab-account", cfApiToken = "lab-ai-token",
            cfGatewayId = "", analyticsToken = "",
            model = cfg.replyModel.ifBlank { DiscordBotStore.DEFAULT_MODEL },
            ambientPercent = cfg.ambient, ambientCooldownSec = cfg.ambientCooldownSec, contextTurns = cfg.contextTurns)
        DiscordBotStore.setShadowMode(ctx, cfg.shadow)
        check(DiscordBotStore.load(ctx).isComplete) { "DiscordBotStore did not round-trip (fake keystore problem)" }

        val pump: () -> Unit = {
            if (Looper.getMainLooper().isCurrentThread) {
                shadowOf(Looper.getMainLooper()).idle()
                while (true) { val r = mainTasks.poll() ?: break; r.run() }
            }
        }
        val driver = LabDriver(cfg, rec, discord, ai, ctx, pump, restartService = { onMain { restart(ctx, rec, discord) } })
        cfg.stateIn?.let { driver.restore(it) }
        if (cfg.spent > 0) NeuronBudget.add(ctx, cfg.spent)

        val mirror = StateMirror(rec).apply { start() }
        rec.note("Cardinal Lab ${cfg.runName} — ${cfg.mode}${if (cfg.remap.isNotEmpty()) " remap=${cfg.remap}" else ""} · run dir ${cfg.runDir}")
        startService(ctx)
        awaitReady(discord, pump)

        var quit = false
        try {
            if (cfg.interactive) {
                val control = LabControl(driver) { quit = true }
                val port = control.start(cfg.port)
                File(cfg.runDir, "control.json").writeText(JSONObject().put("port", port).toString())
                println("LAB READY http://127.0.0.1:$port  (run dir ${cfg.runDir})")
                while (!quit) {
                    pump(); Thread.sleep(50)
                    if (System.currentTimeMillis() - control.lastRequestMs > cfg.idleMinutes * 60_000) {
                        rec.note("idle for ${cfg.idleMinutes} min — finishing"); break
                    }
                }
                control.stop()
            } else {
                val runner = LabScript(driver)
                for (f in cfg.scripts) {
                    val out = runner.run(f.name, f.readText())
                    File(cfg.runDir, "script-${f.nameWithoutExtension}.txt").writeText(out)
                    println(out)
                    if (ai.capHit) break
                }
            }
            driver.idle(60_000)
        } finally {
            val report = runCatching { LabReport(driver).write() }.onFailure { rec.note("report failed: $it") }.getOrNull()
            mirror.stopMirror()
            runCatching { controller?.destroy() }
            discord.shutdown(); ai.stop(); rec.close()
            println("LAB DONE ${report?.path ?: cfg.runDir}")
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.getMainLooper().isCurrentThread) { block(); return }
        val f = CompletableFuture<Unit>()
        mainTasks.add(Runnable { try { block(); f.complete(Unit) } catch (e: Throwable) { f.completeExceptionally(e) } })
        f.get(60, TimeUnit.SECONDS)
    }

    private fun startService(ctx: Application) {
        val intent = Intent(ctx, DiscordBotService::class.java).setAction(DiscordBotService.ACTION_START)
        controller = Robolectric.buildService(DiscordBotService::class.java, intent).create().startCommand(0, 1)
    }

    private fun restart(ctx: Application, rec: LabRecorder, discord: FakeDiscord) {
        rec.note("restarting bot service (config change)")
        runCatching { controller?.destroy() }
        controller = null
        startService(ctx)
        awaitReady(discord) { shadowOf(Looper.getMainLooper()).idle() }
    }

    private fun awaitReady(discord: FakeDiscord, pump: () -> Unit) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            pump()
            if (discord.ready.count == 0L && DiscordBotState.statusFlow.value == DiscordBotState.Status.CONNECTED &&
                com.vrca.discordbot.ChannelInfoStore.size() > 0) return
            Thread.sleep(50)
        }
        error("bot never reached CONNECTED (status=${DiscordBotState.statusFlow.value} ${DiscordBotState.detailFlow.value})")
    }

    /** Mirrors the bot's own routing traces + activity log into the recorder as they appear. */
    private class StateMirror(private val rec: LabRecorder) : Thread("lab-state-mirror") {
        @Volatile private var running = true
        init { isDaemon = true }
        fun stopMirror() { running = false; runCatching { join(500) } }

        override fun run() {
            var traces = DiscordBotState.tracesFlow.value
            var log = DiscordBotState.logFlow.value
            traces.forEach { emitTrace(it) }; log.forEach { emitLog(it) }
            while (running) {
                val t = DiscordBotState.tracesFlow.value
                if (t !== traces) { newTail(traces, t).forEach { emitTrace(it) }; traces = t }
                val l = DiscordBotState.logFlow.value
                if (l !== log) { newTail(log, l).forEach { emitLog(it) }; log = l }
                runCatching { sleep(50) }
            }
        }

        private fun emitTrace(t: DiscordBotState.Trace) = rec.event("trace", JSONObject()
            .put("atMs", t.atMs).put("channel", t.channel).put("author", t.author).put("score", t.score)
            .put("plan", t.plan).put("action", t.action).put("detail", t.detail))

        private fun emitLog(line: String) = rec.event("log", JSONObject().put("line", line.substringAfter("  ")))

        /** The items appended to [old] to make [new] (both are capped ring buffers). */
        private fun <T> newTail(old: List<T>, new: List<T>): List<T> {
            for (k in minOf(old.size, new.size) downTo 0) {
                if (new.subList(0, k) == old.subList(old.size - k, old.size)) return new.drop(k)
            }
            return new
        }
    }
}
