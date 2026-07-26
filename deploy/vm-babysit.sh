#!/usr/bin/env bash
# VM-side watchdog (cron: @reboot and every 5 min): relaunches the harvest declared
# in ~/agent-snake-game/job.env unless it is running or finished (.done marker).
set -u
cd "$HOME/agent-snake-game" || exit 0
[ -f job.env ] || exit 0
# shellcheck disable=SC1091
source job.env   # SIZE GAMES SEEDFROM ROLLOUTS OUT
[ -f "$OUT.done" ] && exit 0
pgrep -f HarvestKt >/dev/null && exit 0
echo "$(date -Is) babysit: relaunching harvest $SIZE/$GAMES/$SEEDFROM" >> harvest.log
nohup nice -n 5 ./gradlew -q harvest \
  -Psize="$SIZE" -Pgames="$GAMES" -PseedFrom="$SEEDFROM" -Prollouts="$ROLLOUTS" -Pout="$OUT" \
  -PstarveDiv="${STARVEDIV:-1}" -PeveryNth="${EVERYNTH:-1}" \
  -Pphase1Policy="${P1POLICY:-champion}" -ProlloutPolicy="${RPOLICY:-champion}" \
  -Pmode="${MODE:-endgame}" \
  >> harvest.log 2>&1 &
