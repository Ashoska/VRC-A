// app/src/main/kotlin/com/scrapw/chatbox/ChatboxApplication.kt
package com.scrapw.chatbox

import android.app.Application
import android.content.Context
import com.scrapw.chatbox.data.UserPreferencesRepository
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

class ChatboxApplication : Application() {

    // Used by ChatboxViewModel.Factory
    lateinit var userPreferencesRepository: UserPreferencesRepository
        private set

    override fun onCreate() {
        super.onCreate()

        // Your repo expects Context
        userPreferencesRepository = UserPreferencesRepository(applicationContext)

        installCrashCapture()
    }

    private fun installCrashCapture() {
        val prefs = getSharedPreferences(CRASH_PREFS_FILE, Context.MODE_PRIVATE)

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                pw.println("=== VRC-A Crash Report ===")
                pw.println("timeMs=${System.currentTimeMillis()}")
                pw.println("thread=${thread.name}")
                pw.println("appId=${BuildConfig.APPLICATION_ID}")
                pw.println("versionName=${BuildConfig.VERSION_NAME}")
                pw.println("versionCode=${BuildConfig.VERSION_CODE}")
                pw.println("isAdmin=${BuildConfig.IS_ADMIN_BUILD}")
                pw.println()
                throwable.printStackTrace(pw)
                pw.flush()

                prefs.edit()
                    .putString(CRASH_KEY_TEXT, sw.toString())
                    .apply()
            } catch (_: Throwable) {
                // If crash logging fails, do nothing.
            }

            // Let Android / previous handler handle the actual crash dialog/kill
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                // Fallback hard kill
                exitProcess(10)
            }
        }
    }

    companion object {
        const val CRASH_PREFS_FILE = "vrca_crash"
        const val CRASH_KEY_TEXT = "last_crash_text"
    }
}
