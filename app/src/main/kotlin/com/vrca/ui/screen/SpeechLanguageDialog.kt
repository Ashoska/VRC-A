package com.vrca.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.vrca.speech.MicSensitivity
import com.vrca.speech.SpeechCatalog
import com.vrca.speech.SpeechPacks
import com.vrca.ui.common.DialogHeader
import com.vrca.ui.common.VrcaCardDialog
import com.vrca.ui.common.VrcaConfirmDialog

/**
 * Voice-language picker. Wide panel (the Quest's 1024 dp): two panes — a compact,
 * searchable language list on the left, the chosen language's sizes on the right as
 * roomy cards (Large / Medium / Small, quality badge with the measured %, download +
 * memory in plain units, and one clear action: Use / Download / In use). Narrow window:
 * the list, then the details with a back arrow. One card per language listing every tier
 * inline got cramped once languages had three tiers, so the details moved to their own pane.
 */
@Composable
internal fun SpeechLanguageDialog(
    currentLang: String,
    currentPackId: String?,
    installedPacks: Set<String>,
    sensitivity: MicSensitivity,
    onSensitivity: (MicSensitivity) -> Unit,
    listenParam: String?,
    listenWhenOn: Boolean,
    triggerParams: () -> List<Pair<String, Boolean>>,
    onListen: (param: String?, whenOn: Boolean) -> Unit,
    commandsOn: Boolean,
    commandWords: Map<com.vrca.speech.VoiceCommand, List<String>>,
    onCommands: () -> Unit,
    onSelect: (code: String, packId: String) -> Unit,
    onRemove: (packId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var removing by remember { mutableStateOf<SpeechCatalog.Pack?>(null) }
    var editingListen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(currentLang) }
    var narrowShowsDetails by remember { mutableStateOf(false) }
    val q = query.trim()
    val langs = SpeechCatalog.languages.filter {
        q.isEmpty() || it.englishName.contains(q, true) || it.nativeName.contains(q, true) || it.code.equals(q, true)
    }
    val deviceLang = java.util.Locale.getDefault().language
    val focusedLang = SpeechCatalog.lang(focused) ?: SpeechCatalog.languages.first()

    VrcaCardDialog(onDismiss = onDismiss) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DialogHeader(title = "Voice language", icon = Icons.Filled.Mic, onClose = onDismiss)
            SensitivityRow(sensitivity, onSensitivity)
            ListenRow(listenParam, listenWhenOn, onChange = { editingListen = true })
            CommandsRow(commandsOn, commandWords, onChange = onCommands)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val paneHeight = if (maxHeight.value.isFinite()) minOf(440.dp, maxHeight) else 440.dp
                val list: @Composable (Modifier) -> Unit = { m ->
                    LanguageList(
                        modifier = m,
                        langs = langs,
                        query = query,
                        onQuery = { query = it },
                        focused = focusedLang.code,
                        currentLang = currentLang,
                        currentReady = currentPackId in installedPacks,
                        deviceLang = deviceLang,
                        installedPacks = installedPacks,
                        onFocus = { focused = it; narrowShowsDetails = true },
                    )
                }
                val details: @Composable (Modifier) -> Unit = { m ->
                    LanguageDetails(
                        modifier = m,
                        lang = focusedLang,
                        isDevice = focusedLang.code == deviceLang,
                        inUsePackId = if (focusedLang.code == currentLang) focusedLang.tier(currentPackId).packId else null,
                        installedPacks = installedPacks,
                        onPick = { packId -> onSelect(focusedLang.code, packId) },
                        onRemove = { pack -> removing = pack },
                    )
                }
                if (maxWidth >= 640.dp) {
                    Row(Modifier.fillMaxWidth().height(paneHeight), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        list(Modifier.width(280.dp).fillMaxHeight())
                        details(Modifier.weight(1f).fillMaxHeight())
                    }
                } else if (narrowShowsDetails) {
                    Column(Modifier.fillMaxWidth().height(paneHeight)) {
                        TextButton(onClick = { narrowShowsDetails = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("  All languages")
                        }
                        details(Modifier.fillMaxWidth().weight(1f))
                    }
                } else {
                    list(Modifier.fillMaxWidth().height(paneHeight))
                }
            }
        }
    }
    if (editingListen) {
        ListenTriggerDialog(
            param = listenParam, whenOn = listenWhenOn, params = triggerParams,
            onDone = { p, on -> onListen(p, on); editingListen = false },
            onDismiss = { editingListen = false },
        )
    }
    removing?.let { pack ->
        // One pack can serve several languages (SenseVoice: Chinese, Cantonese, Korean):
        // say so, since removing it takes it away from all of them.
        val others = SpeechCatalog.languages.filter { l -> l.code != focusedLang.code && l.tiers.any { it.packId == pack.id } }
            .joinToString(", ") { it.englishName }
        VrcaConfirmDialog(
            title = "Remove this voice pack?",
            body = "Frees ${SpeechPacks.mb(pack.sizeBytes)}." +
                (if (others.isNotEmpty()) " It's also used for $others, so those lose it too." else "") +
                " You can download it again any time.",
            confirmLabel = "Remove",
            onConfirm = { onRemove(pack.id); removing = null },
            onDismiss = { removing = null },
            destructive = true,
        )
    }
}

/** Mic sensitivity for all languages: Soft voice / Normal (default) / Noisy room. */
/** "While muted in VRChat", "While STT is on", or "Always". */
internal fun listenSummary(param: String?, whenOn: Boolean): String = when (param) {
    null -> "Always"
    "MuteSelf" -> if (whenOn) "While muted in VRChat" else "While unmuted in VRChat"
    else -> "While $param is ${if (whenOn) "on" else "off"}"
}

@Composable
private fun CommandsRow(on: Boolean, words: Map<com.vrca.speech.VoiceCommand, List<String>>, onChange: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Voice commands", style = MaterialTheme.typography.labelLarge)
        Text(if (on) com.vrca.speech.VoiceCommand.entries.joinToString(" · ") { words[it]?.firstOrNull() ?: "" } else "Off",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        ActionPill("Change", filled = false, onClick = onChange)
    }
}

@Composable
private fun ListenRow(param: String?, whenOn: Boolean, onChange: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Listen", style = MaterialTheme.typography.labelLarge)
        Text(listenSummary(param, whenOn), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false))
        ActionPill("Change", filled = false, onClick = onChange)
    }
}

/**
 * Pick the VRChat avatar parameter (read live over OSCQuery) that switches listening on
 * and off: MuteSelf, Earmuffs, AFK or any on/off toggle on the avatar. While it says
 * "don't listen" the model stays loaded, so listening resumes instantly.
 */
@Composable
private fun ListenTriggerDialog(
    param: String?,
    whenOn: Boolean,
    params: () -> List<Pair<String, Boolean>>,
    onDone: (String?, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf(param) }
    var on by remember { mutableStateOf(whenOn) }
    var live by remember { mutableStateOf(params()) }
    LaunchedEffect(Unit) { while (true) { delay(500); live = params() } }
    // Keep the saved choice listed even while VRChat doesn't report it.
    val rows = live + listOfNotNull(param?.takeIf { p -> live.none { it.first == p } }?.let { it to null })
    VrcaCardDialog(onDismiss = onDismiss) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DialogHeader(title = "Listen", icon = Icons.Filled.Mic, onClose = onDismiss)
            Text("Voice to text listens only while a VRChat setting is on (or off). The voice model stays loaded, so it hears you straight away.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TriggerRow("Always", null, selected = picked == null) { picked = null }
            if (live.isEmpty()) Text("Open VRChat with OSC on to see your avatar's settings here.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                items(rows, key = { it.first }) { (name, value) ->
                    TriggerRow(if (name == "MuteSelf") "Mic muted (MuteSelf)" else name, value, selected = picked == name) { picked = name }
                }
            }
            if (picked != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Listen while it's", style = MaterialTheme.typography.labelLarge)
                    ActionPill("On", filled = on, onClick = { on = true })
                    ActionPill("Off", filled = !on, onClick = { on = false })
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                ActionPill("Done", filled = true, onClick = { onDone(picked, on) })
            }
        }
    }
}

@Composable
private fun TriggerRow(label: String, value: Boolean?, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            if (label != "Always") Tag(when (value) { true -> "On now"; false -> "Off now"; null -> "Not found now" },
                if (value == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp).size(18.dp))
        }
    }
}

@Composable
private fun SensitivityRow(current: MicSensitivity, onPick: (MicSensitivity) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Mic sensitivity", style = MaterialTheme.typography.labelLarge)
        MicSensitivity.entries.forEach { level ->
            val selected = level == current
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { onPick(level) }
            ) {
                Text(level.label + if (level == MicSensitivity.NORMAL) " (recommended)" else "",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
        Text(
            when (current) {
                MicSensitivity.SOFT -> "Picks up quiet speech; may react to more background sound."
                MicSensitivity.NORMAL -> "Best for most people."
                MicSensitivity.NOISY -> "Ignores more background sound; speak up a little."
            },
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LanguageList(
    modifier: Modifier,
    langs: List<SpeechCatalog.Lang>,
    query: String,
    onQuery: (String) -> Unit,
    focused: String,
    currentLang: String,
    currentReady: Boolean,
    deviceLang: String,
    installedPacks: Set<String>,
    onFocus: (String) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            placeholder = { Text("Search") },
            modifier = Modifier.fillMaxWidth()
        )
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(langs, key = { it.code }) { l ->
                val selected = l.code == focused
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().clickable { onFocus(l.code) }
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(l.nativeName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = listOfNotNull(
                                l.englishName.takeIf { it != l.nativeName },
                                "your device language".takeIf { l.code == deviceLang },
                            ).joinToString(" · ")
                            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        when {
                            l.code == currentLang && currentReady -> Tag("In use", MaterialTheme.colorScheme.primary)
                            l.tiers.any { it.packId in installedPacks } -> Tag("Installed", MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (langs.isEmpty()) item {
                Text("No languages match \"${query.trim()}\".", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

@Composable
private fun LanguageDetails(
    modifier: Modifier,
    lang: SpeechCatalog.Lang,
    isDevice: Boolean,
    inUsePackId: String?,
    installedPacks: Set<String>,
    onPick: (String) -> Unit,
    onRemove: (SpeechCatalog.Pack) -> Unit,
) {
    var showCredits by remember { mutableStateOf(false) }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(lang.nativeName, style = MaterialTheme.typography.titleLarge)
            val sub = listOfNotNull(lang.englishName.takeIf { it != lang.nativeName },
                "your device language".takeIf { isDevice }).joinToString(" · ")
            if (sub.isNotEmpty()) Text("  $sub", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            if (lang.tiers.size > 1) "Larger sizes make fewer mistakes but take more storage and memory."
            else "One size for this language. More may come as better small models appear.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Keyed by language + pack: positional reuse showed the previous language's first
        // card (e.g. English's "Large · In use") under the newly picked language.
        lang.tiers.mapNotNull { t -> SpeechCatalog.packs[t.packId]?.let { t to it } }.forEach { (t, pack) ->
            key(lang.code, t.packId) {
                TierCard(
                    tier = t,
                    pack = pack,
                    installed = t.packId in installedPacks,
                    // Only while it's still downloaded: a removed in-use pack must offer
                    // Download again (it stayed "In use" with no way to reinstall).
                    inUse = t.packId == inUsePackId && t.packId in installedPacks,
                    onClick = { onPick(t.packId) },
                    onRemove = { onRemove(pack) },
                )
            }
        }
        Text("Quality is measured on test recordings (casual talk does a bit worse). Compare sizes within a language: each language has its own test sentences.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { showCredits = !showCredits }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text(if (showCredits) "Hide model credits" else "Model credits", style = MaterialTheme.typography.labelMedium)
        }
        if (showCredits) {
            Text(
                "Runs fully on your headset after a one-time download. Models: " +
                    (SpeechCatalog.packs.values.map { it.credit } + SpeechCatalog.VAD.credit).distinct().joinToString(", "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun TierCard(
    tier: SpeechCatalog.Tier,
    pack: SpeechCatalog.Pack,
    installed: Boolean,
    inUse: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (inUse) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = if (inUse) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth().clickable(enabled = !inUse) { onClick() }
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tier.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    QualityBadge(tier)
                    if (pack.slow) Tag("Slower", MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "${SpeechPacks.mb(pack.sizeBytes)} download · ${memory(pack.ramMb)} memory",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Downloaded sizes can be removed right here (Settings also lists them).
            if (installed) {
                TextButton(onClick = onRemove) {
                    Text("Remove", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                }
            }
            when {
                inUse -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Text(" In use", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                installed -> ActionPill("Use", filled = true, onClick = onClick)
                else -> ActionPill("Download", filled = false, onClick = onClick)
            }
        }
    }
}

/** "Great · 96%": the plain-word quality first, the measured figure after it. */
@Composable
private fun QualityBadge(t: SpeechCatalog.Tier) {
    val c = when (t.quality) {
        SpeechCatalog.Quality.EXCELLENT -> Color(0xFF26C6DA)
        SpeechCatalog.Quality.GREAT -> Color(0xFF4CAF50)
        SpeechCatalog.Quality.GOOD -> Color(0xFF42A5F5)
        SpeechCatalog.Quality.OK -> Color(0xFFFFB300)
        SpeechCatalog.Quality.EXPERIMENTAL -> Color(0xFF9E9E9E)
    }
    Surface(shape = MaterialTheme.shapes.small, color = c.copy(alpha = 0.2f)) {
        Text("${t.quality.label} · ${t.accuracy}%", color = c, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
internal fun Tag(text: String, color: Color) {
    Surface(shape = MaterialTheme.shapes.small, color = color.copy(alpha = 0.14f)) {
        Text(text, color = color, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
internal fun ActionPill(text: String, filled: Boolean, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (filled) MaterialTheme.colorScheme.primary else Color.Transparent,
        contentColor = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
        border = if (filled) null else BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
    }
}

/** Memory in plain units: "about 670 MB", "about 1.0 GB". */
private fun memory(mb: Int): String = if (mb >= 1000) "about %.1f GB".format(mb / 1000.0) else "about $mb MB"
