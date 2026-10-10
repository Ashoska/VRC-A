# Wake-word voice commands: plan (not started)

**Status:** idea agreed with the user, nothing built. Start after the voice-to-text PR (#169) is tested and merged, as its own branch/PR (and ideally a fresh chat).

**Separate from the Cardinal AI.** This has nothing to do with the admin Discord bot (`docs/systems/cardinal.md`, `com.vrca.discordbot`): no shared code, data or behaviour. "Cardinal" only came up as an example wake word; the final word is still open, and even if it ends up being "Cardinal" it is just a word to this system.

## Goal
- Hands-free control of VRC-A from inside VRChat on Quest, where the app isn't visible: a wake word plus a fixed list of at most ~100 phrases, e.g. "<wake>, pause", "<wake>, chatbox on", "<wake>, start voice" (loads the big dictation model on demand, so it doesn't sit in RAM all the time).
- Always listening while enabled (opt-in), and tiny: **under 10 MB of RAM** (user requirement; aim for 1–3 MB), next to no CPU while nobody talks, reacts straight away.
- "100%" as the user means it: **it never does something you didn't say.** When it isn't sure it does nothing and plays a "didn't catch that" sound, so the only failure is having to say it again.

## Ruled out (and why)
- **sherpa-onnx keyword spotting** (open vocabulary, already in our AAR): the model is a few MB, but ONNX Runtime adds ~10–30 MB on top, so likely over budget (confirm on the bench before dropping it for good).
- **Picovoice Porcupine / Rhino:** small and accurate, but a commercial licence with an AccessKey.
- **A trainable speech-sounds (phoneme) model** that takes phrases as text with no retraining: needs a GPU (weeks on CPU). Not worth it for a fixed list that rarely changes.

## Design
1. **Always-on stage, pure Kotlin, under 1 MB:** mic capture + the existing `Voicing` pitch check + a ~2 s ring buffer. Nothing else runs until there's voiced speech. When dictation is running too, both use the same capture (never a second mic stream).
2. **Wake-word model:** a small DS-CNN (~50–200k parameters) on log-mel features, run in plain Kotlin (no ONNX Runtime, no native libs): weights ~100–400 KB.
3. **Phrase classifier:** the same kind of model over the at most ~100 phrases plus a "none of these" class, on the ~2 s after the wake word. Both "<wake> pause" in one breath and "<wake>" → chime → command within ~3 s should work.
4. **Acts only when sure:** the best phrase must clear a confidence threshold AND be well ahead of the second best. Reuse the checks from the bare-word voice commands (`docs/systems/voice-to-text.md`): your own voice at about your usual loudness (keeps other people and the Quest speakers out), clearly voiced, commands that change nothing do nothing, one chime per action (`CommandSounds`), plus a new "didn't catch that" sound.
5. **Tuned to the user:** "Say it" a few phrases at setup to fine-tune the last layer on their voice (more accurate for them, less reactive to others).

## Phrase list (to draft first)
- From what the app does: dictation (start voice, stop voice, pause, resume, clear, scratch that), chatbox (on/off, next/previous preset, preset 1–10), AFK on/off, music in the chatbox on/off, and so on. Slots expand into phrases (preset 1–10 = 10 phrases), all within ~100.
- **Confusability check:** compare every pair of phrases phonetically and reword close ones ("start chatbox" vs "stop chatbox" differ by one vowel: use "chatbox on/off" or different verbs).
- English first; other languages later with one classifier per language from the same pipeline.

## Training (no GPU needed for this design)
- **Data:** synthetic voices (Piper TTS, many speakers, speeds and pitches) saying the wake word and every phrase; negatives from real conversation (LibriSpeech, AMI, FLEURS and the chatter set in `tools/speech-bench`), music and game-like noise; augmentation with room reverb, noise, a Quest-mic EQ and volume changes.
- **Where:** this cloud container (4 cores) or the user's Galaxy S24 Ultra via Termux + Python + PyTorch on the CPU (its GPU/NPU can't train with standard tools). Estimates: wake word ~2–4 h, ~100-phrase classifier ~6–12 h (overnight). Retrain when the phrase list changes. On the phone: plugged in, kept cool, `termux-wake-lock`, Termux exempt from battery optimisation.
- **Output:** a small weights file + the phrase list, shipped in the APK (no download needed at this size).

## Bench before building (same approach as `tools/speech-bench/README.md`)
- **Catch rate** per phrase: held-out synthetic voices + real recordings of the user.
- **False wakes** per hour on hours of conversation + VRChat-like noise, and **wrong actions** over thousands of trials.
- **CPU and RAM** on desktop, then on the Quest next to VRChat.

## Targets
- Wrong actions: **0** in testing.
- Catch rate when said clearly: ~97–99% (lower in a loud room, where it asks you to repeat).
- False wakes: under 1 per 10 hours of conversation (and a false wake alone does nothing).
- RAM: under 10 MB, aiming for 1–3 MB.

## App integration (after the bench passes)
- Opt-in setting ("Hands-free commands"); a microphone foreground service while it's on (like `DictationService`), and the top bar's red mic shows it's listening. Works with VRChat in front.
- Phrases map onto existing ViewModel actions (OSC Start/Stop, preset selection, AFK, `startDictation`/`stopDictation`, `clearManual`, …).
- The bare-word commands (`VoiceCommands`) stay as a fallback or are replaced; `CommandSounds`, the own-voice level check and "Say it" are reused.

## Open questions
- The wake word itself: 3+ syllables, uncommon in conversation. "Cardinal" was the example; if it's chosen it is still unrelated to the Cardinal AI.
- The first phrase list (draft it, run the confusability check, let the user trim it).
- One breath ("<wake> pause") vs wake → chime → command, or both.
