// app/src/main/kotlin/com/scrapw/chatbox/ChatboxScreen.kt
package com.scrapw.chatbox

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.provider.Settings.Secure
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest

private enum class AppPage(val title: String) {
    Home("Home"),
    Automations("Automations"),
    Music("Music"),
    Debug("Debug"),
    Admin("Admin")
}

private enum class ChatboxAutomationsTab(val title: String) {
    AFK("AFK"),
    Cycle("Cycle")
}

private enum class ChatboxInfoTab(val title: String) {
    Overview("Overview"),
    Help("Help")
}

/** Simple persisted UI prefs (no VM changes required). */
private object UiPrefs {
    private const val FILE = "vrca_ui_prefs"
    private const val KEY_SPOTIFY_ENABLED = "spotify_enabled"
    private const val KEY_SPOTIFY_DEMO = "spotify_demo"
    private const val KEY_SPOTIFY_PRESET = "spotify_preset"
    private const val KEY_TUTORIAL_EXPANDED = "tutorial_expanded"

    fun readSpotifyEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).getBoolean(KEY_SPOTIFY_ENABLED, false)

    fun writeSpotifyEnabled(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).edit().putBoolean(KEY_SPOTIFY_ENABLED, v).apply()
    }

    fun readSpotifyDemo(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).getBoolean(KEY_SPOTIFY_DEMO, false)

    fun writeSpotifyDemo(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).edit().putBoolean(KEY_SPOTIFY_DEMO, v).apply()
    }

    fun readSpotifyPreset(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).getInt(KEY_SPOTIFY_PRESET, 1).coerceIn(1, 5)

    fun writeSpotifyPreset(ctx: Context, v: Int) {
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).edit().putInt(KEY_SPOTIFY_PRESET, v.coerceIn(1, 5)).apply()
    }

    fun readTutorialExpanded(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).getBoolean(KEY_TUTORIAL_EXPANDED, true)

    fun writeTutorialExpanded(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).edit().putBoolean(KEY_TUTORIAL_EXPANDED, v).apply()
    }
}

/**
 * âœ… ToS acceptance storage.
 * We store an "accepted_version" integer locally.
 * "version" is fetched remotely.
 */
private object TosPrefs {
    private const val FILE = "vrca_tos"
    private const val KEY_ACCEPTED_VERSION = "accepted_version"
    private const val KEY_ACCEPTED_AT_MS = "accepted_at_ms"
    private const val KEY_ACCEPTED_REMOTE_UPDATED_AT_MS = "accepted_remote_updated_at_ms"

    fun acceptedVersion(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).getInt(KEY_ACCEPTED_VERSION, 0)

    fun acceptedRemoteUpdatedAtMs(ctx: Context): Long =
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).getLong(KEY_ACCEPTED_REMOTE_UPDATED_AT_MS, 0L)

    fun isAccepted(ctx: Context, requiredVersion: Int, remoteUpdatedAtMs: Long?): Boolean {
        val vOk = acceptedVersion(ctx) >= requiredVersion.coerceAtLeast(1)
        val remoteMs = (remoteUpdatedAtMs ?: 0L).coerceAtLeast(0L)
        // If server provides updatedAt, require the user to have accepted after that update.
        val uOk = (remoteMs <= 0L) || (acceptedRemoteUpdatedAtMs(ctx) >= remoteMs)
        return vOk && uOk
    }

    fun accept(ctx: Context, version: Int, remoteUpdatedAtMs: Long?) {
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).edit()
            .putInt(KEY_ACCEPTED_VERSION, version.coerceAtLeast(1))
            .putLong(KEY_ACCEPTED_AT_MS, System.currentTimeMillis())
            .putLong(KEY_ACCEPTED_REMOTE_UPDATED_AT_MS, (remoteUpdatedAtMs ?: 0L).coerceAtLeast(0L))
            .apply()
    }
}

/* =========================
   Remote UI models
   ========================= */

private data class RemoteTosUi(
    val tosVersion: Int = 1,
    val tosText: String = "",
    val tosUrl: String = "",
    val updatedAt: Timestamp? = null
)

private data class AnnouncementUi(
    val id: String,
    val title: String,
    val body: String,
    val active: Boolean,
    val priority: Int,
    val createdAt: Timestamp?
)

private data class ModerationUi(
    val warned: Boolean = false,
    val warnReason: String = "",
    val banned: Boolean = false,
    val banReason: String = "",
    val deviceBanned: Boolean = false,
    val deviceBanReason: String = "",
    val updatedAt: Timestamp? = null
)

/* =========================
   Device hash (survives reinstall)
   ========================= */

private object DeviceId {
    private const val PREFS = "vrca_remote"
    private const val KEY_DEVICE_ID_HASH = "device_id_hash"

    fun read(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS, MODE_PRIVATE)
        return prefs.getString(KEY_DEVICE_ID_HASH, "")?.trim().orEmpty()
    }

    fun ensure(ctx: Context): String {
        val existing = read(ctx)
        if (existing.isNotBlank()) return existing

        // ANDROID_ID survives reinstall (same signing key + same Android user), changes on factory reset.
        val androidId = runCatching { Secure.getString(ctx.contentResolver, Secure.ANDROID_ID) }
            .getOrNull()
            ?.trim()
            .orEmpty()

        val seed = "v1:${androidId.ifBlank { "unknown" }}"
        val hash = sha256Hex(seed)

        ctx.getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_DEVICE_ID_HASH, hash)
            .apply()

        return hash
    }

    private fun sha256Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatboxScreen(
    chatboxViewModel: com.scrapw.chatbox.ui.ChatboxViewModel
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // --- Firebase (ToS + announcements only; moderation comes from ViewModel) ---
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }

    // Ensure device hash exists for this install/user (survives reinstall)
    val deviceHash = remember { DeviceId.ensure(ctx).trim() }

    var authedUid by remember { mutableStateOf(auth.currentUser?.uid?.trim()) }

    // Capture last Firebase issue for Debug ONLY (from ToS/announcements/auth)
    var lastFirebaseIssue by remember { mutableStateOf<String?>(null) }

    fun reportFirebase(tag: String, msg: String, t: Throwable? = null) {
        val full = "[$tag] $msg" + (t?.let { " :: ${it.message ?: it::class.java.simpleName}" } ?: "")
        lastFirebaseIssue = full.take(4000)
        if (t != null) Log.w("VRC-A/Firebase", full, t) else Log.w("VRC-A/Firebase", full)
    }

    fun safeUid(): String = authedUid?.trim().orEmpty()

    // Ensure anon auth ASAP (VM also does this, but we keep it for ToS/announcements listeners)
    LaunchedEffect(Unit) {
        if (auth.currentUser == null) {
            runCatching {
                auth.signInAnonymously().await()
                authedUid = auth.currentUser?.uid?.trim()
            }.onFailure { e ->
                reportFirebase("auth", "Anonymous auth failed", e)
            }
        } else {
            authedUid = auth.currentUser?.uid?.trim()
        }
    }

    // --- Remote config state ---
    var remoteTos by remember { mutableStateOf(RemoteTosUi()) }
    var announcements by remember { mutableStateOf<List<AnnouncementUi>>(emptyList()) }

    // Listen: config/app (ToS)
    DisposableEffect(Unit) {
        var reg: ListenerRegistration? = null
        reg = db.collection("config").document("app")
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    reportFirebase("config/app", "Snapshot listener error", err)
                    return@addSnapshotListener
                }
                if (snap != null && snap.exists()) {
                    val v = (snap.getLong("tosVersion") ?: 1L).toInt().coerceAtLeast(1)
                    remoteTos = RemoteTosUi(
                        tosVersion = v,
                        tosText = snap.getString("tosText") ?: "",
                        tosUrl = snap.getString("tosUrl") ?: "",
                        updatedAt = snap.getTimestamp("updatedAt")
                    )
                }
            }
        onDispose { reg?.remove() }
    }

    // Announcements
    DisposableEffect(Unit) {
        var reg: ListenerRegistration? = null
        reg = db.collection("announcements")
            .whereEqualTo("active", true)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(60)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    reportFirebase("announcements", "Failed to load announcements", err)
                    return@addSnapshotListener
                }
                if (snap != null) {
                    val list = snap.documents.map { d ->
                        AnnouncementUi(
                            id = d.id,
                            title = d.getString("title") ?: "",
                            body = d.getString("body") ?: "",
                            active = d.getBoolean("active") ?: true,
                            priority = (d.getLong("priority") ?: 0L).toInt(),
                            createdAt = d.getTimestamp("createdAt")
                        )
                    }.sortedWith(
                        compareByDescending<AnnouncementUi> { it.priority }
                            .thenByDescending { it.createdAt }
                    )
                    announcements = list
                }
            }
        onDispose { reg?.remove() }
    }

    // --- Moderation state comes from ViewModel ---
    val moderation = remember(
        chatboxViewModel.warned,
        chatboxViewModel.warnReason,
        chatboxViewModel.uidBanned,
        chatboxViewModel.banReason,
        chatboxViewModel.deviceBanned,
        chatboxViewModel.deviceBanReason
    ) {
        ModerationUi(
            warned = chatboxViewModel.warned,
            warnReason = chatboxViewModel.warnReason,
            banned = chatboxViewModel.uidBanned,
            banReason = chatboxViewModel.banReason,
            deviceBanned = chatboxViewModel.deviceBanned,
            deviceBanReason = chatboxViewModel.deviceBanReason,
            updatedAt = null
        )
    }

    // --- ToS gate (remote) ---
    val requiredTosVersion = remoteTos.tosVersion.coerceAtLeast(1)
    var tosAccepted by rememberSaveable {
        mutableStateOf(TosPrefs.isAccepted(ctx, requiredTosVersion, remoteTos.updatedAt?.toDate()?.time))
    }

    LaunchedEffect(requiredTosVersion, remoteTos.updatedAt) {
        tosAccepted = TosPrefs.isAccepted(ctx, requiredTosVersion, remoteTos.updatedAt?.toDate()?.time)
    }

    if (!tosAccepted) {
        TosGate(
            tosVersion = requiredTosVersion,
            tosText = remoteTos.tosText,
            tosUrl = remoteTos.tosUrl,
            onOpenUrl = { url ->
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            },
            onAccept = {
                TosPrefs.accept(ctx, requiredTosVersion, remoteTos.updatedAt?.toDate()?.time)
                tosAccepted = true
            }
        )
        return
    }

    // --- Ban gate (from VM) ---
    val isBannedEffective = chatboxViewModel.isBanned

    // Ensure a one-time stop/clear when the ban flips on
    var banStopRan by remember { mutableStateOf(false) }
    LaunchedEffect(isBannedEffective) {
        if (isBannedEffective && !banStopRan) {
            banStopRan = true
            runCatching { chatboxViewModel.killStopAndClear() }
        }
        if (!isBannedEffective) banStopRan = false
    }

    var page by rememberSaveable { mutableStateOf(AppPage.Home) }

    // Safety: if PUBLIC build, never allow landing on Admin
    LaunchedEffect(Unit) {
        if (!BuildConfig.IS_ADMIN_BUILD && page == AppPage.Admin) page = AppPage.Home
    }

    var showSettingsSheet by remember { mutableStateOf(false) }
    var showInfoSheet by remember { mutableStateOf(false) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }

    // Apply persisted music UI settings once
    LaunchedEffect(Unit) {
        // Do NOT persist Now Playing toggle across app restarts.
        UiPrefs.writeSpotifyEnabled(ctx, false)
        chatboxViewModel.setSpotifyEnabledFlag(false)

        // Keep demo + preset restore.
        chatboxViewModel.setSpotifyDemoFlag(UiPrefs.readSpotifyDemo(ctx))
        chatboxViewModel.updateSpotifyPreset(UiPrefs.readSpotifyPreset(ctx))
    }

    // If banned, always keep them on Home (so they see ban screen)
    LaunchedEffect(isBannedEffective) {
        if (isBannedEffective) page = AppPage.Home
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            DrawerContent(
                current = page,
                onSelect = { chosen ->
                    val safeChosen =
                        if (isBannedEffective) AppPage.Home
                        else if (!BuildConfig.IS_ADMIN_BUILD && chosen == AppPage.Admin) AppPage.Home
                        else chosen

                    page = safeChosen
                    scope.launch { drawerState.close() }
                },
                onOpenSettings = {
                    showSettingsSheet = true
                    scope.launch { drawerState.close() }
                },
                onOpenInfo = {
                    showInfoSheet = true
                    scope.launch { drawerState.close() }
                }
            )
        }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(if (BuildConfig.IS_ADMIN_BUILD) "VRC-A (ADMIN)" else "VRC-A") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Menu")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSettingsSheet = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            contentWindowInsets = WindowInsets(0)
        ) { padding ->
            // Use Column so banners are NOT drawn on top of content.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Warning banner only (ban shows full screen)
                
                Crossfade(targetState = page, label = "page_crossfade") { p ->
                    when (p) {
                        AppPage.Home -> {
                            if (isBannedEffective) {
                                BannedScreen(
                                    uid = safeUid(),
                                    deviceHash = deviceHash,
                                    banReason = moderation.banReason,
                                    deviceBanReason = moderation.deviceBanReason,
                                    onOpenInfo = { showInfoSheet = true },
                                    onOpenSettings = { showSettingsSheet = true }
                                )
                            } else {
                                HomePage(
                                    vm = chatboxViewModel,
                                    snackbarHostState = snackbarHostState,
                                    onOpenSettings = { showSettingsSheet = true },
                                    announcements = announcements,
                                    moderation = moderation,
                                    isBanned = false
                                )
                            }
                        }

                        AppPage.Automations -> AutomationsPage(chatboxViewModel, isBanned = isBannedEffective)

                        AppPage.Music -> NowPlayingPage(
                            vm = chatboxViewModel,
                            isBanned = isBannedEffective,
                            onPersistSpotifyEnabled = { UiPrefs.writeSpotifyEnabled(ctx, it) },
                            onPersistSpotifyDemo = { UiPrefs.writeSpotifyDemo(ctx, it) },
                            onPersistSpotifyPreset = { UiPrefs.writeSpotifyPreset(ctx, it) }
                        )

                        AppPage.Debug -> DebugPage(
                            vm = chatboxViewModel,
                            lastFirebaseIssue = lastFirebaseIssue,
                            moderationError = chatboxViewModel.moderationLastError
                        )

                        AppPage.Admin -> {
                            if (BuildConfig.IS_ADMIN_BUILD && !isBannedEffective) {
                                AdminScreen()
                            } else {
                                HomePage(
                                    vm = chatboxViewModel,
                                    snackbarHostState = snackbarHostState,
                                    onOpenSettings = { showSettingsSheet = true },
                                    announcements = announcements,
                                    moderation = moderation,
                                    isBanned = isBannedEffective
                                )
                            }
                        }
                    }
                }

                if (showSettingsSheet) {
                    SettingsSheet(
                        vm = chatboxViewModel,
                        onDismiss = { showSettingsSheet = false }
                    )
                }

                if (showInfoSheet) {
                    InfoSheet(onDismiss = { showInfoSheet = false })
                }
            }
        }
    }
}

/* =========================
   Global banners (WARNINGS ONLY)
   ========================= */

@Composable
private fun GlobalStatusBanner(
    moderation: ModerationUi
) {
    if (!moderation.warned || (moderation.banned || moderation.deviceBanned)) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Warning", style = MaterialTheme.typography.labelLarge)
                Text(
                    moderation.warnReason.ifBlank { "You have been warned by moderators." },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/* =========================
   Ban screen
   ========================= */

@Composable
private fun BannedScreen(
    uid: String,
    deviceHash: String,
    banReason: String,
    deviceBanReason: String,
    onOpenInfo: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Access restricted", style = MaterialTheme.typography.headlineSmall)

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("You are banned from using this app.", style = MaterialTheme.typography.titleSmall)

                    val reasons = buildList {
                        if (banReason.isNotBlank()) add("Ban reason: $banReason")
                        if (deviceBanReason.isNotBlank()) add("Device ban: $deviceBanReason")
                    }.ifEmpty { listOf("No reason provided.") }

                    reasons.forEach { r ->
                        Text(r, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("IDs (for support/admin)", style = MaterialTheme.typography.titleSmall)
                    Text("uid=${uid.ifBlank { "?" }}", fontFamily = FontFamily.Monospace)
                    Text(
                        "deviceHash=${deviceHash.take(16).ifBlank { "?" }}...",
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("What you can do", style = MaterialTheme.typography.titleSmall)
                    Text("- You can still open Settings and Info.")
                    Text("- If this is a mistake, contact the app moderators.")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) { Text("Settings") }
                OutlinedButton(onClick = onOpenInfo, modifier = Modifier.weight(1f)) { Text("Info") }
            }
        }
    }
}

/* =========================
   ToS Gate UI
   ========================= */

@Composable
private fun TosGate(
    tosVersion: Int,
    tosText: String,
    tosUrl: String,
    onOpenUrl: (String) -> Unit,
    onAccept: () -> Unit
) {
    var checked by rememberSaveable { mutableStateOf(false) }

    // Always show something even if Firestore text is empty.
    val fallbackText = remember {
        """
TERMS OF SERVICE (SUMMARY)

By using this app, you agree to:
- Use it responsibly and legally
- Not use it to harass, spam, or impersonate others
- Understand VRChat chatbox limits apply and messages may be trimmed
- Accept that settings/history are stored locally on your device
- You may be moderated (warned/banned) for abuse

If you do not agree, close the app.
        """.trimIndent()
    }

    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Terms of Service", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Version $tosVersion",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            ElevatedCard {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = tosText.ifBlank { fallbackText },
                        style = MaterialTheme.typography.bodyMedium
                    )

                    if (tosUrl.isNotBlank()) {
                        OutlinedButton(
                            onClick = { onOpenUrl(tosUrl.trim()) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Open full ToS link")
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("I agree to the Terms of Service")
                Switch(checked = checked, onCheckedChange = { checked = it })
            }

            Button(
                onClick = onAccept,
                enabled = checked,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Accept & Continue")
            }
        }
    }
}

/* =========================
   LEFT NAV DRAWER
   ========================= */

@Composable
private fun DrawerContent(
    current: AppPage,
    onSelect: (AppPage) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenInfo: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(min = 290.dp, max = 360.dp),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Navigation", style = MaterialTheme.typography.titleLarge)

            DrawerSectionHeader("Main")
            DrawerItem(
                title = AppPage.Home.title,
                icon = Icons.Filled.Home,
                selected = current == AppPage.Home,
                onClick = { onSelect(AppPage.Home) }
            )
            DrawerItem(
                title = AppPage.Automations.title,
                icon = Icons.Filled.Sync,
                selected = current == AppPage.Automations,
                onClick = { onSelect(AppPage.Automations) }
            )
            DrawerItem(
                title = AppPage.Music.title,
                icon = Icons.Filled.MusicNote,
                selected = current == AppPage.Music,
                onClick = { onSelect(AppPage.Music) }
            )

            DrawerSectionHeader("Tools")
            DrawerItem(
                title = AppPage.Debug.title,
                icon = Icons.Filled.BugReport,
                selected = current == AppPage.Debug,
                onClick = { onSelect(AppPage.Debug) }
            )

            if (BuildConfig.IS_ADMIN_BUILD) {
                DrawerItem(
                    title = AppPage.Admin.title,
                    icon = Icons.Filled.Gavel,
                    selected = current == AppPage.Admin,
                    onClick = { onSelect(AppPage.Admin) }
                )
            }

            Divider()

            DrawerSectionHeader("Setup")
            DrawerItem(
                title = "Settings & Permissions",
                icon = Icons.Filled.Settings,
                selected = false,
                onClick = onOpenSettings
            )
            DrawerItem(
                title = "Info",
                icon = Icons.Filled.Info,
                selected = false,
                onClick = onOpenInfo
            )

            Spacer(Modifier.weight(1f))

            Text(
                text = if (BuildConfig.IS_ADMIN_BUILD) "VRC-A (Admin build)" else "VRC-A",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DrawerSectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp)
    )
}

@Composable
private fun DrawerItem(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    NavigationDrawerItem(
        label = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        modifier = Modifier.fillMaxWidth(),
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            unselectedContainerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f),
            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

/* =========================
   COMMON LAYOUT
   ========================= */

@Composable
private fun PageContainer(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .padding(end = 10.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 4,
                        overflow = TextOverflow.Clip
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 6,
                            overflow = TextOverflow.Clip
                        )
                    }
                }
                if (actions != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions
                    )
                }
            }
            content()
        }
    }
}

/* =========================
   HOME
   ========================= */

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun HomePage(
    vm: com.scrapw.chatbox.ui.ChatboxViewModel,
    snackbarHostState: SnackbarHostState,
    onOpenSettings: () -> Unit,
    announcements: List<AnnouncementUi>,
    moderation: ModerationUi,
    isBanned: Boolean
) {
    val uiState by vm.messengerUiState.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val connectionBring = remember { BringIntoViewRequester() }
    val manualSendBring = remember { BringIntoViewRequester() }

    var ipInputText by rememberSaveable { mutableStateOf(uiState.ipAddress) }
    LaunchedEffect(uiState.ipAddress) {
        if (ipInputText.isBlank()) ipInputText = uiState.ipAddress
    }

    var tutorialExpanded by remember { mutableStateOf(UiPrefs.readTutorialExpanded(ctx)) }

    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    LaunchedEffect(Unit) { overlayGranted = Settings.canDrawOverlays(ctx) }

    val pm = remember(ctx) { ctx.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var batteryOk by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }
    LaunchedEffect(Unit) { batteryOk = pm.isIgnoringBatteryOptimizations(ctx.packageName) }

    val notifOk = vm.listenerConnected
    val ipOk = uiState.ipAddress.isNotBlank() && uiState.ipAddress != "127.0.0.1"

    val topAnnouncements = remember(announcements) {
        announcements
            .sortedWith(compareByDescending<AnnouncementUi> { it.priority }.thenByDescending { it.createdAt })
            .take(3)
    }

    PageContainer {
        if (topAnnouncements.isNotEmpty()) {
            SectionCard(
                title = "Announcements",
                subtitle = "Latest updates from the app team."
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    topAnnouncements.forEach { a ->
                        ElevatedCard(
                            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(a.title.ifBlank { "Announcement" }, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    a.body.ifBlank { "" },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }

        if (moderation.warned && !(moderation.banned || moderation.deviceBanned)) {
            SectionCard(
                title = "Account warning",
                subtitle = "This warning is shown to you only."
            ) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("You have been warned.", style = MaterialTheme.typography.titleSmall)
                        Text(
                            moderation.warnReason.ifBlank { "No reason provided." },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        SectionCard(
            title = "VRChat Preview",
            subtitle = "Exactly what will appear in your chatbox.",
            actions = {
                Button(
                    onClick = {
                        vm.startAfkSender()
                        vm.startCycle()
                        vm.startNowPlayingSender()
                    },
                    enabled = !isBanned
                ) { Text("Start") }

                Button(
                    onClick = { vm.killStopAndClear() },
                    enabled = !isBanned,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("KILL", color = MaterialTheme.colorScheme.onError)
                }
            }
        ) {
            val previewTextRaw = vm.debugLastCombinedOsc.ifBlank { "(nothing active)" }
            val previewText = remember(previewTextRaw) { vrChatSafePreview(previewTextRaw) }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(280.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = 420.dp)
                        .fillMaxWidth(0.92f),
                    tonalElevation = 3.dp,
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Box(
                        Modifier
                            .heightIn(min = 96.dp)
                            .padding(12.dp)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        SelectionContainer {
                            Text(
                                text = previewText,
                                modifier = Modifier.fillMaxWidth(),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                softWrap = true,
                                maxLines = 9,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Canvas(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .height(200.dp)
                        .width(170.dp)
                ) {
                    val w = size.width
                    val h = size.height

                    drawCircle(
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f),
                        radius = w * 0.18f,
                        center = Offset(w * 0.5f, h * 0.20f)
                    )

                    val path = Path().apply {
                        moveTo(w * 0.50f, h * 0.36f)
                        cubicTo(w * 0.18f, h * 0.40f, w * 0.18f, h * 0.96f, w * 0.50f, h * 0.98f)
                        cubicTo(w * 0.82f, h * 0.96f, w * 0.82f, h * 0.40f, w * 0.50f, h * 0.36f)
                        close()
                    }
                    drawPath(path, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.06f))
                }
            }

            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Quick Toggles", style = MaterialTheme.typography.titleSmall)
                    ToggleRow("AFK", vm.afkEnabled, enabled = !isBanned) { vm.setAfkEnabledFlag(it) }
                    ToggleRow("Cycle", vm.cycleEnabled, enabled = !isBanned) { vm.setCycleEnabledFlag(it) }
                    ToggleRow("Now Playing", vm.spotifyEnabled, enabled = !isBanned) { vm.setSpotifyEnabledFlag(it) }
                }
            }
        }

        SectionCard(
            title = "Setup Tutorial",
            subtitle = "Tap each step to open settings or jump to the right place.",
            actions = {
                IconButton(onClick = {
                    tutorialExpanded = !tutorialExpanded
                    UiPrefs.writeTutorialExpanded(ctx, tutorialExpanded)
                }) {
                    Icon(
                        imageVector = if (tutorialExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null
                    )
                }
            }
        ) {
            AnimatedVisibility(
                visible = tutorialExpanded,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 3 }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 3 })
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TutorialStep(
                        number = 0,
                        title = "Enable OSC in VRChat",
                        subtitle = "VRChat -> Settings -> OSC -> Enable OSC.",
                        icon = Icons.Filled.Bolt,
                        primary = "How"
                    ) { scope.launch { snackbarHostState.showSnackbar("Open VRChat -> Settings -> OSC -> Enable OSC") } }

                    TutorialStep(
                        number = 1,
                        title = "Enable Notification Access",
                        subtitle = if (notifOk) "Enabled." else "Required for Now Playing detection.",
                        icon = Icons.Filled.MusicNote,
                        primary = "Open"
                    ) { ctx.startActivity(vm.notificationAccessIntent()) }

                    TutorialStep(
                        number = 2,
                        title = "Allow Overlay permission",
                        subtitle = if (overlayGranted) "Enabled." else "Only needed if you use overlay.",
                        icon = Icons.Filled.Bolt,
                        primary = "Open"
                    ) {
                        ctx.startActivity(vm.overlayPermissionIntent())
                        overlayGranted = Settings.canDrawOverlays(ctx)
                    }

                    TutorialStep(
                        number = 3,
                        title = "Disable Battery Optimization",
                        subtitle = if (batteryOk) "Disabled (good)." else "Stops Android pausing the app when screen is off.",
                        icon = Icons.Filled.Power,
                        primary = "Request"
                    ) {
                        ctx.startActivity(vm.batteryOptimizationIntent())
                        batteryOk = pm.isIgnoringBatteryOptimizations(ctx.packageName)
                    }

                    TutorialStep(
                        number = 4,
                        title = "Set Headset IP",
                        subtitle = if (ipOk) "Looks set." else "Quest/PC IP on the same Wi-Fi.",
                        icon = Icons.Filled.Wifi,
                        primary = "Go"
                    ) { scope.launch { connectionBring.bringIntoView() } }

                    TutorialStep(
                        number = 5,
                        title = "Manual Test Send",
                        subtitle = "Type a message and hit Send.",
                        icon = Icons.Filled.ChevronRight,
                        primary = "Go"
                    ) { scope.launch { manualSendBring.bringIntoView() } }
                }
            }
        }

        SectionCard(
            title = "Connection",
            subtitle = "Headset IP (Quest / PC)."
        ) {
            Column(Modifier.bringIntoViewRequester(connectionBring)) {
                OutlinedTextField(
                    value = ipInputText,
                    onValueChange = { s: String -> ipInputText = s },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Headset IP address") },
                    placeholder = { Text("Example: 192.168.1.23") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    enabled = !isBanned
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            val ip = ipInputText.trim()
                            runCatching { vm.ipAddressApply(ip) }
                                .onFailure {
                                    scope.launch {
                                        snackbarHostState.showSnackbar("IP apply failed. Check format (e.g. 192.168.1.23)")
                                    }
                                }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isBanned
                    ) { Text("Apply") }

                    OutlinedButton(
                        onClick = { ipInputText = uiState.ipAddress },
                        modifier = Modifier.weight(1f),
                        enabled = !isBanned
                    ) { Text("Reset") }
                }

                Text(
                    text = "Current target: ${uiState.ipAddress}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        SectionCard(
            title = "Manual Send",
            subtitle = "One-off message (doesn't affect AFK/Cycle/Now Playing)."
        ) {
            Column(Modifier.bringIntoViewRequester(manualSendBring)) {
                OutlinedTextField(
                    value = vm.messageText.value,
                    onValueChange = { v: TextFieldValue -> vm.onMessageTextChange(v) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    label = { Text("Message") },
                    enabled = !isBanned
                )
                Button(
                    onClick = { vm.sendMessage() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isBanned
                ) {
                    Text("Send")
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun TutorialStep(
    number: Int,
    title: String,
    subtitle: String,
    icon: ImageVector,
    primary: String,
    onPrimary: () -> Unit
) {
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onPrimary() }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null)
                }
            }

            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = "$number. $title",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            TextButton(onClick = onPrimary, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Text(primary)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Filled.ChevronRight, contentDescription = null)
            }
        }
    }
}

/* =========================
   AUTOMATIONS
   ========================= */

@Composable
private fun AutomationsPage(vm: com.scrapw.chatbox.ui.ChatboxViewModel, isBanned: Boolean) {
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(ChatboxAutomationsTab.AFK) }

    val cycleLineFields = remember { mutableStateMapOf<Int, TextFieldValue>() }

    fun syncCycleLineFieldsFromVm() {
        val valid = vm.cycleLines.indices.toSet()
        cycleLineFields.keys.toList().forEach { if (it !in valid) cycleLineFields.remove(it) }
        vm.cycleLines.forEachIndexed { idx, text ->
            val existing = cycleLineFields[idx]
            if (existing == null || existing.text != text) cycleLineFields[idx] = TextFieldValue(text)
        }
    }

    LaunchedEffect(vm.cycleLines.size) { syncCycleLineFieldsFromVm() }
    LaunchedEffect(vm.cycleLines.toList()) { syncCycleLineFieldsFromVm() }

    fun afkPresetsPreview(): String {
        val parts = (1..3).map { slot ->
            val p = vm.getAfkPresetPreview(slot).ifBlank { "empty" }
            "${slot}:${p}"
        }
        return parts.joinToString("  -  ").let { if (it.length > 80) it.take(79) + "..." else it }
    }

    fun cyclePresetsPreview(): String {
        val parts = (1..5).map { slot ->
            val p = vm.getCyclePresetPreview(slot).ifBlank { "empty" }
            "${slot}:${p}"
        }
        return parts.joinToString("  -  ").let { if (it.length > 80) it.take(79) + "..." else it }
    }

    PageContainer {
        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(10.dp)) {
                TabRow(selectedTabIndex = tab.ordinal) {
                    ChatboxAutomationsTab.entries.forEachIndexed { idx, t ->
                        Tab(
                            selected = (tab.ordinal == idx),
                            onClick = { tab = t },
                            text = { Text(t.title) }
                        )
                    }
                }
            }
        }

        when (tab) {
            ChatboxAutomationsTab.AFK -> {
                SectionCard(
                    title = "AFK",
                    subtitle = "AFK always appears above Cycle + Music."
                ) {
                    ToggleRow("AFK enabled", vm.afkEnabled, enabled = !isBanned) { vm.setAfkEnabledFlag(it) }

                    OutlinedTextField(
                        value = vm.afkMessage,
                        onValueChange = { s: String -> vm.updateAfkText(s) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("AFK text") },
                        enabled = !isBanned
                    )

                    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !isBanned) { vm.updateAfkPresetsCollapsed(!vm.afkPresetsCollapsed) },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("AFK Presets (3)", style = MaterialTheme.typography.titleSmall)
                                    if (vm.afkPresetsCollapsed) {
                                        Text(
                                            afkPresetsPreview(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Icon(
                                    imageVector = if (vm.afkPresetsCollapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                                    contentDescription = null
                                )
                            }

                            AnimatedVisibility(
                                visible = !vm.afkPresetsCollapsed,
                                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 4 }),
                                exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 4 })
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    (1..3).forEach { slot ->
                                        ElevatedCard {
                                            Column(
                                                Modifier.padding(10.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                val preview = vm.getAfkPresetPreview(slot).ifBlank { "(empty)" }
                                                Text("Preset $slot â€” $preview")

                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    OutlinedButton(
                                                        onClick = { scope.launch { vm.loadAfkPreset(slot) } },
                                                        modifier = Modifier.weight(1f),
                                                        enabled = !isBanned
                                                    ) { Text("Load") }

                                                    Button(
                                                        onClick = { scope.launch { vm.saveAfkPreset(slot, vm.afkMessage) } },
                                                        modifier = Modifier.weight(1f),
                                                        enabled = !isBanned
                                                    ) { Text("Save") }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { vm.startAfkSender() },
                            modifier = Modifier.weight(1f),
                            enabled = !isBanned && vm.afkEnabled
                        ) { Text("Start") }

                        OutlinedButton(
                            onClick = { vm.stopAfkSender(clearFromChatbox = true) },
                            modifier = Modifier.weight(1f),
                            enabled = !isBanned
                        ) { Text("Stop") }
                    }

                    OutlinedButton(
                        onClick = { vm.sendAfkNow() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isBanned && vm.afkEnabled
                    ) { Text("Send once") }
                }
            }

            ChatboxAutomationsTab.Cycle -> {
                SectionCard(
                    title = "Cycle",
                    subtitle = "Up to 10 lines. Stop clears instantly."
                ) {
                    ToggleRow("Cycle enabled", vm.cycleEnabled, enabled = !isBanned) { vm.setCycleEnabledFlag(it) }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (vm.cycleLines.isEmpty()) {
                            Text("No lines yet. Tap Add Line.", style = MaterialTheme.typography.bodySmall)
                        }

                        vm.cycleLines.forEachIndexed { idx, _ ->
                            val fieldValue =
                                cycleLineFields[idx] ?: TextFieldValue(vm.cycleLines.getOrNull(idx).orEmpty())

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = fieldValue,
                                    onValueChange = { v: TextFieldValue ->
                                        cycleLineFields[idx] = v
                                        vm.updateCycleLine(idx, v.text)
                                    },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    label = { Text("Line ${idx + 1}") },
                                    enabled = !isBanned
                                )
                                Spacer(Modifier.width(8.dp))
                                IconButton(onClick = { vm.removeCycleLine(idx) }, enabled = !isBanned) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Remove line")
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { vm.addCycleLine() },
                                enabled = !isBanned && vm.cycleLines.size < 10,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Add line (${vm.cycleLines.size}/10)")
                            }

                            OutlinedButton(
                                onClick = { vm.clearCycleLines() },
                                enabled = !isBanned && vm.cycleLines.isNotEmpty(),
                                modifier = Modifier.weight(1f)
                            ) { Text("Clear") }
                        }
                    }

                    Text(
                        text = "Cycle speed: fixed at 10 seconds",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !isBanned) { vm.updateCyclePresetsCollapsed(!vm.cyclePresetsCollapsed) },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Cycle Presets (5)", style = MaterialTheme.typography.titleSmall)
                                    if (vm.cyclePresetsCollapsed) {
                                        Text(
                                            cyclePresetsPreview(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Icon(
                                    imageVector = if (vm.cyclePresetsCollapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                                    contentDescription = null
                                )
                            }

                            AnimatedVisibility(
                                visible = !vm.cyclePresetsCollapsed,
                                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 4 }),
                                exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 4 })
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    (1..5).forEach { slot ->
                                        ElevatedCard {
                                            Column(
                                                Modifier.padding(10.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                val preview = vm.getCyclePresetPreview(slot).ifBlank { "(empty)" }
                                                Text("Preset $slot â€” $preview")

                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    OutlinedButton(
                                                        onClick = { scope.launch { vm.loadCyclePreset(slot) } },
                                                        modifier = Modifier.weight(1f),
                                                        enabled = !isBanned
                                                    ) { Text("Load") }

                                                    Button(
                                                        onClick = { scope.launch { vm.saveCyclePreset(slot, vm.cycleLines.toList()) } },
                                                        modifier = Modifier.weight(1f),
                                                        enabled = !isBanned
                                                    ) { Text("Save") }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { vm.startCycle() },
                            modifier = Modifier.weight(1f),
                            enabled = !isBanned && vm.cycleEnabled && vm.cycleLines.any { it.trim().isNotEmpty() }
                        ) { Text("Start") }

                        OutlinedButton(
                            onClick = { vm.stopCycle(clearFromChatbox = true) },
                            modifier = Modifier.weight(1f),
                            enabled = !isBanned
                        ) { Text("Stop") }
                    }
                }
            }
        }
    }
}

/* =========================
   MUSIC
   ========================= */

@Composable
private fun NowPlayingPage(
    vm: com.scrapw.chatbox.ui.ChatboxViewModel,
    isBanned: Boolean,
    onPersistSpotifyEnabled: (Boolean) -> Unit,
    onPersistSpotifyDemo: (Boolean) -> Unit,
    onPersistSpotifyPreset: (Int) -> Unit
) {
    val ctx = LocalContext.current

    var previewT by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            previewT += 0.02f
            if (previewT > 1f) previewT -= 1f
            delay(60L)
        }
    }

    PageContainer {
        SectionCard(
            title = "Now Playing",
            subtitle = "Uses Notification Access. Stop clears instantly."
        ) {
            ToggleRow("Enable Now Playing block", vm.spotifyEnabled, enabled = !isBanned) {
                vm.setSpotifyEnabledFlag(it)
                onPersistSpotifyEnabled(it)
            }
            ToggleRow("Demo mode (testing)", vm.spotifyDemoEnabled, enabled = !isBanned) {
                vm.setSpotifyDemoFlag(it)
                onPersistSpotifyDemo(it)
            }

            OutlinedButton(
                onClick = { ctx.startActivity(vm.notificationAccessIntent()) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Open Notification Access settings") }

            Text(
                text = "Music refresh speed: fixed at 2 seconds",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("Progress bar preset:", style = MaterialTheme.typography.labelLarge)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..5).forEach { p ->
                    val selected = (vm.spotifyPreset == p)
                    val name = vm.getMusicPresetName(p)

                    ElevatedCard(
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(10.dp)
                                .clickable(enabled = !isBanned) {
                                    vm.updateSpotifyPreset(p)
                                    onPersistSpotifyPreset(p)
                                },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(text = name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = vm.renderMusicPresetPreview(p, previewT),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            if (selected) Text("Selected", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { vm.startNowPlayingSender() },
                    modifier = Modifier.weight(1f),
                    enabled = !isBanned && vm.spotifyEnabled
                ) { Text("Start") }

                OutlinedButton(
                    onClick = { vm.stopNowPlayingSender(clearFromChatbox = true) },
                    modifier = Modifier.weight(1f),
                    enabled = !isBanned
                ) { Text("Stop") }
            }

            OutlinedButton(
                onClick = { vm.sendNowPlayingOnce() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isBanned && vm.spotifyEnabled
            ) { Text("Send once now (test)") }
        }

        SectionCard(
            title = "Detected / Preview",
            subtitle = "If blank: enable access, restart app, then play music."
        ) {
            Text("Detected: ${vm.nowPlayingDetected}")
            Text("Artist: ${vm.lastNowPlayingArtist}")
            Text("Title: ${vm.lastNowPlayingTitle}")
            Text("App: ${vm.activePackage}")
            Text("Status: ${if (vm.nowPlayingIsPlaying) "Playing" else "Paused"}")
        }
    }
}

/* =========================
   DEBUG
   ========================= */

@Composable
private fun DebugPage(
    vm: com.scrapw.chatbox.ui.ChatboxViewModel,
    lastFirebaseIssue: String?,
    moderationError: String?
) {
    PageContainer {
        SectionCard(
            title = "Firebase (last issue)",
            subtitle = "ToS / Announcements / Auth errors captured here."
        ) {
            Text(
                text = lastFirebaseIssue ?: "(none captured)",
                fontFamily = FontFamily.Monospace
            )
        }

        SectionCard(
            title = "Moderation listener (last error)",
            subtitle = "If non-empty, moderation reads may be failing."
        ) {
            Text(
                text = moderationError?.ifBlank { "(none)" } ?: "(none)",
                fontFamily = FontFamily.Monospace
            )
        }

        SectionCard(
            title = "Listener",
            subtitle = "Confirms Notification Access + media detection."
        ) {
            Text("Listener connected: ${vm.listenerConnected}")
            Text("Active package: ${vm.activePackage}")
            Text("Detected: ${vm.nowPlayingDetected}")
            Text("Playing: ${vm.nowPlayingIsPlaying}")
        }

        SectionCard(
            title = "OSC Output Preview",
            subtitle = "Raw lines + combined output."
        ) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("AFK:", style = MaterialTheme.typography.labelLarge)
                    Text(vm.debugLastAfkOsc, fontFamily = FontFamily.Monospace)

                    Text("Cycle:", style = MaterialTheme.typography.labelLarge)
                    Text(vm.debugLastCycleOsc, fontFamily = FontFamily.Monospace)

                    Text("Music:", style = MaterialTheme.typography.labelLarge)
                    Text(vm.debugLastMusicOsc, fontFamily = FontFamily.Monospace)

                    Text("Combined:", style = MaterialTheme.typography.labelLarge)
                    Text(vm.debugLastCombinedOsc, fontFamily = FontFamily.Monospace)
                }
            }
        }

        SectionCard(title = "VRChat send status") {
            Text("Last sent to VRChat (ms): ${vm.lastSentToVrchatAtMs}")
        }
    }
}

/* =========================
   SETTINGS SHEET
   ========================= */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    vm: com.scrapw.chatbox.ui.ChatboxViewModel,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Settings & Setup", style = MaterialTheme.typography.titleMedium)
            }

            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsGroupHeader("Permissions")

                    SettingsRow(
                        icon = Icons.Filled.MusicNote,
                        title = "Notification Access",
                        subtitle = "Required for Now Playing detection.",
                        primary = "Open"
                    ) { ctx.startActivity(vm.notificationAccessIntent()) }

                    SettingsRow(
                        icon = Icons.Filled.Bolt,
                        title = "Overlay Permission",
                        subtitle = "Only needed if you use overlay.",
                        primary = "Open"
                    ) { ctx.startActivity(vm.overlayPermissionIntent()) }

                    SettingsRow(
                        icon = Icons.Filled.Power,
                        title = "Battery Optimization",
                        subtitle = "Stops Android pausing when screen is off.",
                        primary = "Request"
                    ) { ctx.startActivity(vm.batteryOptimizationIntent()) }
                }
            }

            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Close")
            }
        }
    }
}

@Composable
private fun SettingsGroupHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    primary: String,
    onPrimary: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onPrimary() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null)
            }
        }

        Spacer(Modifier.width(10.dp))

        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        TextButton(onClick = onPrimary) {
            Text(primary)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Filled.ChevronRight, contentDescription = null)
        }
    }
}

/* =========================
   INFO SHEET
   ========================= */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoSheet(onDismiss: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(ChatboxInfoTab.Overview) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Info, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Info", style = MaterialTheme.typography.titleMedium)
            }

            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TabRow(selectedTabIndex = tab.ordinal) {
                        ChatboxInfoTab.entries.forEachIndexed { idx, t ->
                            Tab(
                                selected = (tab.ordinal == idx),
                                onClick = { tab = t },
                                text = { Text(t.title) }
                            )
                        }
                    }

                    val overview = remember {
                        """
VRC-A (VRChat Assistant)

- Sends OSC chatbox text to your Quest/PC target
- Includes: AFK, Cycle, Now Playing, Manual Send
- Use KILL to stop all senders and clear the VRChat chatbox
                        """.trimIndent()
                    }

                    val help = remember {
                        """
HELP

Nothing appears in VRChat:
- VRChat -> Settings -> OSC -> Enable OSC
- Phone + headset on the same Wi-Fi
- Set the correct headset IP (Home -> Connection)
- Try Manual Send

Now Playing blank:
- Enable Notification Access
- Reopen the app
- Start music so a notification exists

Stops sending with screen off:
- Disable Battery Optimization for VRC-A
                        """.trimIndent()
                    }

                    val text = when (tab) {
                        ChatboxInfoTab.Overview -> overview
                        ChatboxInfoTab.Help -> help
                    }

                    SelectionContainer {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Close")
            }
        }
    }
}

/**
 * Makes preview behave nicer:
 * - Inserts zero-width breaks into long unbroken tokens so Text can wrap
 * - Keeps newlines intact
 */
private fun vrChatSafePreview(input: String): String {
    val zwsp = '\u200B'
    val maxToken = 18

    fun breakLongToken(token: String): String {
        if (token.length <= maxToken) return token
        val sb = StringBuilder(token.length + token.length / maxToken)
        var i = 0
        while (i < token.length) {
            val end = (i + maxToken).coerceAtMost(token.length)
            sb.append(token.substring(i, end))
            if (end < token.length) sb.append(zwsp)
            i = end
        }
        return sb.toString()
    }

    return input.lines().joinToString("\n") { line ->
        line.split(" ").joinToString(" ") { breakLongToken(it) }
    }
}
