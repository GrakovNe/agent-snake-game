#!/usr/bin/env bash
# Bootstrap a bare Ubuntu/Debian box into a harvest shard worker.
# Usage: SEED_FROM=100000 GAMES=2000 SIZE=30 ROLLOUTS=32 ./bootstrap.sh
set -euo pipefail

SIZE="${SIZE:-30}"
GAMES="${GAMES:-1000}"
SEED_FROM="${SEED_FROM:?set SEED_FROM to a unique per-machine range start}"
ROLLOUTS="${ROLLOUTS:-32}"
REPO="${REPO:-https://github.com/GrakovNe/agent-snake-game.git}"

if ! command -v java >/dev/null; then
  sudo apt-get update -qq
  sudo apt-get install -y -qq openjdk-21-jre-headless git
fi

if [ ! -d agent-snake-game ]; then
  git clone --depth 1 "$REPO"
fi
cd agent-snake-game

nice -n 10 ./gradlew -q harvest \
  -Psize="$SIZE" -Pgames="$GAMES" -PseedFrom="$SEED_FROM" -Prollouts="$ROLLOUTS" \
  -Pout="data/planes-$SIZE-seed$SEED_FROM.txt"

echo "shard ready: data/planes-$SIZE-seed$SEED_FROM.txt"
echo "fetch it with: scp $(hostname):$(pwd)/data/planes-$SIZE-seed$SEED_FROM.txt ."
