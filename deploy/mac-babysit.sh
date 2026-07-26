#!/usr/bin/env bash
# Mac-side watchdog (cron every 10 min): restarts the preempted VM and mirrors
# harvest data locally. Remove with: crontab -e (delete the snake-babysit line).
set -u
export PATH="$HOME/yandex-cloud/bin:$PATH"
INSTANCE=epdedarjpcu84qbub6gr
LOG="$HOME/projects/agent-snake-game/data/babysit.log"

STATE=$(yc compute instance get "$INSTANCE" --format json 2>/dev/null |
  python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null)

if [ "$STATE" = "STOPPED" ]; then
  echo "$(date -Is) instance stopped (preempted?) — starting" >> "$LOG"
  yc compute instance start "$INSTANCE" >> "$LOG" 2>&1
  exit 0   # next run will sync; vm-babysit resumes the job on boot
fi
[ "$STATE" = "RUNNING" ] || { echo "$(date -Is) state=$STATE, skip" >> "$LOG"; exit 0; }

IP=$(yc compute instance get "$INSTANCE" --format json |
  python3 -c "import sys,json; d=json.load(sys.stdin); print(d['network_interfaces'][0]['primary_v4_address']['one_to_one_nat']['address'])")

mkdir -p "$HOME/projects/agent-snake-game/data/cloud-mirror"
rsync -a --timeout=60 -e "ssh -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10" \
  "yc-user@$IP:agent-snake-game/data/" \
  "$HOME/projects/agent-snake-game/data/cloud-mirror/" >> "$LOG" 2>&1
