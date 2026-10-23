package com.vrca.app

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.vrca.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Sends the crashes of the past 3 days that haven't been sent yet to the developer (`crashes`
 * collection, read in the admin Crashes tab; user, 2026-10-23). Runs after Start VRC-A, never during
 * the safe start, and waits for the app's sign-in (made by the boot). One small write per crash;
 * the report text is capped at 20,000 characters. No VRChat name, account or tokens are in it.
 */
object CrashUpload {
    private const val WAIT_MS = 120_000L

    fun sendPending(ctx: Context) {
        val app = ctx.applicationContext
        val pending = CrashReport.recent(app).filter { !it.sent }
        if (pending.isEmpty()) return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                // The boot signs the app in; wait for it (up to 2 minutes), then give up until next time.
                val auth = FirebaseAuth.getInstance()
                var waited = 0L
                while (auth.currentUser == null && waited < WAIT_MS) { delay(1000); waited += 1000 }
                val uid = auth.currentUser?.uid ?: return@launch
                val hash = app.getSharedPreferences("vrca_remote", Context.MODE_PRIVATE).getString("device_id_hash", "").orEmpty()
                val db = FirebaseFirestore.getInstance()
                val sent = mutableSetOf<Long>()
                for (e in pending) {
                    val t = e.text
                    db.collection("crashes").add(
                        mapOf(
                            "uid" to uid,
                            "deviceHash" to hash,
                            "crashedAt" to e.at,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "version" to CrashReport.field(t, "VersionName").ifBlank { BuildConfig.VERSION_NAME },
                            "versionCode" to (CrashReport.field(t, "VersionCode").toLongOrNull() ?: BuildConfig.VERSION_CODE.toLong()),
                            "build" to CrashReport.field(t, "Build"),
                            "device" to CrashReport.field(t, "Device"),
                            "sdk" to (CrashReport.field(t, "SDK").toLongOrNull() ?: 0L),
                            "where" to CrashReport.whereOf(t),
                            "what" to CrashReport.whatOf(t),
                            "text" to t.take(20_000),
                        )
                    ).await()
                    sent += e.at
                }
                CrashReport.markSent(app, sent)
            }
        }
    }
}
