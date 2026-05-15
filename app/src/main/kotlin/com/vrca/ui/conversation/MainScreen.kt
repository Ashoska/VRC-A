package com.vrca.ui.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vrca.data.SettingsStates
import com.vrca.ui.viewmodel.VrcaViewModel
import com.vrca.ui.viewmodel.MessengerUiState

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    chatboxViewModel: VrcaViewModel = viewModel(factory = VrcaViewModel.Factory),
    uiState: MessengerUiState = MessengerUiState()
) {
    val displayIpState = SettingsStates.displayIpState()
    val displayMessageOptionsState = SettingsStates.displayMessageOptionsState()

    Column(modifier) {
        if (displayIpState.value) {
            Surface(tonalElevation = 2.dp) {
                // ✅ Correct call: IpField expects (viewModel, modifier)
                IpField(
                    chatboxViewModel = chatboxViewModel,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Conversation(
            uiState = chatboxViewModel.conversationUiState,
            onCopyPressed = chatboxViewModel::onMessageTextChange,
            modifier = Modifier.weight(1f)
        )

        Surface(
            tonalElevation = 2.dp,
            shape = MaterialTheme.shapes.large.copy(
                bottomEnd = CornerSize(0.dp),
                bottomStart = CornerSize(0.dp)
            )
        ) {
            Column(Modifier.padding(top = 10.dp)) {
                if (displayMessageOptionsState.value) {
                    MessageOptions(chatboxViewModel, uiState, true)
                }
                MessageField(chatboxViewModel)
            }
        }
    }
}
