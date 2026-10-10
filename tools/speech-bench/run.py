"""One bench job: transcribe test clips with one engine and record text, speed and RAM.

  run.py <name> <kind> <model_dir> <lang,lang,set:casual_norm,...> <out.json>
  env: FLEURS_DIR, SETS_DIR (queue.sh sets them), BENCH_LIMIT (clips per FLEURS language, 20),
       BENCH_THREADS (1: compare engines per core; the app uses 2)

Langs are FLEURS codes (en_us, ru_ru, cmn_hans_cn, ...) or set:<name> from fetch_sets.py.
Kinds (sherpa-onnx = what the app runs, so prefer those): parakeet / gigaam (NeMo transducer),
nemo_ctc (GigaAM v3), canary180, zipformer (offline transducer), streaming / streaming_ctc
(streaming zipformers: Kroko, small Russian/Chinese), sherpa_whisper, sensevoice, dolphin, omni
(Omnilingual CTC), paraformer, qwen3; non-sherpa reference engines: whisper (faster-whisper),
canary1b + gigaam3 (onnx-asr), moonshine2. Add a kind = one elif building `tr(x, iso)`.
Output: per language the RTF (processing time / audio time; > 1 = slower than real time),
every hypothesis next to its reference, load time, RAM after load and peak.
"""
import sys, os, json, time, glob, resource
import numpy as np, soundfile as sf, psutil

engine, kind, mdir, langs_csv, out_path = sys.argv[1:6]
FLEURS = os.environ["FLEURS_DIR"]; SETS = os.environ.get("SETS_DIR", "")
limit = int(os.environ.get("BENCH_LIMIT", "0")); threads = int(os.environ.get("BENCH_THREADS", "1"))
ISO = {"en_us": "en", "es_419": "es", "fr_fr": "fr", "de_de": "de", "it_it": "it", "pt_br": "pt", "ru_ru": "ru",
       "uk_ua": "uk", "pl_pl": "pl", "nl_nl": "nl", "sk_sk": "sk", "cs_cz": "cs", "ro_ro": "ro", "hr_hr": "hr",
       "bg_bg": "bg", "fi_fi": "fi", "sv_se": "sv", "hu_hu": "hu", "et_ee": "et", "da_dk": "da", "lt_lt": "lt",
       "mt_mt": "mt", "el_gr": "el", "lv_lv": "lv", "sl_si": "sl", "cmn_hans_cn": "zh", "yue_hant_hk": "yue",
       "ja_jp": "ja", "ko_kr": "ko", "th_th": "th", "vi_vn": "vi", "id_id": "id", "ms_my": "ms", "hi_in": "hi",
       "fil_ph": "tl", "ar_eg": "ar", "tr_tr": "tr", "fa_ir": "fa", "he_il": "he"}
proc = psutil.Process()
def rss(): return proc.memory_info().rss / 1048576
def find(*pats):
    for p in pats:
        h = sorted(glob.glob(os.path.join(mdir, "**", p), recursive=True))
        if h: return h[0]
    raise SystemExit(f"missing {pats} in {mdir}")
def nonint8(pat):
    h = [p for p in sorted(glob.glob(os.path.join(mdir, "**", pat), recursive=True)) if "int8" not in p]
    return h[0] if h else find(pat)

base = rss(); t0 = time.time(); cache = {}
import sherpa_onnx as so
def sherpa_text(rec, x):
    s = rec.create_stream(); s.accept_waveform(16000, x); rec.decode_stream(s); return s.result.text

if kind == "parakeet":
    R = so.OfflineRecognizer.from_transducer(encoder=find("encoder*int8.onnx"), decoder=find("decoder*int8.onnx"),
        joiner=find("joiner*int8.onnx"), tokens=find("tokens.txt"), num_threads=threads, model_type="nemo_transducer")
    def tr(x, iso): return sherpa_text(R, x)
elif kind == "gigaam":
    R = so.OfflineRecognizer.from_transducer(encoder=find("encoder*int8.onnx", "encoder*.onnx"), decoder=find("decoder*int8.onnx", "decoder*.onnx"),
        joiner=find("joiner*int8.onnx", "joiner*.onnx"), tokens=find("tokens.txt"), num_threads=threads, model_type="nemo_transducer")
    def tr(x, iso): return sherpa_text(R, x)
elif kind == "zipformer":
    R = so.OfflineRecognizer.from_transducer(encoder=find("encoder*int8.onnx", "encoder*.onnx"), decoder=nonint8("decoder*.onnx"),
        joiner=find("joiner*int8.onnx", "joiner*.onnx"), tokens=find("tokens.txt"), num_threads=threads)
    def tr(x, iso): return sherpa_text(R, x)
elif kind == "canary180":
    def tr(x, iso):
        if iso not in cache:
            cache[iso] = so.OfflineRecognizer.from_nemo_canary(encoder=find("encoder*int8.onnx"), decoder=find("decoder*int8.onnx"),
                tokens=find("tokens.txt"), src_lang=iso, tgt_lang=iso, num_threads=threads)
        return sherpa_text(cache[iso], x)
    tr(np.zeros(16000, dtype=np.float32), "en")
elif kind == "sensevoice":
    def tr(x, iso):
        lang = iso if iso in ("zh", "en", "ja", "ko", "yue") else "auto"
        if lang not in cache:
            cache[lang] = so.OfflineRecognizer.from_sense_voice(model=find("model.int8.onnx", "model*.onnx"), tokens=find("tokens.txt"),
                num_threads=threads, language=lang, use_itn=True)
        return sherpa_text(cache[lang], x)
    tr(np.zeros(16000, dtype=np.float32), "zh")
elif kind == "nemo_ctc":
    R = so.OfflineRecognizer.from_nemo_ctc(model=find("model.int8.onnx", "model*.onnx"), tokens=find("tokens.txt"), num_threads=threads)
    def tr(x, iso): return sherpa_text(R, x)
elif kind in ("streaming", "streaming_ctc"):
    # Streaming models, fed one phrase at a time like the app's ONLINE_* decoders
    # (whole phrase + 0.8 s of silence so the look-ahead flushes, then input_finished).
    if kind == "streaming":
        R = so.OnlineRecognizer.from_transducer(tokens=find("tokens.txt"), encoder=find("encoder*int8.onnx", "encoder*.onnx"),
            decoder=nonint8("decoder*.onnx"), joiner=find("joiner*int8.onnx", "joiner*.onnx"), num_threads=threads)
    else:
        R = so.OnlineRecognizer.from_zipformer2_ctc(tokens=find("tokens.txt"), model=find("model*.onnx"), num_threads=threads)
    tail = np.zeros(int(16000 * 0.8), dtype=np.float32)
    def tr(x, iso):
        s = R.create_stream(); s.accept_waveform(16000, x); s.accept_waveform(16000, tail); s.input_finished()
        while R.is_ready(s): R.decode_stream(s)
        return R.get_result(s)
elif kind == "sherpa_whisper":
    # Whisper through sherpa (what the app would run); language set explicitly.
    def tr(x, iso):
        # tiny/base/small predate Whisper's "yue": Cantonese goes as Chinese (an unknown
        # language aborts sherpa natively, killing the whole job).
        lang = {"yue": "zh", "fil": "tl"}.get(iso, iso)
        if lang not in cache:
            cache.clear()  # one recognizer at a time: RAM = a single language's
            cache[lang] = so.OfflineRecognizer.from_whisper(encoder=find("*encoder.int8.onnx"), decoder=find("*decoder.int8.onnx"),
                tokens=find("*tokens.txt"), language=lang, task="transcribe", num_threads=threads)
        return sherpa_text(cache[lang], x)
elif kind == "dolphin":
    R = so.OfflineRecognizer.from_dolphin_ctc(model=find("model.int8.onnx", "model*.onnx"), tokens=find("tokens.txt"), num_threads=threads)
    def tr(x, iso): return sherpa_text(R, x)
elif kind == "omni":
    R = so.OfflineRecognizer.from_omnilingual_asr_ctc(model=find("model.int8.onnx", "model*.onnx"), tokens=find("tokens.txt"), num_threads=threads)
    def tr(x, iso): return sherpa_text(R, x)
elif kind == "paraformer":
    R = so.OfflineRecognizer.from_paraformer(paraformer=find("model.int8.onnx", "model*.onnx"), tokens=find("tokens.txt"), num_threads=threads)
    def tr(x, iso): return sherpa_text(R, x)
elif kind == "qwen3":
    R = so.OfflineRecognizer.from_qwen3_asr(conv_frontend=find("conv_frontend*.onnx"), encoder=find("encoder*.onnx"),
        decoder=find("decoder*.onnx"), tokenizer=os.path.join(mdir, "tokenizer"), num_threads=threads)
    def tr(x, iso): return sherpa_text(R, x)
elif kind == "gigaam3":
    import onnx_asr, onnxruntime as rt
    so_ = rt.SessionOptions(); so_.intra_op_num_threads = threads; so_.inter_op_num_threads = 1
    M = onnx_asr.load_model("gigaam-v3-e2e-ctc", mdir, quantization="int8", sess_options=so_)
    def tr(x, iso): return M.recognize(x, sample_rate=16000)
elif kind == "canary1b":
    import onnx_asr, onnxruntime as rt
    so_ = rt.SessionOptions(); so_.intra_op_num_threads = threads; so_.inter_op_num_threads = 1
    M = onnx_asr.load_model("nemo-canary-1b-v2", mdir, quantization="int8", sess_options=so_)
    def tr(x, iso): return M.recognize(x, sample_rate=16000, language=iso)
elif kind == "whisper":
    from faster_whisper import WhisperModel
    W = WhisperModel(mdir, device="cpu", compute_type="int8", cpu_threads=threads, num_workers=1)
    def tr(x, iso):
        lang = {"yue": "zh"}.get(iso, iso)
        segs, _ = W.transcribe(x, language=lang, beam_size=1, condition_on_previous_text=False, without_timestamps=True, vad_filter=False)
        return " ".join(s.text.strip() for s in segs)
elif kind == "moonshine2":
    if threads == 1: os.environ["MOONSHINE_ORT_SINGLE_THREAD"] = "1"
    from moonshine_voice import Transcriber, ModelArch
    def tr(x, iso):
        if iso not in cache:
            d = sorted(glob.glob(os.path.join(mdir, "**", f"*-streaming-{iso}", "quantized*"), recursive=True))
            if not d: raise SystemExit(f"no moonshine model for {iso}")
            arch = ModelArch[os.path.basename(os.path.dirname(d[0])).split("-streaming")[0].upper() + "_STREAMING"]
            cache[iso] = Transcriber(d[0], arch, update_interval=0.5)
        s = cache[iso].create_stream(update_interval=0.5); s.start()
        for i in range(0, len(x), 8000): s.add_audio(x[i:i+8000].tolist(), 16000)
        r = s.stop()
        return " ".join(l.text.strip() for l in (r.lines if r else []) if l.text.strip())
else:
    raise SystemExit("unknown kind " + kind)

load_s = round(time.time() - t0, 2); load_rss = round(rss() - base, 1)
out = {"engine": engine, "kind": kind, "threads": threads, "load_s": load_s, "rss_after_load_mb": load_rss, "langs": {}}
for code in langs_csv.split(","):
    if code.startswith("set:"):
        name = code[4:]; man = json.load(open(os.path.join(SETS, name, "manifest.json"))); iso = "en"
    else:
        p = os.path.join(FLEURS, code, "manifest.json")
        if not os.path.exists(p): print("skip (no data)", code, flush=True); continue
        man = json.load(open(p)); iso = ISO[code]
    if limit and not code.startswith("set:"): man = man[:limit]
    hyps, ps, au = [], 0.0, 0.0
    for m in man:
        x, sr = sf.read(m["wav"], dtype="float32")
        t = time.time()
        try: h = tr(x, iso)
        except SystemExit: raise
        except Exception as e: h = ""; print("ERR", code, type(e).__name__, str(e)[:120], flush=True)
        ps += time.time() - t; au += m["dur"]
        hyps.append({"id": m["id"], "ref": m["ref"], "ref_norm": m.get("ref_norm", ""), "hyp": h})
    out["langs"][code] = {"iso": iso, "rtf": round(ps / max(au, 1e-9), 4), "audio_s": round(au, 1), "hyps": hyps}
    print(engine, code, "rtf", out["langs"][code]["rtf"], flush=True)
    json.dump(out, open(out_path, "w"), ensure_ascii=False)
out["peak_rss_mb"] = round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024, 1)
out["rss_end_mb"] = round(rss() - base, 1)
json.dump(out, open(out_path, "w"), ensure_ascii=False)
print("DONE", engine, "load", load_s, "s, RAM", out["rss_end_mb"], "MB, peak", out["peak_rss_mb"], "MB", flush=True)
