# Voice-to-text dictation (headset, offline)

Hands-free dictation into the Manual Send field — built first for **mute players to talk without speaking aloud in-game**. **Headset-only**: the engine is a `headsetAppImplementation` dependency, so public/admin ship stubs (`SUPPORTED = false`) and the UI hides itself. Fully on-device: no audio leaves the headset, no API cost.

## How it works
- **Engine: sherpa-onnx** (`com.vrca.speech.SpeechToText`, headset flavor). One native engine (`libsherpa-onnx-jni.so` + `libonnxruntime.so`) runs every model family we use, so adding a language = adding a downloadable pack, not code.
- **Phrase-based, not word-by-word.** Capture thread: `AudioRecord` (16 kHz mono, `VOICE_RECOGNITION`) → slow AGC (gain 1–8×, so quiet Quest mics still trigger detection) → **Silero VAD** (`minSilenceDuration` 0.5 s ends a phrase, `maxSpeechDuration` 15 s splits monologues). Each finished phrase goes on a queue; a separate decode thread **normalises its loudness to ~-24 dBFS** (quiet audio badly hurt some engines in testing) and recognises it. The capture thread never blocks, so no speech is lost while a phrase decodes. On Stop the VAD is flushed and the decoder drains the queue before exiting (a `captureDone` flag — exiting on `running=false` alone lost the last phrase).
- **Field integration** (`VrcaViewModel.startDictation`/`appendSpeechPhrase`): dictation forces Live + Scroll on; each phrase is **appended to the CURRENT field text** via `onMessageTextChange` (so scroll windowing applies). It deliberately does not keep its own transcript — the Live hold expiry clears the field, and a remembered transcript would resurrect old text with the next phrase.
- Listener callbacks: `onFinal(phrase)`, `onError`, `onSpeechActive` (UI "Hearing you…"), `onLoading` (model load, a few seconds). The recognizer (~1 GB for Parakeet) is loaded on mic tap and released on Stop — zero RAM when idle.

## Language packs (`SpeechCatalog`, `SpeechPacks` — shared code)
- `SpeechCatalog.languages` maps each language → pack id + measured quality label (Great/Good/OK/Experimental). `packs` lists files, each **pinned to an exact upstream revision** (Hugging Face `resolve/<commit>/…`) with exact **size + SHA-256**. The Silero VAD (0.6 MB) is a shared pack downloaded with the first language.
- `SpeechPacks.downloadLanguage`: one download at a time, checks free space first (+50 MB margin), **resumes** from `<file>.part` via HTTP Range (redirects followed by hand so Range survives the hop to the CDN; a 200 reply to a ranged request restarts the file), verifies SHA-256, and only then writes the `.ok` marker. Installed = `.ok` exists AND every file has its exact size. Location: `filesDir/stt/<packId>/` (not cacheDir — the OS may wipe cache). `deletePack` removes a pack and the VAD once no language pack is left. `cleanupLegacy` deletes the old Vosk model dirs (`filesDir/vosk-model*`) from the first build.
- Selected language: `vrca_speech` prefs (`lang`); default = device language if offered, else English.
- Phase 1 languages: English (Parakeet EN v2, 661 MB), Spanish/Portuguese/Russian (Parakeet-25 v3, 670 MB, one download covers all three).

## UI
- `HomePage.SpeechDictationRow` (inside `ManualSendCard`, under the Scroll toggle): not installed → **Install** (opens picker); downloading → % + progress bar + Cancel; ready → language chip (opens picker) + mic/stop. Requests `RECORD_AUDIO` on first mic tap.
- `SpeechLanguageDialog`: same look as the timezone picker (search, rows with native + English name, quality chip, size or "Installed", device-language hint) + model credits footer (Parakeet is CC-BY-4.0 → attribution required).
- Settings → App → one row per installed pack with size + Delete (confirm dialog).

## Build / packaging notes
- Dependency: official AAR `com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8@aar` from **JitPack**, declared in `settings.gradle` as `exclusiveContent` filtered to that group (JitPack can't serve anything else). The top-level `com.github.k2-fsa:sherpa-onnx` JitPack artifact also drags desktop/JVM natives — do not use it.
- Headset flavor: `ndk { abiFilters "arm64-v8a" }` (every Quest is arm64; strips the other ABIs' engine copies). `packaging.jniLibs` excludes `libsherpa-onnx-c-api.so` / `-cxx-api.so` (JNI lib needs only `libonnxruntime.so`, verified with readelf; ~2 MB saved). Headset manifest keeps `extractNativeLibs="true"` so the ~25 MB of native libs are stored compressed in the APK.
- ProGuard: `-keep class com.k2fsa.sherpa.onnx.** { *; }` — the JNI layer reads config fields **by name**; R8 renaming them breaks release builds.
- Kotlin API used: `OfflineRecognizer(config = OfflineRecognizerConfig(featConfig, modelConfig = OfflineModelConfig(transducer = OfflineTransducerModelConfig(encoder, decoder, joiner), tokens, numThreads, modelType = "nemo_transducer"), decodingMethod))`, `createStream/acceptWaveform/decode/getResult/release`; `Vad(config = VadModelConfig(SileroVadModelConfig(...)))` with `acceptWaveform/empty/front/pop/isSpeechDetected/flush`.
- Planned size trim (later): a reduced-operator ONNX Runtime build (~3–5 MB instead of ~7.6 MB compressed).

## Known limits / next steps
- **Background**: Android silences the mic for backgrounded apps unless a `microphone`-type foreground service was started while the app was on screen. Not built yet (pending decision). Even with it, a game in front that holds the mic (e.g. VRChat voice) may still win the mic.
- RAM on Quest 3 with VRChat running must be confirmed on device (Parakeet ~1 GB; Canary-1B ~1.9 GB, Omnilingual-1B ~1.7 GB for later packs).

## Engine shoot-out (Oct 2026) — why these models
Desktop CPU, same audio for every engine; % = words wrong (characters for zh/yue/ja/ko/th).
- **English, real conversation (AMI headset mics) / with 3 background talkers / clean read speech:** Vosk small 38.4 / 47.3 / 9.6 · Vosk 128 MB lgraph 29.7 / 40.2 / 6.8 (and only ~1× real time — too slow) · Vosk 1.8 GB 26.2 / 33.9 / 5.2 (**5 GB RAM**) · Kroko 22.5 / 7.7 / 5.4 · Moonshine Medium (live) 20.0 / 13.2 / 2.2 · Whisper small 18.6 / 7.6 / 3.3 · **Parakeet EN 14.2 / 4.0 / 1.6** · Granite 1B 13.0 / 2.4 (same subset: Parakeet 15.7 / 3.4) but slower than real time on CPU + 2 GB RAM.
- Vosk's published numbers are real (measured 9.6 vs claimed 9.85 on audiobooks) but it collapses on casual speech and background chatter — the old design, not its size, is the problem.
- **25 European languages (FLEURS, 20 sentences each, ±2–3 pts):** Canary-1B (language is specified → cannot confuse languages) won 21/25. Parakeet-25 is fine only for Spanish (2.2), Portuguese (4.5), Russian (10.6); it auto-detects language and wrote the wrong language for 40% of Slovenian and 10% of Croatian sentences, and even with confusion removed stayed ~2× worse than Canary on small languages. Its int8 build underperforms its published scores on small languages. Whisper small is worse than both.
- **Asian:** Japanese → ReazonSpeech zipformer 5.3 · Cantonese 5.5 / Chinese 8.0 → **SenseVoice 2024-07-17** (the **2025-09-09 build is broken** for ja/ko/en: 77–79% garbage) · Korean → Whisper small 4.0 (Korean zipformer 39.3) · Thai → Thai zipformer 7.9 · Vietnamese → Vietnamese zipformer 10.8.
- **Other:** Omnilingual-1B (Meta, 1,600 languages) won all: Indonesian 9.7, Hindi 11.4, Turkish 14.9, Filipino 16.5, Arabic 19.0, Malay 19.8, Persian 19.8. Hebrew 40.1 everywhere → not offered. Omnilingual-300M is clearly weaker. Dolphin has no Turkish/Hebrew.
- Moonshine non-English models print a non-commercial licence warning in their library despite the README saying MIT — verify before shipping any.
- Round 2 (pending): GigaAM v3 (ru), Paraformer + Qwen3-ASR (zh), Whisper large-v3-turbo + Qwen3-ASR (hi/ar/tr/fa/fil/ms/id), ivrit.ai Whisper (he).
