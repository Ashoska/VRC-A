package com.vrca.spotify

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Catches the `vrca://spotify-callback?code=...` OAuth redirect (declared with an
 * intent-filter in the manifest), exchanges the code for tokens, then bounces
 * back into the app. Transparent + no-history so the user just returns to VRC-A.
 */
class SpotifyRedirectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri == null) { finish(); return }
        val appCtx = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val ok = SpotifyAuthManager.handleRedirect(appCtx, uri)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    appCtx,
                    if (ok) "Spotify connected" else "Spotify sign-in failed",
                    Toast.LENGTH_SHORT
                ).show()
                // Return to the app's main task.
                runCatching {
                    startActivity(
                        Intent(appCtx, Class.forName("com.vrca.app.MainActivity")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                    )
                }
                finish()
            }
        }
    }
}
