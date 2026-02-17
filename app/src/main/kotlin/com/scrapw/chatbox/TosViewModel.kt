package com.scrapw.chatbox

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scrapw.chatbox.data.SettingsStates
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TosViewModel(application: Application) : AndroidViewModel(application) {

    val tosAccepted: StateFlow<Boolean> =
        SettingsStates.tosAcceptedFlow(application.applicationContext)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun acceptTos() {
        viewModelScope.launch {
            SettingsStates.setTosAccepted(getApplication(), true)
        }
    }

    fun resetTos() {
        viewModelScope.launch {
            SettingsStates.setTosAccepted(getApplication(), false)
        }
    }
}
