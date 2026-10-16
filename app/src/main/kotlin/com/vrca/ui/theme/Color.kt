package com.vrca.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Gremlin: VRC-A's own design system. Every colour the app draws comes from here (or from
 * MaterialTheme.colorScheme, which [GremlinTheme] builds from these), never a hex literal at
 * the call site.
 *
 * Depth comes from LAYERS, not gradients: each layer is a flat tone one step lighter than the
 * one behind it (Backdrop → Panel → Card → Raised), with [Edge] lines where layers meet.
 */
object GremlinColors {
    // Accent
    val Accent = Color(0xFF8F7CFF)
    val AccentSoft = Color(0xFFB7A9FF)

    // Layers, back to front
    val Backdrop = Color(0xFF06101C) // layer 0: the wall behind everything
    val Panel = Color(0xFF0A1828)    // layer 1: bars, rails, big panels
    val Card = Color(0xFF0F2236)     // layer 2: cards on a panel
    val Raised = Color(0xFF142B44)   // layer 3: chips, fields, controls on a card
    val Edge = Color(0xFF223B57)     // line where two layers meet

    // Text
    val Text = Color(0xFFEAF0FF)
    val TextMuted = Color(0xFFB3C0DE)

    // App tones (kit badges, banners)
    val Success = Color(0xFF53E39F)
    val Warning = Color(0xFFFFD166)
    val Error = Color(0xFFFF6B6B)

    // Signals: small status marks (dots, counters, "live", "granted")
    val Positive = Color(0xFF4CAF50)
    val Caution = Color(0xFFFFB300)
    val Negative = Color(0xFFEF5350)
    val Alert = Color(0xFFE05561)      // ban / moderation red
    val Inactive = Color(0xFF9E9E9E)

    /** VRChat user status dots. */
    object Status {
        val Online = Color(0xFF4CAF50)
        val JoinMe = Color(0xFF2196F3)
        val AskMe = Color(0xFFFF9800)
        val Busy = Color(0xFFE53935)
        val Offline = Color(0xFF9E9E9E)
    }

    /** VRChat trust ranks, by the DISPLAYED rank (tags are offset one step: see trust helpers). */
    object Trust {
        val Visitor = Color(0xFFCCCCCC)
        val NewUser = Color(0xFF1778FF)
        val User = Color(0xFF2BCF5C)
        val KnownUser = Color(0xFFFF7B42)
        val TrustedUser = Color(0xFFB18FE4)
        val Veteran = Color(0xFFFFD000)
    }

    /** Platform glyphs: Windows blue, Android green, Apple light grey. */
    object Platform {
        val Pc = Color(0xFF2196F3)
        val Quest = Color(0xFF3DDC84)
        val Ios = Color(0xFFE0E0E0)
    }

    /** Avatar performance ranks. */
    object Performance {
        val Excellent = Color(0xFF3CCF4E)
        val Good = Color(0xFF8CC63F)
        val Medium = Color(0xFFFFC107)
        val Poor = Color(0xFFFF7B42)
        val VeryPoor = Color(0xFFE53935)
    }

    /** Instance access types. */
    object Access {
        val Public = Color(0xFF4CAF50)
        val Friends = Color(0xFF42A5F5)
        val Group = Color(0xFFAB47BC)
        val Invite = Color(0xFFFFB300)
    }

    /** Before/after change lists (bio, status edits). */
    object Change {
        val Added = Color(0xFF4CAF50)
        val Removed = Color(0xFFEF5350)
        val Moved = Color(0xFFB388FF)
        val Neutral = Color(0xFFB0B0B0)
    }

    /** People highlighted in lists: you, your friends. */
    object Highlight {
        val Self = Color(0xFFB388FF)
        val Friend = Color(0xFFFFD54F)
    }

    /** Group roles / badges. */
    object Medal {
        val Gold = Color(0xFFFFC64B)
        val Silver = Color(0xFFC7CDD9)
    }

    /** Voice-model quality badges. */
    object Quality {
        val Excellent = Color(0xFF26C6DA)
        val Great = Color(0xFF4CAF50)
        val Good = Color(0xFF42A5F5)
        val Ok = Color(0xFFFFB300)
        val Experimental = Color(0xFF9E9E9E)
    }

    /** Rich-content callout boxes. */
    object Callout {
        val Success = Color(0xFF22C55E)
        val Warning = Color(0xFFF4511E)
        val Info = Color(0xFF3B82F6)
    }
}
