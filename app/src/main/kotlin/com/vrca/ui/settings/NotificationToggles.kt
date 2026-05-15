package com.vrca.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.vrca.ui.viewmodel.VrcaViewModel
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@Composable
fun NotificationToggleSection(vm: VrcaViewModel) {
    val scope = rememberCoroutineScope()
    val repo = vm.userPreferencesRepository

    val friendRequest      by repo.notifFriendRequest.collectAsState(initial = false)
    val newFriend          by repo.notifNewFriend.collectAsState(initial = false)
    val unfriend           by repo.notifUnfriend.collectAsState(initial = false)
    val vrchatMessage      by repo.notifVrchatMessage.collectAsState(initial = false)
    val friendOnline       by repo.notifFriendOnline.collectAsState(initial = false)
    val friendOffline      by repo.notifFriendOffline.collectAsState(initial = false)
    val friendActive       by repo.notifFriendActive.collectAsState(initial = false)
    val friendLocation     by repo.notifFriendLocation.collectAsState(initial = false)
    val friendStatus       by repo.notifFriendStatus.collectAsState(initial = false)
    val friendAvatar       by repo.notifFriendAvatar.collectAsState(initial = false)
    val friendBio          by repo.notifFriendBio.collectAsState(initial = false)
    val friendDisplayName  by repo.notifFriendDisplayName.collectAsState(initial = false)
    val voteToKick         by repo.notifVoteToKick.collectAsState(initial = false)
    val friendRank         by repo.notifFriendRank.collectAsState(initial = false)
    val invite             by repo.notifInvite.collectAsState(initial = false)
    val inviteRequest      by repo.notifInviteRequest.collectAsState(initial = false)
    val groupInvite        by repo.notifGroupInvite.collectAsState(initial = false)
    val groupAnnouncement  by repo.notifGroupAnnouncement.collectAsState(initial = false)
    val groupEvent         by repo.notifGroupEvent.collectAsState(initial = false)
    val groupQueue         by repo.notifGroupQueue.collectAsState(initial = false)
    val groupJoinRequest   by repo.notifGroupJoinRequest.collectAsState(initial = false)
    val groupRole          by repo.notifGroupRole.collectAsState(initial = false)
    val groupInstance      by repo.notifGroupInstance.collectAsState(initial = false)
    val appUpdate          by repo.notifAppUpdate.collectAsState(initial = true)
    val announcements      by repo.notifAnnouncements.collectAsState(initial = true)
    val connection         by repo.notifConnection.collectAsState(initial = false)
    val auth               by repo.notifAuth.collectAsState(initial = true)
    val vrchatAlert        by repo.notifVrchatAlert.collectAsState(initial = false)
    val giftReceived       by repo.notifGiftReceived.collectAsState(initial = false)

    var friendListExpanded by rememberSaveable { mutableStateOf(false) }
    var friendsActivityExpanded by rememberSaveable { mutableStateOf(false) }
    var invitesExpanded by rememberSaveable { mutableStateOf(false) }
    var groupsExpanded by rememberSaveable { mutableStateOf(false) }
    var appConnectionExpanded by rememberSaveable { mutableStateOf(false) }

    SectionCard(
        title = "Friend list",
        subtitle = "Changes to who's on your friends list.",
        actions = {
            IconButton(onClick = { friendListExpanded = !friendListExpanded }) {
                Icon(
                    if (friendListExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (friendListExpanded) "Collapse" else "Expand"
                )
            }
        }
    ) {
        AnimatedVisibility(
            visible = friendListExpanded,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 3 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 3 })
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ToggleRow("Friend request received", friendRequest,
                    description = "When someone sends you a friend request") {
                    scope.launch { repo.saveNotifFriendRequest(it) }
                }
                ToggleRow("New friend added", newFriend,
                    description = "When someone accepts your request or you accept theirs") {
                    scope.launch { repo.saveNotifNewFriend(it) }
                }
                ToggleRow("Friend removed", unfriend,
                    description = "When someone is no longer on your friends list") {
                    scope.launch { repo.saveNotifUnfriend(it) }
                }
                ToggleRow("VRChat in-app messages", vrchatMessage,
                    description = "Direct messages sent through VRChat") {
                    scope.launch { repo.saveNotifVrchatMessage(it) }
                }
            }
        }
    }

    SectionCard(
        title = "Friends activity",
        subtitle = "What your friends are doing in VRChat.",
        actions = {
            IconButton(onClick = { friendsActivityExpanded = !friendsActivityExpanded }) {
                Icon(
                    if (friendsActivityExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (friendsActivityExpanded) "Collapse" else "Expand"
                )
            }
        }
    ) {
        AnimatedVisibility(
            visible = friendsActivityExpanded,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 3 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 3 })
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SubSectionLabel("Presence", topPadding = 0.dp)
                ToggleRow("Friend came online", friendOnline,
                    description = "When a friend logs into VRChat") {
                    scope.launch { repo.saveNotifFriendOnline(it) }
                }
                ToggleRow("Friend went offline", friendOffline,
                    description = "When a friend leaves VRChat (10-min delay)") {
                    scope.launch { repo.saveNotifFriendOffline(it) }
                }
                ToggleRow("Friend on website (not in VR)", friendActive,
                    description = "When a friend is browsing the VRChat website") {
                    scope.launch { repo.saveNotifFriendActive(it) }
                }
                ToggleRow("Friend changed worlds", friendLocation,
                    description = "When a friend joins a different public world") {
                    scope.launch { repo.saveNotifFriendLocation(it) }
                }

                SubSectionLabel("Profile changes")
                ToggleRow("Friend changed presence", friendStatus,
                    description = "Online, Join Me, Ask Me, or Do Not Disturb") {
                    scope.launch { repo.saveNotifFriendStatus(it) }
                }
                ToggleRow("Friend changed avatar", friendAvatar,
                    description = "When a friend switches to a different avatar") {
                    scope.launch { repo.saveNotifFriendAvatar(it) }
                }
                ToggleRow("Friend updated bio", friendBio,
                    description = "When a friend edits their profile bio") {
                    scope.launch { repo.saveNotifFriendBio(it) }
                }
                ToggleRow("Friend renamed themselves", friendDisplayName,
                    description = "When a friend changes their display name") {
                    scope.launch { repo.saveNotifFriendDisplayName(it) }
                }
                ToggleRow("Friend trust rank changed", friendRank,
                    description = "Known → Trusted, New User → Known, etc.") {
                    scope.launch { repo.saveNotifFriendRank(it) }
                }

                SubSectionLabel("Alerts")
                ToggleRow("Vote-to-kick warnings", voteToKick,
                    description = "When a vote-to-kick is started in your instance") {
                    scope.launch { repo.saveNotifVoteToKick(it) }
                }
            }
        }
    }

    SectionCard(
        title = "Invites",
        subtitle = "World and group invites.",
        actions = {
            IconButton(onClick = { invitesExpanded = !invitesExpanded }) {
                Icon(
                    if (invitesExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (invitesExpanded) "Collapse" else "Expand"
                )
            }
        }
    ) {
        AnimatedVisibility(
            visible = invitesExpanded,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 3 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 3 })
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ToggleRow("World invites", invite,
                    description = "When someone invites you to a world") {
                    scope.launch { repo.saveNotifInvite(it) }
                }
                ToggleRow("Invite requests", inviteRequest,
                    description = "When someone asks for an invite to your instance") {
                    scope.launch { repo.saveNotifInviteRequest(it) }
                }
                ToggleRow("Group invites", groupInvite,
                    description = "When you're invited to join a group") {
                    scope.launch { repo.saveNotifGroupInvite(it) }
                }
            }
        }
    }

    SectionCard(
        title = "Groups",
        subtitle = "Activity in groups you're part of.",
        actions = {
            IconButton(onClick = { groupsExpanded = !groupsExpanded }) {
                Icon(
                    if (groupsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (groupsExpanded) "Collapse" else "Expand"
                )
            }
        }
    ) {
        AnimatedVisibility(
            visible = groupsExpanded,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 3 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 3 })
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SubSectionLabel("Updates", topPadding = 0.dp)
                ToggleRow("Group announcements", groupAnnouncement,
                    description = "Posts from group owners and managers") {
                    scope.launch { repo.saveNotifGroupAnnouncement(it) }
                }
                ToggleRow("Queue ready", groupQueue,
                    description = "When your spot in a group instance queue opens") {
                    scope.launch { repo.saveNotifGroupQueue(it) }
                }
                ToggleRow("New group instance opened", groupInstance,
                    description = "When a new joinable instance is created for a group") {
                    scope.launch { repo.saveNotifGroupInstance(it) }
                }

                SubSectionLabel("Management")
                ToggleRow("Join requests (group managers)", groupJoinRequest,
                    description = "When someone wants to join a group you manage") {
                    scope.launch { repo.saveNotifGroupJoinRequest(it) }
                }
                ToggleRow("Group role / rank changes", groupRole,
                    description = "When your role in a group is changed") {
                    scope.launch { repo.saveNotifGroupRole(it) }
                }
                ToggleRow("Other group activity", groupEvent,
                    description = "Catch-all for other group events") {
                    scope.launch { repo.saveNotifGroupEvent(it) }
                }
            }
        }
    }

    SectionCard(
        title = "App and connection",
        subtitle = "VRC-A system alerts.",
        actions = {
            IconButton(onClick = { appConnectionExpanded = !appConnectionExpanded }) {
                Icon(
                    if (appConnectionExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (appConnectionExpanded) "Collapse" else "Expand"
                )
            }
        }
    ) {
        AnimatedVisibility(
            visible = appConnectionExpanded,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 3 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 3 })
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SubSectionLabel("App", topPadding = 0.dp)
                ToggleRow("New app version available", appUpdate,
                    description = "When a new VRC-A update is available") {
                    scope.launch { repo.saveNotifAppUpdate(it) }
                }
                ToggleRow("Admin announcements", announcements,
                    description = "Messages from the VRC-A developer") {
                    scope.launch { repo.saveNotifAnnouncements(it) }
                }

                SubSectionLabel("VRChat")
                ToggleRow("VRChat connection status", connection,
                    description = "When VRChat monitoring connects or disconnects") {
                    scope.launch { repo.saveNotifConnection(it) }
                }
                ToggleRow("Sign-in required alerts", auth,
                    description = "When your VRChat session expires and needs re-login") {
                    scope.launch { repo.saveNotifAuth(it) }
                }
                ToggleRow("VRChat server alerts", vrchatAlert,
                    description = "Maintenance notices and other VRChat server alerts") {
                    scope.launch { repo.saveNotifVrchatAlert(it) }
                }
                ToggleRow("VRChat Plus / credit gifts", giftReceived,
                    description = "When someone sends you VRChat Plus or credits") {
                    scope.launch { repo.saveNotifGiftReceived(it) }
                }
            }
        }
    }
}
