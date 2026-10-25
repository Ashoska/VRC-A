package com.vrca.ui.onboarding

import android.content.Context
import java.io.File

/**
 * The OSC tutorial's step pictures. NOT bundled in the APK (they only appear during first-run
 * onboarding / replay, so shipping them was pure bloat): they're the `tutorial` group of the image-store
 * repo (`images/tutorial/1..4`, keys `tutorial/1..4`, see [ImageManifest]), downloaded into
 * `filesDir/tutorial_images` and deleted the moment the tutorial is done. Replacing a picture in the repo
 * reaches users the next time the tutorial loads, and the old one is deleted (OnDemandImages).
 *
 * Lifecycle (owned by the callers):
 *  - New-user boot (onboarding not complete): [ensureDownloaded] prefetches them during the boot screen
 *    (AppBoot's Tutorial row).
 *  - Replay (Settings): [ensureDownloaded] on tutorial entry (OnboardingFlow).
 *  - Tutorial COMPLETE or replay EXIT: [clear] deletes them (VrcaApp `onFinish`).
 *  - A new user who closes/kills the app MID-tutorial keeps them (onboarding isn't complete, so no
 *    cleanup runs) → resume works.
 *  - A replay killed mid-way leaves strays: on the next fresh process onboarding is already complete, so
 *    [clear] wipes them (VrcaApplication.onCreate).
 */
object TutorialImageStore {
    private val images = OnDemandImages("TutorialImageStore", "tutorial_images", "tutorial")

    /** The downloaded, up-to-date picture for step [n] (1-based), else null. */
    fun cachedFileFor(ctx: Context, n: Int): File? = images.cachedFileFor(ctx, n.toString())

    /** Just step [n]'s picture, ahead of the others (the step showing it now). */
    suspend fun fetch(ctx: Context, n: Int): File? = images.fetch(ctx, n.toString())

    /** Brings the pictures in line with the repo (see [OnDemandImages.ensureDownloaded]). */
    suspend fun ensureDownloaded(ctx: Context): Boolean = images.ensureDownloaded(ctx)

    /** Deletes all downloaded tutorial pictures (frees disk once the tutorial ends). */
    fun clear(ctx: Context) = images.clear(ctx)
}
