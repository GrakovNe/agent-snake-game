#!/usr/bin/env bash
# Run ON the VM: declares the harvest job (job.env) and starts it via the watchdog,
# so crash/preemption/reboot all resume automatically.
# Usage: ./deploy/launch-harvest.sh SIZE GAMES SEEDFROM ROLLOUTS OUT
set -eu
cd "$HOME/agent-snake-game"
cat > job.env <<EOF
SIZE=$1
GAMES=$2
SEEDFROM=$3
ROLLOUTS=$4
OUT=$5
EOF
rm -f "$5.done"
./deploy/vm-babysit.sh
echo "job declared and started: $*"
