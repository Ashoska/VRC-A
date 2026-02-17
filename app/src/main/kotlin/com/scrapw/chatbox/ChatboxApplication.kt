package com.scrapw.chatbox

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.scrapw.chatbox.data.UserPreferencesRepository

class ChatboxApplication : Application() {

    // Used by ChatboxViewModel.Factory
    lateinit var userPreferencesRepository: UserPreferencesRepository
        private set

    override fun onCreate() {
        super.onCreate()

        // ✅ Firebase init (SAFE):
        // If a flavor is missing / has wrong google-services.json, this can crash without a screen.
        // We catch it so the app can still open and show UI + errors.
        try {
            FirebaseApp.initializeApp(this)
            Log.d("ChatboxApplication", "Firebase initialized")
        } catch (t: Throwable) {
            Log.e("ChatboxApplication", "Firebase init failed (app will continue)", t)
        }

        // ✅ IMPORTANT:
        // UserPreferencesRepository expects a Context (not a DataStore object).
        userPreferencesRepository = UserPreferencesRepository(applicationContext)
    }
}
