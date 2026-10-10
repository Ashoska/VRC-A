#!/usr/bin/env bash
# Python env for the speech bench (desktop/CI box, NOT the app). Pinned to what the
# 2026-10 shoot-out used; keep sherpa_onnx == the app's AAR version (app/build.gradle).
set -euo pipefail
D=${1:-"$HOME/speech-bench"}
python3 -m venv "$D/venv"
"$D/venv/bin/pip" install -q \
  sherpa_onnx==1.13.8 numpy soundfile pyarrow psutil \
  jiwer==4.0.0 whisper_normalizer==0.1.15 opencc-python-reimplemented==0.1.7 \
  lingua-language-detector==2.1.1 huggingface_hub \
  onnx-asr==0.12.0 onnxruntime faster-whisper==1.2.1   # only for non-sherpa candidates
echo "venv ready: $D/venv  (work dir: $D)"
