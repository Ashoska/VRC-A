package com.scrapw.chatbox

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

val LocalAppContext = staticCompositionLocalOf<Context> {
    error("LocalAppContext not provided")
}

@androidx.compose.runtime.Composable
fun LocalAppContextProvider(content: @androidx.compose.runtime.Composable () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    androidx.compose.runtime.CompositionLocalProvider(LocalAppContext provides ctx) {
        content()
    }
}
