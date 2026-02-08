// app/src/main/kotlin/com/scrapw/chatbox/ui/AppIcons.kt
package com.scrapw.chatbox.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Icon grouping = defining nav items in grouped sections
 * so your Drawer UI can render "Main / Tools / Setup" consistently.
 *
 * UI-only. No app logic changes.
 */
data class NavItem(
    val title: String,
    val icon: ImageVector,
    val key: String
)

data class NavSection(
    val title: String,
    val items: List<NavItem>
)

object AppNav {
    const val KEY_HOME = "home"
    const val KEY_AUTOMATIONS = "automations"
    const val KEY_MUSIC = "music"
    const val KEY_DEBUG = "debug"
    const val KEY_SETTINGS = "settings"

    val sections: List<NavSection> = listOf(
        NavSection(
            title = "Main",
            items = listOf(
                NavItem("Home", Icons.Filled.Home, KEY_HOME),
                NavItem("Automations", Icons.Filled.Sync, KEY_AUTOMATIONS),
                NavItem("Music", Icons.Filled.MusicNote, KEY_MUSIC)
            )
        ),
        NavSection(
            title = "Tools",
            items = listOf(
                NavItem("Debug", Icons.Filled.BugReport, KEY_DEBUG)
            )
        ),
        NavSection(
            title = "Setup",
            items = listOf(
                NavItem("Settings & Permissions", Icons.Filled.Settings, KEY_SETTINGS)
            )
        )
    )
}
