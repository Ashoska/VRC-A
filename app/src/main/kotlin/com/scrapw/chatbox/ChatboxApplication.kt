package com.scrapw.chatbox

import android.app.Application
import com.google.firebase.FirebaseApp
import com.scrapw.chatbox.data.UserPreferencesRepository
import com.scrapw.chatbox.data.dataStore

class ChatboxApplication : Application() {

    // keep your existing repository pattern
    lateinit var userPreferencesRepository: UserPreferencesRepository
        private set

    override fun onCreate() {
        super.onCreate()

        // ✅ Firebase init (works for both flavors as long as each flavor has its google-services.json)
        runCatching { FirebaseApp.initializeApp(this) }

        userPreferencesRepository = UserPreferencesRepository(dataStore)
    }
}
