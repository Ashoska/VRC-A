"""English test sets (pinned): clean read speech, real casual talk, talk under background chatter.

  python -I fetch_sets.py <work_dir>        → <work_dir>/data/sets/{clean,casual,casual_norm,chatter}

Sources: Hugging Face's Open ASR Leaderboard test data (hf-audio/open-asr-leaderboard, pinned rev):
  librispeech/test.clean   → "clean"  : 100 read utterances (3-12 s), evenly spaced
  ami/test-00005-of-00015  → "casual" : 150 real meeting utterances (headset mics, >= 3 words)
  "casual_norm" = casual normalised to -24 dBFS (AMI is very quiet; the app normalises phrases too)
  "chatter"     = the clean clips + 3 other LibriSpeech speakers underneath at 10 dB SNR (babble)
"""
import sys, io, json, os, random, ssl, urllib.request
import numpy as np, soundfile as sf, pyarrow.parquet as pq

work = sys.argv[1]
REV = "de79e57adf9983993bcc72586d7313460825f5eb"
FILES = {"librispeech": "librispeech/test.clean-00000-of-00001.parquet", "ami": "ami/test-00005-of-00015.parquet"}
SR = 16000
ca = "/root/.ccr/ca-bundle.crt"
ctx = ssl.create_default_context(cafile=ca) if os.path.exists(ca) else None
raw = os.path.join(work, "data", "raw"); out = os.path.join(work, "data", "sets")
os.makedirs(raw, exist_ok=True)

def fetch(path):
    dst = os.path.join(raw, os.path.basename(path))
    if not os.path.exists(dst):
        url = f"https://huggingface.co/datasets/hf-audio/open-asr-leaderboard/resolve/{REV}/{path}"
        print("downloading", url, flush=True)
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "bench"}), context=ctx, timeout=600) as r, open(dst + ".part", "wb") as f:
            while (b := r.read(1 << 20)): f.write(b)
        os.replace(dst + ".part", dst)
    return pq.read_table(dst).to_pylist()

def decode(r):
    a, sr = sf.read(io.BytesIO(r["audio"]["bytes"]), dtype="float32")
    if a.ndim > 1: a = a.mean(axis=1)
    assert sr == SR, sr
    return a

def even(seq, n):
    if len(seq) <= n: return list(seq)
    step = len(seq) / n
    return [seq[int(i * step)] for i in range(n)]

def write_set(name, items):
    d = os.path.join(out, name); os.makedirs(d, exist_ok=True); man = []
    for i, (rid, pcm, ref) in enumerate(items):
        p = os.path.join(d, f"{i:04d}.wav")
        sf.write(p, np.clip(pcm, -1, 1), SR, subtype="PCM_16")
        man.append({"id": rid, "wav": p, "ref": ref, "dur": round(len(pcm) / SR, 3)})
    json.dump(man, open(os.path.join(d, "manifest.json"), "w"), indent=1)
    print(name, len(man), "utts", round(sum(m["dur"] for m in man) / 60, 1), "min", flush=True)

L = fetch(FILES["librispeech"])
clean = even([r for r in L if 3.0 <= r["audio_length_s"] <= 12.0], 100)
write_set("clean", [(r["id"], decode(r), r["text"]) for r in clean])

A = fetch(FILES["ami"])
amis = even([r for r in A if len(r["text"].split()) >= 3], 150)
casual = [(r["id"], decode(r), r["text"]) for r in amis]
write_set("casual", casual)
norm = []
for rid, x, ref in casual:
    y = x * (10 ** (-24 / 20) / (np.sqrt(np.mean(x ** 2)) + 1e-9))
    norm.append((rid, y / max(1.0, np.max(np.abs(y)) / 0.95), ref))
write_set("casual_norm", norm)

rng = random.Random(7)
others = [r for r in L if r["id"] not in {c["id"] for c in clean}]
items = []
for r in clean:
    tgt = decode(r); spk = r["id"].split("-")[0]; bab = np.zeros_like(tgt)
    for o in [o for o in rng.sample(others, 12) if o["id"].split("-")[0] != spk][:3]:
        x = decode(o); x = np.tile(x, int(np.ceil(len(tgt) / len(x))) + 1)
        off = rng.randrange(0, len(x) - len(tgt)); x = x[off:off + len(tgt)]
        bab += x / (np.sqrt(np.mean(x ** 2)) + 1e-9)
    bab *= np.sqrt(np.mean(tgt ** 2) / ((np.mean(bab ** 2) + 1e-12) * 10 ** (10 / 10)))
    mix = tgt + bab; mix /= max(1.0, np.max(np.abs(mix)) / 0.95)
    items.append((r["id"], mix.astype("float32"), r["text"]))
write_set("chatter", items)
