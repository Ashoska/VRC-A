package com.vrca.admin

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Public-flavor stub (Phase 5). The video transcoder is an ADMIN-ONLY feature backed
 * by Media3 Transformer (`adminAppImplementation`), which isn't on the public
 * classpath — so the public build returns null (it never authors rich content). The
 * shared RichDocEditor treats null as "transcode unavailable" and falls back to a
 * raw, size-guarded upload. Signature MUST match the adminApp version.
 */
@Suppress("UNUSED_PARAMETER")
internal suspend fun transcodeVideoForUpload(ctx: Context, uri: Uri): File? = null
