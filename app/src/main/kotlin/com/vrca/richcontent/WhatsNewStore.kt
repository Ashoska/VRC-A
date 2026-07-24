package com.vrca.richcontent

import android.content.Context

/**
 * Offline per-version cache of patch notes for Settings → "What's New" (Phase 2).
 *
 * When the client sees the `releases/latest` doc whose `versionCode` equals the
 * INSTALLED version, [cache] stores that doc's `bodyDoc` + `notes` + `versionName`
 * locally (SharedPreferences, keyed by versionCode). This costs ZERO extra reads —
 * the update listener already holds that doc — and lets Settings show the patch
 * notes for the running version fully offline. [forVersion] resolves them back into
 * a [RichDoc] via [resolveRichDoc] (rich body preferred, legacy notes fallback).
 *
 * Multiple versions can coexist (a small archive), but the UI only ever asks for
 * the installed `BuildConfig.VERSION_CODE`. Returns null for versions never seen
 * (installs predating this feature) → the UI shows a friendly empty state.
 */
object WhatsNewStore {
    private const val FILE = "vrca_whatsnew"

    data class WhatsNew(val versionName: String, val doc: RichDoc)

    fun cache(ctx: Context, versionCode: Long, versionName: String, bodyDoc: String, notes: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("name_$versionCode", versionName)
            .putString("body_$versionCode", bodyDoc)
            .putString("notes_$versionCode", notes)
            .apply()
    }

    fun forVersion(ctx: Context, versionCode: Long): WhatsNew? {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!p.contains("name_$versionCode") &&
            !p.contains("body_$versionCode") &&
            !p.contains("notes_$versionCode")
        ) return null
        val doc = resolveRichDoc(
            p.getString("body_$versionCode", ""),
            p.getString("notes_$versionCode", "")
        ) ?: return null
        return WhatsNew(p.getString("name_$versionCode", "").orEmpty(), doc)
    }
}
