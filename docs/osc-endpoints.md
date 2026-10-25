# VRChat OSC on Quest: what's there (research notes)

Source: the headset debug "Dump all endpoints" (`VrcaOscQuery.dumpTree()`), run on the user's Quest 3 in VRChat on 2026-10-25. VRChat listed **237 endpoints** (`VRChat-Client-XXXX`; HOST_INFO `OSC_IP 127.0.0.1`, `OSC_PORT 9000`, UDP). Below is what was found and what each could be used for. Kept for future features; nothing here is built yet unless it says so.

## Hand-drag locomotion (in progress)
- **`/tracking/vrsystem/{head,leftwrist,rightwrist}/pose`** (`ffffff`, read): head + wrist pose (position + rotation). They exist on Quest but read `[null]` when polled. VRChat SENDS this output to apps that announce themselves over OSCQuery; the headset debug "Announce to VRChat (receive test)" (`OscAnnouncer`) tests that. Enabled in VRChat by "Allow Sending Head and Wrist VR Tracking OSC Data" (Tracking & IK).
- **`/input/ToggleSitStand`** (bool, write, undocumented): "Toggles between Sitting and Standing modes. Only has an effect in VR." Seated mode lifts the view to standing height, so it's a VRChat-side vertical offset reachable from OSC. Test button: "Send ToggleSitStand".
- **`/input/LookVertical`** (float, write, undocumented): look up/down; probably desktop-only.
- Movement inputs used for horizontal drag: `/input/Vertical`, `/input/Horizontal` (floats -1..1), `/input/Run`, `/input/Jump`.
- No OSC input moves the player up/down or moves the play space. The **system floor height** does (user-tested: floor set below the feet = float, above = crouch, live in VRChat) but no setting or property an app can read holds it (floor probe, 2026-10-25): it belongs to Meta's native `trackingservice` (`ro.vendor.oculus.trackingservice.instance=/odm/bin/trackingservice`). Also seen: `persist.device_config.oculus_shared_os_services_sessionless.oculus_compositor_space_manager=false`, `config.disable_smartspacemanagerservice=true`.
- Not usable: OSC trackers `/tracking/trackers/{1-8,head}/{position,rotation}` only pose the avatar body (the head pair aligns the tracker space); avatar add-ons are out (Quest-only users can't upload avatars); OpenXR layers need a modified VRChat APK.

## Ideas for other features
- **Camera remote** (`/usercamera/*`, ~40 endpoints, mostly read/write): `Capture`, `CaptureDelayed`, `Mode`, `Zoom`, `Exposure`, `Aperture`, `FocalDistance`, `GreenScreen` + `Hue`/`Saturation`/`Lightness`, `Streaming`, `Flying`/`FlySpeed`, `SmoothMovement`/`SmoothingStrength`, `LookAtMe` (+ offsets), `LocalPlayer`/`RemotePlayer`, `TriggerTakesPhotos`, `PhotoRate`, `Duration`, `ShowUIInCamera`, `Lock`, `Close`, `Pose` (`ffffff`, read: camera position). A VRC-A panel could run VRChat's camera.
- **Camera dolly paths** (`/dolly/Play`, `/dolly/PlayDelayed`, `/dolly/Import`, `/dolly/Export`, `/dolly/ExportLocal`): save, load and play dolly paths.
- **World size limits** (`/avatar/eyeheightmin`, `/avatar/eyeheightmax`, `/avatar/eyeheightscalingallowed`, read): the avatar-size tool could show the world's range and disable itself where scaling is off.
- **Current avatar** (`/avatar/name`, `/avatar/change`, read): live avatar name and id, e.g. an `{avatar}` chatbox token or the clone tool.
- **Debug overlays** (`/input/ShowDebugInfo0`-`9`): toggles for VRChat's debug menus (2 = build + FPS, 3 = output log).
- Also listed: `/input/AFKToggle`, `/input/Voice`, `/chatbox/input` (`sTT`), `/chatbox/typing`, `/avatar/avatarfullfacetracking`. Eye/face tracking input (`/tracking/eye/*`, `/tracking/face/v1`) is not useful (Quest Pro only).
