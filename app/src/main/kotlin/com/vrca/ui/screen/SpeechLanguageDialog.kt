package com.vrca.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vrca.speech.SpeechCatalog
import com.vrca.speech.SpeechPacks
import com.vrca.ui.common.DialogHeader
import com.vrca.ui.common.VrcaCardDialog

/**
 * Voice-language picker (same look as the timezone picker). Each language card lists its
 * tiers best-first — bigger + more accurate vs smaller + lighter — with download size
 * (or "Installed"), RAM while listening and the measured quality label, so users can
 * trade accuracy for device space. Picking a tier that isn't installed downloads it.
 */
@Composable
internal fun SpeechLanguageDialog(
    currentLang: String,
    currentPackId: String?,
    installedPacks: Set<String>,
    onSelect: (code: String, packId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val langs = SpeechCatalog.languages.filter {
        q.isEmpty() || it.englishName.contains(q, true) || it.nativeName.contains(q, true) || it.code.equals(q, true)
    }
    val deviceLang = java.util.Locale.getDefault().language

    VrcaCardDialog(onDismiss = onDismiss) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DialogHeader(title = "Voice language", icon = Icons.Filled.Mic, onClose = onDismiss)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text("Search a language") },
                modifier = Modifier.fillMaxWidth()
            )
            Column(
                Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                langs.forEach { l ->
                    LanguageCard(
                        lang = l,
                        isDevice = l.code == deviceLang,
                        selectedPackId = if (l.code == currentLang) l.tier(currentPackId).packId else null,
                        installedPacks = installedPacks,
                        onPick = { packId -> onSelect(l.code, packId) },
                    )
                }
                if (langs.isEmpty()) {
                    Text("No languages match \"$q\".", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                }
            }
            Text(
                "Runs fully on your headset after the one-time download. Smaller tiers use less storage and memory but make more mistakes. More languages are coming.\n" +
                    "Models: " + (SpeechCatalog.packs.values.map { it.credit } + SpeechCatalog.VAD.credit).distinct().joinToString(", "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LanguageCard(
    lang: SpeechCatalog.Lang,
    isDevice: Boolean,
    selectedPackId: String?,
    installedPacks: Set<String>,
    onPick: (String) -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(lang.nativeName, style = MaterialTheme.typography.bodyLarge)
                val sub = listOfNotNull(
                    lang.englishName.takeIf { it != lang.nativeName },
                    "your device language".takeIf { isDevice },
                ).joinToString(" · ")
                if (sub.isNotEmpty()) Text("  $sub", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            lang.tiers.forEach { t ->
                val pack = SpeechCatalog.packs[t.packId]
                TierRow(
                    tier = t,
                    detail = listOfNotNull(
                        if (t.packId in installedPacks) "Installed" else pack?.let { SpeechPacks.mb(it.sizeBytes) },
                        pack?.let { "~${it.ramMb} MB RAM" },
                    ).joinToString(" · "),
                    selected = t.packId == selectedPackId,
                    onClick = { onPick(t.packId) },
                )
            }
        }
    }
}

@Composable
private fun TierRow(tier: SpeechCatalog.Tier, detail: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surface,
        // A translucent highlight has no matching content colour, so set it explicitly
        // (otherwise the selected row's text inherits the card's muted colour).
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tier.label, style = MaterialTheme.typography.labelLarge)
            Text("  $detail", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            QualityChip(tier.quality)
        }
    }
}

@Composable
private fun QualityChip(q: SpeechCatalog.Quality) {
    val c = when (q) {
        SpeechCatalog.Quality.GREAT -> Color(0xFF4CAF50)
        SpeechCatalog.Quality.GOOD -> Color(0xFF42A5F5)
        SpeechCatalog.Quality.OK -> Color(0xFFFFB300)
        SpeechCatalog.Quality.EXPERIMENTAL -> Color(0xFF9E9E9E)
    }
    Surface(shape = MaterialTheme.shapes.small, color = c.copy(alpha = 0.2f)) {
        Text(q.label, color = c, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}
