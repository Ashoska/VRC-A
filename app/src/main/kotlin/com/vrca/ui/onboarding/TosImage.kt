package com.vrca.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vrca.ui.theme.GremlinColors

/**
 * A Terms of Service picture by its file [name] in [TosImageStore] (on-demand, like the tutorial's
 * TutorialImage): the frame keeps its size while the picture is fetched (Boot Cat thinking), it's
 * decoded off the main thread (Coil) and fades in. If boot's prefetch didn't cover it, it fetches it.
 */
@Composable
fun TosImage(name: String, contentDescription: String?, modifier: Modifier) {
    val ctx = LocalContext.current
    var file by remember(name) { mutableStateOf(TosImageStore.cachedFileFor(ctx, name)) }
    LaunchedEffect(name) {
        if (file == null) {
            TosImageStore.ensureDownloaded(ctx)
            file = TosImageStore.cachedFileFor(ctx, name)
        }
    }
    var loaded by remember(name) { mutableStateOf(false) }
    val shown = androidx.compose.animation.core.animateFloatAsState(if (loaded) 1f else 0f, label = "tosImage")
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(GremlinColors.Raised).border(1.dp, GremlinColors.Edge, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (!loaded) com.vrca.app.BootCat(com.vrca.app.Mark.Working, 0, Modifier.size(34.dp))
        file?.let { f ->
            coil.compose.AsyncImage(
                model = f, contentDescription = contentDescription, contentScale = ContentScale.Crop,
                onSuccess = { loaded = true },
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = shown.value }
            )
        }
    }
}
