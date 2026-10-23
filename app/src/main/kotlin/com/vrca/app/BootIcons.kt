package com.vrca.app

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import com.vrca.BuildConfig

/**
 * A boot row icon in two layers: [lines] in the row's normal colour, and one small [detail] (eyes,
 * heart, paw, globe, arrow, dots) that takes the row's state colour. Discord has no detail (its
 * official mark, which can't be restyled).
 */
@Immutable
class BootIcon(val lines: ImageVector, val detail: ImageVector? = null)

/**
 * Draws a [BootIcon]. The colours are read while drawing, so a state change (or its colour fade)
 * only repaints the icon instead of rebuilding the row.
 */
@Composable
fun BootIconView(icon: BootIcon, lines: () -> Color, detail: () -> Color, modifier: Modifier) {
    val l = rememberVectorPainter(icon.lines)
    val d = icon.detail?.let { rememberVectorPainter(it) }
    Spacer(modifier.drawBehind {
        with(l) { draw(size, colorFilter = ColorFilter.tint(lines())) }
        if (d != null) with(d) { draw(size, colorFilter = ColorFilter.tint(detail())) }
    })
}

/**
 * The boot screen's row icons, VRC-A's own (user picks, 2026-10-22): rounded outline doodles on a
 * 24-unit grid, one line weight, the logo's cat ears as the recurring touch. Drawn from the Symbol
 * Lab-style SVG paths of the approved sheet. Discord's comes from R.drawable.ic_discord.
 */
object BootIcons {
    /** The device itself: the eared Quest on the headset build, an eared phone otherwise. */
    val DeviceSession: BootIcon get() = if (BuildConfig.IS_HEADSET_BUILD) Headset else Phone

    val Internet = BootIcon(
        glyph("Internet") {
            line("M3 9.4a13 13 0 0 1 18 0")
            line("M6.1 12.5a8.6 8.6 0 0 1 11.8 0")
            line("M9.1 15.5a4.2 4.2 0 0 1 5.8 0")
        },
        glyph("InternetDetail") {
            solid("M10 19.7l.15-2.5 1.25 1h1.2l1.25-1 .15 2.5a2 1.7 0 0 1-4 0z")
        }
    )
    val NoInternet = BootIcon(
        glyph("NoInternet") {
            line("M3 9.4a13 13 0 0 1 18 0", clipped = true)
            line("M6.1 12.5a8.6 8.6 0 0 1 11.8 0", clipped = true)
            line("M9.1 15.5a4.2 4.2 0 0 1 5.8 0", clipped = true)
            line("M4 3.5l16 17")
        },
        glyph("NoInternetDetail") {
            solid("M10 19.7l.15-2.5 1.25 1h1.2l1.25-1 .15 2.5a2 1.7 0 0 1-4 0z", clipped = true)
        }
    )
    val Vrchat = BootIcon(
        glyph("Vrchat") {
            line("M5 3.8h14a2.5 2.5 0 0 1 2.5 2.5v8.6a2.5 2.5 0 0 1-2.5 2.5h-7l-4.6 3.3v-3.3H5a2.5 2.5 0 0 1-2.5-2.5V6.3A2.5 2.5 0 0 1 5 3.8z")
        },
        glyph("VrchatDetail") {
            line("M7.7 10.6a4.3 4.3 0 1 0 8.6 0a4.3 4.3 0 1 0 -8.6 0z", width = 1.6f)
            line("M7.7 10.6h8.6", width = 1.6f)
            line("M10.1 10.6a1.9 4.3 0 1 0 3.8 0a1.9 4.3 0 1 0 -3.8 0z", width = 1.6f)
        }
    )
    val VrcaServers = BootIcon(
        glyph("VrcaServers") {
            line("M3.5 9.6c0-1.2.4-2 1-2.6L6.8 4.1l2.6 3h5.2l2.6-3L19.5 7c.6.6 1 1.4 1 2.6v7.2l1.2 3.7-4.4-2.5H5.5a2 2 0 0 1-2-2z")
            solid("M7.7 11.3a1.1 1.1 0 1 0 2.2 0a1.1 1.1 0 1 0 -2.2 0z")
            solid("M14.1 11.3a1.1 1.1 0 1 0 2.2 0a1.1 1.1 0 1 0 -2.2 0z")
        },
        glyph("VrcaServersDetail") {
            line("M9.5 13.6q1.25 1.6 2.5 0 1.25 1.6 2.5 0")
        }
    )
    val Headset = BootIcon(
        glyph("Headset") {
            line("M6.4 8.6 7.7 4.6l2.6 4")
            line("M13.7 8.6l2.6-4 1.3 4")
            line("M6.6 8.6h10.8a3.6 3.6 0 0 1 3.6 3.6v2.4a3.8 3.8 0 0 1-3.8 3.8h-1.6c-1.3 0-2.1-1.5-3.6-1.5s-2.3 1.5-3.6 1.5H6.8A3.8 3.8 0 0 1 3 14.6v-2.4a3.6 3.6 0 0 1 3.6-3.6z")
        },
        glyph("HeadsetDetail") {
            solid("M6.9 13.1a1.3 1.3 0 1 0 2.6 0a1.3 1.3 0 1 0 -2.6 0z")
            solid("M14.5 13.1a1.3 1.3 0 1 0 2.6 0a1.3 1.3 0 1 0 -2.6 0z")
        }
    )
    val Phone = BootIcon(
        glyph("Phone") {
            line("M8 5.6 8.9 2.8l2 2.1")
            line("M13.1 4.9l2-2.1.9 2.8")
            line("M8.8 4.9h6.4a2.8 2.8 0 0 1 2.8 2.8v11a2.8 2.8 0 0 1 -2.8 2.8h-6.4a2.8 2.8 0 0 1 -2.8 -2.8v-11a2.8 2.8 0 0 1 2.8 -2.8z")
            line("M10.5 18.3h3")
        },
        glyph("PhoneDetail") {
            solid("M8.8 11a1.2 1.2 0 1 0 2.4 0a1.2 1.2 0 1 0 -2.4 0z")
            solid("M12.8 11a1.2 1.2 0 1 0 2.4 0a1.2 1.2 0 1 0 -2.4 0z")
        }
    )
    val Terms = BootIcon(
        glyph("Terms") {
            line("M6.5 3h8l4.5 4.5v12a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 19.5v-15A1.5 1.5 0 0 1 6.5 3z")
            line("M14.5 3v4.5H19")
            line("M8.2 9.2h3.6")
        },
        glyph("TermsDetail") {
            solid("M12 19.2c-1.6 0-2.4-.8-2.4-1.7 0-1.2 1.2-2.2 2.4-2.2s2.4 1 2.4 2.2c0 .9-.8 1.7-2.4 1.7z")
            solid("M7.9 14.6a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z")
            solid("M9.9 13.1a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z")
            solid("M12.1 13.1a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z")
            solid("M14.1 14.6a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z")
        }
    )
    val Account = BootIcon(
        glyph("Account") {
            line("M12 21c-4.6-1.6-7-5-7-9.4V6.6L7.2 3.6 9.6 6h4.8l2.4-2.4L19 6.6v5c0 4.4-2.4 7.8-7 9.4z")
        },
        glyph("AccountDetail") {
            solid("M12 16.3l-3-2.9a1.85 1.85 0 0 1 3-2.1 1.85 1.85 0 0 1 3 2.1z")
        }
    )
    val VrchatSignIn = BootIcon(
        glyph("VrchatSignIn") {
            line("M8.7 7.2 8.5 3.3l2.5 1.9")
            line("M15.3 7.2l.2-3.9-2.5 1.9")
            line("M8.2 8.7a3.8 3.8 0 1 0 7.6 0a3.8 3.8 0 1 0 -7.6 0z")
            line("M4.8 20.6c.4-3.7 3.4-6.2 7.2-6.2s6.8 2.5 7.2 6.2")
        },
        glyph("VrchatSignInDetail") {
            solid("M9.5 9a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z")
            solid("M12.5 9a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z")
        }
    )
    val VrchatHere = BootIcon(
        glyph("VrchatHere") {
            line("M6.6 4.6h10.8a3.6 3.6 0 0 1 3.6 3.6v4.4a3.6 3.6 0 0 1-3.6 3.6H9l-3.6 3.4v-3.5A3.6 3.6 0 0 1 3 12.6V8.2a3.6 3.6 0 0 1 3.6-3.6z")
        },
        glyph("VrchatHereDetail") {
            solid("M7.1 10.4a1.2 1.2 0 1 0 2.4 0a1.2 1.2 0 1 0 -2.4 0z")
            solid("M10.8 10.4a1.2 1.2 0 1 0 2.4 0a1.2 1.2 0 1 0 -2.4 0z")
            solid("M14.5 10.4a1.2 1.2 0 1 0 2.4 0a1.2 1.2 0 1 0 -2.4 0z")
        }
    )
    val Updates = BootIcon(
        glyph("Updates") {
            line("M5.6 7.6 6.8 3.8l2.8 3.8")
            line("M14.4 7.6l2.8-3.8 1.2 3.8")
            line("M7 7.6h10a3 3 0 0 1 3 3v7a3 3 0 0 1 -3 3h-10a3 3 0 0 1 -3 -3v-7a3 3 0 0 1 3 -3z")
        },
        glyph("UpdatesDetail") {
            line("M12 10.6v6.3")
            line("M9.3 14.2 12 16.9l2.7-2.7")
        }
    )

    /** Start (crash screen's Start VRC-A), option A: a rounded play triangle. TRIAL: one of A/B stays. */
    val StartPlain = BootIcon(
        glyph("StartPlain") { line("M8.4 5.2c-.8-.5-1.6 0-1.6.9v11.8c0 .9.8 1.4 1.6.9l9.3-5.9c.7-.5.7-1.4 0-1.8z") }
    )
    /** Start, option B: the eared tile of the download icon with a play triangle inside. TRIAL. */
    val StartTile = BootIcon(
        glyph("StartTile") {
            line("M5.6 7.6 6.8 3.8l2.8 3.8")
            line("M14.4 7.6l2.8-3.8 1.2 3.8")
            line("M7 7.6h10a3 3 0 0 1 3 3v7a3 3 0 0 1 -3 3h-10a3 3 0 0 1 -3 -3v-7a3 3 0 0 1 3 -3z")
        },
        glyph("StartTileDetail") { line("M10.2 10.9c-.4-.3-.9 0-.9.5v5.9c0 .5.5.8.9.5l4.7-3c.4-.3.4-.8 0-1z") }
    )

    /** First open: the tutorial's pictures (an open book, paw on the page). */
    val Tutorial = BootIcon(
        glyph("Tutorial") {
            line("M12 7c-2.2-1.6-5.3-2-8.5-1.5v13c3.2-.5 6.3-.1 8.5 1.5 2.2-1.6 5.3-2 8.5-1.5v-13c-3.2-.5-6.3-.1-8.5 1.5z")
            line("M12 7v13")
        },
        glyph("TutorialDetail") {
            solid("M16 15.2c-1 0-1.5-.5-1.5-1.1 0-.8.7-1.4 1.5-1.4s1.5.6 1.5 1.4c0 .6-.5 1.1-1.5 1.1z")
            solid("M13.9 11.6a.65 .65 0 1 0 1.3 0a.65 .65 0 1 0 -1.3 0z")
            solid("M15.35 10.8a.65 .65 0 1 0 1.3 0a.65 .65 0 1 0 -1.3 0z")
            solid("M16.8 11.6a.65 .65 0 1 0 1.3 0a.65 .65 0 1 0 -1.3 0z")
        }
    )

    private class Glyph(val b: ImageVector.Builder) {
        fun line(d: String, width: Float = 1.9f, clipped: Boolean = false) = add(d, clipped) { nodes ->
            b.addPath(nodes, stroke = SolidColor(Color.Black), strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
        fun solid(d: String, clipped: Boolean = false) = add(d, clipped) { nodes -> b.addPath(nodes, fill = SolidColor(Color.Black)) }
        private inline fun add(d: String, clipped: Boolean, draw: (List<androidx.compose.ui.graphics.vector.PathNode>) -> Unit) {
            // No internet: the bars stop either side of the slash instead of running under it.
            if (clipped) b.addGroup(clipPathData = addPathNodes(SLASH_GAP))
            draw(addPathNodes(d))
            if (clipped) b.clearGroup()
        }
    }

    private fun glyph(name: String, build: Glyph.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).also { Glyph(it).build() }.build()

    /** Everything except a 4.6-unit band along the No internet slash. */
    private const val SLASH_GAP = "M-11.38 -9.49 L32.03 36.64 L2.90 64.05 L-40.51 17.93ZM-8.03 -12.64 L35.38 33.49 L64.51 6.07 L21.10 -40.05Z"
}
