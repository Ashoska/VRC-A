package com.vrca.admin

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

/**
 * Account-wide moderation fan-out.
 *
 * Per the single-session model, the admin directory collapses every device that's
 * logged into the same VRChat account into ONE row. When the admin moderates that
 * row (warn / ban / clear / kill), the action must hit EVERY device on that account
 * — otherwise a banned user just opens VRC-A on their other phone and dodges it.
 *
 * This reads the target's `vrchatUserId`, finds every `users/{deviceHash}` doc that
 * shares it, and writes the moderation [fields] to all of them in a single batch
 * (the target included). If the target never logged into VRChat (blank
 * `vrchatUserId`) there are no siblings, so it writes just the target — identical to
 * the old per-device behavior.
 *
 * Device bans (`bannedDevices/{deviceHash}`) are intentionally NOT fanned out here —
 * a device ban is by definition per-physical-device.
 */
object AccountModeration {

    /**
     * Apply [fields] (merge) to the target doc and every sibling sharing its
     * `vrchatUserId`. Returns the number of docs written. Safe to call from a
     * moderation coroutine; throws on Firestore failure so callers' existing
     * try/catch surfaces it.
     */
    suspend fun applyAccountWide(
        db: FirebaseFirestore,
        targetDocId: String,
        fields: Map<String, Any>
    ): Int {
        if (targetDocId.isBlank()) return 0

        val targetSnap = db.collection("users").document(targetDocId).get().await()
        val vid = (targetSnap.getString("vrchatUserId") ?: "").trim()

        // LinkedHashSet de-dupes (the sibling query returns the target too) while
        // keeping the target first.
        val docIds = LinkedHashSet<String>()
        docIds.add(targetDocId)
        if (vid.isNotBlank()) {
            val siblings = db.collection("users")
                .whereEqualTo("vrchatUserId", vid)
                .get()
                .await()
            for (d in siblings.documents) docIds.add(d.id)
        }

        val batch = db.batch()
        for (id in docIds) {
            batch.set(db.collection("users").document(id), fields, SetOptions.merge())
        }
        batch.commit().await()
        return docIds.size
    }
}
