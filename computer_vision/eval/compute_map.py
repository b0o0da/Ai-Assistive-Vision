#!/usr/bin/env python3
"""Compute a real, self-measured mAP for both shipped ONNX detectors.

This replaces "we only ever trusted published benchmark numbers" with an
actual on-task measurement, using real local data and the exact production
decode path (letterbox -> bounded class-score read -> conf filter -> NMS ->
classMap remap), reused from verify_yolov8n_onnx_asset.py rather than
reimplemented, so this stays consistent with YoloDecoder.kt.

Two detectors, two datasets:

  1. General detector (yolo11m_int8_coco_stock.onnx): scored against the
     5000 real COCO val2017 images at datasets/prepared/avn_coco/, with
     YOLO-format ground truth for an 11-raw-class subset. Both the model's
     80-class predictions AND the 11-class ground truth are merged through
     the SAME classMap the app uses (bicycle/car/motorcycle/bus/truck ->
     "transportation") before scoring, because the merged 7-class taxonomy
     is what this app actually outputs to the user -- not raw 80-class COCO.

  2. Pothole detector (pothole_keremberke_n.onnx): scored against the real
     HF keremberke/pothole-segmentation validation split, fetched locally by
     datasets/tools/import_hf_pothole.py (box labels only; segmentation mask
     rows in the ONNX output are read-bounded out, never touched).

AP methodology: standard COCO-style AP with greedy IoU matching (per class,
per image, descending confidence, each ground-truth box usable at most once)
and official COCO 101-point recall interpolation. mAP50 = AP @ IoU 0.5;
mAP50-95 = mean AP over IoU thresholds 0.50:0.05:0.95 (10 thresholds). No
pycocotools/torchmetrics dependency -- this is implemented directly in numpy.

Usage:
    python computer_vision/eval/compute_map.py [--limit N]

--limit subsamples the first N images of EACH dataset, for fast iteration
while developing/debugging this script. Final, reported numbers must come
from a full run (no --limit).
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
import yaml
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify_yolov8n_onnx_asset import decode, letterbox, to_chw  # noqa: E402  (reused, not reimplemented)

ROOT = Path(__file__).resolve().parents[2]
ASSETS_DIR = ROOT / "android/app/src/main/assets"
CONFIG_GENERAL = ROOT / "android/app/src/main/resources/config/detector.json"
CONFIG_POTHOLE = ROOT / "android/app/src/main/resources/config/detector_pothole.json"
AVN_COCO = ROOT / "datasets/prepared/avn_coco"
POTHOLE_ROOT = ROOT / "datasets/data/detection/public_sources/hf_pothole_keremberke"

IOU_THRESHOLDS = [round(0.5 + 0.05 * i, 2) for i in range(10)]  # 0.50 .. 0.95


def box_iou_xyxy(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    """a: (N,4) b:(M,4) xyxy pixel boxes -> (N,M) IoU matrix."""
    area_a = np.clip(a[:, 2] - a[:, 0], 0, None) * np.clip(a[:, 3] - a[:, 1], 0, None)
    area_b = np.clip(b[:, 2] - b[:, 0], 0, None) * np.clip(b[:, 3] - b[:, 1], 0, None)
    inter_x1 = np.maximum(a[:, 0:1], b[None, :, 0])
    inter_y1 = np.maximum(a[:, 1:2], b[None, :, 1])
    inter_x2 = np.minimum(a[:, 2:3], b[None, :, 2])
    inter_y2 = np.minimum(a[:, 3:4], b[None, :, 3])
    inter = np.clip(inter_x2 - inter_x1, 0, None) * np.clip(inter_y2 - inter_y1, 0, None)
    union = area_a[:, None] + area_b[None, :] - inter
    return np.where(union > 0, inter / union, 0.0)


def coco_101point_ap(recall: np.ndarray, precision: np.ndarray) -> float:
    """Official COCO AP: precision envelope + 101-point recall interpolation."""
    recall = np.concatenate(([0.0], recall, [1.0]))
    precision = np.concatenate(([0.0], precision, [0.0]))
    for i in range(len(precision) - 2, -1, -1):
        precision[i] = max(precision[i], precision[i + 1])
    sample_points = np.linspace(0.0, 1.0, 101)
    interpolated = np.interp(sample_points, recall, precision)
    return float(np.mean(interpolated))


def average_precision_for_class(preds: list[tuple[str, float, tuple]],
                                 gts_by_image: dict[str, list[tuple]],
                                 iou_threshold: float) -> float | None:
    """preds: (image_id, confidence, xyxy) across all images for one class.
    gts_by_image: image_id -> list of xyxy ground-truth boxes for that class.
    Returns None if the class has zero ground-truth instances (excluded from
    the mean, same convention pycocotools/Ultralytics use for absent classes).
    """
    total_gt = sum(len(boxes) for boxes in gts_by_image.values())
    if total_gt == 0:
        return None
    if not preds:
        return 0.0

    preds_sorted = sorted(preds, key=lambda p: -p[1])
    matched = {image_id: np.zeros(len(boxes), dtype=bool) for image_id, boxes in gts_by_image.items()}
    tp = np.zeros(len(preds_sorted))
    fp = np.zeros(len(preds_sorted))

    for i, (image_id, _confidence, box) in enumerate(preds_sorted):
        candidates = gts_by_image.get(image_id, [])
        if not candidates:
            fp[i] = 1
            continue
        cand_arr = np.asarray(candidates, dtype=np.float64)
        ious = box_iou_xyxy(np.asarray([box], dtype=np.float64), cand_arr)[0]
        available = ~matched[image_id]
        ious_available = np.where(available, ious, -1.0)
        best_idx = int(np.argmax(ious_available))
        if ious_available[best_idx] >= iou_threshold:
            tp[i] = 1
            matched[image_id][best_idx] = True
        else:
            fp[i] = 1

    cum_tp = np.cumsum(tp)
    cum_fp = np.cumsum(fp)
    recall = cum_tp / total_gt
    precision = cum_tp / np.clip(cum_tp + cum_fp, 1e-9, None)
    return coco_101point_ap(recall, precision)


def summarize(title: str, classes: list[str],
              preds_by_class: dict[str, list], gts_by_class: dict[str, dict],
              num_images: int, elapsed_seconds: float) -> dict:
    print(f"\n=== {title} ===")
    print(f"Images evaluated: {num_images}  |  wall-clock: {elapsed_seconds:.1f}s "
          f"({elapsed_seconds / max(num_images, 1):.3f}s/image)")

    per_class_ap50: dict[str, float] = {}
    per_class_ap5095: dict[str, float] = {}
    per_class_gt_count: dict[str, int] = {}
    per_class_pred_count: dict[str, int] = {}

    for cls in classes:
        gt_count = sum(len(v) for v in gts_by_class[cls].values())
        pred_count = len(preds_by_class[cls])
        per_class_gt_count[cls] = gt_count
        per_class_pred_count[cls] = pred_count

        aps = [average_precision_for_class(preds_by_class[cls], gts_by_class[cls], t) for t in IOU_THRESHOLDS]
        ap50 = aps[0]
        valid_aps = [a for a in aps if a is not None]
        ap5095 = float(np.mean(valid_aps)) if valid_aps else None
        per_class_ap50[cls] = ap50 if ap50 is not None else float("nan")
        per_class_ap5095[cls] = ap5095 if ap5095 is not None else float("nan")

    print(f"{'class':14s} {'GT boxes':>9s} {'preds':>7s} {'AP50':>7s} {'AP50-95':>8s}")
    for cls in classes:
        print(f"{cls:14s} {per_class_gt_count[cls]:9d} {per_class_pred_count[cls]:7d} "
              f"{per_class_ap50[cls]:7.3f} {per_class_ap5095[cls]:8.3f}")

    valid_ap50 = [v for v in per_class_ap50.values() if not np.isnan(v)]
    valid_ap5095 = [v for v in per_class_ap5095.values() if not np.isnan(v)]
    map50 = float(np.mean(valid_ap50)) if valid_ap50 else float("nan")
    map5095 = float(np.mean(valid_ap5095)) if valid_ap5095 else float("nan")
    print(f"\nmAP50    = {map50:.4f}")
    print(f"mAP50-95 = {map5095:.4f}")

    return {
        "num_images": num_images, "elapsed_seconds": elapsed_seconds,
        "per_class_ap50": per_class_ap50, "per_class_ap50_95": per_class_ap5095,
        "per_class_gt_count": per_class_gt_count, "per_class_pred_count": per_class_pred_count,
        "mAP50": map50, "mAP50_95": map5095,
    }


def evaluate_general(limit: int | None) -> dict:
    config = json.loads(CONFIG_GENERAL.read_text())
    model_path = ASSETS_DIR / config["modelAsset"]
    if not model_path.is_file():
        raise SystemExit(f"General detector asset not found: {model_path}")

    session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name

    dataset_yaml = yaml.safe_load((AVN_COCO / "dataset.yaml").read_text())
    raw_names = [dataset_yaml["names"][i] for i in range(len(dataset_yaml["names"]))]
    class_map: dict[str, str] = config.get("classMap", {})
    merged_classes: list[str] = config["classes"]

    image_dir = AVN_COCO / "images/val2017"
    label_dir = AVN_COCO / "labels/val2017"
    if not image_dir.is_dir() or not label_dir.is_dir():
        raise SystemExit(f"General detector ground truth not found under {AVN_COCO}")

    image_paths = sorted(image_dir.glob("*.jpg"))
    if limit:
        image_paths = image_paths[:limit]

    preds_by_class: dict[str, list] = {c: [] for c in merged_classes}
    gts_by_class: dict[str, dict] = {c: {p.stem: [] for p in image_paths} for c in merged_classes}

    t0 = time.time()
    for idx, image_path in enumerate(image_paths):
        image = Image.open(image_path).convert("RGB")
        w, h = image.width, image.height

        label_path = label_dir / f"{image_path.stem}.txt"
        if label_path.is_file():
            text = label_path.read_text().strip()
            for line in text.splitlines() if text else []:
                cls_id_s, cx_s, cy_s, bw_s, bh_s = line.split()
                raw_name = raw_names[int(cls_id_s)]
                merged_name = class_map.get(raw_name, raw_name)
                if merged_name not in gts_by_class:
                    continue
                cx, cy, bw, bh = float(cx_s) * w, float(cy_s) * h, float(bw_s) * w, float(bh_s) * h
                box = (cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)
                gts_by_class[merged_name][image_path.stem].append(box)

        letterboxed, scale, pad_x, pad_y = letterbox(image, config["inputSize"])
        tensor = to_chw(letterboxed).astype(np.float32)
        output = session.run(None, {input_name: tensor})[0][0]
        detections = decode(
            output, w, h, scale, pad_x, pad_y,
            classes=set(merged_classes),
            conf_threshold=config["confidenceThreshold"],
            nms_iou=config["nmsIouThreshold"],
            model_labels=config["labels"],
            class_map=class_map,
        )
        for d in detections:
            box = (d["x"], d["y"], d["x"] + d["w"], d["y"] + d["h"])
            preds_by_class[d["label"]].append((image_path.stem, d["confidence"], box))

        if (idx + 1) % 250 == 0 or (idx + 1) == len(image_paths):
            print(f"  [general] {idx + 1}/{len(image_paths)} images...", flush=True)

    elapsed = time.time() - t0
    return summarize(
        "General detector (yolo11m_int8_coco_stock.onnx, 7-class classMap-merged)",
        merged_classes, preds_by_class, gts_by_class, len(image_paths), elapsed,
    )


def evaluate_pothole(limit: int | None) -> dict:
    config = json.loads(CONFIG_POTHOLE.read_text())
    model_path = ASSETS_DIR / config["modelAsset"]
    if not model_path.is_file():
        raise SystemExit(f"Pothole detector asset not found: {model_path}")

    image_dir = POTHOLE_ROOT / "images/validation"
    label_dir = POTHOLE_ROOT / "labels/validation"
    if not image_dir.is_dir() or not label_dir.is_dir():
        raise SystemExit(
            f"Pothole ground truth not found under {POTHOLE_ROOT}. Run first:\n"
            f"  python datasets/tools/import_hf_pothole.py"
        )

    session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name
    classes = config["classes"]  # ["pothole"]

    image_paths = sorted(image_dir.glob("*.jpg"))
    if limit:
        image_paths = image_paths[:limit]

    preds_by_class: dict[str, list] = {c: [] for c in classes}
    gts_by_class: dict[str, dict] = {c: {p.stem: [] for p in image_paths} for c in classes}

    t0 = time.time()
    for idx, image_path in enumerate(image_paths):
        image = Image.open(image_path).convert("RGB")
        w, h = image.width, image.height

        label_path = label_dir / f"{image_path.stem}.txt"
        if label_path.is_file():
            text = label_path.read_text().strip()
            for line in text.splitlines() if text else []:
                cls_id_s, cx_s, cy_s, bw_s, bh_s = line.split()
                cls_name = classes[int(cls_id_s)]
                cx, cy, bw, bh = float(cx_s) * w, float(cy_s) * h, float(bw_s) * w, float(bh_s) * h
                box = (cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)
                gts_by_class[cls_name][image_path.stem].append(box)

        letterboxed, scale, pad_x, pad_y = letterbox(image, config["inputSize"])
        tensor = to_chw(letterboxed).astype(np.float32)
        output = session.run(None, {input_name: tensor})[0][0]
        detections = decode(
            output, w, h, scale, pad_x, pad_y,
            classes=set(classes),
            conf_threshold=config["confidenceThreshold"],
            nms_iou=config["nmsIouThreshold"],
            model_labels=config["labels"],
            class_map=None,
        )
        for d in detections:
            box = (d["x"], d["y"], d["x"] + d["w"], d["y"] + d["h"])
            preds_by_class[d["label"]].append((image_path.stem, d["confidence"], box))

        if (idx + 1) % 50 == 0 or (idx + 1) == len(image_paths):
            print(f"  [pothole] {idx + 1}/{len(image_paths)} images...", flush=True)

    elapsed = time.time() - t0
    return summarize(
        "Pothole detector (pothole_keremberke_n.onnx, 1-class)",
        classes, preds_by_class, gts_by_class, len(image_paths), elapsed,
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--limit", type=int, default=None,
                         help="Subsample the first N images of EACH dataset (dev/debug only; "
                              "final reported numbers must come from a full run).")
    args = parser.parse_args()

    overall_start = time.time()
    general_result = evaluate_general(args.limit)
    pothole_result = evaluate_pothole(args.limit)
    total_elapsed = time.time() - overall_start

    print(f"\n=== Total wall-clock for both evaluations: {total_elapsed:.1f}s ===")
    print(json.dumps({"general": general_result, "pothole": pothole_result}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
