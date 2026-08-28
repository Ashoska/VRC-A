package com.vrca.admin

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Admin-side orchestrator for "move VRChat login to another device".
 *
 * The admin can't move credentials directly — it relays an END-TO-END-ENCRYPTED handoff between
 * two of the account's devices (both must be online; their moderation listeners drive it):
 *   1. tell the TARGET to make a one-time keypair       → target publishes its PUBLIC key
 *   2. give the SOURCE that public key                  → source seals its session to it
 *   3. relay the sealed bundle to the TARGET            → target decrypts, imports, logs in
 *   4. on success, sign the SOURCE out (frees the single-session lock so the target claims it)
 *
 * The admin only ever handles ciphertext (sealed to the target's key) — it never sees the
 * credentials. Runs on a process-lifetime scope so it survives admin-UI recomposition; a
 * StateFlow drives the progress UI.
 */
object AuthTransferCoordinator {
    data class Progress(val stage: String, val message: String, val done: Boolean = false, val error: Boolean = false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db get() = FirebaseFirestore.getInstance()
    private val _state = MutableStateFlow<Progress?>(null)
    val state: StateFlow<Progress?> = _state

    @Volatile private var job: Job? = null

    private const val STEP_TIMEOUT_MS = 45_000L

    private sealed class Step { data class Ok(val value: String) : Step(); data class Err(val code: String) : Step() }

    fun clear() { if (job?.isActive != true) _state.value = null }
    fun cancel() { job?.cancel(); _state.value = null }
    val isRunning: Boolean get() = job?.isActive == true

    /** Move the VRChat login from [sourceDocId] to [targetDocId]. Both devices must be online. */
    fun start(sourceDocId: String, targetDocId: String, sourceLabel: String, targetLabel: String) {
        if (sourceDocId.isBlank() || targetDocId.isBlank() || sourceDocId == targetDocId) {
            _state.value = Progress("error", "Pick a different target device.", error = true); return
        }
        if (job?.isActive == true) return
        job = scope.launch { runTransfer(sourceDocId, targetDocId, sourceLabel, targetLabel) }
    }

    private suspend fun runTransfer(sourceId: String, targetId: String, sourceLabel: String, targetLabel: String) {
        val reqId = UUID.randomUUID().toString()
        val src = db.collection("users").document(sourceId)
        val tgt = db.collection("users").document(targetId)
        try {
            // 1. Ask the TARGET to prepare a one-time key.
            _state.value = Progress("prepare", "Asking $targetLabel to prepare a secure channel…")
            tgt.set(mapOf(
                "transferReqId" to reqId, "transferRole" to "target", "transferPeerHash" to sourceId,
                "transferAt" to FieldValue.serverTimestamp(),
                "transferPayload" to "", "transferPubKey" to ""
            ), SetOptions.merge()).await()

            val pub = when (val r = awaitStep(tgt, reqId, "transferPubKeyOut")) {
                is Step.Ok -> r.value
                is Step.Err -> return fail(errorText(r.code))
                null -> return fail("$targetLabel didn't respond. Make sure that app is open and online, then try again.")
            }

            // 2. Give the SOURCE the target's public key to seal its session to.
            _state.value = Progress("seal", "Asking $sourceLabel to hand over its login securely…")
            src.set(mapOf(
                "transferReqId" to reqId, "transferRole" to "source", "transferPubKey" to pub,
                "transferPeerHash" to targetId, "transferAt" to FieldValue.serverTimestamp()
            ), SetOptions.merge()).await()

            val sealed = when (val r = awaitStep(src, reqId, "transferPayloadOut")) {
                is Step.Ok -> r.value
                is Step.Err -> return fail(errorText(r.code))
                null -> return fail("$sourceLabel didn't respond. Make sure that device is open and online, then try again.")
            }

            // 3. Relay the sealed bundle to the TARGET to import + log in.
            _state.value = Progress("import", "Sending the login to $targetLabel…")
            tgt.set(mapOf(
                "transferPayload" to sealed, "transferReqId" to reqId,
                "transferAt" to FieldValue.serverTimestamp()
            ), SetOptions.merge()).await()

            when (val r = awaitStep(tgt, reqId, statusMode = true)) {
                is Step.Ok -> { /* imported */ }
                is Step.Err -> return fail(errorText(r.code))
                null -> return fail("$targetLabel didn't finish signing in. Try again with it open.")
            }

            // 4. Sign the SOURCE out so the single-session lock frees and the target claims it.
            _state.value = Progress("finalize", "Signing $sourceLabel out so $targetLabel can take over…")
            runCatching {
                AccountModeration.applyAccountWide(db, sourceId, mapOf("logoutVrchatAt" to FieldValue.serverTimestamp()))
                val vid = (src.get().await().getString("vrchatUserId") ?: "").trim()
                if (vid.isNotBlank()) db.collection("accounts").document(vid).delete().await()
            }
            clearTransferFields(src); clearTransferFields(tgt)
            _state.value = Progress("done", "Done — $targetLabel is now signed in. $sourceLabel was signed out.", done = true)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            fail(e.message ?: "Transfer failed.")
        }
    }

    private fun fail(msg: String) { _state.value = Progress("error", msg, error = true) }

    /** Listen to [ref] until this transfer's device acks with the wanted field (or a status).
     *  Returns Ok(value)/Err(code), or null on timeout. One snapshot listener, no polling. */
    private suspend fun awaitStep(ref: DocumentReference, reqId: String, field: String? = null, statusMode: Boolean = false): Step? =
        withTimeoutOrNull(STEP_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                var reg: ListenerRegistration? = null
                reg = ref.addSnapshotListener { snap, _ ->
                    val r = snap?.let { evalStep(it, reqId, field, statusMode) } ?: return@addSnapshotListener
                    if (cont.isActive) { reg?.remove(); cont.resumeWith(Result.success(r)) }
                }
                cont.invokeOnCancellation { reg?.remove() }
            }
        }

    private fun evalStep(s: DocumentSnapshot, reqId: String, field: String?, statusMode: Boolean): Step? {
        if (s.getString("transferAckReqId") != reqId) return null
        if (s.getString("transferStatus") == "error") return Step.Err(s.getString("transferError").orEmpty())
        if (statusMode) return if (s.getString("transferStatus") == "imported") Step.Ok("") else null
        val v = s.getString(field!!).orEmpty()
        return if (v.isNotBlank()) Step.Ok(v) else null
    }

    private fun errorText(code: String): String = when (code) {
        "no_saved_password" ->
            "That device has no saved password to move. It was signed in a way that didn't store one, so its login can't be transferred."
        "login_failed" ->
            "The target couldn't sign in with the transferred login — VRChat asked for a 2FA code. The trusted-device window may have lapsed."
        "decrypt_failed", "no_key" -> "The secure channel failed. Try the transfer again."
        "keygen_failed", "seal_failed" -> "A device failed to set up the secure channel. Try again."
        else -> "Transfer failed ($code)."
    }

    private suspend fun clearTransferFields(ref: DocumentReference) {
        runCatching {
            ref.set(mapOf(
                "transferReqId" to FieldValue.delete(), "transferRole" to FieldValue.delete(),
                "transferAt" to FieldValue.delete(), "transferPubKey" to FieldValue.delete(),
                "transferPayload" to FieldValue.delete(), "transferPeerHash" to FieldValue.delete(),
                "transferPubKeyOut" to FieldValue.delete(), "transferPayloadOut" to FieldValue.delete(),
                "transferAckReqId" to FieldValue.delete(), "transferStatus" to FieldValue.delete(),
                "transferError" to FieldValue.delete(), "transferStatusAt" to FieldValue.delete()
            ), SetOptions.merge()).await()
        }
    }
}
