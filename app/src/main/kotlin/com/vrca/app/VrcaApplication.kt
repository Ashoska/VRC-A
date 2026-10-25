package com.vrca.app

import kotlinx.coroutines.flow.first
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
import kotlin.system.exitProcess

/**
 * Application is also a [ViewModelStoreOwner] so the core runtime ViewModel
 * ([com.vrca.ui.viewmodel.VrcaViewModel]) can be scoped to the PROCESS, not the
 * Activity. This keeps the chatbox senders, moderation/kill listener, NowPlaying
 * consumer and Firestore sync loops alive while the app is backgrounded and the
 * Activity is destroyed — they are torn down only by [AppShutdown] on a real swipe.
 */
open class VrcaApplication : Application(), ViewModelStoreOwner {

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

        // True when this is the first open since the device restarted (BootReceiver
        // leaves a note; the app is no longer opened on boot). A reboot is a genuine cold
        // start the user should see the boot screen for (same treatment as a swipe-reopen).
        @Volatile
        var openedFromBoot: Boolean = false
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

        // Records main-thread freezes in the first 2 minutes (Settings → Debug).
        MainThreadWatch.start(applicationContext)

        // A crash report waiting = a SAFE START: only what the crash screen needs starts now; the
        // rest waits for "Start VRC-A" (CrashReport), so a crash in it can't crash this open too.
        CrashReport.checkAtLaunch(applicationContext)

        // Parse the logo's big vector off the main thread (~0.6 s on the Quest when the boot
        // screen parsed it in its first frame; MainThreadWatch).
        com.vrca.ui.common.AppLogo.preload(applicationContext)

        // Your repo expects Context
        userPreferencesRepository = UserPreferencesRepository(applicationContext)

        CrashReport.whenStarted { startOptional() }

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
     * Everything at launch the crash screen doesn't need. Runs from onCreate normally, or when a
     * safe start ends ("Start VRC-A", [CrashReport.release]).
     */
    private fun startOptional() {
        // Crashes whose upload never finished (CrashUpload) are retried on every normal start.
        runCatching { CrashUpload.sendPending(applicationContext) }

        // Open the encrypted VRChat store in the background now, so it's ready by the time
        // anything asks (slow right after the headset turns on; VrchatAuthManager.getPrefs).
        com.vrca.vrchat.VrchatAuthManager.warmUp(applicationContext)

        // Load Chromium's WebView provider off the main thread when Discord will need a WebView
        // (~1 s on the Quest, previously all inside the boot screen's pre-first-frame warm-up).
        // getDefaultUserAgent may be called from any thread and loads the provider.
        Thread({
            runCatching {
                val wanted = kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(2_000L) {
                        userPreferencesRepository.discordRpcEnabled.first() && userPreferencesRepository.discordSessionSeeded.first()
                    }
                } == true
                if (wanted) android.webkit.WebSettings.getDefaultUserAgent(applicationContext)
            }
        }, "vrca-webview-warm").apply { isDaemon = true }.start()

        // Seed the last-known NowPlaying track (as paused) BEFORE any ViewModel
        // collects NowPlayingState, so a headless revival / cold start shows the
        // previous (possibly paused) track immediately instead of blanking.
        com.vrca.nowplaying.NowPlayingState.attach(applicationContext)

        // "In VRChat" uptime timer (Home uptime label) — restore across a kill
        // within the grace window, like the Discord RPC counter.
        com.vrca.vrchat.VrchatUptime.attach(applicationContext)

        // Best-effort weather for the {weather} chatbox token (IP-geo + open-meteo).
        WeatherProvider.start()

        // Load the exact chatbox line-width calibration (if measured), so
        // TitleCleaner wraps by real per-glyph widths instead of estimates.
        com.vrca.nowplaying.ChatboxCalibration.attach(applicationContext)

        // Headset: listen for VRChat's OSC output (avatar params) so the chatbox
        // can use {mute}/{afk}/{movement}/{scale}/{param:Name}. OSC-out is loopback,
        // so this only receives on the same device as VRChat (the Quest).
        if (com.vrca.BuildConfig.IS_HEADSET_BUILD) {
            com.vrca.osc.VrcaOscReceiver.start()
            // OSC-in on Quest: VRChat won't push params over UDP, but it SERVES their
            // live values over OSCQuery HTTP. Discover VRChat's service + poll params
            // so {mute}/{afk}/{movement}/{scale}/{param:Name} resolve.
            com.vrca.osc.VrcaOscQuery.start(applicationContext)
            // Announce VRC-A as an OSCQuery app so VRChat PUSHES its output to us
            // (head/wrist tracking, every avatar param). Once per process, never
            // stopped: re-announcing in the same VRChat session gets nothing.
            val appCtx = applicationContext
            Thread({ runCatching { com.vrca.osc.OscAnnouncer.start(appCtx) } }, "vrca-announce-start")
                .apply { isDaemon = true; start() }
            // Start the instance-roster / log reader in the BACKGROUND at launch,
            // not just when the Home roster panel is visible. It owns the "VRChat
            // closed -> force presence offline" detection (applyLogPresence), which
            // must run whenever VRC-A is alive so the Discord RPC doesn't stay
            // frozen in-world after the headset is shut down / asleep. Idempotent.
            com.vrca.vrchat.InstanceRosterManager.start(applicationContext)
        }

        // Cap Firestore offline cache (default is 100 MB — far more than needed).
        // (After a safe start something may already have used the database, and then settings
        // can't change; the default cache is fine for that one run.)
        runCatching {
            FirebaseFirestore.getInstance().firestoreSettings =
                FirebaseFirestoreSettings.Builder()
                    .setCacheSizeBytes(25L * 1024 * 1024)
                    .build()
        }

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
            // Saved synchronously (CrashReport.record): the old apply() lost most reports.
            runCatching { CrashReport.record(applicationContext, t, e) }

            // Let Android still treat it as a crash
            prev?.uncaughtException(t, e) ?: run {
                // Last resort
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }
}
