package com.vrca.ui.conversation

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
import com.vrca.ui.viewmodel.VrcaViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Returns true if the input is a syntactically valid IPv4 dotted-quad
 * (each octet 0-255). Hostnames are rejected because the OSC layer can't
 * resolve them on the LAN without DNS. Empty input is invalid.
 */
private fun isValidIpv4(input: String): Boolean {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return false
    val parts = trimmed.split(".")
    if (parts.size != 4) return false
    return parts.all { p ->
        if (p.isEmpty() || p.length > 3) return@all false
        val n = p.toIntOrNull() ?: return@all false
        n in 0..255
    }
}

@Composable
fun IpField(
    chatboxViewModel: VrcaViewModel,
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

    // One-shot legacy migration: copy the old single-IP key into slot 1 for
    // users upgrading from the pre-slots build.
    //
    // This MUST be a one-shot reading PERSISTED values — never a reactive
    // LaunchedEffect on the (slot-resolving) ipAddress flow. The legacy `IP`
    // key is kept in lock-step with the ACTIVE slot's address by
    // saveActiveIpSlot/saveIpSlotAddress, so `repo.ipAddress` resolves to
    // whatever slot is equipped. The old reactive migration therefore fired on
    // every tab re-entry and, for anyone on slot 2/3 with an empty slot 1,
    // wrote the ACTIVE slot's IP into slot 1 — the persistent "IP cloning" bug
    // (slot 1 silently mirrors slot 2). The `ipSlot1Migrated` flag makes it run
    // at most once, and `slot == 1` guarantees a non-slot-1 address can never be
    // copied into slot 1 (a genuine legacy upgrader always defaults to slot 1).
    LaunchedEffect(Unit) {
        if (repo.ipSlot1Migrated.first()) return@LaunchedEffect
        val slot = repo.activeIpSlot.first()
        val slot1 = repo.ip1Address.first()
        val legacy = repo.ipAddress.first()
        if (slot == 1 && slot1.isBlank() && legacy.isNotBlank() && legacy != "127.0.0.1") {
            repo.saveIpSlotAddress(1, legacy)
        }
        repo.saveIpSlot1Migrated(true)
    }

    // editBuffer keyed directly to currentSlot + the DataStore address for that slot.
    // It reflects ONLY the selected slot's saved address (blank if unset). It must
    // NOT fall back to uiState.ipAddress (the last-applied RUNTIME ip, which belongs
    // to whatever slot was last equipped): during a tab re-entry currentSlot briefly
    // defaults to 1 while the slot's DataStore value is still the placeholder "",
    // and that fallback would fill the field with a DIFFERENT slot's ip — which an
    // Apply then writes into the wrong slot (the "slot 2 overrides slot 1" bug). The
    // legitimate slot-1 migration is handled separately above by writing to DataStore.
    val activeAddress = dsAddrForSlot(currentSlot)

    var editBuffer by remember { mutableStateOf(activeAddress) }
    // Track which slot the editBuffer was last set from, so we only update it
    // when the slot genuinely changes or DataStore emits a NEW value for the
    // same slot — never when a stale emission from the old slot arrives.
    var editBufferSlot by remember { mutableIntStateOf(currentSlot) }
    var editBufferAddr by remember { mutableStateOf(activeAddress) }
    LaunchedEffect(currentSlot, activeAddress) {
        if (currentSlot != editBufferSlot) {
            editBuffer = activeAddress
            editBufferSlot = currentSlot
            editBufferAddr = activeAddress
        } else if (activeAddress != editBufferAddr) {
            editBuffer = activeAddress
            editBufferAddr = activeAddress
        }
    }

    var expanded by remember { mutableStateOf(false) }

    // Per-slot edit buffers for the expanded slot editor.
    // Buffers seed from the DataStore value and follow it ONLY when the user
    // hasn't typed something different. We track the previous flow value so
    // we can tell "flow changed from X→Y, buffer still equals X" (safe to
    // adopt Y) apart from "flow changed but buffer holds user's edit"
    // (don't clobber). This avoids both the "stale Home shown after DataStore
    // load" race and the "user's typing overwritten by debounced echo" race.
    var n1 by remember { mutableStateOf(ip1Name) }
    var a1 by remember { mutableStateOf(ip1Address) }
    var n2 by remember { mutableStateOf(ip2Name) }
    var a2 by remember { mutableStateOf(ip2Address) }
    var n3 by remember { mutableStateOf(ip3Name) }
    var a3 by remember { mutableStateOf(ip3Address) }

    var prevIp1Name    by remember { mutableStateOf(ip1Name) }
    var prevIp1Address by remember { mutableStateOf(ip1Address) }
    var prevIp2Name    by remember { mutableStateOf(ip2Name) }
    var prevIp2Address by remember { mutableStateOf(ip2Address) }
    var prevIp3Name    by remember { mutableStateOf(ip3Name) }
    var prevIp3Address by remember { mutableStateOf(ip3Address) }

    // Per-slot "dirty" flags — set true ONLY when the user actually types in
    // that slot's editor field. The auto-save debounce below persists a slot
    // ONLY if it is dirty, so a buffer that merely SYNCED from DataStore (via
    // the follow effects above) is never written back. Without this, the
    // debounce runs on every fresh composition (e.g. returning to the Home
    // tab) and a follow-vs-debounce race could persist a buffer that doesn't
    // reflect a real edit — overwriting a populated slot 1 with another slot's
    // value (the "slot contents clone on tab re-entry" bug). The follow effects
    // deliberately do NOT set these flags.
    var dirty1 by remember { mutableStateOf(false) }
    var dirty2 by remember { mutableStateOf(false) }
    var dirty3 by remember { mutableStateOf(false) }

    LaunchedEffect(ip1Name)    { if (n1 == prevIp1Name)    n1 = ip1Name;    prevIp1Name    = ip1Name }
    LaunchedEffect(ip1Address) { if (a1 == prevIp1Address) a1 = ip1Address; prevIp1Address = ip1Address }
    LaunchedEffect(ip2Name)    { if (n2 == prevIp2Name)    n2 = ip2Name;    prevIp2Name    = ip2Name }
    LaunchedEffect(ip2Address) { if (a2 == prevIp2Address) a2 = ip2Address; prevIp2Address = ip2Address }
    LaunchedEffect(ip3Name)    { if (n3 == prevIp3Name)    n3 = ip3Name;    prevIp3Name    = ip3Name }
    LaunchedEffect(ip3Address) { if (a3 == prevIp3Address) a3 = ip3Address; prevIp3Address = ip3Address }

    // Auto-save edits with a 400ms debounce so the user never has to click
    // an explicit Save button — typing alone persists the change.
    LaunchedEffect(n1, a1) {
        if (!dirty1) return@LaunchedEffect
        delay(400)
        val n = n1.trim().ifBlank { "Slot 1" }
        if (n != ip1Name || a1 != ip1Address) repo.saveIpSlot(1, n, a1)
    }
    LaunchedEffect(n2, a2) {
        if (!dirty2) return@LaunchedEffect
        delay(400)
        val n = n2.trim().ifBlank { "Slot 2" }
        if (n != ip2Name || a2 != ip2Address) repo.saveIpSlot(2, n, a2)
    }
    LaunchedEffect(n3, a3) {
        if (!dirty3) return@LaunchedEffect
        delay(400)
        val n = n3.trim().ifBlank { "Slot 3" }
        if (n != ip3Name || a3 != ip3Address) repo.saveIpSlot(3, n, a3)
    }

    var invalidIpWarning by remember { mutableStateOf<String?>(null) }

    fun applyActiveSlot(addr: String) {
        val trimmed = addr.trim()
        if (trimmed.isBlank()) {
            invalidIpWarning = "Enter an IP address first."
            return
        }
        if (!isValidIpv4(trimmed)) {
            invalidIpWarning = "Not a valid IPv4 address (expected 0-255.0-255.0-255.0-255)."
            return
        }
        invalidIpWarning = null
        val slot = currentSlot
        // Address-only save — never touch the slot name. The slot name is owned
        // exclusively by the auto-save debounce in the expanded slot editor.
        // Reading nameForSlot(slot) here would risk overwriting the saved name
        // with the collectAsState placeholder default if DataStore hasn't loaded.
        scope.launch {
            repo.saveIpSlotAddress(slot, trimmed)
        }
        chatboxViewModel.ipAddressApplyRuntimeOnly(trimmed)
        focusManager.clearFocus()
    }

    fun switchToSlot(slot: Int, overrideAddr: String? = null) {
        val addr = (overrideAddr ?: dsAddrForSlot(slot)).trim()
        if (addr.isBlank()) {
            invalidIpWarning = "That slot is empty — set an IP first."
            return
        }
        if (!isValidIpv4(addr)) {
            invalidIpWarning = "Slot $slot has an invalid IP — fix it before equipping."
            return
        }
        invalidIpWarning = null
        currentSlot = slot
        editBuffer = addr
        scope.launch { repo.saveActiveIpSlot(slot) }
        chatboxViewModel.ipAddressApplyRuntimeOnly(addr)
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
                    onValueChange = {
                        editBuffer = it
                        if (invalidIpWarning != null) invalidIpWarning = null
                    },
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

            // Inline warning for invalid IP attempts
            invalidIpWarning?.let { msg ->
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
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
                    Text("Saved slots (auto-saves as you type)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)

                    listOf(
                        Triple(1, n1, a1),
                        Triple(2, n2, a2),
                        Triple(3, n3, a3)
                    ).forEach { (slot, nameVal, addrVal) ->
                        val addrTrimmed = addrVal.trim()
                        val addrLooksValid = addrTrimmed.isBlank() || isValidIpv4(addrTrimmed)
                        ElevatedCard {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = nameVal,
                                        onValueChange = { v ->
                                            when (slot) {
                                                1 -> { n1 = v; dirty1 = true }
                                                2 -> { n2 = v; dirty2 = true }
                                                3 -> { n3 = v; dirty3 = true }
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        label = { Text("Name") }
                                    )
                                    OutlinedTextField(
                                        value = addrVal,
                                        onValueChange = { v ->
                                            when (slot) {
                                                1 -> { a1 = v; dirty1 = true }
                                                2 -> { a2 = v; dirty2 = true }
                                                3 -> { a3 = v; dirty3 = true }
                                            }
                                            if (invalidIpWarning != null) invalidIpWarning = null
                                        },
                                        modifier = Modifier.weight(2f),
                                        singleLine = true,
                                        label = { Text("IP Address") },
                                        placeholder = { Text("192.168.1.x") },
                                        isError = !addrLooksValid,
                                        supportingText = if (!addrLooksValid) {
                                            { Text("Invalid IPv4", style = MaterialTheme.typography.bodySmall) }
                                        } else null,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                                    )
                                }
                                Button(
                                    onClick = { switchToSlot(slot, addrTrimmed) },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = addrTrimmed.isNotBlank() && isValidIpv4(addrTrimmed)
                                ) {
                                    Icon(Icons.Filled.Check, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Use this slot", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
