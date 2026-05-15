package com.vrca.ui.settingsScreen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vrca.R
import com.vrca.data.SettingsStates
import com.vrca.ui.ChatboxViewModel

@Composable
fun SettingsScreen(
    chatboxViewModel: ChatboxViewModel,
    modifier: Modifier = Modifier
) {
    // These were already used in your MainScreen logic
    val displayIpState = SettingsStates.displayIpState()
    val displayMessageOptionsState = SettingsStates.displayMessageOptionsState()

    // If you have these SettingsStates elsewhere, keep them. If not, this still compiles
    // because we don't reference missing ones.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = stringResource(R.string.settings),
            style = MaterialTheme.typography.headlineSmall
        )

        // ---------- OSC Host / Layout ----------
        Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.layout),
                    style = MaterialTheme.typography.titleMedium
                )
                Divider()

                SettingRow(
                    title = stringResource(R.string.display_ip_edit_bar),
                    description = null,
                    checked = displayIpState.value,
                    onCheckedChange = { displayIpState.value = it }
                )

                SettingRow(
                    title = stringResource(R.string.display_message_options),
                    description = null,
                    checked = displayMessageOptionsState.value,
                    onCheckedChange = { displayMessageOptionsState.value = it }
                )
            }
        }

        // ---------- Message behaviour ----------
        val ui by chatboxViewModel.messengerUiState.collectAsState()

        Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.message),
                    style = MaterialTheme.typography.titleMedium
                )
                Divider()

                SettingRow(
                    title = stringResource(R.string.real_time_sending),
                    description = stringResource(R.string.real_time_sending_desc),
                    checked = ui.isRealtimeMsg,
                    onCheckedChange = chatboxViewModel::onRealtimeMsgChanged
                )

                SettingRow(
                    title = stringResource(R.string.trigger_notification_sound),
                    description = stringResource(R.string.trigger_notification_sound_desc),
                    checked = ui.isTriggerSFX,
                    onCheckedChange = chatboxViewModel::onTriggerSfxChanged
                )

                SettingRow(
                    title = stringResource(R.string.show_message_typing_indicator),
                    description = stringResource(R.string.show_message_typing_indicator_desc),
                    checked = ui.isTypingIndicator,
                    onCheckedChange = chatboxViewModel::onTypingIndicatorChanged
                )

                SettingRow(
                    title = stringResource(R.string.send_message_directly),
                    description = stringResource(R.string.send_message_directly_desc),
                    checked = ui.isSendImmediately,
                    onCheckedChange = chatboxViewModel::onSendImmediatelyChanged
                )
            }
        }

        // IMPORTANT:
        // About + FAQ removed on purpose (you asked to remove them).
        Spacer(Modifier.height(6.dp))
        Text(
            text = "VRC-A (made by Ashoska Mitsu Sisko)",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun SettingRow(
    title: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                if (!description.isNullOrBlank()) {
                    Text(text = description, style = MaterialTheme.typography.bodySmall)
                }
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
