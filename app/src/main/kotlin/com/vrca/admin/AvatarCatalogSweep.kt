package com.vrca.admin

import android.content.Context
import com.vrca.vrchat.AvatarGlobalDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The admin dead-avatar/refresh sweep. Walks the crowdsourced catalog and, using
 * the dedicated [BotVrchatSession] (so the admin's real account isn't rate-limited),
 * checks each avatar via `GET /avatars/{id}`:
 *  - 404/410  -> dead -> queued for REMOVAL,
 *  - 200      -> refresh name/author/authorId/platforms/image if changed (a changed
 *                image file id re-keys the entry: remove old key + upsert new).
 * Changes are batched and pushed authoritatively to the Worker `/admin` endpoint.
 *
 * Paced (default 1.5s/avatar) so it can run continuously without hammering VRChat.
 * Admin build only.
 */
object AvatarCatalogSweep {
    @Volatile var running = false; private set
    @Volatile var checked = 0; private set
    @Volatile var refreshed = 0; private set
    @Volatile var removed = 0; private set
    @Volatile var status = "idle"; private set

    private const val PACE_MS = 1500L      // per avatar (bot account)
    private const val BATCH = 20           // ops per /admin push
    private const val CYCLE_PAUSE_MS = 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun progress(): String =
        "${if (running) "running" else "stopped"} · checked=$checked refreshed=$refreshed removed=$removed\n$status"

    fun start(context: Context, adminKey: String) {
        if (running) return
        if (!BotVrchatSession.isLoggedIn(context)) { status = "bot not logged in"; return }
        if (adminKey.isBlank()) { status = "admin key not set"; return }
        running = true; checked = 0; refreshed = 0; removed = 0; status = "starting…"
        val app = context.applicationContext
        job = scope.launch { try { loop(app, adminKey) } finally { running = false; status = "stopped" } }
    }

    fun stop() { running = false; job?.cancel(); status = "stopped" }

    private suspend fun loop(context: Context, adminKey: String) {
        while (running && scope.isActive) {
            AvatarGlobalDb.forceRefresh(context)
            delay(3000)
            val entries = AvatarGlobalDb.snapshot()
            if (entries.isEmpty()) { status = "catalog empty — waiting"; delay(CYCLE_PAUSE_MS); continue }
            val upserts = mutableListOf<AvatarGlobalDb.Entry>()
            val removes = mutableListOf<String>()
            for (e in entries) {
                if (!running) break
                status = "checking ${e.name.ifBlank { e.avatarId }}"
                val chk = BotVrchatSession.checkAvatar(context, e.avatarId)
                checked++
                if (chk == null) { delay(PACE_MS); continue } // unknown (429/network) — skip
                if (!chk.alive) {
                    removes.add(e.fileId); removed++
                } else {
                    val newFile = chk.fileId ?: e.fileId
                    val changed = chk.name != e.name || chk.author != e.author ||
                        chk.authorId != e.authorId || chk.platforms != e.platforms || newFile != e.fileId
                    if (changed) {
                        if (newFile != e.fileId) removes.add(e.fileId) // re-key on image change
                        upserts.add(
                            e.copy(
                                fileId = newFile, name = chk.name, author = chk.author,
                                authorId = chk.authorId, platforms = chk.platforms
                            )
                        )
                        refreshed++
                    }
                }
                if (upserts.size + removes.size >= BATCH) {
                    AvatarGlobalDb.adminPush(context, adminKey, upserts.toList(), removes.toList())
                    upserts.clear(); removes.clear()
                }
                delay(PACE_MS)
            }
            if (upserts.isNotEmpty() || removes.isNotEmpty()) {
                AvatarGlobalDb.adminPush(context, adminKey, upserts.toList(), removes.toList())
            }
            status = "cycle complete — checked=$checked refreshed=$refreshed removed=$removed"
            delay(CYCLE_PAUSE_MS)
        }
    }
}
