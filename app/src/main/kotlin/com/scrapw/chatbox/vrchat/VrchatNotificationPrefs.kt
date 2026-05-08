package com.scrapw.chatbox.vrchat

/**
 * DataStore key names for per-event VRChat notification toggles.
 * These are used both in UserPreferencesRepository (to expose flows)
 * and in VrchatPipelineService (to gate notification firing).
 */
object VrchatNotificationPrefs {
    // ── Friend list changes ─────────────────────────────────────────────
    const val KEY_NOTIF_FRIEND_REQUEST          = "notif_friend_request"          // Incoming friend request
    const val KEY_NOTIF_NEW_FRIEND              = "notif_new_friend"              // Friend added (you and X are now friends)
    const val KEY_NOTIF_UNFRIEND                = "notif_unfriend"                // Friend removed from list

    // ── Friends activity ────────────────────────────────────────────────
    const val KEY_NOTIF_FRIEND_ONLINE           = "notif_friend_online"           // Friend came online in VRChat
    const val KEY_NOTIF_FRIEND_OFFLINE          = "notif_friend_offline"          // Friend went offline
    const val KEY_NOTIF_FRIEND_ACTIVE           = "notif_friend_active"           // Friend active on website (not in VR)
    const val KEY_NOTIF_FRIEND_LOCATION         = "notif_friend_location"         // Friend changed worlds
    const val KEY_NOTIF_FRIEND_STATUS           = "notif_friend_status"           // Friend changed VRChat status (Online/DND/Ask Me/Join Me)
    const val KEY_NOTIF_FRIEND_AVATAR           = "notif_friend_avatar"           // Friend changed avatar
    const val KEY_NOTIF_FRIEND_BIO              = "notif_friend_bio"              // Friend updated profile/bio
    const val KEY_NOTIF_FRIEND_DISPLAY_NAME     = "notif_friend_display_name"     // Friend renamed themselves

    // ── Invites ─────────────────────────────────────────────────────────
    const val KEY_NOTIF_INVITE                  = "notif_invite"                  // World invite + invite request
    const val KEY_NOTIF_GROUP_INVITE            = "notif_group_invite"            // Invite to join a group

    // ── Groups ──────────────────────────────────────────────────────────
    const val KEY_NOTIF_GROUP_ANNOUNCEMENT      = "notif_group_announcement"      // Group announcement post
    const val KEY_NOTIF_GROUP_EVENT             = "notif_group_event"             // Generic catch-all for other group.* events
    const val KEY_NOTIF_GROUP_QUEUE             = "notif_group_queue"             // Group instance queue ready
    const val KEY_NOTIF_GROUP_JOIN_REQUEST      = "notif_group_join_request"      // Someone wants to join a group you manage
    const val KEY_NOTIF_GROUP_ROLE              = "notif_group_role"              // Your role/rank changed
    const val KEY_NOTIF_GROUP_INSTANCE          = "notif_group_instance"          // A new group instance opened

    // ── Friends activity (continued) ───────────────────────────────────
    const val KEY_NOTIF_VOTE_TO_KICK            = "notif_vote_to_kick"            // Vote-to-kick warning received
    const val KEY_NOTIF_FRIEND_RANK             = "notif_friend_rank"             // Friend trust rank changed

    // ── Friend list (continued) ────────────────────────────────────────
    const val KEY_NOTIF_VRCHAT_MESSAGE          = "notif_vrchat_message"          // VRChat in-app message received

    // ── App / system ────────────────────────────────────────────────────
    const val KEY_NOTIF_APP_UPDATE              = "notif_app_update"              // New VRC-A version available
    const val KEY_NOTIF_ANNOUNCEMENTS           = "notif_announcements"           // Admin announcements
    const val KEY_NOTIF_CONNECTION              = "notif_connection"              // VRChat pipeline connect/disconnect
    const val KEY_NOTIF_AUTH                    = "notif_auth"                    // Sign-in required / session expired
    const val KEY_NOTIF_VRCHAT_ALERT            = "notif_vrchat_alert"            // VRChat server alert (maintenance, flags)
    const val KEY_NOTIF_GIFT_RECEIVED           = "notif_gift_received"           // VRChat Plus / credit gift received
}
