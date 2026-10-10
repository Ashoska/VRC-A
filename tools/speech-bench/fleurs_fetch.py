import sys, os, io, json, tarfile, urllib.request, ssl
import numpy as np, soundfile as sf
lang, out, n_want = sys.argv[1], sys.argv[2], int(sys.argv[3])
base = f"https://huggingface.co/datasets/google/fleurs/resolve/main/data/{lang}"
ctx = ssl.create_default_context(cafile="/root/.ccr/ca-bundle.crt") if os.path.exists("/root/.ccr/ca-bundle.crt") else None
def get(u): return urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent": "bench"}), context=ctx, timeout=120)
meta = {}
for line in get(base + "/test.tsv").read().decode("utf-8").splitlines():
    p = line.split("\t")
    if len(p) >= 4: meta[p[1]] = (p[0], p[2], p[3])
d = os.path.join(out, lang); os.makedirs(d, exist_ok=True)
seen, man, scanned = set(), [], 0
with get(base + "/audio/test.tar.gz") as resp:
    with tarfile.open(fileobj=resp, mode="r|gz") as tf:
        for m in tf:
            if not m.isfile() or not m.name.endswith(".wav"): continue
            scanned += 1
            fn = os.path.basename(m.name); info = meta.get(fn)
            if not info or info[0] in seen:
                if scanned > 400: break
                continue
            x, sr = sf.read(io.BytesIO(tf.extractfile(m).read()), dtype="float32")
            if x.ndim > 1: x = x.mean(axis=1)
            if sr != 16000:
                t = np.arange(0, len(x) * 16000 / sr) * sr / 16000; x = np.interp(t, np.arange(len(x)), x).astype("float32"); sr = 16000
            dur = len(x) / sr
            if not (2.5 <= dur <= 16): continue
            seen.add(info[0]); p = os.path.join(d, f"{len(man):04d}.wav"); sf.write(p, x, sr, subtype="PCM_16")
            man.append({"id": fn, "wav": p, "ref": info[1], "ref_norm": info[2], "dur": round(dur, 2), "lang": lang})
            if len(man) >= n_want or scanned > 400: break
json.dump(man, open(os.path.join(d, "manifest.json"), "w"), ensure_ascii=False, indent=1)
print(f"{lang}: {len(man)} clips, {round(sum(m['dur'] for m in man)/60,1)} min", flush=True)
