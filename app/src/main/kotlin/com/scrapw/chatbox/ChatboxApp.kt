package com.scrapw.chatbox

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scrapw.chatbox.ui.ChatboxViewModel

@Composable
fun ChatboxApp() {
    val vm: ChatboxViewModel = viewModel(factory = ChatboxViewModel.Factory)
    ChatboxScreen(chatboxViewModel = vm)
}
