"""Deterministic synthetic test images for the export self-test pipeline.

Not camera frames -- these are generated from a fixed seed, never read
from a device or dataset, and (per android/CLAUDE.md's "no frame
persistence" spirit, applied here to this test tooling) are only ever
materialized in a caller-provided temp directory that the caller is
responsible for cleaning up; nothing here writes into the repo.
"""
from __future__ import annotations

from pathlib import Path

import numpy as np


def generate_synthetic_batch(
    n: int, height: int = 320, width: int = 320, seed: int = 0
) -> list[np.ndarray]:
    """Return `n` deterministic (3, height, width) float32 CHW arrays in [0, 1].

    Each image is a distinct seeded-random pattern (not blank, not
    identical) so that per-image box comparisons in test_export.py are
    meaningful rather than trivially passing on a constant input.
    """
    images = []
    for i in range(n):
        rng = np.random.default_rng(seed + i)
        arr = rng.random((height, width, 3), dtype=np.float64).astype(np.float32)
        images.append(arr.transpose(2, 0, 1))  # CHW
    return images


def save_as_pngs(images: list[np.ndarray], directory: Path) -> list[Path]:
    """Write CHW float32 [0, 1] images out as PNGs, for manual CLI runs.

    Not used by the automated tests (which keep everything in memory /
    in a temp dir) -- provided so a human can run `export.py
    --calibration-images` against real files if they want to inspect them.
    """
    from PIL import Image

    directory.mkdir(parents=True, exist_ok=True)
    paths = []
    for idx, chw in enumerate(images):
        hwc = (chw.transpose(1, 2, 0) * 255.0).clip(0, 255).astype(np.uint8)
        path = directory / f"synthetic_{idx:02d}.png"
        Image.fromarray(hwc).save(path)
        paths.append(path)
    return paths
