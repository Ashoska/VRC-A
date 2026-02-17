package com.scrapw.chatbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun AdminScreen(
    vm: AdminViewModel = viewModel(factory = AdminViewModel.Factory)
) {
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val users by vm.users.collectAsState()

    Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Admin", style = MaterialTheme.typography.titleLarge)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { vm.refresh() }) { Text("Refresh") }
            OutlinedButton(onClick = { vm.clearError() }) { Text("Clear error") }
        }

        if (loading) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
        }

        if (error != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Error", style = MaterialTheme.typography.titleSmall)
                    Text(error ?: "", fontFamily = FontFamily.Monospace)
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Rules (current plan)", style = MaterialTheme.typography.titleSmall)
                Text("• Only keep offending messages (linked to punishments).")
                Text("• 30→90 rule: warned/banned stays until warn cleared or unbanned.")
            }
        }

        Divider()

        Text("Users", style = MaterialTheme.typography.titleMedium)

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(users) { u ->
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(u.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(u.status, style = MaterialTheme.typography.labelLarge)
                        }
                        Text("ID: ${u.userId}", fontFamily = FontFamily.Monospace)
                        if (u.notes.isNotBlank()) Text(u.notes)
                    }
                }
            }

            item { Spacer(Modifier.padding(bottom = 40.dp)) }
        }
    }
}
