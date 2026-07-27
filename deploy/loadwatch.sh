#!/bin/bash
# Fleet load watcher: prints one BUSY/IDLE line per machine plus evidence.
# Used by the recurring load-watch cron; exit code is always 0.
# Cloud IPs are ephemeral (preemptible boxes) — resolved via yc on every run.

export PATH="$HOME/yandex-cloud/bin:$PATH"

echo "=== $(date '+%H:%M:%S') fleet load ==="

# --- Mac (this machine) ---
LOAD=$(sysctl -n vm.loadavg | awk '{print $2}')
TRAIN=$(pgrep -f '[t]rain.py' | head -1)
BENCH=$(pgrep -f '[B]enchmarkKt' | head -1)
MAC_JOBS=""
[ -n "$TRAIN" ] && MAC_JOBS="train.py "
[ -n "$BENCH" ] && MAC_JOBS="${MAC_JOBS}benchmark "
if [ -n "$MAC_JOBS" ]; then
  echo "mac: BUSY load=$LOAD jobs: $MAC_JOBS"
else
  echo "mac: IDLE load=$LOAD (no train/bench jobs)"
fi

ip_of() {
  yc compute instance get "$1" --format json 2>/dev/null | python3 -c \
    "import sys,json
try: print(json.load(sys.stdin)['network_interfaces'][0]['primary_v4_address']['one_to_one_nat']['address'])
except Exception: pass"
}

# --- CPU worker (harvests/benches) ---
WIP=$(ip_of snake-worker)
if [ -z "$WIP" ]; then
  echo "worker: NO IP via yc (stopped/preempted? yc compute instance list)"
else
  VM=$(ssh -o ConnectTimeout=6 -o BatchMode=yes -o StrictHostKeyChecking=accept-new yc-user@$WIP '
    L=$(cut -d" " -f1 /proc/loadavg); J=$(pgrep -c java)
    H=$(wc -l < agent-snake-game/data/planes-60-exit3.txt 2>/dev/null || echo 0)
    D=$([ -f agent-snake-game/data/planes-60-exit3.txt.done ] && echo done || echo running)
    echo "$L $J $H $D"' 2>/dev/null)
  if [ -z "$VM" ]; then
    echo "worker: ssh UNREACHABLE ($WIP) — booting or dead"
  else
    set -- $VM
    if [ "$2" -gt 0 ] 2>/dev/null && [ "$4" != "done" ]; then
      echo "worker: BUSY ip=$WIP load=$1 java=$2 exit3=$3 lines ($4)"
    elif [ "$4" = "done" ]; then
      echo "worker: IDLE ip=$WIP — harvest exit3 COMPLETE ($3 lines), ready for new work"
    else
      echo "worker: IDLE ip=$WIP load=$1 java=$2 — no jobs, ready for new work"
    fi
  fi
fi

# --- GPU box (preemptible T4, training) ---
GIP=$(ip_of snake-gpu)
if [ -z "$GIP" ]; then
  echo "gpu-box: NO IP via yc (stopped/preempted? yc compute instance list)"
else
  G=$(ssh -o ConnectTimeout=6 -o BatchMode=yes -o StrictHostKeyChecking=accept-new yc-user@$GIP '
    T=$(pgrep -cf train.py); U=$(nvidia-smi --query-gpu=utilization.gpu --format=csv,noheader,nounits 2>/dev/null)
    E=$(grep -c "^epoch" agent-snake-game/data/train-v9.log 2>/dev/null || echo 0)
    echo "${T:-0} ${U:-?} ${E:-0}"' 2>/dev/null)
  if [ -z "$G" ]; then
    echo "gpu-box: ssh UNREACHABLE ($GIP) — booting or dead"
  else
    set -- $G
    if [ "$1" -gt 0 ] 2>/dev/null; then
      echo "gpu-box: BUSY ip=$GIP train.py alive gpu=$2% epochs=$3"
    else
      echo "gpu-box: IDLE ip=$GIP gpu=$2% epochs-done=$3 — no training, check data/train-v9.log"
    fi
  fi
fi

# --- Home box: powered off by the user (2026-07-27), do not watch. ---
# Webshow (snake.grakovne.org) resumes via systemd whenever the box is up again.
echo "home: not watched (powered off; webshow autostarts on boot)"
