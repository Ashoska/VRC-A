# Symbol Lab

Turns a fan-made drawing into a symbol for the boot screen's moving wall (`app/.../app/BootBackdrop.kt`).

    pip install scikit-image pillow          # once per session
    python3 -I tools/symbol-lab/symbol.py drawing.png --name "cheese"

It prints the Kotlin line to paste into `BootSymbols` (then add the name to `BootSymbols.all`):

    val Cheese = BootSymbol.fromSvg("cheese", "M12 40L88 22...")

and writes `ui-shots/symbols/<name>.png`: original | traced | on the wall (app colours and size).
Check the preview, then the boot screen in the UI Lab (`ui.sh run headset "show boot mid; shot b"`).

- `--mode lines` (default): line drawings. Traces the CENTRE of each stroke (an outline tracer
  would give every line two edges), smooths each line along its length (no pixel staircase),
  simplifies it and writes smooth curves (`C`), keeping real corners sharp. Solid dots (eyes)
  become small circles; spurs off junctions (scaled to the line thickness) are dropped; line ends
  that meet are joined.
- `--mode outline`: solid filled shapes; traces their outer edge, smoothed the same way.
- `--smooth N` (default 1.0, box units), `--corner DEG` (default 50), `--detail N` (default 0.35):
  how much lines are smoothed, how sharp a turn stays a corner, how closely points follow the line.
- `--invert`: force light-on-dark (auto-detected; transparent PNGs use their alpha).

Quality notes (why it's built this way): curve handles are sized to their own segment (plain
Catmull-Rom sized them from the neighbours' gap and overshot into a hook beside long straight
sides); the drawing is resampled to 900 px and blurred slightly before tracing (smooth edges).

Path data is tiny (a symbol is typically 1-3 KB of text, no image stored), drawn live at any panel
zoom. Ask for: one simple outline drawing, no shading/colour/text, transparent or plain background.
