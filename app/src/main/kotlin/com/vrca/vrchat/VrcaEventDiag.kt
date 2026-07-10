package com.vrca.vrchat

/**
 * Process-lifetime diagnostics for the group event / announcement / calendar
 * plumbing, surfaced read-only in Settings -> Debug. VRChat's calendar API isn't
 * publicly documented, so several field/endpoint names in [GroupAlertEnricher] /
 * [VrchatAuthManager] are INFERRED (organizer id/name, the per-user follow flag,
 * the Add/Remove-from-Calendar endpoint). This holder captures the RAW payloads
 * and the raw follow-call request/response so the real names can be read off a
 * device instead of guessed — the user copies these back and the probes get
 * corrected to the actual fields.
 *
 * Everything here is display-only debug state; nothing drives behavior.
 */
object VrcaEventDiag {
    // Raw JSON of the most recently fetched SINGLE calendar event
    // (GET /calendar/{group}/{event}) — the richest object, carrying whatever
    // field VRChat actually uses for the organizer and the follow state.
    @Volatile var lastCalendarEventJson: String? = null
        private set

    // Raw JSON of the most recently fetched group POST (announcement) — carries
    // whatever field VRChat uses for the author.
    @Volatile var lastPostJson: String? = null
        private set

    // The last Add/Remove-from-Calendar follow call: exact URL, request body,
    // HTTP code, and raw response body.
    @Volatile var lastFollowCall: String? = null
        private set

    // What the organizer probes actually resolved to on the last event, so the
    // user can see whether it found anything without reading the whole JSON.
    @Volatile var lastOrganizerResolved: String? = null
        private set

    private fun clip(s: String?, max: Int = 4000): String? =
        if (s == null) null else if (s.length <= max) s else s.take(max) + "…(${s.length} chars)"

    fun recordCalendarEvent(json: String?) { lastCalendarEventJson = clip(json) }
    fun recordPost(json: String?) { lastPostJson = clip(json) }
    fun recordOrganizer(id: String?, name: String?) {
        lastOrganizerResolved = "id=${id ?: "(none)"}  name=${name ?: "(none)"}"
    }
    fun recordFollowCall(url: String, body: String, code: Int, response: String) {
        lastFollowCall = "POST $url\nbody: $body\nHTTP $code\nresp: ${clip(response, 1200)}"
    }
}
