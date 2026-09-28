#!/usr/bin/env python3
"""Contact sheet: many UI Lab shots in one labelled image (cheaper to look at than one by one).
   tools/ui-lab/sheet.py OUT.png shot1.png shot2.png ... [--cols 4] [--width 400]"""
import sys, os, subprocess
try:
    from PIL import Image, ImageDraw
except ImportError:
    subprocess.run([sys.executable, "-m", "pip", "install", "-q", "pillow"], check=True)
    from PIL import Image, ImageDraw
args = sys.argv[1:]
cols, width = 4, 400
if "--cols" in args: i = args.index("--cols"); cols = int(args[i + 1]); del args[i:i + 2]
if "--width" in args: i = args.index("--width"); width = int(args[i + 1]); del args[i:i + 2]
out, files = args[0], args[1:]
thumbs = []
for f in files:
    im = Image.open(f).convert("RGB")
    im = im.resize((width, int(im.height * width / im.width)))
    thumbs.append((os.path.splitext(os.path.basename(f))[0], im))
rows = (len(thumbs) + cols - 1) // cols
cell_h = max(t.height for _, t in thumbs) + 24
sheet = Image.new("RGB", (cols * (width + 8), rows * cell_h), (40, 40, 40))
d = ImageDraw.Draw(sheet)
for n, (name, im) in enumerate(thumbs):
    x, y = (n % cols) * (width + 8), (n // cols) * cell_h
    d.text((x + 4, y + 4), name, fill=(255, 255, 255))
    sheet.paste(im, (x, y + 20))
sheet.save(out)
print(out, sheet.size)
