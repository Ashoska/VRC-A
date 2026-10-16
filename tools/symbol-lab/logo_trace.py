#!/usr/bin/env python3
# Logo -> vector drawable: traces a white-on-transparent PNG (source/vrca_logo_bar.png, the logo cropped to its drawing) into a filled
# Android <vector> so the logo stays sharp at any size. Needs `pip install potracer`.
#   python3 tools/symbol-lab/logo_trace.py tools/symbol-lab/source/vrca_logo_bar.png \
#           app/src/main/res/drawable/vrca_logo_vector.xml
import sys, numpy as np, potrace
from PIL import Image
src, out = sys.argv[1], sys.argv[2]
S = 6  # trace at 6x so curves follow the strokes closely
im = Image.open(src).convert('RGBA')
W, H = im.size
a = im.getchannel('A').resize((W*S, H*S), Image.LANCZOS)
# potracer traces the FALSE pixels as ink, so the mask is inverted here.
bm = potrace.Bitmap(np.array(a) <= 127)
plist = bm.trace(turdsize=8, alphamax=1.0, opticurve=True, opttolerance=0.2)
def f(v): return f"{v/S:.1f}".rstrip("0").rstrip(".")
parts = []
for curve in plist:
    s = curve.start_point
    d = [f"M{f(s.x)},{f(s.y)}"]
    for seg in curve.segments:
        if seg.is_corner:
            d.append(f"L{f(seg.c.x)},{f(seg.c.y)}L{f(seg.end_point.x)},{f(seg.end_point.y)}")
        else:
            d.append(f"C{f(seg.c1.x)},{f(seg.c1.y)} {f(seg.c2.x)},{f(seg.c2.y)} {f(seg.end_point.x)},{f(seg.end_point.y)}")
    d.append("Z")
    parts.append("".join(d))
path = "".join(parts)
xml = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- The VRC-A logo as a vector (traced from tools/symbol-lab/source/vrca_logo_bar.png by tools/symbol-lab/logo_trace.py), so it
     stays sharp at any size: the headset panel zooms when its window is resized. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{W/4:.1f}dp"
    android:height="{H/4:.1f}dp"
    android:viewportWidth="{W}"
    android:viewportHeight="{H}">
    <path
        android:fillColor="#FFFFFFFF"
        android:fillType="evenOdd"
        android:pathData="{path}" />
</vector>
'''
open(out, 'w').write(xml)
print(len(plist), "curves", len(path), "chars")
