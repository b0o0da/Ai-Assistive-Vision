#!/usr/bin/env python3
"""Import visaitech/vehicle-mixed-traffic-detection from Hugging Face.

Downloads only the annotated frames (images/, labels/, data.yaml, classes.txt,
attributes.csv, README.md) via the HF Hub API. Deliberately skips `videos/`
(203 raw dashcam clips, ~12 GB, unannotated — out of scope and far too large
to vendor). Labels are already in YOLO format in the source dataset; this
script does not touch their content, only places the files in-repo.
"""
from __future__ import annotations

import argparse
import shutil
from pathlib import Path

from huggingface_hub import snapshot_download

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUT = ROOT / "datasets/data/detection/public_sources/hf_mixed_traffic_visaitech"
REPO_ID = "visaitech/vehicle-mixed-traffic-detection"
ALLOW_PATTERNS = [
    "images/**",
    "labels/**",
    "data.yaml",
    "classes.txt",
    "attributes.csv",
    "README.md",
]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUT)
    args = parser.parse_args()

    args.output.mkdir(parents=True, exist_ok=True)
    snapshot_dir = snapshot_download(
        repo_id=REPO_ID,
        repo_type="dataset",
        allow_patterns=ALLOW_PATTERNS,
    )
    for name in ("images", "labels", "data.yaml", "classes.txt", "attributes.csv", "README.md"):
        source = Path(snapshot_dir) / name
        destination = args.output / name
        if not source.exists():
            continue
        if destination.exists():
            if destination.is_dir():
                shutil.rmtree(destination)
            else:
                destination.unlink()
        if source.is_dir():
            shutil.copytree(source, destination)
        else:
            shutil.copy2(source, destination)

    n_train = len(list((args.output / "images/train").glob("*.jpg")))
    n_val = len(list((args.output / "images/val").glob("*.jpg")))
    print(f"train images: {n_train}, val images: {n_val}")
    print(f"Written to {args.output}")


if __name__ == "__main__":
    main()
