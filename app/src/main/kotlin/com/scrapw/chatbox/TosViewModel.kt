// app/src/main/kotlin/com/scrapw/chatbox/tos/TosViewModel.kt
package com.scrapw.chatbox.tos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.scrapw.chatbox.ChatboxApplication
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

class TosViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        // ✅ Bump this number whenever you change ToS text/rules.
        const val CURRENT_TOS_VERSION = 1

        val Factory: ViewModelProvider.Factory =
            ViewModelProvider.AndroidViewModelFactory.getInstance
                ((null as Application?) ?: throw IllegalStateException("Factory misuse"))
    }

    private val repo = (app as ChatboxApplication).userPreferencesRepository

    val currentTosVersion: Int = CURRENT_TOS_VERSION

    val tosAccepted: StateFlow<Boolean> = repo.tosAcceptedStateFlow(currentTosVersion)

    fun acceptTos() {
        viewModelScope.launch {
            repo.saveTosAcceptedVersion(currentTosVersion)
        }
    }

    fun declineAndExit() {
        // Hard exit (you asked: must accept to use).
        exitProcess(0)
    }

    object Factory : ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            throw IllegalStateException("Use viewModel(factory = TosViewModel.Factory) with AndroidViewModelFactory? No.")
        }
    }
}
