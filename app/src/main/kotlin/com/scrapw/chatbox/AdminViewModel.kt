// app/src/main/kotlin/com/scrapw/chatbox/AdminViewModel.kt
package com.scrapw.chatbox

import android.util.Log
import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

class AdminViewModel : ViewModel() {

    var statusText by mutableStateOf("Ready")
        private set

    init {
        Log.d("AdminViewModel", "Init")
    }

    companion object {
        private lateinit var instance: AdminViewModel

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                instance = AdminViewModel()
                instance
            }
        }

        @MainThread
        fun isInstanceInitialized(): Boolean = ::instance.isInitialized

        @MainThread
        fun getInstance(): AdminViewModel {
            if (!::instance.isInitialized) throw Exception("AdminViewModel is not initialized!")
            return instance
        }
    }
}
