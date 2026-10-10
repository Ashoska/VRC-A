#!/usr/bin/env bash
# Fetch a candidate model into <dest>, pinned:
#   fetch_model.sh <dest> hf  <owner/repo> <revision> <file> [file...]   # individual files (what the app downloads)
#   fetch_model.sh <dest> tar <url-to-.tar.bz2>                         # k2-fsa release tarballs (bench only)
# Prefer the "hf" form for anything that may ship: the app downloads single files from a
# pinned Hugging Face revision (SpeechCatalog), so test exactly those files.
set -euo pipefail
dest=$1; mode=$2; shift 2
mkdir -p "$dest"
case "$mode" in
  hf) repo=$1; rev=$2; shift 2
      for f in "$@"; do
        curl -sSfL --retry 3 -o "$dest/$f" "https://huggingface.co/$repo/resolve/$rev/$f"
        echo "$(sha256sum "$dest/$f" | cut -d' ' -f1)  $(stat -c %s "$dest/$f")  $f"
      done ;;
  tar) curl -sSfL --retry 3 -o "$dest/a.tar.bz2" "$1" && tar -xjf "$dest/a.tar.bz2" -C "$dest" && rm -f "$dest/a.tar.bz2" ;;
  *) echo "mode must be hf or tar"; exit 2 ;;
esac
touch "$dest/.done"
