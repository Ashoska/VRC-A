package com.vrca.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

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
internal fun ConfigTab(
    db: FirebaseFirestore,
    setGlobalLoading: (Boolean) -> Unit,
    setError: (String?) -> Unit
) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    // The website's status (Worker v56 `/admin/status`), read by everyone's boot screen and gremlininc.app.
    fun sendStatus(on: Boolean, message: String) {
        val key = ctx.getSharedPreferences("vrca_admin_local", android.content.Context.MODE_PRIVATE).getString("avatar_admin_key", "").orEmpty()
        if (key.isBlank()) return
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val c = (java.net.URL("${com.vrca.vrchat.AvatarGlobalDb.WORKER_URL}/admin/status").openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; setRequestProperty("content-type", "application/json")
                    connectTimeout = 10_000; readTimeout = 10_000
                }
                c.outputStream.use { it.write(org.json.JSONObject().put("key", key).put("maintenance", on).put("message", message.trim()).toString().toByteArray()) }
                c.responseCode; c.disconnect()
            }
        }
    }

    var tosVersion by rememberSaveable { mutableIntStateOf(1) }
    var tosText by rememberSaveable { mutableStateOf("") }
    var tosUrl by rememberSaveable { mutableStateOf("") }
    var ownerUid by rememberSaveable { mutableStateOf("") }
    var discordInvite by rememberSaveable { mutableStateOf("") }
    // Shows "Maintenance" on everyone's boot screen VRC-A servers row (read with the terms, no extra read).
    var serversMaintenance by rememberSaveable { mutableStateOf(false) }
    // The reason shown with it (boot row + website banner); kept in the config doc so it reloads here.
    var maintenanceMsg by rememberSaveable { mutableStateOf("") }
    var loadedAt by remember { mutableStateOf<Timestamp?>(null) }
    // The terms as last loaded: a save stamps `tosUpdatedAt` (which asks users to accept again) only
    // when one of these actually changed, never for the other settings on this tab.
    var savedTos by remember { mutableStateOf(Triple(1, "", "")) }

    suspend fun load() {
        setGlobalLoading(true)
        setError(null)

        runCatching {
            val snap = db.collection("config").document("app").get().await()
            tosVersion = (snap.getLong("tosVersion") ?: 1L).toInt().coerceAtLeast(1)
            tosText = snap.getString("tosText") ?: ""
            tosUrl = snap.getString("tosUrl") ?: ""
            ownerUid = snap.getString("ownerUid") ?: ""
            discordInvite = snap.getString("discordInvite") ?: ""
            serversMaintenance = snap.getBoolean("serversMaintenance") ?: false
            maintenanceMsg = snap.getString("serversMaintenanceMessage") ?: ""
            loadedAt = snap.getTimestamp("updatedAt")
            savedTos = Triple(tosVersion, tosText, tosUrl)
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
            val data = hashMapOf<String, Any>(
                "ownerUid" to ownerUid.trim(),
                "tosVersion" to tosVersion,
                "tosText" to tosText,
                "tosUrl" to tosUrl,
                "discordInvite" to discordInvite.trim(),
                "serversMaintenance" to serversMaintenance,
                "serversMaintenanceMessage" to maintenanceMsg.trim()
            )
            // Only a real terms change is stamped: `tosUpdatedAt` for this version's apps, and the doc's
            // `updatedAt` too, because apps from before this fix still re-ask the terms when THAT moves.
            if (Triple(tosVersion, tosText, tosUrl) != savedTos) {
                data["tosUpdatedAt"] = FieldValue.serverTimestamp()
                data["updatedAt"] = FieldValue.serverTimestamp()
            }
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
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

            OutlinedTextField(
                value = discordInvite,
                onValueChange = { discordInvite = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Discord invite link") },
                placeholder = { Text("https://discord.gg/...") }
            )
            Text(
                "Shown as a Discord button in the public app's top bar. Leave blank to hide it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text("VRC-A servers maintenance", style = MaterialTheme.typography.labelLarge)
                androidx.compose.material3.Switch(
                    checked = serversMaintenance,
                    onCheckedChange = { on -> serversMaintenance = on; scope.launch { saveConfig() }; sendStatus(on, maintenanceMsg) }
                )
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = maintenanceMsg,
                    onValueChange = { maintenanceMsg = it.take(200) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Maintenance message") }
                )
                com.vrca.ui.common.GremlinSmallButton(onClick = { scope.launch { saveConfig() }; if (serversMaintenance) sendStatus(true, maintenanceMsg) }) { Text("Save") }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("ToS version", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.vrca.ui.common.GremlinSecondaryButton(onClick = { tosVersion = (tosVersion - 1).coerceAtLeast(1); scope.launch { saveConfig() } }) { Text("-") }
                    Text(tosVersion.toString(), fontFamily = FontFamily.Monospace)
                    com.vrca.ui.common.GremlinSecondaryButton(onClick = { tosVersion += 1; scope.launch { saveConfig() } }) { Text("+") }
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
                com.vrca.ui.common.GremlinSecondaryButton(
                    onClick = { if (tosText.trim().isEmpty()) tosText = DEFAULT_TOS_TEXT },
                    modifier = Modifier.weight(1f)
                ) { Text("Use template") }

                com.vrca.ui.common.GremlinSecondaryButton(
                    onClick = { scope.launch { load() } },
                    modifier = Modifier.weight(1f)
                ) { Text("Reload") }
            }

            com.vrca.ui.common.GremlinButton(
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
    Spacer(Modifier.height(24.dp))
    }
}
