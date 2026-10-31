package com.vrca.uilab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vrca.ui.common.GremlinButton
import com.vrca.ui.common.GremlinButtonKind
import com.vrca.ui.common.GremlinButtonSize
import com.vrca.ui.common.GremlinChip
import com.vrca.ui.theme.GremlinColors as G

/**
 * UI Lab mock-up (revamp 2, account standing, 2026-10-31): how a feature an admin disabled looks where it lives.
 * `show disabledmock`: the control greys out and can't be used; one line in its place says it's disabled and for
 * how long, with the countdown Boot Cat draining; tapping the line opens the standing popup. A whole section that's
 * off gets one line at its top instead. Mock data only.
 */
object DisabledMocks {
    @Composable
    fun Page() {
        var standing by remember { mutableStateOf(false) }
        val open = { standing = true }
        Box(Modifier.fillMaxSize().background(G.Backdrop)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 700.dp
                val cards: List<@Composable (Modifier) -> Unit> = listOf(
                    { m -> ManualSend(m, open) }, { m -> AvatarSearch(m, open) }, { m -> Roster(m, open) }, { m -> ChatboxOff(m, open) })
                if (wide) Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) { cards[0](Modifier.fillMaxWidth()); cards[3](Modifier.fillMaxWidth()) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) { cards[1](Modifier.fillMaxWidth()); cards[2](Modifier.fillMaxWidth()) }
                } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    cards.forEach { it(Modifier.fillMaxWidth()) }
                }
            }
            if (standing) StandingMocks.Standing("risk", onClosed = { standing = false })
        }
    }

    /** The line that takes a disabled control's place: draining Boot Cat, what and how long, tap for the standing. */
    @Composable
    fun DisabledLine(text: String, left: Float, onClick: () -> Unit, modifier: Modifier = Modifier) = Row(
        modifier.clip(RoundedCornerShape(10.dp)).background(G.Caution.copy(alpha = 0.10f)).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        com.vrca.app.BootCatCountdown(left, Modifier.size(18.dp), G.Caution, smooth = false)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = G.Caution, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(6.dp))
        Text("›", style = MaterialTheme.typography.bodyMedium, color = G.Caution)
    }

    /** Greyed and untouchable (the real one: `enabled = false` plus this look). */
    private val Off = Modifier.graphicsLayer { alpha = 0.38f }

    @Composable
    private fun CardTitle(t: String) = Text(t, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = G.Text)

    @Composable
    private fun FakeField(text: String, modifier: Modifier = Modifier) = Box(
        modifier.height(44.dp).clip(RoundedCornerShape(12.dp)).background(G.Raised).border(1.dp, G.Edge, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) { Text(text, style = MaterialTheme.typography.bodyMedium, color = G.TextMuted) }

    @Composable
    private fun ManualSend(modifier: Modifier, open: () -> Unit) = com.vrca.app.Layer(modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardTitle("Manual Send")
            Row(Off, verticalAlignment = Alignment.CenterVertically) {
                FakeField("Type a message", Modifier.weight(1f)); Spacer(Modifier.width(10.dp))
                GremlinButton("Send", {}, enabled = false)
            }
            DisabledLine("Custom text disabled · 2 days left", 0.67f, open)
        }
    }

    @Composable
    private fun AvatarSearch(modifier: Modifier, open: () -> Unit) = com.vrca.app.Layer(modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardTitle("Avatar search")
            Column(Off, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FakeField("Search avatars", Modifier.weight(1f)); Spacer(Modifier.width(10.dp))
                    GremlinButton("Search", {}, enabled = false)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GremlinChip("All", true, {}); GremlinChip("PC", false, {}); GremlinChip("Quest", false, {})
                }
            }
            DisabledLine("Avatar search disabled · 28 days left", 0.93f, open)
        }
    }

    @Composable
    private fun Roster(modifier: Modifier, open: () -> Unit) = com.vrca.app.Layer(modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardTitle("In your instance")
            listOf("Kiri", "Moth").forEach { n ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(G.Card).padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(G.Raised))
                    Spacer(Modifier.width(10.dp))
                    Text(n, style = MaterialTheme.typography.bodyMedium, color = G.Text, modifier = Modifier.weight(1f))
                    Box(Off) { GremlinButton("Clone", {}, kind = GremlinButtonKind.Secondary, size = GremlinButtonSize.Small, enabled = false) }
                }
            }
            // Repeated buttons: one line for the list, not one per row.
            DisabledLine("Avatar clone disabled · 28 days left", 0.93f, open)
        }
    }

    /** A whole section off: one line at its top, everything under it greyed. */
    @Composable
    private fun ChatboxOff(modifier: Modifier, open: () -> Unit) = com.vrca.app.Layer(modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardTitle("Chatbox")
            DisabledLine("Chatbox sending disabled · 6 h left", 0.25f, open, Modifier.fillMaxWidth())
            Column(Off, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Pinned", "Cycle", "Now Playing").forEach { t ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(G.Card).padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(t, style = MaterialTheme.typography.bodyMedium, color = G.Text)
                    }
                }
                GremlinButton("Start sending", {}, enabled = false, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
