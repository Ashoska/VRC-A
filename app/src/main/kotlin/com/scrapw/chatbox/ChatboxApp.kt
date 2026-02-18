// app/src/main/kotlin/com/scrapw/chatbox/ChatboxApp.kt
package com.scrapw.chatbox

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.scrapw.chatbox.ui.ChatboxViewModel
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest

/**
 * ChatboxApp
 *
 * Responsibilities:
 *  - crash gate
 *  - bootstrap gate
 *  - anonymous auth (no login UI)
 *  - stable device identity via ANDROID_ID hash
 *  - sync + cache:
 *      devices/{deviceHash}      moderation state (+ adminEnabled exists there too)
 *      announcements (active)
 *      config/app                ToS config
 */
@Composable
fun ChatboxApp() {
    val ctx = LocalContext.current

    // ----- Crash gate -----
    val crashPrefs = remember {
        ctx.getSharedPreferences(ChatboxApplication.CRASH_PREFS_FILE, Context.MODE_PRIVATE)
    }
    val lastCrashText = remember {
        crashPrefs.getString(ChatboxApplication.CRASH_KEY_TEXT, "").orEmpty()
    }

    var showApp by remember { mutableStateOf(lastCrashText.isBlank()) }

    if (!showApp) {
        CrashScreen(
            crashText = lastCrashText,
            onClear = {
                crashPrefs.edit().remove(ChatboxApplication.CRASH_KEY_TEXT).commit()
            },
            onContinue = {
                crashPrefs.edit().remove(ChatboxApplication.CRASH_KEY_TEXT).commit()
                showApp = true
            }
        )
        return
    }

    // ----- Bootstrap gate -----
    var bootOk by remember { mutableStateOf(false) }
    var bootWorking by remember { mutableStateOf(false) }
    var bootErr by remember { mutableStateOf<String?>(null) }

    if (!bootOk) {
        BootstrapScreen(
            working = bootWorking,
            error = bootErr,
            onRetry = {
                bootOk = false
                bootWorking = false
                bootErr = null
            }
        )

        LaunchedEffect(bootOk) {
            if (bootOk || bootWorking) return@LaunchedEffect
            bootWorking = true
            bootErr = null

            try {
                bootstrapFirebaseAndCache(ctx)
                bootOk = true
            } catch (t: Throwable) {
                bootErr = (t.message ?: t.toString()).take(4000)
            } finally {
                bootWorking = false
            }
        }
        return
    }

    // ----- Normal app -----
    val vm: ChatboxViewModel = viewModel(factory = ChatboxViewModel.Factory)
    ChatboxScreen(chatboxViewModel = vm)
}

/* =========================================================
   BOOTSTRAP
   ========================================================= */

private const val REMOTE_PREFS_FILE = "vrca_remote"

private object RemoteKeys {
    const val DEVICE_ID_HASH = "device_id_hash"
    const val AUTH_UID = "auth_uid"

    const val WARNED = "warned"
    const val BANNED = "banned"
    const val WARN_REASON = "warn_reason"
    const val BAN_REASON = "ban_reason"
    const val MOD_UPDATED_AT = "mod_updated_at"

    const val TOS_VERSION = "tos_version"
    const val TOS_TEXT = "tos_text"
    const val TOS_URL = "tos_url"
    const val TOS_UPDATED_AT = "tos_updated_at"

    // Stored as compact escaped plaintext rows:
    //  esc(title)|||esc(body)|||priority|||createdAtMs\n
    const val ANNOUNCEMENTS_BLOCK = "announcements_block"
    const val ANNOUNCEMENTS_UPDATED_AT = "announcements_updated_at"
}

private suspend fun bootstrapFirebaseAndCache(ctx: Context) {
    val auth = FirebaseAuth.getInstance()
    if (auth.currentUser == null) {
        auth.signInAnonymously().await()
    }
    val uid = auth.currentUser?.uid ?: error("Anonymous auth returned null user")

    val androidId = Settings.Secure
        .getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        ?.trim()
        .orEmpty()

    // ANDROID_ID can be null/blank on some devices; fall back to UID (still no login UI).
    val deviceKeySource = if (androidId.isNotBlank()) "a:$androidId" else "u:$uid"
    val deviceHash = sha256Hex(deviceKeySource)

    val prefs = ctx.getSharedPreferences(REMOTE_PREFS_FILE, Context.MODE_PRIVATE)
    prefs.edit()
        .putString(RemoteKeys.AUTH_UID, uid)
        .putString(RemoteKeys.DEVICE_ID_HASH, deviceHash)
        .apply()

    val db = FirebaseFirestore.getInstance()
    val deviceRef = db.collection("devices").document(deviceHash)

    // Create if missing (safe merge)
    runCatching {
        val existing = deviceRef.get().await()
        if (!existing.exists()) {
            deviceRef.set(
                mapOf(
                    "deviceHash" to deviceHash,
                    "ownerUid" to uid,
                    "adminEnabled" to false,
                    "warned" to false,
                    "banned" to false,
                    "warnReason" to "",
                    "banReason" to "",
                    "createdAt" to FieldValue.serverTimestamp()
                ),
                com.google.firebase.firestore.SetOptions.merge()
            ).await()
        }
    }

    // Heartbeat (merge-safe). Non-fatal if rules deny.
    runCatching {
        deviceRef.set(
            mapOf(
                "lastSeenAt" to FieldValue.serverTimestamp(),
                "lastSeenUid" to uid,
                "appId" to BuildConfig.APPLICATION_ID,
                "adminBuild" to BuildConfig.IS_ADMIN_BUILD,
                "versionName" to BuildConfig.VERSION_NAME,
                "versionCode" to BuildConfig.VERSION_CODE
            ),
            com.google.firebase.firestore.SetOptions.merge()
        ).await()
    }

    // Moderation read (device doc)
    run {
        val snap = deviceRef.get().await()
        val warned = snap.getBoolean("warned") ?: false
        val banned = snap.getBoolean("banned") ?: false
        val warnReason = snap.getString("warnReason") ?: ""
        val banReason = snap.getString("banReason") ?: ""
        val updatedAt = snap.getTimestamp("updatedAt") ?: snap.getTimestamp("lastSeenAt")

        prefs.edit()
            .putBoolean(RemoteKeys.WARNED, warned)
            .putBoolean(RemoteKeys.BANNED, banned)
            .putString(RemoteKeys.WARN_REASON, warnReason)
            .putString(RemoteKeys.BAN_REASON, banReason)
            .putString(RemoteKeys.MOD_UPDATED_AT, updatedAt?.toDate()?.time?.toString().orEmpty())
            .apply()
    }

    // ToS config (config/app)
    run {
        val snap = db.collection("config").document("app").get().await()
        val tosVersion = (snap.getLong("tosVersion") ?: 1L).toInt().coerceAtLeast(1)
        val tosText = snap.getString("tosText") ?: ""
        val tosUrl = snap.getString("tosUrl") ?: ""
        val updatedAt = snap.getTimestamp("updatedAt")

        prefs.edit()
            .putInt(RemoteKeys.TOS_VERSION, tosVersion)
            .putString(RemoteKeys.TOS_TEXT, tosText)
            .putString(RemoteKeys.TOS_URL, tosUrl)
            .putString(RemoteKeys.TOS_UPDATED_AT, updatedAt?.toDate()?.time?.toString().orEmpty())
            .apply()
    }

    // Announcements (active only)
    run {
        val snap = db.collection("announcements")
            .whereEqualTo("active", true)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(10)
            .get()
            .await()

        fun esc(s: String): String =
            s.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("\n", "\\n")
                .replace("\r", "")

        val sb = StringBuilder()
        var newest: Timestamp? = null

        for (d in snap.documents) {
            val title = (d.getString("title") ?: "").trim()
            val body = (d.getString("body") ?: "").trim()
            val priority = (d.getLong("priority") ?: 0L).toInt()
            val createdAt = d.getTimestamp("createdAt")

            if (newest == null && createdAt != null) newest = createdAt

            sb.append(esc(title))
                .append("|||")
                .append(esc(body))
                .append("|||")
                .append(priority)
                .append("|||")
                .append(createdAt?.toDate()?.time?.toString().orEmpty())
                .append("\n")
        }

        prefs.edit()
            .putString(RemoteKeys.ANNOUNCEMENTS_BLOCK, sb.toString())
            .putString(RemoteKeys.ANNOUNCEMENTS_UPDATED_AT, newest?.toDate()?.time?.toString().orEmpty())
            .apply()
    }
}

private fun sha256Hex(input: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        sb.append("0123456789abcdef"[v ushr 4])
        sb.append("0123456789abcdef"[v and 0x0F])
    }
    return sb.toString()
}

/* =========================================================
   UI: Bootstrap + Crash
   ========================================================= */

@Composable
private fun BootstrapScreen(
    working: Boolean,
    error: String?,
    onRetry: () -> Unit
) {
    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Starting VRC-A…", style = MaterialTheme.typography.headlineSmall)

            if (working) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            if (error != null) {
                ElevatedCard {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Startup error", style = MaterialTheme.typography.titleSmall)
                        Text(error, fontFamily = FontFamily.Monospace)
                    }
                }
                Button(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun CrashScreen(
    crashText: String,
    onClear: () -> Unit,
    onContinue: () -> Unit
) {
    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("VRC-A crashed on last launch", style = MaterialTheme.typography.headlineSmall)

            ElevatedCard {
                Column(Modifier.padding(12.dp)) {
                    Text(crashText.ifBlank { "(no crash text saved)" }, fontFamily = FontFamily.Monospace)
                }
            }

            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text("Clear crash + continue")
            }

            OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                Text("Clear crash log only")
            }
        }
    }
}
