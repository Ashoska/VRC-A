package com.vrca.ui.onboarding

import android.content.Context
import java.io.File

/**
 * On-demand store for the OSC-tutorial instruction images. They are NOT bundled
 * in the APK — they only appear during first-run onboarding / replay, so shipping
 * ~600 KB of PNGs for a one-time screen was pure bloat (removing them nearly
 * halved the APK once before). Instead they're downloaded from the VRC-A image
 * store GitHub repo into `filesDir/tutorial_images` and deleted the moment the
 * tutorial is done.
 *
 * Lifecycle (owned by the callers):
 *  - New-user boot (onboarding not complete): [ensureDownloaded] prefetches them
 *    during the boot screen — this REPLACES the old fixed "new user" floor with
 *    real work (VrcaApp).
 *  - Replay (Settings): [ensureDownloaded] re-downloads on tutorial entry
 *    (OnboardingFlow).
 *  - Tutorial COMPLETE or replay EXIT: [clear] deletes them (VrcaApp `onFinish`).
 *  - A new user who closes/kills the app MID-tutorial keeps the images (onboarding
 *    isn't complete, so no cleanup runs) → resume works.
 *  - A replay killed mid-way leaves strays: on the next fresh process onboarding
 *    is already complete, so [clear] wipes them (VrcaApplication.onCreate).
 */
object TutorialImageStore {
    const val COUNT = 4
    // The shared on-demand picture system (OnDemandImages), also used by the Terms of Service.
    private val images = OnDemandImages("TutorialImageStore", "tutorial_images", "", (1..COUNT).map { "osc_tutorial_$it.png" })

    /** Local file for tutorial image [n] (1-based), whether or not it exists yet. */
    fun fileFor(ctx: Context, n: Int): File = images.fileFor(ctx, "osc_tutorial_$n.png")

    /** The downloaded file for [n] if present and non-empty, else null. */
    fun cachedFileFor(ctx: Context, n: Int): File? = images.cachedFileFor(ctx, "osc_tutorial_$n.png")

    /** True when all [COUNT] images are present and non-empty. */
    fun isCached(ctx: Context): Boolean = images.isCached(ctx)

    /** Downloads any missing tutorial image (idempotent; see [OnDemandImages.ensureDownloaded]). */
    suspend fun ensureDownloaded(ctx: Context): Boolean = images.ensureDownloaded(ctx)

    /** Deletes all downloaded tutorial images (frees disk once the tutorial ends). */
    fun clear(ctx: Context) = images.clear(ctx)
}
