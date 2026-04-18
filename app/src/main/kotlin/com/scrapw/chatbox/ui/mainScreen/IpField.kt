package com.scrapw.chatbox.ui.mainScreen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.scrapw.chatbox.ui.ChatboxViewModel
import kotlinx.coroutines.launch

@Composable
fun IpField(
    chatboxViewModel: ChatboxViewModel,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val repo = chatboxViewModel.userPreferencesRepository
    val focusManager = LocalFocusManager.current

    // DataStore-backed state (async)
    val dsActiveSlot by repo.activeIpSlot.collectAsState(initial = 1)
    val ip1Name    by repo.ip1Name.collectAsState(initial = "Home")
    val ip1Address by repo.ip1Address.collectAsState(initial = "")
    val ip2Name    by repo.ip2Name.collectAsState(initial = "Hotspot")
    val ip2Address by repo.ip2Address.collectAsState(initial = "")
    val ip3Name    by repo.ip3Name.collectAsState(initial = "Other")
    val ip3Address by repo.ip3Address.collectAsState(initial = "")

    // Local slot tracking (immediate, not async)
    var currentSlot by remember { mutableIntStateOf(dsActiveSlot) }
    LaunchedEffect(dsActiveSlot) { currentSlot = dsActiveSlot }

    fun nameForSlot(slot: Int): String = when (slot) {
        1 -> ip1Name; 2 -> ip2Name; 3 -> ip3Name; else -> "Slot $slot"
    }

    fun dsAddrForSlot(slot: Int): String = when (slot) {
        1 -> ip1Address; 2 -> ip2Address; 3 -> ip3Address; else -> ""
    }

    // Auto-migrate: if active slot is empty but the ViewModel has a saved IP, populate slot 1
    val uiState by chatboxViewModel.messengerUiState.collectAsState()
    LaunchedEffect(ip1Address, uiState.ipAddress) {
        if (ip1Address.isBlank() && uiState.ipAddress.isNotBlank() && uiState.ipAddress != "127.0.0.1") {
            repo.saveIpSlot(1, "Home", uiState.ipAddress)
        }
    }

    // editBuffer keyed directly to currentSlot + the DataStore address for that slot
    val activeAddress = dsAddrForSlot(currentSlot)
    val resolvedAddress = activeAddress.ifBlank {
        uiState.ipAddress.takeIf { it != "127.0.0.1" } ?: ""
    }

    var editBuffer by remember { mutableStateOf(resolvedAddress) }
    // Re-derive editBuffer when slot changes or the DataStore value for the active slot changes
    LaunchedEffect(currentSlot, activeAddress) {
        editBuffer = activeAddress.ifBlank {
            uiState.ipAddress.takeIf { it != "127.0.0.1" } ?: ""
        }
    }

    var expanded by remember { mutableStateOf(false) }

    // Per-slot edit buffers for the expanded slot editor
    var nameEdit1 by remember(ip1Name) { mutableStateOf(ip1Name) }
    var addrEdit1 by remember(ip1Address) { mutableStateOf(ip1Address) }
    var nameEdit2 by remember(ip2Name) { mutableStateOf(ip2Name) }
    var addrEdit2 by remember(ip2Address) { mutableStateOf(ip2Address) }
    var nameEdit3 by remember(ip3Name) { mutableStateOf(ip3Name) }
    var addrEdit3 by remember(ip3Address) { mutableStateOf(ip3Address) }

    fun applyActiveSlot(addr: String) {
        val trimmed = addr.trim()
        if (trimmed.isBlank()) return
        val slot = currentSlot
        val name = nameForSlot(slot)
        scope.launch {
            repo.saveIpSlot(slot, name, trimmed)
        }
        chatboxViewModel.ipAddressApply(trimmed)
        focusManager.clearFocus()
    }

    fun switchToSlot(slot: Int, overrideAddr: String? = null) {
        val addr = overrideAddr ?: dsAddrForSlot(slot)
        if (addr.isBlank()) return
        currentSlot = slot
        editBuffer = addr
        scope.launch { repo.saveActiveIpSlot(slot) }
        chatboxViewModel.ipAddressApply(addr)
    }

    fun saveSlot(slot: Int, name: String, addr: String) {
        val n = name.trim().ifBlank { "Slot $slot" }
        val a = addr.trim()
        scope.launch { repo.saveIpSlot(slot, n, a) }
        if (slot == currentSlot && a.isNotBlank()) {
            chatboxViewModel.ipAddressApply(a)
            editBuffer = a
        }
    }

    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("OSC Host", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${nameForSlot(currentSlot)}  \u00b7  ${activeAddress.ifBlank { "not set" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Manage slots"
                    )
                }
            }

            // Active slot quick-edit
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = editBuffer,
                    onValueChange = { editBuffer = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text(nameForSlot(currentSlot)) },
                    placeholder = { Text("192.168.1.x") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        focusManager.clearFocus()
                        applyActiveSlot(editBuffer)
                    })
                )
                Button(
                    onClick = { focusManager.clearFocus(); applyActiveSlot(editBuffer) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) { Text("Apply") }
            }

            // Slot switcher chips
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..3).forEach { slot ->
                    val addr = dsAddrForSlot(slot)
                    FilterChip(
                        selected = slot == currentSlot,
                        onClick = { if (slot != currentSlot) switchToSlot(slot) },
                        label = {
                            Text(
                                nameForSlot(slot),
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        enabled = addr.isNotBlank() || slot == currentSlot,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Expanded slot editor
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Divider()
                    Text("Saved slots", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)

                    listOf(
                        Triple(1, nameEdit1, addrEdit1),
                        Triple(2, nameEdit2, addrEdit2),
                        Triple(3, nameEdit3, addrEdit3)
                    ).forEach { (slot, nameVal, addrVal) ->
                        ElevatedCard {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = nameVal,
                                        onValueChange = { v ->
                                            when (slot) { 1 -> nameEdit1 = v; 2 -> nameEdit2 = v; 3 -> nameEdit3 = v }
                                        },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        label = { Text("Name") }
                                    )
                                    OutlinedTextField(
                                        value = addrVal,
                                        onValueChange = { v ->
                                            when (slot) { 1 -> addrEdit1 = v; 2 -> addrEdit2 = v; 3 -> addrEdit3 = v }
                                        },
                                        modifier = Modifier.weight(2f),
                                        singleLine = true,
                                        label = { Text("IP Address") },
                                        placeholder = { Text("192.168.1.x") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                                    )
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { saveSlot(slot, nameVal, addrVal) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Filled.Edit, null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Save", style = MaterialTheme.typography.labelMedium)
                                    }
                                    Button(
                                        onClick = {
                                            saveSlot(slot, nameVal, addrVal)
                                            if (addrVal.trim().isNotBlank()) switchToSlot(slot, addrVal.trim())
                                        },
                                        modifier = Modifier.weight(1f),
                                        enabled = addrVal.trim().isNotBlank()
                                    ) {
                                        Icon(Icons.Filled.Check, null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Use", style = MaterialTheme.typography.labelMedium)
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
