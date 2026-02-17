package com.scrapw.chatbox

import android.app.Application
import com.scrapw.chatbox.data.UserPreferencesRepository

class ChatboxApplication : Application() {

    // Used by ChatboxViewModel.Factory
    lateinit var userPreferencesRepository: UserPreferencesRepository
        private set

    override fun onCreate() {
        super.onCreate()

        // ✅ IMPORTANT:
        // UserPreferencesRepository in your project expects a Context (not a DataStore object).
        // Passing applicationContext fixes: "Type mismatch: DataStore<Preferences> but Context expected".
        userPreferencesRepository = UserPreferencesRepository(applicationContext)
    }
}
