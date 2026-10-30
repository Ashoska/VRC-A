# VRChat OSC on Quest: what's there (research notes)

Contents: hand drag and vertical research (top), feature ideas, then the **full endpoint reference**, the built-in avatar parameters, **how VRC-A connects to VRChat** (polling, sending, the bridge) and new ideas from the endpoint list.

Source: the headset debug "Dump all endpoints" (`VrcaOscQuery.dumpTree()`), run on the user's Quest 3 in VRChat on 2026-10-25. VRChat listed **237 endpoints** (`VRChat-Client-XXXX`; HOST_INFO `OSC_IP 127.0.0.1`, `OSC_PORT 9000`, UDP). Below is what was found and what each could be used for. Kept for future features; nothing here is built yet unless it says so.

## Hand-drag locomotion (in progress)
- **`/tracking/vrsystem/{head,leftwrist,rightwrist}/pose`** (`ffffff`: x y z meters + pitch yaw roll degrees): polling reads `[null]`, but VRChat PUSHES them (~75/s each) to an app that announces itself over OSCQuery (device-proven 2026-10-25, VRChat toast "Sending OSC data to VRCA-…"). Needs "Allow Sending Head and Wrist VR Tracking OSC Data" (Tracking & IK). Positions are raw VR tracking space (locomotion does not move them). Oddity: the wrist rotations read as the head rotation with a fixed ±90° roll. The same push also delivers **every avatar parameter** (`/avatar/parameters/*`), which on Quest were never sent over UDP before; the app could move off 250 ms polling to this. Besides poses and params, VRChat pushes `/avatar/change`, `/avatar/name`, `/avatar/eyeheight`, `/avatar/eyeheightmin`/`max`, `/avatar/eyeheightscalingallowed`, `/avatar/avatarfullfacetracking` (sent on connect and on change). VRChat connects to the first VRC-A per VRChat session but ignores any returning app after a goodbye until VRChat restarts (device-tested), and once our receive socket closes (even briefly, any name, no goodbye, multicast) VRChat stops sending until it restarts, so the headset build keeps the connection in a separate `:oscbridge` process that outlives the main app (`OscBridge`, `OscBridgeService`, `MdnsAnnouncer`; the main app reads it through `OscAnnouncer`). Code: `OscAnnouncer`, `OscBridge`, `HandDrag` (prototype).
- **`/input/ToggleSitStand`** (bool, write, undocumented): sent as bool false/true/false it did nothing on standalone Quest (device-tested 2026-10-25). Retest as int 0/1/0 via the headset debug "Vertical input tests" (`VerticalTests`).
- **`/input/LookVertical`** (float, write; VRChat wiki: player rotation, +1 up / -1 down): untested in VR; "Vertical input tests" holds it ±1 for 1 s.
- **VRChat's own height offset:** VRChat has a VR-only "Horizon Adjust" with "Set Standing Height" (sets the current head height as full standing height) and seated mode, so VRChat applies its own play-space height offset internally (a 2024 open-beta bug report describes the viewport resetting vertically around it, tied to "Temporary Pose Space"). Not in the OSC endpoint list; only reachable from VRChat's menu as far as known.
- Movement inputs used for horizontal drag: `/input/Vertical`, `/input/Horizontal` (floats -1..1), `/input/Run`, `/input/Jump`.
- No OSC input moves the player up/down or moves the play space. The **system floor height** does (user-tested: floor set below the feet = float, above = crouch, live in VRChat) but no setting or property an app can read holds it (floor probe, 2026-10-25): it belongs to Meta's native `trackingservice` (`ro.vendor.oculus.trackingservice.instance=/odm/bin/trackingservice`). Also seen: `persist.device_config.oculus_shared_os_services_sessionless.oculus_compositor_space_manager=false`, `config.disable_smartspacemanagerservice=true`.
- Not usable: OSC trackers `/tracking/trackers/{1-8,head}/{position,rotation}` only pose the avatar body (the head pair aligns the tracker space); avatar add-ons are out (Quest-only users can't upload avatars); OpenXR layers need a modified VRChat APK.

## Vertical drag: research handoff (open)
Status: horizontal drag works over OSC (`HandDrag`); vertical is unsolved. Everything known so far, for whoever picks it up:
- **What moves the player vertically on standalone Quest:** only the **system floor height**. User-tested 2026-10-25: lowering the floor in Quick Settings (Boundary, Set floor level) below the feet makes the player float in VRChat, raising it makes them crouch, live, no VRChat restart. Hitbox and view both move.
- **What does not:** every OSC endpoint VRChat lists (237, dump 2026-10-25). No input moves the view or play space up/down; `/tracking/trackers/*` only pose the avatar body; `/usercamera/Flying` flies the photo camera; `/input/ToggleSitStand` did nothing on Quest; `/avatar/eyeheight` only resizes (rejected by the user). Avatar add-ons are out (Quest-only users can't upload avatars). OpenXR API layers only load from inside the target app's own APK (needs a modified VRChat client). OpenXR only lets apps read the floor (`LOCAL_FLOOR`, `xrGetReferenceSpaceBoundsRect`); Meta's boundary extension only hides/shows the boundary; Meta's forums report no app API to set the floor or open boundary setup.
- **Where the floor lives:** not in any `Settings.System`/`Secure`/`Global` key and not in any system property an app can read (floor probe). Readable properties point at Meta's native tracking daemon: `ro.vendor.oculus.trackingservice.instance=/odm/bin/trackingservice`, with `init.svc.trackingservice` and `init.svc.trackingfidelityservice` running. Also seen: feature flag `persist.device_config.oculus_shared_os_services_sessionless.oculus_compositor_space_manager=false`, `config.disable_smartspacemanagerservice=true`.
- **Open questions:** which system service stores the floor offset; whether Meta exposes any supported way for another app to change it; whether a change can be made smoothly enough (many times a second, without the boundary flashing) to follow a hand drag; how this behaves after reboot and across boundary re-setup.
- **Constraints set by the user:** no PC, no root, no avatar edits; works for Quest-only users.
- **ADB shell tests (built, not yet run on device):** the headset debug "ADB shell tests" (`AdbShell`, `AdbProbes`) pair with the Quest's own Wireless debugging (no PC) and run the floor hunt with `shell` rights: service/dumpsys/cmd lists, full getprop + settings, processes, Meta packages, matching services' dumpsys, logcat. Next step: "Floor test: Before", change the floor in Quick Settings, "Floor test: After"; the diff should name the service (and maybe a setting or property) that holds the floor offset. Research only: a shell-rights step can't be a Quest-only user feature unless every user turns on Developer mode + Wireless debugging.

## Ideas for other features
- **Camera remote** (`/usercamera/*`, ~40 endpoints, mostly read/write): `Capture`, `CaptureDelayed`, `Mode`, `Zoom`, `Exposure`, `Aperture`, `FocalDistance`, `GreenScreen` + `Hue`/`Saturation`/`Lightness`, `Streaming`, `Flying`/`FlySpeed`, `SmoothMovement`/`SmoothingStrength`, `LookAtMe` (+ offsets), `LocalPlayer`/`RemotePlayer`, `TriggerTakesPhotos`, `PhotoRate`, `Duration`, `ShowUIInCamera`, `Lock`, `Close`, `Pose` (`ffffff`, read: camera position). A VRC-A panel could run VRChat's camera.
- **Camera dolly paths** (`/dolly/Play`, `/dolly/PlayDelayed`, `/dolly/Import`, `/dolly/Export`, `/dolly/ExportLocal`): save, load and play dolly paths.
- **World size limits** (`/avatar/eyeheightmin`, `/avatar/eyeheightmax`, `/avatar/eyeheightscalingallowed`, read): BUILT. The size tool shows them as the world's *recommended* range (not a clamp: OSC still sets 0.01-10,000 m unless the world enforces) and hides its controls when scaling is off.
- **Current avatar** (`/avatar/name`, `/avatar/change`, read): BUILT as the `{avatar}` chatbox token + `VrcaOscState.avatarNameFlow`/`avatarIdFlow`.
- **Debug overlays** (`/input/ShowDebugInfo0`-`9`): toggles for VRChat's debug menus (2 = build + FPS, 3 = output log).
- Also listed: `/input/AFKToggle`, `/input/Voice`, `/chatbox/input` (`sTT`), `/chatbox/typing`, `/avatar/avatarfullfacetracking`. Eye/face tracking input (`/tracking/eye/*`, `/tracking/face/v1`) is not useful (Quest Pro only).

## Full endpoint reference (device dump)

Every endpoint VRChat's OSCQuery server listed on the user's Quest 3 (dump 2026-10-25 21:54, `VRChat-Client-574826`, "Dump all endpoints"). The dump said **237 endpoints**: the 120 below, their container nodes (`/avatar`, `/input`, `/tracking/trackers/1`…, not listed), and the current avatar's **94 parameters** under `/avatar/parameters/*` (only counted; they change per avatar). Type letters are OSC tags (`T` bool, `f` float, `i` int, `s` string, `b` blob; several letters = several values, e.g. `ffffff` = x y z pitch yaw roll). Access: `r` = we can read the value by polling, `w` = we can send to it, `rw` = both. Values are what they were at the time of the dump. Descriptions are VRChat's own text from the dump; *(no description)* where VRChat gave none. Polling reads `[null]` for the three `/tracking/vrsystem/*/pose` nodes; their values only arrive by push (see the bridge section).

### `/avatar` (7)

| Endpoint | Type | Access | Value then | VRChat's description |
|---|---|---|---|---|
| `/avatar/avatarfullfacetracking` | `T` | r | [false] | Whether the current avatar has hardware face tracking enabled (face tracking with all tracking keys). |
| `/avatar/change` | `s` | rw | ["avtr_776bee88-5ee1-4a48-ae85-87ee9524fd29"] | Avatar ID, updated whenever the user switches into a valid avatar. |
| `/avatar/eyeheight` | `f` | rw | [0.752500057] | The current eye height of the avatar in meters. |
| `/avatar/eyeheightmax` | `f` | r | [5] | The maximum eye height the current world recommends. |
| `/avatar/eyeheightmin` | `f` | r | [0.2] | The minimum eye height the current world recommends. |
| `/avatar/eyeheightscalingallowed` | `T` | r | [true] | Whether the current world allows avatar scaling. |
| `/avatar/name` | `s` | r | ["Ashoska's Kipfel"] | Avatar name, updated whenever the user switches into a valid avatar. |

### `/chatbox` (2)

| Endpoint | Type | Access | Value then | VRChat's description |
|---|---|---|---|---|
| `/chatbox/input` | `sTT` | w | - | *(no description)* |
| `/chatbox/typing` | `s` | w | - | *(no description)* |

### `/dolly` (5)

| Endpoint | Type | Access | Value then | VRChat's description |
|---|---|---|---|---|
| `/dolly/Export` | `s` | w | [null] | *(no description)* |
| `/dolly/ExportLocal` | `s` | w | [null] | *(no description)* |
| `/dolly/Import` | `s` | w | [null] | *(no description)* |
| `/dolly/Play` | `T` | rw | [false] | *(no description)* |
| `/dolly/PlayDelayed` | `i` | w | [0] | *(no description)* |

### `/input` (39)

| Endpoint | Type | Access | Value then | VRChat's description |
|---|---|---|---|---|
| `/input/AFKToggle` | `T` | w | [false] | Toggles AFK mode on and off. Only has an effect when changing from 0 to 1. |
| `/input/ComfortLeft` | `T` | w | [false] | Spins you instantly to the left. Only has an effect in VR. |
| `/input/ComfortRight` | `T` | w | [false] | Spins you instantly to the right. Only has an effect in VR. |
| `/input/DropLeft` | `T` | w | [false] | Drops the item in your left hand. |
| `/input/DropRight` | `T` | w | [false] | Drops the item in your right hand. |
| `/input/GrabLeft` | `T` | w | [false] | Grabs the item near your left hand. |
| `/input/GrabRight` | `T` | w | [false] | Grabs the item near your right hand. |
| `/input/Horizontal` | `f` | w | [0] | Moves you right for values above 0, and left for values below 0. |
| `/input/Jump` | `T` | w | [false] | Jumps when the input changes to 1. Only has an effect when changing from 0 to 1. |
| `/input/LookHorizontal` | `f` | w | [0] | Looks to the right for values above 0, and to the left for values below 0. |
| `/input/LookLeft` | `T` | w | [false] | Looks to the left while the input equals 1.. |
| `/input/LookRight` | `T` | w | [false] | Looks to the right while the input equals 1.. |
| `/input/LookVertical` | `f` | w | [0] | Looks up for values above 0, and down for values below 0. |
| `/input/MoveBackward` | `T` | w | [false] | Moves you backward while the input equals 1.. |
| `/input/MoveForward` | `T` | w | [false] | Moves you forward while the input equals 1.. |
| `/input/MoveLeft` | `T` | w | [false] | Moves you Left while the input equals 1.. |
| `/input/MoveRight` | `T` | w | [false] | Moves you Right while the input equals 1.. |
| `/input/PanicButton` | `T` | w | [false] | Turns on Panic mode for your safety. |
| `/input/QuickMenuToggleLeft` | `T` | w | [false] | Toggles the state of the QuickMenu attached to your left hand. Only has an effect when changing from 0 to 1. |
| `/input/QuickMenuToggleRight` | `T` | w | [false] | Toggles the state of the QuickMenu attached to your left hand. Only has an effect when changing from 0 to 1. |
| `/input/Run` | `T` | w | [false] | Applies run speed while the input equals 1. |
| `/input/ShowDebugInfo0` | `T` | w | [false] | Displays some debugging visualizations for VRC_UiShapes (interactable UIs). Only accessible to the world creator unless they have turned on 'World Debugging' via the website. |
| `/input/ShowDebugInfo1` | `T` | w | [false] | Displays information about loaded asset bundles. It is mostly irrelevant to world development. |
| `/input/ShowDebugInfo2` | `T` | w | [false] | Displays the current build of VRChat that you are using, along with your FPS. |
| `/input/ShowDebugInfo3` | `T` | w | [false] | Displays your output log. |
| `/input/ShowDebugInfo4` | `T` | w | [false] | Displays various stats about other users. VR?: Whether or not the user is in VR. More info at https://docs.vrchat.com/docs/world-debug-views#debug-menu-4 |
| `/input/ShowDebugInfo5` | `T` | w | [false] | Displays some graphs related to networking. They are unlabeled and mostly not useful. |
| `/input/ShowDebugInfo6` | `T` | w | [false] | Displays all the networked objects in your world, along with various stats. Only accessible to the world creator unless they have turned on 'World Debugging' via the website. |
| `/input/ShowDebugInfo7` | `T` | w | [false] | Overlays visual representations of every PhysBone and Contact in the world near the local player. Only accessible to the world creator unless they have turned on 'World Debugging' via the website. |
| `/input/ShowDebugInfo8` | `T` | w | [false] | Overlays a panel on top of every synced object in the world. Each panel displays various stats about that specific object. Only accessible to the world creator unless they have turned on 'World Debugging' via the website. More info at https://docs.vrchat.com/docs/world-debug-views#debug-menu-8 |
| `/input/ShowDebugInfo9` | `T` | w | [false] | Displays debug stats for every player in the instance at their feet. Only accessible to the world creator unless they have turned on 'World Debugging' via the website. |
| `/input/SpinHoldCwCcw` | `f` | w | [0] | Spins the held item Clockwise for values above 0, and Counter-Clockwise for values below 0. |
| `/input/SpinHoldLR` | `f` | w | [0] | Spins the held item Left for values above 0, and Right for values below 0. |
| `/input/SpinHoldUD` | `f` | w | [0] | Spins the held item Up for values above 0, and Down for values below 0. |
| `/input/ToggleSitStand` | `T` | w | [false] | Toggles between Sitting and Standing modes. Only has an effect in VR. |
| `/input/UseLeft` | `T` | w | [false] | Uses the item in your left hand. |
| `/input/UseRight` | `T` | w | [false] | Uses the item in your right hand. |
| `/input/Vertical` | `f` | w | [0] | Moves you forward for values above 0, and backwards for values below 0. |
| `/input/Voice` | `T` | w | [false] | In 'Toggle Mic' mode, Toggles your Mic when changing the input from 0 to 1. In Push-To-Talk Mode, 0 = Muted and 1 = Unmuted. |

### `/tracking` (29)

| Endpoint | Type | Access | Value then | VRChat's description |
|---|---|---|---|---|
| `/tracking/eye/CenterPitchYaw` | `ff` | w | - | *(no description)* |
| `/tracking/eye/CenterPitchYawDist` | `fff` | w | - | *(no description)* |
| `/tracking/eye/CenterVec` | `fff` | w | - | *(no description)* |
| `/tracking/eye/CenterVecFull` | `fff` | w | - | *(no description)* |
| `/tracking/eye/EyesClosedAmount` | `f` | w | - | *(no description)* |
| `/tracking/eye/LeftRightPitchYaw` | `ffff` | w | - | *(no description)* |
| `/tracking/eye/LeftRightVec` | `ffffff` | w | - | *(no description)* |
| `/tracking/face/v1` | `b` | w | - | *(no description)* |
| `/tracking/trackers/1/position` | `fff` | w | - | *(no description)* |
| `/tracking/trackers/1/rotation` | `fff` | w | - | *(no description)* |
| `/tracking/trackers/2/position` | `fff` | w | - | *(no description)* |
| `/tracking/trackers/2/rotation` | `fff` | w | - | *(no description)* |
| `/tracking/trackers/3/position` | `fff` | w | - | *(no description)* |
| `/tracking/trackers/3/rotation` | `fff` | w | - | *(no description)* |
| `/tracking/trackers/4/position` | `fff` | w | - | *(no description)* |
| `/tracking/trackers/4/rotation` | `fff` | w | - | *(no description)* |
| `/tracking/trackers/5/position` | `fff` | w | - | *(no description)* |
| `/tracking/trackers/5/rotation` | `fff` | w | - | *(no description)* |
| `/tracking/trackers/6/position` | `fff` | w | - | *(no description)* |
| `/tracking/trackers/6/rotation` | `fff` | w | - | *(no description)* |
| `/tracking/trackers/7/position` | `fff` | w | - | *(no description)* |
| `/tracking/trackers/7/rotation` | `fff` | w | - | *(no description)* |
| `/tracking/trackers/8/position` | `fff` | w | - | *(no description)* |
| `/tracking/trackers/8/rotation` | `fff` | w | - | *(no description)* |
| `/tracking/trackers/head/position` | `fff` | w | - | *(no description)* |
| `/tracking/trackers/head/rotation` | `fff` | w | - | *(no description)* |
| `/tracking/vrsystem/head/pose` | `ffffff` | r | [null] | *(no description)* |
| `/tracking/vrsystem/leftwrist/pose` | `ffffff` | r | [null] | *(no description)* |
| `/tracking/vrsystem/rightwrist/pose` | `ffffff` | r | [null] | *(no description)* |

### `/usercamera` (38)

| Endpoint | Type | Access | Value then | VRChat's description |
|---|---|---|---|---|
| `/usercamera/Aperture` | `f` | rw | [15] | *(no description)* |
| `/usercamera/AudioFromCamera` | `T` | rw | [false] | *(no description)* |
| `/usercamera/AutoLevelPitch` | `T` | rw | [false] | *(no description)* |
| `/usercamera/AutoLevelRoll` | `T` | rw | [false] | *(no description)* |
| `/usercamera/Capture` | `T` | w | [false] | *(no description)* |
| `/usercamera/CaptureDelayed` | `T` | w | [false] | *(no description)* |
| `/usercamera/Close` | `T` | w | [false] | *(no description)* |
| `/usercamera/DollyPathsStayVisible` | `T` | rw | [false] | *(no description)* |
| `/usercamera/Duration` | `f` | rw | [1] | *(no description)* |
| `/usercamera/Environment` | `T` | rw | [true] | *(no description)* |
| `/usercamera/Exposure` | `f` | rw | [0] | *(no description)* |
| `/usercamera/FlingToClose` | `T` | rw | [true] | *(no description)* |
| `/usercamera/FlySpeed` | `f` | rw | [0.2] | *(no description)* |
| `/usercamera/Flying` | `T` | rw | [false] | *(no description)* |
| `/usercamera/FocalDistance` | `f` | rw | [2] | *(no description)* |
| `/usercamera/GreenScreen` | `T` | rw | [false] | *(no description)* |
| `/usercamera/Hue` | `f` | rw | [120] | *(no description)* |
| `/usercamera/Lightness` | `f` | rw | [50] | *(no description)* |
| `/usercamera/LocalPlayer` | `T` | rw | [true] | *(no description)* |
| `/usercamera/Lock` | `T` | rw | [true] | *(no description)* |
| `/usercamera/LookAtMe` | `T` | rw | [false] | *(no description)* |
| `/usercamera/LookAtMeXOffset` | `f` | rw | [0] | *(no description)* |
| `/usercamera/LookAtMeYOffset` | `f` | rw | [0] | *(no description)* |
| `/usercamera/Mode` | `i` | rw | [0] | *(no description)* |
| `/usercamera/OrientationIsLandscape` | `T` | rw | [true] | *(no description)* |
| `/usercamera/PhotoRate` | `f` | rw | [1] | *(no description)* |
| `/usercamera/Pose` | `ffffff` | r | [[0,0,0,0,0,0]] | *(no description)* |
| `/usercamera/RemotePlayer` | `T` | rw | [true] | *(no description)* |
| `/usercamera/RollWhileFlying` | `T` | rw | [false] | *(no description)* |
| `/usercamera/Saturation` | `f` | rw | [100] | *(no description)* |
| `/usercamera/ShowFocus` | `T` | rw | [false] | *(no description)* |
| `/usercamera/ShowUIInCamera` | `T` | rw | [false] | *(no description)* |
| `/usercamera/SmoothMovement` | `T` | rw | [false] | *(no description)* |
| `/usercamera/SmoothingStrength` | `f` | rw | [5] | *(no description)* |
| `/usercamera/Streaming` | `T` | rw | [false] | *(no description)* |
| `/usercamera/TriggerTakesPhotos` | `T` | rw | [true] | *(no description)* |
| `/usercamera/TurnSpeed` | `f` | rw | [1] | *(no description)* |
| `/usercamera/Zoom` | `f` | rw | ["NaN"] | *(no description)* |

### `/avatar/parameters/*` (per avatar; 94 on the user's avatar)
Every avatar has VRChat's built-in parameters plus its own (toggles, outfits, physbones…). The dump only counts them; the headset debug "OSCQuery probe" lists the names it sees. VRChat's built-ins (from VRChat's avatar parameter docs; which of them Quest actually reports was not dumped one by one): `IsLocal`, `PreviewMode`, `Viseme`, `Voice` (mic level 0-1), `GestureLeft`/`GestureRight` (0-7: neutral, fist, open hand, point, victory, rock'n'roll, finger gun, thumbs up), `GestureLeftWeight`/`GestureRightWeight` (trigger pull), `AngularY`, `VelocityX`/`VelocityY`/`VelocityZ`, `VelocityMagnitude`, `Upright`, `Grounded`, `Seated`, `AFK`, `TrackingType`, `VRMode`, `MuteSelf`, `InStation`, `Earmuffs`, `IsOnFriendsList`, `AvatarVersion`, `IsAnimatorEnabled`, `ScaleModified`, `ScaleFactor`, `ScaleFactorInverse`, `EyeHeightAsMeters`, `EyeHeightAsPercent`. Used today: `MuteSelf`, `AFK`, `Velocity*`, `EyeHeightAsMeters`/`ScaleFactor` (chatbox tokens), `GestureLeft`/`GestureRight` (hand drag grip), any bool (dictation Listen trigger), any name (`{param:Name}`).

## How VRC-A talks to VRChat (the connection)
Three paths, all on the headset itself (no PC):
- **Reading by polling (OSCQuery, main app, `VrcaOscQuery`)**: VRChat runs an HTTP server and announces it over mDNS as `_oscjson._tcp` (name `VRChat-Client-XXXXXX`, a new random port each VRChat start, on the headset's Wi-Fi IP, e.g. `192.168.0.89:34995`). VRC-A finds it with Android `NsdManager`, then `GET http://host:port/avatar` every 250 ms (1 s while the push below is live) returns all avatar params with values + the `/avatar/*` nodes. `GET /?HOST_INFO` gives VRChat's OSC input: `OSC_IP 127.0.0.1`, `OSC_PORT 9000`, UDP. `GET /` returns the whole tree (the dump above). Plain `http://`, so the headset manifest allows cleartext. On Quest, VRChat does NOT send anything to UDP 9001 except `/avatar/change`, which is why polling was needed at all.
- **Sending (UDP to VRChat, `VrcaOsc`)**: OSC messages to VRChat's input port 9000 (`/chatbox/input`, `/input/*`, `/avatar/eyeheight`, `/avatar/parameters/*`). Host: VRChat's advertised IP from HOST_INFO / the resolved OSCQuery host (loopback alone proved unreliable on Quest).
- **Being pushed to (the bridge, `:oscbridge` process)**: VRC-A announces ITSELF as an OSCQuery app (`MdnsAnnouncer`: own mDNS responder, `_oscjson._tcp` + `_osc._udp`, stable name `VRCA-XXXX` and stable ports saved in prefs, never sends a goodbye, re-announces when the Wi-Fi IP changes and never advertises `127.0.0.1`). The bridge serves `/?HOST_INFO` + a tree saying which addresses we want (`/avatar`, `/tracking/vrsystem`), VRChat reads it (2 requests), shows the toast "Sending OSC data to VRCA-…" and then pushes to our UDP port: every avatar param on change, the `/avatar/*` nodes on connect/change, and head + both wrist poses ~75/s each (needs VRChat's "Allow Sending Head and Wrist VR Tracking OSC Data"). Measured: 13,871 packets in one session (9,547 params, 4,316 per pose).
- **Bridge process and relay**: VRChat only connects to the FIRST such app per VRChat session and stops for good if our socket closes even briefly, so the socket lives in the separate `:oscbridge` process (`OscBridgeService`, outlives the main app; only an app update needs a VRChat restart). It relays every datagram to the main app over a local socket named `<package>.oscbridge` (own uid only): frames `[type 1 byte][length 4 bytes][payload]`, type 0 = one OSC datagram, 1 = bridge status JSON once a second, 2 (main → bridge) = hand-drag state `host\nport\nmoving` so the bridge can zero the sticky `/input` axes if the main app dies mid-drag. The main app reads it in `OscAnnouncer` (`pushLive` = linked + this VRChat is pushing). Stale-value rules: headset.md.

## New feature ideas from the endpoint list (2026-10-29, not built)
- **Mic + safety controls**: `/input/Voice` (mic toggle; `MuteSelf` reads the result back) and `/input/PanicButton` (VRChat's safety mode) as panel buttons or dictation voice commands ("mute me", "panic"). Small, useful, no avatar needed.
- **Hand-sign shortcuts**: `GestureLeft`/`GestureRight` already arrive live through the bridge. Holding a sign (e.g. thumbs-up for 1 s) could send a chatbox preset, toggle the mic, take a photo or open the Quick Menu (`/input/QuickMenuToggleLeft`). Works on every avatar (built-in params), nothing to upload.
- **Talking-aware dictation**: `Voice` is VRChat's own mic level (0 when muted). The dictation could listen only while VRChat hears speech, fewer false triggers and less battery than running VAD on everything.
- **Sleep / AFK eyes**: `/tracking/eye/EyesClosedAmount` (write) closes the avatar's eyes on avatars with eye tracking set up; pair with `AFK`/`Seated` or a "sleep" button. `/tracking/eye/CenterPitchYaw` could make the eyes look somewhere. Needs a device test: VRChat may ignore eye input without eye-tracking hardware.
- **Pose presets without full-body**: `/tracking/trackers/1-8` (+ `head` to align) pose the avatar's body; sending fixed hip/feet positions relative to the head pose (we have it, pushed) might give sit / lie-down poses for Quest users with no trackers. Needs a device test (calibration may be required).
- **Camera remote**: as listed above; `/usercamera/Pose` (read) + our head pose would let a panel show where the camera is relative to you. No camera image (VRChat doesn't expose frames).
- **Performance HUD toggle**: `/input/ShowDebugInfo2` (build + FPS) as a quick button; `ShowDebugInfo4` (stats about other users, incl. who is in VR).
- **Activity tokens/stats**: `VelocityMagnitude`/`Grounded`/`Seated` → distance walked, time seated, `{seated}`/`{speed}` chatbox tokens.
