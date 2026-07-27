#!/bin/bash
# Fleet load watcher: prints one BUSY/IDLE line per machine plus evidence.
# Used by the recurring load-watch cron; exit code is always 0.

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
[ -f data/train-v9.log ] && echo "  v9: $(tail -1 data/train-v9.log | cut -c1-100)"

# --- Yandex VM ---
VM=$(ssh -o ConnectTimeout=6 -o BatchMode=yes yc-user@89.169.175.18 '
  L=$(cut -d" " -f1 /proc/loadavg); J=$(pgrep -c java)
  H=$(wc -l < agent-snake-game/data/planes-60-exit3.txt 2>/dev/null || echo 0)
  D=$([ -f agent-snake-game/data/planes-60-exit3.txt.done ] && echo done || echo running)
  echo "$L $J $H $D"' 2>/dev/null)
if [ -z "$VM" ]; then
  echo "vm: UNREACHABLE (preempted? yc compute instance list)"
else
  set -- $VM
  if [ "$2" -gt 0 ] 2>/dev/null && [ "$4" != "done" ]; then
    echo "vm: BUSY load=$1 java=$2 exit3=$3 lines ($4)"
  elif [ "$4" = "done" ]; then
    echo "vm: IDLE load=$1 — harvest exit3 COMPLETE ($3 lines), ready for new work"
  else
    echo "vm: IDLE load=$1 java=$2 — no jobs, ready for new work"
  fi
fi

# --- Home box: powered off by the user (2026-07-27), do not watch. ---
# Webshow (snake.grakovne.org) resumes via systemd whenever the box is up again.
echo "home: not watched (powered off; webshow autostarts on boot)"
