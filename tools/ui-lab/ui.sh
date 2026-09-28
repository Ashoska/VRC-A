#!/usr/bin/env bash
# UI Lab — render the REAL app UI on the JVM to a PNG (no APK, no device).
#
#   tools/ui-lab/ui.sh shot <admin|headset|public> [--out FILE] [--qualifiers Q] [--settle MS]
#                         [--firestore] [--no-vrchat]
#
# --firestore  use the real Firebase project (fixed lab device id per variant)
# VRChat: signed in as the alt when tools/ui-lab/vrc-login.sh has a session (--no-vrchat to skip)
#
# Defaults: admin/public = phone 411x891dp @xxhdpi, headset = Quest panel 1024x640dp @xhdpi.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
[[ $# -ge 2 && "$1" == shot ]] || { sed -n '2,7p' "$0"; exit 2; }
VARIANT=$2; shift 2
case "$VARIANT" in
  admin) TASK=testAdminAppDebugUnitTest ;;
  headset) TASK=testHeadsetAppDebugUnitTest ;;
  public) TASK=testPublicAppDebugUnitTest ;;
  *) echo "unknown variant: $VARIANT"; exit 2 ;;
esac
OUT="$ROOT/ui-shots/$VARIANT-home.png"
PROPS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --out) OUT=$(realpath -m "$2"); shift ;;
    --qualifiers) PROPS+=("-Pui.qualifiers=$2"); shift ;;
    --settle) PROPS+=("-Pui.settle=$2"); shift ;;
    --firestore) PROPS+=("-Pui.firestore=real") ;;
    --no-vrchat) PROPS+=("-Pui.vrchat=off") ;;
    *) echo "unknown option: $1"; exit 2 ;;
  esac
  shift
done
mkdir -p "$ROOT/ui-shots"; rm -f "$OUT"
./gradlew --console=plain :app:$TASK -PuiLab --tests 'com.vrca.uilab.UiLabTest' \
  "-Pui.out=$OUT" "${PROPS[@]}" 2>&1 | grep -E '\[uilab\]|FAILED|Exception|error:|^e: ' || true
[[ -f "$OUT" ]] && echo "$OUT"
