#!/usr/bin/env python3
"""Symbol Lab: turns a fan-made drawing into a boot-wall symbol (BootBackdrop.kt).

    tools/symbol-lab/symbol.py <image> --name "cheese" [--mode lines|outline] [--smooth N] [--invert]

Takes a PNG/JPG/WEBP of a drawing, traces it into compact, smooth path data in the wall's 100x100
box and prints the Kotlin line to paste into BootSymbols:

    val Cheese = BootSymbol.fromSvg("cheese", "M12 40C...")

plus its size in bytes and a preview PNG (original | as traced | how it looks on the wall) in
ui-shots/symbols/<name>.png.

How (lines mode): the drawing is traced along the CENTRE of each stroke (an outline tracer gives
every line two edges), each traced line is smoothed along its length (no pixel staircase),
simplified, then written as smooth curves through its points, with real corners (ear tips, the
tip of a wedge) kept sharp. Line ends that almost meet are snapped together; solid dots (eyes)
become small circles; tiny spurs at junctions are dropped.

  --mode outline  for solid, filled shapes: traces their outer edge (smoothed the same way).
  --smooth N      how much each line is smoothed, in box units (default 1.0; 0 = off).
  --corner DEG    turn sharper than this stays a corner (default 50).
  --invert        the drawing is light on a dark background (auto-detected; this forces it).

Needs: pip install scikit-image pillow
"""
import argparse, math, os, sys
import numpy as np
from PIL import Image, ImageDraw
from scipy.ndimage import distance_transform_edt, gaussian_filter, gaussian_filter1d
from skimage.morphology import skeletonize, remove_small_objects, closing, disk
from skimage.measure import approximate_polygon, find_contours

BOX = 100.0          # the wall's symbol box (BootSymbol.fromSvg viewBox)
WORK = 900           # working resolution of the drawing's long side, px
APP_STROKE = 1.6     # BootBackdrop line width, dp
APP_SIZE = 58        # BootBackdrop SYMBOL_SIZE, dp
BACKDROP = (6, 16, 28)       # GremlinColors.Backdrop
EDGE = (34, 59, 87)          # GremlinColors.Edge


# ---------------------------------------------------------------- reading the drawing

def ink_level(img: Image.Image, invert: bool | None) -> np.ndarray:
    """0..1 'how much ink' per pixel. Transparent PNGs use alpha; others contrast with the border."""
    rgba = np.asarray(img.convert("RGBA")).astype(np.float32) / 255
    alpha = rgba[..., 3]
    if (alpha < 0.98).mean() > 0.05:
        return alpha
    gray = rgba[..., :3].mean(axis=2)
    bg = np.median(np.concatenate([gray[0], gray[-1], gray[:, 0], gray[:, -1]]))
    light_ink = (invert is True) or (invert is None and bg < 0.5)
    return np.clip((gray - bg) / 0.6, 0, 1) if light_ink else np.clip((bg - gray) / 0.6, 0, 1)


def fit(ink: np.ndarray):
    """Crop to the drawing and resample its long side to WORK px (smooth, anti-aliased), then
    threshold: the resample + blur remove jagged pixel edges before tracing.
    Returns (mask, px -> box unit, (x, y) offset into the box)."""
    ys, xs = np.nonzero(ink > 0.35)
    if len(xs) == 0:
        sys.exit("no drawing found (try --invert)")
    crop = ink[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    h, w = crop.shape
    k = WORK / max(h, w)
    im = Image.fromarray((crop * 255).astype(np.uint8)).resize(
        (max(1, round(w * k)), max(1, round(h * k))), Image.LANCZOS)
    arr = gaussian_filter(np.asarray(im).astype(np.float32) / 255, 1.6)
    m = np.pad(arr > 0.5, 6)
    unit = (BOX * 0.92) / WORK
    ox = (BOX - m.shape[1] * unit) / 2
    oy = (BOX - m.shape[0] * unit) / 2
    return m, unit, (ox, oy)


# ---------------------------------------------------------------- tracing

NB = [(-1, -1), (-1, 0), (-1, 1), (0, -1), (0, 1), (1, -1), (1, 0), (1, 1)]


def trace_skeleton(sk: np.ndarray):
    """Pixel chains of a 1-px skeleton: every end/junction to the next, then leftover loops.
    Returns ([(chain, closed)], degree map)."""
    pts = set(zip(*np.nonzero(sk)))
    def nbrs(p):
        y, x = p
        return [(y + dy, x + dx) for dy, dx in NB if (y + dy, x + dx) in pts]
    deg = {p: len(nbrs(p)) for p in pts}
    used = set()
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
                chains.append((walk(p, n), False))
    for p in pts:  # loops: every pixel has two neighbours (a circle, a closed outline)
        for n in nbrs(p):
            if frozenset((p, n)) not in used:
                c = walk(p, n)
                chains.append((c, len(c) > 8 and max(abs(c[0][0] - c[-1][0]), abs(c[0][1] - c[-1][1])) <= 1))
    chains += [([p], False) for p in pts if deg[p] == 0]
    return chains, deg


def smooth(pts: np.ndarray, closed: bool, sigma_px: float) -> np.ndarray:
    """Gaussian-smooth a traced line along its length (removes the pixel staircase).
    Open lines keep their exact end points so joins stay put."""
    if sigma_px <= 0 or len(pts) < 5:
        return pts
    if closed:
        return gaussian_filter1d(pts, sigma_px, axis=0, mode="wrap")
    out = gaussian_filter1d(pts, sigma_px, axis=0, mode="nearest")
    out[0], out[-1] = pts[0], pts[-1]
    return out


def raw_corners(p: np.ndarray, closed: bool, k: int, max_turn_deg: float):
    """Corners on the RAW trace (before smoothing blurs them): points where the direction over
    k points before vs k after turns sharper than max_turn_deg, one per corner (local maximum)."""
    n = len(p)
    if n < 2 * k + 1:
        return []
    def turn_at(i, kk):
        v1 = p[i] - p[(i - kk) % n]
        v2 = p[(i + kk) % n] - p[i]
        l1, l2 = np.hypot(*v1), np.hypot(*v2)
        if l1 <= 1e-6 or l2 <= 1e-6:
            return 0.0
        return math.degrees(math.acos(max(-1.0, min(1.0, float(np.dot(v1, v2) / (l1 * l2))))))
    turns = np.zeros(n)
    kh = max(2, k // 2)
    for i in range(n):
        if not closed and (i < k or i >= n - k):
            continue
        t = turn_at(i, k)
        # A real corner turns all at once: over half the distance it still turns most of the
        # way. A tight round curve (a mouth, a small loop) turns gradually, about half as much
        # over half the distance, so it stays a curve instead of being pinned into a point.
        if t > max_turn_deg and turn_at(i, kh) < 0.65 * t:
            t = 0.0
        turns[i] = t
    out = []
    for i in range(n):
        if turns[i] <= max_turn_deg:
            continue
        window = [turns[(i + j) % n] if closed else turns[i + j] for j in range(-k, k + 1) if closed or 0 <= i + j < n]
        if turns[i] >= max(window) and not (out and i - out[-1] <= k):
            out.append(i)
    return out


def _arc(q: np.ndarray):
    return np.r_[0, np.cumsum(np.hypot(*np.diff(q, axis=0).T))]


def _join(A: np.ndarray, B: np.ndarray, raw_corner: np.ndarray):
    """The corner between smoothed piece A (ending there) and B (starting there): where their
    straight runs cross, measured 1-3.5 units back from the corner (past the rounded bit). Only
    for two real straight-ish runs; otherwise the raw corner. Returns (A trimmed, corner, B trimmed)."""
    la, lb = _arc(A[::-1]), _arc(B)
    if la[-1] > 4 and lb[-1] > 4:
        a1, a0 = A[::-1][np.searchsorted(la, 1.0)], A[::-1][np.searchsorted(la, 3.5)]
        b1, b0 = B[np.searchsorted(lb, 1.0)], B[np.searchsorted(lb, 3.5)]
        da, db = a1 - a0, b1 - b0
        det = da[0] * -db[1] - da[1] * -db[0]
        if abs(det) > 1e-6:
            t = ((b0 - a0)[0] * -db[1] - (b0 - a0)[1] * -db[0]) / det
            x = a0 + t * da
            if np.hypot(*(x - raw_corner)) < 2.5:
                return A[::-1][np.searchsorted(la, 1.0):][::-1], x, B[np.searchsorted(lb, 1.0):]
    return A[:-1], raw_corner, B[1:]


def shape_stroke(p: np.ndarray, closed: bool, sigma_pts: float, tol: float, k: int, max_turn: float):
    """Raw trace -> simplified points + their corner indices. Corners are found on the raw trace,
    the stretches between them smoothed separately, and each corner rebuilt where the two
    smoothed stretches' straight runs cross. (Pinning a smoothed stretch back onto the raw,
    rounded corner made a little hook at every corner.)"""
    cs = raw_corners(p, closed, k, max_turn)
    if not cs:
        sm = smooth(p, closed, sigma_pts)
        if closed:
            return [np.vstack([sm, sm[:1]])], True
        return [sm], False
    if closed:
        p = np.roll(p, -cs[0], axis=0)
        cs = [(c - cs[0]) % len(p) for c in cs]
        p = np.vstack([p, p[:1]])
        cuts = cs + [len(p) - 1]
    else:
        cuts = [0] + cs + [len(p) - 1]
    # Smooth each stretch freely (open ends of the whole stroke stay pinned: they meet other lines).
    pieces = []
    for i, (a, b) in enumerate(zip(cuts, cuts[1:])):
        seg = p[a:b + 1]
        sm = gaussian_filter1d(seg, sigma_pts, axis=0, mode="nearest") if len(seg) >= 5 and sigma_pts > 0 else seg.copy()
        if not closed and i == 0:
            sm[0] = seg[0]
        if not closed and i == len(cuts) - 2:
            sm[-1] = seg[-1]
        pieces.append(sm)
    corners_xy = []
    n = len(pieces)
    joins = range(n) if closed else range(n - 1)
    for j in joins:
        A, B = pieces[j], pieces[(j + 1) % n]
        At, x, Bt = _join(A, B, p[cuts[j + 1]])
        pieces[j], pieces[(j + 1) % n] = At, Bt
        corners_xy.append(x)
    bodies = []
    for j, piece in enumerate(pieces):
        start = corners_xy[j - 1] if (closed or j > 0) else None
        end = corners_xy[j] if (closed or j < n - 1) else None
        bodies.append(np.vstack([v for v in (start[None] if start is not None else None, piece,
                                              end[None] if end is not None else None) if v is not None]))
    # Corners join piece ends exactly; each piece is fitted separately, so the corner stays sharp.
    return bodies, False


def length(p: np.ndarray) -> float:
    return float(np.sum(np.hypot(*np.diff(p, axis=0).T))) if len(p) > 1 else 0.0


# ---------------------------------------------------------------- curves

def corners_of(p: np.ndarray, closed: bool, max_turn_deg: float):
    """Indices where the line turns sharper than max_turn_deg: kept as real corners."""
    n = len(p)
    out = set()
    for i in range(n):
        if not closed and (i == 0 or i == n - 1):
            continue
        a, b, c = p[(i - 1) % n], p[i], p[(i + 1) % n]
        v1, v2 = b - a, c - b
        l1, l2 = np.hypot(*v1), np.hypot(*v2)
        if l1 < 1e-6 or l2 < 1e-6:
            continue
        turn = math.degrees(math.acos(max(-1.0, min(1.0, float(np.dot(v1, v2) / (l1 * l2))))))
        if turn > max_turn_deg:
            out.add(i)
    return out


def bezier_segments(p: np.ndarray, closed: bool, corners: set):
    """Smooth cubic Béziers through the points, sharp at corners and open ends. Each handle points
    along the line through the neighbours but is sized to ITS OWN segment (a third of it): plain
    Catmull-Rom sizes handles from the neighbours' gap, so next to a long straight side a curve
    overshot past the corner into a hook. Yields (p0, c1, c2, p1)."""
    n = len(p)
    def direction(i):
        if i in corners or (not closed and (i == 0 or i == n - 1)):
            return None
        v = p[(i + 1) % n] - p[(i - 1) % n]
        l = np.hypot(*v)
        return v / l if l > 1e-9 else None
    segs = n if closed else n - 1
    for i in range(segs):
        a, b = p[i], p[(i + 1) % n]
        d = b - a
        seg = np.hypot(*d)
        ua, ub = direction(i), direction((i + 1) % n)
        c1 = a + (ua * seg / 3 if ua is not None else d / 3)
        c2 = b - (ub * seg / 3 if ub is not None else d / 3)
        yield a, c1, c2, b


# ---------------------------------------------------------------- curve fitting
# Smooth cubic Béziers fitted straight to the traced line (Schneider, "An Algorithm for
# Automatically Fitting Digitized Curves", Graphics Gems 1990): one curve per stretch, split where
# it strays more than the tolerance from the trace, with Newton re-parameterisation. Replaced
# "simplify to a few points, then draw curves through them": small round parts (tight hair curls,
# the bumps of a mouth) kept too few points and came out pointy or flat (user, Niko drawing).

def _bez(c, t):
    mt = 1 - t
    return ((mt ** 3)[:, None] * c[0] + (3 * mt * mt * t)[:, None] * c[1]
            + (3 * mt * t * t)[:, None] * c[2] + (t ** 3)[:, None] * c[3])


def _bez_d1(c, t):
    mt = 1 - t
    return ((3 * mt * mt)[:, None] * (c[1] - c[0]) + (6 * mt * t)[:, None] * (c[2] - c[1])
            + (3 * t * t)[:, None] * (c[3] - c[2]))


def _bez_d2(c, t):
    return ((6 * (1 - t))[:, None] * (c[2] - 2 * c[1] + c[0]) + (6 * t)[:, None] * (c[3] - 2 * c[2] + c[1]))


def _unit(v):
    l = float(np.hypot(*v))
    return v / l if l > 1e-9 else v


def _end_tangent(p, at_start, reach=1.2):
    """Direction leaving an end, averaged over ~reach box units (pixel steps are noisy)."""
    q = p if at_start else p[::-1]
    d = np.cumsum(np.r_[0, np.hypot(*np.diff(q, axis=0).T)])
    j = min(len(q) - 1, max(1, int(np.searchsorted(d, reach))))
    return _unit(q[j] - q[0])


def _gen(pts, u, t1, t2):
    p0, p3 = pts[0], pts[-1]
    b1, b2 = 3 * (1 - u) ** 2 * u, 3 * (1 - u) * u ** 2
    a1, a2 = t1[None] * b1[:, None], t2[None] * b2[:, None]
    tmp = pts - _bez(np.array([p0, p0, p3, p3]), u)
    C = np.array([[np.sum(a1 * a1), np.sum(a1 * a2)], [np.sum(a1 * a2), np.sum(a2 * a2)]])
    X = np.array([np.sum(a1 * tmp), np.sum(a2 * tmp)])
    det = C[0, 0] * C[1, 1] - C[0, 1] * C[1, 0]
    seg = float(np.hypot(*(p3 - p0)))
    if abs(det) > 1e-12:
        al1 = (X[0] * C[1, 1] - X[1] * C[0, 1]) / det
        al2 = (C[0, 0] * X[1] - C[1, 0] * X[0]) / det
    else:
        al1 = al2 = 0.0
    if al1 < 1e-6 * seg or al2 < 1e-6 * seg:
        al1 = al2 = seg / 3
    return np.array([p0, p0 + t1 * al1, p3 + t2 * al2, p3])


def _reparam(c, pts, u):
    d = _bez(c, u) - pts
    d1, d2 = _bez_d1(c, u), _bez_d2(c, u)
    num = np.sum(d * d1, axis=1)
    den = np.sum(d1 * d1, axis=1) + np.sum(d * d2, axis=1)
    return np.clip(np.where(np.abs(den) > 1e-12, u - num / np.where(den == 0, 1, den), u), 0, 1)


def _fit(pts, t1, t2, err2, out, depth=0):
    if len(pts) <= 2:
        seg = float(np.hypot(*(pts[-1] - pts[0]))) / 3
        out.append(np.array([pts[0], pts[0] + t1 * seg, pts[-1] + t2 * seg, pts[-1]]))
        return
    u = np.r_[0, np.cumsum(np.hypot(*np.diff(pts, axis=0).T))]
    u = u / u[-1] if u[-1] > 0 else np.linspace(0, 1, len(pts))
    c = _gen(pts, u, t1, t2)
    for _ in range(8):
        e = np.sum((_bez(c, u) - pts) ** 2, axis=1)
        if e.max() <= err2:
            out.append(c)
            return
        if e.max() > err2 * 16:
            break
        u = _reparam(c, pts, u)
        c = _gen(pts, u, t1, t2)
    e = np.sum((_bez(c, u) - pts) ** 2, axis=1)
    if e.max() <= err2 or depth > 12 or len(pts) < 4:
        out.append(c)
        return
    i = int(np.clip(np.argmax(e[1:-1]) + 1, 1, len(pts) - 2))
    tc = _unit(pts[max(0, i - 2)] - pts[min(len(pts) - 1, i + 2)])
    _fit(pts[:i + 1], t1, tc, err2, out, depth + 1)
    _fit(pts[i:], -tc, t2, err2, out, depth + 1)


def fit_curves(pts: np.ndarray, err: float, closed_loop: bool = False):
    """Béziers [p0, c1, c2, p3] fitted to a dense point run (box units)."""
    if len(pts) < 2:
        return []
    if closed_loop:
        t = _unit(pts[1] - pts[-2])  # same tangent both sides of the seam: no kink there
        t1, t2 = t, -t
    else:
        t1, t2 = _end_tangent(pts, True), _end_tangent(pts, False)
    out = []
    _fit(pts, t1, t2, err * err, out)
    return out


def fmt(v: float) -> str:
    s = f"{v:.1f}"
    s = s[:-2] if s.endswith(".0") else s
    return "0" if s == "-0" else s


def curves_path(curves, closed: bool) -> str:
    """One stroke as SVG path data (absolute, 1 decimal); a new M only where pieces don't meet."""
    out, last = [], None
    for c in curves:
        a, c1, c2, b = c
        if last is None or np.hypot(*(a - last)) > 0.05:
            out.append(f"M{fmt(a[0])} {fmt(a[1])}")
        cross = lambda u, v: abs(u[0] * v[1] - u[1] * v[0])
        straight = cross(b - a, c1 - a) + cross(b - a, c2 - a)
        if straight / max(np.linalg.norm(b - a), 1e-6) < 0.05:
            out.append(f"L{fmt(b[0])} {fmt(b[1])}")
        else:
            out.append(f"C{fmt(c1[0])} {fmt(c1[1])} {fmt(c2[0])} {fmt(c2[1])} {fmt(b[0])} {fmt(b[1])}")
        last = b
    if closed:
        out.append("Z")
    return "".join(out)


def curves_points(curves, per=16):
    pts = []
    t = np.linspace(0, 1, per)
    for c in curves:
        q = _bez(np.asarray(c), t)
        pts.extend(map(tuple, q if not pts else q[1:]))
    return pts


def stroke_path(p: np.ndarray, closed: bool, corners: set) -> str:
    """One stroke as SVG path data (absolute, 1 decimal): straight runs as L, curves as C."""
    out = [f"M{fmt(p[0][0])} {fmt(p[0][1])}"]
    for a, c1, c2, b in bezier_segments(p, closed, corners):
        # A curve that is really a straight line costs fewer bytes as L.
        cross = lambda u, v: abs(u[0] * v[1] - u[1] * v[0])
        straight = cross(b - a, c1 - a) + cross(b - a, c2 - a)
        if straight / max(np.linalg.norm(b - a), 1e-6) < 0.05:
            out.append(f"L{fmt(b[0])} {fmt(b[1])}")
        else:
            out.append(f"C{fmt(c1[0])} {fmt(c1[1])} {fmt(c2[0])} {fmt(c2[1])} {fmt(b[0])} {fmt(b[1])}")
    if closed:
        out.append("Z")
    return "".join(out)


def dot_path(x: float, y: float, r: float) -> str:
    """A solid dot as a small circle (two arcs); the round stroke fills a small one in."""
    return f"M{fmt(x - r)} {fmt(y)}a{fmt(r)} {fmt(r)} 0 1 0 {fmt(2 * r)} 0a{fmt(r)} {fmt(r)} 0 1 0 {fmt(-2 * r)} 0"


def sample(p: np.ndarray, closed: bool, corners: set, per_seg=12):
    pts = [tuple(p[0])]
    for a, c1, c2, b in bezier_segments(p, closed, corners):
        for t in np.linspace(0, 1, per_seg)[1:]:
            q = (1 - t) ** 3 * a + 3 * (1 - t) ** 2 * t * c1 + 3 * (1 - t) * t ** 2 * c2 + t ** 3 * b
            pts.append(tuple(q))
    return pts


# ---------------------------------------------------------------- pipeline

def to_box(chain, unit, off):
    """(row, col) pixels -> (x, y) box units."""
    a = np.asarray(chain, dtype=float)
    return np.stack([off[0] + a[:, 1] * unit, off[1] + a[:, 0] * unit], axis=1)


def _end_dir(p: np.ndarray, at_start: bool, reach: float):
    """Direction a stroke heads INTO its end, measured over its last `reach` units (past the curl)."""
    q = p if at_start else p[::-1]
    d = np.cumsum(np.r_[0, np.hypot(*np.diff(q, axis=0).T)])
    far = q[min(np.searchsorted(d, reach), len(q) - 1)]
    v = q[0] - far
    n = np.hypot(*v)
    return v / n if n > 1e-6 else None


def snap_ends(strokes, radius, half):
    """Line ends that (almost) meet -> one shared point. Where exactly two lines meet, the
    traced centre lines curl into a point just inside the corner, so each is trimmed back past
    the curl and extended straight to where the two cross: the real, crisp corner."""
    ends = [(si, k) for si, (p, closed) in enumerate(strokes) if not closed for k in (0, -1)]
    done = set()
    for i, (si, ki) in enumerate(ends):
        if (si, ki) in done:
            continue
        group = [(si, ki)] + [(sj, kj) for sj, kj in ends[i + 1:]
                              if (sj, kj) not in done and np.hypot(*(strokes[si][0][ki] - strokes[sj][0][kj])) < radius]
        done.update(group)
        if len(group) == 1:
            continue
        mean = np.mean([strokes[g][0][k] for g, k in group], axis=0)
        corner = None
        if len(group) == 2:
            (sa, ka), (sb, kb) = group
            trim = 2.0 * half
            da = _end_dir(strokes[sa][0], ka == 0, trim + 3 * half)
            db = _end_dir(strokes[sb][0], kb == 0, trim + 3 * half)
            if da is not None and db is not None and abs(da[0] * db[1] - da[1] * db[0]) > 0.25:
                pa, pb = strokes[sa][0][ka], strokes[sb][0][kb]
                # pa + t*da = pb + u*db
                m = np.array([[da[0], -db[0]], [da[1], -db[1]]])
                t, _ = np.linalg.solve(m, pb - pa)
                x = pa + t * da
                if np.hypot(*(x - mean)) < 5 * half + 1:
                    corner = x
        for g, k in group:
            p = strokes[g][0]
            if corner is not None:
                # Drop the curled points near the end, then end exactly at the corner.
                q = p if k == 0 else p[::-1]
                keep = np.hypot(*(q - q[0]).T) > 2.0 * half
                first = int(np.argmax(keep)) if keep.any() else len(q) - 1
                q = np.vstack([corner, q[first:]])
                strokes[g][0] = q if k == 0 else q[::-1]
            else:
                p[k] = mean


def trace_lines(m, unit, off, sigma_units, max_turn):
    sk = skeletonize(m)
    dist = distance_transform_edt(m)
    half_px = float(np.median(dist[sk])) if sk.any() else 1.0
    dots = []
    # Solid dots (eyes) found as whole shapes first: a filled blob's centreline is often a tiny
    # branched cluster, which the chain walk split into short pieces at a junction and dropped
    # as spurs (a fan drawing lost both eyes). A small, compact, filled component is a dot.
    from skimage.measure import label, regionprops
    lab = label(m)
    for r in regionprops(lab):
        h, w = r.bbox[2] - r.bbox[0], r.bbox[3] - r.bbox[1]
        # Round, not just small: a short dash (sound lines) is small too but elongated.
        if max(h, w) <= 8 * half_px and r.solidity > 0.8 and max(h, w) <= 1.6 * min(h, w):
            cy, cx = r.centroid
            rad = max(dist[int(round(cy)), int(round(cx))] * unit * 0.6, 0.6)
            dots.append((off[0] + cx * unit, off[1] + cy * unit, rad))
            sk[lab == r.label] = False
    chains, deg = trace_skeleton(sk)
    # Typical half-thickness of the drawing's lines (box units): a thick corner sprouts a spur
    # toward its outer point about this long, so spurs shorter than ~3x it are pruned.
    half = half_px * unit
    spur = max(2.5, 3.0 * half)
    strokes = []
    for c, closed in chains:
        p = to_box(c, unit, off)
        ends_deg = [deg.get(tuple(e), 0) for e in (c[0], c[-1])]
        if not closed and len(c) > 1 and length(p) < spur and max(ends_deg) >= 3 and min(ends_deg) <= 1:
            continue  # a spur: hangs off a junction, ends in nothing
        if len(c) == 1 or (not closed and length(p) < 2.5):
            # Short: a spur off a line (dropped) or a solid dot on its own, like an eye (kept).
            if len(c) > 1 and any(deg.get(tuple(e), 0) >= 3 for e in (c[0], c[-1])):
                continue
            cy, cx = np.mean(np.asarray(c, float), axis=0)
            r = max(dist[int(round(cy)), int(round(cx))] * unit * 0.6, 0.6)
            dots.append((off[0] + cx * unit, off[1] + cy * unit, r))
            continue
        strokes.append([p, closed])
    snap_ends(strokes, max(2.0, 2.5 * half), half)
    return strokes, dots


def trace_outline(m, unit, off, sigma_units):
    strokes = []
    for c in find_contours(m.astype(float), 0.5):
        p = to_box(c, unit, off)
        if length(p) > 3:
            strokes.append([p, True])
    return strokes, []


def finish(strokes, unit, sigma_units, tol, max_turn):
    """Each raw stroke -> corners found, stretches smoothed + simplified (points are one working
    pixel apart, so a length in box units / unit = that many points)."""
    out = []
    k = max(3, round(2.0 / unit))
    for p, closed in strokes:
        bodies, loop = shape_stroke(p, closed, sigma_units / unit, tol, k, max_turn)
        curves = []
        for b in bodies:
            if len(b) >= 2 and length(b) > 0.2:
                curves += fit_curves(b, tol, closed_loop=loop)
        if curves:
            out.append((curves, loop))
    return out


def preview(src: Image.Image, finished, dots, out_path: str):
    """original | traced (clear) | on the wall (app colours, app line weight)."""
    S, SS = 300, 3  # tile size, supersampling for smooth preview lines
    panel = Image.new("RGB", (S * 3 + 40, S + 20), (20, 24, 32))
    orig = src.convert("RGBA"); orig.thumbnail((S, S))
    bg = Image.new("RGBA", (S, S), (128, 132, 140, 255))  # grey: shows white-on-clear art too
    bg.alpha_composite(orig, ((S - orig.width) // 2, (S - orig.height) // 2))
    panel.paste(bg.convert("RGB"), (10, 10))
    for col, (fill, ink, width) in enumerate([((12, 18, 28), (235, 240, 255), 2.4),
                                               (BACKDROP, EDGE, APP_STROKE * S / APP_SIZE)]):
        big = Image.new("RGB", (S * SS, S * SS), fill)
        d = ImageDraw.Draw(big)
        k = S * SS / BOX
        w = max(1, round(width * SS))
        for curves, closed in finished:
            pts = [(x * k, y * k) for x, y in curves_points(curves)]
            d.line(pts, fill=ink, width=w, joint="curve")
            for x, y in (pts[0], pts[-1]):  # round caps
                d.ellipse([x - w / 2, y - w / 2, x + w / 2, y + w / 2], fill=ink)
        for x, y, r in dots:
            X, Y, R = x * k, y * k, r * k + w / 2
            d.ellipse([X - R, Y - R, X + R, Y + R], fill=ink)
        panel.paste(big.resize((S, S), Image.LANCZOS), (20 + S * (col + 1), 10))
    panel.save(out_path)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("image")
    ap.add_argument("--name", required=True)
    ap.add_argument("--mode", choices=["lines", "outline"], default="lines")
    ap.add_argument("--smooth", type=float, default=0.6)
    ap.add_argument("--corner", type=float, default=50.0)
    ap.add_argument("--detail", type=float, default=0.25, help="curve fit tolerance: max distance from the traced line, box units")
    ap.add_argument("--invert", action="store_true", default=None)
    a = ap.parse_args()

    src = Image.open(a.image)
    m, unit, off = fit(ink_level(src, a.invert))
    m = closing(m, disk(3))
    m = remove_small_objects(m, max_size=40)

    if a.mode == "lines":
        strokes, dots = trace_lines(m, unit, off, a.smooth, a.corner)
    else:
        strokes, dots = trace_outline(m, unit, off, a.smooth)
    finished = finish(strokes, unit, a.smooth, a.detail, a.corner)

    d = "".join(curves_path(curves, closed) for curves, closed in finished)
    d += "".join(dot_path(x, y, r) for x, y, r in dots)
    ident = "".join(w.capitalize() for w in "".join(ch if ch.isalnum() else " " for ch in a.name).split()) or "Symbol"
    print(f'    val {ident} = BootSymbol.fromSvg("{a.name}", "{d}")')
    print(f"[symbol] {len(finished)} strokes + {len(dots)} dots, {len(d.encode())} bytes of path data", file=sys.stderr)
    root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
    out_dir = os.path.join(root, "ui-shots", "symbols"); os.makedirs(out_dir, exist_ok=True)
    out = os.path.join(out_dir, f"{a.name.replace(' ', '-')}.png")
    preview(src, finished, dots, out)
    print(f"[symbol] preview: {out}", file=sys.stderr)


if __name__ == "__main__":
    main()
