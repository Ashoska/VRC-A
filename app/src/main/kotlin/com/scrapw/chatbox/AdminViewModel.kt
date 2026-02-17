package com.scrapw.chatbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class AdminUserRow(
    val userId: String,
    val displayName: String,
    val status: String, // "OK", "WARNED", "BANNED"
    val notes: String
)

class AdminViewModel : ViewModel() {

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _users = MutableStateFlow<List<AdminUserRow>>(emptyList())
    val users: StateFlow<List<AdminUserRow>> = _users

    init {
        // Placeholder so Admin screen shows something immediately.
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            delay(250)

            _users.value = listOf(
                AdminUserRow("user_001", "ExampleUser", "OK", "No actions."),
                AdminUserRow("user_002", "WarnedUser", "WARNED", "Case evidence retained."),
                AdminUserRow("user_003", "BannedUser", "BANNED", "Permanent until unbanned.")
            )

            _loading.value = false
        }
    }

    fun clearError() {
        _error.value = null
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return AdminViewModel() as T
            }
        }
    }
}
