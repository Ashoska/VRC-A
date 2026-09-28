# UI Lab

Renders the REAL app UI on the JVM (Robolectric native graphics) to a PNG. No APK, no device.

    tools/ui-lab/ui.sh shot admin      # phone, 411x891dp @xxhdpi → ui-shots/admin-home.png
    tools/ui-lab/ui.sh shot headset    # Quest panel, 1024x640dp @xhdpi → ui-shots/headset-home.png
    tools/ui-lab/ui.sh shot public --qualifiers w360dp-h780dp-port-xxhdpi --out ui-shots/small.png

About 15-20 s per shot with a warm Gradle daemon, including recompiling an edited file.

- The harness is `app/src/test/kotlin/com/vrca/uilab/UiLabTest.kt`. It only runs with `-PuiLab`,
  so normal test runs skip it.
- `UiLabApp` swaps in a fake Firebase project pointed at a dead emulator port before the app starts,
  so nothing can reach production.
- **VRChat alt:** `tools/ui-lab/vrc-login.sh` logs the alt in. The password is an API credential
  on the environment for `api.vrchat.cloud`, added by the agent proxy, so it's never on this
  machine. Then `vrc-login.sh <code>` finishes the login with the emailed code. Cookies stay in
  git-ignored `ui-shots/.vrc/`. Every shot is then signed in as the alt, with its real presence;
  `--no-vrchat` skips that. `vrc-login.sh status` checks the session.
- **`--firestore`:** uses the real Firebase project with a fixed lab device id per variant
  (`sha256("uilab:<flavor>")`), so runs never pile up new user docs. Without it, Firebase is
  offline.
- Presets and toggles start fresh on every shot (defaults, everything off).
- Not rendered: the Discord WebView, notifications, OSC, and the live VRChat connection (pipeline websocket), so friends counts don't show. Fonts are
  Robolectric's, close to the device.
