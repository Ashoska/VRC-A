// app/src/main/kotlin/com/vrca/ChatboxApplication.kt
package com.vrca

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import com.vrca.data.UserPreferencesRepository
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

class ChatboxApplication : Application() {

    companion object {
        // Used by ChatboxApp.kt to show last crash
        const val CRASH_PREFS_FILE = "vrca_crash"
        const val CRASH_KEY_TEXT = "last_crash_text"
    }

    // Used by ChatboxViewModel.Factory
    lateinit var userPreferencesRepository: UserPreferencesRepository
        private set

    override fun onCreate() {
        // Install crash handler as early as possible
        installCrashHandler()

        super.onCreate()

        // Your repo expects Context
        userPreferencesRepository = UserPreferencesRepository(applicationContext)
    }

    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)

                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

                pw.println("=== VRC-A LAST CRASH ===")
                pw.println("Time: $stamp")
                pw.println("Thread: ${t.name}")
                pw.println("Process: ${Process.myPid()}")
                pw.println("SDK: ${Build.VERSION.SDK_INT}")
                pw.println("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                pw.println("AppId: ${applicationContext.packageName}")
                pw.println("VersionName: ${BuildConfig.VERSION_NAME}")
                pw.println("VersionCode: ${BuildConfig.VERSION_CODE}")
                pw.println()
                e.printStackTrace(pw)
                pw.flush()

                val text = sw.toString().take(80_000) // keep prefs sane
                val prefs = applicationContext.getSharedPreferences(CRASH_PREFS_FILE, Context.MODE_PRIVATE)
                prefs.edit().putString(CRASH_KEY_TEXT, text).apply()
            } catch (_: Throwable) {
                // If even this fails, fall through to previous handler
            }

            // Let Android still treat it as a crash
            prev?.uncaughtException(t, e) ?: run {
                // Last resort
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }
}
