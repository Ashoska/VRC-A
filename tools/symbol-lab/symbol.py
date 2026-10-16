#!/usr/bin/env python3
"""Symbol Lab: turns a fan-made drawing into a boot-wall symbol (BootBackdrop.kt).

    tools/symbol-lab/symbol.py <image> --name "cheese" [--mode lines|outline] [--detail N] [--invert]

Takes a PNG/JPG/WEBP (or an SVG's raster export) of a drawing, traces it into compact path data
in the wall's 100x100 box and prints the Kotlin line to paste into BootSymbols:

    val Cheese = BootSymbol.fromSvg("cheese", "M12 40L88 22...")

plus its size in bytes and a preview PNG (original | as traced | how it looks on the wall) in
ui-shots/symbols/<name>.png.

Modes:
  lines    (default) for line drawings: traces the CENTRE of every stroke, so each line comes out
           once (an outline tracer gives every stroke two edges: doubled lines).
  outline  for solid, filled shapes: traces their outer edge.
--detail   simplification tolerance in box units (default 0.6; lower = more points, more bytes).
--invert   the drawing is light on a dark background (auto-detected; this forces it).

Needs: pip install scikit-image pillow
"""
import argparse, os, sys
import numpy as np
from PIL import Image, ImageDraw
from scipy.ndimage import distance_transform_edt
from skimage.morphology import skeletonize, remove_small_objects, closing, disk
from skimage.measure import approximate_polygon, find_contours

BOX = 100.0          # the wall's symbol box (BootSymbol.fromSvg viewBox)
WORK = 420           # working resolution of the drawing's long side, px
APP_STROKE = 1.6     # BootBackdrop line width, dp
APP_SIZE = 58        # BootBackdrop SYMBOL_SIZE, dp
BACKDROP = (6, 16, 28)       # GremlinColors.Backdrop
EDGE = (34, 59, 87)          # GremlinColors.Edge


def ink_mask(img: Image.Image, invert: bool | None) -> np.ndarray:
    """True where the drawing is. Transparent PNGs use alpha; others contrast with the border."""
    rgba = np.asarray(img.convert("RGBA")).astype(np.float32)
    alpha = rgba[..., 3]
    if (alpha < 250).mean() > 0.05:
        return alpha > 128
    gray = rgba[..., :3].mean(axis=2)
    border = np.concatenate([gray[0], gray[-1], gray[:, 0], gray[:, -1]])
    bg = np.median(border)
    if invert is None:
        return np.abs(gray - bg) > 60
    return gray > bg + 60 if invert else gray < bg - 60


def fit(mask: np.ndarray):
    """Crop to the drawing, scale its long side to WORK px; returns (mask, px -> box-unit scale, offset)."""
    ys, xs = np.nonzero(mask)
    if len(xs) == 0:
        sys.exit("no drawing found (try --invert)")
    crop = mask[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    h, w = crop.shape
    k = WORK / max(h, w)
    im = Image.fromarray((crop * 255).astype(np.uint8)).resize((max(1, round(w * k)), max(1, round(h * k))), Image.LANCZOS)
    m = np.asarray(im) > 127
    # Into the 100 box with a small margin, centred.
    unit = (BOX * 0.92) / WORK
    ox = (BOX - m.shape[1] * unit) / 2
    oy = (BOX - m.shape[0] * unit) / 2
    return m, unit, (ox, oy)


NB = [(-1, -1), (-1, 0), (-1, 1), (0, -1), (0, 1), (1, -1), (1, 0), (1, 1)]


def trace_skeleton(sk: np.ndarray):
    """Pixel chains of a 1-px skeleton: from every end/junction to the next, then leftover loops."""
    H, W = sk.shape
    pts = set(zip(*np.nonzero(sk)))
    def nbrs(p):
        y, x = p
        return [(y + dy, x + dx) for dy, dx in NB if (y + dy, x + dx) in pts]
    deg = {p: len(nbrs(p)) for p in pts}
    used = set()  # undirected pixel edges already walked
    chains = []
    def walk(a, b):
        chain = [a, b]
        used.add(frozenset((a, b)))
        prev, cur = a, b
        while deg[cur] == 2:
            nxt = [n for n in nbrs(cur) if n != prev and frozenset((cur, n)) not in used]
            if not nxt:
                break
            prev, cur = cur, nxt[0]
            used.add(frozenset((prev, cur)))
            chain.append(cur)
        return chain
    for p in [p for p in pts if deg[p] != 2]:
        for n in nbrs(p):
            if frozenset((p, n)) not in used:
                chains.append(walk(p, n))
    for p in pts:  # closed loops (every pixel degree 2)
        for n in nbrs(p):
            if frozenset((p, n)) not in used:
                chains.append(walk(p, n))
    # Isolated single pixels (dots, e.g. eyes).
    chains += [[p] for p in pts if deg[p] == 0]
    return chains, deg


def fmt(v: float) -> str:
    s = f"{v:.1f}"
    return s[:-2] if s.endswith(".0") else s


def to_path(polys, unit, off, closed=False) -> str:
    out = []
    for poly in polys:
        if len(poly) == 1:  # a dot: a tiny segment, drawn as a round dot by the round cap
            y, x = poly[0]
            X, Y = off[0] + x * unit, off[1] + y * unit
            out.append(f"M{fmt(X)} {fmt(Y)}L{fmt(X + 0.1)} {fmt(Y)}")
            continue
        seg = []
        for i, (y, x) in enumerate(poly):
            X, Y = off[0] + x * unit, off[1] + y * unit
            seg.append(("M" if i == 0 else "L") + f"{fmt(X)} {fmt(Y)}")
        out.append("".join(seg) + ("Z" if closed else ""))
    return "".join(out)


def dots_path(dots, unit, off) -> str:
    """Solid dots as small circles (two SVG arcs); a round stroke fills a small one in."""
    out = []
    for cy, cx, r in dots:
        X, Y, R = off[0] + cx * unit, off[1] + cy * unit, max(r * unit * 0.6, 0.6)
        out.append(f"M{fmt(X - R)} {fmt(Y)}a{fmt(R)} {fmt(R)} 0 1 0 {fmt(2 * R)} 0a{fmt(R)} {fmt(R)} 0 1 0 {fmt(-2 * R)} 0")
    return "".join(out)


def length(poly) -> float:
    p = np.asarray(poly, dtype=float)
    return float(np.sum(np.hypot(*np.diff(p, axis=0).T))) if len(p) > 1 else 0.0


def preview(src: Image.Image, polys, dots, unit, off, name: str, out_path: str):
    """original | traced (clear) | on the wall (app colours, app size x4)."""
    S = 300
    panel = Image.new("RGB", (S * 3 + 40, S + 20), (20, 24, 32))
    orig = src.convert("RGBA"); orig.thumbnail((S, S))
    bg = Image.new("RGBA", (S, S), (128, 132, 140, 255))  # grey: shows white-on-clear art too
    bg.alpha_composite(orig, ((S - orig.width) // 2, (S - orig.height) // 2))
    panel.paste(bg.convert("RGB"), (10, 10))
    for col, (fill, ink, width) in enumerate([((12, 18, 28), (235, 240, 255), 3), (BACKDROP, EDGE, APP_STROKE * 4 * S / (APP_SIZE * 4))]):
        tile = Image.new("RGB", (S, S), fill)
        d = ImageDraw.Draw(tile)
        k = S / BOX
        for poly in polys:
            xy = [((off[0] + x * unit) * k, (off[1] + y * unit) * k) for y, x in poly]
            if len(xy) == 1:
                r = max(1.5, width)
                d.ellipse([xy[0][0] - r, xy[0][1] - r, xy[0][0] + r, xy[0][1] + r], fill=ink)
            else:
                d.line(xy, fill=ink, width=max(1, round(width)), joint="curve")
        for cy, cx, r in dots:
            X, Y = (off[0] + cx * unit) * k, (off[1] + cy * unit) * k
            R = max(r * unit * 0.6, 0.6) * k
            d.ellipse([X - R, Y - R, X + R, Y + R], outline=ink, width=max(1, round(width)))
        panel.paste(tile, (20 + S * (col + 1), 10))
    panel.save(out_path)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("image")
    ap.add_argument("--name", required=True)
    ap.add_argument("--mode", choices=["lines", "outline"], default="lines")
    ap.add_argument("--detail", type=float, default=0.6)
    ap.add_argument("--invert", action="store_true", default=None)
    a = ap.parse_args()

    src = Image.open(a.image)
    mask = ink_mask(src, a.invert)
    m, unit, off = fit(mask)
    m = closing(m, disk(2))
    m = remove_small_objects(m, max_size=12)
    tol = a.detail / unit  # box units -> working px

    if a.mode == "lines":
        sk = skeletonize(m)
        dist = distance_transform_edt(m)  # half the ink's thickness at each skeleton pixel
        chains, deg = trace_skeleton(sk)
        polys, dots = [], []
        for c in chains:
            if len(c) == 1 or length(c) * unit < 2.5:
                # Short: a spur off a line (drop it) or a solid dot on its own, like an eye (keep,
                # as a circle the size of the blob).
                if any(deg.get(tuple(e), 0) >= 3 for e in (c[0], c[-1])) and len(c) > 1:
                    continue
                cy, cx = np.mean(np.asarray(c, float), axis=0)
                r = max(dist[int(round(cy)), int(round(cx))], 1.0)
                dots.append((cy, cx, r))
                continue
            polys.append([tuple(p) for p in approximate_polygon(np.asarray(c, float), tol)] if len(c) > 2 else c)
        closed = False
    else:
        polys = [[tuple(p) for p in approximate_polygon(c, tol)] for c in find_contours(np.pad(m, 1).astype(float), 0.5)]
        polys = [[(y - 1, x - 1) for y, x in p] for p in polys if len(p) > 2]
        closed = True
        dots = []

    d = to_path(polys, unit, off, closed) + dots_path(dots, unit, off)
    ident = "".join(w.capitalize() for w in "".join(ch if ch.isalnum() else " " for ch in a.name).split()) or "Symbol"
    print(f'    val {ident} = BootSymbol.fromSvg("{a.name}", "{d}")')
    print(f"[symbol] {len(polys)} strokes + {len(dots)} dots, {len(d.encode())} bytes of path data", file=sys.stderr)
    root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
    out_dir = os.path.join(root, "ui-shots", "symbols"); os.makedirs(out_dir, exist_ok=True)
    out = os.path.join(out_dir, f"{a.name.replace(' ', '-')}.png")
    preview(src, polys, dots, unit, off, a.name, out)
    print(f"[symbol] preview: {out}", file=sys.stderr)


if __name__ == "__main__":
    main()
