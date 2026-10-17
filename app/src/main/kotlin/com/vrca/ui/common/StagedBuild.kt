package com.vrca.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.withFrameNanos

/*
 * Builds a big page over a few frames instead of all in its first one. A tab switch is a
 * Crossfade that starts the new page at alpha 0, so parts that arrive 1-3 frames (~50 ms) later
 * are invisible; building it all at once was a single 100-500 ms hitch on the Quest (UI Lab
 * `perf`: Settings 4,100 composable calls in one frame). Put what's on screen first in step 0 and
 * the rest in later steps. Each [Step] reads the stage in its own scope, so revealing one step
 * builds only that step; nothing already built re-runs.
 */
class StagedBuild internal constructor(private val stage: State<Int>, private val steps: Int) {
    /** True once every step has been built (e.g. before scrolling to something in a late step). */
    val done: Boolean get() = stage.value >= steps

    /** [content] appears from step [n] on (0 = first frame). */
    @Composable
    fun Step(n: Int, content: @Composable () -> Unit) {
        if (stage.value >= n) content()
    }
}

/**
 * True where staging is hidden by a fade (VrcaScreen sets it for pages entering through a tab
 * switch). Elsewhere a page builds all at once: on a plain appear (cold start, tabbing back into
 * the app) the steps showed as blocks popping in one at a time.
 */
val LocalStagedBuild = compositionLocalOf { false }

/** Steps 1..[steps] each arrive one frame after the last (only where [LocalStagedBuild] is true). */
@Composable
fun rememberStagedBuild(steps: Int): StagedBuild {
    val staged = LocalStagedBuild.current
    val stage = remember { mutableIntStateOf(if (staged) 0 else steps) }
    LaunchedEffect(Unit) {
        while (stage.intValue < steps) {
            withFrameNanos { }
            stage.intValue++
        }
    }
    return remember { StagedBuild(stage, steps) }
}
