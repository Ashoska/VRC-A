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
    var dwellRunnable by remember { mutableStateOf<Runnable?>(null) }
    val pendingRetries = remember { mutableListOf<Runnable>() }

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
                                dwellRunnable?.let { handler.removeCallbacks(it) }
                                dwellRunnable = null
                                pendingRetries.forEach { handler.removeCallbacks(it) }
                                pendingRetries.clear()
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                val u = url ?: ""
                                dwellRunnable?.let { handler.removeCallbacks(it) }
                                dwellRunnable = null
                                pendingRetries.forEach { handler.removeCallbacks(it) }
                                pendingRetries.clear()
                                if (!loggedIn && (u.contains("discord.com/channels") || u.contains("discord.com/app"))) {
                                    val wv = view ?: return
                                    val jsProbe = "(function(){" +
                                        "var noLogin = !document.querySelector('input[type=\"password\"], input[type=\"email\"], input[name=\"email\"]');" +
                                        "var appMounted = (document.getElementById('app-mount')?.childElementCount || 0) > 0;" +
                                        "return noLogin && appMounted;" +
                                        "})()"
                                    var retryCount = 0
                                    val maxRetries = 4
                                    fun probe() {
                                        if (loggedIn) return
                                        wv.evaluateJavascript(jsProbe) { result ->
                                            if (result == "true" && !loggedIn) {
                                                Log.d("DiscordLogin", "Login confirmed: webpack loaded, no login form")
                                                loggedIn = true
                                                // Persist the login cookies BEFORE signalling completion.
                                                // onLoginComplete → the RPC service starts + loads its own
                                                // discord.com WebView; if the cookies aren't committed yet it
                                                // loads UNAUTHENTICATED, Discord redirects to /login, and the
                                                // RPC reports SESSION_EXPIRED — the "log in, then it says sign
                                                // in again" loop. flush() commits them; a short delay lets the
                                                // async flush land before the RPC reads the cookie store.
                                                CookieManager.getInstance().flush()
                                                handler.postDelayed({ onLoginComplete() }, 350)
                                            } else if (retryCount < maxRetries && !loggedIn) {
                                                retryCount++
                                                Log.d("DiscordLogin", "Probe returned $result — retry $retryCount/$maxRetries")
                                                val retry = Runnable { probe() }
                                                pendingRetries.add(retry)
                                                handler.postDelayed(retry, 1500)
                                            } else {
                                                Log.d("DiscordLogin", "Probe gave up after $retryCount retries")
                                            }
                                        }
                                    }
                                    val runnable = Runnable { probe() }
                                    dwellRunnable = runnable
                                    handler.postDelayed(runnable, 2000)
                                }
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
