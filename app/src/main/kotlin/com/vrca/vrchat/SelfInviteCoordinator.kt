package com.vrca.vrchat

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Two-sided state machine for the admin "Invite me to this instance" flow.
 *
 * The admin's own self-invite (`POST /invite/myself/to/{loc}`) is rejected for
 * INVITE-ONLY instances the admin has no standing in. This flow flips who invites:
 * the USER (who IS in the instance, so may invite anyone) invites the admin. It tries
 * that directly first; only if VRChat rejects the direct invite does it fall back to a
 * brief, hidden friend → invite → unfriend "dance" (some instance/account combos
 * require the invitee to be a friend of the inviter).
 *
 * Signaling is a small Firestore handshake on `users/{deviceHash}`:
 *   admin → user (owner-only keys): selfInviteReqId, selfInviteAdminId,
 *                                   selfInviteLocation, selfInviteAt
 *   user → admin (self-mutable):    selfInviteAckReqId (echoes reqId),
 *                                   selfInviteStatus (ok|need_friend|failed),
 *                                   selfInviteError, selfInviteStatusAt
 *
 * Safety invariants:
 *  - A counterpart is only ever unfriended if it was NOT a friend before the dance
 *    (checked via getFriendStatus; the fallback only runs when the DIRECT invite
 *    failed, which already implies they aren't friends). A real friendship is never
 *    touched.
 *  - The unfriend is force-scheduled 10s after the invite AND persisted to
 *    [SelfInviteStore.pendingUnfriend] BEFORE friending, so a connection dip / app
 *    death still cleans up on the next pipeline connect ([drainPendingUnfriend]).
 *  - Friend-list notifications about the counterpart are suppressed on BOTH phones
 *    for the whole window (owner id + pending-unfriend set); nothing else is hidden.
 */
object SelfInviteCoordinator {
    private const val TAG = "SelfInvite"

    // Timing budgets.
    private const val INVITE_RETRY_STEP_MS = 1_000L
    private const val FRIEND_POLL_MAX_MS = 18_000L
    private const val ADMIN_POLL_STEP_MS = 1_000L
    private const val ADMIN_POLL_MAX_MS = 35_000L
    private const val FORCE_UNFRIEND_DELAY_MS = 10_000L
    // After the unfriend we KEEP the counterpart in the suppression set a few more
    // seconds — the friend-delete WebSocket event arrives AFTER the unfriend REST call
    // returns, so removing suppression immediately let "removed from friends list" slip
    // through on the admin side. (The user side is covered permanently by ownerVrchatId.)
    private const val SUPPRESS_GRACE_MS = 8_000L

    private val db get() = FirebaseFirestore.getInstance()

    // Process-lifetime scope for the DETACHED unfriend cleanup so it doesn't block the
    // caller — the admin UI must acknowledge "invite sent" the instant it lands, not
    // wait out the 10s+8s unfriend window. The cleanup survives the UI coroutine / VM
    // being cancelled; the persisted pendingUnfriend backup + drain-on-connect is the
    // ultimate safety net if this process dies first.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Fire-and-forget the temporary-friendship teardown so callers return immediately. */
    private fun scheduleCleanup(ctx: Context, counterpartId: String) {
        val appCtx = ctx.applicationContext
        scope.launch {
            delay(FORCE_UNFRIEND_DELAY_MS)
            VrchatAuthManager.unfriendUser(appCtx, counterpartId)
            VrchatAuthManager.cancelFriendRequest(appCtx, counterpartId)
            delay(SUPPRESS_GRACE_MS)
            SelfInviteStore.removePendingUnfriend(appCtx, counterpartId)
        }
    }

    /* ============================ USER SIDE ============================ */

    /**
     * Runs on the USER's phone (from the moderation listener) when a fresh self-invite
     * signal lands. Tries the direct invite, falls back to the friend-dance, and writes
     * the outcome back so the admin's poll can report it.
     */
    suspend fun runUserSide(
        ctx: Context,
        deviceHash: String,
        adminId: String,
        location: String,
        reqId: String
    ) {
        if (deviceHash.isBlank() || adminId.isBlank() || location.isBlank()) return
        // Arm the global friend-notif exemption immediately, before any friend event
        // for the admin can arrive on the pipeline.
        SelfInviteStore.setOwnerVrchatId(ctx, adminId)

        suspend fun writeStatus(status: String, error: String = "") {
            runCatching {
                db.collection("users").document(deviceHash).set(
                    mapOf(
                        "selfInviteAckReqId" to reqId,
                        "selfInviteStatus" to status,
                        "selfInviteError" to error,
                        "selfInviteStatusAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                ).await()
            }.onFailure { Log.w(TAG, "user writeStatus failed", it) }
        }

        // 1. Direct invite (works whenever VRChat lets a user invite this account).
        var res = VrchatAuthManager.inviteUserToInstance(ctx, adminId, location)
        if (res.ok) { writeStatus("ok"); return }

        Log.i(TAG, "direct invite rejected (${res.error}) — falling back to friend-dance")

        // 2. Friend-dance fallback. Direct failing implies we aren't friends, but
        //    double-check so a real friendship is never severed by cleanup.
        val wasAlreadyFriend = VrchatAuthManager.getFriendStatus(ctx, adminId)?.isFriend == true
        if (!wasAlreadyFriend) {
            // Persist the cleanup obligation BEFORE friending (crash-safe) — this also
            // suppresses the incoming friend-list notifications about the admin.
            SelfInviteStore.addPendingUnfriend(ctx, adminId)
            VrchatAuthManager.sendFriendRequest(ctx, adminId) // user → admin
        }
        // Tell the admin to send ITS request (mutual request → VRChat auto-befriends).
        writeStatus("need_friend")

        // RETRY the invite directly rather than polling friendStatus: the invite
        // succeeds the instant we're actually friends server-side (the admin's mutual
        // request lands), WITHOUT waiting for VRChat's friendStatus to propagate — that
        // eventual-consistency lag was the ~10s stall before the invite fired.
        var invited = false
        var waited = 0L
        while (!invited && waited < FRIEND_POLL_MAX_MS) {
            delay(INVITE_RETRY_STEP_MS); waited += INVITE_RETRY_STEP_MS
            res = VrchatAuthManager.inviteUserToInstance(ctx, adminId, location)
            if (res.ok) invited = true
        }
        writeStatus(
            if (invited) "ok" else "failed",
            if (invited) "" else (res.error ?: "Couldn't establish the invite link in time")
        )

        // 3. Force-unfriend 10s after the invite, no matter the outcome — DETACHED so it
        //    doesn't hold this coroutine. The persisted pending-unfriend entry is the
        //    backup if the process dies first; the suppression is kept a grace window
        //    past the unfriend so the friend-delete event is swallowed too.
        if (!wasAlreadyFriend) scheduleCleanup(ctx, adminId)
    }

    /* ============================ ADMIN SIDE ============================ */

    /**
     * Runs on the ADMIN's phone from the "Invite me" button when the direct
     * `inviteSelfToInstance` failed. Signals the user's app, participates in the friend
     * handshake if the user needs it, and returns a user-facing result string.
     *
     * [targetVrchatId] is the user's VRChat account id (from the detail poll) — needed
     * for the admin's half of the mutual friend request. If blank, the friend-dance
     * can't complete, but the direct path on the user side may still succeed.
     */
    suspend fun runAdminSide(
        ctx: Context,
        targetDocId: String,
        adminId: String,
        location: String,
        targetVrchatId: String
    ): String {
        if (targetDocId.isBlank() || adminId.isBlank() || location.isBlank())
            return "Missing invite details"

        val reqId = "$adminId:${System.currentTimeMillis()}"

        // Record the owner VRChat id globally so every user app exempts friend-list
        // notifications about the admin (best-effort; the user side also learns it from
        // the signal). Owner-writable per rules.
        runCatching {
            db.collection("config").document("app")
                .set(mapOf("ownerVrchatId" to adminId), SetOptions.merge()).await()
        }

        val tvid = targetVrchatId.trim()
        val wasAlreadyFriend = tvid.isNotBlank() &&
            VrchatAuthManager.getFriendStatus(ctx, tvid)?.isFriend == true

        // Arm admin-side suppression + cleanup for the user's id BEFORE the user can
        // send us a friend request (so the "X sent you a friend request" event is
        // swallowed). Harmless if the direct invite ends up working.
        if (tvid.isNotBlank() && !wasAlreadyFriend) SelfInviteStore.addPendingUnfriend(ctx, tvid)

        // Write the signal.
        runCatching {
            db.collection("users").document(targetDocId).set(
                mapOf(
                    "selfInviteReqId" to reqId,
                    "selfInviteAdminId" to adminId,
                    "selfInviteLocation" to location,
                    "selfInviteAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()
        }.onFailure {
            Log.w(TAG, "admin signal write failed", it)
            return "Couldn't reach the user's app"
        }

        var result = "Timed out waiting for the user's app"
        var friendSent = false
        var waited = 0L
        while (waited < ADMIN_POLL_MAX_MS) {
            delay(ADMIN_POLL_STEP_MS); waited += ADMIN_POLL_STEP_MS
            val snap = runCatching {
                db.collection("users").document(targetDocId).get(Source.SERVER).await()
            }.getOrNull() ?: continue
            if (snap.getString("selfInviteAckReqId") != reqId) continue
            when (snap.getString("selfInviteStatus")) {
                "ok" -> { result = "Invite sent — check your VRChat notifications"; break }
                "failed" -> {
                    val err = snap.getString("selfInviteError").orEmpty()
                    result = "Invite failed" + (if (err.isNotBlank()) ": $err" else "")
                    break
                }
                "need_friend" -> {
                    if (!friendSent && tvid.isNotBlank() && !wasAlreadyFriend) {
                        VrchatAuthManager.sendFriendRequest(ctx, tvid) // admin → user (mutual)
                        friendSent = true
                    } else if (tvid.isBlank()) {
                        result = "Can't complete the invite (user not linked to VRChat)"
                        break
                    }
                }
            }
        }

        // Cleanup DETACHED so the admin UI acknowledges the result NOW instead of
        // waiting out the 10s+8s unfriend window (that stuck spinner was the bug). Keeps
        // the suppression a grace window past the unfriend so the friend-delete event
        // (which arrives after the REST call returns) is swallowed on the admin side too.
        if (tvid.isNotBlank() && !wasAlreadyFriend) scheduleCleanup(ctx, tvid)
        return result
    }

    /* ============================ CLEANUP SWEEP ============================ */

    /**
     * Drains the persisted pending-unfriend backup — unfriends anyone we still owe an
     * unfriend to and clears them. Called on every pipeline connect so a dance
     * interrupted by a connection dip / app death still severs the temporary friendship
     * on the next reconnect. Entries are only ever non-pre-existing friends, so this can
     * never remove a genuine friend.
     */
    suspend fun drainPendingUnfriend(ctx: Context) {
        val pending = SelfInviteStore.pendingUnfriend(ctx)
        if (pending.isEmpty()) return
        for (id in pending) {
            runCatching {
                VrchatAuthManager.unfriendUser(ctx, id)
                VrchatAuthManager.cancelFriendRequest(ctx, id)
            }
            SelfInviteStore.removePendingUnfriend(ctx, id)
            delay(300) // pace to stay under VRChat rate limits
        }
    }
}
