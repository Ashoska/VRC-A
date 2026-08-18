package com.vrca.discord

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DiscordLoginWebView(
    onLoginComplete: () -> Unit,
    onDismiss: () -> Unit
) {
    var loading by remember { mutableStateOf(true) }
    var loggedIn by remember { mutableStateOf(false) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Completion is detected by a CONTINUOUS poll, independent of page-load
    // callbacks — on a slow Quest WebView the post-login SPA redirect frequently
    // does NOT fire a fresh onPageFinished, so the old "probe only inside
    // onPageFinished for a /channels URL, give up after 4 retries" never
    // completed there (login worked, cookies/localStorage persisted, but
    // onLoginComplete never fired → the dialog never closed and
    // discord_session_seeded was never set → "log in again" on reopen). The poll
    // runs every ~1.2s regardless of navigation and also treats a present
    // localStorage.token (the exact signal DiscordRpcService reads) as
    // conclusive login, so redirect timing can't defeat it.
    DisposableEffect(Unit) {
        val jsProbe = "(function(){" +
            "try{" +
            "var hasLoginForm = !!document.querySelector('input[type=\"password\"], input[type=\"email\"], input[name=\"email\"]');" +
            "var appMounted = (document.getElementById('app-mount')?.childElementCount || 0) > 0;" +
            "var hasToken = false; try { hasToken = !!(window.localStorage && window.localStorage.getItem('token')); } catch(e) {}" +
            "return (!hasLoginForm && (appMounted || hasToken));" +
            "}catch(e){return false;}" +
            "})()"
        var polls = 0
        val maxPolls = 90 // ~110s of open login before giving up
        lateinit var poll: Runnable
        poll = Runnable {
            if (loggedIn) return@Runnable
            val wv = webViewRef
            val u = wv?.url ?: ""
            // Only probe once we're plausibly on the app (not the login form),
            // but the token check inside the probe is authoritative regardless.
            val onApp = u.contains("discord.com/channels") || u.contains("discord.com/app") ||
                u.contains("discord.com/@me")
            if (wv != null && (onApp || polls > 3)) {
                wv.evaluateJavascript(jsProbe) { result ->
                    if (result == "true" && !loggedIn) {
                        Log.d("DiscordLogin", "Login confirmed (poll $polls, url=$u)")
                        loggedIn = true
                        // Commit cookies before signalling completion so the RPC
                        // service's own WebView loads authenticated (see flush note).
                        CookieManager.getInstance().flush()
                        handler.postDelayed({ onLoginComplete() }, 350)
                    }
                }
            }
            polls++
            if (!loggedIn && polls < maxPolls) handler.postDelayed(poll, 1200)
        }
        handler.postDelayed(poll, 1500)
        onDispose { handler.removeCallbacksAndMessages(null) }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Sign in to Discord") },
            navigationIcon = {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.ArrowBack, "Back")
                }
            }
        )
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Box(Modifier.weight(1f)) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.setSupportMultipleWindows(true)
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.allowContentAccess = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"

                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                loading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                // Completion detection lives in the continuous poll
                                // (DisposableEffect above) — not here — so a slow
                                // Quest SPA redirect that skips onPageFinished can't
                                // prevent login from being confirmed.
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                return false
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onCreateWindow(
                                view: WebView?, isDialog: Boolean,
                                isUserGesture: Boolean, resultMsg: Message?
                            ): Boolean {
                                val popup = WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.databaseEnabled = true
                                    settings.userAgentString = view?.settings?.userAgentString
                                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean = false
                                    }
                                    webChromeClient = object : WebChromeClient() {
                                        override fun onCloseWindow(window: WebView?) {
                                            (view as? ViewGroup)?.removeView(window)
                                            window?.destroy()
                                        }
                                    }
                                }
                                view?.addView(popup, ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                ))
                                val transport = resultMsg?.obj as? WebView.WebViewTransport
                                transport?.webView = popup
                                resultMsg?.sendToTarget()
                                return true
                            }
                        }

                        loadUrl("https://discord.com/login")
                        webViewRef = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
        if (loggedIn) {
            Card(
                Modifier.fillMaxWidth().padding(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Signed in! Discord cookies saved. You can close this.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
