package com.vrca.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vrca.discord.DiscordLoginWebView
import com.vrca.discord.DiscordRpcService
import com.vrca.discord.DiscordRpcState
import com.vrca.discord.DiscordRpcStatus
import com.vrca.ui.settings.NotificationToggleSection
import com.vrca.ui.settings.ToggleRow
import com.vrca.ui.viewmodel.VrcaViewModel
import com.vrca.vrchat.InAppAlertState
import com.vrca.vrchat.VrchatAuthManager
import com.vrca.vrchat.VrchatPipelineService
import com.vrca.vrchat.VrchatPipelineState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun VrchatStatusPage(
    vm: VrcaViewModel,
    onOpenLogin: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val isLinked = remember { mutableStateOf(VrchatAuthManager.isLoggedIn(ctx)) }
    val displayName = remember { mutableStateOf(VrchatAuthManager.getStoredDisplayName(ctx) ?: "") }
    val presence by VrchatPipelineState.presenceFlow.collectAsState()
    val isConnected by VrchatPipelineState.isConnectedFlow.collectAsState()

    var showLogoutDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { InAppAlertState.load(ctx) }

    PageContainer {
        VrchatStatusBanner()

        // In-app alerts (persistent until dismissed)
        InAppAlertCards()

        // Connection status header
        ElevatedCard {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (isLinked.value) displayName.value.ifBlank { "VRChat account" }
                        else "Not signed in",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        if (isConnected) "Live connection active"
                        else if (isLinked.value) "Connecting..."
                        else "Sign in to enable notifications and presence",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isLinked.value) {
                    OutlinedButton(onClick = { showLogoutDialog = true }) { Text("Sign out") }
                } else {
                    Button(onClick = onOpenLogin) { Text("Sign in") }
                }
            }
        }

        // Presence card
        val p = presence
        if (p != null && isLinked.value) {
            val statusColor = if (p.isOnlineInVRChat) {
                when (p.status) {
                    "ask me" -> Color(0xFFFF9800)
                    "busy"   -> Color(0xFFF44336)
                    "join me" -> Color(0xFF2196F3)
                    else     -> Color(0xFF4CAF50)
                }
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            val statusText = if (p.isOnlineInVRChat) {
                when (p.status) {
                    "ask me" -> "Ask Me"
                    "busy"   -> "Do Not Disturb"
                    "join me" -> "Join Me"
                    else     -> "Online"
                }
            } else "Offline"

            val platform = when (p.platform) {
                "standalonewindows" -> "Desktop"
                "android"           -> "Android/Quest"
                "ios"               -> "iOS"
                else                -> ""
            }

            ElevatedCard {
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Status header with colored dot
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Canvas(Modifier.size(12.dp)) {
                                drawCircle(color = statusColor)
                            }
                            Column {
                                Text(
                                    p.displayName,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    statusText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = statusColor
                                )
                            }
                        }
                        if (platform.isNotBlank()) {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(platform, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    if (p.statusDescription.isNotBlank()) {
                        Text(
                            p.statusDescription,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // World info
                    if (p.isOnlineInVRChat) {
                        Divider()
                        if (p.worldName.isNotBlank()) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    p.worldName,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                val count = if (p.instanceCapacity > 0)
                                    "${p.instancePlayerCount} / ${p.instanceCapacity} players"
                                else "${p.instancePlayerCount} players"
                                Text(
                                    count,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Text(
                                when (p.location) {
                                    "private"   -> "In a private world"
                                    "traveling" -> "Traveling between worlds..."
                                    else        -> "In a world"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // VRChat profile link
                    if (p.userId.isNotBlank()) {
                        Text(
                            text = "View VRChat Profile",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                val intent = Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://vrchat.com/home/user/${p.userId}"))
                                ctx.startActivity(intent)
                            }
                        )
                    }
                }
            }
        } else if (isLinked.value) {
            ElevatedCard {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Fetching presence...", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // -- VRChat Notification Toggles (collapsible categories) --
        val repo = vm.userPreferencesRepository
        NotificationToggleSection(vm = vm)

        // -- Discord Rich Presence --
        val discordEnabled by repo.discordRpcEnabled.collectAsState(initial = false)
        val discordSeeded by repo.discordSessionSeeded.collectAsState(initial = false)
        val discordRiskAccepted by repo.discordRiskAccepted.collectAsState(initial = false)
        val discordStatus by DiscordRpcState.statusFlow.collectAsState()
        val discordFailureMsg by DiscordRpcState.failureMessageFlow.collectAsState()
        var showDiscordLogin by remember { mutableStateOf(false) }
        var showRiskConsent by remember { mutableStateOf(false) }
        SectionCard(
            title = "Discord Rich Presence",
            subtitle = "Show VRChat activity on your Discord profile."
        ) {
            Text(
                "Uses a hidden Discord web session to set your activity. " +
                "Sign in below to connect your Discord account.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))

            if (discordSeeded) {
                // Status indicator
                val (statusColor, statusLabel) = when (discordStatus) {
                    DiscordRpcStatus.CONNECTED -> MaterialTheme.colorScheme.primary to "Connected"
                    DiscordRpcStatus.CONNECTING -> MaterialTheme.colorScheme.tertiary to "Connecting..."
                    DiscordRpcStatus.RECONNECTING -> MaterialTheme.colorScheme.tertiary to "Reconnecting..."
                    DiscordRpcStatus.SESSION_EXPIRED -> MaterialTheme.colorScheme.error to "Session Expired"
                    DiscordRpcStatus.FAILED -> MaterialTheme.colorScheme.error to "Failed"
                    DiscordRpcStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant to "Idle"
                }
                Card(colors = CardDefaults.cardColors(
                    containerColor = if (discordStatus == DiscordRpcStatus.SESSION_EXPIRED || discordStatus == DiscordRpcStatus.FAILED)
                        MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.primaryContainer
                )) {
                    Column(Modifier.padding(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Canvas(Modifier.size(8.dp)) {
                                drawCircle(color = statusColor)
                            }
                            Text(statusLabel, style = MaterialTheme.typography.bodySmall,
                                color = statusColor)
                        }
                        if (discordFailureMsg != null) {
                            Text(discordFailureMsg!!, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }

                // Re-login button when session expired
                if (discordStatus == DiscordRpcStatus.SESSION_EXPIRED) {
                    Button(onClick = { showDiscordLogin = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )) {
                        Text("Sign in again")
                    }
                }

                ToggleRow("Enable Discord RPC", discordEnabled) { enabled ->
                    if (enabled && !discordRiskAccepted) {
                        showRiskConsent = true
                    } else {
                        scope.launch {
                            repo.saveDiscordRpcEnabled(enabled)
                            val svcIntent = Intent(ctx, DiscordRpcService::class.java)
                            if (enabled) {
                                svcIntent.action = DiscordRpcService.ACTION_START
                                ctx.startForegroundService(svcIntent)
                            } else {
                                svcIntent.action = DiscordRpcService.ACTION_STOP
                                ctx.startService(svcIntent)
                            }
                        }
                    }
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        repo.saveDiscordRpcEnabled(false)
                        repo.saveDiscordSessionSeeded(false)
                        android.webkit.CookieManager.getInstance().removeAllCookies(null)
                        val svcIntent = Intent(ctx, DiscordRpcService::class.java)
                        svcIntent.action = DiscordRpcService.ACTION_STOP
                        ctx.startService(svcIntent)
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Disconnect Discord")
                }
            } else {
                Button(
                    onClick = {
                        if (!discordRiskAccepted) {
                            showRiskConsent = true
                        } else {
                            showDiscordLogin = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Sign in to Discord") }
            }
            Text(
                "Your session is stored securely on-device only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Risk consent dialog
        if (showRiskConsent) {
            var riskChecked by remember { mutableStateOf(false) }
            var confirmEnabled by remember { mutableStateOf(false) }
            LaunchedEffect(riskChecked) {
                if (riskChecked) {
                    confirmEnabled = false
                    delay(4000)
                    confirmEnabled = true
                } else {
                    confirmEnabled = false
                }
            }
            AlertDialog(
                onDismissRequest = { showRiskConsent = false },
                title = { Text("Discord Rich Presence") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "This feature runs a hidden Discord web session on your device to show " +
                            "VRChat activity on your Discord profile.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Please be aware:",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            "• A background Discord web session will be active while enabled\n" +
                            "• This uses additional battery and data\n" +
                            "• Your Discord session cookies are stored on-device only\n" +
                            "• While unlikely, Discord could flag unusual client behavior\n" +
                            "• Disconnecting clears your Discord session — Discord may also " +
                            "invalidate your sessions on other devices when it detects an " +
                            "unauthorized client, logging you out everywhere\n" +
                            "• You can disable this at any time from settings",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { riskChecked = !riskChecked }) {
                            Checkbox(checked = riskChecked, onCheckedChange = { riskChecked = it })
                            Text("I understand and accept these risks",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            scope.launch {
                                repo.saveDiscordRiskAccepted(true)
                                showRiskConsent = false
                                if (discordSeeded) {
                                    repo.saveDiscordRpcEnabled(true)
                                    val svcIntent = Intent(ctx, DiscordRpcService::class.java)
                                    svcIntent.action = DiscordRpcService.ACTION_START
                                    ctx.startForegroundService(svcIntent)
                                } else {
                                    showDiscordLogin = true
                                }
                            }
                        },
                        enabled = confirmEnabled
                    ) {
                        Text(if (confirmEnabled) "Continue" else "Please wait...")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRiskConsent = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Discord login dialog
        if (showDiscordLogin) {
            AlertDialog(
                onDismissRequest = { showDiscordLogin = false },
                confirmButton = {},
                text = {
                    Box(Modifier.fillMaxWidth().height(500.dp)) {
                        DiscordLoginWebView(
                            onLoginComplete = {
                                scope.launch {
                                    repo.saveDiscordSessionSeeded(true)
                                    showDiscordLogin = false
                                }
                            },
                            onDismiss = { showDiscordLogin = false }
                        )
                    }
                }
            )
        }

        // Info card
        ElevatedCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("About", style = MaterialTheme.typography.titleSmall)
                Text(
                    "VRC-A uses VRChat's API for status, notifications, and friend tracking. Discord RPC shows your VRChat activity on your Discord profile via a hidden web session.\n\nYour VRChat password is only used to get a session cookie - it is never stored. Your Discord session stays on-device only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // Sign out confirmation
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Sign out of VRChat?") },
            text = {
                Text("Notifications and presence will stop until you sign back in. The app will require you to sign in again before you can use it.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    VrchatAuthManager.logout(ctx)
                    isLinked.value = false
                    displayName.value = ""
                    // Stop pipeline service
                    ctx.stopService(
                        Intent(ctx, VrchatPipelineService::class.java)
                    )
                }) { Text("Sign out", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("Cancel") }
            }
        )
    }
}

private const val VISIBLE_ALERT_LIMIT = 3

@Composable
private fun InAppAlertCards() {
    val ctx = LocalContext.current
    val alerts by InAppAlertState.alerts.collectAsState()
    if (alerts.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    val visible = if (expanded || alerts.size <= VISIBLE_ALERT_LIMIT) alerts
        else alerts.take(VISIBLE_ALERT_LIMIT)
    val hiddenCount = alerts.size - visible.size

    for (alert in visible) {
        AlertCard(alert = alert, onDismiss = {
            InAppAlertState.dismiss(ctx, alert.id)
        })
    }

    if (hiddenCount > 0) {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "+$hiddenCount more alert${if (hiddenCount > 1) "s" else ""}",
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun AlertCard(alert: com.vrca.vrchat.InAppAlert, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    alert.title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDismiss) {
                    Text("Dismiss", style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                alert.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            if (alert.url != null) {
                Text(
                    text = "Open in VRChat",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(alert.url)))
                    }
                )
            }
        }
    }
}
