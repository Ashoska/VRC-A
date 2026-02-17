// app/src/main/kotlin/com/scrapw/chatbox/ui/admin/AdminScreen.kt
package com.scrapw.chatbox.ui.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun AdminScreen(
    vm: AdminViewModel = viewModel(factory = AdminViewModel.Factory)
) {
    val state by vm.ui.collectAsState()

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    var targetUserId by remember { mutableStateOf("") }
    var action by remember { mutableStateOf(AdminAction.Warn) }
    var reason by remember { mutableStateOf("") }

    // evidence: only offending messages
    var evidenceText by remember { mutableStateOf("") }

    val scroll = rememberScrollState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ElevatedCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Admin Auth", style = MaterialTheme.typography.titleMedium)

                Text(
                    "Signed in: ${state.signedInAs}",
                    style = MaterialTheme.typography.bodySmall
                )

                if (!state.isSignedIn) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Email") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Password") },
                        singleLine = true
                    )

                    Button(
                        onClick = { vm.signIn(email.trim(), password) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Sign in") }
                } else {
                    OutlinedButton(
                        onClick = { vm.signOut() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Sign out") }
                }

                if (state.error.isNotBlank()) {
                    Text(state.error, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        ElevatedCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Create Punishment", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Only store offending messages. Warn/Ban evidence remains until cleared, then expires after 90 days.",
                    style = MaterialTheme.typography.bodySmall
                )

                OutlinedTextField(
                    value = targetUserId,
                    onValueChange = { targetUserId = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Target user ID (VRChat user id / name)") },
                    singleLine = true
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(
                        selected = action == AdminAction.Warn,
                        onClick = { action = AdminAction.Warn },
                        label = { Text("Warn") }
                    )
                    FilterChip(
                        selected = action == AdminAction.Ban,
                        onClick = { action = AdminAction.Ban },
                        label = { Text("Ban") }
                    )
                    FilterChip(
                        selected = action == AdminAction.Note,
                        onClick = { action = AdminAction.Note },
                        label = { Text("Note") }
                    )
                }

                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Reason") }
                )

                OutlinedTextField(
                    value = evidenceText,
                    onValueChange = { evidenceText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    label = { Text("Offending message(s) evidence (one per line)") }
                )

                Button(
                    onClick = {
                        vm.createPunishment(
                            targetUser = targetUserId.trim(),
                            action = action,
                            reason = reason.trim(),
                            evidenceLines = evidenceText.lines().map { it.trim() }.filter { it.isNotBlank() }
                        )
                        evidenceText = ""
                        reason = ""
                    },
                    enabled = state.isSignedIn && targetUserId.trim().isNotBlank() && (action == AdminAction.Note || evidenceText.trim().isNotBlank()),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Create") }

                if (state.lastWriteStatus.isNotBlank()) {
                    Text(state.lastWriteStatus, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        ElevatedCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Evidence Cleanup", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Deletes evidence that has expired (90 days after case cleared).",
                    style = MaterialTheme.typography.bodySmall
                )

                OutlinedButton(
                    onClick = { vm.runEvidenceCleanup() },
                    enabled = state.isSignedIn,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Run cleanup now") }

                if (state.cleanupStatus.isNotBlank()) {
                    Text(state.cleanupStatus, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
