// app/src/main/kotlin/com/vrca/AdminViewModel.kt
package com.vrca.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

// =========================
// Models shown in Admin UI
// =========================

data class AdminUserRow(
    val userId: String,
    val displayName: String,
    val warned: Boolean,
    val banned: Boolean
)

data class AdminAnnouncement(
    val id: String,
    val title: String,
    val body: String,
    val active: Boolean,
    val priority: Int,
    val startsAt: Timestamp?,
    val endsAt: Timestamp?,
    val createdAt: Timestamp?
)

data class AdminTosConfig(
    val version: Int,
    val text: String,
    val url: String
)

private object AdminSchema {
    // You can change these names later without touching UI code
    const val COL_USERS = "users"               // users/{uid}
    const val COL_ANNOUNCEMENTS = "announcements" // announcements/{id}
    const val COL_ACTIONS = "actions"           // actions/{autoId} (audit trail)
    const val COL_CONFIG = "config"             // config/app

    const val DOC_APP = "app"
}

// =========================
// ViewModel
// =========================

class AdminViewModel(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore
) : ViewModel() {

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _users = MutableStateFlow<List<AdminUserRow>>(emptyList())
    val users: StateFlow<List<AdminUserRow>> = _users

    private val _announcements = MutableStateFlow<List<AdminAnnouncement>>(emptyList())
    val announcements: StateFlow<List<AdminAnnouncement>> = _announcements

    private val _tosConfig = MutableStateFlow(AdminTosConfig(version = 1, text = "", url = ""))
    val tosConfig: StateFlow<AdminTosConfig> = _tosConfig

    private val _authEmail = MutableStateFlow(auth.currentUser?.email ?: "")
    val authEmail: StateFlow<String> = _authEmail

    init {
        // Admin UI expects something immediately:
        refresh()
    }

    fun clearError() {
        _error.value = null
    }

    // -------------------------
    // Auth helpers (optional UI later)
    // -------------------------

    fun signInEmailPassword(email: String, password: String) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                auth.signInWithEmailAndPassword(email.trim(), password).await()
                _authEmail.value = auth.currentUser?.email ?: ""
                refresh()
            } catch (t: Throwable) {
                _error.value = "Sign-in failed: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                _loading.value = false
            }
        }
    }

    fun signOut() {
        auth.signOut()
        _authEmail.value = ""
        // Keep data visible; real access control should be via Firestore rules.
    }

    // -------------------------
    // Core loads
    // -------------------------

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                loadTos()
                loadAnnouncements()
                loadUsers()
            } catch (t: Throwable) {
                _error.value = t.message ?: t.javaClass.simpleName
            } finally {
                _loading.value = false
            }
        }
    }

    private suspend fun loadUsers() {
        // users/{deviceHash} documents use boolean flags: warned, banned (+ warnReason, banReason).
        // There is no "status" string field — the public app reads banned/warned booleans.
        val snap = db.collection(AdminSchema.COL_USERS)
            .orderBy("updatedAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(200)
            .get()
            .await()

        val list = snap.documents.map { d ->
            val uid = d.id
            val name = (d.getString("displayName") ?: d.getString("vrchatDisplayName") ?: uid).trim()
            val warned = d.getBoolean("warned") ?: false
            val banned = d.getBoolean("banned") ?: false
            AdminUserRow(
                userId = uid,
                displayName = name,
                warned = warned,
                banned = banned
            )
        }

        _users.value = list
    }

    private suspend fun loadAnnouncements() {
        val snap = db.collection(AdminSchema.COL_ANNOUNCEMENTS)
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(50)
            .get()
            .await()

        _announcements.value = snap.documents.map { d ->
            AdminAnnouncement(
                id = d.id,
                title = d.getString("title") ?: "(untitled)",
                body = d.getString("body") ?: "",
                active = d.getBoolean("active") ?: false,
                priority = (d.getLong("priority") ?: 0L).toInt(),
                startsAt = d.getTimestamp("startsAt"),
                endsAt = d.getTimestamp("endsAt"),
                createdAt = d.getTimestamp("createdAt")
            )
        }
    }

    private suspend fun loadTos() {
        val doc = db.collection(AdminSchema.COL_CONFIG)
            .document(AdminSchema.DOC_APP)
            .get()
            .await()

        val v = (doc.getLong("tosVersion") ?: 1L).toInt()
        val text = doc.getString("tosText") ?: ""
        val url = doc.getString("tosUrl") ?: ""
        _tosConfig.value = AdminTosConfig(version = v, text = text, url = url)
    }

    // -------------------------
    // Admin actions (used by Admin UI later)
    // -------------------------

    fun warnUser(userId: String, reason: String) = runAction {
        // Write warned=true + warnReason. These are in ownerOnlyUserKeys() so Firestore rules allow it.
        // The public app reads `warned: Boolean` — do NOT write a "status" string; it's not in the rules.
        val ref = db.collection(AdminSchema.COL_USERS).document(userId)
        ref.set(
            mapOf(
                "warned" to true,
                "warnReason" to reason,
                "warnedAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            com.google.firebase.firestore.SetOptions.merge()
        ).await()
        audit("warn", userId, mapOf("reason" to reason))
    }

    fun banUser(userId: String, reason: String) = runAction {
        // Write banned=true + banReason. These are in ownerOnlyUserKeys() so Firestore rules allow it.
        // The public app reads `banned: Boolean` — do NOT write a "status" string; it's not in the rules.
        val ref = db.collection(AdminSchema.COL_USERS).document(userId)
        ref.set(
            mapOf(
                "banned" to true,
                "banReason" to reason,
                "bannedAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            com.google.firebase.firestore.SetOptions.merge()
        ).await()
        audit("ban", userId, mapOf("reason" to reason))
    }

    fun clearPunishment(userId: String) = runAction {
        // Clear both warned and banned flags. ownerOnlyUserKeys() allows writing these fields.
        val ref = db.collection(AdminSchema.COL_USERS).document(userId)
        ref.set(
            mapOf(
                "warned" to false,
                "banned" to false,
                "warnReason" to FieldValue.delete(),
                "banReason" to FieldValue.delete(),
                "warnedAt" to FieldValue.delete(),
                "bannedAt" to FieldValue.delete(),
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            com.google.firebase.firestore.SetOptions.merge()
        ).await()
        audit("clear_punishment", userId, emptyMap<String, Any?>())
    }

    fun createAnnouncement(
        title: String,
        body: String,
        active: Boolean,
        priority: Int = 0,
        startsAt: Timestamp? = null,
        endsAt: Timestamp? = null
    ) = runAction {
        val data = mutableMapOf<String, Any?>(
            "title" to title,
            "body" to body,
            "active" to active,
            "priority" to priority,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (startsAt != null) data["startsAt"] = startsAt
        if (endsAt != null) data["endsAt"] = endsAt

        val ref = db.collection(AdminSchema.COL_ANNOUNCEMENTS).document()
        ref.set(data).await()
        audit("create_announcement", ref.id, mapOf("title" to title, "active" to active, "priority" to priority))
    }

    fun setAnnouncementActive(id: String, active: Boolean) = runAction {
        db.collection(AdminSchema.COL_ANNOUNCEMENTS)
            .document(id)
            .set(
                mapOf(
                    "active" to active,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                com.google.firebase.firestore.SetOptions.merge()
            ).await()
        audit("set_announcement_active", id, mapOf("active" to active))
    }

    fun updateTos(version: Int, text: String, url: String) = runAction {
        db.collection(AdminSchema.COL_CONFIG)
            .document(AdminSchema.DOC_APP)
            .set(
                mapOf(
                    "tosVersion" to version,
                    "tosText" to text,
                    "tosUrl" to url,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                com.google.firebase.firestore.SetOptions.merge()
            ).await()
        audit("update_tos", AdminSchema.DOC_APP, mapOf("tosVersion" to version))
    }

    // -------------------------
    // Internals
    // -------------------------

    private fun runAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                block()
                // refresh after write so UI updates
                loadUsers()
                loadAnnouncements()
                loadTos()
            } catch (t: Throwable) {
                _error.value = t.message ?: t.javaClass.simpleName
            } finally {
                _loading.value = false
            }
        }
    }

    private suspend fun audit(action: String, targetId: String, extra: Map<String, Any?>) {
        // Optional audit trail (great for later)
        val actor = auth.currentUser?.uid ?: "unknown"
        val email = auth.currentUser?.email ?: ""
        val data = mutableMapOf<String, Any?>(
            "action" to action,
            "targetId" to targetId,
            "actorUid" to actor,
            "actorEmail" to email,
            "createdAt" to FieldValue.serverTimestamp()
        )
        extra.forEach { (k, v) -> data[k] = v }
        db.collection(AdminSchema.COL_ACTIONS).add(data).await()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return AdminViewModel(
                    auth = FirebaseAuth.getInstance(),
                    db = FirebaseFirestore.getInstance()
                ) as T
            }
        }
    }
}
