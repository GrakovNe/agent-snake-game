#!/usr/bin/env bash
# Run ON the VM: declares the harvest job (job.env) and starts it via the watchdog,
# so crash/preemption/reboot all resume automatically.
# Usage: ./deploy/launch-harvest.sh SIZE GAMES SEEDFROM ROLLOUTS OUT [STARVEDIV] [EVERYNTH]
set -eu
cd "$HOME/agent-snake-game"
cat > job.env <<EOF
SIZE=$1
GAMES=$2
SEEDFROM=$3
ROLLOUTS=$4
OUT=$5
STARVEDIV=${6:-1}
EVERYNTH=${7:-1}
P1POLICY=${8:-champion}
RPOLICY=${9:-champion}
EOF
rm -f "$5.done"
./deploy/vm-babysit.sh
echo "job declared and started: $*"
