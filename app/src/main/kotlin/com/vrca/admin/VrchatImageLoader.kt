package com.vrca.admin

import android.content.Context
import coil.ImageLoader
import com.vrca.vrchat.VrchatAuthManager
import okhttp3.OkHttpClient

/**
 * A Coil [ImageLoader] that attaches this device's VRChat session cookie (and the
 * required User-Agent) to requests for `*.vrchat.cloud` hosts. VRChat+ profile
 * pictures live behind auth-gated `api.vrchat.cloud` file/image URLs that 401 for
 * an unauthenticated client — so the admin directory could never render them.
 * With this loader, the admin's own logged-in VRChat session authorises the image
 * fetch, so any user's VRChat+ picture loads directly from VRChat with no need to
 * store the picture (or proxy it) anywhere.
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
            .build()
    }
}
