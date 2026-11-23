#!/usr/bin/env bash
# Waits until Meta approves access to both gated Llama repos, then runs export_models.sh.
# Usage (WSL, from the repo root): bash scripts/wait_and_export.sh
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-$HOME/tutor-export}"
LOG="$WORK/export.log"
INTERVAL="${INTERVAL:-300}"
. "$WORK/.venv/bin/activate"

has_access() {
  python - <<'PY'
import sys
from huggingface_hub import hf_hub_download
repos = {
    "meta-llama/Llama-3.2-1B-Instruct-SpinQuant_INT4_EO8": "params.json",
    "meta-llama/Llama-3.2-1B-Instruct": "original/params.json",
}
ok = True
for repo, f in repos.items():
    try:
        hf_hub_download(repo, f, local_dir="/tmp/hf-access-check")
    except Exception as e:
        ok = False
        print(f"waiting: {repo}: {str(e).strip().splitlines()[-1][:120]}")
sys.exit(0 if ok else 1)
PY
}

echo "[$(date '+%F %T')] watcher started" | tee -a "$LOG"
until has_access >>"$LOG" 2>&1; do
  echo "[$(date '+%F %T')] access not granted yet; retry in ${INTERVAL}s" >>"$LOG"
  sleep "$INTERVAL"
done
echo "[$(date '+%F %T')] ACCESS GRANTED - starting export" | tee -a "$LOG"
bash "$HERE/export_models.sh" >>"$LOG" 2>&1
rc=$?
echo "[$(date '+%F %T')] EXPORT FINISHED rc=$rc" | tee -a "$LOG"
exit $rc
