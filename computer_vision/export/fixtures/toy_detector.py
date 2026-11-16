"""A tiny, synthetic stand-in detector used ONLY to self-test the export
pipeline in this package (export.py, test_export.py).

This is NOT the production detector. It is not YOLO, it is not trained on
any dataset, and it carries no licence question -- it exists purely so
`export.py`'s ONNX/TFLite conversion and the box-comparison tests in
test_export.py can run on a laptop without a real checkpoint and without
touching TD-001 (YOLO nano AGPL vs an Apache-licensed detector, still Open
per docs/decisions/README.md). Its weights are generated at import time
from a fixed seed; nothing is loaded from or written to disk, so there is
no checkpoint file to accidentally commit.

Output shape matches the real detector's documented contract anyway
(docs/architecture.md WS-05, models/README.md manifest "output" field):
`(1, N, 6)` = boxes xyxy (normalized to the input size) + objectness score
+ class id, so the export/verify code in this package exercises the same
shape the real detector will eventually produce.
"""
from __future__ import annotations

import torch
from torch import nn

_SEED = 1234
_GRID = 5  # N = _GRID * _GRID detections


class ToyDetector(nn.Module):
    """Downsample -> fixed 5x5 grid -> 6 channels (xyxy, score, class)."""

    def __init__(self) -> None:
        super().__init__()
        self.backbone = nn.Sequential(
            nn.Conv2d(3, 8, kernel_size=3, stride=2, padding=1),
            nn.ReLU(inplace=True),
            nn.Conv2d(8, 16, kernel_size=3, stride=2, padding=1),
            nn.ReLU(inplace=True),
            nn.AdaptiveAvgPool2d((_GRID, _GRID)),
        )
        self.head = nn.Conv2d(16, 6, kernel_size=1)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        features = self.backbone(x)
        raw = self.head(features)  # (B, 6, GRID, GRID)
        bounded = torch.sigmoid(raw)  # keep every channel in [0, 1], incl. the xyxy box channels
        batch = bounded.shape[0]
        # (B, 6, GRID, GRID) -> (B, GRID*GRID, 6)
        return bounded.permute(0, 2, 3, 1).reshape(batch, _GRID * _GRID, 6)


def load_toy_detector(checkpoint_path: str | None = None) -> nn.Module:
    """Loader matching export.py's "module:function" contract.

    `checkpoint_path` is accepted (to satisfy the loader contract) and
    ignored: this fixture is self-contained and seeds its own weights so
    it is reproducible on its own, independent of any caller-side seeding.
    """
    del checkpoint_path  # intentionally unused; see docstring
    generator = torch.Generator().manual_seed(_SEED)
    model = ToyDetector()
    with torch.no_grad():
        for param in model.parameters():
            param.copy_(torch.empty_like(param).uniform_(-0.5, 0.5, generator=generator))
    model.eval()
    return model
