#!/usr/bin/env bash
# UI Lab — the REAL app UI on the JVM, driven by commands, captured to PNGs (no APK, no device).
#
#   tools/ui-lab/ui.sh shot  <variant> [options]                  # Home → ui-shots/<variant>-home.png
#   tools/ui-lab/ui.sh run   <variant> "cmd; cmd; …" [options]    # run commands (see UiLabDriver.kt)
#   tools/ui-lab/ui.sh run   <variant> --script file.txt [options]
#   tools/ui-lab/ui.sh serve <variant> [options]                  # live: then tools/ui-lab/uictl.sh "cmd" …
#
# variant: admin | headset | public
# options: --size phone|small|tablet|headset   --tall (phone sizes only: a long page, whole scroll content)
#          --qualifiers Q   --settle MS   --firestore (real project)   --no-vrchat   --port N
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
# Cloud sessions started outside the repo folder never run the SessionStart hook, so the first
# lab run had no Android SDK. Install it here when it's missing (the hook is idempotent).
SDK=$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null || true)
if [[ "${CLAUDE_CODE_REMOTE:-}" == true && ! -d "${SDK:-${ANDROID_HOME:-$HOME/android-sdk}}/platforms/android-34" ]]; then
  CLAUDE_PROJECT_DIR="$ROOT" bash "$ROOT/.claude/hooks/session-start.sh" 2>&1 | tail -1
fi
[[ -n "${ANDROID_HOME:-}" ]] || export ANDROID_HOME="$HOME/android-sdk"
[[ $# -ge 2 ]] || { sed -n '2,13p' "$0"; exit 2; }
MODE=$1; VARIANT=$2; shift 2
case "$VARIANT" in
  admin) TASK=testAdminAppDebugUnitTest; SIZE=phone ;;
  headset) TASK=testHeadsetAppDebugUnitTest; SIZE=headset ;;
  public) TASK=testPublicAppDebugUnitTest; SIZE=phone ;;
  *) echo "unknown variant: $VARIANT"; exit 2 ;;
esac
PROPS=(); DO=""; TALL=""; Q=""
case "$MODE" in
  shot) DO="shot $VARIANT-home" ;;
  run)
    if [[ "${1:-}" == --script ]]; then PROPS+=("-Pui.script=$(realpath "$2")"); shift 2
    else DO="${1:?commands}"; shift; fi ;;
  serve) PROPS+=("-Pui.serve=18760") ;;
  *) echo "unknown mode: $MODE"; exit 2 ;;
esac
while [[ $# -gt 0 ]]; do
  case "$1" in
    --size) SIZE=$2; shift ;;
    --tall) TALL=1 ;;
    --qualifiers) Q=$2; shift ;;
    --settle) PROPS+=("-Pui.settle=$2"); shift ;;
    --firestore) PROPS+=("-Pui.firestore=real") ;;
    --no-vrchat) PROPS+=("-Pui.vrchat=off") ;;
    --port) PROPS=("${PROPS[@]/-Pui.serve=18760/-Pui.serve=$2}"); shift ;;
    *) echo "unknown option: $1"; exit 2 ;;
  esac
  shift
done
if [[ -n "$TALL" && "$SIZE" == headset ]]; then
  # The Quest panel is a fixed 1024x640dp. A 3x-tall render stretches the columns and leaves a
  # huge gap above the bottom bar that the headset never shows; it scrolls instead.
  echo "--tall isn't supported for the headset panel: use 'scrollto <text>' / 'scroll down' (the app scrolls like the device)"; exit 2
fi
if [[ -z "$Q" ]]; then
  case "$SIZE" in
    phone) W=411; H=891; D=xxhdpi; O=port ;;
    small) W=360; H=740; D=xxhdpi; O=port ;;
    tablet) W=800; H=1280; D=xhdpi; O=port ;;
    headset) W=1024; H=640; D=xhdpi; O=land ;;
    *) echo "unknown size: $SIZE"; exit 2 ;;
  esac
  [[ -n "$TALL" ]] && { H=$((H * 3)); O=port; }
  Q="w${W}dp-h${H}dp-$O-$D"
fi
PROPS+=("-Pui.qualifiers=$Q")
[[ -n "$DO" ]] && PROPS+=("-Pui.do=$DO")
mkdir -p "$ROOT/ui-shots"
./gradlew --console=plain :app:$TASK -PuiLab --tests 'com.vrca.uilab.UiLabTest' "${PROPS[@]}" 2>&1 \
  | grep --line-buffered -E '\[uilab\]|FAILED|^e: |Exception|Could not (resolve|get|HEAD)|Received status code|SDK location' | sed -u 's/^ *//' || true
# Recordings (`record start/stop`) → <name>.gif from their frames.
python3 "$ROOT/tools/ui-lab/gif.py" --all "$ROOT/ui-shots" || true
