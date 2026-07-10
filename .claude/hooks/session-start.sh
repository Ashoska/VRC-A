#!/bin/bash
# SessionStart hook for Claude Code on the web: installs the Android SDK so
# `./gradlew` (compile/build) works in the ephemeral remote container. Runs only
# in a remote session, is idempotent (skips install when the SDK is already
# present), and requires no local machine config. Does nothing to the app itself.
set -euo pipefail

# Remote (Claude Code on the web) only — no-op on a local machine.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(pwd)}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
CMDLINE_VER="11076708"   # cmdline-tools 11.0 (linux)
CMDLINE_ZIP="commandlinetools-linux-${CMDLINE_VER}_latest.zip"
SDK_URL="https://dl.google.com/android/repository/${CMDLINE_ZIP}"

# Persist ANDROID_HOME + PATH for the whole session.
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  {
    echo "export ANDROID_HOME=\"$ANDROID_HOME\""
    echo "export ANDROID_SDK_ROOT=\"$ANDROID_HOME\""
    echo "export PATH=\"$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:\$PATH\""
  } >> "$CLAUDE_ENV_FILE"
fi

# Point Gradle at the SDK (local.properties is gitignored — no stray diff).
echo "sdk.dir=$ANDROID_HOME" > "$PROJECT_DIR/local.properties"

have_pkg() { [ -d "$ANDROID_HOME/$1" ]; }

# Idempotent fast path: everything already installed (cached container) → done.
if have_pkg "platforms/android-34" && have_pkg "build-tools/34.0.0" && have_pkg "platform-tools"; then
  echo "Android SDK already present at $ANDROID_HOME — skipping install."
  exit 0
fi

echo "Installing Android SDK into $ANDROID_HOME ..."
mkdir -p "$ANDROID_HOME/cmdline-tools"

# Install cmdline-tools (provides sdkmanager) if absent.
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  tmp="$(mktemp -d)"
  echo "Downloading $CMDLINE_ZIP ..."
  curl -fsSL "$SDK_URL" -o "$tmp/cmdline.zip"
  unzip -q "$tmp/cmdline.zip" -d "$tmp"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$tmp"
fi

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

# Accept licenses, then install the components the build needs (compileSdk 34).
yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" --sdk_root="$ANDROID_HOME" \
  "platform-tools" "platforms;android-34" "build-tools;34.0.0"

echo "Android SDK ready at $ANDROID_HOME"
