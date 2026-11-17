#!/usr/bin/env python3
"""Prepare the already-downloaded COCO 2017 data as a small YOLO-format hazard dataset.

The COCO images remain in place and are exposed by symlinks. Only labels and a dataset
manifest are generated under datasets/prepared/, which is ignored by Git.
"""
from __future__ import annotations

import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path

try:
    import orjson
except ImportError:  # pragma: no cover - stdlib fallback for clean environments
    orjson = None


ROOT = Path(__file__).resolve().parents[2]
COCO_ROOT = ROOT / "datasets/data/detection/coco"
DEFAULT_CLASSES = [
    "person", "bicycle", "car", "motorcycle", "bus", "truck",
    "bench", "chair", "fire hydrant", "stop sign", "parking meter",
]


def load_json(path: Path):
    raw = path.read_bytes()
    return orjson.loads(raw) if orjson else json.loads(raw)


def yaml_quote(text: str) -> str:
    return json.dumps(text, ensure_ascii=False)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classes", default=",".join(DEFAULT_CLASSES),
                        help="comma-separated COCO categories to keep; default is a starter hazard set")
    parser.add_argument("--output", type=Path, default=ROOT / "datasets/prepared/avn_coco")
    parser.add_argument("--split", choices=("train", "val", "both"), default="both")
    args = parser.parse_args()
    class_names = [name.strip() for name in args.classes.split(",") if name.strip()]
    if not class_names or len(set(class_names)) != len(class_names):
        raise SystemExit("Choose one or more unique class names.")

    splits = ("train", "val") if args.split == "both" else (args.split,)
    total_counts: Counter[str] = Counter()
    for split in splits:
        coco_split = "train2017" if split == "train" else "val2017"
        annotation_path = COCO_ROOT / "annotations/annotations" / f"instances_{coco_split}.json"
        image_source = COCO_ROOT / coco_split
        if not annotation_path.is_file() or not image_source.is_dir():
            raise SystemExit(f"Missing local COCO {coco_split} files: {annotation_path}")

        data = load_json(annotation_path)
        category_ids = {category["name"]: category["id"] for category in data["categories"]}
        missing = sorted(set(class_names) - set(category_ids))
        if missing:
            raise SystemExit(f"Unknown COCO categories: {', '.join(missing)}")
        class_index = {category_ids[name]: index for index, name in enumerate(class_names)}
        image_by_id = {image["id"]: image for image in data["images"]}
        labels_by_image: dict[int, list[str]] = defaultdict(list)
        counts: Counter[str] = Counter()

        for annotation in data["annotations"]:
            category_id = annotation["category_id"]
            if category_id not in class_index or annotation.get("iscrowd", 0):
                continue
            image = image_by_id.get(annotation["image_id"])
            if image is None:
                continue
            x, y, width, height = annotation["bbox"]
            image_width, image_height = image["width"], image["height"]
            left = max(0.0, min(float(image_width), float(x)))
            top = max(0.0, min(float(image_height), float(y)))
            right = max(left, min(float(image_width), float(x + width)))
            bottom = max(top, min(float(image_height), float(y + height)))
            box_width, box_height = right - left, bottom - top
            if box_width < 1 or box_height < 1:
                continue
            center_x = (left + right) / (2 * image_width)
            center_y = (top + bottom) / (2 * image_height)
            norm_width, norm_height = box_width / image_width, box_height / image_height
            labels_by_image[image["id"]].append(
                f"{class_index[category_id]} {center_x:.6f} {center_y:.6f} {norm_width:.6f} {norm_height:.6f}"
            )
            counts[class_names[class_index[category_id]]] += 1

        image_link = args.output / "images" / coco_split
        image_link.parent.mkdir(parents=True, exist_ok=True)
        if image_link.is_symlink():
            if image_link.resolve() != image_source.resolve():
                image_link.unlink()
        elif image_link.exists():
            raise SystemExit(f"Refusing to replace non-symlink image path: {image_link}")
        if not image_link.exists():
            image_link.symlink_to(image_source, target_is_directory=True)

        label_dir = args.output / "labels" / coco_split
        label_dir.mkdir(parents=True, exist_ok=True)
        for image in data["images"]:
            (label_dir / f"{Path(image['file_name']).stem}.txt").write_text(
                "\n".join(labels_by_image.get(image["id"], [])), encoding="utf-8"
            )
        total_counts.update(counts)
        print(f"{coco_split}: images={len(data['images'])}, kept_boxes={sum(counts.values())}")
        del data, image_by_id, labels_by_image

    args.output.mkdir(parents=True, exist_ok=True)
    yaml_lines = [f"path: {yaml_quote(str(args.output.resolve()))}"]
    if "train" in splits:
        yaml_lines.append("train: images/train2017")
    if "val" in splits:
        yaml_lines.append("val: images/val2017")
    yaml_lines.append("names:")
    yaml_lines.extend(f"  {index}: {yaml_quote(name)}" for index, name in enumerate(class_names))
    (args.output / "dataset.yaml").write_text("\n".join(yaml_lines) + "\n", encoding="utf-8")
    report = [
        "# COCO hazard subset preparation",
        "",
        "Images are symlinked from the existing COCO download; labels use normalized YOLO xywh.",
        "The class list is a starter subset, not the final project labeling guideline.",
        "",
        "| Class | Boxes |",
        "|---|---:|",
    ]
    report.extend(f"| {name} | {total_counts[name]} |" for name in class_names)
    (args.output / "class_counts.md").write_text("\n".join(report) + "\n", encoding="utf-8")
    print(f"Wrote labels and dataset manifest to {args.output}")


if __name__ == "__main__":
    main()
