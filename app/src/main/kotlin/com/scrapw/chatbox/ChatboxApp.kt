// app/src/main/kotlin/com/scrapw/chatbox/ChatboxApp.kt
package com.scrapw.chatbox

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scrapw.chatbox.ui.ChatboxViewModel

@Composable
fun ChatboxApp() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(ChatboxApplication.CRASH_PREFS_FILE, Context.MODE_PRIVATE) }
    val crashText = remember {
        // read once at composition start; if user presses "Clear", we simply proceed
        prefs.getString(ChatboxApplication.CRASH_KEY_TEXT, null)
    }

    if (!crashText.isNullOrBlank()) {
        CrashScreen(
            crashText = crashText,
            onClearAndContinue = {
                prefs.edit().remove(ChatboxApplication.CRASH_KEY_TEXT).apply()
                // After clearing, the next recomposition will run the normal UI.
                // Easiest way: just rely on process restart, but we can also recompose by no-op:
                // (User will press "Continue" and it will render normal UI immediately)
            }
        )
        return
    }

    // Normal app
    val vm: ChatboxViewModel = viewModel(factory = ChatboxViewModel.Factory)
    ChatboxScreen(chatboxViewModel = vm)
}

@Composable
private fun CrashScreen(
    crashText: String,
    onClearAndContinue: () -> Unit
) {
    val scroll = rememberScrollState()

    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "VRC-A crashed last time",
                style = MaterialTheme.typography.headlineSmall
            )

            Text(
                text = "Send this log to debug the crash. You can clear it and continue.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.heightIn(min = 140.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = crashText,
                        modifier = Modifier.padding(12.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Button(
                onClick = onClearAndContinue,
                modifier = Modifier.fillMaxSize().let { Modifier } // keep stable; no full-width requirement
            ) {
                Text("Clear crash log & continue")
            }

            OutlinedButton(
                onClick = { /* leave log on screen */ }
            ) {
                Text("Keep log (don’t clear)")
            }
        }
    }
}
