#!/usr/bin/env python3
"""Invariant mining on decidedness data: which algebraic properties of a
configuration separate solved states from doomed ones.

Input: bucket-mode harvest lines `mean std w h cells...` (cells = body,
head first; order = phase). For each state computes phase-circle invariants
and reports their separating power (AUC) against the label
"solved" = deficit <= 1.5 cells.

Usage: train/.venv/bin/python train/invariants.py data/decided-30.txt [minfill maxfill]
"""
import sys

import numpy as np

NEIGH = [(0, 1), (0, -1), (1, 0), (-1, 0)]


def invariants(w, h, cells):
    area = w * h
    L = len(cells)
    g = area - L
    pos = {c: i for i, c in enumerate(cells)}  # cell -> phase (head=0)
    body = set(cells)

    holes = []
    for c in range(area):
        if c in body:
            continue
        walls = []
        free_neigh = 0
        x, y = c % w, c // w
        for dx, dy in NEIGH:
            nx, ny = x + dx, y + dy
            if not (0 <= nx < w and 0 <= ny < h):
                continue
            n = ny * w + nx
            if n in body:
                walls.append(pos[n])
            else:
                free_neigh += 1
        holes.append((free_neigh, walls))

    def circ(a, b):
        d = abs(a - b) % L
        return min(d, L - d)

    n_isolated = 0
    n_undig = 0
    repair_need = 0.0
    disp_sum = 0.0
    for free_neigh, walls in holes:
        if free_neigh > 0 or len(walls) < 2:
            continue  # clusters have slack; degenerate skipped
        n_isolated += 1
        dmin = min(circ(a, b) for i, a in enumerate(walls) for b in walls[i + 1:])
        disp_sum += dmin
        if dmin > g + 2:
            n_undig += 1
            repair_need += dmin - g - 2

    return {
        "g": g,
        "isolated": n_isolated,
        "undig": n_undig,
        "repair": repair_need,
        "repair_norm": repair_need / max(1.0, g * g / 2.0),
        "disp_mean": disp_sum / max(1, n_isolated),
    }


def auc(scores, labels):
    order = np.argsort(scores)
    ranks = np.empty(len(scores))
    ranks[order] = np.arange(1, len(scores) + 1)
    pos_mask = labels == 1
    n1, n0 = pos_mask.sum(), (~pos_mask).sum()
    if n1 == 0 or n0 == 0:
        return float("nan")
    return (ranks[pos_mask].sum() - n1 * (n1 + 1) / 2) / (n1 * n0)


def main():
    path = sys.argv[1]
    minfill = float(sys.argv[2]) if len(sys.argv) > 2 else 0.93
    maxfill = float(sys.argv[3]) if len(sys.argv) > 3 else 0.97

    rows = []
    with open(path) as f:
        for line in f:
            parts = line.split()
            if len(parts) < 6:
                continue
            mean, std = float(parts[0]), float(parts[1])
            w, h = int(parts[2]), int(parts[3])
            cells = np.array(parts[4:], dtype=np.int64)
            fill = len(cells) / (w * h)
            if not (minfill <= fill < maxfill):
                continue
            rows.append((mean, std, w, h, cells))

    if len(rows) < 50:
        sys.exit(f"only {len(rows)} states in fill range")

    labels = np.array([1 if (r[2] * r[3] - r[0]) <= 1.5 else 0 for r in rows])
    print(f"{len(rows)} states in fill [{minfill}, {maxfill}); "
          f"solved: {labels.mean():.1%}")

    feats = [invariants(r[2], r[3], r[4]) for r in rows]
    for key in ["isolated", "undig", "repair", "repair_norm", "disp_mean"]:
        scores = np.array([f[key] for f in feats], dtype=float)
        # higher invariant value should mean doomed -> AUC of (-score) vs solved
        a = auc(-scores, labels)
        solved_mean = scores[labels == 1].mean()
        doomed_mean = scores[labels == 0].mean() if (labels == 0).any() else float("nan")
        print(f"{key:>12}: AUC={a:.3f}  solved_mean={solved_mean:.2f}  "
              f"doomed_mean={doomed_mean:.2f}")


if __name__ == "__main__":
    main()
