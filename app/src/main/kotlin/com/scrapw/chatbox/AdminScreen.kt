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
    val tabs = remember { listOf("Users", "Mod", "Announce", "Releases", "Config") }
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
                        "Owner build - ${BuildConfig.APPLICATION_ID}",
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
            ScrollableTabRow(
                selectedTabIndex = tabIndex,
                edgePadding = 0.dp
            ) {
                tabs.forEachIndexed { i, label ->
                    Tab(
                        selected = tabIndex == i,
                        onClick = { tabIndex = i },
                        text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
                    0 -> UsersTab(
                        db = db,
                        clipboardCopy = { },
                        setGlobalLoading = { globalLoading = it },
                        setError = ::setErr,
                        onSendToModeration = { target ->
                            moderationTarget = target
                            tabIndex = 1
                        }
                    )

                    1 -> ModerationTab(
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

                    2 -> AnnouncementsTab(
                        db = db,
                        createdByDevice = deviceHash,
                        setGlobalLoading = { globalLoading = it },
                        setError = ::setErr
                    )

                    3 -> ReleasesTab(
                        db = db,
                        setGlobalLoading = { globalLoading = it },
                        setError = ::setErr
                    )

                    else -> ConfigTab(
                        db = db,
                        setGlobalLoading = { globalLoading = it },
                        setError = ::setErr
                    )
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
    val updatedAt: Timestamp?
)

private data class UserDetail(
    val afkEnabled: Boolean,
    val afkMessage: String,
    val cycleEnabled: Boolean,
    val cycleIntervalSeconds: Long,
    val cycleLinesText: String,
    val spotifyEnabled: Boolean,
    val spotifyDemoEnabled: Boolean,
    val spotifyPreset: Long,
    val nowPlayingDetected: Boolean,
    val nowPlayingIsPlaying: Boolean,
    val nowPlayingTitle: String,
    val nowPlayingArtist: String,
    val combinedPreviewText: String,
    val warnReason: String,
    val banReason: String,
    val afkPresets: List<String>,
    val cyclePresets: List<String>,
    val versionName: String,
    val versionCode: Long,
    val appId: String
)

private data class ModerationTarget(
    val docId: String,
    val authUid: String,
    val deviceHash: String,
    val displayName: String
)

@Composable
private fun UsersTab(
    db: FirebaseFirestore,
    clipboardCopy: (String) -> Unit,
    setGlobalLoading: (Boolean) -> Unit,
    setError: (String?) -> Unit,
    onSendToModeration: (ModerationTarget) -> Unit
) {
    val users = remember { mutableStateListOf<UserRow>() }

    var search by rememberSaveable { mutableStateOf("") }
    var filterWarned by rememberSaveable { mutableStateOf(false) }
    var filterBanned by rememberSaveable { mutableStateOf(false) }

    // Selected user takes over whole Users tab until unselected
    var selectedDocId by rememberSaveable { mutableStateOf<String?>(null) }

    // "More" just increases live query limit (still realtime)
    var liveLimit by rememberSaveable { mutableIntStateOf(75) }

    // Selected details live doc listener
    var selectedDetail by remember { mutableStateOf<UserDetail?>(null) }
    var selectedDetailLoading by remember { mutableStateOf(false) }

    // ticker for relative times
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000L)
        }
    }

    fun rowMatches(u: UserRow, q: String): Boolean {
        if (q.isBlank()) return true
        val t = q.trim()
        return u.docId.contains(t, true) ||
            u.authUid.contains(t, true) ||
            u.deviceHash.contains(t, true) ||
            u.displayName.contains(t, true)
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

    // Live users list
    DisposableEffect(liveLimit) {
        setError(null)
        setGlobalLoading(true)

        val reg: ListenerRegistration = db.collection("users")
            .orderBy("lastSeenAt", Query.Direction.DESCENDING)
            .limit(liveLimit.toLong())
            .addSnapshotListener { snap, e ->
                if (e != null) {
                    setGlobalLoading(false)
                    setError(e.message ?: "Users live update failed")
                    return@addSnapshotListener
                }
                if (snap == null) {
                    setGlobalLoading(false)
                    return@addSnapshotListener
                }

                val next = snap.documents.map { d ->
                    val docId = d.id
                    val authUid = (d.getString("authUid") ?: d.getString("uid") ?: "").trim()
                    UserRow(
                        docId = docId,
                        authUid = authUid,
                        displayName = (d.getString("displayName") ?: "").trim(),
                        deviceHash = (d.getString("deviceHash") ?: "").trim(),
                        warned = d.getBoolean("warned") ?: false,
                        banned = d.getBoolean("banned") ?: false,
                        lastSeenAt = d.getTimestamp("lastSeenAt"),
                        updatedAt = d.getTimestamp("updatedAt")
                    )
                }

                users.clear()
                users.addAll(next)
                setGlobalLoading(false)
            }

        onDispose { reg.remove() }
    }

    // Live selected doc details
    DisposableEffect(selectedDocId) {
        val docId = selectedDocId
        if (docId.isNullOrBlank()) {
            selectedDetail = null
            selectedDetailLoading = false
            return@DisposableEffect onDispose { }
        }

        selectedDetailLoading = true
        setError(null)

        val reg = db.collection("users")
            .document(docId)
            .addSnapshotListener { snap, e ->
                if (e != null) {
                    selectedDetailLoading = false
                    setError(e.message ?: "User detail live update failed")
                    return@addSnapshotListener
                }
                if (snap == null || !snap.exists()) {
                    selectedDetailLoading = false
                    selectedDetail = null
                    return@addSnapshotListener
                }

                fun s(key: String) = (snap.getString(key) ?: "").trim()
                fun b(key: String) = snap.getBoolean(key) ?: false
                fun l(key: String) = snap.getLong(key) ?: 0L

                val afkPresets = listOf(s("afkPreset1"), s("afkPreset2"), s("afkPreset3"))
                val cyclePresets = listOf(
                    s("cyclePreset1"),
                    s("cyclePreset2"),
                    s("cyclePreset3"),
                    s("cyclePreset4"),
                    s("cyclePreset5")
                )

                selectedDetail = UserDetail(
                    afkEnabled = b("afkEnabled"),
                    afkMessage = s("afkMessage"),
                    cycleEnabled = b("cycleEnabled"),
                    cycleIntervalSeconds = l("cycleIntervalSeconds"),
                    cycleLinesText = s("cycleLinesText"),
                    spotifyEnabled = b("spotifyEnabled"),
                    spotifyDemoEnabled = b("spotifyDemoEnabled"),
                    spotifyPreset = l("spotifyPreset"),
                    nowPlayingDetected = b("nowPlayingDetected"),
                    nowPlayingIsPlaying = b("nowPlayingIsPlaying"),
                    nowPlayingTitle = s("nowPlayingTitle"),
                    nowPlayingArtist = s("nowPlayingArtist"),
                    combinedPreviewText = s("combinedPreviewText"),
                    warnReason = s("warnReason"),
                    banReason = s("banReason"),
                    afkPresets = afkPresets,
                    cyclePresets = cyclePresets,
                    versionName = s("versionName"),
                    versionCode = l("versionCode"),
                    appId = s("appId")
                )
                selectedDetailLoading = false
            }

        onDispose { reg.remove() }
    }

    val selectedRow = remember(selectedDocId, users.size) {
        selectedDocId?.let { id -> users.firstOrNull { it.docId == id } }
    }

    // Detail view
    if (selectedRow != null) {
        val d = selectedDetail

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ElevatedCard {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(onClick = { selectedDocId = null }) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                            }
                            Column {
                                Text("User", style = MaterialTheme.typography.titleMedium)
                                if (selectedRow.displayName.isNotBlank()) {
                                    Text(
                                        selectedRow.displayName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    "docId=${shortId(selectedRow.docId)}",
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        IconButton(onClick = { /* live already */ }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Live")
                        }
                    }

                    Text(
                        "authUid=${shortId(selectedRow.authUid.ifBlank { "(blank)" })}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "device=${shortId(selectedRow.deviceHash.ifBlank { "(blank)" })}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "warned=${selectedRow.warned}  banned=${selectedRow.banned}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )

                    val lastSeenRel = relativeTime(selectedRow.lastSeenAt, nowMs)
                    val updatedRel = relativeTime(selectedRow.updatedAt, nowMs)
                    Text(
                        "lastSeen=$lastSeenRel   updated=$updatedRel",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Divider()

                    
                    Button(
                        onClick = {
                            onSendToModeration(
                                ModerationTarget(
                                    docId = selectedRow.docId,
                                    authUid = selectedRow.authUid,
                                    deviceHash = selectedRow.deviceHash,
                                    displayName = selectedRow.displayName
                                )
                            )
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
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator()
                        Text("Loading details...")
                    }
                }
            } else if (d != null) {
                DetailBlock(d)
            } else {
                ElevatedCard {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Details", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "No detail loaded (doc missing or not yet written).",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
        }
        return
    }

    // Normal Users list view - LazyColumn so all users scroll properly
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Controls header
        item {
            ElevatedCard {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Users", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${filteredUsers.size} / ${users.size}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Search") },
                        placeholder = { Text("name / docId / uid / device") }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Warned", style = MaterialTheme.typography.bodySmall)
                            Switch(checked = filterWarned, onCheckedChange = { filterWarned = it })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Banned", style = MaterialTheme.typography.bodySmall)
                            Switch(checked = filterBanned, onCheckedChange = { filterBanned = it })
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { liveLimit = liveLimit.coerceAtLeast(1); setError(null) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Live") }
                        Button(
                            onClick = { liveLimit = (liveLimit + 500).coerceAtMost(10000) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Load more (+500)") }
                    }

                    Text(
                        "Showing ${filteredUsers.size} of $liveLimit loaded",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Empty state
        if (filteredUsers.isEmpty()) {
            item {
                Text(
                    "No users loaded / matching filters.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // User rows (compact)
        items(filteredUsers, key = { it.docId }) { u ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        u.banned  -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                        u.warned  -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
                        else      -> MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectedDocId = u.docId }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        // Name / fallback to short docId
                        Text(
                            u.displayName.ifBlank { shortId(u.docId) },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            shortId(u.docId),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                        Text(
                            relativeTime(u.lastSeenAt, nowMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // Status badges
                    Column(
                        horizontalAlignment = androidx.compose.ui.Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (u.banned) {
                            androidx.compose.material3.Badge(
                                containerColor = MaterialTheme.colorScheme.error
                            ) { Text("BANNED", style = MaterialTheme.typography.labelSmall) }
                        }
                        if (u.warned) {
                            androidx.compose.material3.Badge(
                                containerColor = MaterialTheme.colorScheme.tertiary
                            ) { Text("WARNED", style = MaterialTheme.typography.labelSmall) }
                        }
                        Icon(
                            Icons.Filled.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun DetailBlock(d: UserDetail) {
    @Composable
    fun Mono(label: String, value: String) {
        Text(
            "$label=$value",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
    }

    ElevatedCard {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Live State", style = MaterialTheme.typography.titleSmall)
            Mono("afkEnabled", d.afkEnabled.toString())
            Mono("afkMessage", d.afkMessage.ifBlank { "(blank)" })
            Mono("cycleEnabled", d.cycleEnabled.toString())
            Mono("cycleIntervalSeconds", d.cycleIntervalSeconds.toString())
            Mono("spotifyEnabled", d.spotifyEnabled.toString())
            Mono("spotifyDemoEnabled", d.spotifyDemoEnabled.toString())
            Mono("spotifyPreset", d.spotifyPreset.toString())
            Mono("nowPlayingDetected", d.nowPlayingDetected.toString())
            Mono("nowPlayingIsPlaying", d.nowPlayingIsPlaying.toString())
            Mono("nowPlayingTitle", d.nowPlayingTitle.ifBlank { "(blank)" })
            Mono("nowPlayingArtist", d.nowPlayingArtist.ifBlank { "(blank)" })

            if (d.versionName.isNotBlank() || d.versionCode > 0L || d.appId.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("App Build", style = MaterialTheme.typography.titleSmall)
                if (d.versionName.isNotBlank()) Mono("versionName", d.versionName)
                if (d.versionCode > 0L) Mono("versionCode", d.versionCode.toString())
                if (d.appId.isNotBlank()) Mono("appId", d.appId)
            }

            Spacer(Modifier.height(4.dp))
            Text("combinedPreviewText", style = MaterialTheme.typography.labelLarge)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text(
                    d.combinedPreviewText.ifBlank { "(blank)" },
                    modifier = Modifier.padding(10.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (d.warnReason.isNotBlank() || d.banReason.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("Moderation Flags", style = MaterialTheme.typography.titleSmall)
                if (d.warnReason.isNotBlank()) Mono("warnReason", d.warnReason)
                if (d.banReason.isNotBlank()) Mono("banReason", d.banReason)
            }

            Spacer(Modifier.height(4.dp))
            Text("AFK Presets", style = MaterialTheme.typography.titleSmall)
            d.afkPresets.forEachIndexed { i, p ->
                Mono("afkPreset${i + 1}", p.ifBlank { "(blank)" })
            }

            Spacer(Modifier.height(4.dp))
            Text("Cycle Presets", style = MaterialTheme.typography.titleSmall)
            d.cyclePresets.forEachIndexed { i, p ->
                val oneLine = p.lines().firstOrNull()?.trim().orEmpty()
                Mono("cyclePreset${i + 1}", oneLine.ifBlank { "(blank)" })
            }

            if (d.cycleLinesText.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("cycleLinesText", style = MaterialTheme.typography.labelLarge)
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text(
                        d.cycleLinesText,
                        modifier = Modifier.padding(10.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
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
