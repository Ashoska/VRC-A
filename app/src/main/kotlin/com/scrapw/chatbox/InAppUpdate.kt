// app/src/main/kotlin/com/scrapw/chatbox/InAppUpdate.kt
package com.scrapw.chatbox

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Firestore release document: releases/latest
 *
 * Fields:
 *   versionCode       (Long)   - integer build number of the latest public release
 *   versionName       (String) - display label e.g. "v1.4.2"
 *   downloadUrl       (String) - direct APK download URL
 *   requiredMinCode   (Long)   - users with versionCode < this are FORCE-updated (cannot dismiss)
 *   notes             (String) - optional release notes shown in the dialog
 *   publishedAt       (Timestamp)
 *
 * Only admin build can write this doc (enforced by Firestore rules).
 * Public build reads it on every launch.
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

suspend fun checkFirestoreRelease(currentVersionCode: Int): ReleaseCheckResult {
    return try {
        val db = FirebaseFirestore.getInstance()
        val snap = db.collection("releases").document("latest").get().await()

        if (!snap.exists()) return ReleaseCheckResult.UpToDate

        val latestCode   = snap.getLong("versionCode") ?: return ReleaseCheckResult.UpToDate
        val versionName  = snap.getString("versionName").orEmpty()
        val downloadUrl  = snap.getString("downloadUrl").orEmpty()
        val requiredMin  = snap.getLong("requiredMinCode") ?: 0L
        val notes        = snap.getString("notes").orEmpty()

        if (downloadUrl.isBlank() || latestCode <= currentVersionCode) {
            return ReleaseCheckResult.UpToDate
        }

        val info = ReleaseInfo(
            versionCode    = latestCode,
            versionName    = versionName,
            downloadUrl    = downloadUrl,
            requiredMinCode = requiredMin,
            notes          = notes
        )
        val forced = currentVersionCode < requiredMin
        ReleaseCheckResult.UpdateAvailable(info, forced)

    } catch (e: Exception) {
        ReleaseCheckResult.Failed(e.message ?: "Unknown error")
    }
}
