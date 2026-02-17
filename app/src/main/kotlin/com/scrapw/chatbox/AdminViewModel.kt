// app/src/main/kotlin/com/scrapw/chatbox/ui/admin/AdminViewModel.kt
package com.scrapw.chatbox.ui.admin

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

enum class AdminAction { Warn, Ban, Note }

data class AdminUiState(
    val isSignedIn: Boolean = false,
    val signedInAs: String = "(not signed in)",
    val error: String = "",
    val lastWriteStatus: String = "",
    val cleanupStatus: String = ""
)

class AdminViewModel : ViewModel() {

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return AdminViewModel() as T
            }
        }
    }

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    private val _ui = MutableStateFlow(AdminUiState())
    val ui: StateFlow<AdminUiState> = _ui

    init {
        val user = auth.currentUser
        _ui.value = _ui.value.copy(
            isSignedIn = user != null,
            signedInAs = user?.email ?: "(not signed in)"
        )
    }

    fun signIn(email: String, password: String) {
        _ui.value = _ui.value.copy(error = "", lastWriteStatus = "", cleanupStatus = "")
        auth.signInWithEmailAndPassword(email, password)
            .addOnSuccessListener {
                val u = auth.currentUser
                _ui.value = _ui.value.copy(
                    isSignedIn = true,
                    signedInAs = u?.email ?: "(signed in)",
                    error = ""
                )
            }
            .addOnFailureListener { e ->
                _ui.value = _ui.value.copy(error = e.message ?: "Sign-in failed")
            }
    }

    fun signOut() {
        auth.signOut()
        _ui.value = _ui.value.copy(
            isSignedIn = false,
            signedInAs = "(not signed in)",
            error = "",
            lastWriteStatus = "",
            cleanupStatus = ""
        )
    }

    /**
     * Firestore layout:
     * - punishments/{punishmentId}
     *   fields: targetUser, action, reason, createdAt, status(active/cleared), clearedAt?
     * - evidence/{evidenceId}
     *   fields: targetUser, punishmentId, message, createdAt, expiresAt? (Timestamp or null)
     *
     * Retention rule:
     * - If action is Warn/Ban: evidence.expiresAt = null (kept until case cleared)
     * - If action is Note: evidence.expiresAt = now + 90 days
     * - When you later implement "clear punishment": set evidence.expiresAt = now + 90 days
     */
    fun createPunishment(
        targetUser: String,
        action: AdminAction,
        reason: String,
        evidenceLines: List<String>
    ) {
        if (auth.currentUser == null) {
            _ui.value = _ui.value.copy(error = "You must sign in first.")
            return
        }

        _ui.value = _ui.value.copy(error = "", lastWriteStatus = "", cleanupStatus = "")

        val createdAt = Timestamp.now()

        val punishRef = db.collection("punishments").document()
        val punishmentId = punishRef.id

        val punishmentDoc = hashMapOf(
            "punishmentId" to punishmentId,
            "targetUser" to targetUser,
            "action" to action.name,
            "reason" to reason,
            "status" to "active",
            "createdAt" to createdAt,
            "createdBy" to (auth.currentUser?.uid ?: "")
        )

        punishRef.set(punishmentDoc)
            .addOnSuccessListener {
                if (evidenceLines.isEmpty()) {
                    _ui.value = _ui.value.copy(lastWriteStatus = "Punishment created (no evidence attached).")
                    return@addOnSuccessListener
                }

                val batch = db.batch()

                val expiresAt: Timestamp? = when (action) {
                    AdminAction.Warn, AdminAction.Ban -> null
                    AdminAction.Note -> Timestamp(
                        java.util.Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(90))
                    )
                }

                evidenceLines.forEach { msg ->
                    val evRef = db.collection("evidence").document()
                    val evDoc = hashMapOf(
                        "evidenceId" to evRef.id,
                        "targetUser" to targetUser,
                        "punishmentId" to punishmentId,
                        "message" to msg,
                        "createdAt" to createdAt,
                        "expiresAt" to expiresAt, // null means "keep until cleared"
                        "createdBy" to (auth.currentUser?.uid ?: "")
                    )
                    batch.set(evRef, evDoc)
                }

                batch.commit()
                    .addOnSuccessListener {
                        _ui.value = _ui.value.copy(lastWriteStatus = "Punishment + ${evidenceLines.size} evidence line(s) saved.")
                    }
                    .addOnFailureListener { e ->
                        _ui.value = _ui.value.copy(error = e.message ?: "Failed to write evidence")
                    }
            }
            .addOnFailureListener { e ->
                _ui.value = _ui.value.copy(error = e.message ?: "Failed to create punishment")
            }
    }

    /**
     * Deletes evidence where expiresAt < now.
     * (Evidence with expiresAt == null is kept.)
     */
    fun runEvidenceCleanup() {
        if (auth.currentUser == null) {
            _ui.value = _ui.value.copy(error = "You must sign in first.")
            return
        }

        _ui.value = _ui.value.copy(error = "", cleanupStatus = "Running cleanup...")

        val now = Timestamp.now()

        db.collection("evidence")
            .whereLessThan("expiresAt", now)
            .get()
            .addOnSuccessListener { snap ->
                if (snap.isEmpty) {
                    _ui.value = _ui.value.copy(cleanupStatus = "No expired evidence found.")
                    return@addOnSuccessListener
                }

                val batch = db.batch()
                snap.documents.forEach { d -> batch.delete(d.reference) }

                batch.commit()
                    .addOnSuccessListener {
                        _ui.value = _ui.value.copy(cleanupStatus = "Deleted ${snap.size()} expired evidence docs.")
                    }
                    .addOnFailureListener { e ->
                        _ui.value = _ui.value.copy(error = e.message ?: "Cleanup failed")
                    }
            }
            .addOnFailureListener { e ->
                Log.e("AdminCleanup", "query failed", e)
                _ui.value = _ui.value.copy(error = e.message ?: "Cleanup query failed")
            }
    }
}
