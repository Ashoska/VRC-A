package com.scrapw.chatbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class AdminUser(
    val uid: String,
    val email: String
)

data class Punishment(
    val id: String,
    val targetUserId: String,
    val type: String, // "warn" or "ban"
    val reason: String,
    val createdAt: Timestamp,
    val active: Boolean
)

class AdminViewModel : ViewModel() {

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _adminUser = MutableStateFlow<AdminUser?>(null)
    val adminUser: StateFlow<AdminUser?> = _adminUser

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _punishments = MutableStateFlow<List<Punishment>>(emptyList())
    val punishments: StateFlow<List<Punishment>> = _punishments

    fun signIn(email: String, password: String) {
        _error.value = null
        viewModelScope.launch {
            _loading.value = true
            try {
                val result = auth.signInWithEmailAndPassword(email.trim(), password).await()
                val user = result.user
                if (user == null) {
                    _adminUser.value = null
                    _error.value = "Login failed."
                } else {
                    _adminUser.value = AdminUser(uid = user.uid, email = user.email ?: email.trim())
                    loadLatestPunishments()
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Login error"
            } finally {
                _loading.value = false
            }
        }
    }

    fun signOut() {
        auth.signOut()
        _adminUser.value = null
        _punishments.value = emptyList()
    }

    fun loadLatestPunishments(limit: Long = 50) {
        _error.value = null
        viewModelScope.launch {
            _loading.value = true
            try {
                val snap = db.collection("punishments")
                    .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                    .limit(limit)
                    .get()
                    .await()

                val list = snap.documents.mapNotNull { d ->
                    val targetUserId = d.getString("targetUserId") ?: return@mapNotNull null
                    val type = d.getString("type") ?: return@mapNotNull null
                    val reason = d.getString("reason") ?: ""
                    val createdAt = d.getTimestamp("createdAt") ?: Timestamp.now()
                    val active = d.getBoolean("active") ?: true

                    Punishment(
                        id = d.id,
                        targetUserId = targetUserId,
                        type = type,
                        reason = reason,
                        createdAt = createdAt,
                        active = active
                    )
                }

                _punishments.value = list
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load punishments"
            } finally {
                _loading.value = false
            }
        }
    }

    /**
     * Your rule:
     * - keep offending messages only
     * - warn/ban evidence can be permanent until cleared
     *
     * For now we store punishment records; evidence linking comes next step.
     */
    fun createPunishment(targetUserId: String, type: String, reason: String) {
        _error.value = null
        viewModelScope.launch {
            _loading.value = true
            try {
                val doc = hashMapOf(
                    "targetUserId" to targetUserId.trim(),
                    "type" to type.trim(),
                    "reason" to reason.trim(),
                    "createdAt" to Timestamp.now(),
                    "active" to true
                )
                db.collection("punishments").add(doc).await()
                loadLatestPunishments()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to create punishment"
            } finally {
                _loading.value = false
            }
        }
    }
}
