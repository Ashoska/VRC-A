package com.vrca.ui.common

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource

/*
 * The VRC-A logo (res/drawable/vrca_logo_vector), parsed once on a background thread at process
 * start. Its traced path is big: parsing it on the main thread the first time it was drawn took
 * ~0.6 s on the Quest (MainThreadWatch, user's 2026-10-18 recording: BootLogo → painterResource →
 * XmlVectorParser), right in the boot screen's first frame. Use [appLogoPainter] for every logo.
 */
object AppLogo {
    // State, so a logo drawn before the parse finishes switches to it (watched-state rule).
    private val vector = mutableStateOf<ImageVector?>(null)

    fun preload(ctx: Context) {
        if (vector.value != null) return
        val app = ctx.applicationContext
        Thread({
            runCatching { vector.value = ImageVector.vectorResource(null, app.resources, com.vrca.R.drawable.vrca_logo_vector) }
        }, "vrca-logo").apply { isDaemon = true }.start()
    }

    internal val parsed: ImageVector? get() = vector.value
}

/** The logo painter: the background-parsed vector, or the normal resource load if it isn't ready. */
@Composable
fun appLogoPainter(): Painter {
    val v = AppLogo.parsed
    return if (v != null) rememberVectorPainter(v) else painterResource(com.vrca.R.drawable.vrca_logo_vector)
}
