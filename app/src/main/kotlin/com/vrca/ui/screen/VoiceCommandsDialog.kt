package com.vrca.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vrca.speech.VoiceCommand
import com.vrca.speech.VoiceCommands
import com.vrca.ui.common.DialogHeader
import com.vrca.ui.common.VrcaCardDialog
import com.vrca.ui.viewmodel.VrcaViewModel

/**
 * Voice commands: on/off, and each command's word for the current language. "Say it"
 * records the word 3 times and keeps what the model actually heard (a model may write
 * "pause" as "paws"; storing that is what makes it match); "Type it" for spellings.
 * Warns about short or everyday words and blocks a word another command already uses.
 */
@Composable
internal fun VoiceCommandsDialog(
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    langCode: String,
    langName: String,
    words: Map<VoiceCommand, List<String>>,
    noSpace: Boolean,
    learning: VoiceCommand?,
    heard: List<String>,
    onSayIt: (VoiceCommand) -> Unit,
    onLearnDone: (save: Boolean) -> Unit,
    onSetWords: (VoiceCommand, List<String>?) -> Unit,
    onDismiss: () -> Unit,
) {
    var typing by remember { mutableStateOf<VoiceCommand?>(null) }
    var typed by remember { mutableStateOf("") }
    val defaults = VoiceCommands.defaults(langCode)
    val close = { if (learning != null) onLearnDone(false); onDismiss() }
    fun usedElsewhere(cmd: VoiceCommand, list: List<String>) = list.any { w ->
        words.any { (c, ws) -> c != cmd && ws.any { VoiceCommands.norm(it) == VoiceCommands.norm(w) } }
    }

    VrcaCardDialog(onDismiss = close) {
        Column(
            Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DialogHeader(title = "Voice commands", icon = Icons.Filled.Mic, onClose = close)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Voice commands", style = MaterialTheme.typography.labelLarge)
                ActionPill("On", filled = enabled, onClick = { onEnabled(true) })
                ActionPill("Off", filled = !enabled, onClick = { onEnabled(false) })
            }
            Text(
                "Say a command word on its own, after a short silence, as loud as you normally talk. " +
                    "You'll hear a sound when it works. Words for $langName.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            VoiceCommand.entries.forEach { cmd ->
                val ws = words[cmd].orEmpty()
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(cmd.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(cmd.what, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(ws.joinToString(" · "), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        ws.flatMap { VoiceCommands.warnings(it, cmd, words, noSpace) }.firstOrNull()?.let { Warning(it) }
                        when {
                            learning == cmd -> LearnPanel(cmd, heard, words, noSpace,
                                blocked = { usedElsewhere(cmd, it) }, onRetry = { onSayIt(cmd) }, onDone = onLearnDone)
                            typing == cmd -> {
                                OutlinedTextField(
                                    value = typed, onValueChange = { typed = it }, singleLine = true,
                                    label = { Text("Word (commas between other spellings)") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                val list = typed.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                                list.flatMap { VoiceCommands.warnings(it, cmd, words, noSpace) }.distinct().forEach { Warning(it) }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (list.isNotEmpty() && !usedElsewhere(cmd, list)) {
                                        ActionPill("Save", filled = true, onClick = { onSetWords(cmd, list); typing = null })
                                    }
                                    TextButton(onClick = { typing = null }) { Text("Cancel") }
                                }
                            }
                            else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (learning == null) ActionPill("Say it", filled = true, onClick = { typing = null; onSayIt(cmd) })
                                ActionPill("Type it", filled = false, onClick = { typing = cmd; typed = ws.joinToString(", ") })
                                if (ws != defaults[cmd]) TextButton(onClick = { onSetWords(cmd, null) }) { Text("Use default") }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ActionPill("Done", filled = true, onClick = close)
            }
        }
    }
}

/** "Say it": progress while recording, then what will be saved (with any warnings). */
@Composable
private fun LearnPanel(
    cmd: VoiceCommand,
    heard: List<String>,
    words: Map<VoiceCommand, List<String>>,
    noSpace: Boolean,
    blocked: (List<String>) -> Boolean,
    onRetry: () -> Unit,
    onDone: (save: Boolean) -> Unit,
) {
    val times = VrcaViewModel.LEARN_TIMES
    val shown = heard.map { VoiceCommands.clean(it) }
    if (heard.size < times) {
        Text(
            "Say your ${cmd.label} word on its own, $times times, with a short pause between (${heard.size} of $times).",
            style = MaterialTheme.typography.bodyMedium
        )
        if (shown.isNotEmpty()) Text("Heard: ${shown.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { onDone(false) }) { Text("Cancel") }
        return
    }
    val (learned, consistent) = VoiceCommands.learn(heard)
    Text("Heard: ${shown.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall)
    Text("Saves: ${learned.joinToString(" · ")}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    val warnings = learned.flatMap { VoiceCommands.warnings(it, cmd, words, noSpace) }.distinct() +
        (if (!consistent) listOf("Heard differently each time. A longer, clearer word works better.") else emptyList())
    warnings.forEach { Warning(it) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (learned.isNotEmpty() && !blocked(learned)) ActionPill("Save", filled = true, onClick = { onDone(true) })
        TextButton(onClick = onRetry) { Text("Try again") }
        TextButton(onClick = { onDone(false) }) { Text("Cancel") }
    }
}

@Composable
private fun Warning(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
}
