#!/usr/bin/env bash
# Cardinal Lab launcher — runs the REAL Discord bot code on the JVM against a fake Discord.
#
#   tools/cardinal-lab/lab.sh run <script.txt> [more.txt ...] [options]   # scripted run → report
#   tools/cardinal-lab/lab.sh interactive [options]                        # control API on :18750
#
# Options:
#   --live               forward model calls to real Workers AI (needs CF_ACCOUNT_ID + CF_API_TOKEN;
#                        optional CF_GATEWAY_ID [+ CF_AIG_TOKEN] routes through AI Gateway, tagged lab)
#   --cap N              hard LIVE neuron cap for this run (default 1500)
#   --remap A=B[,C=D]    swap models at the proxy (model id → model id, or role → model id:
#                        e.g. observe=@cf/meta/llama-3.1-8b-instruct-fp8)
#   --state FILE         start from a saved memory state (state-final.json of a previous run)
#   --set key=value      harness knob: ambient, cooldown, context, model, spent, shadow, gap, quiet,
#                        idle (min), dry.director (reply|react|ignore|mix), dry.latency (ms), affinity
#   --run NAME           run directory name under lab-runs/ (default: timestamp)
#   --port N             control API port (interactive; default 18750)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"

usage() { sed -n '2,20p' "$0"; exit 2; }
[[ $# -ge 1 ]] || usage
MODE=$1; shift
PROPS=()
RUN="run-$(date +%Y%m%d-%H%M%S)"
case "$MODE" in
  run)
    SCRIPTS=()
    while [[ $# -gt 0 && "$1" != --* ]]; do SCRIPTS+=("$1"); shift; done
    [[ ${#SCRIPTS[@]} -gt 0 ]] || usage
    PROPS+=("-Plab.script=$(IFS=,; echo "${SCRIPTS[*]}")") ;;
  interactive) PROPS+=("-Plab.interactive=1") ;;
  *) usage ;;
esac
while [[ $# -gt 0 ]]; do
  case "$1" in
    --live) PROPS+=("-Plab.ai=live") ;;
    --cap) PROPS+=("-Plab.cap=$2"); shift ;;
    --remap) PROPS+=("-Plab.remap=$2"); shift ;;
    --state) PROPS+=("-Plab.state=$2"); shift ;;
    --set) PROPS+=("-Plab.$2"); shift ;;
    --run) RUN="$2"; shift ;;
    --port) PROPS+=("-Plab.port=$2"); shift ;;
    *) echo "unknown option: $1"; usage ;;
  esac
  shift
done
PROPS+=("-Plab.run=$RUN")
mkdir -p "lab-runs/$RUN"
echo "Cardinal Lab → lab-runs/$RUN"
./gradlew --console=plain :app:testAdminAppDebugUnitTest -PcardinalLab \
  --tests 'com.vrca.discordbot.lab.CardinalLabTest' "${PROPS[@]}" 2>&1 | tee "lab-runs/$RUN/console.log"
