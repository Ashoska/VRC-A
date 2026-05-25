package com.vrca.update

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Firestore release document: releases/latest  (global)
 *                              releases/{deviceHash}  (per-device targeted)
 *
 * Both use the same fields:
 *   versionCode       (Long)   - integer build number
 *   versionName       (String) - display label e.g. "v1.4.2"
 *   downloadUrl       (String) - direct APK download URL
 *   requiredMinCode   (Long)   - users with versionCode < this are FORCE-updated
 *   notes             (String) - optional release notes shown in the dialog
 *   publishedAt       (Timestamp)
 *
 * Directed releases write to releases/{deviceHash} so they ride the same
 * infrastructure as global releases.  The client checks the per-device doc
 * first, falling back to releases/latest.
 */

data class ReleaseInfo(
    val versionCode: Long,
    val versionName: String,
    val downloadUrl: String,
    val requiredMinCode: Long,
    val notes: String
)

sealed class ReleaseCheckResult {
    object UpToDate : ReleaseCheckResult()
    data class UpdateAvailable(val info: ReleaseInfo, val forced: Boolean) : ReleaseCheckResult()
    data class Failed(val reason: String) : ReleaseCheckResult()
}

private fun parseReleaseSnap(
    snap: com.google.firebase.firestore.DocumentSnapshot,
    currentVersionCode: Int
): ReleaseCheckResult? {
    if (!snap.exists()) return null

    val latestCode  = snap.getLong("versionCode") ?: return null
    val versionName = snap.getString("versionName").orEmpty()
    val downloadUrl = snap.getString("downloadUrl").orEmpty()
    val requiredMin = snap.getLong("requiredMinCode") ?: 0L
    val notes       = snap.getString("notes").orEmpty()

    if (downloadUrl.isBlank() || latestCode <= currentVersionCode) return null

    val info = ReleaseInfo(
        versionCode     = latestCode,
        versionName     = versionName,
        downloadUrl     = downloadUrl,
        requiredMinCode = requiredMin,
        notes           = notes
    )
    val forced = currentVersionCode < requiredMin
    return ReleaseCheckResult.UpdateAvailable(info, forced)
}

suspend fun checkFirestoreRelease(
    currentVersionCode: Int,
    deviceHash: String = ""
): ReleaseCheckResult {
    return try {
        val db = FirebaseFirestore.getInstance()

        // Per-device release takes priority over global
        if (deviceHash.isNotBlank()) {
            val deviceSnap = db.collection("releases").document(deviceHash).get().await()
            val deviceResult = parseReleaseSnap(deviceSnap, currentVersionCode)
            if (deviceResult != null) return deviceResult
        }

        // Fall back to global release
        val globalSnap = db.collection("releases").document("latest").get().await()
        parseReleaseSnap(globalSnap, currentVersionCode) ?: ReleaseCheckResult.UpToDate

    } catch (e: Exception) {
        ReleaseCheckResult.Failed(e.message ?: "Unknown error")
    }
}
