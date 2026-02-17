package com.scrapw.chatbox

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scrapw.chatbox.admin.AdminActionType
import com.scrapw.chatbox.admin.AdminUserStatus
import com.scrapw.chatbox.admin.AdminViewModel
import com.scrapw.chatbox.admin.AdminViewModelFactory
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class AdminTab(val title: String) {
    Users("Users"),
    Cases("Cases"),
    Evidence("Evidence"),
    History("Sent History"),
    About("About")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AdminScreen() {
    if (!BuildConfig.IS_ADMIN_BUILD) {
        // Should never be reachable, but keep safe.
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Text("Admin is only available in the Admin build.", style = MaterialTheme.typography.titleMedium)
        }
        return
    }

    val vm: AdminViewModel = viewModel(factory = AdminViewModelFactory(LocalAppContext.current))
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    var tab by rememberSaveable { mutableStateOf(AdminTab.Users) }

    val ui by vm.ui.collectAsState()

    // Run retention cleanup on open
    LaunchedEffect(Unit) {
        vm.runRetentionCleanup()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.Gavel, contentDescription = null)
                    Column(Modifier.weight(1f)) {
                        Text("Admin Tools", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Local-only (no Firebase yet). Evidence is stored only when linked to a case.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = { vm.runRetentionCleanup() }) {
                        Text("Cleanup")
                    }
                }

                TabRow(selectedTabIndex = tab.ordinal) {
                    AdminTab.entries.forEachIndexed { idx, t ->
                        Tab(
                            selected = idx == tab.ordinal,
                            onClick = { tab = t },
                            text = { Text(t.title) }
                        )
                    }
                }
            }
        }

        when (tab) {
            AdminTab.Users -> AdminUsersTab(
                ui = ui,
                onAddUser = { id, name -> vm.addOrUpdateUser(id, name) },
                onSelectUser = { vm.selectUser(it) },
                onWarn = { userId, reason, days ->
                    vm.createAction(userId, AdminActionType.WARN, reason, expiresDays = days)
                },
                onBan = { userId, reason ->
                    vm.createAction(userId, AdminActionType.BAN, reason, expiresDays = null)
                },
                onUnban = { userId -> vm.unbanUser(userId) },
                onClearWarning = { userId -> vm.clearWarning(userId) },
                onNote = { userId, note -> vm.createAction(userId, AdminActionType.NOTE, note, expiresDays = null) }
            )

            AdminTab.Cases -> AdminCasesTab(
                ui = ui,
                onSelectAction = { vm.selectAction(it) },
                onDeleteAction = { vm.deleteAction(it) }
            )

            AdminTab.Evidence -> AdminEvidenceTab(
                ui = ui,
                onSelectAction = { vm.selectAction(it) },
                onAttachEvidence = { actionId, text -> vm.attachEvidence(actionId, text) },
                onDeleteEvidence = { evidenceId -> vm.deleteEvidence(evidenceId) }
            )

            AdminTab.History -> AdminHistoryTab(
                ui = ui,
                onCopyToEvidence = { msg ->
                    // Put into the evidence composer text
                    vm.setEvidenceDraft(msg)
                    scope.launch { snackbarHost.showSnackbar("Copied to evidence draft.") }
                },
                onClearHistory = { vm.clearSentHistory() }
            )

            AdminTab.About -> AdminAboutTab()
        }
    }
}

/* =========================
   USERS TAB
   ========================= */

@Composable
private fun AdminUsersTab(
    ui: com.scrapw.chatbox.admin.AdminUiState,
    onAddUser: (id: String, name: String) -> Unit,
    onSelectUser: (userId: String) -> Unit,
    onWarn: (userId: String, reason: String, days: Int) -> Unit,
    onBan: (userId: String, reason: String) -> Unit,
    onUnban: (userId: String) -> Unit,
    onClearWarning: (userId: String) -> Unit,
    onNote: (userId: String, note: String) -> Unit
) {
    var search by rememberSaveable { mutableStateOf("") }

    var newId by rememberSaveable { mutableStateOf("") }
    var newName by rememberSaveable { mutableStateOf("") }

    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var sheetUserId by rememberSaveable { mutableStateOf("") }
    var actionReason by rememberSaveable { mutableStateOf("") }
    var warnDaysText by rememberSaveable { mutableStateOf("30") }
    var noteText by rememberSaveable { mutableStateOf("") }

    val filtered = ui.users
        .filter {
            val q = search.trim().lowercase()
            if (q.isBlank()) true
            else it.userId.lowercase().contains(q) || it.displayName.lowercase().contains(q)
        }
        .sortedWith(compareByDescending<com.scrapw.chatbox.admin.AdminUser> { it.status == AdminUserStatus.BANNED }
            .thenByDescending { it.status == AdminUserStatus.WARNED }
            .thenBy { it.displayName.lowercase() })

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ElevatedCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Search, contentDescription = null)
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Search users") }
                    )
                }

                Divider()

                Text("Add / Update user", style = MaterialTheme.typography.titleSmall)

                OutlinedTextField(
                    value = newId,
                    onValueChange = { newId = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("User ID (required)") },
                    placeholder = { Text("e.g. usr_123 or VRChat user id") }
                )

                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Display name") }
                )

                Button(
                    onClick = {
                        val id = newId.trim()
                        if (id.isNotBlank()) {
                            onAddUser(id, newName.trim())
                            newId = ""
                            newName = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.padding(4.dp))
                    Text("Save user")
                }
            }
        }

        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Users (${filtered.size})", style = MaterialTheme.typography.titleMedium)
                if (filtered.isEmpty()) {
                    Text("No users yet.", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(modifier = Modifier.height(360.dp)) {
                        items(filtered, key = { it.userId }) { u ->
                            val badge = when (u.status) {
                                AdminUserStatus.BANNED -> "BANNED"
                                AdminUserStatus.WARNED -> "WARNED"
                                else -> "OK"
                            }
                            val badgeColor = when (u.status) {
                                AdminUserStatus.BANNED -> MaterialTheme.colorScheme.error
                                AdminUserStatus.WARNED -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.primary
                            }

                            ElevatedCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                                    .clickable {
                                        onSelectUser(u.userId)
                                        sheetUserId = u.userId
                                        sheetOpen = true
                                    }
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Person, contentDescription = null)
                                        Spacer(Modifier.padding(4.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(u.displayName.ifBlank { "(no name)" }, style = MaterialTheme.typography.titleSmall)
                                            Text(u.userId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text(
                                            badge,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = badgeColor
                                        )
                                    }

                                    if (u.status == AdminUserStatus.WARNED && u.warnExpiresAtMs > 0L) {
                                        Text(
                                            "Warn expires: ${fmtDate(u.warnExpiresAtMs)}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (sheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = sheetState
        ) {
            val u = ui.users.firstOrNull { it.userId == sheetUserId }

            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("User actions", style = MaterialTheme.typography.titleLarge)

                if (u == null) {
                    Text("User not found.", style = MaterialTheme.typography.bodySmall)
                } else {
                    ElevatedCard {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(u.displayName.ifBlank { "(no name)" }, style = MaterialTheme.typography.titleMedium)
                            Text(u.userId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Status: ${u.status.name}", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Warn", style = MaterialTheme.typography.titleSmall)

                            OutlinedTextField(
                                value = actionReason,
                                onValueChange = { actionReason = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Reason") }
                            )
                            OutlinedTextField(
                                value = warnDaysText,
                                onValueChange = { warnDaysText = it.filter { ch -> ch.isDigit() }.take(3) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Days (suggest 90, or 30 for light)")} ,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true
                            )

                            Button(
                                onClick = {
                                    val days = warnDaysText.toIntOrNull()?.coerceIn(1, 365) ?: 30
                                    onWarn(u.userId, actionReason.trim().ifBlank { "Warn" }, days)
                                    actionReason = ""
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.Report, contentDescription = null)
                                Spacer(Modifier.padding(4.dp))
                                Text("Apply warn")
                            }

                            Divider()

                            Text("Ban", style = MaterialTheme.typography.titleSmall)

                            OutlinedTextField(
                                value = actionReason,
                                onValueChange = { actionReason = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Reason") }
                            )

                            Button(
                                onClick = {
                                    onBan(u.userId, actionReason.trim().ifBlank { "Ban" })
                                    actionReason = ""
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.Block, contentDescription = null)
                                Spacer(Modifier.padding(4.dp))
                                Text("Ban (permanent until unbanned)")
                            }

                            Divider()

                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { onClearWarning(u.userId) },
                                    modifier = Modifier.weight(1f),
                                    enabled = u.status == AdminUserStatus.WARNED
                                ) {
                                    Icon(Icons.Filled.Clear, contentDescription = null)
                                    Spacer(Modifier.padding(4.dp))
                                    Text("Clear warn")
                                }
                                OutlinedButton(
                                    onClick = { onUnban(u.userId) },
                                    modifier = Modifier.weight(1f),
                                    enabled = u.status == AdminUserStatus.BANNED
                                ) {
                                    Icon(Icons.Filled.Check, contentDescription = null)
                                    Spacer(Modifier.padding(4.dp))
                                    Text("Unban")
                                }
                            }
                        }
                    }

                    ElevatedCard {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Note", style = MaterialTheme.typography.titleSmall)
                            OutlinedTextField(
                                value = noteText,
                                onValueChange = { noteText = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Internal note") }
                            )
                            Button(
                                onClick = {
                                    val n = noteText.trim()
                                    if (n.isNotBlank()) {
                                        onNote(u.userId, n)
                                        noteText = ""
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Add note")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = { sheetOpen = false }, modifier = Modifier.fillMaxWidth()) { Text("Close") }
            }
        }
    }
}

/* =========================
   CASES TAB
   ========================= */

@Composable
private fun AdminCasesTab(
    ui: com.scrapw.chatbox.admin.AdminUiState,
    onSelectAction: (actionId: String) -> Unit,
    onDeleteAction: (actionId: String) -> Unit
) {
    val actions = ui.actions.sortedByDescending { it.createdAtMs }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cases / Punishments (${actions.size})", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Evidence is attached per case. Retention: 90 days, but warned/banned users keep cases until cleared.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (actions.isEmpty()) {
            ElevatedCard { Column(Modifier.padding(12.dp)) { Text("No actions yet.") } }
            return
        }

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(actions, key = { it.actionId }) { a ->
                val user = ui.users.firstOrNull { it.userId == a.userId }
                val evidCount = ui.evidence.count { it.actionId == a.actionId }

                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clickable { onSelectAction(a.actionId) }
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.Gavel, contentDescription = null)
                            Column(Modifier.weight(1f)) {
                                Text("${a.type.name} — ${user?.displayName?.ifBlank { a.userId } ?: a.userId}", style = MaterialTheme.typography.titleSmall)
                                Text(fmtDate(a.createdAtMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("Evidence: $evidCount", style = MaterialTheme.typography.labelMedium)
                        }
                        Text(a.reason, style = MaterialTheme.typography.bodyMedium)

                        if (a.expiresAtMs > 0L) {
                            Text("Expires: ${fmtDate(a.expiresAtMs)}", style = MaterialTheme.typography.bodySmall)
                        } else if (a.type == AdminActionType.BAN) {
                            Text("Permanent until unbanned", style = MaterialTheme.typography.bodySmall)
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { onSelectAction(a.actionId) }, modifier = Modifier.weight(1f)) {
                                Text("Open")
                            }
                            OutlinedButton(
                                onClick = { onDeleteAction(a.actionId) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = null)
                                Spacer(Modifier.padding(4.dp))
                                Text("Delete")
                            }
                        }
                    }
                }
            }
        }
    }
}

/* =========================
   EVIDENCE TAB
   ========================= */

@Composable
private fun AdminEvidenceTab(
    ui: com.scrapw.chatbox.admin.AdminUiState,
    onSelectAction: (actionId: String) -> Unit,
    onAttachEvidence: (actionId: String, text: String) -> Unit,
    onDeleteEvidence: (evidenceId: String) -> Unit
) {
    var selectedActionId by rememberSaveable { mutableStateOf(ui.selectedActionId) }
    LaunchedEffect(ui.selectedActionId) { selectedActionId = ui.selectedActionId }

    var draft by rememberSaveable { mutableStateOf(ui.evidenceDraft) }
    LaunchedEffect(ui.evidenceDraft) { draft = ui.evidenceDraft }

    val actions = ui.actions.sortedByDescending { it.createdAtMs }
    val selected = actions.firstOrNull { it.actionId == selectedActionId }
    val selectedEvidence = ui.evidence.filter { it.actionId == selectedActionId }.sortedByDescending { it.createdAtMs }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Evidence", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Only offending messages are stored here — evidence must be attached to a case.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        ElevatedCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Select case", style = MaterialTheme.typography.titleSmall)

                if (actions.isEmpty()) {
                    Text("Create a warn/ban/note first.", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(modifier = Modifier.height(220.dp)) {
                        items(actions, key = { it.actionId }) { a ->
                            val user = ui.users.firstOrNull { it.userId == a.userId }
                            val isSel = a.actionId == selectedActionId
                            ElevatedCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                                    .clickable {
                                        selectedActionId = a.actionId
                                        onSelectAction(a.actionId)
                                    },
                                colors = CardDefaults.elevatedCardColors(
                                    containerColor = if (isSel) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("${a.type.name} — ${user?.displayName?.ifBlank { a.userId } ?: a.userId}", style = MaterialTheme.typography.titleSmall)
                                    Text(fmtDate(a.createdAtMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(a.reason, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }

        ElevatedCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Attach evidence", style = MaterialTheme.typography.titleSmall)

                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    label = { Text("Offending message / evidence text") }
                )

                Button(
                    onClick = {
                        val actionId = selectedActionId
                        val txt = draft.trim()
                        if (actionId.isNotBlank() && txt.isNotBlank()) {
                            onAttachEvidence(actionId, txt)
                            draft = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selectedActionId.isNotBlank()
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.padding(4.dp))
                    Text("Attach to selected case")
                }

                if (selected == null) {
                    Text("No case selected.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Selected: ${selected.type.name} — ${selected.userId}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Evidence entries (${selectedEvidence.size})", style = MaterialTheme.typography.titleSmall)
                if (selectedActionId.isBlank()) {
                    Text("Pick a case to view evidence.", style = MaterialTheme.typography.bodySmall)
                } else if (selectedEvidence.isEmpty()) {
                    Text("No evidence yet for this case.", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(modifier = Modifier.height(260.dp)) {
                        items(selectedEvidence, key = { it.evidenceId }) { e ->
                            ElevatedCard(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(fmtDate(e.createdAtMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(e.text, style = MaterialTheme.typography.bodyMedium)
                                    OutlinedButton(
                                        onClick = { onDeleteEvidence(e.evidenceId) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Filled.Delete, contentDescription = null)
                                        Spacer(Modifier.padding(4.dp))
                                        Text("Delete evidence")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/* =========================
   HISTORY TAB (Admin build only)
   ========================= */

@Composable
private fun AdminHistoryTab(
    ui: com.scrapw.chatbox.admin.AdminUiState,
    onCopyToEvidence: (String) -> Unit,
    onClearHistory: () -> Unit
) {
    val history = ui.sentHistory.sortedByDescending { it.createdAtMs }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.History, contentDescription = null)
                    Column(Modifier.weight(1f)) {
                        Text("Sent History (${history.size})", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Admin build logs recent outgoing sends so you can copy offenders into Evidence.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = onClearHistory) { Text("Clear") }
                }
            }
        }

        if (history.isEmpty()) {
            ElevatedCard { Column(Modifier.padding(12.dp)) { Text("No history yet.") } }
            return
        }

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(history, key = { it.entryId }) { h ->
                ElevatedCard(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(fmtDate(h.createdAtMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(h.text, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { onCopyToEvidence(h.text) }, modifier = Modifier.weight(1f)) {
                                Text("Copy to Evidence")
                            }
                        }
                    }
                }
            }
        }
    }
}

/* =========================
   ABOUT TAB
   ========================= */

@Composable
private fun AdminAboutTab() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ElevatedCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.Info, contentDescription = null)
                    Text("Admin system (local)", style = MaterialTheme.typography.titleMedium)
                }

                Text(
                    """
What this does NOW (offline):
• Users list: warn/ban/unban/notes
• Cases list: view/delete actions
• Evidence: store only offending messages linked to a case
• Sent History: admin-only recent outgoing messages (for quick evidence copy)

Retention rules:
• Default cleanup window = 90 days
• BUT: if user is WARNED or BANNED, their actions + evidence are kept permanently until cleared/unbanned
                    """.trimIndent(),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/* =========================
   HELPERS
   ========================= */

private fun fmtDate(ms: Long): String {
    return runCatching {
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.UK)
        df.format(Date(ms))
    }.getOrElse { ms.toString() }
}
