package com.vrca.admin

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.vrca.app.BootCat
import com.vrca.app.BootIconView
import com.vrca.app.BootIcons
import com.vrca.app.Mark
import com.vrca.app.Standing
import com.vrca.ui.common.GremlinButton
import com.vrca.ui.common.GremlinButtonKind
import com.vrca.ui.common.GremlinButtonSize
import com.vrca.ui.common.GremlinChip
import com.vrca.ui.common.PanelDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import com.vrca.ui.theme.GremlinColors as G

/**
 * Admin account standing (revamp 2, 2026-10-31; docs/systems/moderation-accounts.md "Account standing").
 * Full records in `standingActions/{id}` (admin-only); on every save the user's view is rebuilt from all of the
 * account's records and written account-wide as `standing` on each `users/{deviceHash}` doc (+ the legacy
 * `warned`/`banned` fields older apps read, + `bannedDevices` for device bans). Evidence files go to the Worker's
 * PRIVATE bucket; their show-to-user switch flips the Worker flag at once and re-saves the view, so a hidden file
 * vanishes from the user's screen live.
 */
object AdminStandingStore {
    private const val COL = "standingActions"

    data class Ev(val id: String, val kind: String, val shown: Boolean)
    data class Act(
        val id: String, val account: String, val docId: String, val kind: String, val reason: String, val title: String,
        val message: String, val adminNote: String, val given: Long, val editedAt: Long, val active: Boolean,
        val off: Map<String, Long>, val banUntil: Long, val banAccount: Boolean, val banDevice: Boolean, val banOthers: Boolean,
        val level: String, val evidence: List<Ev>, val bannedHashes: List<String>, val givenBy: String, val seenAt: Long = 0L,
        /** Ids put in `bannedIdentifiers` (the evasion check): banned device hashes + the VRChat id. */
        val identifiers: List<String> = emptyList()
    )

    fun accountKey(docId: String, vrchatUserId: String) = vrchatUserId.ifBlank { docId }

    fun newId(): String = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    @Suppress("UNCHECKED_CAST")
    private fun parse(id: String, m: Map<String, Any?>) = Act(
        id = id, account = m["account"] as? String ?: "", docId = m["docId"] as? String ?: "", kind = m["kind"] as? String ?: "warning",
        reason = m["reason"] as? String ?: "", title = m["title"] as? String ?: "", message = m["message"] as? String ?: "",
        adminNote = m["adminNote"] as? String ?: "", given = (m["given"] as? Number)?.toLong() ?: 0L,
        editedAt = (m["editedAt"] as? Number)?.toLong() ?: 0L, active = m["active"] as? Boolean ?: true,
        off = (m["off"] as? Map<String, Any?>).orEmpty().mapNotNull { (k, v) -> (v as? Number)?.let { k to it.toLong() } }.toMap(),
        banUntil = (m["banUntil"] as? Number)?.toLong() ?: 0L, banAccount = m["banAccount"] as? Boolean ?: true,
        banDevice = m["banDevice"] as? Boolean ?: false, banOthers = m["banOthers"] as? Boolean ?: false,
        level = m["level"] as? String ?: "auto",
        evidence = (m["evidence"] as? List<Any?>).orEmpty().mapNotNull { e ->
            val o = e as? Map<String, Any?> ?: return@mapNotNull null
            Ev(o["id"] as? String ?: return@mapNotNull null, o["kind"] as? String ?: "png", o["shown"] as? Boolean ?: false)
        },
        bannedHashes = (m["bannedHashes"] as? List<Any?>).orEmpty().filterIsInstance<String>(),
        identifiers = (m["identifiers"] as? List<Any?>).orEmpty().filterIsInstance<String>(),
        givenBy = m["givenBy"] as? String ?: ""
    )

    private fun toMap(a: Act): Map<String, Any> = mapOf(
        "account" to a.account, "docId" to a.docId, "kind" to a.kind, "reason" to a.reason, "title" to a.title,
        "message" to a.message, "adminNote" to a.adminNote, "given" to a.given, "editedAt" to a.editedAt, "active" to a.active,
        "off" to a.off, "banUntil" to a.banUntil, "banAccount" to a.banAccount, "banDevice" to a.banDevice,
        "banOthers" to a.banOthers, "level" to a.level, "bannedHashes" to a.bannedHashes, "identifiers" to a.identifiers, "givenBy" to a.givenBy,
        "evidence" to a.evidence.map { mapOf("id" to it.id, "kind" to it.kind, "shown" to it.shown) }
    )

    /** One query (the account's records). Read when the page opens and after each save. */
    suspend fun load(db: FirebaseFirestore, account: String): List<Act> =
        db.collection(COL).whereEqualTo("account", account).get().await().documents
            .mapNotNull { d -> d.data?.let { parse(d.id, it) } }.sortedByDescending { it.given }

    private fun live(until: Long, now: Long) = until == -1L || until > now

    /** Still counts for the user's view: switched on by the admin and something about it not run out yet. */
    fun isLive(a: Act, now: Long): Boolean = a.active && when (a.kind) {
        "ban" -> a.banUntil != 0L && live(a.banUntil, now)
        "restriction" -> a.off.values.any { live(it, now) }
        else -> now - a.given < 30L * 86_400_000L   // a warning stays active for 30 days
    }

    fun autoLevel(acts: List<Act>, now: Long): Standing.Level {
        val liveActs = acts.filter { isLive(it, now) }
        if (liveActs.any { it.kind == "ban" }) return Standing.Level.Banned
        val offCount = liveActs.flatMap { a -> a.off.filterValues { live(it, now) }.keys }.distinct().size
        return when { offCount >= 3 -> Standing.Level.AtRisk; offCount == 2 -> Standing.Level.VeryLimited; offCount == 1 -> Standing.Level.Limited; else -> Standing.Level.Good }
    }

    fun levelOf(acts: List<Act>, now: Long): Standing.Level {
        val manual = acts.filter { isLive(it, now) && it.level != "auto" }.maxByOrNull { it.editedAt }
        return manual?.let { Standing.Level.of(it.level) } ?: autoLevel(acts, now)
    }

    /** Saves [a] (or removes it when [remove]) and rebuilds what the user sees, account-wide. Returns false when
     *  the record saved but the Worker's server blocks (search / contributing) couldn't be updated. */
    suspend fun save(ctx: Context, db: FirebaseFirestore, a: Act, remove: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        // The account's device docs (the same set applyAccountWide writes).
        val target = db.collection("users").document(a.docId).get().await()
        val vid = (target.getString("vrchatUserId") ?: "").trim()
        val hashes = LinkedHashSet<String>().apply {
            add(a.docId)
            if (vid.isNotBlank()) db.collection("users").whereEqualTo("vrchatUserId", vid).get().await().documents.forEach { add(it.id) }
        }
        val others = load(db, a.account).filter { it.id != a.id }
        val bans = if (remove) emptySet() else buildSet {
            if (a.kind == "ban" && a.active && live(a.banUntil, now)) {
                if (a.banDevice) add(a.docId)
                if (a.banOthers) addAll(hashes.filter { it != a.docId })
            }
        }
        val idents = if (bans.isEmpty() && !(a.kind == "ban" && a.active && a.banAccount && live(a.banUntil, now) && !remove)) emptyList()
            else bans.toList() + listOfNotNull(vid.takeIf { it.isNotBlank() && a.banAccount })
        val saved = a.copy(editedAt = now, bannedHashes = bans.toList(), identifiers = idents)
        val all = (if (remove) others else others + saved).sortedByDescending { it.given }
        // Device bans: every hash a live record bans; un-ban only hashes a standing record banned before.
        val banNow = all.filter { it.kind == "ban" && isLive(it, now) }.flatMap { it.bannedHashes }.toSet()
        val banBefore = (others + a).flatMap { it.bannedHashes }.toSet()
        val batch = db.batch()
        if (remove) batch.delete(db.collection(COL).document(a.id)) else batch.set(db.collection(COL).document(a.id), toMap(saved))
        for (h in banNow) batch.set(db.collection("bannedDevices").document(h),
            mapOf("banned" to true, "reason" to (all.firstOrNull { h in it.bannedHashes }?.title ?: "Banned"), "byStanding" to true,
                "updatedAt" to FieldValue.serverTimestamp()), com.google.firebase.firestore.SetOptions.merge())
        for (h in banBefore - banNow) batch.delete(db.collection("bannedDevices").document(h))
        // The evasion check (VrchatBanChecker): a banned VRChat id or device found on ANOTHER phone or sign-in shows the
        // banned popup there too. Same "only remove what a standing record added" rule.
        val idNow = all.filter { it.kind == "ban" && isLive(it, now) }.flatMap { it.identifiers }.toSet()
        val idBefore = (others + a).flatMap { it.identifiers }.toSet()
        for (i in idNow) batch.set(db.collection("bannedIdentifiers").document(i),
            mapOf("banId" to (all.firstOrNull { i in it.identifiers }?.id ?: a.id), "type" to (if (i.startsWith("usr_")) "vrchatId" else "deviceHash"), "active" to true))
        for (i in idBefore - idNow) batch.delete(db.collection("bannedIdentifiers").document(i))
        batch.commit().await()
        val view = mapOf(
            "rev" to now,
            "level" to levelOf(all, now).key,
            "actions" to all.map { x ->
                mapOf("id" to x.id, "kind" to x.kind, "title" to x.title, "message" to x.message, "given" to x.given,
                    "active" to isLive(x, now), "off" to x.off, "banUntil" to x.banUntil,
                    "ev" to x.evidence.filter { it.shown }.map { mapOf("id" to it.id, "kind" to it.kind) })
            }
        )
        val warn = all.firstOrNull { it.kind == "warning" && isLive(it, now) }
        val ban = all.firstOrNull { it.kind == "ban" && isLive(it, now) && it.banAccount }
        AccountModeration.applyAccountWide(db, a.docId, mapOf(
            "standing" to view,
            // Older apps read these.
            "warned" to (warn != null), "warnReason" to (warn?.title ?: ""),
            "banned" to (ban != null), "banReason" to (ban?.title ?: ""),
            "updatedAt" to FieldValue.serverTimestamp()
        ))
        // Server blocks (Worker v58): search and contributing for every id of the account; a live ban blocks both.
        val liveActs = all.filter { isLive(it, now) }
        fun until(feature: String): Long {
            val ends = liveActs.mapNotNull { x -> if (x.kind == "ban") x.banUntil else x.off[feature] }.filter { it == -1L || it > now }
            return if (ends.isEmpty()) 0L else if (-1L in ends) -1L else ends.max()
        }
        val ids = (hashes + vid).filter { it.length >= 8 }
        return pushBlocks(ctx, ids, until(Standing.Feature.AvatarSearch.key), until(Standing.Feature.AvatarContribute.key))
    }

    private suspend fun pushBlocks(ctx: Context, ids: List<String>, s: Long, c: Long): Boolean = withContext(Dispatchers.IO) {
        val k = key(ctx).ifBlank { return@withContext false }
        runCatching {
            val body = org.json.JSONObject().put("key", k).put("ids", org.json.JSONArray(ids)).put("s", s).put("c", c).toString().toByteArray()
            val conn = (java.net.URL("$BASE/admin/standing").openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; setRequestProperty("Content-Type", "application/json"); connectTimeout = 10_000; readTimeout = 15_000
            }
            conn.outputStream.use { it.write(body) }
            (conn.responseCode == 200).also { conn.disconnect() }
        }.getOrDefault(false)
    }

    // ---- evidence (Worker v57, admin key from the Bots tab) ----
    private fun key(ctx: Context) = ctx.getSharedPreferences("vrca_admin_local", Context.MODE_PRIVATE).getString("avatar_admin_key", "").orEmpty()
    private val BASE = com.vrca.vrchat.AvatarGlobalDb.WORKER_URL

    fun adminUrl(ctx: Context, id: String) = "$BASE/evidence/$id?key=" + Uri.encode(key(ctx))

    fun kindOf(mime: String?): String? = when (mime?.lowercase()) {
        "image/png" -> "png"; "image/jpeg", "image/jpg" -> "jpg"; "image/webp" -> "webp"; "image/gif" -> "gif"
        "video/mp4" -> "mp4"; "video/webm" -> "webm"; else -> null
    }

    /** Uploads a picked file; null = failed (no key, wrong type, too big, network). New files start admin-only. */
    suspend fun upload(ctx: Context, uri: Uri): Ev? = withContext(Dispatchers.IO) {
        val k = key(ctx).ifBlank { return@withContext null }
        val kind = kindOf(ctx.contentResolver.getType(uri)) ?: return@withContext null
        val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return@withContext null
        if (bytes.isEmpty() || bytes.size > 95 * 1024 * 1024) return@withContext null
        val id = newId()
        val ok = runCatching {
            val c = (java.net.URL("$BASE/admin/evidence?key=${Uri.encode(k)}&id=$id&kind=$kind").openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; setFixedLengthStreamingMode(bytes.size); connectTimeout = 15_000; readTimeout = 120_000
                setRequestProperty("Content-Type", "application/octet-stream")
            }
            c.outputStream.use { it.write(bytes) }
            (c.responseCode == 200).also { c.disconnect() }
        }.getOrDefault(false)
        if (ok) Ev(id, kind, false) else null
    }

    suspend fun setShown(ctx: Context, id: String, on: Boolean): Boolean = call(ctx, "POST", "$BASE/admin/evidence/show?key=${Uri.encode(key(ctx))}&id=$id&on=${if (on) 1 else 0}")
    suspend fun delete(ctx: Context, id: String): Boolean = call(ctx, "DELETE", "$BASE/admin/evidence?key=${Uri.encode(key(ctx))}&id=$id")

    private suspend fun call(ctx: Context, method: String, url: String): Boolean = withContext(Dispatchers.IO) {
        if (key(ctx).isBlank()) return@withContext false
        runCatching {
            val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply { requestMethod = method; connectTimeout = 10_000; readTimeout = 15_000 }
            (c.responseCode == 200).also { c.disconnect() }
        }.getOrDefault(false)
    }
}

/** Who the page is for (from the users directory). */
data class StandingTarget(val docId: String, val name: String, val vrchatUserId: String)

private val DURATIONS = listOf("1 h" to 3_600_000L, "1 d" to 86_400_000L, "3 d" to 3 * 86_400_000L, "7 d" to 7 * 86_400_000L,
    "30 d" to 30 * 86_400_000L, "Forever" to -1L)
private val REASONS = listOf("Spam", "Harassment", "Server abuse", "Ban evasion", "Modified app", "Other")
private val dayFmt = java.text.SimpleDateFormat("d MMM", java.util.Locale.UK)
private val stampFmt = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.UK)
private fun endText(until: Long) = if (until == -1L) "Forever" else "until " + dayFmt.format(java.util.Date(until))
private fun kindLabel(k: String) = when (k) { "ban" -> "Ban"; "restriction" -> "Restriction"; else -> "Warning" }
private fun tone(k: String) = when (k) { "ban" -> G.Alert; "restriction" -> G.Caution; else -> G.Warning }

/** The admin's standing page for one user: their actions, and the add / edit page. Labels, values and controls only. */
@Composable
fun AdminStandingDialog(target: StandingTarget, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { FirebaseFirestore.getInstance() }
    val scope = rememberCoroutineScope()
    val account = AdminStandingStore.accountKey(target.docId, target.vrchatUserId)
    val acts = remember { mutableStateListOf<AdminStandingStore.Act>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<AdminStandingStore.Act?>(null) }
    suspend fun reload() {
        loading = true
        runCatching { AdminStandingStore.load(db, account) }.onSuccess { acts.clear(); acts.addAll(it); error = null }
            .onFailure { error = it.message?.take(200) }
        loading = false
    }
    LaunchedEffect(account) { reload() }
    PanelDialog(onDismissRequest = { if (editing != null) editing = null else onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(G.Backdrop)) {
            val e = editing
            if (e != null) ActionEditor(target, e, acts.toList(), onCancel = { editing = null }, onSaved = { scope.launch { reload() }; editing = null })
            else Column(Modifier.fillMaxSize()) {
                TopBar("Account standing", onBack = onClose)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TargetCard(target, AdminStandingStore.levelOf(acts, System.currentTimeMillis()), acts.size)
                    if (error != null) Text(error!!, style = MaterialTheme.typography.bodySmall, color = G.Error)
                    if (loading) Row(verticalAlignment = Alignment.CenterVertically) { BootCat(Mark.Working, 0, Modifier.size(22.dp)) }
                    if (acts.isNotEmpty()) com.vrca.app.Layer(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            acts.forEachIndexed { i, a ->
                                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(G.Edge))
                                val live = AdminStandingStore.isLive(a, System.currentTimeMillis())
                                Row(Modifier.fillMaxWidth().clickable { editing = a }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(8.dp).background(if (live) tone(a.kind) else G.TextMuted, CircleShape)); Spacer(Modifier.width(10.dp))
                                    Text(kindLabel(a.kind), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = if (live) tone(a.kind) else G.TextMuted)
                                    Spacer(Modifier.width(8.dp))
                                    Text(a.title.ifBlank { a.reason }, style = MaterialTheme.typography.bodyMedium, color = if (live) G.Text else G.TextMuted,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Text(dayFmt.format(java.util.Date(a.given)), style = MaterialTheme.typography.bodySmall, color = G.TextMuted)
                                }
                            }
                        }
                    }
                }
                BottomBar {
                    GremlinButton("New action", {
                        val now = System.currentTimeMillis()
                        editing = AdminStandingStore.Act(AdminStandingStore.newId(), account, target.docId, "warning", "", "", "", "", now, now, true,
                            emptyMap(), 0L, true, true, false, "auto", emptyList(), emptyList(), "admin")
                    })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionEditor(target: StandingTarget, start: AdminStandingStore.Act, existing: List<AdminStandingStore.Act>,
                         onCancel: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { FirebaseFirestore.getInstance() }
    val scope = rememberCoroutineScope()
    val isNew = existing.none { it.id == start.id }
    var kind by remember { mutableStateOf(start.kind) }
    var reason by remember { mutableStateOf(start.reason) }
    var title by remember { mutableStateOf(start.title) }
    var message by remember { mutableStateOf(start.message) }
    var note by remember { mutableStateOf(start.adminNote) }
    val off = remember { mutableStateMapOf<String, Long>().apply { putAll(start.off) } }
    var banUntil by remember { mutableStateOf(start.banUntil) }
    // The length chip last picked (per feature, and for the ban), so the chosen chip stays lit. An end loaded from a
    // saved action shows only its date until a chip is picked; Forever is recognised from the value itself.
    val picked = remember { mutableStateMapOf<String, String>() }
    var banAccount by remember { mutableStateOf(start.banAccount) }
    var banDevice by remember { mutableStateOf(start.banDevice) }
    var banOthers by remember { mutableStateOf(start.banOthers) }
    var level by remember { mutableStateOf(start.level) }
    var active by remember { mutableStateOf(start.active) }
    val evidence = remember { mutableStateListOf<AdminStandingStore.Ev>().apply { addAll(start.evidence) } }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }

    fun current() = start.copy(kind = kind, reason = reason, title = title.trim().ifBlank { reason }, message = message.trim(),
        adminNote = note.trim(), off = if (kind == "ban") emptyMap() else off.toMap(), banUntil = if (kind == "ban") (if (banUntil == 0L) -1L else banUntil) else 0L,
        banAccount = banAccount, banDevice = banDevice, banOthers = banOthers, level = level, active = active, evidence = evidence.toList())
    fun save(remove: Boolean = false, close: Boolean = true) {
        busy = true; error = null
        scope.launch {
            runCatching {
                if (remove) start.evidence.forEach { AdminStandingStore.delete(ctx, it.id) }
                AdminStandingStore.save(ctx, db, current(), remove)
            }.onSuccess { serverOk ->
                if (!serverOk) error = "Saved. Server blocks not updated: check the admin key in the Bots tab, then save again."
                else if (close) onSaved()
            }.onFailure { error = it.message?.take(200) }
            busy = false
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { busy = true; scope.launch {
            val ev = AdminStandingStore.upload(ctx, uri)
            if (ev != null) evidence.add(ev) else error = "Upload failed"
            busy = false
        } }
    }
    val now = System.currentTimeMillis()
    val result = AdminStandingStore.levelOf(existing.filter { it.id != start.id } + current(), now)

    Column(Modifier.fillMaxSize()) {
        TopBar(if (isNew) "New action" else "Edit action", onBack = onCancel, live = !isNew)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp).widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TargetCard(target, AdminStandingStore.levelOf(existing, now), existing.size)
            Section("Type") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("warning", "restriction", "ban").forEach { k -> GremlinChip(kindLabel(k), kind == k, { kind = k }, leading = { Dot(tone(k)) }) }
                }
            }
            Section("Reason") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    REASONS.forEach { r -> GremlinChip(r, reason == r, { reason = r }) }
                }
                Field("Title", title, { title = it }, "Shown on the user's standing")
                Field("Message to user", message, { message = it }, "Shown when they open it", lines = 3)
                Field("Admin note", note, { note = it }, "Admins only", lines = 2, lock = true)
            }
            if (kind == "ban") Section("Ban") {
                Label("Length")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DURATIONS.drop(1).forEach { (l, ms) -> GremlinChip(l, banUntil != 0L && (if (ms == -1L) banUntil == -1L else picked["ban"] == l && banUntil != -1L), { picked["ban"] = l; banUntil = if (ms == -1L) -1L else System.currentTimeMillis() + ms }) }
                }
                if (banUntil != 0L) Text(endText(banUntil), style = MaterialTheme.typography.bodySmall, color = G.Caution)
                SwitchRow("VRChat account", banAccount) { banAccount = it }
                SwitchRow("This device", banDevice) { banDevice = it }
                SwitchRow("Other devices", banOthers) { banOthers = it }
            } else Section("Disable", trailing = if (off.isEmpty()) null else "${off.size} off") {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(G.Card)) {
                    Standing.Feature.entries.forEachIndexed { i, f ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(G.Edge))
                        FeatureRow(f.label, off[f.key], picked[f.key], { on -> if (on) { picked[f.key] = "1 d"; off[f.key] = System.currentTimeMillis() + 86_400_000L } else { picked.remove(f.key); off.remove(f.key) } },
                            { l, ms -> picked[f.key] = l; off[f.key] = if (ms == -1L) -1L else System.currentTimeMillis() + ms })
                    }
                }
            }
            Section("Standing") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GremlinChip("Auto", level == "auto", { level = "auto" })
                    Standing.Level.entries.forEach { l -> GremlinChip(l.label, level == l.key, { level = l.key }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("Result", Modifier.weight(1f))
                    Text(result.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = result.color)
                }
                SwitchRow("Active", active) { active = it }
            }
            Section("Evidence", trailing = if (evidence.isEmpty()) null else "${evidence.count { it.shown }} of ${evidence.size} shown") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 3) {
                    evidence.forEachIndexed { i, e ->
                        EvidenceTile(e, Modifier.weight(1f),
                            onShown = { on ->
                                scope.launch {
                                    if (AdminStandingStore.setShown(ctx, e.id, on)) {
                                        evidence[i] = e.copy(shown = on)
                                        // Live: an edited action re-saves at once, so it appears / vanishes on their screen.
                                        if (!isNew) runCatching { AdminStandingStore.save(ctx, db, current()) }
                                    } else error = "Couldn't change who sees it"
                                }
                            },
                            onDelete = {
                                scope.launch {
                                    if (AdminStandingStore.delete(ctx, e.id)) {
                                        evidence.remove(e)
                                        if (!isNew) runCatching { AdminStandingStore.save(ctx, db, current()) }
                                    } else error = "Couldn't delete it"
                                }
                            })
                    }
                    AddTile(Modifier.weight(1f)) { picker.launch(arrayOf("image/*", "video/*")) }
                    repeat((3 - (evidence.size + 1) % 3) % 3) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (!isNew) Section("History") {
                Fact("Given", stampFmt.format(java.util.Date(start.given)))
                if (start.editedAt > start.given) Fact("Edited", stampFmt.format(java.util.Date(start.editedAt)))
                Row(Modifier.padding(top = 4.dp)) {
                    GremlinButton("Remove action", { confirmRemove = true }, kind = GremlinButtonKind.Danger, size = GremlinButtonSize.Small)
                }
            }
            if (error != null) Text(error!!, style = MaterialTheme.typography.bodySmall, color = G.Error)
            Spacer(Modifier.height(4.dp))
        }
        BottomBar {
            GremlinButton("Cancel", onCancel, kind = GremlinButtonKind.Secondary)
            GremlinButton(if (!isNew) "Save changes" else if (kind == "ban") "Ban" else "Apply", { save() },
                kind = if (kind == "ban") GremlinButtonKind.Danger else GremlinButtonKind.Primary, loading = busy,
                enabled = !busy && (title.isNotBlank() || reason.isNotBlank()))
        }
    }
    if (confirmRemove) com.vrca.ui.common.VrcaConfirmDialog(
        title = "Remove this action?", body = "Its evidence files are deleted too.", confirmLabel = "Remove", destructive = true,
        onConfirm = { confirmRemove = false; save(remove = true) }, onDismiss = { confirmRemove = false })
}

// ---- pieces (the approved mock's look) ----
@Composable
private fun TopBar(title: String, onBack: () -> Unit, live: Boolean = false) =
    Row(Modifier.fillMaxWidth().background(G.Panel).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.clip(CircleShape).clickable(onClick = onBack).padding(4.dp)) { Chevron(left = true) }
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = G.Text, modifier = Modifier.weight(1f))
        if (live) Text("Live", style = MaterialTheme.typography.labelMedium, color = G.Success,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(G.Success.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp))
    }

@Composable
private fun BottomBar(content: @Composable () -> Unit) =
    Row(Modifier.fillMaxWidth().background(G.Panel).padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) { content() }

@Composable
private fun TargetCard(t: StandingTarget, level: Standing.Level, count: Int) = com.vrca.app.Layer(Modifier.fillMaxWidth()) {
    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(G.Raised), contentAlignment = Alignment.Center) {
            Text(t.name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = G.TextMuted)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.name.ifBlank { "User" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = G.Text)
            Text(t.vrchatUserId.ifBlank { t.docId.take(12) } + " · $count actions", style = MaterialTheme.typography.bodySmall, color = G.TextMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        BootCat(level.mark, 0, Modifier.size(22.dp))
        Spacer(Modifier.width(6.dp))
        Text(level.label, style = MaterialTheme.typography.labelLarge, color = level.color)
    }
}

@Composable
private fun Section(title: String, trailing: String? = null, content: @Composable () -> Unit) = com.vrca.app.Layer(Modifier.fillMaxWidth()) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = G.Text, modifier = Modifier.weight(1f))
            if (trailing != null) Text(trailing, style = MaterialTheme.typography.bodySmall, color = G.TextMuted)
        }
        content()
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) = Text(text, style = MaterialTheme.typography.labelLarge, color = G.TextMuted, modifier = modifier)

@Composable
private fun Fact(label: String, value: String) = Row {
    Text(label, style = MaterialTheme.typography.bodySmall, color = G.TextMuted, modifier = Modifier.width(110.dp))
    Text(value, style = MaterialTheme.typography.bodySmall, color = G.Text)
}

@Composable
private fun Dot(c: Color) = Box(Modifier.size(8.dp).background(c, CircleShape))

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, placeholder: String, lines: Int = 1, lock: Boolean = false) =
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(label)
            if (lock) { Spacer(Modifier.width(6.dp)); BootIconView(BootIcons.Privacy, { G.TextMuted }, { G.TextMuted }, Modifier.size(14.dp)) }
        }
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        val edge by animateColorAsState(if (focused) G.Accent else G.Edge, label = "fieldEdge")
        BasicTextField(value, onChange, interactionSource = interaction, minLines = lines, maxLines = lines + 2,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = G.Text), cursorBrush = SolidColor(G.Accent),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(G.Raised).border(1.dp, edge, RoundedCornerShape(12.dp)),
            decorationBox = { inner ->
                Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = G.TextMuted.copy(alpha = 0.6f))
                    inner()
                }
            })
    }

@Composable
private fun Switch(on: Boolean, onChange: (Boolean) -> Unit) {
    val p by animateFloatAsState(if (on) 1f else 0f, label = "sw")
    Canvas(Modifier.size(width = 40.dp, height = 24.dp).clip(RoundedCornerShape(12.dp)).clickable { onChange(!on) }) {
        val r = size.height / 2f
        drawRoundRect(lerp(G.Raised, G.Accent.copy(alpha = 0.35f), p), cornerRadius = CornerRadius(r))
        drawRoundRect(lerp(G.Edge, G.Accent, p), cornerRadius = CornerRadius(r), style = Stroke(1.dp.toPx()))
        drawCircle(lerp(G.TextMuted, G.Text, p), r - 4.dp.toPx(), Offset(r + (size.width - 2 * r) * p, r))
    }
}

@Composable
private fun SwitchRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = G.Text, modifier = Modifier.weight(1f))
        Switch(on, onChange)
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeatureRow(name: String, until: Long?, picked: String?, onToggle: (Boolean) -> Unit, onLength: (String, Long) -> Unit) =
    Column(Modifier.fillMaxWidth().animateContentSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.bodyMedium, color = if (until != null) G.Text else G.TextMuted, modifier = Modifier.weight(1f))
            if (until != null) { Text(endText(until), style = MaterialTheme.typography.bodySmall, color = G.Caution); Spacer(Modifier.width(10.dp)) }
            Switch(until != null, onToggle)
        }
        if (until != null) FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DURATIONS.forEach { (l, ms) -> SmallChip(l, if (ms == -1L) until == -1L else picked == l && until != -1L) { onLength(l, ms) } }
        }
    }

@Composable
private fun SmallChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val edge by animateColorAsState(if (selected) G.Accent else G.Edge, label = "sc")
    Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) G.AccentSoft else G.TextMuted,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) G.Accent.copy(alpha = 0.16f) else G.Raised)
            .border(1.dp, edge, RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp))
}

@Composable
private fun EvidenceTile(e: AdminStandingStore.Ev, modifier: Modifier, onShown: (Boolean) -> Unit, onDelete: () -> Unit) =
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val ctx = LocalContext.current
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 11f).clip(RoundedCornerShape(10.dp)).background(G.Card)
            .border(1.dp, if (e.shown) G.Accent.copy(alpha = 0.6f) else G.Edge, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            if (e.kind == "mp4" || e.kind == "webm") BootIconView(BootIcons.Start, { G.TextMuted }, { G.TextMuted }, Modifier.size(24.dp))
            else coil.compose.AsyncImage(AdminStandingStore.adminUrl(ctx, e.id), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Text(e.kind.uppercase(), style = MaterialTheme.typography.labelSmall, color = G.TextMuted,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(5.dp)).background(G.Raised).padding(horizontal = 5.dp, vertical = 1.dp))
            Text("✕", style = MaterialTheme.typography.labelSmall, color = G.TextMuted,
                modifier = Modifier.align(Alignment.TopEnd).clip(CircleShape).clickable(onClick = onDelete).padding(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            BootIconView(if (e.shown) BootIcons.Unlocked else BootIcons.Privacy, { if (e.shown) G.AccentSoft else G.TextMuted }, { if (e.shown) G.AccentSoft else G.TextMuted }, Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (e.shown) "User" else "Admin", style = MaterialTheme.typography.labelMedium, color = if (e.shown) G.AccentSoft else G.TextMuted, modifier = Modifier.weight(1f))
            Switch(e.shown, onShown)
        }
    }

@Composable
private fun AddTile(modifier: Modifier, onClick: () -> Unit) = Column(modifier) {
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 11f).clip(RoundedCornerShape(10.dp)).border(1.dp, G.Edge, RoundedCornerShape(10.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text("+ Add", style = MaterialTheme.typography.labelLarge, color = G.AccentSoft)
    }
}

@Composable
private fun Chevron(left: Boolean, tint: Color = G.Text) = Canvas(Modifier.size(if (left) 22.dp else 14.dp)) {
    val w = 1.8.dp.toPx(); val s = size.width
    val (a, b, c) = if (left) Triple(Offset(s * 0.62f, s * 0.22f), Offset(s * 0.32f, s * 0.5f), Offset(s * 0.62f, s * 0.78f))
        else Triple(Offset(s * 0.38f, s * 0.22f), Offset(s * 0.68f, s * 0.5f), Offset(s * 0.38f, s * 0.78f))
    drawLine(tint, a, b, w, StrokeCap.Round); drawLine(tint, b, c, w, StrokeCap.Round)
}
