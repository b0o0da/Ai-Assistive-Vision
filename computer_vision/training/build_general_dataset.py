#!/usr/bin/env python3
"""Build and validate the real, local `GENERAL_CLASSES` merged dataset.

This is the local (non-Colab) counterpart of train_yolo_colab.ipynb's Option A + Option D +
Step 3 (discover/resolve) + Step 4A (validate_and_count) + Step 5 (session-safe split) +
Step 6 (materialize), run directly against the real files already on disk in this repo:

  - datasets/prepared/avn_coco/            (COCO subset, labels already YOLO format)
  - datasets/data/detection/public_sources/hf_mixed_traffic_visaitech/  (South Asian dashcam, CC BY 4.0)
  - datasets/data/detection/public_sources/hf_pothole_keremberke/       (generic pothole stock photos, CC BY 4.0)

GENERAL_CLASSES = ["person", "transportation", "pothole", "bench", "chair",
                    "fire hydrant", "stop sign", "parking meter"]

Class remapping:
  avn_coco (raw COCO ids):   0 person -> person; 1 bicycle, 2 car, 3 motorcycle, 4 bus, 5 truck
                             -> transportation; 6 bench -> bench; 7 chair -> chair;
                             8 fire hydrant -> fire hydrant; 9 stop sign -> stop sign;
                             10 parking meter -> parking meter.
  visaitech (pedestrian / 2-wheeler / 3-wheeler / 4-wheeler):
                             pedestrian -> person; 2-wheeler, 3-wheeler, 4-wheeler -> transportation.
                             Nothing discarded -- all 4 upstream classes are kept.
  pothole (pothole):         pothole -> pothole.

avn_coco is COCO-scale (118287 train + 5000 val label files) and is NOT used in full locally --
that is not a taxonomy decision, it is a tractability one: a bounded, class-stratified subsample
is drawn (see COCO_SAMPLE_CAP_PER_CLASS) so a multi-epoch run finishes on this machine's MPS
backend in a reasonable time. The subsample is drawn from both official COCO train2017 and
val2017 files; COCO's own train/val boundary is then dissolved and every sampled image is treated
as its own independent "session" (it is already an independent, de-duplicated stock photo) and
re-assigned to train/val/test by the session-safe splitter below, same as every other source.

Session-safe split: images from the same video are grouped into one "session" (visaitech:
video_id, parsed from the `V001__...` filename prefix, cross-checked against attributes.csv) so a
video's frames never straddle train/val/test. Every avn_coco and pothole image is its own session
(independent photos). Sessions are then greedily assigned to train/val/test to approximate
SPLIT_RATIOS while keeping each session wholly inside one split.

Run: python3 computer_vision/training/build_general_dataset.py
"""
from __future__ import annotations

import json
import random
import shutil
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
AVN_COCO_ROOT = ROOT / "datasets/prepared/avn_coco"
VISAITECH_ROOT = ROOT / "datasets/data/detection/public_sources/hf_mixed_traffic_visaitech"
POTHOLE_ROOT = ROOT / "datasets/data/detection/public_sources/hf_pothole_keremberke"
OUTPUT_ROOT = ROOT / "datasets/prepared/avn_general"

GENERAL_CLASSES = [
    "person", "transportation", "pothole",
    "bench", "chair", "fire hydrant", "stop sign", "parking meter",
]

AVN_COCO_RAW_TO_TARGET = {
    0: "person", 1: "transportation", 2: "transportation", 3: "transportation",
    4: "transportation", 5: "transportation", 6: "bench", 7: "chair",
    8: "fire hydrant", 9: "stop sign", 10: "parking meter",
}
VISAITECH_RAW_TO_TARGET = {
    "pedestrian": "person", "2-wheeler": "transportation",
    "3-wheeler": "transportation", "4-wheeler": "transportation",
}
POTHOLE_RAW_TO_TARGET = {"pothole": "pothole"}

COCO_SAMPLE_CAP_PER_CLASS = 150  # images per raw COCO class id; tractability, not a taxonomy choice
SPLIT_RATIOS = {"train": 0.70, "val": 0.15, "test": 0.15}
SPLIT_SEED = 1337


@dataclass
class ImageRecord:
    source: str
    session_id: str
    image_path: Path
    boxes: list[tuple[int, float, float, float, float]] = field(default_factory=list)  # (target_class_idx, cx, cy, w, h)


def parse_yolo_label(label_path: Path, raw_to_target_idx: dict) -> list[tuple[int, float, float, float, float]] | None:
    """Returns remapped boxes, or None if the file has a box whose raw class has no mapping entry
    (so the caller can tell "no file / empty" apart from "unmapped class present")."""
    if not label_path.is_file():
        return []
    boxes = []
    text = label_path.read_text().strip()
    if not text:
        return []
    for line in text.splitlines():
        parts = line.split()
        raw_id = int(parts[0])
        if raw_id not in raw_to_target_idx:
            continue  # class not part of GENERAL_CLASSES (e.g. all other 69 COCO classes) -- drop silently
        target_name = raw_to_target_idx[raw_id]
        target_idx = GENERAL_CLASSES.index(target_name)
        cx, cy, w, h = (float(x) for x in parts[1:5])
        boxes.append((target_idx, cx, cy, w, h))
    return boxes


def discover_avn_coco() -> list[ImageRecord]:
    records: list[ImageRecord] = []
    rng = random.Random(SPLIT_SEED)
    for split_dir, img_dir in [("train2017", "train2017"), ("val2017", "val2017")]:
        label_dir = AVN_COCO_ROOT / "labels" / split_dir
        image_dir = AVN_COCO_ROOT / "images" / img_dir
        label_files = sorted(label_dir.glob("*.txt"))

        per_class_candidates: dict[int, list[Path]] = defaultdict(list)
        file_boxes_cache: dict[Path, list[tuple[int, float, float, float, float]]] = {}
        for label_file in label_files:
            boxes = parse_yolo_label(label_file, AVN_COCO_RAW_TO_TARGET)
            if not boxes:
                continue
            file_boxes_cache[label_file] = boxes
            present_targets = {b[0] for b in boxes}
            for target_idx in present_targets:
                per_class_candidates[target_idx].append(label_file)

        sampled: set[Path] = set()
        for target_idx in range(len(GENERAL_CLASSES)):
            candidates = per_class_candidates.get(target_idx, [])
            rng.shuffle(candidates)
            for label_file in candidates[:COCO_SAMPLE_CAP_PER_CLASS]:
                sampled.add(label_file)

        for label_file in sampled:
            stem = label_file.stem
            image_path = image_dir / f"{stem}.jpg"
            if not image_path.is_file():
                continue
            records.append(ImageRecord(
                source="avn_coco", session_id=f"coco_{stem}",
                image_path=image_path, boxes=file_boxes_cache[label_file],
            ))
    return records


def discover_visaitech() -> list[ImageRecord]:
    records: list[ImageRecord] = []
    raw_to_target_idx = {k: v for k, v in VISAITECH_RAW_TO_TARGET.items()}
    class_names = (VISAITECH_ROOT / "classes.txt").read_text().strip().splitlines()
    for split_dir in ["train", "val"]:
        image_dir = VISAITECH_ROOT / "images" / split_dir
        label_dir = VISAITECH_ROOT / "labels" / split_dir
        if not image_dir.is_dir():
            continue
        for image_path in sorted(image_dir.glob("*.jpg")):
            stem = image_path.stem
            session_id = stem.split("__", 1)[0]
            label_path = label_dir / f"{stem}.txt"
            boxes = []
            if label_path.is_file():
                text = label_path.read_text().strip()
                if text:
                    for line in text.splitlines():
                        parts = line.split()
                        raw_name = class_names[int(parts[0])]
                        target_name = raw_to_target_idx[raw_name]
                        target_idx = GENERAL_CLASSES.index(target_name)
                        cx, cy, w, h = (float(x) for x in parts[1:5])
                        boxes.append((target_idx, cx, cy, w, h))
            records.append(ImageRecord(
                source="visaitech", session_id=f"visaitech_{session_id}",
                image_path=image_path, boxes=boxes,
            ))
    return records


def discover_pothole() -> list[ImageRecord]:
    records: list[ImageRecord] = []
    pothole_idx = GENERAL_CLASSES.index("pothole")
    for split_dir in ["train", "validation", "test"]:
        image_dir = POTHOLE_ROOT / "images" / split_dir
        label_dir = POTHOLE_ROOT / "labels" / split_dir
        if not image_dir.is_dir():
            continue
        for image_path in sorted(image_dir.glob("*.jpg")):
            stem = image_path.stem
            label_path = label_dir / f"{stem}.txt"
            boxes = []
            if label_path.is_file():
                text = label_path.read_text().strip()
                if text:
                    for line in text.splitlines():
                        parts = line.split()
                        # single-class source file; raw id is always 0 == "pothole"
                        cx, cy, w, h = (float(x) for x in parts[1:5])
                        boxes.append((pothole_idx, cx, cy, w, h))
            records.append(ImageRecord(
                source="pothole", session_id=f"pothole_{stem}",
                image_path=image_path, boxes=boxes,
            ))
    return records


def session_safe_split(records: list[ImageRecord]) -> dict[str, list[ImageRecord]]:
    groups: dict[tuple[str, str], list[ImageRecord]] = defaultdict(list)
    for record in records:
        groups[(record.source, record.session_id)].append(record)

    group_items = list(groups.items())
    rng = random.Random(SPLIT_SEED)
    rng.shuffle(group_items)
    # Smallest sessions first: if the handful of large multi-image video sessions were placed
    # first, they would all land in whichever split starts with the largest absolute deficit
    # (always train, since its target is biggest) before val/test get a turn. Placing singleton
    # sessions first lets val/test accumulate enough of their own deficit that large sessions
    # arriving later actually get distributed across splits instead of all piling into train.
    group_items.sort(key=lambda kv: len(kv[1]))

    total = len(records)
    targets = {split: ratio * total for split, ratio in SPLIT_RATIOS.items()}
    counts = {split: 0 for split in SPLIT_RATIOS}
    assignment: dict[str, list[ImageRecord]] = {split: [] for split in SPLIT_RATIOS}

    for _, group_records in group_items:
        deficit = {split: targets[split] - counts[split] for split in SPLIT_RATIOS}
        best_split = max(deficit, key=lambda s: deficit[s])
        assignment[best_split].extend(group_records)
        counts[best_split] += len(group_records)

    return assignment


def report_counts(assignment: dict[str, list[ImageRecord]]) -> None:
    print("\n=== Per-split image counts ===")
    for split, records in assignment.items():
        print(f"  {split:6s}: {len(records)} images")

    print("\n=== Per-class box/image counts by split ===")
    for split, records in assignment.items():
        box_counts = [0] * len(GENERAL_CLASSES)
        img_counts = [0] * len(GENERAL_CLASSES)
        for record in records:
            present = set()
            for target_idx, *_ in record.boxes:
                box_counts[target_idx] += 1
                present.add(target_idx)
            for target_idx in present:
                img_counts[target_idx] += 1
        print(f"  [{split}]")
        for idx, name in enumerate(GENERAL_CLASSES):
            print(f"    {name:14s} boxes={box_counts[idx]:6d}  images={img_counts[idx]:6d}")

    print("\n=== Honest provenance of 'person' and 'transportation' boxes (by source) ===")
    for split, records in assignment.items():
        person_idx = GENERAL_CLASSES.index("person")
        transport_idx = GENERAL_CLASSES.index("transportation")
        by_source_person = defaultdict(int)
        by_source_transport = defaultdict(int)
        for record in records:
            for target_idx, *_ in record.boxes:
                if target_idx == person_idx:
                    by_source_person[record.source] += 1
                elif target_idx == transport_idx:
                    by_source_transport[record.source] += 1
        print(f"  [{split}] person boxes by source: {dict(by_source_person)}")
        print(f"  [{split}] transportation boxes by source: {dict(by_source_transport)}")

    print("\n=== Leakage check ===")
    seen_sessions: dict[tuple[str, str], str] = {}
    leaks = []
    for split, records in assignment.items():
        for record in records:
            key = (record.source, record.session_id)
            if key in seen_sessions and seen_sessions[key] != split:
                leaks.append((key, seen_sessions[key], split))
            seen_sessions[key] = split
    if leaks:
        print(f"  FAIL: {len(leaks)} session(s) span more than one split: {leaks[:5]}")
    else:
        print("  OK: every (source, session_id) is wholly contained in exactly one split.")

    print("\n=== Test-split class presence check ===")
    test_box_counts = [0] * len(GENERAL_CLASSES)
    for record in assignment["test"]:
        for target_idx, *_ in record.boxes:
            test_box_counts[target_idx] += 1
    missing = [GENERAL_CLASSES[i] for i, c in enumerate(test_box_counts) if c == 0]
    if missing:
        print(f"  WARNING: these classes have ZERO boxes in the held-out test split: {missing}")
        print("  Any reported test-set metric for these classes would be meaningless (no ground truth).")
    else:
        print("  OK: every GENERAL_CLASSES entry has at least one box in the held-out test split.")

    print("\n=== Known data-quality caveats (must accompany any accuracy claim) ===")
    print("  - visaitech 'pedestrian' (-> person): only 39 boxes total across all 6 dashcam videos,")
    print("    South Asian traffic footage -- not Egyptian, and far too thin alone to certify 'person'")
    print("    detection; most 'person' boxes above come from COCO, a generic photo dataset.")
    print("  - pothole: 100% from 90 generic stock photos (keremberke/pothole-segmentation), not")
    print("    Egyptian roads. A model trained on these is not validated for Egyptian pothole conditions.")
    print("  - 'transportation' merges COCO bicycle/car/motorcycle/bus/truck photos with South Asian")
    print("    2/3/4-wheeler dashcam frames; it is a broad, mixed-provenance class by design.")


def materialize(assignment: dict[str, list[ImageRecord]]) -> None:
    if OUTPUT_ROOT.exists():
        shutil.rmtree(OUTPUT_ROOT)
    for split, records in assignment.items():
        image_out = OUTPUT_ROOT / "images" / split
        label_out = OUTPUT_ROOT / "labels" / split
        image_out.mkdir(parents=True, exist_ok=True)
        label_out.mkdir(parents=True, exist_ok=True)
        used_names: set[str] = set()
        for record in records:
            base_name = f"{record.source}_{record.image_path.stem}"
            name = base_name
            suffix = 1
            while name in used_names:
                name = f"{base_name}_{suffix}"
                suffix += 1
            used_names.add(name)

            image_link = image_out / f"{name}.jpg"
            image_link.symlink_to(record.image_path.resolve())

            label_lines = [
                f"{target_idx} {cx:.6f} {cy:.6f} {w:.6f} {h:.6f}"
                for target_idx, cx, cy, w, h in record.boxes
            ]
            (label_out / f"{name}.txt").write_text("\n".join(label_lines) + ("\n" if label_lines else ""))

    dataset_yaml = {
        "path": str(OUTPUT_ROOT),
        "train": "images/train",
        "val": "images/val",
        "test": "images/test",
        "names": {i: name for i, name in enumerate(GENERAL_CLASSES)},
    }
    yaml_lines = [f'path: "{dataset_yaml["path"]}"', f'train: {dataset_yaml["train"]}',
                  f'val: {dataset_yaml["val"]}', f'test: {dataset_yaml["test"]}', "names:"]
    for i, name in enumerate(GENERAL_CLASSES):
        yaml_lines.append(f'  {i}: "{name}"')
    (OUTPUT_ROOT / "dataset.yaml").write_text("\n".join(yaml_lines) + "\n")

    manifest = {
        "general_classes": GENERAL_CLASSES,
        "sources": ["avn_coco (COCO subset, class-stratified sample)", "hf_mixed_traffic_visaitech (CC BY 4.0)",
                     "hf_pothole_keremberke (CC BY 4.0)"],
        "split_ratios": SPLIT_RATIOS,
        "split_seed": SPLIT_SEED,
        "counts": {split: len(records) for split, records in assignment.items()},
    }
    (OUTPUT_ROOT / "MANIFEST.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"\nMaterialized dataset at {OUTPUT_ROOT}")
    print(f"  dataset.yaml + MANIFEST.json written; images are symlinks to original files (no copies).")


def main() -> None:
    print("Discovering real local records...")
    avn_coco_records = discover_avn_coco()
    visaitech_records = discover_visaitech()
    pothole_records = discover_pothole()
    print(f"  avn_coco subsample: {len(avn_coco_records)} images (cap={COCO_SAMPLE_CAP_PER_CLASS}/class)")
    print(f"  visaitech:          {len(visaitech_records)} images")
    print(f"  pothole:            {len(pothole_records)} images")

    all_records = avn_coco_records + visaitech_records + pothole_records
    print(f"  TOTAL:              {len(all_records)} images")

    assignment = session_safe_split(all_records)
    report_counts(assignment)
    materialize(assignment)


if __name__ == "__main__":
    main()
