#!/usr/bin/env python3
"""Decidedness curve: how much of the game is still open at each fill level.

Reads bucket-mode harvest output (`mean std w h cells...`) and aggregates the
std of policy-RNG rollouts by fill bucket. Where the std collapses, the game
is already decided.

Usage: train/.venv/bin/python train/curve.py data/decided-30.txt
"""
import sys

import numpy as np

rows = []
for path in sys.argv[1:]:
    with open(path) as f:
        for line in f:
            parts = line.split()
            if len(parts) < 5:
                continue
            mean, std = float(parts[0]), float(parts[1])
            w, h = int(parts[2]), int(parts[3])
            fill = (len(parts) - 4) / (w * h)
            rows.append((fill, mean, std, w * h))

if not rows:
    sys.exit("no data")

buckets = [0.70, 0.80, 0.85, 0.90, 0.93, 0.95, 0.97, 0.99]
print(f"{len(rows)} states")
print(f"{'fill':>6} {'n':>6} {'std p50':>8} {'std p90':>8} {'std mean':>9} "
      f"{'deficit p50':>12} {'share decided':>14}")
for i, b in enumerate(buckets):
    hi = buckets[i + 1] if i + 1 < len(buckets) else 1.01
    grp = [r for r in rows if b <= r[0] < hi]
    if not grp:
        continue
    stds = np.array([g[2] for g in grp])
    deficits = np.array([g[3] - g[1] for g in grp])
    # "decided" = rollout std below 1 cell: the outcome barely depends on choices
    decided = float((stds < 1.0).mean())
    print(f"{b:>6.0%} {len(grp):>6} {np.median(stds):>8.2f} "
          f"{np.percentile(stds, 90):>8.2f} {stds.mean():>9.2f} "
          f"{np.median(deficits):>12.1f} {decided:>13.0%}")
