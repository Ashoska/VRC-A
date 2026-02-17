package com.scrapw.chatbox

import android.app.Application
import com.google.firebase.FirebaseApp
import com.scrapw.chatbox.data.UserPreferencesRepository

class ChatboxApplication : Application() {

    // Used by ChatboxViewModel.Factory
    lateinit var userPreferencesRepository: UserPreferencesRepository
        private set

    override fun onCreate() {
        super.onCreate()

        // ✅ Only initialize Firebase for the admin build variant.
        // (Public build doesn't need Firebase and won't pay startup cost.)
        if (BuildConfig.IS_ADMIN_BUILD) {
            runCatching { FirebaseApp.initializeApp(this) }
        }

        // ✅ Your repository expects a Context
        userPreferencesRepository = UserPreferencesRepository(applicationContext)
    }
}
