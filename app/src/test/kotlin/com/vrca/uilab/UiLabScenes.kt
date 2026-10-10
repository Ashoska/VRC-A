package com.vrca.uilab

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.vrca.app.BannedScreen
import com.vrca.app.BootstrapScreen
import com.vrca.app.CrashScreen
import com.vrca.app.TosGate
import com.vrca.app.UpdateDialog
import com.vrca.app.VrcaApp
import com.vrca.nowplaying.NowPlayingSnapshot
import com.vrca.nowplaying.NowPlayingState
import com.vrca.richcontent.RichBlock
import com.vrca.richcontent.RichDoc
import com.vrca.ui.common.VrcaConfirmDialog
import com.vrca.ui.common.VrcaTimeZoneDialog
import com.vrca.ui.onboarding.OnboardingFlow
import com.vrca.ui.onboarding.OnboardingPrefs
import com.vrca.ui.screen.VrcaScreen
import com.vrca.ui.screen.WhatsNewDialog
import com.vrca.update.ReleaseInfo
import com.vrca.vrchat.InAppAlertEvent
import com.vrca.vrchat.InAppAlertState
import com.vrca.vrchat.InstanceRosterManager
import com.vrca.vrchat.VrchatAuthManager
import com.vrca.vrchat.VrchatLoginScreen
import com.vrca.vrchat.VrchatPipelineState
import com.vrca.vrchat.VrchatStatusComponent
import com.vrca.vrchat.VrchatStatusIncident
import com.vrca.vrchat.VrchatStatusPageData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow

/** Ready-made states (`preset`), gated screens (`show`), and generic `set`/`get`. */
object UiLabScenes {

    /** Where short names in `set Object.prop value` are looked up. */
    private val PACKAGES = listOf(
        "com.vrca.vrchat", "com.vrca.ui.common", "com.vrca.ui.screen", "com.vrca.ui.onboarding", "com.vrca.app",
        "com.vrca.discord", "com.vrca.discordbot", "com.vrca.osc", "com.vrca.admin", "com.vrca.nowplaying",
        "com.vrca.richcontent", "com.vrca.update", "com.vrca.keepalive"
    )

    private fun resolveObject(name: String): Any {
        val candidates = if ('.' in name) listOf(name) else PACKAGES.map { "$it.$name" }
        for (c in candidates) {
            val cls = runCatching { Class.forName(c) }.getOrNull() ?: continue
            runCatching { cls.getDeclaredField("INSTANCE").get(null) }.getOrNull()?.let { return it }
        }
        error("no object \"$name\"")
    }

    private fun target(d: UiLabDriver, prop: String): Pair<Any, String> {
        val dot = prop.lastIndexOf('.')
        return if (dot < 0) d.vm to prop else resolveObject(prop.substring(0, dot)) to prop.substring(dot + 1)
    }

    fun set(d: UiLabDriver, prop: String, value: String): String {
        val (owner, name) = target(d, prop)
        return d.setState(owner, name, value)
    }

    fun get(d: UiLabDriver, prop: String): String {
        val (owner, name) = target(d, prop)
        return "$prop = ${d.getState(owner, name)}"
    }

    /** Write [value] into a ViewModel MutableState (`<name>$delegate`) of any type: the
     *  string `set` can't express enums-from-null or lists. */
    private fun setObj(owner: Any, name: String, value: Any?) {
        var c: Class<*>? = owner.javaClass
        while (c != null) {
            c.declaredFields.firstOrNull { it.name == "$name\$delegate" }?.let { f ->
                f.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                (f.get(owner) as androidx.compose.runtime.MutableState<Any?>).value = value
                return
            }
            c = c.superclass
        }
        error("no state $name")
    }

    /** A voice pack + the voice detector "installed" as SpeechPacks.isInstalled sees it: every
     *  file at its exact size (sparse) and the .ok marker; [packId] selected for [lang]. */
    private fun installVoicePack(ctx: android.content.Context, lang: String, packId: String) {
        listOf(packId, com.vrca.speech.SpeechCatalog.VAD.id).forEach { id ->
            val pack = com.vrca.speech.SpeechCatalog.pack(id) ?: error("no pack $id")
            val dir = com.vrca.speech.SpeechPacks.packDir(ctx, id).apply { mkdirs() }
            pack.files.forEach { f -> java.io.RandomAccessFile(java.io.File(dir, f.name), "rw").use { it.setLength(f.size) } }
            java.io.File(dir, ".ok").writeText("ui-lab")
        }
        com.vrca.speech.SpeechPacks.setSelected(ctx, lang, packId)
    }

    // ------------------------------------------------------------------ presets

    val PRESETS = listOf(
        "in-world", "offline", "friends", "incident", "outage-minor", "status-ok", "alerts", "no-alerts",
        "sending", "idle", "warned", "banned", "auth-dead", "logged-out", "nowplaying", "paused", "ad",
        "roster", "roster-empty", "manual", "owner", "osc-params",
        "voice", "voice-listening", "voice-hearing", "voice-loading", "voice-stopping",
        "voice-paused", "voice-trigger", "voice-learning", "voice-teach-loading", "voice-downloading"
    )

    fun preset(d: UiLabDriver, name: String): String {
        val ctx = d.app
        when (name) {
            "in-world" -> {
                VrchatPipelineState.presence = presence(ctx, inWorld = true)
                VrchatPipelineState.isConnected = true
                VrchatPipelineState.friendsOnline = 7 to 42
            }
            "offline" -> { VrchatPipelineState.presence = presence(ctx, inWorld = false); VrchatPipelineState.isConnected = true }
            "friends" -> VrchatPipelineState.friendsOnline = 7 to 42
            "incident" -> VrchatPipelineState.statusPageState = VrchatStatusPageData(
                indicator = "major", description = "Partial System Outage",
                components = listOf(
                    VrchatStatusComponent("Authentication / Login", "major_outage"),
                    VrchatStatusComponent("Social / Friends List", "degraded_performance"),
                    VrchatStatusComponent("API Latency", "partial_outage")
                ),
                incidents = listOf(
                    VrchatStatusIncident("Login issues", "investigating", "major",
                        "We're aware some users can't log in and are investigating."),
                    VrchatStatusIncident("Slow friends list", "identified", "minor",
                        "A fix is being rolled out for delayed friend updates.")
                )
            )
            "outage-minor" -> VrchatPipelineState.statusPageState = VrchatStatusPageData(
                "minor", "Minor Service Outage",
                listOf(VrchatStatusComponent("Image Uploads", "degraded_performance")),
                listOf(VrchatStatusIncident("Slow image uploads", "monitoring", "minor", "Uploads are recovering."))
            )
            "status-ok" -> VrchatPipelineState.statusPageState = null
            "alerts" -> addSampleAlerts(ctx)
            "no-alerts" -> InAppAlertState.dismissAll(ctx)
            "sending" -> {
                d.vm.setAfkEnabledFlag(true); d.vm.setCycleEnabledFlag(true); d.vm.updateTimeEnabled(true)
                d.vm.startSending()
            }
            "idle" -> d.vm.stopSending()
            "warned" -> { d.setState(d.vm, "warned", "true"); d.setState(d.vm, "warnReason", "Spamming the chatbox in public worlds.") }
            "banned" -> { d.setState(d.vm, "uidBanned", "true"); d.setState(d.vm, "banReason", "Repeated harassment after warnings.") }
            "auth-dead" -> { d.setState(d.vm, "vrchatAuthDead", "true"); VrchatPipelineState.authDead = true }
            "logged-out" -> { d.setState(d.vm, "vrchatLoggedOut", "true") }
            "nowplaying", "paused", "ad" -> {
                d.vm.setSpotifyEnabledFlag(true)
                val now = android.os.SystemClock.elapsedRealtime()
                NowPlayingState.update(NowPlayingSnapshot(
                    listenerConnected = true, activePackage = "com.spotify.music", detected = true,
                    title = if (name == "ad") "Advertisement" else "Midnight City",
                    artist = if (name == "ad") "" else "M83",
                    durationMs = if (name == "ad") 30_000 else 243_000, positionMs = if (name == "ad") 12_000 else 97_000,
                    positionUpdateTimeMs = now, playbackSpeed = if (name == "paused") 0f else 1f,
                    isPlaying = name != "paused"
                ))
            }
            "roster", "roster-empty" -> {
                stopRosterManager()
                val members = if (name == "roster-empty") emptyList() else sampleRoster()
                rosterFlow().value = InstanceRosterManager.RosterUi(
                    status = if (name == "roster-empty") InstanceRosterManager.Status.IDLE else InstanceRosterManager.Status.LIVE,
                    worldName = if (name == "roster-empty") null else "The Black Cat",
                    location = if (name == "roster-empty") null else "wrld_4cf554b4-430c-4f8f-b53e-1f294eed230b:12345~public",
                    members = members
                )
            }
            "owner" -> com.vrca.admin.AdminLabAccess.forceOwner = true
            // VRChat avatar params as OSCQuery would report them (dictation Listen trigger),
            // re-fed every second so they stay "present" (VrcaOscState ages them out).
            // Voice to text (headset). "voice" = a pack installed: stand-in files of the exact
            // sizes SpeechPacks checks (sparse, so instant and no disk use), so the app's own
            // install check passes. The others add a state on top (the engine itself can't
            // run on the JVM: its native libs are Android-only). Expand Manual Send to see it.
            "voice", "voice-listening", "voice-hearing", "voice-loading", "voice-stopping",
            "voice-paused", "voice-trigger", "voice-learning", "voice-teach-loading", "voice-downloading" -> {
                installVoicePack(ctx, "en", "kroko-en")
                val vm = d.vm
                setObj(vm, "speechLanguage", "en"); setObj(vm, "speechPackId", "kroko-en")
                setObj(vm, "speechInstalledPacks", setOf("kroko-en", com.vrca.speech.SpeechCatalog.VAD.id))
                setObj(vm, "speechModelReady", true)
                vm.refreshSpeechModelReady()
                // Start from a clean slate so voice presets can follow each other in one run.
                listOf("speechListening", "speechHearing", "speechLoading", "speechStopping", "speechVoicePaused",
                    "speechPaused", "speechTriggerMissing").forEach { setObj(vm, it, false) }
                setObj(vm, "speechListenParam", null); setObj(vm, "speechLearning", null)
                setObj(vm, "speechLearnHeard", emptyList<String>()); setObj(vm, "speechDownloadingLang", null)
                val listening = name !in setOf("voice", "voice-stopping", "voice-downloading")
                if (listening) setObj(vm, "speechListening", true)
                when (name) {
                    "voice-hearing" -> setObj(vm, "speechHearing", true)
                    "voice-loading" -> setObj(vm, "speechLoading", true)
                    "voice-stopping" -> setObj(vm, "speechStopping", true)
                    "voice-paused" -> setObj(vm, "speechVoicePaused", true)
                    "voice-trigger" -> {
                        setObj(vm, "speechListenParam", "MuteSelf"); setObj(vm, "speechListenWhenOn", true)
                        setObj(vm, "speechPaused", true)
                    }
                    "voice-teach-loading" -> {
                        setObj(vm, "speechLearning", com.vrca.speech.VoiceCommand.CLEAR); setObj(vm, "speechLoading", true)
                    }
                    "voice-learning" -> {
                        setObj(vm, "speechLearning", com.vrca.speech.VoiceCommand.PAUSE)
                        setObj(vm, "speechLearnHeard", listOf("Pause.", "Paws."))
                    }
                    "voice-downloading" -> { setObj(vm, "speechDownloadingLang", "en"); setObj(vm, "speechDownloadPct", 42) }
                }
            }
            "osc-params" -> Thread {
                repeat(600) {
                    listOf("MuteSelf" to true, "Earmuffs" to false, "AFK" to false, "STT" to true, "Grounded" to true)
                        .forEach { (k, v) -> com.vrca.osc.VrcaOscState.onParam(k, v) }
                    Thread.sleep(1000)
                }
            }.apply { isDaemon = true }.start()
            "manual" -> d.vm.onMessageTextChange(androidx.compose.ui.text.input.TextFieldValue("be right back, grabbing food"))
            else -> error("unknown preset \"$name\". Presets: ${PRESETS.joinToString()}")
        }
        return "preset $name"
    }

    private fun presence(ctx: android.content.Context, inWorld: Boolean): VrchatAuthManager.VrcUserPresence {
        val cur = VrchatPipelineState.presence
        return VrchatAuthManager.VrcUserPresence(
            userId = cur?.userId ?: VrchatAuthManager.getStoredUserId(ctx) ?: "usr_lab",
            displayName = cur?.displayName ?: VrchatAuthManager.getStoredDisplayName(ctx) ?: "Robo-Gremlin",
            state = if (inWorld) "online" else "offline",
            status = if (inWorld) "active" else "offline",
            statusDescription = if (inWorld) "stars :0" else "",
            location = if (inWorld) "wrld_4cf554b4-430c-4f8f-b53e-1f294eed230b:12345~hidden(usr_x)~region(eu)" else "offline",
            platform = if (inWorld) "android" else "",
            worldName = if (inWorld) "The Black Cat" else "",
            instancePlayerCount = if (inWorld) 18 else 0,
            instanceCapacity = if (inWorld) 32 else 0,
            currentAvatarThumbnailUrl = "",
            isOnlineInVRChat = inWorld,
            trustRank = "system_trust_veteran",
        )
    }

    private fun addSampleAlerts(ctx: android.content.Context) {
        val now = System.currentTimeMillis()
        val h = 3_600_000L
        InAppAlertState.addGroupedEvent(ctx, "bio_usr_lab1", "Kiwi updated bio", "https://vrchat.com/home/user/usr_lab1",
            InAppAlertEvent(id = "a1", body = "Kiwi updated their bio", timestampMs = now - 5 * 60_000,
                beforeText = "hi i'm kiwi\nquest user\ndon't touch my tail",
                afterText = "hi i'm kiwi\npc user now!!\ndon't touch my tail\nfriend me"))
        InAppAlertState.addGroupedEvent(ctx, "friend_usr_lab2", "Friend request", "https://vrchat.com/home/user/usr_lab2",
            InAppAlertEvent(id = "a2", body = "Nova sent you a friend request", timestampMs = now - 20 * 60_000))
        InAppAlertState.addGroupedEvent(ctx, "worldinvite_usr_lab3", "Invite from Echo", null,
            InAppAlertEvent(id = "a3", body = "Echo invited you to The Great Pug", timestampMs = now - 2 * 60_000,
                actionType = "invite_me", actionData = "wrld_6caf5200-70e1-46c2-b043-e3c4abe69e0f:9876~friends(usr_x)"))
        InAppAlertState.addGroupedEvent(ctx, "event_grp_lab", "Event from Night Owls", "https://vrchat.com/home/group/grp_lab",
            InAppAlertEvent(id = "a4", body = "Weekly chill hangout with music and games.\nEveryone welcome!",
                eventTitle = "Friday Night Hangout", timestampMs = now + 2 * h, startsAtMs = now + 2 * h, endsAtMs = now + 5 * h,
                createdAtMs = now - 48 * h, interestedCount = 23, category = "hangout", platforms = "standalonewindows,android",
                accessType = "group", languages = "eng,jpn", following = true, recurring = true,
                groupRefId = "grp_lab", eventRefId = "cal_lab1", seriesId = "s_lab"))
        InAppAlertState.addGroupedEvent(ctx, "announcement_grp_lab2", "Announcement from Pixel Club", "https://vrchat.com/home/group/grp_lab2",
            InAppAlertEvent(id = "a5", body = "New world is live! Come check out the lobby and tell us what you think: https://vrchat.com/home/world/wrld_x",
                eventTitle = "New world released", timestampMs = now - 3 * h, groupRefId = "grp_lab2"))
        InAppAlertState.addGroupedEvent(ctx, "rank_usr_lab4", "Rank change", null,
            InAppAlertEvent(id = "a6", body = "Mochi is now a Trusted User", timestampMs = now - 26 * h))
        InAppAlertState.addGroupedEvent(ctx, "unfriend_usr_lab5", "Friend removed", null,
            InAppAlertEvent(id = "a7", body = "Blip is no longer on your friends list", timestampMs = now - 30 * h))
    }

    @Suppress("UNCHECKED_CAST")
    private fun rosterFlow(): MutableStateFlow<InstanceRosterManager.RosterUi> =
        InstanceRosterManager.javaClass.getDeclaredField("_flow").apply { isAccessible = true }
            .get(InstanceRosterManager) as MutableStateFlow<InstanceRosterManager.RosterUi>

    /** The real log reader would overwrite a fake roster within a second. */
    private fun stopRosterManager() {
        runCatching {
            (InstanceRosterManager.javaClass.getDeclaredField("scope").apply { isAccessible = true }
                .get(InstanceRosterManager) as CoroutineScope).cancel()
        }
    }

    private fun sampleRoster(): List<InstanceRosterManager.Member> {
        fun m(name: String, plat: String, trust: String, friend: Boolean = false, self: Boolean = false,
              status: String = "active", desc: String = "", avatar: String? = "Kitsune", avatarId: String? = "avtr_x") =
            InstanceRosterManager.Member(
                displayName = name, userId = "usr_" + name.lowercase(), platform = plat, avatarName = avatar,
                avatarId = avatarId, isFriend = friend, isSelf = self, status = status, statusDescription = desc,
                trustRank = trust
            )
        return listOf(
            m("Robo-Gremlin", "Quest", "system_trust_veteran", self = true, desc = "stars :0"),
            m("Kiwi", "PC", "system_trust_trusted", friend = true, status = "join me", desc = "come hang"),
            m("Nova", "Quest", "system_trust_known", friend = true, status = "ask me"),
            m("Echo", "PC", "system_trust_veteran", status = "busy", desc = "recording", avatarId = ""),
            m("Mochi", "iOS", "system_trust_basic", avatarId = null),
            m("Blip", "Quest", "", status = "active"),
            m("Sage the Wise Old Owl of the Forest", "PC", "system_trust_legend", desc = "a very long status message that should wrap or cut"),
        )
    }

    // ------------------------------------------------------------------- shows

    /** Full-screen shows replace the base; the rest are drawn over it. */
    private val FULL = setOf("boot", "crash", "banned", "tos", "onboarding", "login")
    val SHOWS = listOf("update", "update-optional", "whatsnew", "confirm", "confirm-destructive", "timezone") + FULL

    fun show(d: UiLabDriver, arg: String): String {
        val name = arg.substringBefore(' ')
        require(name in SHOWS) { "unknown show \"$name\". Shows: ${SHOWS.joinToString()}" }
        if (name == "onboarding") {
            val step = arg.substringAfter(' ', "0").trim().toIntOrNull() ?: 0
            OnboardingPrefs.saveStep(d.app, step)
        }
        if (name in FULL) { d.overlay.value = null; d.base.value = "show:$arg" } else d.overlay.value = arg
        return "show $arg"
    }

    private val SAMPLE_NOTES = RichDoc(v = 1, blocks = listOf(
        RichBlock.Heading("What's new"),
        RichBlock.Bullets(listOf("**Instance roster** shows platform + trust badges", "Faster avatar cloning", "Fixed the chatbox cutting off long titles")),
        RichBlock.Callout("warn", "Re-open the app once after updating."),
        RichBlock.Text("Thanks for testing! *More soon.*")
    )).toJson()

    @Composable
    fun Root(d: UiLabDriver) {
        Box {
            val base = d.base.value
            when {
                base == "app" -> VrcaApp()
                base.startsWith("show:") -> FullShow(d, base.removePrefix("show:"))
                else -> VrcaScreen(chatboxViewModel = d.vm, discordInvite = "https://discord.gg/example")
            }
            d.overlay.value?.let { Overlay(d, it) }
        }
    }

    @Composable
    private fun FullShow(d: UiLabDriver, arg: String) {
        val name = arg.substringBefore(' ')
        val rest = arg.substringAfter(' ', "")
        when (name) {
            "boot" -> BootstrapScreen(
                working = rest != "error", error = if (rest == "error") "Couldn't reach the server. Check your connection." else null,
                onRetry = {}, phase = if (rest == "2") 2 else 1, accountChecked = rest == "done"
            )
            "crash" -> CrashScreen(
                crashText = "java.lang.IllegalStateException: example crash\n\tat com.vrca.ui.screen.HomePage(HomePage.kt:212)\n\tat androidx.compose.runtime.Composer.invoke(Composer.kt:1)",
                onClear = {}, onContinue = {}
            )
            "banned" -> BannedScreen(reason = rest.ifBlank { "Repeated harassment after warnings." })
            "tos" -> TosGate(3, SAMPLE_TOS, "https://example.com/tos", onOpenUrl = {}, onAccept = {})
            "onboarding" -> OnboardingFlow(
                vm = d.vm, tosVersion = 3, tosText = SAMPLE_TOS, tosUrl = "https://example.com/tos",
                tosAlreadyAccepted = true, phase1BanId = null, replay = false, onTosAccepted = {}, onFinish = {}
            )
            "login" -> VrchatLoginScreen(pendingBanId = null, onCancel = if (rest == "nocancel") null else ({})) { _, _ -> }
        }
    }

    @Composable
    private fun Overlay(d: UiLabDriver, arg: String) {
        val name = arg.substringBefore(' ')
        when (name) {
            "update", "update-optional" -> UpdateDialog(
                info = ReleaseInfo(480, "v1.4.80", "https://example.com/vrc-a.apk", 0, "", SAMPLE_NOTES),
                forced = name == "update", onDismiss = { d.overlay.value = null }, onDownload = {}
            )
            "whatsnew" -> {
                LaunchedEffect(Unit) {
                    com.vrca.richcontent.WhatsNewStore.cache(d.app, com.vrca.BuildConfig.VERSION_CODE.toLong(), "v1.4.80", SAMPLE_NOTES, "")
                }
                WhatsNewDialog(onDismiss = { d.overlay.value = null })
            }
            "confirm", "confirm-destructive" -> VrcaConfirmDialog(
                title = if (name == "confirm") "Save changes?" else "Sign out of VRChat?",
                body = if (name == "confirm") "Your preset will be updated." else "Chatbox sending stops until you sign back in.",
                confirmLabel = if (name == "confirm") "Save" else "Sign out", onConfirm = {}, onDismiss = { d.overlay.value = null },
                destructive = name != "confirm"
            )
            "timezone" -> VrcaTimeZoneDialog(currentMode = "Europe/Paris", use24h = false, onSelect = {}, onDismiss = { d.overlay.value = null })
        }
    }

    private const val SAMPLE_TOS = """1. Acceptance
By using VRC-A you agree to these terms.

2. Acceptable Use
* Don't spam the chatbox in public worlds
* Don't use VRC-A to harass anyone

3. Risk
VRC-A talks to VRChat's API. Use it at your own risk.

4. Moderation
Accounts that break these rules can be warned or banned."""
}
