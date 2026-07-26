#!/usr/bin/env python3
"""Value-network trainer for snake loop states.

Input shards: lines of `label w h cell cell ...` (cells head-first; the order IS
the vacate schedule). Builds input planes on the fly:
  0: vacate time, normalized by body length (0 for free cells)
  1: free-cell mask
  2: head one-hot
  3: fill level (constant plane, score/area)

Usage:
  train/.venv/bin/python train/train.py data/planes-30-*.txt --epochs 30
"""
import argparse
import glob
import math
import sys

import numpy as np
import torch
import torch.nn as nn


def load_shards(patterns):
    rows = []
    for pattern in patterns:
        for path in sorted(glob.glob(pattern)):
            with open(path) as f:
                for line in f:
                    parts = line.split()
                    if len(parts) < 4:
                        continue
                    rows.append((float(parts[0]), int(parts[1]), int(parts[2]),
                                 np.array(parts[3:], dtype=np.int32)))
    return rows


def to_planes(rows):
    """rows must share one board size; returns (x, y, w, h)."""
    w, h = rows[0][1], rows[0][2]
    area = w * h
    x = np.zeros((len(rows), 4, h, w), dtype=np.float32)
    y = np.zeros(len(rows), dtype=np.float32)
    for i, (label, _, _, cells) in enumerate(rows):
        length = len(cells)
        body = np.zeros(area, dtype=np.float32)
        # vacate time of cell at body index j is length - j; normalize
        body[cells] = (length - np.arange(length)) / length
        x[i, 0] = body.reshape(h, w)
        x[i, 1] = (body.reshape(h, w) == 0).astype(np.float32)
        x[i, 2].reshape(-1)[cells[0]] = 1.0
        x[i, 3] = length / area
        # label: normalize the deficit — predict (area - final score) squashed
        y[i] = (area - label) / 32.0
    return x, y, w, h


def group_by_size(rows):
    groups = {}
    for row in rows:
        groups.setdefault((row[1], row[2]), []).append(row)
    return groups


class ValueNet(nn.Module):
    def __init__(self, channels=48, blocks=5):
        super().__init__()
        layers = [nn.Conv2d(4, channels, 3, padding=1), nn.ReLU()]
        for _ in range(blocks):
            layers += [nn.Conv2d(channels, channels, 3, padding=1), nn.ReLU()]
        self.trunk = nn.Sequential(*layers)
        self.head = nn.Sequential(
            nn.AdaptiveAvgPool2d(1), nn.Flatten(),
            nn.Linear(channels, 64), nn.ReLU(), nn.Linear(64, 1),
        )

    def forward(self, x):
        return self.head(self.trunk(x)).squeeze(-1)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("shards", nargs="+")
    parser.add_argument("--epochs", type=int, default=30)
    parser.add_argument("--batch", type=int, default=256)
    parser.add_argument("--lr", type=float, default=1e-3)
    parser.add_argument("--val-frac", type=float, default=0.1)
    parser.add_argument("--out", default="data/value-net.pt")
    args = parser.parse_args()

    rows = load_shards(args.shards)
    if not rows:
        sys.exit("no data")
    rng = np.random.default_rng(7)
    device = "mps" if torch.backends.mps.is_available() else "cpu"

    # The net is fully convolutional: one model serves every board size. Batches are
    # homogeneous by size; sizes are interleaved during training.
    sizes = {}
    for (w, h), group in group_by_size(rows).items():
        x, y, _, _ = to_planes(group)
        order = rng.permutation(len(group))
        val_n = max(1, int(len(group) * args.val_frac))
        xt, yt = torch.from_numpy(x), torch.from_numpy(y)
        sizes[(w, h)] = {
            "xt": xt, "yt": yt,
            "val": order[:val_n], "train": order[val_n:],
        }
        print(f"{len(group)} samples {w}x{h}; deficit mean={32 * y.mean():.2f} std={32 * y.std():.2f}")

    net = ValueNet().to(device)
    opt = torch.optim.AdamW(net.parameters(), lr=args.lr)
    loss_fn = nn.SmoothL1Loss()
    print(f"device={device}")
    for (w, h), s in sizes.items():
        base = float(((s["yt"][s["val"]] - s["yt"][s["train"]].mean()) ** 2).mean().sqrt()) * 32
        print(f"  {w}x{h}: val baseline RMSE (predict mean) = {base:.2f} cells")

    for epoch in range(args.epochs):
        net.train()
        batches = []
        for key, s in sizes.items():
            perm = rng.permutation(s["train"])
            for start in range(0, len(perm), args.batch):
                batches.append((key, perm[start:start + args.batch]))
        rng.shuffle(batches)
        total = 0.0
        for key, idx in batches:
            s = sizes[key]
            xb = s["xt"][idx].to(device)
            yb = s["yt"][idx].to(device)
            opt.zero_grad()
            loss = loss_fn(net(xb), yb)
            loss.backward()
            opt.step()
            total += float(loss.detach())
        net.eval()
        report = []
        with torch.no_grad():
            for (w, h), s in sizes.items():
                pred = net(s["xt"][s["val"]].to(device))
                yv = s["yt"][s["val"]].to(device)
                rmse = float(((pred - yv) ** 2).mean().sqrt()) * 32
                corr = float(np.corrcoef(pred.cpu().numpy(), yv.cpu().numpy())[0, 1])
                report.append(f"{w}x{h}: RMSE {rmse:.2f} corr {corr:.3f}")
        print(f"epoch {epoch + 1:3d}  train {total / len(batches):.4f}  " + "  ".join(report))

    first = next(iter(sizes))
    torch.save({"model": net.state_dict(), "w": first[0], "h": first[1]}, args.out)
    print(f"saved {args.out}")


if __name__ == "__main__":
    main()
