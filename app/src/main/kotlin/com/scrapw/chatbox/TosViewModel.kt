package com.scrapw.chatbox

import androidx.annotation.MainThread
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class TosViewModel(
    private val app: ChatboxApplication
) : ViewModel() {

    private val KEY_TOS_ACCEPTED = booleanPreferencesKey("tos_accepted_v1")

    private val _tosAcceptedFlow = MutableStateFlow(false)
    val tosAcceptedFlow: StateFlow<Boolean> = _tosAcceptedFlow

    init {
        // Keep it updated from DataStore
        viewModelScope.launch {
            app.applicationContext.dataStore.data
                .map { prefs -> prefs[KEY_TOS_ACCEPTED] ?: false }
                .distinctUntilChanged()
                .collect { accepted -> _tosAcceptedFlow.value = accepted }
        }
    }

    fun setTosAccepted(accepted: Boolean) {
        viewModelScope.launch {
            app.applicationContext.dataStore.edit { prefs ->
                prefs[KEY_TOS_ACCEPTED] = accepted
            }
        }
    }

    companion object {
        private lateinit var instance: TosViewModel

        @MainThread
        fun isInstanceInitialized(): Boolean = ::instance.isInitialized

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as ChatboxApplication)
                instance = TosViewModel(application)
                instance
            }
        }

        @MainThread
        fun getInstance(): TosViewModel {
            if (!::instance.isInitialized) throw Exception("TosViewModel is not initialized!")
            return instance
        }
    }
}
