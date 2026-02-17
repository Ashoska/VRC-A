// app/src/main/kotlin/com/scrapw/chatbox/tos/TosScreen.kt
package com.scrapw.chatbox.tos

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TosScreen(
    appName: String,
    tosVersion: Int,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("$appName — Terms") }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ElevatedCard {
                Column(
                    Modifier
                        .padding(14.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Terms of Service (v$tosVersion)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text =
                        """
1) You are responsible for how you use this app.
2) Do not use this app to harass, threaten, or target people.
3) Admin features are for moderation and evidence tracking only.
4) Evidence retention:
   - Normal cases: stored up to 90 days.
   - If a user is warned/banned: related evidence remains until un-warned/unbanned, then expires after 90 days.
5) The app may store moderation records (warnings/bans) and attached offending messages for evidence.
6) By accepting, you agree to these rules.
                        """.trimIndent(),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Text(
                "You must accept to continue.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onDecline,
                    modifier = Modifier.weight(1f)
                ) { Text("Decline") }

                Button(
                    onClick = onAccept,
                    modifier = Modifier.weight(1f)
                ) { Text("Accept") }
            }
        }
    }
}
