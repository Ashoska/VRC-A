// app/src/main/kotlin/com/scrapw/chatbox/AdminScreen.kt
package com.scrapw.chatbox

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

/**
 * ✅ Device-gated Admin-only screen (NO LOGIN).
 *
 * Admin enable:
 * - devices/{deviceHash} : { adminEnabled: true }
 *
 * Owner enable:
 * - config/security : { ownerDeviceHash: "<hash>" }
 * Owner can toggle adminEnabled for other devices.
 *
 * Moderation:
 * - devices/{targetDeviceHash} : { warned, warnReason, banned, banReason, updatedAt }
 *
 * Announcements:
 * - announcements/{id} : { title, body, active, priority, createdAt, createdByDevice, createdByAppId, writerDeviceHash }
 *
 * ToS:
 * - config/app : { tosVersion, tosText, tosUrl, updatedAt, writerDeviceHash }
 */
@Composable
fun AdminScreen() {
    val ctx = LocalContext.current
    val db = remember { FirebaseFirestore.getInstance() }

    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun setErr(msg: String?) { error = msg?.takeIf { it.isNotBlank() }?.take(4000) }

    // Hard block: should never be reachable on public build, but keep it safe anyway.
    if (!BuildConfig.IS_ADMIN_BUILD) {
        Surface {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Admin", style = MaterialTheme.typography.titleLarge)
                ErrorCard("This page is only available in the Admin build.")
            }
        }
        return
    }

    val deviceHash = remember { readDeviceHash(ctx) }

    // ---------- Admin gate (device-based) ----------
    var adminChecked by remember { mutableStateOf(false) }
    var isAdmin by remember { mutableStateOf(false) }

    // ---------- Owner gate ----------
    var ownerHash by remember { mutableStateOf("") }
    var isOwner by remember { mutableStateOf(false) }

    fun refreshGates() {
        adminChecked = false
        isAdmin = false
        isOwner = false
        ownerHash = ""
        setErr(null)

        if (deviceHash.isBlank()) {
            setErr("DeviceHash is blank. (Couldn't read vrca_remote prefs key: device_id_hash)")
            adminChecked = true
            return
        }

        // Owner hash
        db.collection("config").document("security").get()
            .addOnSuccessListener { snap ->
                ownerHash = snap.getString("ownerDeviceHash")?.trim().orEmpty()
                isOwner = ownerHash.isNotBlank() && ownerHash == deviceHash
            }
            .addOnFailureListener { /* ignore owner if missing */ }

        // Admin flag
        db.collection("devices").document(deviceHash).get()
            .addOnSuccessListener { snap ->
                isAdmin = snap.getBoolean("adminEnabled") ?: false
                adminChecked = true
            }
            .addOnFailureListener { e ->
                setErr(e.message ?: "Failed to check admin status")
                adminChecked = true
            }
    }

    LaunchedEffect(deviceHash) { refreshGates() }

    if (!adminChecked) {
        Surface {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Admin", style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator()
                    Text("Checking permissions…")
                }
                DeviceHashCard(deviceHash)
                if (error != null) ErrorCard(error!!)
            }
        }
        return
    }

    if (!isAdmin) {
        Surface {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Admin", style = MaterialTheme.typography.titleLarge)

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Access denied", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Enable this device in Firestore:\n\n" +
                                "devices/$deviceHash\n" +
                                "{ adminEnabled: true }",
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                DeviceHashCard(deviceHash)

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { refreshGates() }) { Text("Re-check") }
                    if (error != null) OutlinedButton(onClick = { setErr(null) }) { Text("Clear error") }
                }

                if (error != null) ErrorCard(error!!)
            }
        }
        return
    }

    // ---------- Main admin UI ----------
    Surface {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Admin", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Device: $deviceHash",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (ownerHash.isNotBlank()) {
                        Text(
                            "Owner: ${if (isOwner) "YES" else "NO"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "Owner: (not set)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                OutlinedButton(onClick = { refreshGates() }) { Text("Re-check") }
            }

            if (error != null) {
                ErrorCard(error!!)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { setErr(null) }) { Text("Clear error") }
                }
            }

            AdminRulesCard()

            // Owner tools
            if (isOwner) {
                Divider()
                OwnerAdminTools(
                    db = db,
                    writerDeviceHash = deviceHash,
                    setLoading = { loading = it },
                    setError = ::setErr
                )
            }

            Divider()

            // Announcements
            AdminAnnouncementsSection(
                db = db,
                createdByDevice = deviceHash,
                writerDeviceHash = deviceHash,
                setLoading = { loading = it },
                setError = ::setErr
            )

            Divider()

            // Moderation (DEVICE HASH based)
            AdminModerationSection(
                db = db,
                writerDeviceHash = deviceHash,
                setLoading = { loading = it },
                setError = ::setErr
            )

            Divider()

            // ToS / Config
            AdminTosConfigSection(
                db = db,
                writerDeviceHash = deviceHash,
                setLoading = { loading = it },
                setError = ::setErr
            )

            if (loading) {
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }

            Spacer(Modifier.height(30.dp))
        }
    }
}

private fun readDeviceHash(ctx: Context): String {
    val prefs = ctx.getSharedPreferences("vrca_remote", Context.MODE_PRIVATE)
    return prefs.getString("device_id_hash", "")?.trim().orEmpty()
}

/* =========================
   OWNER ADMIN TOOLS
   ========================= */

private data class AdminDeviceRow(
    val deviceHash: String,
    val adminEnabled: Boolean,
    val updatedAt: Timestamp?
)

@Composable
private fun OwnerAdminTools(
    db: FirebaseFirestore,
    writerDeviceHash: String,
    setLoading: (Boolean) -> Unit,
    setError: (String?) -> Unit
) {
    val admins = remember { mutableStateListOf<AdminDeviceRow>() }

    fun refresh() {
        setLoading(true)
        setError(null)
        db.collection("devices")
            .whereEqualTo("adminEnabled", true)
            .limit(50)
            .get()
            .addOnSuccessListener { snap ->
                admins.clear()
                snap.documents.forEach { d ->
                    admins.add(
                        AdminDeviceRow(
                            deviceHash = d.id,
                            adminEnabled = d.getBoolean("adminEnabled") ?: false,
                            updatedAt = d.getTimestamp("updatedAt")
                        )
                    )
                }
                setLoading(false)
            }
            .addOnFailureListener { e ->
                setLoading(false)
                setError(e.message ?: "Failed to load admin devices")
            }
    }

    var targetHash by remember { mutableStateOf("") }
    var setAdminOn by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) { refresh() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Owner: Admin management", style = MaterialTheme.typography.titleMedium)

        ElevatedCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Add / remove admin", style = MaterialTheme.typography.titleSmall)

                OutlinedTextField(
                    value = targetHash,
                    onValueChange = { targetHash = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Target deviceHash") },
                    placeholder = { Text("paste device hash") }
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Set adminEnabled")
                    Spacer(Modifier.width(10.dp))
                    Switch(checked = setAdminOn, onCheckedChange = { setAdminOn = it })
                    Spacer(Modifier.width(10.dp))
                    Text(if (setAdminOn) "true" else "false", fontFamily = FontFamily.Monospace)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            val dh = targetHash.trim()
                            if (dh.isBlank()) return@Button
                            setLoading(true)
                            setError(null)
                            val payload = mapOf(
                                "adminEnabled" to setAdminOn,
                                "updatedAt" to FieldValue.serverTimestamp(),
                                "writerDeviceHash" to writerDeviceHash
                            )
                            db.collection("devices").document(dh)
                                .set(payload, com.google.firebase.firestore.SetOptions.merge())
                                .addOnSuccessListener {
                                    setLoading(false)
                                    refresh()
                                }
                                .addOnFailureListener { e ->
                                    setLoading(false)
                                    setError(e.message ?: "Failed to update adminEnabled")
                                }
                        },
                        enabled = targetHash.trim().isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) { Text("Apply") }

                    OutlinedButton(onClick = { refresh() }, modifier = Modifier.weight(1f)) { Text("Refresh list") }
                }

                Text(
                    "Tip: send someone their deviceHash from the Admin denied screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (admins.isEmpty()) {
            Text("No admin-enabled devices found.", style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(admins, key = { it.deviceHash }) { row ->
                    Card {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("deviceHash=${row.deviceHash}", fontFamily = FontFamily.Monospace)
                            Text("adminEnabled=${row.adminEnabled}", fontFamily = FontFamily.Monospace)
                            Text("updatedAt=${row.updatedAt ?: "?"}", fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

/* =========================
   Common UI cards
   ========================= */

@Composable
private fun DeviceHashCard(deviceHash: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("DeviceHash", style = MaterialTheme.typography.titleSmall)
            Text(
                deviceHash.ifBlank { "(blank — not found in vrca_remote prefs: device_id_hash)" },
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Error", style = MaterialTheme.typography.titleSmall)
            Text(message, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun AdminRulesCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Rules (current plan)", style = MaterialTheme.typography.titleSmall)
            Text("• Public build shows: announcements + warning/ban banners.")
            Text("• Admin build writes moderation + announcements + ToS config.")
            Text("• Warn/Ban include a reason string for the public UI.")
            Text("• Admin access: devices/{deviceHash}.adminEnabled")
            Text("• Owner: config/security.ownerDeviceHash can add/remove admins.")
        }
    }
}

/* =========================
   Announcements
   ========================= */

private data class AnnouncementRow(
    val id: String,
    val title: String,
    val body: String,
    val active: Boolean,
    val priority: Int,
    val createdAt: Timestamp?
)

@Composable
private fun AdminAnnouncementsSection(
    db: FirebaseFirestore,
    createdByDevice: String,
    writerDeviceHash: String,
    setLoading: (Boolean) -> Unit,
    setError: (String?) -> Unit
) {
    val announcements = remember { mutableStateListOf<AnnouncementRow>() }

    fun refresh() {
        setLoading(true)
        setError(null)
        db.collection("announcements")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(30)
            .get()
            .addOnSuccessListener { snap ->
                announcements.clear()
                for (d in snap.documents) {
                    announcements.add(
                        AnnouncementRow(
                            id = d.id,
                            title = d.getString("title") ?: "",
                            body = d.getString("body") ?: "",
                            active = d.getBoolean("active") ?: true,
                            priority = (d.getLong("priority") ?: 0L).toInt(),
                            createdAt = d.getTimestamp("createdAt")
                        )
                    )
                }
                setLoading(false)
            }
            .addOnFailureListener { e ->
                setLoading(false)
                setError(e.message ?: "Failed to load announcements")
            }
    }

    var newTitle by remember { mutableStateOf("") }
    var newBody by remember { mutableStateOf("") }
    var newActive by remember { mutableStateOf(true) }
    var newPriority by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { refresh() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Announcements", style = MaterialTheme.typography.titleMedium)

        ElevatedCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Create / publish", style = MaterialTheme.typography.titleSmall)

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
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Active")
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = newActive, onCheckedChange = { newActive = it })
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Priority")
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { newPriority = (newPriority - 1).coerceIn(-5, 5) }) { Text("−") }
                        Spacer(Modifier.width(8.dp))
                        Text(newPriority.toString(), fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { newPriority = (newPriority + 1).coerceIn(-5, 5) }) { Text("+") }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            setLoading(true)
                            setError(null)
                            val data = hashMapOf(
                                "title" to newTitle.trim(),
                                "body" to newBody.trim(),
                                "active" to newActive,
                                "priority" to newPriority,
                                "createdAt" to FieldValue.serverTimestamp(),
                                "createdByDevice" to createdByDevice,
                                "createdByAppId" to BuildConfig.APPLICATION_ID,
                                "writerDeviceHash" to writerDeviceHash
                            )
                            db.collection("announcements").add(data)
                                .addOnSuccessListener {
                                    setLoading(false)
                                    newTitle = ""
                                    newBody = ""
                                    newActive = true
                                    newPriority = 0
                                    refresh()
                                }
                                .addOnFailureListener { e ->
                                    setLoading(false)
                                    setError(e.message ?: "Failed to publish announcement")
                                }
                        },
                        enabled = newTitle.isNotBlank() && newBody.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) { Text("Publish") }

                    OutlinedButton(onClick = { refresh() }, modifier = Modifier.weight(1f)) { Text("Refresh list") }
                }
            }
        }

        if (announcements.isEmpty()) {
            Text("No announcements yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(announcements, key = { it.id }) { a ->
                    Card {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(a.title.ifBlank { "(no title)" }, style = MaterialTheme.typography.titleSmall)
                                Text(if (a.active) "ACTIVE" else "OFF", style = MaterialTheme.typography.labelLarge)
                            }
                            Text(
                                "priority=${a.priority}  createdAt=${a.createdAt ?: "?"}",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(a.body, style = MaterialTheme.typography.bodyMedium)

                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        setLoading(true)
                                        setError(null)
                                        db.collection("announcements").document(a.id)
                                            .update(
                                                mapOf(
                                                    "active" to !a.active,
                                                    "updatedAt" to FieldValue.serverTimestamp(),
                                                    "writerDeviceHash" to writerDeviceHash
                                                )
                                            )
                                            .addOnSuccessListener { setLoading(false); refresh() }
                                            .addOnFailureListener { e ->
                                                setLoading(false)
                                                setError(e.message ?: "Failed to toggle active")
                                            }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text(if (a.active) "Disable" else "Enable") }

                                OutlinedButton(
                                    onClick = {
                                        setLoading(true)
                                        setError(null)
                                        // delete must be allowed by rules for admins; no writer field possible on delete
                                        db.collection("announcements").document(a.id)
                                            .delete()
                                            .addOnSuccessListener { setLoading(false); refresh() }
                                            .addOnFailureListener { e ->
                                                setLoading(false)
                                                setError(e.message ?: "Failed to delete")
                                            }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Delete") }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

/* =========================
   Moderation (deviceHash-based)
   ========================= */

@Composable
private fun AdminModerationSection(
    db: FirebaseFirestore,
    writerDeviceHash: String,
    setLoading: (Boolean) -> Unit,
    setError: (String?) -> Unit
) {
    var lookupDeviceHash by remember { mutableStateOf("") }
    var loadedDeviceHash by remember { mutableStateOf<String?>(null) }

    var warned by remember { mutableStateOf(false) }
    var banned by remember { mutableStateOf(false) }
    var warnReason by remember { mutableStateOf("") }
    var banReason by remember { mutableStateOf("") }
    var updatedAt by remember { mutableStateOf<Timestamp?>(null) }

    fun clearLoaded() {
        loadedDeviceHash = null
        warned = false
        banned = false
        warnReason = ""
        banReason = ""
        updatedAt = null
    }

    fun load(dh: String) {
        val d = dh.trim()
        if (d.isBlank()) return
        setLoading(true)
        setError(null)
        db.collection("devices").document(d).get()
            .addOnSuccessListener { snap ->
                loadedDeviceHash = d
                warned = snap.getBoolean("warned") ?: false
                banned = snap.getBoolean("banned") ?: false
                warnReason = snap.getString("warnReason") ?: ""
                banReason = snap.getString("banReason") ?: ""
                updatedAt = snap.getTimestamp("updatedAt")
                setLoading(false)
            }
            .addOnFailureListener { e ->
                setLoading(false)
                setError(e.message ?: "Failed to load device")
            }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Moderation (by deviceHash)", style = MaterialTheme.typography.titleMedium)

        ElevatedCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Lookup device", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = lookupDeviceHash,
                    onValueChange = { lookupDeviceHash = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Device hash") },
                    placeholder = { Text("paste device hash here") }
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { load(lookupDeviceHash) },
                        enabled = lookupDeviceHash.trim().isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) { Text("Load") }

                    OutlinedButton(onClick = { clearLoaded() }, modifier = Modifier.weight(1f)) { Text("Clear") }
                }
            }
        }

        if (loadedDeviceHash == null) {
            Text("No device loaded.", style = MaterialTheme.typography.bodySmall)
            return
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Device: $loadedDeviceHash", style = MaterialTheme.typography.titleSmall)
                Text("warned=$warned  banned=$banned", fontFamily = FontFamily.Monospace)
                Text("updatedAt=${updatedAt ?: "?"}", fontFamily = FontFamily.Monospace)
            }
        }

        ElevatedCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Warn", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = warnReason,
                    onValueChange = { warnReason = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    label = { Text("Warn reason (shown to user)") }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            val d = loadedDeviceHash ?: return@Button
                            setLoading(true); setError(null)
                            val payload = mapOf(
                                "warned" to true,
                                "warnReason" to warnReason.trim(),
                                "updatedAt" to FieldValue.serverTimestamp(),
                                "writerDeviceHash" to writerDeviceHash
                            )
                            db.collection("devices").document(d)
                                .set(payload, com.google.firebase.firestore.SetOptions.merge())
                                .addOnSuccessListener { setLoading(false); load(d) }
                                .addOnFailureListener { e ->
                                    setLoading(false)
                                    setError(e.message ?: "Failed to warn")
                                }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Apply warn") }

                    OutlinedButton(
                        onClick = {
                            val d = loadedDeviceHash ?: return@OutlinedButton
                            setLoading(true); setError(null)
                            val payload = mapOf(
                                "warned" to false,
                                "warnReason" to "",
                                "updatedAt" to FieldValue.serverTimestamp(),
                                "writerDeviceHash" to writerDeviceHash
                            )
                            db.collection("devices").document(d)
                                .set(payload, com.google.firebase.firestore.SetOptions.merge())
                                .addOnSuccessListener { setLoading(false); load(d) }
                                .addOnFailureListener { e ->
                                    setLoading(false)
                                    setError(e.message ?: "Failed to clear warn")
                                }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Clear warn") }
                }
            }
        }

        ElevatedCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Ban", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = banReason,
                    onValueChange = { banReason = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    label = { Text("Ban reason (shown to user)") }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            val d = loadedDeviceHash ?: return@Button
                            setLoading(true); setError(null)
                            val payload = mapOf(
                                "banned" to true,
                                "banReason" to banReason.trim(),
                                "updatedAt" to FieldValue.serverTimestamp(),
                                "writerDeviceHash" to writerDeviceHash
                            )
                            db.collection("devices").document(d)
                                .set(payload, com.google.firebase.firestore.SetOptions.merge())
                                .addOnSuccessListener { setLoading(false); load(d) }
                                .addOnFailureListener { e ->
                                    setLoading(false)
                                    setError(e.message ?: "Failed to ban")
                                }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Ban device") }

                    OutlinedButton(
                        onClick = {
                            val d = loadedDeviceHash ?: return@OutlinedButton
                            setLoading(true); setError(null)
                            val payload = mapOf(
                                "banned" to false,
                                "banReason" to "",
                                "updatedAt" to FieldValue.serverTimestamp(),
                                "writerDeviceHash" to writerDeviceHash
                            )
                            db.collection("devices").document(d)
                                .set(payload, com.google.firebase.firestore.SetOptions.merge())
                                .addOnSuccessListener { setLoading(false); load(d) }
                                .addOnFailureListener { e ->
                                    setLoading(false)
                                    setError(e.message ?: "Failed to unban")
                                }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Unban") }
                }
            }
        }
    }
}

/* =========================
   ToS Config
   ========================= */

@Composable
private fun AdminTosConfigSection(
    db: FirebaseFirestore,
    writerDeviceHash: String,
    setLoading: (Boolean) -> Unit,
    setError: (String?) -> Unit
) {
    var tosVersion by remember { mutableIntStateOf(1) }
    var tosText by remember { mutableStateOf("") }
    var tosUrl by remember { mutableStateOf("") }
    var loadedAt by remember { mutableStateOf<Timestamp?>(null) }

    fun load() {
        setLoading(true)
        setError(null)
        db.collection("config").document("app").get()
            .addOnSuccessListener { snap ->
                tosVersion = (snap.getLong("tosVersion") ?: 1L).toInt().coerceAtLeast(1)
                tosText = snap.getString("tosText") ?: ""
                tosUrl = snap.getString("tosUrl") ?: ""
                loadedAt = snap.getTimestamp("updatedAt")
                setLoading(false)
            }
            .addOnFailureListener { e ->
                setLoading(false)
                setError(e.message ?: "Failed to load ToS config")
            }
    }

    LaunchedEffect(Unit) { load() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("ToS / App Config", style = MaterialTheme.typography.titleMedium)

        ElevatedCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Current config", style = MaterialTheme.typography.titleSmall)
                Text("loadedAt=${loadedAt ?: "?"}", fontFamily = FontFamily.Monospace)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("ToS version", style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { tosVersion = (tosVersion - 1).coerceAtLeast(1) }) { Text("−") }
                        Spacer(Modifier.width(10.dp))
                        Text(tosVersion.toString(), fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.width(10.dp))
                        OutlinedButton(onClick = { tosVersion += 1 }) { Text("+") }
                    }
                }

                OutlinedTextField(
                    value = tosUrl,
                    onValueChange = { tosUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("ToS URL (optional)") },
                    placeholder = { Text("https://…") }
                )

                OutlinedTextField(
                    value = tosText,
                    onValueChange = { tosText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    label = { Text("ToS text (shown in-app)") }
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            setLoading(true)
                            setError(null)
                            val data = hashMapOf(
                                "tosVersion" to tosVersion,
                                "tosText" to tosText,
                                "tosUrl" to tosUrl,
                                "updatedAt" to FieldValue.serverTimestamp(),
                                "writerDeviceHash" to writerDeviceHash
                            )
                            db.collection("config").document("app")
                                .set(data, com.google.firebase.firestore.SetOptions.merge())
                                .addOnSuccessListener { setLoading(false); load() }
                                .addOnFailureListener { e ->
                                    setLoading(false)
                                    setError(e.message ?: "Failed to save config")
                                }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Save") }

                    OutlinedButton(onClick = { load() }, modifier = Modifier.weight(1f)) { Text("Reload") }
                }

                Text(
                    "Public build should re-prompt ToS when tosVersion increases.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
