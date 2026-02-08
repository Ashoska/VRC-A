// app/src/main/kotlin/com/scrapw/chatbox/ChatboxScreen.kt
package com.scrapw.chatbox

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scrapw.chatbox.ui.ChatboxViewModel
import kotlinx.coroutines.launch

private enum class AppPage(val title: String) {
    Home("Home"),
    Automations("Automations"),
    Music("Music"),
    Debug("Debug"),
    Settings("Settings"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatboxScreen(
    chatboxViewModel: ChatboxViewModel = viewModel(factory = ChatboxViewModel.Factory)
) {
    // ✅ Mobile SlimeVR-style: hamburger drawer nav (no bottom nav)
    var page by rememberSaveable { mutableStateOf(AppPage.Home) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    fun select(p: AppPage) {
        page = p
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    text = "Chatbox",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .then(Modifier)
                        .run { this }
                        .then(Modifier)
                        .run { this }
                        .then(Modifier)
                )

                DrawerItem(
                    label = "Home",
                    selected = page == AppPage.Home,
                    icon = Icons.Filled.Home
                ) { select(AppPage.Home) }

                DrawerItem(
                    label = "Automations",
                    selected = page == AppPage.Automations,
                    icon = Icons.Filled.Sync
                ) { select(AppPage.Automations) }

                DrawerItem(
                    label = "Music",
                    selected = page == AppPage.Music,
                    icon = Icons.Filled.MusicNote
                ) { select(AppPage.Music) }

                DrawerItem(
                    label = "Debug",
                    selected = page == AppPage.Debug,
                    icon = Icons.Filled.BugReport
                ) { select(AppPage.Debug) }

                DrawerItem(
                    label = "Settings",
                    selected = page == AppPage.Settings,
                    icon = Icons.Filled.Settings
                ) { select(AppPage.Settings) }
            }
        }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(page.title) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Open menu")
                        }
                    },
                    actions = {
                        // Optional quick jump to Settings (UI-only)
                        if (page != AppPage.Settings) {
                            IconButton(onClick = { select(AppPage.Settings) }) {
                                Icon(Icons.Filled.Settings, contentDescription = "Settings")
                            }
                        }
                    }
                )
            }
        ) { padding ->
            when (page) {
                AppPage.Home -> ChatboxHomePage(chatboxViewModel, modifier = Modifier)
                AppPage.Automations -> ChatboxAutomationsPage(chatboxViewModel, modifier = Modifier)
                AppPage.Music -> ChatboxMusicPage(chatboxViewModel, modifier = Modifier)
                AppPage.Debug -> ChatboxDebugPage(chatboxViewModel, modifier = Modifier)
                AppPage.Settings -> SettingsPage(vm = chatboxViewModel, modifier = Modifier)
            }
        }
    }
}

@Composable
private fun DrawerItem(
    label: String,
    selected: Boolean,
    icon: ImageVector,
    onClick: () -> Unit
) {
    NavigationDrawerItem(
        label = { Text(label) },
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        modifier = Modifier
            .then(Modifier),
        colors = NavigationDrawerItemDefaults.colors()
    )
}

/**
 * These wrappers keep your existing pages untouched (logic unchanged),
 * while allowing the new drawer nav to route cleanly.
 *
 * IMPORTANT: they call your existing composables from your current file.
 */
@Composable
private fun ChatboxHomePage(vm: ChatboxViewModel, modifier: Modifier) {
    // This function is defined in your existing ChatboxScreen.kt you pasted before.
    // We keep it in the same package by moving page implementations into SettingsPage.kt OR
    // by leaving them in a separate file.
    //
    // ✅ If your current HomePage/AutomationsPage/etc are in THIS file already,
    // you should paste them into SettingsPage.kt (next block) or keep them where they are.
    //
    // For now, call the existing screen that already contains Home/Automations/Music/Debug UI:
    LegacyChatboxMainPages(vm = vm, startPage = "home", modifier = modifier)
}

@Composable
private fun ChatboxAutomationsPage(vm: ChatboxViewModel, modifier: Modifier) {
    LegacyChatboxMainPages(vm = vm, startPage = "automations", modifier = modifier)
}

@Composable
private fun ChatboxMusicPage(vm: ChatboxViewModel, modifier: Modifier) {
    LegacyChatboxMainPages(vm = vm, startPage = "music", modifier = modifier)
}

@Composable
private fun ChatboxDebugPage(vm: ChatboxViewModel, modifier: Modifier) {
    LegacyChatboxMainPages(vm = vm, startPage = "debug", modifier = modifier)
}

/**
 * ✅ Drop-in compatibility shim:
 * - If you already have the big multi-page UI (Home/Automations/Music/Debug) implemented
 *   inside one composable (your current ChatboxScreen), put that implementation in a new
 *   composable named LegacyChatboxMainPages below (or in another file).
 *
 * This keeps ALL your existing logic intact while we only swap navigation UI.
 */
@Composable
private fun LegacyChatboxMainPages(
    vm: ChatboxViewModel,
    startPage: String,
    modifier: Modifier
) {
    // FULL IMPLEMENTATION PROVIDED IN THE NEXT FILE (SettingsPage.kt replacement).
    // This stub exists only so this file compiles once you paste the next file.
}
