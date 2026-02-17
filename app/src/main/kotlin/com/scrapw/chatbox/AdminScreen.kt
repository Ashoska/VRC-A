package com.scrapw.chatbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AdminScreen(vm: AdminViewModel) {
    val loading by vm.loading.collectAsState()
    val err by vm.error.collectAsState()
    val adminUser by vm.adminUser.collectAsState()
    val punishments by vm.punishments.collectAsState()

    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }

    var targetUserId by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("warn") } // "warn" or "ban"

    Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Admin Panel")

        if (loading) {
            CircularProgressIndicator()
        }

        if (err != null) {
            Text("Error: $err")
        }

        if (adminUser == null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Sign in (Firebase Auth)")

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Email") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Password") },
                        singleLine = true
                    )

                    Button(
                        onClick = { vm.signIn(email, pass) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Login")
                    }
                }
            }
            return
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Logged in: ${adminUser!!.email}")

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { vm.loadLatestPunishments() }, modifier = Modifier.weight(1f)) {
                        Text("Refresh")
                    }
                    TextButton(onClick = { vm.signOut() }, modifier = Modifier.weight(1f)) {
                        Text("Sign out")
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Create punishment")

                OutlinedTextField(
                    value = targetUserId,
                    onValueChange = { targetUserId = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Target user id") },
                    singleLine = true
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { type = "warn" }, modifier = Modifier.weight(1f)) { Text("Warn") }
                    Button(onClick = { type = "ban" }, modifier = Modifier.weight(1f)) { Text("Ban") }
                }

                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Reason") }
                )

                Button(
                    onClick = { vm.createPunishment(targetUserId, type, reason) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = targetUserId.isNotBlank()
                ) {
                    Text("Submit")
                }
            }
        }

        Divider()

        Text("Latest punishments (${punishments.size})")

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(punishments) { p ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Type: ${p.type}  •  Active: ${p.active}")
                        Text("Target: ${p.targetUserId}")
                        Text("Reason: ${p.reason}")
                        Text("Created: ${p.createdAt.toDate()}")
                    }
                }
            }
        }
    }
}
