package com.vrca.vrchat

/**
 * VRChat output-log parser (M2 headset instance-roster feature).
 *
 * VRChat (the PC client) writes a plain-text `output_log_*.txt` to its
 * persistent-data directory. On a Quest running VRChat natively the same
 * client writes an equivalent log; this parser turns those lines into a
 * structured event stream + a folded roster/location model so the headset
 * build can be the sole writer of the instance-roster + live-location fields
 * (see docs/account-system-plan.md §2 for the device-role/source-of-truth
 * model).
 *
 * This file is INTENTIONALLY pure Kotlin — no Android imports — so the grammar
 * can be unit-verified with a throwaway harness. The file-reading + Quest
 * storage-permission (`MANAGE_EXTERNAL_STORAGE`/SAF) is the M2 hookup and lives
 * elsewhere; this is just the parse + fold, "ready to hook up".
 *
 * Log-line shape (PC format, variable whitespace):
 *
 *   2024.01.15 22:34:56 Log        -  [Behaviour] OnPlayerJoined Alice (usr_abc)
 *   ^timestamp          ^level        ^message
 *
 * The timestamp/level prefix is optional — [parseLine] tolerates a bare
 * message too, so a caller that already stripped the prefix still works.
 */
object VrcLogParser {

    /** One meaningful thing that happened, parsed from a single log line. */
    sealed class LogEvent {
        /** Local player is JOINING an instance. Carries the full location
         *  string (world:instance~tags~nonce) plus the split-out pieces. */
        data class Joining(
            val worldId: String,
            val instanceId: String,
            val location: String,
            val nonce: String?
        ) : LogEvent()

        /** Human-readable world name for the instance just joined
         *  (`[Behaviour] Entering Room: <name>`). */
        data class EnteringRoom(val worldName: String) : LogEvent()

        /** Finished loading into the room — roster is now authoritative. */
        object JoinedRoom : LogEvent()

        /** Local player LEFT the instance (`OnLeftRoom`). Clears the roster. */
        object LeftRoom : LogEvent()

        /** VRChat's networking logged its instance goodbye ("...good night
         *  server!") — the local player DISCONNECTED (quit / headset shutdown /
         *  extended background). Distinct from [LeftRoom] (a clean in-client
         *  instance leave), which VRChat does NOT write on a quit/shutdown. This
         *  flags [InstanceState.suspended] WITHOUT clearing the roster, so the
         *  reader can force "not in an instance" instantly (rather than waiting out
         *  OSCQuery's grace) while a brief background pause that resumes still
         *  repopulates the retained roster. */
        object Disconnected : LogEvent()

        /** Another player entered the instance. `userId` is null on the older
         *  log format that logged only the display name. */
        data class PlayerJoined(val displayName: String, val userId: String?) : LogEvent()

        /** A player left the instance. */
        data class PlayerLeft(val displayName: String, val userId: String?) : LogEvent()

        /** A player switched avatars (`Switching <name> to avatar <avatar>`). */
        data class AvatarSwitch(val displayName: String, val avatarName: String) : LogEvent()

        /** `Unpacking Avatar (<avatarName> by <author>)` — gives the avatar's author,
         *  used (with the avatar name) to resolve the exact avatar id on demand. */
        data class AvatarUnpack(val avatarName: String, val author: String) : LogEvent()
    }

    // Message-body matchers (prefix already stripped). VRChat prefixes most of
    // these with "[Behaviour] " but not universally, so the tag is optional.
    private val RE_JOINING = Regex(
        """\[Behaviour]\s+Joining\s+(wrld_[A-Za-z0-9_-]+):(\S+)"""
    )
    private val RE_ENTERING = Regex(
        """\[Behaviour]\s+Entering Room:\s+(.+)$"""
    )
    // VRChat also logs the world name as "Joining or Creating Room: <name>"
    // (distinct from the "Joining wrld_…" location line above).
    private val RE_JOINING_ROOM = Regex(
        """\[Behaviour]\s+Joining or Creating Room:\s+(.+)$"""
    )
    private val RE_JOINED = Regex(
        """\[Behaviour]\s+(?:Successfully joined room|OnJoinedRoom)\b"""
    )
    private val RE_LEFT_ROOM = Regex(
        """\[Behaviour]\s+OnLeftRoom\b"""
    )
    // "OnPlayerJoined Alice (usr_abc)" — the "(usr_...)" tail is optional
    // (older builds logged only the name). Name may contain spaces, so we
    // capture greedily up to an optional trailing " (usr_...)".
    private val RE_PLAYER_JOINED = Regex(
        """\[Behaviour]\s+OnPlayerJoined\s+(.+?)(?:\s+\((usr_[A-Za-z0-9_-]+)\))?\s*$"""
    )
    private val RE_PLAYER_LEFT = Regex(
        """\[Behaviour]\s+OnPlayerLeft\s+(.+?)(?:\s+\((usr_[A-Za-z0-9_-]+)\))?\s*$"""
    )
    private val RE_AVATAR_SWITCH = Regex(
        """\[Behaviour]\s+Switching\s+(.+?)\s+to avatar\s+(.+)$"""
    )
    // "[…] Unpacking Avatar (<avatarName> by <author>)" — carries the author. On
    // Quest this is logged by [AssetBundleDownloadManager], not [Behaviour], so the
    // tag is left unanchored.
    private val RE_AVATAR_UNPACK = Regex(
        """Unpacking Avatar\s+\((.+?)\s+by\s+(.+?)\)\s*$"""
    )
    // Leading "YYYY.MM.DD HH:MM:SS   Level   -  " prefix (variable spacing).
    private val RE_PREFIX = Regex(
        """^\d{4}\.\d{2}\.\d{2}\s+\d{2}:\d{2}:\d{2}\s+\w+\s+-\s+"""
    )

    /**
     * Parse a single raw log line into a [LogEvent], or null when the line is
     * not one we care about. Tolerant of the timestamp/level prefix being
     * present or already stripped.
     */
    fun parseLine(raw: String): LogEvent? {
        val body = RE_PREFIX.replaceFirst(raw, "").trim()
        if (body.isEmpty()) return null

        RE_JOINING.find(body)?.let { m ->
            val worldId = m.groupValues[1]
            val instancePart = m.groupValues[2]
            val instanceId = instancePart.substringBefore('~')
            val nonce = Regex("""~nonce\(([^)]+)\)""").find(instancePart)?.groupValues?.get(1)
            return LogEvent.Joining(
                worldId = worldId,
                instanceId = instanceId,
                location = "$worldId:$instancePart",
                nonce = nonce
            )
        }
        RE_ENTERING.find(body)?.let { return LogEvent.EnteringRoom(it.groupValues[1].trim()) }
        RE_JOINING_ROOM.find(body)?.let { return LogEvent.EnteringRoom(it.groupValues[1].trim()) }
        if (RE_JOINED.containsMatchIn(body)) return LogEvent.JoinedRoom
        if (RE_LEFT_ROOM.containsMatchIn(body)) return LogEvent.LeftRoom
        // VRChat's netcode goodbye on disconnect (quit / headset shutdown). Not
        // "[Behaviour]"-prefixed, so match the distinctive phrase directly.
        if (body.contains("good night server", ignoreCase = true)) return LogEvent.Disconnected
        RE_PLAYER_JOINED.find(body)?.let { m ->
            val name = m.groupValues[1].trim()
            if (name.isNotEmpty()) {
                return LogEvent.PlayerJoined(name, m.groupValues[2].ifBlank { null })
            }
        }
        RE_PLAYER_LEFT.find(body)?.let { m ->
            val name = m.groupValues[1].trim()
            if (name.isNotEmpty()) {
                return LogEvent.PlayerLeft(name, m.groupValues[2].ifBlank { null })
            }
        }
        RE_AVATAR_SWITCH.find(body)?.let { m ->
            return LogEvent.AvatarSwitch(m.groupValues[1].trim(), m.groupValues[2].trim())
        }
        RE_AVATAR_UNPACK.find(body)?.let { m ->
            return LogEvent.AvatarUnpack(m.groupValues[1].trim(), m.groupValues[2].trim())
        }
        return null
    }

    /** One person currently in the instance, folded from the event stream. */
    data class RosterEntry(
        val displayName: String,
        val userId: String?,
        val joinedAtMs: Long,
        val avatarName: String? = null,
        // Avatar author from `Unpacking Avatar (<name> by <author>)`. With the avatar
        // NAME this resolves the exact avatar id on demand (Quest never logs the id).
        val avatarCreator: String? = null,
        // Filled by the platform-API hookup (VrchatAuthManager.fetchUserInfo →
        // prettyPlatform). Left blank by the parser; the reader enriches it.
        val platform: String = ""
    )

    /** The instance we're in plus everyone the log says is in it. */
    data class InstanceState(
        val location: String? = null,
        val worldId: String? = null,
        val instanceId: String? = null,
        val worldName: String? = null,
        val nonce: String? = null,
        val joinedRoom: Boolean = false,
        // Set by a Disconnected event ("good night server") and cleared by any real
        // in-instance activity (join/leave/hop). The roster is RETAINED while this is
        // true so a brief background pause repopulates on resume; the reader treats a
        // suspended state as "not present in an instance right now".
        val suspended: Boolean = false,
        // Keyed by userId when known, else by a "name:<displayName>" fallback so
        // the older name-only log format still tracks a person.
        val roster: Map<String, RosterEntry> = emptyMap(),
        // Avatar names seen (by display name) BEFORE the player was in the roster.
        // VRChat logs `Switching X to avatar Y` BEFORE `OnPlayerJoined X`, so the
        // switch would otherwise be dropped and the avatar (→ clone button) wouldn't
        // appear until the person changed avatar again. Buffered here, applied on join.
        val pendingAvatarByName: Map<String, String> = emptyMap()
    ) {
        val playerCount: Int get() = roster.size
    }

    private fun rosterKey(userId: String?, displayName: String): String =
        userId ?: "name:$displayName"

    /**
     * Fold one event onto the running instance state. `nowMs` timestamps a
     * join (the log line's own time isn't threaded through yet; the reader can
     * pass the file-tail read time, close enough for a roster). Pure — returns
     * a new immutable state, so the reader can diff old→new for Firestore.
     */
    fun apply(state: InstanceState, event: LogEvent, nowMs: Long): InstanceState = when (event) {
        is LogEvent.Joining -> InstanceState(
            location = event.location,
            worldId = event.worldId,
            instanceId = event.instanceId,
            worldName = null,
            nonce = event.nonce,
            joinedRoom = false,
            // A new instance resets the roster; the local player + others
            // re-announce via OnPlayerJoined once loaded in.
            roster = emptyMap()
        )
        // Real in-instance activity clears `suspended` (see Disconnected): the local
        // player is demonstrably present again.
        is LogEvent.EnteringRoom -> state.copy(worldName = event.worldName, suspended = false)
        LogEvent.JoinedRoom -> state.copy(joinedRoom = true, suspended = false)
        LogEvent.LeftRoom -> InstanceState() // wiped — we're between instances
        LogEvent.Disconnected -> state.copy(suspended = true) // roster RETAINED
        is LogEvent.PlayerJoined -> {
            val key = rosterKey(event.userId, event.displayName)
            // Preserve an existing entry's join time / avatar on a duplicate
            // join line (VRChat can re-log on reconnect). Apply any avatar name that
            // was logged BEFORE this join line (the `Switching`-before-`OnPlayerJoined`
            // ordering) so the avatar/clone button is available from the moment they join.
            val existing = state.roster[key]
            val buffered = state.pendingAvatarByName[event.displayName]
            state.copy(
                suspended = false,
                pendingAvatarByName = if (buffered != null) state.pendingAvatarByName - event.displayName else state.pendingAvatarByName,
                roster = state.roster + (key to RosterEntry(
                    displayName = event.displayName,
                    userId = event.userId,
                    joinedAtMs = existing?.joinedAtMs ?: nowMs,
                    avatarName = existing?.avatarName ?: buffered,
                    avatarCreator = existing?.avatarCreator,
                    platform = existing?.platform ?: ""
                ))
            )
        }
        is LogEvent.PlayerLeft -> {
            val key = rosterKey(event.userId, event.displayName)
            state.copy(suspended = false, roster = state.roster - key)
        }
        is LogEvent.AvatarSwitch -> {
            // Match by display name (avatar lines carry no usr_ id). Only the avatar
            // NAME is logged — the exact id is resolved on demand (see the roster
            // clone button → VrchatAuthManager.resolveWornAvatarId). If the player
            // isn't in the roster yet (VRChat logs the switch BEFORE OnPlayerJoined),
            // buffer the name so it's applied the moment they join.
            val entry = state.roster.entries.firstOrNull { it.value.displayName == event.displayName }
            if (entry == null) state.copy(
                pendingAvatarByName = state.pendingAvatarByName + (event.displayName to event.avatarName)
            )
            else state.copy(
                suspended = false,
                roster = state.roster + (entry.key to entry.value.copy(avatarName = event.avatarName))
            )
        }
        is LogEvent.AvatarUnpack -> {
            // Best-effort author: attach to the entry whose avatarName matches.
            val entry = state.roster.entries.firstOrNull { it.value.avatarName == event.avatarName }
            if (entry == null) state
            else state.copy(
                roster = state.roster + (entry.key to entry.value.copy(avatarCreator = event.author))
            )
        }
    }

    /**
     * Convenience: fold a whole list of raw log lines into a final state.
     * `nowMs` is used for every join timestamp (a batch replay has no per-line
     * time); a live tail should call [parseLine] + [apply] per line instead so
     * join times are real.
     */
    fun replay(lines: List<String>, nowMs: Long, initial: InstanceState = InstanceState()): InstanceState {
        var state = initial
        for (line in lines) {
            val ev = parseLine(line) ?: continue
            state = apply(state, ev, nowMs)
        }
        return state
    }
}
