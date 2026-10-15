#!/usr/bin/env python3
"""Animated GIF from a UI Lab recording (`record start/stop` writes <name>-frames/: 0000.png …
   + times.txt, the app time of each frame in ms). Plays at the recorded app-time pace, loops,
   and holds the last frame 1 s so the end state is readable before it starts over.
   tools/ui-lab/gif.py ui-shots/<name>-frames [out.gif]      (default: ui-shots/<name>.gif)
   tools/ui-lab/gif.py --all ui-shots                        (every recording whose GIF is missing or stale)"""
import os, subprocess, sys

try:
    from PIL import Image
except ImportError:
    subprocess.run([sys.executable, "-m", "pip", "install", "-q", "pillow"], check=True)
    from PIL import Image


def make(frames_dir, out=None):
    frames_dir = frames_dir.rstrip("/")
    if out is None:
        base = frames_dir[: -len("-frames")] if frames_dir.endswith("-frames") else frames_dir
        out = base + ".gif"
    times = [int(t) for t in open(os.path.join(frames_dir, "times.txt")).read().split()]
    frames = [Image.open(os.path.join(frames_dir, f"{i:04d}.png")).convert("RGB") for i in range(len(times))]
    if not frames:
        return None
    durations = [max(20, b - a) for a, b in zip(times, times[1:])] + [1000]
    frames[0].save(out, save_all=True, append_images=frames[1:], duration=durations, loop=0)
    return out


if __name__ == "__main__":
    args = sys.argv[1:]
    if args and args[0] == "--all":
        root = args[1] if len(args) > 1 else "ui-shots"
        for name in sorted(os.listdir(root)):
            d = os.path.join(root, name)
            stamp = os.path.join(d, "times.txt")
            if not (name.endswith("-frames") and os.path.isfile(stamp)):
                continue
            gif = os.path.join(root, name[: -len("-frames")] + ".gif")
            if not os.path.exists(gif) or os.path.getmtime(gif) < os.path.getmtime(stamp):
                print("[uilab] gif", make(d, gif))
    elif args:
        print("[uilab] gif", make(args[0], args[1] if len(args) > 1 else None))
    else:
        print(__doc__)
