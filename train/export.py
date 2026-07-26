#!/usr/bin/env python3
"""Export the trained value net to ONNX for JVM inference.

Usage: train/.venv/bin/python train/export.py [data/value-net.pt] [data/value-net.onnx]
"""
import sys

import torch

from train import ValueNet

src = sys.argv[1] if len(sys.argv) > 1 else "data/value-net.pt"
dst = sys.argv[2] if len(sys.argv) > 2 else "data/value-net.onnx"

checkpoint = torch.load(src, map_location="cpu", weights_only=True)
net = ValueNet()
net.load_state_dict(checkpoint["model"])
net.eval()

w, h = checkpoint["w"], checkpoint["h"]
dummy = torch.zeros(1, 4, h, w)
torch.onnx.export(
    net, dummy, dst,
    input_names=["planes"], output_names=["deficit"],
    dynamic_axes={"planes": {0: "batch", 2: "h", 3: "w"}, "deficit": {0: "batch"}},
    opset_version=17,
)
print(f"exported {dst} (input Bx4xHxW dynamic, output = predicted deficit / 32)")
