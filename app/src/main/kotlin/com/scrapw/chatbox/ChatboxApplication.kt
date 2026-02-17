// app/src/main/kotlin/com/scrapw/chatbox/ChatboxApplication.kt
package com.scrapw.chatbox

import android.app.Application
import com.scrapw.chatbox.data.UserPreferencesRepository
import com.google.firebase.FirebaseApp

class ChatboxApplication : Application() {

    lateinit var userPreferencesRepository: UserPreferencesRepository
        private set

    override fun onCreate() {
        super.onCreate()

        // ✅ Safe init (no-op if already initialized)
        runCatching { FirebaseApp.initializeApp(this) }

        userPreferencesRepository = UserPreferencesRepository(this)
    }
}
