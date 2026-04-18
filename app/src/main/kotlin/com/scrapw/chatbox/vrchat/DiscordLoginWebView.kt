package com.scrapw.chatbox.vrchat

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
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
import kotlinx.coroutines.delay

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DiscordLoginWebView(
    onTokenObtained: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var loading by remember { mutableStateOf(true) }
    var extracting by remember { mutableStateOf(false) }
    var pageUrl by remember { mutableStateOf("") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val extractJs = """
        (function() {
            try {
                var iframe = document.createElement('iframe');
                iframe.style.display = 'none';
                document.body.appendChild(iframe);
                var token = iframe.contentWindow.localStorage.getItem('token');
                document.body.removeChild(iframe);
                if (token) {
                    return token.replace(/^"|"$/g, '');
                }
                return '';
            } catch(e) {
                return '';
            }
        })();
    """.trimIndent()

    LaunchedEffect(extracting) {
        if (!extracting) return@LaunchedEffect
        val wv = webViewRef ?: return@LaunchedEffect
        repeat(30) {
            delay(2000)
            wv.evaluateJavascript(extractJs) { result ->
                val token = result?.trim()?.replace("\"", "") ?: ""
                if (token.isNotBlank() && token != "null") {
                    onTokenObtained(token)
                }
            }
        }
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
                        settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                loading = true
                                pageUrl = url ?: ""
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                val u = url ?: ""
                                if (u.contains("discord.com/channels") || u.contains("discord.com/app")) {
                                    extracting = true
                                }
                            }
                        }
                        webChromeClient = WebChromeClient()

                        loadUrl("https://discord.com/login")
                        webViewRef = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
        if (extracting) {
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
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        "Logged in! Extracting token...",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
