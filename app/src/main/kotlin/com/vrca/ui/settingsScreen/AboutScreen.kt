package com.vrca.ui.settingsScreen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AboutScreen(
    modifier: Modifier = Modifier
) {
    // Stub screen: you asked to remove About from the app,
    // this exists only so any old references won't crash compilation.
    Column(modifier.padding(16.dp)) {
        Text("About", style = MaterialTheme.typography.titleLarge)
        Text(
            "This screen has been removed in VRC-A.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
