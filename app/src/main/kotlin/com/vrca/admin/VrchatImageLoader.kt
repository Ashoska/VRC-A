package com.vrca.admin

import android.content.Context
import coil.ImageLoader
import com.vrca.vrchat.VrchatAuthManager
import okhttp3.OkHttpClient

/**
 * A Coil [ImageLoader] that attaches this device's VRChat session cookie (and the
 * required User-Agent) to requests for `*.vrchat.cloud` hosts. VRChat+ images
 * live behind auth-gated `api.vrchat.cloud` file/image URLs that 401 for an
 * unauthenticated client; this loader authorises them with the device's own
 * logged-in session.
 *
 * Used by the public VRChat tab to render the user's OWN avatar + profile banner.
 * (It used to also back the admin directory's per-user profile pictures via
 * `fetchProfilePicUrl`, but that never worked reliably and was removed —
 * AdminAvatar now shows name initials only.)
 *
 * The cookie is only added for VRChat hosts; Discord CDN and every other URL pass
 * through untouched. Process-lifetime singleton (cheap, shares OkHttp's pool).
 */
object VrchatImageLoader {
    @Volatile private var loader: ImageLoader? = null

    fun get(context: Context): ImageLoader {
        val app = context.applicationContext
        return loader ?: synchronized(this) {
            loader ?: build(app).also { loader = it }
        }
    }

    private fun build(app: Context): ImageLoader {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                if (req.url.host.endsWith("vrchat.cloud")) {
                    val headers = VrchatAuthManager.vrchatImageHeaders(app)
                    if (headers != null) {
                        val b = req.newBuilder()
                        for ((k, v) in headers) b.header(k, v)
                        return@addInterceptor chain.proceed(b.build())
                    }
                }
                chain.proceed(req)
            }
            .build()
        return ImageLoader.Builder(app)
            .okHttpClient(client)
            // Coil's DEFAULT disk cache is 2% of FREE disk space (hundreds of MB
            // on a modern phone) at cacheDir/image_cache — a silent contributor
            // to the app's "Cache" size. Cap it explicitly: the public build only
            // loads the user's own avatar + banner, so 10 MB is generous. The LRU
            // journal trims any oversized pre-existing cache down to the cap.
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(app.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(10L * 1024 * 1024)
                    .build()
            }
            .build()
    }
}
