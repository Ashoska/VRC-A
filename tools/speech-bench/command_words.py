"""Command-word test: each word spoken by N synthetic voices (Piper en_US-libritts_r, 900 speakers,
   varied speed, light noise) through Parakeet EN; prints how often the model writes it exactly and
   its common mishearings. Usage: command_words.py <bench dir with pk/ and voices/> <w1,w2,...> <N>"""
import sys, wave, io, random, collections, json
import numpy as np, sherpa_onnx as so
from piper import PiperVoice
from piper.config import SynthesisConfig
W = sys.argv[1]; words = sys.argv[2].split(","); n = int(sys.argv[3])
voice = PiperVoice.load(f"{W}/voices/en_US-libritts_r-medium.onnx")
rec = so.OfflineRecognizer.from_transducer(encoder=f"{W}/pk/encoder.int8.onnx", decoder=f"{W}/pk/decoder.int8.onnx",
    joiner=f"{W}/pk/joiner.int8.onnx", tokens=f"{W}/pk/tokens.txt", num_threads=4, model_type="nemo_transducer")
nspk = voice.config.num_speakers
rng = random.Random(7)
def norm(t): return "".join(c for c in t.lower() if c.isalnum())
def resample(x, sr):  # linear to 16 kHz
    if sr == 16000: return x
    idx = np.arange(0, len(x), sr / 16000); return np.interp(idx, np.arange(len(x)), x).astype(np.float32)
out = {}
for w in words:
    heard = collections.Counter()
    for i in range(n):
        spk = rng.randrange(nspk); speed = rng.uniform(0.85, 1.25)
        cfg = SynthesisConfig(speaker_id=spk, length_scale=speed, noise_scale=rng.uniform(0.5, 0.9))
        x = np.concatenate([c.audio_float_array for c in voice.synthesize(w + ".", syn_config=cfg)])
        x = resample(x, voice.config.sample_rate)
        x = np.concatenate([np.zeros(4000, np.float32), x, np.zeros(4000, np.float32)])
        x = x / (np.abs(x).max() + 1e-9) * rng.uniform(0.1, 0.5) + np.random.RandomState(i).normal(0, 0.003, len(x)).astype(np.float32)
        s = rec.create_stream(); s.accept_waveform(16000, x); rec.decode_stream(s)
        heard[norm(s.result.text) or "(nothing)"] += 1
    ok = heard[norm(w)]
    out[w] = (ok, heard.most_common(4))
    print(f"{w:10s} {ok:3d}/{n}  {heard.most_common(4)}", flush=True)
