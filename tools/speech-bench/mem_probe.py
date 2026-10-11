"""Memory + speed of one sherpa-onnx model under one ONNX Runtime setting, in a fresh process.

  mem_probe.py <model_dir> <nemo_transducer|canary|online> <settings|-> <clips_dir>

<settings> = provider-config lines joined with ';' (what the app writes to stt/ort-session.cfg),
e.g. "EnableCpuMemArena=0;SessionConfig.session.intra_op.allow_spinning=0", or '-' for plain
"cpu". <clips_dir> holds 0000.wav … (16 kHz mono; fetch_sets.py's sets/clean works). Prints one
JSON line, MB above the process's own baseline: load_peak / after_load, after decoding 2-15 s
(ladder), after 40 mixed sentences (session, = what stays held while idle), after freeing the
model; rtf = decode time / audio time (2 threads, like the app); cpu_per_wall = CPU seconds per
wall second while decoding (2.0 = both threads busy, more = spinning). Results:
results-2026-10-11-memory.txt."""
import gc, json, os, random, resource, sys, time, wave
import numpy as np
import sherpa_onnx

SR = 16000
model_dir, kind, cfg, clips_dir = sys.argv[1:5]

def status(key):
    for line in open("/proc/self/status"):
        if line.startswith(key + ":"):
            return int(line.split()[1]) // 1024  # MB
def reset_peak():
    with open("/proc/self/clear_refs", "w") as f: f.write("5")
def cpu():
    r = resource.getrusage(resource.RUSAGE_SELF); return r.ru_utime + r.ru_stime

def load_wav(p):
    with wave.open(p) as w:
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768
speech = np.concatenate([load_wav(f"{clips_dir}/{i:04d}.wav") for i in range(40)])

provider = "cpu"
if cfg != "-":
    path = os.path.join(os.environ.get("TMPDIR", "/tmp"), f"ortcfg_{os.getpid()}.txt")
    open(path, "w").write("\n".join(cfg.split(";")) + "\n")
    provider = "cpu:" + path

base_rss = status("VmRSS"); reset_peak()
t0 = time.time()
d = model_dir
import glob
def one(pat): return sorted(glob.glob(f"{d}/{pat}"))[0]
if kind == "nemo_transducer":
    rec = sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=one("encoder.int*.onnx"), decoder=one("decoder.int8.onnx"), joiner=one("joiner.int8.onnx"),
        tokens=f"{d}/tokens.txt", num_threads=2, provider=provider, model_type="nemo_transducer",
        decoding_method="greedy_search")
elif kind == "online":
    rec = sherpa_onnx.OnlineRecognizer.from_transducer(
        encoder=one("encoder*.onnx"), decoder=one("decoder*.onnx"), joiner=one("joiner*.onnx"),
        tokens=f"{d}/tokens.txt", num_threads=2, provider=provider, decoding_method="greedy_search")
elif kind == "canary":
    rec = sherpa_onnx.OfflineRecognizer.from_nemo_canary(
        encoder=f"{d}/encoder.int8.onnx", decoder=f"{d}/decoder.int8.onnx", tokens=f"{d}/tokens.txt",
        src_lang="en", tgt_lang="en", num_threads=2, provider=provider)
load_s = time.time() - t0
load_peak = status("VmHWM") - base_rss
after_load = status("VmRSS") - base_rss

def decode(sec, off=0):
    x = speech[off:off + int(sec * SR)]
    if kind == "online":
        s = rec.create_stream(); w0, c0 = time.time(), cpu()
        s.accept_waveform(SR, x); s.accept_waveform(SR, np.zeros(int(0.8 * SR), np.float32)); s.input_finished()
        while rec.is_ready(s): rec.decode_stream(s)
        return time.time() - w0, cpu() - c0, rec.get_result(s)
    s = rec.create_stream(); s.accept_waveform(SR, x)
    w0, c0 = time.time(), cpu()
    rec.decode_stream(s)
    return time.time() - w0, cpu() - c0, s.result.text

reset_peak()
lens = [2, 5, 10, 15]
times = {}
for L in lens:
    wall, c, _ = decode(L)
    times[L] = (wall, c)
after_ladder = status("VmRSS") - base_rss
ladder_peak = status("VmHWM") - base_rss
random.seed(1)
reset_peak()
walls = cpus = audio = 0.0
for i in range(40):  # mixed lengths, like a session of talking
    L = random.choice([1.5, 2, 3, 4, 6, 8, 12, 15])
    wall, c, _ = decode(L, off=random.randint(0, len(speech) - int(16 * SR)))
    walls += wall; cpus += c; audio += L
after_session = status("VmRSS") - base_rss
session_peak = status("VmHWM") - base_rss
del rec; gc.collect(); time.sleep(0.5)
after_free = status("VmRSS") - base_rss
print(json.dumps(dict(cfg=cfg, load_s=round(load_s, 2), load_peak=load_peak, after_load=after_load,
    ladder_peak=ladder_peak, after_ladder=after_ladder, session_peak=session_peak, after_session=after_session,
    after_free=after_free, rtf=round(walls / audio, 4), cpu_per_wall=round(cpus / walls, 2),
    t15=round(times[15][0], 2))))
