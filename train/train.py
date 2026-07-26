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
    x, y, w, h = to_planes(rows)
    print(f"{len(rows)} samples {w}x{h}; deficit mean={32 * y.mean():.2f} std={32 * y.std():.2f}")

    rng = np.random.default_rng(7)
    order = rng.permutation(len(rows))
    val_n = int(len(rows) * args.val_frac)
    val_idx, train_idx = order[:val_n], order[val_n:]

    device = "mps" if torch.backends.mps.is_available() else "cpu"
    net = ValueNet().to(device)
    opt = torch.optim.AdamW(net.parameters(), lr=args.lr)
    loss_fn = nn.SmoothL1Loss()

    xt = torch.from_numpy(x)
    yt = torch.from_numpy(y)
    xv = xt[val_idx].to(device)
    yv = yt[val_idx].to(device)

    baseline = float(((yv - yt[train_idx].mean()) ** 2).mean().sqrt()) * 32
    print(f"device={device}  val baseline RMSE (predict mean) = {baseline:.2f} cells")

    for epoch in range(args.epochs):
        net.train()
        perm = rng.permutation(train_idx)
        total, batches = 0.0, 0
        for start in range(0, len(perm), args.batch):
            idx = perm[start:start + args.batch]
            xb = xt[idx].to(device)
            yb = yt[idx].to(device)
            opt.zero_grad()
            loss = loss_fn(net(xb), yb)
            loss.backward()
            opt.step()
            total += float(loss)
            batches += 1
        net.eval()
        with torch.no_grad():
            pred = net(xv)
            rmse = float(((pred - yv) ** 2).mean().sqrt()) * 32
            corr = float(np.corrcoef(pred.cpu().numpy(), yv.cpu().numpy())[0, 1])
        print(f"epoch {epoch + 1:3d}  train {total / batches:.4f}  "
              f"val RMSE {rmse:.2f} cells  corr {corr:.3f}")

    torch.save({"model": net.state_dict(), "w": w, "h": h}, args.out)
    print(f"saved {args.out}")


if __name__ == "__main__":
    main()
