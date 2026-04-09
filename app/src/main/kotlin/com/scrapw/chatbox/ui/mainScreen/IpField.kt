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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.scrapw.chatbox.ui.ChatboxViewModel
import kotlinx.coroutines.launch

/**
 * Multi-slot IP field.
 *
 * Users can save up to 3 named IP addresses (Home, Hotspot, Other by default).
 * The active slot is shown collapsed with a one-tap switch between saved slots.
 * The IP field always shows the current value â€” it never goes blank.
 */
@Composable
fun IpField(
    chatboxViewModel: ChatboxViewModel,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val repo = chatboxViewModel.userPreferencesRepository
    val focusManager = LocalFocusManager.current

    val activeSlot by repo.activeIpSlot.collectAsState(initial = 1)
    val ip1Name    by repo.ip1Name.collectAsState(initial = "Home")
    val ip1Address by repo.ip1Address.collectAsState(initial = "")
    val ip2Name    by repo.ip2Name.collectAsState(initial = "Hotspot")
    val ip2Address by repo.ip2Address.collectAsState(initial = "")
    val ip3Name    by repo.ip3Name.collectAsState(initial = "Other")
    val ip3Address by repo.ip3Address.collectAsState(initial = "")

    // Names and addresses as parallel lists for easy iteration
    val names    = listOf(ip1Name, ip2Name, ip3Name)
    val addresses = listOf(ip1Address, ip2Address, ip3Address)

    // Active address (never blank â€” falls back to 127.0.0.1 only when all slots empty)
    val activeAddress = addresses.getOrElse(activeSlot - 1) { "" }.ifBlank { "127.0.0.1" }

    // Local edit buffer for the active slot's address â€” always pre-filled
    var editBuffer by rememberSaveable(activeSlot, activeAddress) { mutableStateOf(activeAddress) }

    // Keep buffer in sync when slot switches or external save happens
    LaunchedEffect(activeAddress) {
        if (editBuffer.isBlank()) editBuffer = activeAddress
    }

    var expanded by rememberSaveable { mutableStateOf(false) }

    // Per-slot edit state for the expanded view
    val slotNameBuffers = remember(ip1Name, ip2Name, ip3Name) {
        mutableStateListOf(ip1Name, ip2Name, ip3Name)
    }
    val slotAddrBuffers = remember(ip1Address, ip2Address, ip3Address) {
        mutableStateListOf(
            ip1Address.ifBlank { "" },
            ip2Address.ifBlank { "" },
            ip3Address.ifBlank { "" }
        )
    }

    fun applySlot(slot: Int) {
        val addr = slotAddrBuffers.getOrElse(slot - 1) { "" }.trim()
        if (addr.isBlank()) return
        scope.launch { repo.saveActiveIpSlot(slot) }
        chatboxViewModel.ipAddressApply(addr)
        focusManager.clearFocus()
    }

    fun saveSlot(slot: Int) {
        val idx = slot - 1
        val name = slotNameBuffers.getOrElse(idx) { "Slot $slot" }.trim().ifBlank { "Slot $slot" }
        val addr = slotAddrBuffers.getOrElse(idx) { "" }.trim()
        scope.launch { repo.saveIpSlot(slot, name, addr) }
        if (slot == activeSlot && addr.isNotBlank()) {
            chatboxViewModel.ipAddressApply(addr)
        }
    }

    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header row with collapse toggle
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("OSC Host", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${names.getOrElse(activeSlot - 1) { "Slot $activeSlot" }}  Â·  $activeAddress",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand slots"
                    )
                }
            }

            // Active slot quick-edit (always shown)
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
                    label = { Text(names.getOrElse(activeSlot - 1) { "IP" }) },
                    placeholder = { Text("192.168.1.x") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        focusManager.clearFocus()
                        val trimmed = editBuffer.trim()
                        if (trimmed.isNotBlank()) {
                            scope.launch { repo.saveIpSlot(activeSlot, names.getOrElse(activeSlot - 1) { "Slot $activeSlot" }, trimmed) }
                            chatboxViewModel.ipAddressApply(trimmed)
                        }
                    })
                )
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        val trimmed = editBuffer.trim()
                        if (trimmed.isNotBlank()) {
                            scope.launch { repo.saveIpSlot(activeSlot, names.getOrElse(activeSlot - 1) { "Slot $activeSlot" }, trimmed) }
                            chatboxViewModel.ipAddressApply(trimmed)
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) { Text("Apply") }
            }

            // Slot switcher chips (always shown)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                (1..3).forEach { slot ->
                    val addr = addresses.getOrElse(slot - 1) { "" }
                    val isActive = slot == activeSlot
                    val hasAddr = addr.isNotBlank()
                    FilterChip(
                        selected = isActive,
                        onClick = {
                            if (hasAddr && !isActive) {
                                scope.launch { repo.saveActiveIpSlot(slot) }
                                chatboxViewModel.ipAddressApply(addr)
                            }
                        },
                        label = {
                            Text(
                                names.getOrElse(slot - 1) { "Slot $slot" },
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        enabled = hasAddr || isActive,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Expanded: edit all 3 slots
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Divider()
                    Text(
                        "Saved slots",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    (1..3).forEach { slot ->
                        val idx = slot - 1
                        ElevatedCard {
                            Column(
                                Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = slotNameBuffers.getOrElse(idx) { "Slot $slot" },
                                        onValueChange = { slotNameBuffers[idx] = it },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        label = { Text("Name") }
                                    )
                                    OutlinedTextField(
                                        value = slotAddrBuffers.getOrElse(idx) { "" },
                                        onValueChange = { slotAddrBuffers[idx] = it },
                                        modifier = Modifier.weight(2f),
                                        singleLine = true,
                                        label = { Text("IP Address") },
                                        placeholder = { Text("192.168.1.x") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                                    )
                                }
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { saveSlot(slot) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Filled.Edit, contentDescription = null,
                                            modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Save", style = MaterialTheme.typography.labelMedium)
                                    }
                                    Button(
                                        onClick = { applySlot(slot) },
                                        modifier = Modifier.weight(1f),
                                        enabled = slotAddrBuffers.getOrElse(idx) { "" }.isNotBlank()
                                    ) {
                                        Icon(Icons.Filled.Check, contentDescription = null,
                                            modifier = Modifier.size(16.dp))
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
