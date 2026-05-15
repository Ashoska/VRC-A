package com.vrca.ui.conversation

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vrca.ui.viewmodel.VrcaViewModel

/**
 * NOTE:
 * This file is kept intentionally minimal so it always compiles.
 * Your main UI is handled by VrcaScreen.kt.
 */
@Composable
fun MessageField(
    chatboxViewModel: VrcaViewModel,
    modifier: Modifier = Modifier
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = chatboxViewModel.messageText.value,
                onValueChange = { chatboxViewModel.onMessageTextChange(it) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Write a message") }
            )

            Spacer(Modifier.width(10.dp))

            IconButton(onClick = { chatboxViewModel.stashMessage() }) {
                Icon(Icons.Filled.BookmarkAdd, contentDescription = "Quick message")
            }

            Button(onClick = { chatboxViewModel.sendMessage() }) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}
