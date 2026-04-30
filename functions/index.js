const { onSchedule } = require("firebase-functions/v2/scheduler");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");

initializeApp();

/**
 * Runs every 5 minutes. Finds users with isOnlineInApp=true whose
 * lastSeenAt is older than 5 minutes and flips them to offline.
 *
 * Cost: reads only the stale subset (composite index on isOnlineInApp +
 * lastSeenAt), then one batched write per stale user. No traffic when
 * nobody is stale.
 *
 * Requires composite index:
 *   Collection: users
 *   Fields: isOnlineInApp ASC, lastSeenAt ASC
 */
exports.cleanupStaleOnline = onSchedule("every 5 minutes", async () => {
  const db = getFirestore();
  const cutoff = new Date(Date.now() - 5 * 60 * 1000);

  const stale = await db
    .collection("users")
    .where("isOnlineInApp", "==", true)
    .where("lastSeenAt", "<", cutoff)
    .get();

  if (stale.empty) {
    console.log("No stale-online users found.");
    return;
  }

  console.log(`Found ${stale.size} stale-online user(s). Cleaning up...`);

  // Firestore batch limit is 500; chunk if needed.
  const docs = stale.docs;
  for (let i = 0; i < docs.length; i += 500) {
    const batch = db.batch();
    const chunk = docs.slice(i, i + 500);
    for (const doc of chunk) {
      batch.update(doc.ref, {
        isOnlineInApp: false,
        lastSeenAt: FieldValue.serverTimestamp(),
      });
    }
    await batch.commit();
    console.log(`Batch ${Math.floor(i / 500) + 1}: cleaned ${chunk.length} user(s).`);
  }
});
