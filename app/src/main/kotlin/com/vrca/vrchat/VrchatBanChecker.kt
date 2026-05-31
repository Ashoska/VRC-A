package com.vrca.vrchat

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * VrchatBanChecker
 *
 * Handles the deferred tri-directional ban detection system.
 *
 * Identity index: bannedIdentifiers/{identifier} → { banId, type, active }
 * Full record:    bannedRecords/{banId} → { deviceHashes[], vrchatIds[], authUids[], ... }
 *
 * Flow:
 *  Phase 1 — on bootstrap (deviceHash + authUid known):
 *    Check bannedIdentifiers/{deviceHash} and bannedIdentifiers/{authUid}.
 *    If found and active → set pendingBanCapture = true + store banId.
 *    Do NOT show ban screen yet — wait for VRChat login so we can capture the alt ID.
 *
 *  Phase 2 — after VRChat login (vrchatId now known):
 *    Always check bannedIdentifiers/{vrchatId}.
 *    If found → update record with new deviceHash/authUid, show ban screen.
 *    If pendingBanCapture → update existing record with new vrchatId, show ban screen.
 *    Neither found → user is clean, proceed.
 *
 * When a ban evasion attempt is detected, the new identifiers are appended to
 * the existing record AND a moderationEvent is written for admin review.
 */
object VrchatBanChecker {

    private const val TAG = "BanChecker"
    private const val COL_IDENTIFIERS = "bannedIdentifiers"
    private const val COL_RECORDS = "bannedRecords"
    private const val COL_EVENTS = "moderationEvents"

    data class Phase1Result(
        val isBanned: Boolean,
        /** If banned, the primary banId found — used in Phase 2 to add new VRChat ID */
        val banId: String?,
        val banReason: String
    )

    data class Phase2Result(
        val isBanned: Boolean,
        val banReason: String
    )

    // ------------------------------------------------------------------
    // Phase 1: Check device hash and auth UID
    // ------------------------------------------------------------------

    suspend fun checkPhase1(deviceHash: String, authUid: String): Phase1Result {
        val db = FirebaseFirestore.getInstance()

        // Check deviceHash first
        val deviceResult = checkIdentifier(db, deviceHash)
        if (deviceResult != null && deviceResult.second) {
            return Phase1Result(isBanned = true, banId = deviceResult.first, banReason = deviceResult.third)
        }

        // Check authUid
        val authResult = checkIdentifier(db, authUid)
        if (authResult != null && authResult.second) {
            return Phase1Result(isBanned = true, banId = authResult.first, banReason = authResult.third)
        }

        return Phase1Result(isBanned = false, banId = null, banReason = "")
    }

    // ------------------------------------------------------------------
    // Phase 2: After VRChat login — always run this
    // ------------------------------------------------------------------

    /**
     * @param pendingBanId  If Phase 1 found a ban, pass its banId so we can append the VRChat ID.
     *                      Pass null if Phase 1 was clean.
     */
    suspend fun checkPhase2(
        vrchatId: String,
        vrchatDisplayName: String,
        deviceHash: String,
        authUid: String,
        pendingBanId: String?
    ): Phase2Result {
        val db = FirebaseFirestore.getInstance()

        // Always check the VRChat ID against the identifier index
        val vrchatResult = checkIdentifier(db, vrchatId)

        return when {
            // Case A: VRChat ID is directly banned
            vrchatResult != null && vrchatResult.second -> {
                // Append current deviceHash + authUid to this ban record (new device evasion)
                appendIdentifiersToRecord(
                    db = db,
                    banId = vrchatResult.first,
                    newDeviceHash = deviceHash,
                    newAuthUid = authUid,
                    newVrchatId = null, // already in record
                    newDisplayName = vrchatDisplayName,
                    evasionMethod = "new_device_same_vrchat"
                )
                Phase2Result(isBanned = true, banReason = vrchatResult.third)
            }

            // Case B: Phase 1 flagged device/auth as banned — capture this new VRChat ID
            pendingBanId != null -> {
                appendIdentifiersToRecord(
                    db = db,
                    banId = pendingBanId,
                    newDeviceHash = null, // already known from Phase 1
                    newAuthUid = null,
                    newVrchatId = vrchatId,
                    newDisplayName = vrchatDisplayName,
                    evasionMethod = "banned_device_new_vrchat_id"
                )
                // Retrieve ban reason from record
                val reason = try {
                    db.collection(COL_RECORDS).document(pendingBanId).get().await()
                        .getString("banReason") ?: "Account banned"
                } catch (e: Exception) { "Account banned" }
                Phase2Result(isBanned = true, banReason = reason)
            }

            // Case C: Clean
            else -> Phase2Result(isBanned = false, banReason = "")
        }
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Returns Triple(banId, isActive, banReason) or null if not found.
     */
    private suspend fun checkIdentifier(
        db: FirebaseFirestore,
        identifier: String
    ): Triple<String, Boolean, String>? {
        return try {
            val doc = db.collection(COL_IDENTIFIERS).document(identifier).get().await()
            if (!doc.exists()) return null
            val banId = doc.getString("banId") ?: return null
            val active = doc.getBoolean("active") ?: false
            // Fetch reason from record
            val reason = try {
                db.collection(COL_RECORDS).document(banId).get().await()
                    .getString("banReason") ?: "Account banned"
            } catch (e: Exception) { "Account banned" }
            Triple(banId, active, reason)
        } catch (e: Exception) {
            Log.e(TAG, "checkIdentifier($identifier) failed", e)
            null
        }
    }

    private suspend fun appendIdentifiersToRecord(
        db: FirebaseFirestore,
        banId: String,
        newDeviceHash: String?,
        newAuthUid: String?,
        newVrchatId: String?,
        newDisplayName: String?,
        evasionMethod: String
    ) {
        try {
            // All ban-evasion writes go through ONE atomic WriteBatch: the up-to-3
            // identifier-index entries, the ban-record array unions, and the
            // moderationEvent. Previously these were 5 sequential awaited writes —
            // if the process died (or a write was rejected) partway, the record
            // could end up inconsistent: an identifier index pointing at a banId
            // whose record never listed it, or a captured identifier with no audit
            // event. A batch commits all-or-nothing, so the ban record is never
            // left half-updated.
            val batch = db.batch()
            val recordRef = db.collection(COL_RECORDS).document(banId)
            val updates = mutableMapOf<String, Any>()

            if (newDeviceHash != null) {
                updates["deviceHashes"] = FieldValue.arrayUnion(newDeviceHash)
                batch.set(
                    db.collection(COL_IDENTIFIERS).document(newDeviceHash),
                    mapOf("banId" to banId, "type" to "deviceHash", "active" to true)
                )
            }
            if (newAuthUid != null) {
                updates["authUids"] = FieldValue.arrayUnion(newAuthUid)
                batch.set(
                    db.collection(COL_IDENTIFIERS).document(newAuthUid),
                    mapOf("banId" to banId, "type" to "authUid", "active" to true)
                )
            }
            if (newVrchatId != null) {
                updates["vrchatIds"] = FieldValue.arrayUnion(newVrchatId)
                batch.set(
                    db.collection(COL_IDENTIFIERS).document(newVrchatId),
                    mapOf("banId" to banId, "type" to "vrchatId", "active" to true)
                )
            }
            if (newDisplayName != null) {
                updates["displayNames"] = FieldValue.arrayUnion(newDisplayName)
            }

            val evasionEntry = mapOf(
                "detectedAt" to FieldValue.serverTimestamp(),
                "newDeviceHash" to (newDeviceHash ?: ""),
                "newVrchatId" to (newVrchatId ?: ""),
                "newAuthUid" to (newAuthUid ?: ""),
                "newDisplayName" to (newDisplayName ?: ""),
                "method" to evasionMethod
            )
            updates["evasionAttempts"] = FieldValue.arrayUnion(evasionEntry)

            batch.update(recordRef, updates)

            // Moderation event for admin review — auto-id doc included in the batch.
            val eventRef = db.collection(COL_EVENTS).document()
            batch.set(
                eventRef,
                mapOf(
                    "action" to "ban_evasion_detected",
                    "banId" to banId,
                    "method" to evasionMethod,
                    "newVrchatId" to (newVrchatId ?: ""),
                    "newDeviceHash" to (newDeviceHash ?: ""),
                    "newDisplayName" to (newDisplayName ?: ""),
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )

            batch.commit().await()

            Log.w(TAG, "Ban evasion captured (atomic): banId=$banId method=$evasionMethod")
        } catch (e: Exception) {
            Log.e(TAG, "appendIdentifiersToRecord failed", e)
        }
    }
}
