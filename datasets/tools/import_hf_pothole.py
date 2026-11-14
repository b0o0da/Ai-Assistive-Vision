#!/usr/bin/env python3
"""Import keremberke/pothole-segmentation from Hugging Face into YOLO format.

Fetches the dataset's train/validation/test Parquet files over the public HF
Parquet API (no auth needed), decodes the embedded JPEG bytes, and writes
YOLO-normalized bounding-box labels (single class: pothole) derived from the
dataset's COCO-style [x, y, w, h] boxes. Does not run any model.
"""
from __future__ import annotations

import argparse
import io
from pathlib import Path
from urllib.request import Request, urlopen

import pandas as pd
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUT = ROOT / "datasets/data/detection/public_sources/hf_pothole_keremberke"
PARQUET_URL = (
    "https://huggingface.co/api/datasets/keremberke/pothole-segmentation/"
    "parquet/full/{split}/0.parquet"
)
SPLITS = ("train", "validation", "test")
USER_AGENT = "EgyptianAssistiveVisionDataset/1.0 (research project)"


def fetch_parquet(split: str, cache_dir: Path) -> Path:
    cache_dir.mkdir(parents=True, exist_ok=True)
    destination = cache_dir / f"{split}.parquet"
    if destination.exists():
        return destination
    request = Request(PARQUET_URL.format(split=split), headers={"User-Agent": USER_AGENT})
    with urlopen(request, timeout=60) as response:
        destination.write_bytes(response.read())
    return destination


def convert_split(parquet_path: Path, split: str, out_root: Path) -> int:
    df = pd.read_parquet(parquet_path)
    image_dir = out_root / "images" / split
    label_dir = out_root / "labels" / split
    image_dir.mkdir(parents=True, exist_ok=True)
    label_dir.mkdir(parents=True, exist_ok=True)

    count = 0
    for _, row in df.iterrows():
        image_id = row["image_id"]
        width, height = int(row["width"]), int(row["height"])
        image_bytes = row["image"]["bytes"]

        # Re-encode through Pillow to confirm the embedded bytes decode as valid JPEG.
        image = Image.open(io.BytesIO(image_bytes))
        image.load()
        filename = f"pothole_{image_id:06d}"
        (image_dir / f"{filename}.jpg").write_bytes(image_bytes)

        lines = []
        for x, y, w, h in row["objects"]["bbox"]:
            cx = (x + w / 2) / width
            cy = (y + h / 2) / height
            nw = w / width
            nh = h / height
            lines.append(f"0 {cx:.6f} {cy:.6f} {nw:.6f} {nh:.6f}")
        (label_dir / f"{filename}.txt").write_text("\n".join(lines) + ("\n" if lines else ""))
        count += 1
    return count


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--cache-dir", type=Path, default=Path("/tmp/hf_pothole"))
    args = parser.parse_args()

    args.output.mkdir(parents=True, exist_ok=True)
    totals = {}
    for split in SPLITS:
        parquet_path = fetch_parquet(split, args.cache_dir)
        totals[split] = convert_split(parquet_path, split, args.output)
        print(f"{split}: {totals[split]} images converted")

    (args.output / "classes.txt").write_text("pothole\n")
    print(f"Done. Images+labels written under {args.output}")
    print(f"Totals: {totals}")


if __name__ == "__main__":
    main()
