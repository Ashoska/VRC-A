#!/usr/bin/env bash
# Send commands to a running `ui.sh serve` (each argument is one command; `;` also separates).
#   tools/ui-lab/uictl.sh "tap Settings" "shot settings"
#   tools/ui-lab/uictl.sh quit
PORT=${UILAB_PORT:-18760}
if [[ "${1:-}" == quit ]]; then curl -s "http://127.0.0.1:$PORT/quit"; echo; exit; fi
body=$(printf '%s\n' "$@")
curl -s --max-time 600 -X POST --data-binary "$body" "http://127.0.0.1:$PORT/run"; echo
