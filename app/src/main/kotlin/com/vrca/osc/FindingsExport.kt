package com.vrca.osc

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Headset debug: run every research readout (OSCQuery endpoint dump, floor probe,
 * announce status, hand-drag status) and save them to one text file in Downloads
 * (`vrca-findings-<time>.txt`), because the dumps are too long to screenshot on the
 * Quest panel. Returns where it was saved. Blocking: run off the main thread.
 */
object FindingsExport {

    fun saveAll(context: Context): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val text = buildString {
            append("VRC-A findings ").append(stamp).append('\n')
            append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append("  Android ").append(Build.VERSION.RELEASE)
                .append("  build ").append(Build.DISPLAY).append("\n\n")
            append("######## OSCQuery probe\n").append(VrcaOscState.oscQueryDiag).append("\n\n")
            append("######## Announce\n").append(OscAnnouncer.diag()).append("\n\n")
            append("######## Hand drag\n").append(HandDrag.diag()).append("\n\n")
            append("######## OSCQuery endpoints\n")
            append(runCatching { VrcaOscQuery.dumpTree() }.getOrElse { "error: ${it.message}" })
            append("\n\n######## Floor / boundary values\n")
            append(runCatching { FloorProbe.dump(context) }.getOrElse { "error: ${it.message}" })
            append('\n')
        }
        val name = "vrca-findings-$stamp.txt"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return "save failed (no Downloads entry)"
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                ?: return "save failed (could not open file)"
            "Saved: Downloads/$name"
        } else {
            val dir = context.getExternalFilesDir(null) ?: return "save failed (no storage)"
            File(dir, name).writeText(text)
            "Saved: ${dir.absolutePath}/$name"
        }
    }
}
