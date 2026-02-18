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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
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
 *  - stable device identity via ANDROID_ID hash (fallback: uid)
 *  - SAFE public writes ONLY to users/{uid} (self doc) for:
 *      deviceHash, lastSeenAt, appId, adminBuild, versionName, versionCode
 *
 * IMPORTANT:
 *  - Public build does NOT write to devices/{deviceHash} at all.
 *  - devices collection is reserved for owner/admin workflows ONLY (via rules).
 */
@Composable
fun ChatboxApp() {
    val ctx = LocalContext.current

    /* -------------------------
       Crash gate
       ------------------------- */

    val crashPrefs = remember {
        ctx.getSharedPreferences(ChatboxApplication.CRASH_PREFS_FILE, Context.MODE_PRIVATE)
    }
    val lastCrashText = remember {
        crashPrefs.getString(ChatboxApplication.CRASH_KEY_TEXT, "").orEmpty()
    }

    var allowBoot by remember { mutableStateOf(lastCrashText.isBlank()) }

    if (!allowBoot) {
        CrashScreen(
            crashText = lastCrashText,
            onClear = {
                crashPrefs.edit()
                    .remove(ChatboxApplication.CRASH_KEY_TEXT)
                    .commit()
            },
            onContinue = {
                crashPrefs.edit()
                    .remove(ChatboxApplication.CRASH_KEY_TEXT)
                    .commit()
                allowBoot = true
            }
        )
        return
    }

    /* -------------------------
       Bootstrap gate
       ------------------------- */

    var bootOk by remember { mutableStateOf(false) }
    var bootWorking by remember { mutableStateOf(false) }
    var bootError by remember { mutableStateOf<String?>(null) }

    if (!bootOk) {
        BootstrapScreen(
            working = bootWorking,
            error = bootError,
            onRetry = {
                bootOk = false
                bootWorking = false
                bootError = null
            }
        )

        LaunchedEffect(bootOk) {
            if (bootOk || bootWorking) return@LaunchedEffect
            bootWorking = true
            bootError = null

            try {
                bootstrapFirebaseAndCache(ctx)
                bootOk = true
            } catch (t: Throwable) {
                bootError = (t.message ?: t.toString()).take(4000)
            } finally {
                bootWorking = false
            }
        }
        return
    }

    /* -------------------------
       Main app
       ------------------------- */

    val vm: ChatboxViewModel = viewModel(factory = ChatboxViewModel.Factory)
    ChatboxScreen(chatboxViewModel = vm)
}

/* =========================================================
   BOOTSTRAP
   ========================================================= */

private const val REMOTE_PREFS_FILE = "vrca_remote"

private object RemoteKeys {
    // AdminScreen reads this from prefs (keep this key name stable!)
    const val DEVICE_ID_HASH = "device_id_hash"
    const val AUTH_UID = "auth_uid"
}

/**
 * Boot steps:
 *  1) anonymous auth
 *  2) compute deviceHash
 *  3) cache uid + deviceHash locally
 *  4) SAFE self-write to users/{uid}: deviceHash + lastSeen (rules restrict!)
 *
 * No writes to devices/{deviceHash} here.
 */
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

    // ANDROID_ID can be blank; fallback to uid so app still works.
    val deviceKeySource = if (androidId.isNotBlank()) "a:$androidId" else "u:$uid"
    val deviceHash = sha256Hex(deviceKeySource)

    // Cache for AdminScreen + for debugging
    ctx.getSharedPreferences(REMOTE_PREFS_FILE, Context.MODE_PRIVATE)
        .edit()
        .putString(RemoteKeys.AUTH_UID, uid)
        .putString(RemoteKeys.DEVICE_ID_HASH, deviceHash)
        .apply()

    // SAFE public write (self doc only) — controlled by Firestore rules
    val db = FirebaseFirestore.getInstance()
    val userRef = db.collection("users").document(uid)

    val safe = hashMapOf<String, Any>(
        "deviceHash" to deviceHash,
        "lastSeenAt" to FieldValue.serverTimestamp(),
        "appId" to BuildConfig.APPLICATION_ID,
        "adminBuild" to BuildConfig.IS_ADMIN_BUILD,
        "versionName" to BuildConfig.VERSION_NAME,
        "versionCode" to BuildConfig.VERSION_CODE
    )

    // If rules deny, we still let the app continue (reads still work).
    runCatching {
        userRef.set(safe, com.google.firebase.firestore.SetOptions.merge()).await()
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

            ElevatedCard {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Preparing device session (safe) + starting app.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Build: ${BuildConfig.APPLICATION_ID}\n" +
                            "Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                            "Admin: ${BuildConfig.IS_ADMIN_BUILD}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (working) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator()
                }
            }

            if (error != null) {
                ElevatedCard {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Startup error", style = MaterialTheme.typography.titleSmall)
                        Text(error, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
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

            Text(
                "Build: ${BuildConfig.APPLICATION_ID}\n" +
                    "Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                    "Admin: ${BuildConfig.IS_ADMIN_BUILD}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            ElevatedCard {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        crashText.ifBlank { "(no crash text saved)" },
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            ) {
                Text("Clear crash + Try boot")
            }

            OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                Text("Clear crash log only")
            }

            Text(
                "If this screen shows “no crash text saved”, the process may be dying before the handler can write the log.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
