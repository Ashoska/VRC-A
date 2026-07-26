package com.vrca.app

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.vrca.BuildConfig
import com.vrca.data.UserPreferencesRepository
import com.vrca.ui.viewmodel.VrcaViewModel
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Application is also a [ViewModelStoreOwner] so the core runtime ViewModel
 * ([com.vrca.ui.viewmodel.VrcaViewModel]) can be scoped to the PROCESS, not the
 * Activity. This keeps the chatbox senders, moderation/kill listener, NowPlaying
 * consumer and Firestore sync loops alive while the app is backgrounded and the
 * Activity is destroyed — they are torn down only by [AppShutdown] on a real swipe.
 */
class VrcaApplication : Application(), ViewModelStoreOwner {

    companion object {
        const val CRASH_PREFS_FILE = "vrca_crash"
        const val CRASH_KEY_TEXT = "last_crash_text"

        // Process-wide handle so the runtime ViewModel factory can resolve the
        // Application without relying on CreationExtras[APPLICATION_KEY], which is
        // absent when the VM is obtained against the app-scoped ViewModelStore.
        lateinit var instance: VrcaApplication
            private set

        // Snapshot of AppShutdown.isSwipedAway() captured at the TOP of
        // MainActivity.onCreate, BEFORE clearSwipedAway() wipes the flag. This is the
        // SAME persistent swipe marker FeatureSessionStore.disarm() keys on to tell a
        // deliberate swipe apart from an OS/OEM kill: true ONLY when the last shutdown
        // was a clean user swipe (an OS/OEM/force kill never runs onTaskSwiped, so it
        // stays false). VrcaApp uses it to show the boot screen ONLY on a genuine
        // swipe-reopen (a deliberate fresh start) and skip it for every other relaunch
        // (OEM/force kill, Activity recreate, headless revival), matching how the Quick
        // Toggles decide whether to restore vs start clean.
        @Volatile
        var openedFromSwipe: Boolean = false
    }

    // Process-lifetime ViewModelStore. Cleared only by AppShutdown on swipe.
    override val viewModelStore: ViewModelStore = ViewModelStore()

    lateinit var userPreferencesRepository: UserPreferencesRepository
        private set

    override fun onCreate() {
        // Install crash handler as early as possible
        installCrashHandler()

        super.onCreate()

        instance = this

        // Your repo expects Context
        userPreferencesRepository = UserPreferencesRepository(applicationContext)

        // Seed the last-known NowPlaying track (as paused) BEFORE any ViewModel
        // collects NowPlayingState, so a headless revival / cold start shows the
        // previous (possibly paused) track immediately instead of blanking.
        com.vrca.nowplaying.NowPlayingState.attach(applicationContext)

        // Lifetime chatbox-send counter (boot screen stat).
        ChatboxStats.attach(applicationContext)

        // Cap Firestore offline cache (default is 100 MB — far more than needed).
        FirebaseFirestore.getInstance().firestoreSettings =
            FirebaseFirestoreSettings.Builder()
                .setCacheSizeBytes(25L * 1024 * 1024)
                .build()

        cleanStaleUpdateApks()

        // Free any on-demand OSC tutorial images left over from a replay that was
        // killed mid-way: on a fresh process where onboarding is already COMPLETE
        // there's no reason to keep them. A new user still mid-onboarding is NOT
        // complete, so their prefetched images survive a close/kill (resume works).
        clearStaleTutorialImages()

        // Rich-content UPDATE-scope media (patch-note images/videos/gifs) is
        // view-scoped — normally cleared when the Update / What's New dialog is
        // dismissed. But a FORCED update dialog killed mid-show (process death, or
        // the update reinstalling) never disposes, so its cached media (a patch-note
        // video can be several MB) would linger in DATA forever. Wipe upd/ on every
        // cold start; it re-downloads the moment a dialog shows. Also GC any orphaned
        // rich media not currently referenced. (ann/ is culled live by VrcaScreen.)
        runCatching {
            com.vrca.richcontent.RichMediaStore.clearUpdateMedia(applicationContext)
        }

        // Track app foreground state (started-activity count) so background
        // workers (e.g. the friends-profile refresh) can poll fast while the
        // user is on-screen and back off when not. Dependency-free.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var startedActivities = 0
            override fun onActivityStarted(activity: android.app.Activity) {
                startedActivities++
                com.vrca.vrchat.VrchatPipelineState.appForeground = startedActivities > 0
            }
            override fun onActivityStopped(activity: android.app.Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                com.vrca.vrchat.VrchatPipelineState.appForeground = startedActivities > 0
            }
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
    }

    /**
     * Instantiate the process-scoped runtime [VrcaViewModel] without any UI.
     *
     * After an OS-initiated process kill, a service (KeepAliveService) is brought
     * back by START_STICKY / the watchdog / boot — but the chatbox senders live in
     * this ViewModel, which is otherwise only created when the Activity opens. Calling
     * this from the service recreates the VM headlessly; its init loads content and
     * (if feature-session restore is armed) re-enables the toggles and starts the OSC
     * senders, so the chatbox resumes on its own with no tab-in required.
     *
     * Idempotent: ViewModelProvider returns the existing instance if one already
     * exists (e.g. the Activity later opens). Must run on the main thread.
     */
    fun ensureRuntimeViewModel() {
        val create = Runnable {
            try {
                ViewModelProvider(this, VrcaViewModel.Factory)[VrcaViewModel::class.java]
            } catch (_: Throwable) {
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) create.run()
        else Handler(Looper.getMainLooper()).post(create)
    }

    private fun cleanStaleUpdateApks() {
        try {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
            dir.listFiles()?.forEach { f ->
                if (f.name.endsWith(".apk")) f.delete()
            }
        } catch (_: Throwable) {}
    }

    private fun clearStaleTutorialImages() {
        Thread {
            try {
                if (com.vrca.ui.onboarding.OnboardingPrefs.isComplete(applicationContext)) {
                    com.vrca.ui.onboarding.TutorialImageStore.clear(applicationContext)
                }
            } catch (_: Throwable) {}
        }.start()
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
