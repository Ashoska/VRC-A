#!/usr/bin/env bash
# Run many bench jobs side by side (never one by one): at most $MAX_JOBS at once (default 3;
# each job is single-threaded, so leave a core free). Jobs file, one per line:
#   name|kind|model_dir|lang,lang,set:casual_norm,...
# A job waits until <model_dir>/.done exists (fetch_model.sh writes it), so downloads and
# runs overlap. Results: <work>/results/<name>.json, logs: <work>/logs/<name>.log.
set -euo pipefail
W=$1; JOBS=$2; MAX=${MAX_JOBS:-3}; HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$W/results" "$W/logs"
export FLEURS_DIR="$W/data/fleurs" SETS_DIR="$W/data/sets" BENCH_LIMIT=${BENCH_LIMIT:-20} BENCH_THREADS=${BENCH_THREADS:-1}
while IFS='|' read -r name kind dir langs; do
  [[ -z "$name" || "$name" == \#* ]] && continue
  while [ ! -e "$dir/.done" ]; do sleep 10; done
  while [ "$(pgrep -fc 'speech-bench/run.py')" -ge "$MAX" ]; do sleep 5; done
  "$W/venv/bin/python" -I "$HERE/run.py" "$name" "$kind" "$dir" "$langs" "$W/results/$name.json" > "$W/logs/$name.log" 2>&1 &
  sleep 2
done < "$JOBS"
wait
echo "all jobs done → python -I $HERE/score.py $W/results"
