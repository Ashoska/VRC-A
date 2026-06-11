// app/src/main/kotlin/com/vrca/data/UserPreferencesRepository.kt
package com.vrca.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import com.vrca.data.dataStore
import com.vrca.vrchat.VrchatNotificationPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant

class UserPreferencesRepository(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private object Keys {
        val IP   = stringPreferencesKey("ip_address")
        val PORT = intPreferencesKey("port")

        val IP_1_NAME    = stringPreferencesKey("ip_1_name")
        val IP_1_ADDRESS = stringPreferencesKey("ip_1_address")
        val IP_2_NAME    = stringPreferencesKey("ip_2_name")
        val IP_2_ADDRESS = stringPreferencesKey("ip_2_address")
        val IP_3_NAME    = stringPreferencesKey("ip_3_name")
        val IP_3_ADDRESS = stringPreferencesKey("ip_3_address")
        val ACTIVE_IP_SLOT = intPreferencesKey("active_ip_slot")

        val REALTIME         = booleanPreferencesKey("is_realtime_msg")
        val TRIGGER_SFX      = booleanPreferencesKey("is_trigger_sfx")
        val TYPING           = booleanPreferencesKey("is_typing_indicator")
        val SEND_IMMEDIATELY = booleanPreferencesKey("is_send_immediately")

        val PINNED_MESSAGE         = stringPreferencesKey("afk_message")
        val PINNED_MESSAGE_ENABLED = booleanPreferencesKey("afk_enabled")
        val PINNED_PRESET_1        = stringPreferencesKey("afk_preset_1")
        val PINNED_PRESET_2        = stringPreferencesKey("afk_preset_2")
        val PINNED_PRESET_3        = stringPreferencesKey("afk_preset_3")

        val CYCLE_ENABLED  = booleanPreferencesKey("cycle_enabled")
        val CYCLE_MESSAGES = stringPreferencesKey("cycle_messages")
        val CYCLE_INTERVAL = intPreferencesKey("cycle_interval")

        val CYCLE_P1_MSG  = stringPreferencesKey("cycle_p1_messages")
        val CYCLE_P1_INT  = intPreferencesKey("cycle_p1_interval")
        val CYCLE_P2_MSG  = stringPreferencesKey("cycle_p2_messages")
        val CYCLE_P2_INT  = intPreferencesKey("cycle_p2_interval")
        val CYCLE_P3_MSG  = stringPreferencesKey("cycle_p3_messages")
        val CYCLE_P3_INT  = intPreferencesKey("cycle_p3_interval")
        val CYCLE_P4_MSG  = stringPreferencesKey("cycle_p4_messages")
        val CYCLE_P4_INT  = intPreferencesKey("cycle_p4_interval")
        val CYCLE_P5_MSG  = stringPreferencesKey("cycle_p5_messages")
        val CYCLE_P5_INT  = intPreferencesKey("cycle_p5_interval")

        val SPOTIFY_ENABLED          = booleanPreferencesKey("spotify_enabled")
        val SPOTIFY_PRESET           = intPreferencesKey("spotify_preset")
        val PINNED_PRESETS_COLLAPSED = booleanPreferencesKey("afk_presets_collapsed")
        val CYCLE_PRESETS_COLLAPSED  = booleanPreferencesKey("cycle_presets_collapsed")
        val TIME_ENABLED             = booleanPreferencesKey("time_enabled")
        val TIME_MODE                = stringPreferencesKey("time_mode")
        val CARD_ORDER               = stringPreferencesKey("card_order")
        val MINIMAL_CHATBOX_BG       = booleanPreferencesKey("minimal_chatbox_bg")
        // Per-source Now Playing enables (Media tab). Default ON; an unlisted
        // media app is always allowed (no key for it).
        val MEDIA_SOURCE_SPOTIFY     = booleanPreferencesKey("media_source_spotify")
        val MEDIA_SOURCE_YOUTUBE     = booleanPreferencesKey("media_source_youtube")
        val MEDIA_SOURCE_YTMUSIC     = booleanPreferencesKey("media_source_ytmusic")
        // 24-hour clock for the Time chatbox line (default OFF = 12-hour AM/PM)
        val TIME_FORMAT_24H          = booleanPreferencesKey("time_format_24h")
        // Now Playing progress bar+time line (default ON; OFF = title only)
        val MUSIC_SHOW_PROGRESS      = booleanPreferencesKey("music_show_progress")

        val NOTIF_FRIEND_REQUEST       = booleanPreferencesKey(VrchatNotificationPrefs.KEY_NOTIF_FRIEND_REQUEST)
        val NOTIF_NEW_FRIEND           = booleanPreferencesKey(VrchatNotificationPrefs.KEY_NOTIF_NEW_FRIEND)
        val NOTIF_UNFRIEND             = booleanPreferencesKey(VrchatNotificationPrefs.KEY_NOTIF_UNFRIEND)
        val NOTIF_FRIEND_ONLINE        = booleanPreferencesKey(VrchatNotificationPrefs.KEY_NOTIF_FRIEND_ONLINE)
        val NOTIF_FRIEND_OFFLINE       = booleanPreferencesKey(VrchatNotificationPrefs.KEY_NOTIF_FRIEND_OFFLINE)
        val NOTIF_FRIEND_ACTIVE        = booleanPreferencesKey(VrchatNotificationPrefs.KEY_NOTIF_FRIEND_ACTIVE)
        val NOTIF_FRIEND_LOCATION      = booleanPreferencesKey(VrchatNotificationPrefs.KEY_NOTIF_FRIEND_LOCATION)
        val NOTIF_FRIEND_STATUS        = booleanPreferencesKey(VrchatNotificationPrefs.KEY_NOTIF_FRIEND_STATUS)
        val NOTIF_FRIEND_AVATAR        = booleanPreferencesKey(VrchatNotificationPrefs.KEY_NOTIF_FRIEND_AVATAR)
        val NOTIF_FRIEND_BIO           = booleanPreferencesKey(VrchatNotificationPrefs.KEY_NOTIF_FRIEND_BIO)
        val NOTIF_FRIEND_DISPLAY_NAME  = booleanPreferencesKey(VrchatNotificationPrefs.KEY_NOTIF_FRIEND_DISPLAY_NAME)
        val NOTIF_INVITE               = booleanPreferencesKey(VrchatNotificationPrefs.KEY_NOTIF_INVITE)
        val NOTIF_INVITE_REQUEST       = booleanPreferencesKey(VrchatNotificationPrefs.KEY_NOTIF_INVITE_REQUEST)
        val NOTIF_GROUP_INVITE         = booleanPreferencesKey(VrchatNotificationPrefs.KEY_NOTIF_GROUP_INVITE)
        val NOTIF_GROUP_ANNOUNCEMENT   = booleanPreferencesKey(VrchatNotificationPrefs.KEY_NOTIF_GROUP_ANNOUNCEMENT)
        val NOTIF_GROUP_EVENT          = booleanPreferencesKey(VrchatNotificationPrefs.KEY_NOTIF_GROUP_EVENT)
        val NOTIF_GROUP_QUEUE          = booleanPreferencesKey(VrchatNotificationPrefs.KEY_NOTIF_GROUP_QUEUE)
        val NOTIF_GROUP_JOIN_REQUEST   = booleanPreferencesKey(VrchatNotificationPrefs.KEY_NOTIF_GROUP_JOIN_REQUEST)
        val NOTIF_GROUP_ROLE           = booleanPreferencesKey(VrchatNotificationPrefs.KEY_NOTIF_GROUP_ROLE)
        val NOTIF_GROUP_INSTANCE       = booleanPreferencesKey(VrchatNotificationPrefs.KEY_NOTIF_GROUP_INSTANCE)
        val NOTIF_APP_UPDATE           = booleanPreferencesKey(VrchatNotificationPrefs.KEY_NOTIF_APP_UPDATE)
        val NOTIF_ANNOUNCEMENTS        = booleanPreferencesKey(VrchatNotificationPrefs.KEY_NOTIF_ANNOUNCEMENTS)
        val NOTIF_CONNECTION           = booleanPreferencesKey(VrchatNotificationPrefs.KEY_NOTIF_CONNECTION)
        val NOTIF_AUTH                 = booleanPreferencesKey(VrchatNotificationPrefs.KEY_NOTIF_AUTH)
        val NOTIF_VOTE_TO_KICK        = booleanPreferencesKey(VrchatNotificationPrefs.KEY_NOTIF_VOTE_TO_KICK)
        val NOTIF_FRIEND_RANK         = booleanPreferencesKey(VrchatNotificationPrefs.KEY_NOTIF_FRIEND_RANK)
        val NOTIF_VRCHAT_MESSAGE      = booleanPreferencesKey(VrchatNotificationPrefs.KEY_NOTIF_VRCHAT_MESSAGE)
        val NOTIF_VRCHAT_ALERT        = booleanPreferencesKey(VrchatNotificationPrefs.KEY_NOTIF_VRCHAT_ALERT)
        val NOTIF_GIFT_RECEIVED       = booleanPreferencesKey(VrchatNotificationPrefs.KEY_NOTIF_GIFT_RECEIVED)

        val NOTIF_SEEN_IDS               = stringPreferencesKey("notif_seen_ids")
        val NOTIF_GROUP_ANNOUNCEMENT_SEEN = stringPreferencesKey("notif_group_announcement_seen")
        val NOTIF_GIFT_SEEN_AT           = longPreferencesKey("notif_gift_seen_at")
        val NOTIF_BACKFILL_INITIALIZED   = booleanPreferencesKey("notif_backfill_initialized")
        val POSTS_EVENTS_BASELINE_V2     = booleanPreferencesKey("posts_events_baseline_v2")
        val CALENDAR_BASELINE_V3         = booleanPreferencesKey("calendar_baseline_v3")

        val TOS_ACCEPTED_VERSION  = intPreferencesKey("tos_accepted_version")
        val TOS_ACCEPTED_AT_EPOCH = longPreferencesKey("tos_accepted_at_epoch")
        val SETUP_VRCHAT_DONE     = booleanPreferencesKey("setup_vrchat_done")
        val SETUP_IP_DONE         = booleanPreferencesKey("setup_ip_done")
        // One-shot guard for the legacy single-IP → slot-1 migration. Without
        // this the migration re-runs on every recomposition and, because the
        // legacy `IP` key is kept in sync with the ACTIVE slot, it clones the
        // active (non-slot-1) address into slot 1 (the "IP cloning" bug).
        val IP_SLOT1_MIGRATED     = booleanPreferencesKey("ip_slot1_migrated")

        val DISCORD_RPC_ENABLED       = booleanPreferencesKey("discord_rpc_enabled")
        val DISCORD_SESSION_SEEDED    = booleanPreferencesKey("discord_session_seeded")
        val DISCORD_RISK_ACCEPTED     = booleanPreferencesKey("discord_risk_accepted")
    }

    val ipAddress: Flow<String> = context.dataStore.data.map { prefs ->
        val slot = prefs[Keys.ACTIVE_IP_SLOT] ?: 1
        val slotAddr = when (slot) {
            2 -> prefs[Keys.IP_2_ADDRESS]
            3 -> prefs[Keys.IP_3_ADDRESS]
            else -> prefs[Keys.IP_1_ADDRESS]
        }
        slotAddr?.takeIf { it.isNotBlank() }
            ?: prefs[Keys.IP]?.takeIf { it.isNotBlank() }
            ?: "127.0.0.1"
    }.distinctUntilChanged()
    val port: Flow<Int> = context.dataStore.data.map { it[Keys.PORT] ?: 9000 }.distinctUntilChanged()
    val ip1Name:      Flow<String> = context.dataStore.data.map { it[Keys.IP_1_NAME]    ?: "Home" }.distinctUntilChanged()
    val ip1Address:   Flow<String> = context.dataStore.data.map { it[Keys.IP_1_ADDRESS] ?: "" }.distinctUntilChanged()
    val ip2Name:      Flow<String> = context.dataStore.data.map { it[Keys.IP_2_NAME]    ?: "Hotspot" }.distinctUntilChanged()
    val ip2Address:   Flow<String> = context.dataStore.data.map { it[Keys.IP_2_ADDRESS] ?: "" }.distinctUntilChanged()
    val ip3Name:      Flow<String> = context.dataStore.data.map { it[Keys.IP_3_NAME]    ?: "Other" }.distinctUntilChanged()
    val ip3Address:   Flow<String> = context.dataStore.data.map { it[Keys.IP_3_ADDRESS] ?: "" }.distinctUntilChanged()
    val activeIpSlot: Flow<Int>    = context.dataStore.data.map { it[Keys.ACTIVE_IP_SLOT] ?: 1 }.distinctUntilChanged()

    val isRealtimeMsg:    Flow<Boolean> = context.dataStore.data.map { it[Keys.REALTIME]         ?: false }.distinctUntilChanged()
    val isTriggerSfx:     Flow<Boolean> = context.dataStore.data.map { it[Keys.TRIGGER_SFX]      ?: true }.distinctUntilChanged()
    val isTypingIndicator: Flow<Boolean> = context.dataStore.data.map { it[Keys.TYPING]           ?: true }.distinctUntilChanged()
    val isSendImmediately: Flow<Boolean> = context.dataStore.data.map { it[Keys.SEND_IMMEDIATELY] ?: true }.distinctUntilChanged()

    val pinnedMessage:        Flow<String>  = context.dataStore.data.map { it[Keys.PINNED_MESSAGE]         ?: "" }.distinctUntilChanged()
    val pinnedMessageEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.PINNED_MESSAGE_ENABLED] ?: false }.distinctUntilChanged()
    val pinnedPreset1:        Flow<String>  = context.dataStore.data.map { it[Keys.PINNED_PRESET_1]        ?: "" }.distinctUntilChanged()
    val pinnedPreset2:        Flow<String>  = context.dataStore.data.map { it[Keys.PINNED_PRESET_2]        ?: "" }.distinctUntilChanged()
    val pinnedPreset3:        Flow<String>  = context.dataStore.data.map { it[Keys.PINNED_PRESET_3]        ?: "" }.distinctUntilChanged()

    val cycleEnabled:  Flow<Boolean> = context.dataStore.data.map { it[Keys.CYCLE_ENABLED]  ?: false }.distinctUntilChanged()
    val cycleMessages: Flow<String>  = context.dataStore.data.map { it[Keys.CYCLE_MESSAGES] ?: "" }.distinctUntilChanged()
    val cycleInterval: Flow<Int>     = context.dataStore.data.map { it[Keys.CYCLE_INTERVAL] ?: 10 }.distinctUntilChanged()

    val cyclePreset1Messages: Flow<String> = context.dataStore.data.map { it[Keys.CYCLE_P1_MSG]  ?: "" }.distinctUntilChanged()
    val cyclePreset1Interval: Flow<Int>    = context.dataStore.data.map { it[Keys.CYCLE_P1_INT]  ?: 10 }.distinctUntilChanged()
    val cyclePreset2Messages: Flow<String> = context.dataStore.data.map { it[Keys.CYCLE_P2_MSG]  ?: "" }.distinctUntilChanged()
    val cyclePreset2Interval: Flow<Int>    = context.dataStore.data.map { it[Keys.CYCLE_P2_INT]  ?: 10 }.distinctUntilChanged()
    val cyclePreset3Messages: Flow<String> = context.dataStore.data.map { it[Keys.CYCLE_P3_MSG]  ?: "" }.distinctUntilChanged()
    val cyclePreset3Interval: Flow<Int>    = context.dataStore.data.map { it[Keys.CYCLE_P3_INT]  ?: 10 }.distinctUntilChanged()
    val cyclePreset4Messages: Flow<String> = context.dataStore.data.map { it[Keys.CYCLE_P4_MSG]  ?: "" }.distinctUntilChanged()
    val cyclePreset4Interval: Flow<Int>    = context.dataStore.data.map { it[Keys.CYCLE_P4_INT]  ?: 10 }.distinctUntilChanged()
    val cyclePreset5Messages: Flow<String> = context.dataStore.data.map { it[Keys.CYCLE_P5_MSG]  ?: "" }.distinctUntilChanged()
    val cyclePreset5Interval: Flow<Int>    = context.dataStore.data.map { it[Keys.CYCLE_P5_INT]  ?: 10 }.distinctUntilChanged()

    val spotifyEnabled:        Flow<Boolean> = context.dataStore.data.map { it[Keys.SPOTIFY_ENABLED] ?: false }.distinctUntilChanged()
    val spotifyPreset:         Flow<Int>     = context.dataStore.data.map { (it[Keys.SPOTIFY_PRESET] ?: 1).coerceIn(1, 5) }.distinctUntilChanged()
    val pinnedPresetsCollapsed: Flow<Boolean> = context.dataStore.data.map { it[Keys.PINNED_PRESETS_COLLAPSED] ?: true }.distinctUntilChanged()
    val cyclePresetsCollapsed:  Flow<Boolean> = context.dataStore.data.map { it[Keys.CYCLE_PRESETS_COLLAPSED]  ?: true }.distinctUntilChanged()
    val timeEnabled:            Flow<Boolean> = context.dataStore.data.map { it[Keys.TIME_ENABLED] ?: false }.distinctUntilChanged()
    val timeMode:               Flow<String>  = context.dataStore.data.map { it[Keys.TIME_MODE]    ?: "Device" }.distinctUntilChanged()
    val cardOrder: Flow<List<String>> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.CARD_ORDER] ?: "Time,Pinned,Cycle,NowPlaying"
        raw.split(",").map { it.trim().let { k -> if (k == "AFK") "Pinned" else k } }.filter { it.isNotBlank() }
    }.distinctUntilChanged()
    val minimalChatboxBg:       Flow<Boolean> = context.dataStore.data.map { it[Keys.MINIMAL_CHATBOX_BG] ?: false }.distinctUntilChanged()
    val mediaSourceSpotify:     Flow<Boolean> = context.dataStore.data.map { it[Keys.MEDIA_SOURCE_SPOTIFY] ?: true }.distinctUntilChanged()
    val mediaSourceYoutube:     Flow<Boolean> = context.dataStore.data.map { it[Keys.MEDIA_SOURCE_YOUTUBE] ?: true }.distinctUntilChanged()
    val mediaSourceYtMusic:     Flow<Boolean> = context.dataStore.data.map { it[Keys.MEDIA_SOURCE_YTMUSIC] ?: true }.distinctUntilChanged()
    val timeFormat24h:          Flow<Boolean> = context.dataStore.data.map { it[Keys.TIME_FORMAT_24H] ?: false }.distinctUntilChanged()
    val musicShowProgress:      Flow<Boolean> = context.dataStore.data.map { it[Keys.MUSIC_SHOW_PROGRESS] ?: true }.distinctUntilChanged()

    val notifFriendRequest:        Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIF_FRIEND_REQUEST]        ?: false }.distinctUntilChanged()
    val notifNewFriend:            Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIF_NEW_FRIEND]            ?: false }.distinctUntilChanged()
    val notifUnfriend:             Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIF_UNFRIEND]              ?: false }.distinctUntilChanged()
    val notifFriendOnline:         Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIF_FRIEND_ONLINE]         ?: false }.distinctUntilChanged()
    val notifFriendOffline:        Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIF_FRIEND_OFFLINE]        ?: false }.distinctUntilChanged()
    val notifFriendActive:         Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIF_FRIEND_ACTIVE]         ?: false }.distinctUntilChanged()
    val notifFriendLocation:       Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIF_FRIEND_LOCATION]       ?: false }.distinctUntilChanged()
    val notifFriendStatus:         Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIF_FRIEND_STATUS]         ?: false }.distinctUntilChanged()
    val notifFriendAvatar:         Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIF_FRIEND_AVATAR]         ?: false }.distinctUntilChanged()
    val notifFriendBio:            Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIF_FRIEND_BIO]            ?: false }.distinctUntilChanged()
    val notifFriendDisplayName:    Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIF_FRIEND_DISPLAY_NAME]   ?: false }.distinctUntilChanged()
    val notifInvite:               Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIF_INVITE]                ?: false }.distinctUntilChanged()
    // Auto-mirror legacy combined-invite preference: if user previously enabled
    // KEY_NOTIF_INVITE (which used to gate both world invites AND invite requests),
    // treat invite-requests as enabled too until they explicitly toggle the new
    // KEY_NOTIF_INVITE_REQUEST. After that, the explicit setting takes over.
    val notifInviteRequest:        Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.NOTIF_INVITE_REQUEST] ?: (prefs[Keys.NOTIF_INVITE] ?: false)
    }.distinctUntilChanged()
    val notifGroupInvite:          Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIF_GROUP_INVITE]          ?: false }.distinctUntilChanged()
    val notifGroupAnnouncement:    Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIF_GROUP_ANNOUNCEMENT]    ?: false }.distinctUntilChanged()
    val notifGroupEvent:           Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIF_GROUP_EVENT]           ?: false }.distinctUntilChanged()
    val notifGroupQueue:           Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIF_GROUP_QUEUE]           ?: false }.distinctUntilChanged()
    val notifGroupJoinRequest:     Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIF_GROUP_JOIN_REQUEST]    ?: false }.distinctUntilChanged()
    val notifGroupRole:            Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIF_GROUP_ROLE]            ?: false }.distinctUntilChanged()
    val notifGroupInstance:        Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIF_GROUP_INSTANCE]        ?: false }.distinctUntilChanged()
    val notifAppUpdate:            Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIF_APP_UPDATE]            ?: true  }.distinctUntilChanged()
    val notifAnnouncements:        Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIF_ANNOUNCEMENTS]         ?: true  }.distinctUntilChanged()
    val notifConnection:           Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIF_CONNECTION]            ?: false }.distinctUntilChanged()
    val notifAuth:                 Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIF_AUTH]                  ?: true  }.distinctUntilChanged()
    val notifVoteToKick:           Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIF_VOTE_TO_KICK]          ?: false }.distinctUntilChanged()
    val notifFriendRank:           Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIF_FRIEND_RANK]           ?: false }.distinctUntilChanged()
    val notifVrchatMessage:        Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIF_VRCHAT_MESSAGE]        ?: false }.distinctUntilChanged()
    val notifVrchatAlert:          Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIF_VRCHAT_ALERT]          ?: false }.distinctUntilChanged()
    val notifGiftReceived:         Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIF_GIFT_RECEIVED]         ?: false }.distinctUntilChanged()

    val notifSeenIds:              Flow<String>  = context.dataStore.data.map { it[Keys.NOTIF_SEEN_IDS]              ?: "[]" }.distinctUntilChanged()
    val notifGroupAnnouncementSeen: Flow<String> = context.dataStore.data.map { it[Keys.NOTIF_GROUP_ANNOUNCEMENT_SEEN] ?: "{}" }.distinctUntilChanged()
    val notifGiftSeenAt:           Flow<Long>    = context.dataStore.data.map { it[Keys.NOTIF_GIFT_SEEN_AT]          ?: 0L }.distinctUntilChanged()
    val notifBackfillInitialized:  Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIF_BACKFILL_INITIALIZED]  ?: false }.distinctUntilChanged()

    val setupVrchatDone: Flow<Boolean> = context.dataStore.data.map { it[Keys.SETUP_VRCHAT_DONE] ?: false }.distinctUntilChanged()
    val setupIpDone:     Flow<Boolean> = context.dataStore.data.map { it[Keys.SETUP_IP_DONE]     ?: false }.distinctUntilChanged()
    val ipSlot1Migrated: Flow<Boolean> = context.dataStore.data.map { it[Keys.IP_SLOT1_MIGRATED] ?: false }.distinctUntilChanged()

    val discordRpcEnabled:     Flow<Boolean> = context.dataStore.data.map { it[Keys.DISCORD_RPC_ENABLED]    ?: false }.distinctUntilChanged()
    val discordSessionSeeded:  Flow<Boolean> = context.dataStore.data.map { it[Keys.DISCORD_SESSION_SEEDED] ?: false }.distinctUntilChanged()
    val discordRiskAccepted:   Flow<Boolean> = context.dataStore.data.map { it[Keys.DISCORD_RISK_ACCEPTED]  ?: false }.distinctUntilChanged()

    suspend fun saveDiscordRpcEnabled(value: Boolean) = context.dataStore.edit { it[Keys.DISCORD_RPC_ENABLED] = value }
    suspend fun saveDiscordSessionSeeded(value: Boolean) = context.dataStore.edit { it[Keys.DISCORD_SESSION_SEEDED] = value }
    suspend fun saveDiscordRiskAccepted(value: Boolean) = context.dataStore.edit { it[Keys.DISCORD_RISK_ACCEPTED] = value }

    suspend fun savePort(value: Int)              = context.dataStore.edit { it[Keys.PORT] = value.coerceIn(1, 65535) }
    suspend fun saveIsRealtimeMsg(value: Boolean) = context.dataStore.edit { it[Keys.REALTIME]         = value }
    suspend fun saveIsTriggerSFX(value: Boolean)  = context.dataStore.edit { it[Keys.TRIGGER_SFX]     = value }
    suspend fun saveTypingIndicator(value: Boolean) = context.dataStore.edit { it[Keys.TYPING]         = value }
    suspend fun saveIsSendImmediately(value: Boolean) = context.dataStore.edit { it[Keys.SEND_IMMEDIATELY] = value }

    suspend fun saveIpAddress(value: String) {
        context.dataStore.edit { prefs ->
            val slot = prefs[Keys.ACTIVE_IP_SLOT] ?: 1
            when (slot) {
                2 -> prefs[Keys.IP_2_ADDRESS] = value.trim()
                3 -> prefs[Keys.IP_3_ADDRESS] = value.trim()
                else -> { prefs[Keys.IP_1_ADDRESS] = value.trim(); prefs[Keys.IP] = value.trim() }
            }
        }
    }
    suspend fun saveIpSlot(slot: Int, name: String, address: String) {
        context.dataStore.edit { prefs ->
            when (slot.coerceIn(1, 3)) {
                1 -> { prefs[Keys.IP_1_NAME] = name; prefs[Keys.IP_1_ADDRESS] = address; prefs[Keys.IP] = address }
                2 -> { prefs[Keys.IP_2_NAME] = name; prefs[Keys.IP_2_ADDRESS] = address }
                3 -> { prefs[Keys.IP_3_NAME] = name; prefs[Keys.IP_3_ADDRESS] = address }
            }
        }
    }
    /**
     * Save only the address for a slot. Use from the Apply button and the
     * slot-1 migration LaunchedEffect — both of which would otherwise read
     * the slot name from a flow that may still be emitting its placeholder
     * default ("Home"/"Hotspot"/"Other"), overwriting the user's saved name.
     * The slot name is owned exclusively by the auto-save debounce in IpField.
     */
    suspend fun saveIpSlotAddress(slot: Int, address: String) {
        context.dataStore.edit { prefs ->
            when (slot.coerceIn(1, 3)) {
                1 -> { prefs[Keys.IP_1_ADDRESS] = address; prefs[Keys.IP] = address }
                2 -> { prefs[Keys.IP_2_ADDRESS] = address }
                3 -> { prefs[Keys.IP_3_ADDRESS] = address }
            }
        }
    }
    suspend fun saveActiveIpSlot(slot: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ACTIVE_IP_SLOT] = slot.coerceIn(1, 3)
            val addr = when (slot) {
                2 -> prefs[Keys.IP_2_ADDRESS]; 3 -> prefs[Keys.IP_3_ADDRESS]
                else -> prefs[Keys.IP_1_ADDRESS]
            }
            if (!addr.isNullOrBlank()) prefs[Keys.IP] = addr
        }
    }

    suspend fun savePinnedMessage(v: String)         = context.dataStore.edit { it[Keys.PINNED_MESSAGE]         = v }
    suspend fun savePinnedMessageEnabled(v: Boolean) = context.dataStore.edit { it[Keys.PINNED_MESSAGE_ENABLED] = v }
    suspend fun savePinnedPreset1(v: String)         = context.dataStore.edit { it[Keys.PINNED_PRESET_1]        = v }
    suspend fun savePinnedPreset2(v: String)         = context.dataStore.edit { it[Keys.PINNED_PRESET_2]        = v }
    suspend fun savePinnedPreset3(v: String)         = context.dataStore.edit { it[Keys.PINNED_PRESET_3]        = v }

    suspend fun saveCycleEnabled(v: Boolean)  = context.dataStore.edit { it[Keys.CYCLE_ENABLED]  = v }
    suspend fun saveCycleMessages(v: String)  = context.dataStore.edit { it[Keys.CYCLE_MESSAGES] = v }
    suspend fun saveCycleInterval(v: Int)     = context.dataStore.edit { it[Keys.CYCLE_INTERVAL] = v }
    suspend fun saveCyclePreset1(messages: String, interval: Int, name: String? = null) { context.dataStore.edit { it[Keys.CYCLE_P1_MSG] = messages; it[Keys.CYCLE_P1_INT] = interval } }
    suspend fun saveCyclePreset2(messages: String, interval: Int, name: String? = null) { context.dataStore.edit { it[Keys.CYCLE_P2_MSG] = messages; it[Keys.CYCLE_P2_INT] = interval } }
    suspend fun saveCyclePreset3(messages: String, interval: Int, name: String? = null) { context.dataStore.edit { it[Keys.CYCLE_P3_MSG] = messages; it[Keys.CYCLE_P3_INT] = interval } }
    suspend fun saveCyclePreset4(messages: String, interval: Int, name: String? = null) { context.dataStore.edit { it[Keys.CYCLE_P4_MSG] = messages; it[Keys.CYCLE_P4_INT] = interval } }
    suspend fun saveCyclePreset5(messages: String, interval: Int, name: String? = null) { context.dataStore.edit { it[Keys.CYCLE_P5_MSG] = messages; it[Keys.CYCLE_P5_INT] = interval } }

    suspend fun saveSpotifyEnabled(v: Boolean)           = context.dataStore.edit { it[Keys.SPOTIFY_ENABLED]         = v }
    suspend fun saveSpotifyPreset(v: Int)               = context.dataStore.edit { it[Keys.SPOTIFY_PRESET]           = v.coerceIn(1, 5) }
    suspend fun savePinnedPresetsCollapsed(v: Boolean)  = context.dataStore.edit { it[Keys.PINNED_PRESETS_COLLAPSED] = v }
    suspend fun saveCyclePresetsCollapsed(v: Boolean)   = context.dataStore.edit { it[Keys.CYCLE_PRESETS_COLLAPSED]  = v }
    suspend fun saveTimeEnabled(v: Boolean)             = context.dataStore.edit { it[Keys.TIME_ENABLED] = v }
    suspend fun saveTimeMode(v: String)                 = context.dataStore.edit { it[Keys.TIME_MODE]    = v }
    suspend fun saveCardOrder(order: List<String>)      = context.dataStore.edit { it[Keys.CARD_ORDER]   = order.joinToString(",") }
    suspend fun saveMinimalChatboxBg(v: Boolean)        = context.dataStore.edit { it[Keys.MINIMAL_CHATBOX_BG] = v }
    suspend fun saveMediaSourceSpotify(v: Boolean)      = context.dataStore.edit { it[Keys.MEDIA_SOURCE_SPOTIFY] = v }
    suspend fun saveMediaSourceYoutube(v: Boolean)      = context.dataStore.edit { it[Keys.MEDIA_SOURCE_YOUTUBE] = v }
    suspend fun saveMediaSourceYtMusic(v: Boolean)      = context.dataStore.edit { it[Keys.MEDIA_SOURCE_YTMUSIC] = v }
    suspend fun saveTimeFormat24h(v: Boolean)           = context.dataStore.edit { it[Keys.TIME_FORMAT_24H] = v }
    suspend fun saveMusicShowProgress(v: Boolean)       = context.dataStore.edit { it[Keys.MUSIC_SHOW_PROGRESS] = v }

    suspend fun saveNotifFriendRequest(v: Boolean)        = context.dataStore.edit { it[Keys.NOTIF_FRIEND_REQUEST]        = v }
    suspend fun saveNotifNewFriend(v: Boolean)            = context.dataStore.edit { it[Keys.NOTIF_NEW_FRIEND]            = v }
    suspend fun saveNotifUnfriend(v: Boolean)             = context.dataStore.edit { it[Keys.NOTIF_UNFRIEND]              = v }
    suspend fun saveNotifFriendOnline(v: Boolean)         = context.dataStore.edit { it[Keys.NOTIF_FRIEND_ONLINE]         = v }
    suspend fun saveNotifFriendOffline(v: Boolean)        = context.dataStore.edit { it[Keys.NOTIF_FRIEND_OFFLINE]        = v }
    suspend fun saveNotifFriendActive(v: Boolean)         = context.dataStore.edit { it[Keys.NOTIF_FRIEND_ACTIVE]         = v }
    suspend fun saveNotifFriendLocation(v: Boolean)       = context.dataStore.edit { it[Keys.NOTIF_FRIEND_LOCATION]       = v }
    suspend fun saveNotifFriendStatus(v: Boolean)         = context.dataStore.edit { it[Keys.NOTIF_FRIEND_STATUS]         = v }
    suspend fun saveNotifFriendAvatar(v: Boolean)         = context.dataStore.edit { it[Keys.NOTIF_FRIEND_AVATAR]         = v }
    suspend fun saveNotifFriendBio(v: Boolean)            = context.dataStore.edit { it[Keys.NOTIF_FRIEND_BIO]            = v }
    suspend fun saveNotifFriendDisplayName(v: Boolean)    = context.dataStore.edit { it[Keys.NOTIF_FRIEND_DISPLAY_NAME]   = v }
    suspend fun saveNotifInvite(v: Boolean)               = context.dataStore.edit { it[Keys.NOTIF_INVITE]                = v }
    suspend fun saveNotifInviteRequest(v: Boolean)        = context.dataStore.edit { it[Keys.NOTIF_INVITE_REQUEST]        = v }
    suspend fun saveNotifGroupInvite(v: Boolean)          = context.dataStore.edit { it[Keys.NOTIF_GROUP_INVITE]          = v }
    suspend fun saveNotifGroupAnnouncement(v: Boolean)    = context.dataStore.edit { it[Keys.NOTIF_GROUP_ANNOUNCEMENT]    = v }
    suspend fun saveNotifGroupEvent(v: Boolean)           = context.dataStore.edit { it[Keys.NOTIF_GROUP_EVENT]           = v }
    suspend fun saveNotifGroupQueue(v: Boolean)           = context.dataStore.edit { it[Keys.NOTIF_GROUP_QUEUE]           = v }
    suspend fun saveNotifGroupJoinRequest(v: Boolean)     = context.dataStore.edit { it[Keys.NOTIF_GROUP_JOIN_REQUEST]    = v }
    suspend fun saveNotifGroupRole(v: Boolean)            = context.dataStore.edit { it[Keys.NOTIF_GROUP_ROLE]            = v }
    suspend fun saveNotifGroupInstance(v: Boolean)        = context.dataStore.edit { it[Keys.NOTIF_GROUP_INSTANCE]        = v }
    suspend fun saveNotifAppUpdate(v: Boolean)            = context.dataStore.edit { it[Keys.NOTIF_APP_UPDATE]            = v }
    suspend fun saveNotifAnnouncements(v: Boolean)        = context.dataStore.edit { it[Keys.NOTIF_ANNOUNCEMENTS]         = v }
    suspend fun saveNotifConnection(v: Boolean)           = context.dataStore.edit { it[Keys.NOTIF_CONNECTION]            = v }
    suspend fun saveNotifAuth(v: Boolean)                 = context.dataStore.edit { it[Keys.NOTIF_AUTH]                  = v }
    suspend fun saveNotifVoteToKick(v: Boolean)           = context.dataStore.edit { it[Keys.NOTIF_VOTE_TO_KICK]          = v }
    suspend fun saveNotifFriendRank(v: Boolean)           = context.dataStore.edit { it[Keys.NOTIF_FRIEND_RANK]           = v }
    suspend fun saveNotifVrchatMessage(v: Boolean)        = context.dataStore.edit { it[Keys.NOTIF_VRCHAT_MESSAGE]        = v }
    suspend fun saveNotifVrchatAlert(v: Boolean)          = context.dataStore.edit { it[Keys.NOTIF_VRCHAT_ALERT]          = v }
    suspend fun saveNotifGiftReceived(v: Boolean)         = context.dataStore.edit { it[Keys.NOTIF_GIFT_RECEIVED]         = v }

    suspend fun saveNotifSeenIds(v: String)               = context.dataStore.edit { it[Keys.NOTIF_SEEN_IDS]              = v }
    suspend fun saveNotifGroupAnnouncementSeen(v: String) = context.dataStore.edit { it[Keys.NOTIF_GROUP_ANNOUNCEMENT_SEEN] = v }
    suspend fun saveNotifGiftSeenAt(v: Long)              = context.dataStore.edit { it[Keys.NOTIF_GIFT_SEEN_AT]          = v }
    suspend fun saveNotifBackfillInitialized(v: Boolean)  = context.dataStore.edit { it[Keys.NOTIF_BACKFILL_INITIALIZED]  = v }
    suspend fun savePostsEventsBaselineV2(v: Boolean)    = context.dataStore.edit { it[Keys.POSTS_EVENTS_BASELINE_V2]  = v }
    suspend fun saveCalendarBaselineV3(v: Boolean)       = context.dataStore.edit { it[Keys.CALENDAR_BASELINE_V3]      = v }

    suspend fun saveSetupVrchatDone(v: Boolean) = context.dataStore.edit { it[Keys.SETUP_VRCHAT_DONE] = v }
    suspend fun saveSetupIpDone(v: Boolean)     = context.dataStore.edit { it[Keys.SETUP_IP_DONE]     = v }
    suspend fun saveIpSlot1Migrated(v: Boolean) = context.dataStore.edit { it[Keys.IP_SLOT1_MIGRATED] = v }

    fun tosAcceptedStateFlow(currentVersion: Int): StateFlow<Boolean> =
        context.dataStore.data
            .map { (it[Keys.TOS_ACCEPTED_VERSION] ?: 0) >= currentVersion }
            .stateIn(scope, SharingStarted.Eagerly, false)

    suspend fun saveTosAcceptedVersion(version: Int) {
        context.dataStore.edit {
            it[Keys.TOS_ACCEPTED_VERSION]  = version
            it[Keys.TOS_ACCEPTED_AT_EPOCH] = Instant.now().epochSecond
        }
    }

    // Backwards-compat aliases (AFK -> Pinned rename, ViewModel doesn't need changes)
    val afkMessage: Flow<String>  get() = pinnedMessage
    val afkEnabled: Flow<Boolean> get() = pinnedMessageEnabled
    val afkPreset1: Flow<String>  get() = pinnedPreset1
    val afkPreset2: Flow<String>  get() = pinnedPreset2
    val afkPreset3: Flow<String>  get() = pinnedPreset3
    val afkPresetsCollapsed: Flow<Boolean> get() = pinnedPresetsCollapsed
    suspend fun saveAfkEnabled(v: Boolean)           = savePinnedMessageEnabled(v)
    suspend fun saveAfkMessage(v: String)           = savePinnedMessage(v)
    suspend fun saveAfkPreset1(v: String)           = savePinnedPreset1(v)
    suspend fun saveAfkPreset2(v: String)           = savePinnedPreset2(v)
    suspend fun saveAfkPreset3(v: String)           = savePinnedPreset3(v)
    suspend fun saveAfkPresetsCollapsed(v: Boolean) = savePinnedPresetsCollapsed(v)
}
