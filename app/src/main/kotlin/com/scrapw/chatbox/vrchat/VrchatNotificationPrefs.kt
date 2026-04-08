package com.scrapw.chatbox.vrchat

/**
 * DataStore key names for per-event VRChat notification toggles.
 * These are used both in UserPreferencesRepository (to expose flows)
 * and in VrchatPipelineService (to gate notification firing).
 */
object VrchatNotificationPrefs {
    const val KEY_NOTIF_FRIEND_REQUEST      = "notif_friend_request"
    const val KEY_NOTIF_INVITE              = "notif_invite"
    const val KEY_NOTIF_FRIEND_ONLINE       = "notif_friend_online"
    const val KEY_NOTIF_FRIEND_OFFLINE      = "notif_friend_offline"
    const val KEY_NOTIF_UNFRIEND            = "notif_unfriend"
    const val KEY_NOTIF_GROUP_EVENT         = "notif_group_event"
    const val KEY_NOTIF_GROUP_ANNOUNCEMENT  = "notif_group_announcement"
    const val KEY_NOTIF_APP_UPDATE          = "notif_app_update"
    const val KEY_NOTIF_ANNOUNCEMENTS       = "notif_announcements"
}
