# Symbol Lab

Turns a fan-made drawing into a symbol for the boot screen's moving wall (`app/.../app/BootBackdrop.kt`).

    pip install scikit-image pillow          # once per session
    python3 -I tools/symbol-lab/symbol.py drawing.png --name "cheese"

It prints the Kotlin line to paste into `BootSymbols` (then add the name to `BootSymbols.all`):

    val Cheese = BootSymbol.fromSvg("cheese", "M12 40L88 22...")

and writes `ui-shots/symbols/<name>.png`: original | traced | on the wall (app colours and size).
Check the preview, then the boot screen in the UI Lab (`ui.sh run headset "show boot mid; shot b"`).

- `--mode lines` (default): line drawings. Traces the CENTRE of each stroke (an outline tracer
  would give every line two edges). Solid dots (eyes) become small circles; spurs are dropped.
- `--mode outline`: solid filled shapes; traces their outer edge.
- `--detail N`: simplification in box units (default 0.6). Lower = closer to the drawing, more bytes.
- `--invert`: force light-on-dark (auto-detected; transparent PNGs use their alpha).

Path data is tiny (a symbol is typically 0.3-2 KB of text, no image stored), drawn live at any panel
zoom. Ask for: one simple outline drawing, no shading/colour/text, transparent or plain background.
