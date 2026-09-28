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
- It draws with a fresh, logged-out state: default presets, everything off.
- Not rendered: the Discord WebView, notifications, OSC and the real VRChat data. Fonts are
  Robolectric's, close to the device.
