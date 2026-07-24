package com.vrca.admin

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Admin-flavor on-device video transcode (Phase 5): re-encodes the picked video to
 * HEVC (H.265) with a ~4 Mbps bitrate cap + AAC audio, so the uploaded clip is small
 * (bitrate is the real size lever) while staying sharp. Returns the transcoded file
 * in cacheDir. Built with Media3 Transformer (admin APK only). @OptIn keeps the
 * UnstableApi marker from leaking to the shared caller. Signature matches the
 * publicApp stub so the shared RichDocEditor compiles for both variants.
 */
@OptIn(UnstableApi::class)
internal suspend fun transcodeVideoForUpload(ctx: Context, uri: Uri): File? =
    suspendCancellableCoroutine { cont ->
        val out = File(ctx.cacheDir, "rich_video_${System.currentTimeMillis()}.mp4")
        val transformer = Transformer.Builder(ctx)
            .setVideoMimeType(MimeTypes.VIDEO_H265)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(
                DefaultEncoderFactory.Builder(ctx)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder().setBitrate(4_000_000).build()
                    )
                    .build()
            )
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (cont.isActive) cont.resume(out)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    runCatching { out.delete() }
                    if (cont.isActive) cont.resumeWithException(exportException)
                }
            })
            .build()
        transformer.start(MediaItem.fromUri(uri), out.absolutePath)
        cont.invokeOnCancellation { runCatching { transformer.cancel() } }
    }
