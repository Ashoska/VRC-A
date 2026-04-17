// app/src/main/kotlin/com/scrapw/chatbox/AdminScreen.kt
package com.scrapw.chatbox

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.TabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.LinearProgressIndicator
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.filled.Check

private fun fmtRelativeTime(nowMs: Long, thenMs: Long): String {
    val delta = kotlin.math.abs(nowMs - thenMs)
    val sec = delta / 1000L
    return when {
        sec < 5 -> "just now"
        sec < 60 -> "${sec}s ago"
        sec < 3600 -> "${sec / 60}m ago"
        sec < 86400 -> "${sec / 3600}h ago"
        else -> "${sec / 86400}d ago"
    }
}

/**
 * Owner-only Admin screen.
 *
 * Canonical users doc is:
 * - users/{docId}  (docId usually == deviceHash)
 *
 * Mapping doc is:
 * - usersById/{uid} -> { deviceHash, authUid, appId, adminBuild, updatedAt }
 */
@Composable
fun AdminScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val db = remember { FirebaseFirestore.getInstance() }
    val auth = remember { FirebaseAuth.getInstance() }

    var globalLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun setErr(msg: String?) {
        error = msg?.trim()?.takeIf { it.isNotBlank() }?.take(4000)
    }

    // Hard block: should never be reachable on public build.
    if (!BuildConfig.IS_ADMIN_BUILD) {
        Surface {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Admin", style = MaterialTheme.typography.titleLarge)
                ErrorCard("This page is only available in the Admin build.")
            }
        }
        return
    }

    val deviceHash = remember { readDeviceHash(ctx) }

    // UID: read cached first, then ensure anon auth
    var myUid by remember { mutableStateOf(readCachedUid(ctx)) }

    LaunchedEffect(Unit) {
        if (myUid.isNotBlank()) return@LaunchedEffect
        runCatching {
            if (auth.currentUser == null) auth.signInAnonymously().await()
            val uid = auth.currentUser?.uid.orEmpty()
            if (uid.isNotBlank()) {
                writeCachedUid(ctx, uid)
                myUid = uid
            }
        }.onFailure { e ->
            setErr(e.message ?: "Auth failed while trying to get UID")
        }
    }

    // Owner gate (config/app.ownerUid)
    var ownerChecked by remember { mutableStateOf(false) }
    var ownerUid by remember { mutableStateOf("") }
    var isOwner by remember { mutableStateOf(false) }

    suspend fun refreshOwnerGate() {
        ownerChecked = false
        ownerUid = ""
        isOwner = false
        setErr(null)

        runCatching {
            val snap = db.collection("config").document("app").get().await()
            ownerUid = snap.getString("ownerUid") ?: ""
            isOwner = ownerUid.isNotBlank() && myUid.isNotBlank() && ownerUid == myUid
            ownerChecked = true
        }.onFailure { e ->
            setErr(e.message ?: "Failed to load app config")
            ownerChecked = true
        }
    }

    LaunchedEffect(myUid) { refreshOwnerGate() }

    // Gate loading screen
    if (!ownerChecked) {
        Surface {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Admin", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator()
                    Text("Checking access...")
                }
                if (error != null) ErrorCard(error!!)
            }
        }
        return
    }

    // Access denied
    if (!isOwner) {
        Surface {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Admin", style = MaterialTheme.typography.titleLarge)

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Access denied", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "This account is not the owner.\n\nUID: ${myUid.ifBlank { "(not available yet)" }}",
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                ElevatedCard {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("IDs", style = MaterialTheme.typography.titleSmall)
                        Text("deviceHash=${deviceHash.ifBlank { "(blank)" }}", fontFamily = FontFamily.Monospace)
                        Text("uid=${myUid.ifBlank { "(blank)" }}", fontFamily = FontFamily.Monospace)
                        Text("ownerUid=${ownerUid.ifBlank { "(blank)" }}", fontFamily = FontFamily.Monospace)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = {
                        myUid = readCachedUid(ctx).ifBlank { myUid }
                        scope.launch { refreshOwnerGate() }
                        setErr(null)
                    }) { Text("Re-check") }

                    if (error != null) OutlinedButton(onClick = { setErr(null) }) { Text("Clear error") }
                }

                if (error != null) ErrorCard(error!!)
            }
        }
        return
    }

    // ==========================================
    // MAIN UI
    // ==========================================
    val tabs = remember { listOf("Dashboard", "Users", "Mod", "Announce", "Releases", "Config", "Log") }
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }

    // ModerationTarget is NOT saveable
    var moderationTarget by remember { mutableStateOf<ModerationTarget?>(null) }

    // Compact IDs drawer
    var idsExpanded by rememberSaveable { mutableStateOf(false) }

    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header (clean)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Admin", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Owner build - VRC-A Admin",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(onClick = { idsExpanded = !idsExpanded }) {
                        Icon(Icons.Filled.Info, contentDescription = "IDs")
                    }
                    IconButton(onClick = {
                        scope.launch {
                            myUid = readCachedUid(ctx).ifBlank { myUid }
                            refreshOwnerGate()
                        }
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh gate")
                    }
                }
            }

            // IDs card
            AnimatedVisibility(visible = idsExpanded) {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize()
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("IDs", style = MaterialTheme.typography.titleSmall)
                            IconButton(onClick = { idsExpanded = false }) {
                                Icon(Icons.Filled.ExpandLess, contentDescription = "Close")
                            }
                        }


                        Text("deviceHash=${deviceHash.ifBlank { "(blank)" }}", fontFamily = FontFamily.Monospace)
                        Text("uid=${myUid.ifBlank { "(blank)" }}", fontFamily = FontFamily.Monospace)
                        Text("ownerUid=${ownerUid.ifBlank { "(blank)" }}", fontFamily = FontFamily.Monospace)
                    }
                }
            }

            if (error != null) {
                ErrorCard(error!!)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { setErr(null) }) { Text("Clear error") }
                }
            }

            // Tabs
            ScrollableTabRow(selectedTabIndex = tabIndex, edgePadding = 0.dp) {
                tabs.forEachIndexed { i, label ->
                    Tab(
                        selected = tabIndex == i,
                        onClick = { tabIndex = i },
                        text = {
                            Text(
                                label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                }
            }

            // Content gets remaining height
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (tabIndex) {
                    0 -> DashboardTab(db = db, setError = ::setErr)

                    1 -> UsersTab(
                        db = db,
                        myDeviceHash = deviceHash,
                        setGlobalLoading = { globalLoading = it },
                        setError = ::setErr,
                        onSendToModeration = { target ->
                            moderationTarget = target
                            tabIndex = 2
                        }
                    )

                    2 -> ModerationTab(
                        db = db,
                        myUid = myUid,
                        byDeviceHash = deviceHash,
                        byAppId = BuildConfig.APPLICATION_ID,
                        clipboardCopy = { },
                        setGlobalLoading = { globalLoading = it },
                        setError = ::setErr,
                        initialTarget = moderationTarget,
                        onClearInitialTarget = { moderationTarget = null }
                    )

                    3 -> AnnouncementsTab(
                        db = db,
                        createdByDevice = deviceHash,
                        setGlobalLoading = { globalLoading = it },
                        setError = ::setErr
                    )

                    4 -> ReleasesTab(
                        db = db,
                        setGlobalLoading = { globalLoading = it },
                        setError = ::setErr
                    )

                    5 -> ConfigTab(
                        db = db,
                        setGlobalLoading = { globalLoading = it },
                        setError = ::setErr
                    )

                    else -> ModLogTab(db = db, setError = ::setErr)
                }
            }

            if (globalLoading) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

/* =========================================================
   USERS TAB (LIVE / REACTIVE)
   ========================================================= */

private data class UserRow(
    val docId: String,
    val authUid: String,
    val displayName: String,
    val deviceHash: String,
    val warned: Boolean,
    val banned: Boolean,
    val lastSeenAt: Timestamp?,
    val updatedAt: Timestamp?,
    // VRChat
    val vrchatUserId: String = "",
    val vrchatDisplayName: String = "",
    val vrchatStatus: String = "",
    val vrchatWorld: String = "",
    val vrchatPlayerCount: Int = 0,
    val vrchatCapacity: Int = 0,
    val vrchatPlatform: String = "",
    val vrchatLastSyncAt: Timestamp? = null,
    val isOnlineInApp: Boolean = false
)

private data class UserDetail(
    // Pinned message (was AFK)
    val pinnedEnabled: Boolean,
    val pinnedMessage: String,
    val pinnedPresets: List<String>,
    val pinnedPresetNames: List<String>,
    // Cycle
    val cycleEnabled: Boolean,
    val cycleIntervalSeconds: Long,
    val cycleLinesText: String,
    val cyclePresets: List<String>,
    val cyclePresetNames: List<String>,
    // Now Playing
    val spotifyEnabled: Boolean,
    val spotifyDemoEnabled: Boolean,
    val spotifyPreset: Long,
    val nowPlayingDetected: Boolean,
    val nowPlayingIsPlaying: Boolean,
    val nowPlayingTitle: String,
    val nowPlayingArtist: String,
    // Output
    val combinedPreviewText: String,
    // Moderation
    val warnReason: String,
    val banReason: String,
    // Network
    val ip1Name: String, val ip1Address: String,
    val ip2Name: String, val ip2Address: String,
    val ip3Name: String, val ip3Address: String,
    val activeIpSlot: Long,
    // App info
    val versionName: String,
    val versionCode: Long,
    val appId: String,
    val adminBuild: Boolean,
    // VRChat
    val vrchatUserId: String,
    val vrchatDisplayName: String,
    val vrchatStatus: String,
    val vrchatStatusDescription: String,
    val vrchatWorld: String,
    val vrchatLocation: String,
    val vrchatPlayerCount: Long,
    val vrchatCapacity: Long,
    val vrchatPlatform: String,
    val timeEnabled: Boolean = false,
    val vrchatLastSyncAt: Timestamp?
)

private data class ModerationTarget(
    val docId: String,
    val authUid: String,
    val deviceHash: String,
    val displayName: String,
    val vrchatUserId: String = "",
    val banReason: String = "",
    val warnReason: String = "",
    val banned: Boolean = false,
    val warned: Boolean = false
)

@Composable
private fun UsersTab(
    db: FirebaseFirestore,
    myDeviceHash: String,
    setGlobalLoading: (Boolean) -> Unit,
    setError: (String?) -> Unit,
    onSendToModeration: (ModerationTarget) -> Unit
) {
    val users = remember { mutableStateListOf<UserRow>() }

    var search       by rememberSaveable { mutableStateOf("") }
    var filterWarned by rememberSaveable { mutableStateOf(false) }
    var filterBanned by rememberSaveable { mutableStateOf(false) }
    var selectedDocId by rememberSaveable { mutableStateOf<String?>(null) }
    var liveLimit    by rememberSaveable { mutableIntStateOf(500) }

    var selectedDetail        by remember { mutableStateOf<UserDetail?>(null) }
    var selectedDetailLoading by remember { mutableStateOf(false) }

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { nowMs = System.currentTimeMillis(); delay(1000L) }
    }

    fun rowMatches(u: UserRow, q: String): Boolean {
        if (q.isBlank()) return true
        val t = q.trim()
        return u.docId.contains(t, true) || u.authUid.contains(t, true) ||
            u.deviceHash.contains(t, true) || u.displayName.contains(t, true) ||
            u.vrchatUserId.contains(t, true) || u.vrchatDisplayName.contains(t, true) ||
            u.vrchatWorld.contains(t, true)
    }

    val filteredUsers by remember(search, filterWarned, filterBanned, users.size) {
        derivedStateOf {
            val q = search.trim()
            users.asSequence()
                .filter { if (filterWarned) it.warned else true }
                .filter { if (filterBanned) it.banned else true }
                .filter { rowMatches(it, q) }
                .toList()
        }
    }

    DisposableEffect(liveLimit) {
        setError(null); setGlobalLoading(true)
        val reg: ListenerRegistration = db.collection("users")
            .orderBy("lastSeenAt", Query.Direction.DESCENDING)
            .limit(liveLimit.toLong())
            .addSnapshotListener { snap, e ->
                if (e != null) { setGlobalLoading(false); setError(e.message ?: "Users live update failed"); return@addSnapshotListener }
                if (snap == null) { setGlobalLoading(false); return@addSnapshotListener }
                val next = snap.documents.map { d ->
                    val docId = d.id
                    val authUid = (d.getString("authUid") ?: d.getString("uid") ?: "").trim()
                    UserRow(
                        docId = docId, authUid = authUid,
                        displayName = (d.getString("displayName") ?: "").trim(),
                        deviceHash = (d.getString("deviceHash") ?: "").trim(),
                        warned = d.getBoolean("warned") ?: false,
                        banned = d.getBoolean("banned") ?: false,
                        lastSeenAt = d.getTimestamp("lastSeenAt"),
                        updatedAt = d.getTimestamp("updatedAt"),
                        vrchatUserId = (d.getString("vrchatUserId") ?: "").trim(),
                        vrchatDisplayName = (d.getString("vrchatDisplayName") ?: "").trim(),
                        vrchatStatus = (d.getString("vrchatStatus") ?: "").trim(),
                        vrchatWorld = (d.getString("vrchatWorld") ?: "").trim(),
                        vrchatPlayerCount = (d.getLong("vrchatInstancePlayerCount") ?: 0).toInt(),
                        vrchatCapacity = (d.getLong("vrchatInstanceCapacity") ?: 0).toInt(),
                        vrchatPlatform = (d.getString("vrchatPlatform") ?: "").trim(),
                        vrchatLastSyncAt = d.getTimestamp("vrchatLastSyncAt"),
                        isOnlineInApp = d.getBoolean("isOnlineInApp") ?: false
                    )
                }
                users.clear(); users.addAll(next); setGlobalLoading(false)
            }
        onDispose { reg.remove() }
    }

    DisposableEffect(selectedDocId) {
        val docId = selectedDocId
        if (docId.isNullOrBlank()) {
            selectedDetail = null; selectedDetailLoading = false
            return@DisposableEffect onDispose { }
        }
        selectedDetailLoading = true; setError(null)
        val reg = db.collection("users").document(docId)
            .addSnapshotListener { snap, e ->
                if (e != null) { selectedDetailLoading = false; setError(e.message ?: "User detail live update failed"); return@addSnapshotListener }
                if (snap == null || !snap.exists()) { selectedDetailLoading = false; selectedDetail = null; return@addSnapshotListener }
                fun s(key: String) = (snap.getString(key) ?: "").trim()
                fun b(key: String) = snap.getBoolean(key) ?: false
                fun l(key: String) = snap.getLong(key) ?: 0L
                selectedDetail = UserDetail(
                    pinnedEnabled  = b("afkEnabled"), pinnedMessage = s("afkMessage"),
                    pinnedPresets  = listOf(s("afkPreset1"), s("afkPreset2"), s("afkPreset3")),
                    pinnedPresetNames = listOf(s("afkPreset1Name").ifBlank { "Preset 1" }, s("afkPreset2Name").ifBlank { "Preset 2" }, s("afkPreset3Name").ifBlank { "Preset 3" }),
                    cycleEnabled   = b("cycleEnabled"), cycleIntervalSeconds = l("cycleIntervalSeconds"),
                    cycleLinesText = s("cycleLinesText"),
                    cyclePresets   = listOf(s("cyclePreset1"), s("cyclePreset2"), s("cyclePreset3"), s("cyclePreset4"), s("cyclePreset5")),
                    cyclePresetNames = listOf(s("cyclePreset1Name").ifBlank { "Preset 1" }, s("cyclePreset2Name").ifBlank { "Preset 2" }, s("cyclePreset3Name").ifBlank { "Preset 3" }, s("cyclePreset4Name").ifBlank { "Preset 4" }, s("cyclePreset5Name").ifBlank { "Preset 5" }),
                    spotifyEnabled = b("spotifyEnabled"), spotifyDemoEnabled = b("spotifyDemoEnabled"),
                    spotifyPreset  = l("spotifyPreset"),
                    nowPlayingDetected = b("nowPlayingDetected"), nowPlayingIsPlaying = b("nowPlayingIsPlaying"),
                    nowPlayingTitle = s("nowPlayingTitle"), nowPlayingArtist = s("nowPlayingArtist"),
                    combinedPreviewText = s("combinedPreviewText"),
                    warnReason = s("warnReason"), banReason = s("banReason"),
                    ip1Name = s("ip1Name").ifBlank { "Home" }, ip1Address = s("ip1Address"),
                    ip2Name = s("ip2Name").ifBlank { "Hotspot" }, ip2Address = s("ip2Address"),
                    ip3Name = s("ip3Name").ifBlank { "Other" }, ip3Address = s("ip3Address"),
                    activeIpSlot = l("activeIpSlot").let { if (it == 0L) 1L else it },
                    versionName = s("versionName"), versionCode = l("versionCode"), appId = s("appId"),
                    adminBuild = b("adminBuild"),
                    vrchatUserId = s("vrchatUserId"), vrchatDisplayName = s("vrchatDisplayName"),
                    vrchatStatus = s("vrchatStatus"), vrchatStatusDescription = s("vrchatStatusDescription"),
                    vrchatWorld = s("vrchatWorld"), vrchatLocation = s("vrchatLocation"),
                    vrchatPlayerCount = l("vrchatInstancePlayerCount"), vrchatCapacity = l("vrchatInstanceCapacity"),
                    vrchatPlatform = s("vrchatPlatform"),
                    timeEnabled = b("timeEnabled"),
                    vrchatLastSyncAt = snap.getTimestamp("vrchatLastSyncAt")
                )
                selectedDetailLoading = false
            }
        onDispose { reg.remove() }
    }

    val selectedRow = remember(selectedDocId, users.size) {
        selectedDocId?.let { id -> users.firstOrNull { it.docId == id } }
    }

    // ---- Detail view ----
    if (selectedRow != null) {
        val d = selectedDetail
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ElevatedCard {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)) {
                            IconButton(onClick = { selectedDocId = null }) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                            }
                            Column(Modifier.weight(1f)) {
                                val primaryLabel = selectedRow.vrchatDisplayName.ifBlank {
                                    selectedRow.displayName.ifBlank { shortId(selectedRow.docId) }
                                }
                                Text(
                                    primaryLabel,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                if (selectedRow.vrchatUserId.isNotBlank()) {
                                    Text(
                                        selectedRow.vrchatUserId,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    shortId(selectedRow.docId),
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (selectedRow.banned) {
                                androidx.compose.material3.Badge(
                                    containerColor = MaterialTheme.colorScheme.error
                                ) { Text("BANNED", style = MaterialTheme.typography.labelSmall) }
                            }
                            if (selectedRow.warned) {
                                androidx.compose.material3.Badge(
                                    containerColor = MaterialTheme.colorScheme.tertiary
                                ) { Text("WARNED", style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    }

                    Divider()

                    @Composable
                    fun InfoRow(label: String, value: String) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(72.dp))
                            Text(value,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f))
                        }
                    }

                    InfoRow("authUid", shortId(selectedRow.authUid.ifBlank { "(blank)" }))
                    InfoRow("device",  shortId(selectedRow.deviceHash.ifBlank { "(blank)" }))
                    InfoRow("lastSeen", relativeTime(selectedRow.lastSeenAt, nowMs))
                    InfoRow("updated",  relativeTime(selectedRow.updatedAt, nowMs))

                    Divider()

                    Button(
                        onClick = {
                            onSendToModeration(ModerationTarget(
                                docId = selectedRow.docId, authUid = selectedRow.authUid,
                                deviceHash = selectedRow.deviceHash,
                                displayName = selectedRow.vrchatDisplayName.ifBlank { selectedRow.displayName },
                                vrchatUserId = selectedRow.vrchatUserId,
                                banned = selectedRow.banned, warned = selectedRow.warned,
                                banReason = selectedDetail?.banReason ?: "",
                                warnReason = selectedDetail?.warnReason ?: ""
                            ))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.ArrowForward, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Send to Moderation")
                    }
                }
            }

            if (selectedDetailLoading) {
                ElevatedCard {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(); Text("Loading details...")
                    }
                }
            } else if (d != null) {
                DetailBlock(d = d, docId = selectedDocId ?: "", db = db, setError = setError)
            } else {
                ElevatedCard {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Details", style = MaterialTheme.typography.titleSmall)
                        Text("No detail loaded (doc missing or not yet written).",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        return
    }

    // ---- List view ----
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            ElevatedCard {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Users", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(
                                "${filteredUsers.size} / ${users.size}",
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(
                                onClick = { liveLimit = (liveLimit + 500).coerceAtMost(10000) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) { Text("+500", style = MaterialTheme.typography.labelSmall) }
                        }
                    }

                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("Search name / id / uid / device") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null,
                            modifier = Modifier.size(18.dp)) },
                        trailingIcon = if (search.isNotBlank()) ({
                            IconButton(onClick = { search = "" },
                                modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.Remove, contentDescription = "Clear",
                                    modifier = Modifier.size(16.dp))
                            }
                        }) else null
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.FilterChip(
                            selected = filterWarned,
                            onClick = { filterWarned = !filterWarned },
                            label = { Text("Warned", style = MaterialTheme.typography.labelSmall) }
                        )
                        androidx.compose.material3.FilterChip(
                            selected = filterBanned,
                            onClick = { filterBanned = !filterBanned },
                            label = { Text("Banned", style = MaterialTheme.typography.labelSmall) }
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${filteredUsers.size} of $liveLimit loaded",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically)
                        )
                    }
                }
            }
        }

        if (filteredUsers.isEmpty()) {
            item {
                Text("No users matching current filters.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp))
            }
        }

        items(filteredUsers, key = { it.docId }) { u ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        u.banned -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                        u.warned -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
                        else     -> MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
                modifier = Modifier.fillMaxWidth().clickable { selectedDocId = u.docId }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        // VRChat display name is primary identifier; fall back to displayName then docId
                        val primaryName = u.vrchatDisplayName.ifBlank { u.displayName.ifBlank { shortId(u.docId) } }
                        val secondaryName = if (u.vrchatDisplayName.isNotBlank() && u.displayName.isNotBlank() && u.vrchatDisplayName != u.displayName) u.displayName else null
                        Text(
                            primaryName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        if (secondaryName != null) {
                            Text(secondaryName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1)
                        }
                        if (u.vrchatWorld.isNotBlank()) {
                            Text("📍 ${u.vrchatWorld}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(
                            relativeTime(u.lastSeenAt, nowMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        if (u.isOnlineInApp) {
                            androidx.compose.material3.Badge(
                                containerColor = MaterialTheme.colorScheme.primary
                            ) { Text("ONLINE", style = MaterialTheme.typography.labelSmall) }
                        }
                        if (u.banned) {
                            androidx.compose.material3.Badge(
                                containerColor = MaterialTheme.colorScheme.error
                            ) { Text("BAN", style = MaterialTheme.typography.labelSmall) }
                        }
                        if (u.warned) {
                            androidx.compose.material3.Badge(
                                containerColor = MaterialTheme.colorScheme.tertiary
                            ) { Text("WARN", style = MaterialTheme.typography.labelSmall) }
                        }
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun DetailBlock(d: UserDetail, docId: String, db: FirebaseFirestore, setError: (String?) -> Unit) {
    val scope = rememberCoroutineScope()

    fun writeField(key: String, value: Any) {
        if (docId.isBlank()) return
        db.collection("users").document(docId)
            .set(mapOf(key to value), SetOptions.merge())
            .addOnFailureListener { e -> setError("Write failed: ${e.message}") }
    }

    // ── VRChat ──────────────────────────────────────────────────────
    ElevatedCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("VRChat", style = MaterialTheme.typography.titleSmall)
            if (d.vrchatUserId.isBlank()) {
                Text("Not linked", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val dot = when (d.vrchatStatus) {
                    "active", "join me" -> "🟢"; "ask me" -> "🟠"; "busy" -> "🔴"; else -> "⚫"
                }
                Text("$dot ${d.vrchatDisplayName.ifBlank { d.vrchatUserId }}",
                    style = MaterialTheme.typography.bodyMedium)
                if (d.vrchatStatusDescription.isNotBlank())
                    Text(d.vrchatStatusDescription, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (d.vrchatWorld.isNotBlank()) {
                    val cnt = if (d.vrchatCapacity > 0) "${d.vrchatPlayerCount}/${d.vrchatCapacity}"
                              else "${d.vrchatPlayerCount}"
                    Text("📍 ${d.vrchatWorld} ($cnt)", style = MaterialTheme.typography.bodySmall)
                }
                val ctx = androidx.compose.ui.platform.LocalContext.current
                Text("ID: ${d.vrchatUserId}", fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://vrchat.com/home/user/${d.vrchatUserId}"))
                        ctx.startActivity(intent)
                    })
                if (d.vrchatLastSyncAt != null)
                    Text("Synced ${relativeTime(d.vrchatLastSyncAt, System.currentTimeMillis())}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    // ── Live Output + Feature Toggles ───────────────────────────────
    ElevatedCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Live Chatbox Output", style = MaterialTheme.typography.titleSmall)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text(d.combinedPreviewText.ifBlank { "(nothing sending)" },
                    modifier = Modifier.padding(10.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall)
            }
            // Remote toggle chips
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = d.pinnedEnabled, onClick = { writeField("afkEnabled", !d.pinnedEnabled) },
                    label = { Text("Pinned", style = MaterialTheme.typography.labelSmall) })
                FilterChip(selected = d.cycleEnabled, onClick = { writeField("cycleEnabled", !d.cycleEnabled) },
                    label = { Text("Cycle", style = MaterialTheme.typography.labelSmall) })
                FilterChip(selected = d.spotifyEnabled, onClick = { writeField("spotifyEnabled", !d.spotifyEnabled) },
                    label = { Text("Music", style = MaterialTheme.typography.labelSmall) })
                FilterChip(selected = d.timeEnabled, onClick = { writeField("timeEnabled", !d.timeEnabled) },
                    label = { Text("Time", style = MaterialTheme.typography.labelSmall) })
            }
            if (d.nowPlayingDetected) {
                val musicCtx = androidx.compose.ui.platform.LocalContext.current
                val musicQuery = "${d.nowPlayingTitle} ${d.nowPlayingArtist}".trim()
                Text("🎵 ${d.nowPlayingTitle.ifBlank { "?" }} — ${d.nowPlayingArtist.ifBlank { "?" }} " +
                    if (d.nowPlayingIsPlaying) "▶" else "⏸",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        if (musicQuery.isNotBlank()) {
                            val searchUrl = "https://open.spotify.com/search/${android.net.Uri.encode(musicQuery)}"
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(searchUrl))
                            musicCtx.startActivity(intent)
                        }
                    })
            }
        }
    }

    // ── Pinned Message ───────────────────────────────────────────────
    ElevatedCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Pinned Message", style = MaterialTheme.typography.titleSmall)
            var pinnedEdit by remember(d.pinnedMessage) { mutableStateOf(d.pinnedMessage) }
            OutlinedTextField(
                value = pinnedEdit,
                onValueChange = { pinnedEdit = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Pinned text") },
                trailingIcon = {
                    if (pinnedEdit != d.pinnedMessage)
                        IconButton(onClick = { writeField("afkMessage", pinnedEdit) }) {
                            Icon(Icons.Filled.Check, "Save", modifier = Modifier.size(18.dp))
                        }
                }
            )
            Divider()
            Text("Presets", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            d.pinnedPresets.forEachIndexed { i, preset ->
                val name = d.pinnedPresetNames.getOrElse(i) { "Preset ${i+1}" }
                var pe by remember(preset) { mutableStateOf(preset) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = pe, onValueChange = { pe = it },
                        modifier = Modifier.weight(1f), singleLine = true, label = { Text(name) })
                    if (pe != preset)
                        IconButton(onClick = { writeField("afkPreset${i+1}", pe) }) {
                            Icon(Icons.Filled.Check, "Save", modifier = Modifier.size(18.dp))
                        }
                    OutlinedButton(onClick = { writeField("afkMessage", pe) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Text("Load", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    // ── Cycle ────────────────────────────────────────────────────────
    ElevatedCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Cycle", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    var intEdit by remember(d.cycleIntervalSeconds) { mutableStateOf(d.cycleIntervalSeconds.toString()) }
                    OutlinedTextField(value = intEdit, onValueChange = { intEdit = it.filter { c -> c.isDigit() } },
                        modifier = Modifier.width(72.dp), singleLine = true, label = { Text("Sec") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                    if (intEdit.isNotBlank() && intEdit.toLongOrNull() != d.cycleIntervalSeconds)
                        IconButton(onClick = { intEdit.toLongOrNull()?.let { writeField("cycleIntervalSeconds", it) } }) {
                            Icon(Icons.Filled.Check, "Save", modifier = Modifier.size(18.dp))
                        }
                }
            }
            if (d.cycleLinesText.isNotBlank()) {
                var cycleEdit by remember(d.cycleLinesText) { mutableStateOf(d.cycleLinesText) }
                OutlinedTextField(value = cycleEdit, onValueChange = { cycleEdit = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("Cycle lines") },
                    minLines = 2, maxLines = 6,
                    trailingIcon = {
                        if (cycleEdit != d.cycleLinesText)
                            IconButton(onClick = { writeField("cycleLinesText", cycleEdit) }) {
                                Icon(Icons.Filled.Check, "Save", modifier = Modifier.size(18.dp))
                            }
                    })
            }
            Divider()
            Text("Cycle Presets", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            d.cyclePresets.forEachIndexed { i, preset ->
                val defaultName = "Preset ${i+1}"
                val name = d.cyclePresetNames.getOrElse(i) { defaultName }
                var nameEdit by remember(name) { mutableStateOf(name) }
                var pe by remember(preset) { mutableStateOf(preset) }
                var expanded by remember { mutableStateOf(false) }
                ElevatedCard {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(value = nameEdit, onValueChange = { nameEdit = it },
                                modifier = Modifier.weight(1f), singleLine = true,
                                label = { Text("Name") })
                            if (nameEdit != name)
                                IconButton(onClick = { writeField("cyclePreset${i+1}Name", nameEdit) }) {
                                    Icon(Icons.Filled.Check, "Save name", modifier = Modifier.size(18.dp))
                                }
                            IconButton(onClick = { expanded = !expanded }) {
                                Icon(
                                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                    contentDescription = if (expanded) "Collapse" else "Expand"
                                )
                            }
                        }
                        if (expanded) {
                            OutlinedTextField(value = pe, onValueChange = { pe = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Cycle lines") },
                                minLines = 2, maxLines = 8)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (pe != preset)
                                    Button(onClick = { writeField("cyclePreset${i+1}", pe) },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                                        Text("Save", style = MaterialTheme.typography.labelSmall)
                                    }
                                OutlinedButton(onClick = { writeField("cycleLinesText", pe) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                                    Text("Load to active", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        } else {
                            val preview = preset.lines().firstOrNull { it.isNotBlank() }?.trim() ?: "(empty)"
                            Text(preview, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }

    // ── Network ──────────────────────────────────────────────────────
    ElevatedCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Network", style = MaterialTheme.typography.titleSmall)
            listOf(Triple(1L, d.ip1Name, d.ip1Address),
                   Triple(2L, d.ip2Name, d.ip2Address),
                   Triple(3L, d.ip3Name, d.ip3Address)).forEach { (slot, name, addr) ->
                var addrEdit by remember(addr) { mutableStateOf(addr) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (slot == d.activeIpSlot) "▶" else "  ",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(16.dp))
                    Text("[$name]", style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(72.dp))
                    OutlinedTextField(value = addrEdit, onValueChange = { addrEdit = it },
                        modifier = Modifier.weight(1f), singleLine = true, label = { Text("IP") })
                    if (addrEdit.trim() != addr)
                        IconButton(onClick = { writeField("ip${slot}Address", addrEdit.trim()) }) {
                            Icon(Icons.Filled.Check, "Save", modifier = Modifier.size(18.dp))
                        }
                    if (slot != d.activeIpSlot)
                        OutlinedButton(onClick = { writeField("activeIpSlot", slot.toInt()) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
                            Text("Set", style = MaterialTheme.typography.labelSmall)
                        }
                }
            }
        }
    }

    // ── App Info ─────────────────────────────────────────────────────
    ElevatedCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("App", style = MaterialTheme.typography.titleSmall)
            Text("${d.versionName} (${d.versionCode})", fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall)
            Text(d.appId, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (d.adminBuild)
                Text("Admin build", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary)
        }
    }

    // ── Moderation flags ─────────────────────────────────────────────
    if (d.warnReason.isNotBlank() || d.banReason.isNotBlank()) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Moderation Flags", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer)
                if (d.warnReason.isNotBlank())
                    Text("Warn: ${d.warnReason}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                if (d.banReason.isNotBlank())
                    Text("Ban: ${d.banReason}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

/* =========================================================
   MODERATION TAB (LIVE + NO INDEX REQUIRED)
   ========================================================= */

private data class ModerationEventRow(
    val id: String,
    val action: String,
    val reason: String,
    val createdAt: Timestamp?,
    val byDeviceHash: String,
    val byUid: String,
    val byAppId: String,
    val targetDocId: String,
    val targetAuthUid: String,
    val targetDeviceHash: String
)

@Composable
private fun ModerationTab(
    db: FirebaseFirestore,
    myUid: String,
    byDeviceHash: String,
    byAppId: String,
    clipboardCopy: (String) -> Unit,
    setGlobalLoading: (Boolean) -> Unit,
    setError: (String?) -> Unit,
    initialTarget: ModerationTarget?,
    onClearInitialTarget: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var lookup by rememberSaveable { mutableStateOf("") } // can be docId or authUid
    var loaded by remember { mutableStateOf<ModerationTarget?>(null) }

    // LIVE state from Firestore (never bind these directly to TextFields)
    var liveWarned by remember { mutableStateOf(false) }
    var liveBanned by remember { mutableStateOf(false) }
    var liveWarnReason by remember { mutableStateOf("") }
    var liveBanReason by remember { mutableStateOf("") }

    // EDITOR state (TextFields bind to these)
    var editWarnReason by rememberSaveable { mutableStateOf("") }
    var editBanReason by rememberSaveable { mutableStateOf("") }
    var editingWarn by rememberSaveable { mutableStateOf(false) }
    var editingBan by rememberSaveable { mutableStateOf(false) }

    var deviceBanned by remember { mutableStateOf(false) }
    var deviceBanReason by remember { mutableStateOf("") }
    var applyUidBan by rememberSaveable { mutableStateOf(true) }
    var applyDeviceBan by rememberSaveable { mutableStateOf(true) }

    val history = remember { mutableStateListOf<ModerationEventRow>() }

    var userDocReg by remember { mutableStateOf<ListenerRegistration?>(null) }
    var deviceDocReg by remember { mutableStateOf<ListenerRegistration?>(null) }

    fun clearLoaded() {
        userDocReg?.remove(); userDocReg = null
        deviceDocReg?.remove(); deviceDocReg = null
        loaded = null

        liveWarned = false
        liveBanned = false
        liveWarnReason = ""
        liveBanReason = ""

        editWarnReason = ""
        editBanReason = ""
        editingWarn = false
        editingBan = false

        deviceBanned = false
        deviceBanReason = ""
        history.clear()
    }

    suspend fun resolveUser(input: String): ModerationTarget? {
        val t = input.trim()
        if (t.isBlank()) return null

        // 1) Try docId directly (users/{deviceHash})
        runCatching {
            val doc = db.collection("users").document(t).get().await()
            if (doc.exists()) {
                val authUid = (doc.getString("authUid") ?: doc.getString("uid") ?: "").trim()
                val deviceHash = (doc.getString("deviceHash") ?: "").trim()
                val displayName = (doc.getString("displayName") ?: "").trim()
                return ModerationTarget(docId = t, authUid = authUid, deviceHash = deviceHash, displayName = displayName)
            }
        }

        // 2) Try usersById/{uid} mapping
        runCatching {
            val map = db.collection("usersById").document(t).get().await()
            if (map.exists()) {
                val deviceHash = (map.getString("deviceHash") ?: "").trim()
                val authUid = (map.getString("authUid") ?: t).trim()
                if (deviceHash.isNotBlank()) {
                    val doc = db.collection("users").document(deviceHash).get().await()
                    if (doc.exists()) {
                        val displayName = (doc.getString("displayName") ?: "").trim()
                        return ModerationTarget(docId = deviceHash, authUid = authUid, deviceHash = deviceHash, displayName = displayName)
                    }
                    // mapping exists but user doc missing
                    return ModerationTarget(docId = deviceHash, authUid = authUid, deviceHash = deviceHash, displayName = "")
                }
            }
        }

        // 3) Fallback: lookup by authUid in users collection
        return runCatching {
            val q = db.collection("users")
                .whereEqualTo("authUid", t)
                .limit(1)
                .get()
                .await()

            val doc = q.documents.firstOrNull() ?: return@runCatching null
            val docId = doc.id
            val authUid = (doc.getString("authUid") ?: doc.getString("uid") ?: "").trim()
            val deviceHash = (doc.getString("deviceHash") ?: "").trim()
            val displayName = (doc.getString("displayName") ?: "").trim()
            ModerationTarget(docId = docId, authUid = authUid, deviceHash = deviceHash, displayName = displayName)
        }.getOrNull()
    }

    suspend fun loadHistoryNoIndex(target: ModerationTarget) {
        // No orderBy here -> no composite index required
        val snap = db.collection("moderationEvents")
            .whereEqualTo("targetDocId", target.docId)
            .limit(200)
            .get()
            .await()

        val rows = snap.documents.map { d ->
            ModerationEventRow(
                id = d.id,
                action = d.getString("action") ?: "",
                reason = d.getString("reason") ?: "",
                createdAt = d.getTimestamp("createdAt"),
                byDeviceHash = d.getString("byDeviceHash") ?: "",
                byUid = d.getString("byUid") ?: "",
                byAppId = d.getString("byAppId") ?: "",
                targetDocId = d.getString("targetDocId") ?: (d.getString("targetUid") ?: ""),
                targetAuthUid = d.getString("targetAuthUid") ?: "",
                targetDeviceHash = d.getString("targetDeviceHash") ?: ""
            )
        }.sortedByDescending { it.createdAt?.seconds ?: 0L }

        history.clear()
        history.addAll(rows)
    }

    fun attachLiveTarget(target: ModerationTarget) {
        userDocReg?.remove(); userDocReg = null
        deviceDocReg?.remove(); deviceDocReg = null

        userDocReg = db.collection("users").document(target.docId)
            .addSnapshotListener { snap, e ->
                if (e != null) {
                    setError(e.message ?: "Moderation live user update failed")
                    return@addSnapshotListener
                }
                if (snap == null || !snap.exists()) return@addSnapshotListener

                liveWarned = snap.getBoolean("warned") ?: false
                liveBanned = snap.getBoolean("banned") ?: false
                liveWarnReason = (snap.getString("warnReason") ?: "").trim()
                liveBanReason = (snap.getString("banReason") ?: "").trim()

                // Only refresh the editor if user is NOT actively editing
                if (!editingWarn) editWarnReason = liveWarnReason
                if (!editingBan) editBanReason = liveBanReason

                val dh = (snap.getString("deviceHash") ?: target.deviceHash).trim()
                if (dh.isNotBlank()) {
                    deviceDocReg?.remove()
                    deviceDocReg = db.collection("bannedDevices").document(dh)
                        .addSnapshotListener { ds, de ->
                            if (de != null) {
                                setError(de.message ?: "Moderation live device update failed")
                                return@addSnapshotListener
                            }
                            if (ds == null || !ds.exists()) {
                                deviceBanned = false
                                deviceBanReason = ""
                                return@addSnapshotListener
                            }
                            deviceBanned = ds.getBoolean("banned") ?: false
                            deviceBanReason = (ds.getString("reason") ?: "").trim()
                        }
                } else {
                    deviceBanned = false
                    deviceBanReason = ""
                }
            }
    }

    suspend fun loadTarget(target: ModerationTarget) {
        setGlobalLoading(true)
        setError(null)
        runCatching {
            loaded = target
            // reset editing flags for a new target so fields behave
            editingWarn = false
            editingBan = false
            attachLiveTarget(target)
            loadHistoryNoIndex(target)
            setGlobalLoading(false)
        }.onFailure { e ->
            setGlobalLoading(false)
            setError(e.message ?: "Failed to load moderation target")
        }
    }

    suspend fun writeEvent(target: ModerationTarget, action: String, reason: String) {
        runCatching {
            val data = hashMapOf(
                "uid" to target.docId,
                "targetUid" to target.docId,
                "targetDocId" to target.docId,
                "targetAuthUid" to target.authUid,
                "targetDeviceHash" to target.deviceHash,
                "action" to action,
                "reason" to reason.trim(),
                "createdAt" to FieldValue.serverTimestamp(),
                "byDeviceHash" to byDeviceHash,
                "byUid" to myUid,
                "byAppId" to byAppId
            )
            db.collection("moderationEvents").add(data).await()
        }.onFailure { e ->
            setError("Event write failed: ${e.message ?: "unknown"}")
        }
    }

    LaunchedEffect(initialTarget?.docId) {
        val t = initialTarget ?: return@LaunchedEffect
        lookup = t.docId
        loadTarget(t)
        onClearInitialTarget()
    }

    DisposableEffect(Unit) {
        onDispose {
            userDocReg?.remove()
            deviceDocReg?.remove()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ElevatedCard {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Moderation", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Lookup accepts docId OR authUid.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = lookup,
                    onValueChange = { lookup = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("docId or authUid") },
                    placeholder = { Text("paste here") }
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                setGlobalLoading(true)
                                setError(null)
                                val t = resolveUser(lookup)
                                setGlobalLoading(false)
                                if (t == null) setError("No matching user found for: ${lookup.trim()}")
                                else loadTarget(t)
                            }
                        },
                        enabled = lookup.trim().isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) { Text("Load") }

                    OutlinedButton(
                        onClick = { clearLoaded() },
                        modifier = Modifier.weight(1f)
                    ) { Text("Clear") }
                }
            }
        }

        val t = loaded
        if (t == null) {
            Text("No user loaded.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            return
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Target", style = MaterialTheme.typography.titleSmall)
                Text("displayName=${t.displayName.ifBlank { "(blank)" }}", fontFamily = FontFamily.Monospace)
                Text("docId=${t.docId}", fontFamily = FontFamily.Monospace)
                Text("authUid=${t.authUid.ifBlank { "(blank)" }}", fontFamily = FontFamily.Monospace)
                Text("deviceHash=${t.deviceHash.ifBlank { "(blank)" }}", fontFamily = FontFamily.Monospace)

                
                OutlinedButton(
                    onClick = { scope.launch { loadTarget(t) } },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Reload (history + live)") }

                Text(
                    "warned=$liveWarned  banned=$liveBanned  deviceBanned=$deviceBanned",
                    fontFamily = FontFamily.Monospace
                )
                if (deviceBanned && deviceBanReason.isNotBlank()) {
                    Text("deviceBanReason=$deviceBanReason", fontFamily = FontFamily.Monospace)
                }
            }
        }

        // WARN
        ElevatedCard {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Warn", style = MaterialTheme.typography.titleSmall)

                OutlinedTextField(
                    value = editWarnReason,
                    onValueChange = {
                        editingWarn = true
                        editWarnReason = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    label = { Text("Warn reason (shown to user)") }
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                setGlobalLoading(true)
                                setError(null)

                                runCatching {
                                    val reason = editWarnReason.trim()
                                    db.collection("users").document(t.docId)
                                        .set(
                                            mapOf(
                                                "warned" to true,
                                                "warnReason" to reason,
                                                "updatedAt" to FieldValue.serverTimestamp()
                                            ),
                                            SetOptions.merge()
                                        )
                                        .await()
                                    writeEvent(t, "warn", reason)
                                    loadHistoryNoIndex(t)

                                    editingWarn = false
                                    setGlobalLoading(false)
                                }.onFailure { e ->
                                    setGlobalLoading(false)
                                    setError(e.message ?: "Failed to warn")
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Apply warn") }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                setGlobalLoading(true)
                                setError(null)

                                runCatching {
                                    db.collection("users").document(t.docId)
                                        .set(
                                            mapOf(
                                                "warned" to false,
                                                "warnReason" to "",
                                                "updatedAt" to FieldValue.serverTimestamp()
                                            ),
                                            SetOptions.merge()
                                        )
                                        .await()
                                    writeEvent(t, "remove_warn", "")
                                    loadHistoryNoIndex(t)

                                    // reset editor immediately too
                                    editWarnReason = ""
                                    editingWarn = false

                                    setGlobalLoading(false)
                                }.onFailure { e ->
                                    setGlobalLoading(false)
                                    setError(e.message ?: "Failed to remove warn")
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Remove warn") }
                }

                // quick "use live" in case you're mid-edit and want to discard
                if (editingWarn) {
                    OutlinedButton(
                        onClick = {
                            editWarnReason = liveWarnReason
                            editingWarn = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Discard typing (use live)") }
                }
            }
        }

        // BAN
        ElevatedCard {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Ban", style = MaterialTheme.typography.titleSmall)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Apply UID")
                        Switch(checked = applyUidBan, onCheckedChange = { applyUidBan = it })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Apply Device")
                        Switch(
                            checked = applyDeviceBan,
                            onCheckedChange = { applyDeviceBan = it },
                            enabled = t.deviceHash.isNotBlank()
                        )
                    }
                }

                OutlinedTextField(
                    value = editBanReason,
                    onValueChange = {
                        editingBan = true
                        editBanReason = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    label = { Text("Ban reason (shown to user)") }
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                val reason = editBanReason.trim()
                                setGlobalLoading(true)
                                setError(null)

                                runCatching {
                                    if (applyUidBan) {
                                        db.collection("users").document(t.docId)
                                            .set(
                                                mapOf(
                                                    "banned" to true,
                                                    "banReason" to reason,
                                                    "updatedAt" to FieldValue.serverTimestamp()
                                                ),
                                                SetOptions.merge()
                                            )
                                            .await()
                                    }

                                    if (applyDeviceBan) {
                                        if (t.deviceHash.isBlank()) {
                                            throw IllegalStateException("Cannot device-ban: deviceHash missing.")
                                        }
                                        db.collection("bannedDevices").document(t.deviceHash)
                                            .set(
                                                mapOf(
                                                    "banned" to true,
                                                    "reason" to reason,
                                                    "updatedAt" to FieldValue.serverTimestamp(),
                                                    "updatedByUid" to myUid,
                                                    "updatedByDeviceHash" to byDeviceHash,
                                                    "updatedByAppId" to byAppId
                                                ),
                                                SetOptions.merge()
                                            )
                                            .await()
                                    }

                                    writeEvent(t, "ban", reason)
                                    loadHistoryNoIndex(t)

                                    editingBan = false
                                    setGlobalLoading(false)
                                }.onFailure { e ->
                                    setGlobalLoading(false)
                                    setError(e.message ?: "Failed to ban")
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = (applyUidBan || (applyDeviceBan && t.deviceHash.isNotBlank()))
                    ) { Text("Ban") }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                setGlobalLoading(true)
                                setError(null)

                                runCatching {
                                    if (applyUidBan) {
                                        db.collection("users").document(t.docId)
                                            .set(
                                                mapOf(
                                                    "banned" to false,
                                                    "banReason" to "",
                                                    "updatedAt" to FieldValue.serverTimestamp()
                                                ),
                                                SetOptions.merge()
                                            )
                                            .await()
                                    }

                                    if (applyDeviceBan) {
                                        if (t.deviceHash.isBlank()) {
                                            throw IllegalStateException("Cannot device-unban: deviceHash missing.")
                                        }
                                        db.collection("bannedDevices").document(t.deviceHash)
                                            .set(
                                                mapOf(
                                                    "banned" to false,
                                                    "reason" to "",
                                                    "updatedAt" to FieldValue.serverTimestamp(),
                                                    "updatedByUid" to myUid,
                                                    "updatedByDeviceHash" to byDeviceHash,
                                                    "updatedByAppId" to byAppId
                                                ),
                                                SetOptions.merge()
                                            )
                                            .await()
                                    }

                                    writeEvent(t, "unban", "")
                                    loadHistoryNoIndex(t)

                                    editBanReason = ""
                                    editingBan = false

                                    setGlobalLoading(false)
                                }.onFailure { e ->
                                    setGlobalLoading(false)
                                    setError(e.message ?: "Failed to unban")
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = (applyUidBan || (applyDeviceBan && t.deviceHash.isNotBlank()))
                    ) { Text("Unban") }
                }

                if (editingBan) {
                    OutlinedButton(
                        onClick = {
                            editBanReason = liveBanReason
                            editingBan = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Discard typing (use live)") }
                }
            }
        }

        // HISTORY
        ElevatedCard {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("History", style = MaterialTheme.typography.titleSmall)
                    OutlinedButton(onClick = { scope.launch { loadHistoryNoIndex(t) } }) { Text("Reload") }
                }

                if (history.isEmpty()) {
                    Text("No history found.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        history.forEach { e ->
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Column(
                                    Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        "${e.action.ifBlank { "(no action)" }}  @  ${formatTimestamp(e.createdAt)}",
                                        fontFamily = FontFamily.Monospace
                                    )
                                    if (e.reason.isNotBlank()) {
                                        Text("reason=${e.reason}", fontFamily = FontFamily.Monospace)
                                    }
                                    if (e.targetDeviceHash.isNotBlank()) {
                                        Text(
                                            "targetDevice=${e.targetDeviceHash.take(16)}...",
                                            fontFamily = FontFamily.Monospace,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Text(
                                        "byUid=${e.byUid.ifBlank { "?" }}  byDevice=${e.byDeviceHash.take(12).ifBlank { "?" }}...",
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
    }
}

/* =========================================================
   ANNOUNCEMENTS TAB
   ========================================================= */

private data class AnnouncementRow(
    val id: String,
    val title: String,
    val body: String,
    val active: Boolean,
    val priority: Int,
    val createdAt: Timestamp?
)

@Composable
private fun AnnouncementsTab(
    db: FirebaseFirestore,
    createdByDevice: String,
    setGlobalLoading: (Boolean) -> Unit,
    setError: (String?) -> Unit
) {
    val scope = rememberCoroutineScope()
    val announcements = remember { mutableStateListOf<AnnouncementRow>() }

    var newTitle by rememberSaveable { mutableStateOf("") }
    var newBody by rememberSaveable { mutableStateOf("") }
    var newActive by rememberSaveable { mutableStateOf(true) }
    var newPriority by rememberSaveable { mutableIntStateOf(0) }

    suspend fun refresh() {
        setGlobalLoading(true)
        setError(null)

        runCatching {
            val snap = db.collection("announcements")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(50)
                .get()
                .await()

            announcements.clear()
            snap.documents.forEach { d ->
                announcements.add(
                    AnnouncementRow(
                        id = d.id,
                        title = (d.getString("title") ?: "").trim(),
                        body = (d.getString("body") ?: "").trim(),
                        active = d.getBoolean("active") ?: true,
                        priority = (d.getLong("priority") ?: 0L).toInt(),
                        createdAt = d.getTimestamp("createdAt")
                    )
                )
            }

            setGlobalLoading(false)
        }.onFailure { e ->
            setGlobalLoading(false)
            setError(e.message ?: "Failed to load announcements")
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ElevatedCard {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Announcements", style = MaterialTheme.typography.titleMedium)

                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Title") }
                )

                OutlinedTextField(
                    value = newBody,
                    onValueChange = { newBody = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    label = { Text("Body") }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Active")
                        Switch(checked = newActive, onCheckedChange = { newActive = it })
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Priority", style = MaterialTheme.typography.labelLarge)
                        IconButton(onClick = { newPriority = (newPriority - 1).coerceIn(-10, 10) }) {
                            Icon(Icons.Filled.Remove, contentDescription = "Dec")
                        }
                        Text(newPriority.toString(), fontFamily = FontFamily.Monospace)
                        IconButton(onClick = { newPriority = (newPriority + 1).coerceIn(-10, 10) }) {
                            Icon(Icons.Filled.Add, contentDescription = "Inc")
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                setGlobalLoading(true)
                                setError(null)

                                runCatching {
                                    val data = hashMapOf(
                                        "title" to newTitle.trim(),
                                        "body" to newBody.trim(),
                                        "active" to newActive,
                                        "priority" to newPriority,
                                        "createdAt" to FieldValue.serverTimestamp(),
                                        "updatedAt" to FieldValue.serverTimestamp(),
                                        "createdByDevice" to createdByDevice,
                                        "createdByAppId" to BuildConfig.APPLICATION_ID
                                    )
                                    db.collection("announcements").add(data).await()

                                    newTitle = ""
                                    newBody = ""
                                    newActive = true
                                    newPriority = 0
                                    refresh()
                                }.onFailure { e ->
                                    setGlobalLoading(false)
                                    setError(e.message ?: "Failed to publish")
                                }
                            }
                        },
                        enabled = newTitle.trim().isNotBlank() && newBody.trim().isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) { Text("Publish") }

                    OutlinedButton(
                        onClick = { scope.launch { refresh() } },
                        modifier = Modifier.weight(1f)
                    ) { Text("Refresh") }
                }
            }
        }

        if (announcements.isEmpty()) {
            Text("No announcements.", style = MaterialTheme.typography.bodySmall)
        } else {
            announcements.forEach { a ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(a.title.ifBlank { "(no title)" }, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "priority=${a.priority}  active=${a.active}  createdAt=${formatTimestamp(a.createdAt)}",
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            IconButton(
                                onClick = {
                                    scope.launch {
                                        setGlobalLoading(true)
                                        setError(null)
                                        runCatching {
                                            db.collection("announcements").document(a.id).delete().await()
                                            refresh()
                                        }.onFailure { e ->
                                            setGlobalLoading(false)
                                            setError(e.message ?: "Failed to delete announcement")
                                        }
                                    }
                                }
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete")
                            }
                        }

                        if (a.body.isNotBlank()) {
                            Text(a.body, style = MaterialTheme.typography.bodyMedium)
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        setGlobalLoading(true)
                                        setError(null)
                                        runCatching {
                                            db.collection("announcements").document(a.id)
                                                .set(
                                                    mapOf(
                                                        "active" to !a.active,
                                                        "updatedAt" to FieldValue.serverTimestamp()
                                                    ),
                                                    SetOptions.merge()
                                                )
                                                .await()
                                            refresh()
                                        }.onFailure { e ->
                                            setGlobalLoading(false)
                                            setError(e.message ?: "Failed to toggle active")
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text(if (a.active) "Disable" else "Enable") }

                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        setGlobalLoading(true)
                                        setError(null)
                                        runCatching {
                                            db.collection("announcements").document(a.id)
                                                .set(
                                                    mapOf(
                                                        "priority" to (a.priority - 1),
                                                        "updatedAt" to FieldValue.serverTimestamp()
                                                    ),
                                                    SetOptions.merge()
                                                )
                                                .await()
                                            refresh()
                                        }.onFailure { e ->
                                            setGlobalLoading(false)
                                            setError(e.message ?: "Failed to change priority")
                                        }
                                    }
                                }
                            ) { Text("Priority -") }

                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        setGlobalLoading(true)
                                        setError(null)
                                        runCatching {
                                            db.collection("announcements").document(a.id)
                                                .set(
                                                    mapOf(
                                                        "priority" to (a.priority + 1),
                                                        "updatedAt" to FieldValue.serverTimestamp()
                                                    ),
                                                    SetOptions.merge()
                                                )
                                                .await()
                                            refresh()
                                        }.onFailure { e ->
                                            setGlobalLoading(false)
                                            setError(e.message ?: "Failed to change priority")
                                        }
                                    }
                                }
                            ) { Text("Priority +") }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
    }
}

/* =========================================================
   RELEASES TAB
   Pick the public APK on-device. versionCode + versionName are
   read automatically from the APK file. The APK is uploaded to
   GitHub Releases (free, no storage subscription needed) via the
   GitHub API. The resulting download URL + metadata is then
   written to Firestore releases/latest so public clients pick it
   up on next launch.

   Public clients only prompt if releases/latest.versionCode
   is strictly GREATER than BuildConfig.VERSION_CODE, so users
   already on the pushed version or newer see nothing.

   Setup (once):
     Add to keystore.properties (already gitignored):
       githubPat=ghp_xxxxxxxxxxxxxxxxxxxx   <- PAT with Contents write
       githubOwner=your-username
       githubRepo=your-repo-name
   ========================================================= */

// ---- GitHub API helpers (no extra dependencies - uses HttpURLConnection) ----

private data class GithubReleaseResult(
    val releaseId: Long,
    val uploadUrl: String,   // template like https://uploads.github.com/...{?name,label}
    val htmlUrl: String
)

private suspend fun githubCreateRelease(
    owner: String, repo: String, pat: String,
    tagName: String, releaseName: String, body: String
): GithubReleaseResult = withContext(Dispatchers.IO) {
    val url = URL("https://api.github.com/repos/$owner/$repo/releases")
    val payload = JSONObject().apply {
        put("tag_name", tagName)
        put("name", releaseName)
        put("body", body)
        put("draft", false)
        put("prerelease", false)
    }.toString()

    val conn = url.openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.setRequestProperty("Authorization", "Bearer $pat")
    conn.setRequestProperty("Accept", "application/vnd.github+json")
    conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
    conn.setRequestProperty("Content-Type", "application/json")
    conn.doOutput = true
    conn.connectTimeout = 15_000
    conn.readTimeout = 30_000

    conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

    val code = conn.responseCode
    val responseBody = (if (code in 200..299) conn.inputStream else conn.errorStream)
        ?.bufferedReader()?.readText().orEmpty()

    if (code !in 200..299) {
        throw Exception("GitHub create release failed ($code): $responseBody")
    }

    val json = JSONObject(responseBody)
    GithubReleaseResult(
        releaseId = json.getLong("id"),
        uploadUrl = json.getString("upload_url"),
        htmlUrl   = json.getString("html_url")
    )
}

private suspend fun githubUploadAsset(
    owner: String, repo: String, pat: String,
    releaseId: Long, fileName: String, apkFile: File,
    onProgress: (Float) -> Unit
): String = withContext(Dispatchers.IO) {
    val uploadUrl = URL(
        "https://uploads.github.com/repos/$owner/$repo/releases/$releaseId/assets?name=${
            java.net.URLEncoder.encode(fileName, "UTF-8")
        }"
    )

    val conn = uploadUrl.openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.setRequestProperty("Authorization", "Bearer $pat")
    conn.setRequestProperty("Accept", "application/vnd.github+json")
    conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
    conn.setRequestProperty("Content-Type", "application/vnd.android.package-archive")
    conn.setRequestProperty("Content-Length", apkFile.length().toString())
    conn.doOutput = true
    conn.connectTimeout = 15_000
    conn.readTimeout = 300_000  // large file upload - 5 min

    val totalBytes = apkFile.length().toFloat()
    var writtenBytes = 0L
    val buf = ByteArray(64 * 1024)

    conn.outputStream.use { out ->
        apkFile.inputStream().use { inp ->
            var n: Int
            while (inp.read(buf).also { n = it } != -1) {
                out.write(buf, 0, n)
                writtenBytes += n
                if (totalBytes > 0f) onProgress(writtenBytes / totalBytes)
            }
            out.flush()
        }
    }

    val code = conn.responseCode
    val responseBody = (if (code in 200..299) conn.inputStream else conn.errorStream)
        ?.bufferedReader()?.readText().orEmpty()

    if (code !in 200..299) {
        throw Exception("GitHub upload asset failed ($code): $responseBody")
    }

    JSONObject(responseBody).getString("browser_download_url")
}

// ---- Composable ----

@Suppress("DEPRECATION")
private fun parseApkInfo(ctx: Context, apkPath: String): Pair<Long, String>? {
    return try {
        val pm = ctx.packageManager
        val pi = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(
                apkPath,
                android.content.pm.PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            pm.getPackageArchiveInfo(apkPath, 0)
        } ?: return null
        val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
            pi.longVersionCode else pi.versionCode.toLong()
        code to (pi.versionName.orEmpty())
    } catch (_: Throwable) { null }
}

private suspend fun copyUriToCache(ctx: Context, uri: android.net.Uri): File? {
    return try {
        val tmp = File(ctx.cacheDir, "upload_tmp.apk")
        ctx.contentResolver.openInputStream(uri)?.use { inp ->
            tmp.outputStream().use { out -> inp.copyTo(out) }
        }
        tmp
    } catch (_: Throwable) { null }
}

@Composable
private fun ReleasesTab(
    db: FirebaseFirestore,
    setGlobalLoading: (Boolean) -> Unit,
    setError: (String?) -> Unit
) {
    val ctx   = LocalContext.current
    val scope = rememberCoroutineScope()

    val githubPat   = BuildConfig.GITHUB_PAT
    val githubOwner = BuildConfig.GITHUB_OWNER
    val githubRepo  = BuildConfig.GITHUB_REPO
    val credsMissing = githubPat.isBlank() || githubOwner.isBlank() || githubRepo.isBlank()

    // ---- current live release ----
    var liveVersionCode by rememberSaveable { mutableLongStateOf(0L) }
    var liveVersionName by rememberSaveable { mutableStateOf("") }
    var liveDownloadUrl by rememberSaveable { mutableStateOf("") }
    var liveRequiredMin by rememberSaveable { mutableLongStateOf(0L) }
    var liveNotes       by rememberSaveable { mutableStateOf("") }
    var livePublishedAt by remember { mutableStateOf<com.google.firebase.Timestamp?>(null) }
    var loaded          by remember { mutableStateOf(false) }

    // ---- picked APK ----
    var pickedFileName  by rememberSaveable { mutableStateOf("") }
    var parsedCode      by rememberSaveable { mutableLongStateOf(0L) }
    var parsedName      by rememberSaveable { mutableStateOf("") }
    var parseError      by rememberSaveable { mutableStateOf("") }
    var cachedApkPath   by remember { mutableStateOf("") }

    // ---- optional fields ----
    var editRequiredMin by rememberSaveable { mutableStateOf("") }
    var editNotes       by rememberSaveable { mutableStateOf("") }

    // ---- upload state ----
    var uploadPhase     by remember { mutableStateOf("") }
    var uploadProgress  by remember { mutableStateOf(0f) }
    var uploading       by remember { mutableStateOf(false) }
    var uploadDone      by remember { mutableStateOf(false) }

    suspend fun loadCurrent() {
        setGlobalLoading(true)
        runCatching {
            val snap = db.collection("releases").document("latest").get().await()
            if (snap.exists()) {
                liveVersionCode = snap.getLong("versionCode") ?: 0L
                liveVersionName = snap.getString("versionName").orEmpty()
                liveDownloadUrl = snap.getString("downloadUrl").orEmpty()
                liveRequiredMin = snap.getLong("requiredMinCode") ?: 0L
                liveNotes       = snap.getString("notes").orEmpty()
                livePublishedAt = snap.getTimestamp("publishedAt")
            }
            loaded = true
        }.onFailure { e -> setError(e.message ?: "Failed to load release") }
        setGlobalLoading(false)
    }

    LaunchedEffect(Unit) { loadCurrent() }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        parseError = ""; parsedCode = 0L; parsedName = ""
        pickedFileName = ""; cachedApkPath = ""; uploadDone = false

        scope.launch {
            val tmp = copyUriToCache(ctx, uri)
            if (tmp == null) { parseError = "Could not read the selected file."; return@launch }
            cachedApkPath = tmp.absolutePath

            pickedFileName = runCatching {
                ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    c.moveToFirst()
                    c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME))
                }
            }.getOrNull() ?: "release.apk"

            val info = parseApkInfo(ctx, tmp.absolutePath)
            if (info == null) {
                parseError = "Could not read version info.\nMake sure this is a valid APK."
                return@launch
            }
            parsedCode = info.first
            parsedName = info.second
        }
    }

    fun startUpload() {
        val apkPath = cachedApkPath
        if (apkPath.isBlank() || parsedCode == 0L) return

        scope.launch {
            uploading = true; uploadDone = false; uploadProgress = 0f; setError(null)

            runCatching {
                val apkFile = File(apkPath)
                val tagName  = "v$parsedCode"
                val relName  = "v${parsedName.ifBlank { parsedCode.toString() }}"
                val fileName = "chatbox-vrc-a-${relName}.apk"
                    .replace(Regex("[^a-zA-Z0-9._-]"), "_")

                // Step 1: create GitHub release
                uploadPhase = "Creating GitHub release..."
                val release = githubCreateRelease(
                    owner       = githubOwner,
                    repo        = githubRepo,
                    pat         = githubPat,
                    tagName     = tagName,
                    releaseName = relName,
                    body        = editNotes.trim().ifBlank { "Release $relName" }
                )

                // Step 2: upload APK asset with progress
                uploadPhase = "Uploading APK..."
                val downloadUrl = githubUploadAsset(
                    owner      = githubOwner,
                    repo       = githubRepo,
                    pat        = githubPat,
                    releaseId  = release.releaseId,
                    fileName   = fileName,
                    apkFile    = apkFile,
                    onProgress = { uploadProgress = it }
                )

                // Step 3: write Firestore releases/latest
                uploadPhase = "Publishing release info..."
                val data = hashMapOf<String, Any>(
                    "versionCode"       to parsedCode,
                    "versionName"       to parsedName,
                    "downloadUrl"       to downloadUrl,
                    "requiredMinCode"   to (editRequiredMin.toLongOrNull() ?: 0L),
                    "notes"             to editNotes.trim(),
                    "publishedAt"       to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                    "publishedByDevice" to BuildConfig.APPLICATION_ID
                )
                db.collection("releases").document("latest")
                    .set(data, com.google.firebase.firestore.SetOptions.merge())
                    .await()

                loadCurrent()
                uploadDone = true
                uploadPhase = ""

                // clean up temp file
                runCatching { apkFile.delete() }
                cachedApkPath = ""; pickedFileName = ""; parsedCode = 0L; parsedName = ""

            }.onFailure { e ->
                setError(e.message ?: "Upload failed")
                uploadPhase = ""
            }

            uploading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        // ---- Credentials warning ----
        if (credsMissing) {
            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("GitHub credentials not configured", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Add to keystore.properties:\n" +
                        "  githubPat=ghp_xxxxxxxxxxxxxxxxxxxx\n" +
                        "  githubOwner=your-username\n" +
                        "  githubRepo=your-repo-name\n\n" +
                        "The PAT needs Contents: write permission on the repo.",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // ---- Current live release ----
        ElevatedCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Current Live Release", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { scope.launch { loadCurrent() } }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                    }
                }
                if (!loaded) {
                    CircularProgressIndicator()
                } else if (liveVersionCode == 0L && liveDownloadUrl.isBlank()) {
                    Text("No release published yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("versionCode=$liveVersionCode  name=${liveVersionName.ifBlank { "(blank)" }}",
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text("requiredMinCode=$liveRequiredMin",
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text("publishedAt=${formatTimestamp(livePublishedAt)}",
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (liveDownloadUrl.isNotBlank()) {
                        Text("url=${liveDownloadUrl.take(72)}${if (liveDownloadUrl.length > 72) "..." else ""}",
                            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (liveNotes.isNotBlank()) {
                        Text(liveNotes.lines().firstOrNull().orEmpty().take(100),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // ---- Publish new release ----
        ElevatedCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Publish New Release", style = MaterialTheme.typography.titleMedium)
                Text("Pick the public APK. Version info is read automatically, then the APK is uploaded to GitHub Releases.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                OutlinedButton(
                    onClick = { filePicker.launch("*/*") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uploading && !credsMissing
                ) {
                    Icon(Icons.Filled.AttachFile, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (pickedFileName.isBlank()) "Pick APK file" else pickedFileName)
                }

                if (parseError.isNotBlank()) {
                    Card(colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )) {
                        Text(parseError, modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (parsedCode > 0L) {
                    Card(colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Read from APK", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("versionCode = $parsedCode",
                                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                            Text("versionName = ${parsedName.ifBlank { "(blank)" }}",
                                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    OutlinedTextField(
                        value = editRequiredMin,
                        onValueChange = { editRequiredMin = it.filter { c -> c.isDigit() } },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Force-update below this versionCode (0 = optional)") },
                        placeholder = { Text("0") }, enabled = !uploading
                    )

                    OutlinedTextField(
                        value = editNotes,
                        onValueChange = { editNotes = it },
                        modifier = Modifier.fillMaxWidth(), minLines = 3,
                        label = { Text("Release notes (optional)") },
                        enabled = !uploading
                    )

                    if (uploading) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LinearProgressIndicator(
                                progress = uploadProgress,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(uploadPhase.ifBlank { "Working..." },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    if (uploadDone) {
                        Card(colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )) {
                            Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary)
                                Text("Published! Users on older versions will be prompted to update.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    Button(
                        onClick = { startUpload() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uploading && parsedCode > 0L && cachedApkPath.isNotBlank() && !credsMissing
                    ) {
                        Icon(Icons.Filled.CloudUpload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Upload & Publish v$parsedName ($parsedCode)")
                    }

                    Text(
                        "Users on versionCode >= $parsedCode will not be prompted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
    }
}

/* =========================================================
   CONFIG TAB (unchanged)
   ========================================================= */

private const val DEFAULT_TOS_TEXT: String =
    "Terms of Service (Summary)\n" +
        "\n" +
        "By using this app, you agree to the following:\n" +
        "1) You are responsible for how you use the app.\n" +
        "2) Do not use the app to harass, threaten, or abuse others.\n" +
        "3) Do not attempt to bypass restrictions or access areas you are not permitted to.\n" +
        "4) The app may store basic app state for functionality and moderation.\n" +
        "5) Access may be restricted for misuse.\n" +
        "\n" +
        "This text is a short in-app notice. If you publish a full policy page later, set the URL field and keep this short summary."

@Composable
private fun ConfigTab(
    db: FirebaseFirestore,
    setGlobalLoading: (Boolean) -> Unit,
    setError: (String?) -> Unit
) {
    val scope = rememberCoroutineScope()

    var tosVersion by rememberSaveable { mutableIntStateOf(1) }
    var tosText by rememberSaveable { mutableStateOf("") }
    var tosUrl by rememberSaveable { mutableStateOf("") }
    var ownerUid by rememberSaveable { mutableStateOf("") }
    var loadedAt by remember { mutableStateOf<Timestamp?>(null) }

    suspend fun load() {
        setGlobalLoading(true)
        setError(null)

        runCatching {
            val snap = db.collection("config").document("app").get().await()
            tosVersion = (snap.getLong("tosVersion") ?: 1L).toInt().coerceAtLeast(1)
            tosText = snap.getString("tosText") ?: ""
            tosUrl = snap.getString("tosUrl") ?: ""
            ownerUid = snap.getString("ownerUid") ?: ""
            loadedAt = snap.getTimestamp("updatedAt")
            setGlobalLoading(false)
        }.onFailure { e ->
            setGlobalLoading(false)
            setError(e.message ?: "Failed to load config")
        }
    }

    suspend fun saveConfig(reloadAfter: Boolean = true) {
        setGlobalLoading(true)
        setError(null)

        runCatching {
            val data = hashMapOf(
                "ownerUid" to ownerUid.trim(),
                "tosVersion" to tosVersion,
                "tosText" to tosText,
                "tosUrl" to tosUrl,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            db.collection("config").document("app")
                .set(data, SetOptions.merge())
                .await()
            if (reloadAfter) load() else setGlobalLoading(false)
        }.onFailure { e ->
            setGlobalLoading(false)
            setError(e.message ?: "Failed to save config")
        }
    }


    LaunchedEffect(Unit) { load() }

    ElevatedCard {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("ToS / App Config", style = MaterialTheme.typography.titleMedium)
            Text("updatedAt=${formatTimestamp(loadedAt)} (${relativeTime(loadedAt, System.currentTimeMillis())})", fontFamily = FontFamily.Monospace)

            OutlinedTextField(
                value = ownerUid,
                onValueChange = { ownerUid = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Owner UID") },
                placeholder = { Text("paste your UID here") }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("ToS version", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { tosVersion = (tosVersion - 1).coerceAtLeast(1); scope.launch { saveConfig() } }) { Text("-") }
                    Text(tosVersion.toString(), fontFamily = FontFamily.Monospace)
                    OutlinedButton(onClick = { tosVersion += 1; scope.launch { saveConfig() } }) { Text("+") }
                }
            }

            OutlinedTextField(
                value = tosUrl,
                onValueChange = { tosUrl = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("ToS URL (optional)") },
                placeholder = { Text("https://...") }
            )

            OutlinedTextField(
                value = tosText,
                onValueChange = { tosText = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                label = { Text("ToS text (shown in-app)") }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { if (tosText.trim().isEmpty()) tosText = DEFAULT_TOS_TEXT },
                    modifier = Modifier.weight(1f)
                ) { Text("Use template") }

                OutlinedButton(
                    onClick = { scope.launch { load() } },
                    modifier = Modifier.weight(1f)
                ) { Text("Reload") }
            }

            Button(
                onClick = { scope.launch { saveConfig() } },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save") }

            Text(
                "Increase ToS version to re-prompt users.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/* =========================================================
   DASHBOARD TAB
   ========================================================= */

@Composable
private fun DashboardTab(db: FirebaseFirestore, setError: (String?) -> Unit) {
    val scope = rememberCoroutineScope()

    var totalUsers  by remember { mutableIntStateOf(0) }
    var bannedCount by remember { mutableIntStateOf(0) }
    var warnedCount by remember { mutableIntStateOf(0) }
    var onlineCount by remember { mutableIntStateOf(0) } // seen in last 5 min
    var loading by remember { mutableStateOf(true) }
    var evasionCount by remember { mutableIntStateOf(0) }

    val nowMs = System.currentTimeMillis()
    val fiveMinAgo = com.google.firebase.Timestamp(
        (nowMs - 5 * 60 * 1000L) / 1000, 0)

    DisposableEffect(Unit) {
        setError(null)
        loading = true
        val reg = db.collection("users")
            .addSnapshotListener { snap, e ->
                if (e != null) { setError(e.message); loading = false; return@addSnapshotListener }
                if (snap == null) { loading = false; return@addSnapshotListener }
                totalUsers  = snap.size()
                bannedCount = snap.documents.count { it.getBoolean("banned") == true }
                warnedCount = snap.documents.count { it.getBoolean("warned") == true }
                onlineCount = snap.documents.count {
                    it.getBoolean("isOnlineInApp") == true
                }
                loading = false
            }
        onDispose { reg.remove() }
    }

    DisposableEffect(Unit) {
        val reg = db.collection("moderationEvents")
            .whereEqualTo("action", "ban_evasion_detected")
            .addSnapshotListener { snap, _ ->
                evasionCount = snap?.size() ?: 0
            }
        onDispose { reg.remove() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            if (loading) {
                Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Loading...")
                }
            }
        }

        item {
            ElevatedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Overview", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatChip(Modifier.weight(1f), "Users", totalUsers.toString())
                        StatChip(Modifier.weight(1f), "Online", onlineCount.toString(),
                            highlight = onlineCount > 0)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatChip(Modifier.weight(1f), "Warned", warnedCount.toString(),
                            warn = warnedCount > 0)
                        StatChip(Modifier.weight(1f), "Banned", bannedCount.toString(),
                            error = bannedCount > 0)
                    }
                    if (evasionCount > 0) {
                        Card(colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Row(Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("(!) ", style = MaterialTheme.typography.bodyMedium)
                                Column {
                                    Text("Ban evasion attempts detected: $evasionCount",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer)
                                    Text("Check the Log tab for details.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun StatChip(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    highlight: Boolean = false,
    warn: Boolean = false,
    error: Boolean = false
) {
    val containerColor = when {
        error     -> MaterialTheme.colorScheme.errorContainer
        warn      -> MaterialTheme.colorScheme.tertiaryContainer
        highlight -> MaterialTheme.colorScheme.primaryContainer
        else      -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = containerColor)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/* =========================================================
   MOD LOG TAB
   ========================================================= */

@Composable
private fun ModLogTab(db: FirebaseFirestore, setError: (String?) -> Unit) {
    data class LogRow(
        val id: String,
        val action: String,
        val displayName: String,
        val vrchatId: String,
        val deviceHash: String,
        val reason: String,
        val method: String,
        val createdAt: Timestamp?
    )

    val rows = remember { mutableStateListOf<LogRow>() }
    var loading by remember { mutableStateOf(true) }
    val nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    DisposableEffect(Unit) {
        setError(null); loading = true
        val reg = db.collection("moderationEvents")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(200)
            .addSnapshotListener { snap, e ->
                if (e != null) { setError(e.message); loading = false; return@addSnapshotListener }
                if (snap == null) { loading = false; return@addSnapshotListener }
                val next = snap.documents.map { d ->
                    fun s(k: String) = (d.getString(k) ?: "").trim()
                    LogRow(
                        id = d.id,
                        action = s("action"),
                        displayName = s("newDisplayName").ifBlank { s("targetUid") },
                        vrchatId = s("newVrchatId").ifBlank { s("targetAuthUid") },
                        deviceHash = s("newDeviceHash").ifBlank { s("targetDeviceHash") },
                        reason = s("reason"),
                        method = s("method"),
                        createdAt = d.getTimestamp("createdAt")
                    )
                }
                rows.clear(); rows.addAll(next); loading = false
            }
        onDispose { reg.remove() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Moderation Log", style = MaterialTheme.typography.titleMedium)
                if (loading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }

        if (rows.isEmpty() && !loading) {
            item {
                Text("No moderation events yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        items(rows, key = { it.id }) { row ->
            val isEvasion = row.action == "ban_evasion_detected"
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isEvasion)
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            row.action.replace("_", " ").replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isEvasion) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurface
                        )
                        Text(relativeTime(row.createdAt, nowMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (row.displayName.isNotBlank())
                        Text(row.displayName, style = MaterialTheme.typography.bodySmall)
                    if (row.vrchatId.isNotBlank())
                        Text(row.vrchatId, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (row.method.isNotBlank())
                        Text("Method: ${row.method}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (row.reason.isNotBlank())
                        Text("Reason: ${row.reason}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

/* =========================================================
   COMMON UI
   ========================================================= */

@Composable
private fun ErrorCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Error", style = MaterialTheme.typography.titleSmall)
            Text(message, fontFamily = FontFamily.Monospace)
        }
    }
}

/* =========================================================
   SMALL HELPERS
   ========================================================= */

private fun shortId(s: String, head: Int = 10, tail: Int = 6): String {
    val t = s.trim()
    if (t.isBlank()) return "(blank)"
    if (t.length <= head + tail + 1) return t
    return t.take(head) + "..." + t.takeLast(tail)
}

private fun relativeTime(ts: Timestamp?, nowMs: Long): String {
    if (ts == null) return "?"
    val then = ts.toDate().time
    val diff = nowMs - then
    if (diff < 0) return "0s ago"
    val s = diff / 1000L
    if (s < 60L) return "${s}s ago"
    val m = s / 60L
    if (m < 60L) return "${m}m ago"
    val h = m / 60L
    if (h < 24L) return "${h}h ago"
    val d = h / 24L
    return "${d}d ago"
}

/* =========================================================
   PREFS HELPERS
   ========================================================= */

private fun readDeviceHash(ctx: Context): String {
    val prefs = ctx.getSharedPreferences("vrca_remote", Context.MODE_PRIVATE)
    return prefs.getString("device_id_hash", "")?.trim().orEmpty()
}

private fun readCachedUid(ctx: Context): String {
    val prefs = ctx.getSharedPreferences("vrca_remote", Context.MODE_PRIVATE)
    return prefs.getString("auth_uid", "")?.trim().orEmpty()
}

private fun writeCachedUid(ctx: Context, uid: String) {
    ctx.getSharedPreferences("vrca_remote", Context.MODE_PRIVATE)
        .edit()
        .putString("auth_uid", uid.trim())
        .apply()
}

private fun formatTimestamp(ts: com.google.firebase.Timestamp?): String {
    if (ts == null) return "?"
    val ms = ts.seconds * 1000L + (ts.nanoseconds / 1_000_000L)
    if (ms <= 0L) return "?"
    val now = System.currentTimeMillis()
    val diff = now - ms

    // Future timestamps or clock skew: just show a compact date.
    if (diff < 0L) {
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(ms))
    }

    val sec = diff / 1000L
    val min = sec / 60L
    val hr = min / 60L
    val day = hr / 24L

    val rel = when {
        sec < 60L -> "${sec}s ago"
        min < 60L -> "${min}m ago"
        hr < 48L -> "${hr}h ago"
        else -> "${day}d ago"
    }

    val abs = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(ms))
    return "$abs ($rel)"
}
