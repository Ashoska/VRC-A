# Speech bench — how we test voice-to-text models

How the 2026-10 shoot-out chose every voice pack, written so the next round (newer models)
can be run the same way and compared against `results-2026-10.txt`. Runs on a desktop/cloud
Linux box with Python, never in the app. Results and decisions: `docs/systems/voice-to-text.md`.

## The rules we pick by

1. **Accuracy on how people actually talk**, not the model's published score. Published
   numbers come from clean read speech; we test casual conversation and background chatter
   too, because that's where models fell apart (Vosk: 9.6% on audiobooks, 38% on real talk).
2. **Every language gets its own best model**, and the user picks the language, so a model
   can never "detect" the wrong one. Multilingual models that auto-detect are checked for
   wrong-language output and only used for languages where they never did it.
3. **It must run on a Quest CPU next to VRChat**: speed and RAM are measured, not guessed.
4. **Tiers**: per language Large / Medium / Small ("Standard" when there's one). Large = the most
   accurate; each lower tier must be a different, smaller model (less download AND less RAM;
   Dolphin base is the one exception: far smaller download at about SenseVoice's RAM) that is
   still ≤ ~25% wrong. A language only gets the tiers a decent smaller model exists for.
5. **Ship exactly what was tested**: the app downloads single files from a pinned Hugging
   Face revision and checks SHA-256, so test those same files with the same sherpa-onnx
   version as the app's AAR.

## Steps

```bash
# 0. Environment (keep sherpa_onnx == the AAR version in app/build.gradle)
tools/speech-bench/setup.sh ~/speech-bench            # → ~/speech-bench/venv
W=~/speech-bench; PY="$W/venv/bin/python -I"

# 1. Test audio (pinned sources)
$PY tools/speech-bench/fetch_sets.py $W               # English: clean / casual / casual_norm / chatter
for l in en_us ru_ru cmn_hans_cn ja_jp ...; do         # FLEURS test split, 20+ clips per language
  $PY tools/speech-bench/fleurs_fetch.py $l $W/data/fleurs 24; done

# 2. Candidate models (exact files + revision; prints sha256 + size for SpeechCatalog)
tools/speech-bench/fetch_model.sh $W/models/g3s-ctc hf \
  csukuangfj/sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16 4fb5407f... model.int8.onnx tokens.txt

# 3. Jobs, side by side (one line per engine/language group; split big ones: name-1, name-2)
cat > $W/jobs.txt <<'J'
g3s-ctc|nemo_ctc|/root/speech-bench/models/g3s-ctc|ru_ru
pk3-1|parakeet|/root/speech-bench/models/pk3|es_419,pt_br,ru_ru,it_it
pk2en|parakeet|/root/speech-bench/models/pk2en|en_us,set:casual_norm,set:chatter,set:clean
J
MAX_JOBS=3 tools/speech-bench/queue.sh $W $W/jobs.txt  # results/<name>.json, logs/<name>.log

# 4. Score
$PY tools/speech-bench/score.py $W/results
```

`run.py` lists the engine kinds; a new model family = one `elif kind == ...` that builds
`tr(x, iso)` (copy the nearest sherpa one). Prefer sherpa-onnx kinds — that's what the app
runs; onnx-asr / faster-whisper candidates only tell us whether a model is worth porting.

## What the numbers mean and the cut-offs we used

- **% wrong** = word error rate (characters for Chinese, Cantonese, Japanese, Korean, Thai),
  after Whisper's normalisers. 20 FLEURS clips per language ≈ ±2–3 points, so differences
  under ~2 points are a tie: pick the lighter/faster model.
- **Quality label** in the picker: the app derives it from accuracy = 100 − % wrong (rounded):
  Excellent ≥ 98, Great ≥ 92, Good ≥ 88, OK ≥ 82, else Experimental. A language is offered
  only if its best model is ≤ 18% wrong (Arabic 19.0, Malay/Persian 19.8 and Hebrew 40 were
  left out); a lower tier may reach ~25% (shows as Experimental).
- **Wrong-language %** (score.py, European languages): how often the output is in another
  language, minus the detector's own error on the correct text. Anything ≥ 5 points means the
  model guesses languages → only offer it where it stays ~0 (Parakeet-25 wrote Slovenian 40%
  of the time in another language, so it only serves es/pt/de/it/bg/pl/uk/nl, where it didn't).
- **RTF** (processing ÷ audio time, 1 thread, desktop x86): the Quest runs ~2× slower per
  core and the app uses 2 threads, so roughly: RTF ≤ 0.4 → fine, 0.4–0.8 → noticeable delay
  per sentence, ≥ 1 → too slow for live chat (Whisper turbo ~2, because Whisper always
  encodes a 30 s window even for a 3 s phrase).
- **RAM** (`rss_end_mb`): VRChat needs most of the Quest's memory; ≤ ~1 GB per pack is the
  comfortable limit, ~1.7–2 GB only for a clearly better Large tier. Note: engines that build
  one recognizer per language in the bench (canary180, sensevoice) report the SUM — measure
  one language alone for the real figure (they warm up in the job's first language, so a
  one-language job holds one recognizer).
- **Speed/RAM on the headset** are the final word: check the dictation row's "last took X s"
  with a new pack before calling it done.

## Shipping a winner

1. `SpeechCatalog`: a `Pack` (files from the pinned revision with exact size + SHA-256 —
   `fetch_model.sh hf` prints both; non-LFS files like `tokens.txt` too; `ramMb` from the
   bench; `slow = true` if it's near real time) and the language's `Tier(packId, label,
   errorPct)`s biggest-first with the measured FLEURS % wrong (the app derives the label).
2. New model family → a `Kind` + its `PhraseDecoder` branch in `SpeechToText` (headset
   flavor), configured exactly like the bench's sherpa call (feature dim, model type,
   language settings).
3. Compile all three flavors, check the picker in the UI Lab, update
   `docs/systems/voice-to-text.md` (tiers + shoot-out lines) and this folder's results file.
4. A pack the catalog drops is deleted from devices automatically (`SpeechPacks.cleanupLegacy`).

## Ideas for the next round

Canary-1B-v2 in sherpa format (won 21/25 European languages; ~1.9 GB RAM), a Korean model
under 1 GB that beats Whisper small's 4.0%, Hebrew, faster "other languages" than
Omnilingual-1B, a single-file build of the Thai zipformer (7.9, beats Omnilingual-1B's 9.3),
and models with token timestamps (live words lock in only with timestamps; Canary and
Whisper re-read the whole sentence).
