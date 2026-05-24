package com.vrca.ui.screen

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.net.Uri
import android.provider.Settings.Secure
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.vrca.BuildConfig
import com.vrca.admin.AdminScreen
import com.vrca.vrchat.VrchatAuthManager
import com.vrca.vrchat.VrchatPipelineState
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest

private enum class AppPage(val title: String) {
    Home("Home"),
    Automations("Automations"),
    Music("Music"),
    VrchatStatus("VRChat"),
    Settings("Settings"),
    Admin("Admin")
}

internal enum class ChatboxAutomationsTab(val title: String) {
    Pinned("Pinned"),
    Cycle("Cycle")
}

private enum class ChatboxInfoTab(val title: String) {
    Overview("Overview"),
    Help("Help")
}

/** Simple persisted UI prefs (no VM changes required). */
internal object UiPrefs {
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
 * OK ToS acceptance storage.
 * We store an "accepted_version" integer locally.
 * "version" is fetched remotely.
 */
private object TosPrefs {
    private const val FILE = "vrca_tos"
    private const val KEY_ACCEPTED_VERSION = "accepted_version"
    private const val KEY_ACCEPTED_AT_MS = "accepted_at_ms"

    fun acceptedVersion(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).getInt(KEY_ACCEPTED_VERSION, 0)

    fun acceptedAtMs(ctx: Context): Long =
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).getLong(KEY_ACCEPTED_AT_MS, 0L)

    fun accept(ctx: Context, version: Int) {
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).edit()
            .putInt(KEY_ACCEPTED_VERSION, version.coerceAtLeast(1))
            .putLong(KEY_ACCEPTED_AT_MS, System.currentTimeMillis())
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

internal data class AnnouncementUi(
    val id: String,
    val title: String,
    val body: String,
    val active: Boolean,
    val priority: Int,
    val createdAt: Timestamp?
)

internal data class ModerationUi(
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
fun VrcaScreen(
    chatboxViewModel: com.vrca.ui.viewmodel.VrcaViewModel
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
    val requiredUpdatedAtMs = remoteTos.updatedAt?.toDate()?.time ?: 0L

    var tosAccepted by rememberSaveable {
        mutableStateOf(
            TosPrefs.acceptedVersion(ctx) >= requiredTosVersion &&
                TosPrefs.acceptedAtMs(ctx) >= requiredUpdatedAtMs
        )
    }

    LaunchedEffect(requiredTosVersion, requiredUpdatedAtMs) {
        tosAccepted =
            TosPrefs.acceptedVersion(ctx) >= requiredTosVersion &&
                TosPrefs.acceptedAtMs(ctx) >= requiredUpdatedAtMs
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
                TosPrefs.accept(ctx, requiredTosVersion)
                tosAccepted = true

                // Best-effort: persist acceptance to Firestore (requires rules allowing these keys).
                scope.launch {
                    runCatching {
                        val data = hashMapOf(
                            "tosAcceptedVersion" to requiredTosVersion,
                            "tosAcceptedAt" to Timestamp.now(),
                            "updatedAt" to Timestamp.now()
                        )
                        db.collection("users").document(deviceHash)
                            .set(data, com.google.firebase.firestore.SetOptions.merge())
                            .await()
                    }
                }
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


    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }

    // Apply persisted music UI settings once
    LaunchedEffect(Unit) {
        // Do NOT persist Now Playing toggle across app restarts.
        UiPrefs.writeSpotifyEnabled(ctx, false)
        chatboxViewModel.setSpotifyEnabledFlag(false)

        // Time toggle also resets on restart (timezone persists, toggle does not).
        chatboxViewModel.updateTimeEnabled(false)

        // Keep demo + preset restore.
        chatboxViewModel.setSpotifyDemoFlag(UiPrefs.readSpotifyDemo(ctx))
        chatboxViewModel.updateSpotifyPreset(UiPrefs.readSpotifyPreset(ctx))
    }

    // If banned, always keep them on Home (so they see ban screen)
    LaunchedEffect(isBannedEffective) {
        if (isBannedEffective) page = AppPage.Home
    }

    // Setup wizard: check if VRChat linked and IP set
    val vrcLinked = VrchatAuthManager.isLoggedIn(ctx) &&
        VrchatAuthManager.getStoredUserId(ctx)?.isNotBlank() == true
    val ipSet = remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        chatboxViewModel.userPreferencesRepository.ipAddress.collect { ip ->
            ipSet.value = ip.isNotBlank() && ip != "127.0.0.1"
        }
    }
    val showSetupBanner = ipSet.value != null && (!vrcLinked || ipSet.value == false)

    Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(if (BuildConfig.IS_ADMIN_BUILD) "VRC-A (ADMIN)" else "VRC-A") },
                    navigationIcon = {
                        if (BuildConfig.IS_ADMIN_BUILD) {
                            IconButton(onClick = { page = AppPage.Admin }) {
                                Icon(Icons.Filled.Gavel, contentDescription = "Admin")
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { page = AppPage.Settings }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    }
                )
            },
            // Bottom navigation bar -- always visible, labelled
            bottomBar = {
                if (!isBannedEffective) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = page == AppPage.Home,
                            onClick = { page = AppPage.Home },
                            icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                            label = { Text("Home") }
                        )
                        NavigationBarItem(
                            selected = page == AppPage.Automations,
                            onClick = { page = AppPage.Automations },
                            icon = { Icon(Icons.Filled.Sync, contentDescription = null) },
                            label = { Text("Automations") }
                        )
                        NavigationBarItem(
                            selected = page == AppPage.Music,
                            onClick = { page = AppPage.Music },
                            icon = { Icon(Icons.Filled.MusicNote, contentDescription = null) },
                            label = { Text("Music") }
                        )
                        NavigationBarItem(
                            selected = page == AppPage.VrchatStatus,
                            onClick = { page = AppPage.VrchatStatus },
                            icon = { Icon(Icons.Filled.AccountCircle, contentDescription = null) },
                            label = { Text("VRChat") }
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            contentWindowInsets = WindowInsets(0)
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Persistent setup banner -- shows until both steps complete
                if (showSetupBanner && !isBannedEffective) {
                    SetupIncompleteBanner(
                        vrcLinked = vrcLinked,
                        ipSet = ipSet.value == true,
                        onFixVrc = { page = AppPage.VrchatStatus },
                        onFixIp = { page = AppPage.Settings }
                    )
                }

                Crossfade(targetState = page, label = "page_crossfade") { p ->
                    when (p) {
                        AppPage.Home -> {
                            if (isBannedEffective) {
                                BannedScreen(
                                    uid = safeUid(),
                                    deviceHash = deviceHash,
                                    banReason = moderation.banReason,
                                    deviceBanReason = moderation.deviceBanReason,
                                    onOpenInfo = { page = AppPage.Settings },
                                    onOpenSettings = { page = AppPage.Settings }
                                )
                            } else {
                                HomePage(
                                    vm = chatboxViewModel,
                                    snackbarHostState = snackbarHostState,
                                    onOpenSettings = { page = AppPage.Settings },
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

                        AppPage.VrchatStatus -> VrchatStatusPage(
                            vm = chatboxViewModel,
                            onOpenLogin = { /* navigate to login within page */ }
                        )

                        AppPage.Settings -> SettingsPage(
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
                                    onOpenSettings = { page = AppPage.Settings },
                                    announcements = announcements,
                                    moderation = moderation,
                                    isBanned = isBannedEffective
                                )
                            }
                        }
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
    onSelect: (AppPage) -> Unit
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

            Divider()

            DrawerSectionHeader("Setup")
            DrawerItem(
                title = AppPage.Settings.title,
                icon = Icons.Filled.Settings,
                selected = current == AppPage.Settings,
                onClick = { onSelect(AppPage.Settings) }
            )

            if (BuildConfig.IS_ADMIN_BUILD) {
                DrawerItem(
                    title = AppPage.Admin.title,
                    icon = Icons.Filled.Gavel,
                    selected = current == AppPage.Admin,
                    onClick = { onSelect(AppPage.Admin) }
                )
            }

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
internal fun PageContainer(content: @Composable ColumnScope.() -> Unit) {
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
internal fun SectionCard(
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


/**
 * Makes preview behave nicer:
 * - Inserts zero-width breaks into long unbroken tokens so Text can wrap
 * - Keeps newlines intact
 */
internal fun vrChatSafePreview(input: String): String {
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

/* =========================
   Setup incomplete banner
   Shown persistently until VRChat is linked AND IP is set.
   ========================= */

@Composable
private fun SetupIncompleteBanner(
    vrcLinked: Boolean,
    ipSet: Boolean,
    onFixVrc: () -> Unit,
    onFixIp: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "Setup incomplete",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            if (!vrcLinked) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "VRChat account not linked",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onFixVrc) { Text("Fix") }
                }
            }
            if (!ipSet) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "PC/Quest IP not configured",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onFixIp) { Text("Fix") }
                }
            }
        }
    }
}

/* =========================
   VRChat status page
   Shows presence card + login/logout controls
   ========================= */

private object StatusBannerState {
    var expanded = true
}

@Composable
internal fun VrchatStatusBanner() {
    val statusData by VrchatPipelineState.statusPageFlow.collectAsState()
    val data = statusData ?: return

    if (data.indicator == "none") return

    val bannerColor = when (data.indicator) {
        "critical" -> MaterialTheme.colorScheme.error
        "major" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.tertiary
    }
    val containerColor = when (data.indicator) {
        "critical" -> MaterialTheme.colorScheme.errorContainer
        "major" -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val onContainerColor = when (data.indicator) {
        "critical" -> MaterialTheme.colorScheme.onErrorContainer
        "major" -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onTertiaryContainer
    }

    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(StatusBannerState.expanded) }
    DisposableEffect(Unit) {
        onDispose { StatusBannerState.expanded = expanded }
    }

    val title = data.description.ifBlank {
        when (data.indicator) {
            "critical" -> "Major System Outage"
            "major" -> "Significant System Issues"
            else -> "Service Degraded"
        }
    }
    val affected = data.components.filter { it.status != "operational" }

    val innerCardColor = lerp(containerColor, onContainerColor, 0.12f)

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Canvas(Modifier.size(10.dp)) {
                    drawCircle(color = bannerColor)
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = onContainerColor,
                    modifier = Modifier.weight(1f)
                )
                if (!expanded && affected.isNotEmpty()) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = onContainerColor.copy(alpha = 0.12f)
                    ) {
                        Text(
                            "${affected.size} affected",
                            style = MaterialTheme.typography.labelSmall,
                            color = onContainerColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp
                        else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = onContainerColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (affected.isNotEmpty()) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = innerCardColor,
                            shadowElevation = 1.dp
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                Text(
                                    "Affected systems",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = onContainerColor.copy(alpha = 0.6f),
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                                for ((idx, c) in affected.withIndex()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            c.name
                                                .replace("Realtime Player State Changes",
                                                    "Realtime Player State"),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = onContainerColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Surface(
                                            shape = MaterialTheme.shapes.small,
                                            color = bannerColor.copy(alpha = 0.15f)
                                        ) {
                                            Text(
                                                c.status.replace("_", " ")
                                                    .replaceFirstChar { it.uppercase() },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = bannerColor,
                                                modifier = Modifier.padding(
                                                    horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                    }
                                    if (idx < affected.lastIndex) {
                                        Divider(
                                            color = onContainerColor.copy(alpha = 0.1f),
                                            thickness = 0.5.dp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (data.incidents.isNotEmpty()) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = innerCardColor,
                            shadowElevation = 1.dp
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                Text(
                                    "Latest updates",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = onContainerColor.copy(alpha = 0.6f),
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                                val incidents = data.incidents.take(2)
                                for ((idx, inc) in incidents.withIndex()) {
                                    Column(
                                        Modifier.padding(vertical = 6.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            inc.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = onContainerColor
                                        )
                                        if (inc.latestUpdate.isNotBlank()) {
                                            Text(
                                                inc.latestUpdate,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = onContainerColor.copy(alpha = 0.7f)
                                            )
                                        }
                                    }
                                    if (idx < incidents.lastIndex) {
                                        Divider(
                                            color = onContainerColor.copy(alpha = 0.1f),
                                            thickness = 0.5.dp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = innerCardColor,
                        shadowElevation = 1.dp,
                        modifier = Modifier.clickable {
                            val intent = Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://status.vrchat.com"))
                            ctx.startActivity(intent)
                        }
                    ) {
                        Text(
                            "View VRChat Status Page",
                            style = MaterialTheme.typography.labelSmall,
                            color = onContainerColor,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

