#!/usr/bin/env python3
"""Export the trained avn_general checkpoint to ONNX and verify it end-to-end.

Does NOT touch any Android-facing file (detector.json, manifest.json, the .onnx asset,
DetectorConfig.kt). It only produces a candidate .onnx + prints the exact detector.json /
manifest.json snippets a human (or a later, explicit step) would use to adopt it -- per the
project rule that the shipped stock COCO-80 asset is never overwritten until a new checkpoint
has actually been trained, exported, and verified to run real inference.

Run: python3 computer_vision/training/export_and_verify_general.py <run_dir>
  <run_dir> defaults to computer_vision/training/runs/avn_general_1
"""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
import yaml
from PIL import Image
from ultralytics import YOLO

ROOT = Path(__file__).resolve().parents[2]
GENERAL_CLASSES = [
    "person", "transportation", "pothole",
    "bench", "chair", "fire hydrant", "stop sign", "parking meter",
]


def letterbox(image: Image.Image, size: int):
    scale = min(size / image.width, size / image.height)
    new_w, new_h = max(1, round(image.width * scale)), max(1, round(image.height * scale))
    resized = image.resize((new_w, new_h), Image.BILINEAR)
    pad_x, pad_y = (size - new_w) / 2, (size - new_h) / 2
    canvas = Image.new("RGB", (size, size), (114, 114, 114))
    canvas.paste(resized, (round(pad_x), round(pad_y)))
    return canvas


def main() -> int:
    run_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "computer_vision/training/runs/avn_general_1"
    best_weights = run_dir / "weights" / "best.pt"
    if not best_weights.is_file():
        print(f"FAIL: no checkpoint at {best_weights} -- training has not produced a usable model.")
        return 1
    print(f"Loading checkpoint: {best_weights}")

    model = YOLO(str(best_weights))
    trained_names = [model.names[i] for i in range(len(model.names))]
    print(f"Checkpoint class order: {trained_names}")
    if trained_names != GENERAL_CLASSES:
        print(f"FAIL: checkpoint class order {trained_names} != expected GENERAL_CLASSES {GENERAL_CLASSES}")
        return 1

    export_path_str = model.export(format="onnx", imgsz=640, opset=12, simplify=True, dynamic=False)
    export_path = Path(export_path_str)
    print(f"Exported ONNX: {export_path} ({export_path.stat().st_size:,} bytes)")

    sha256 = hashlib.sha256(export_path.read_bytes()).hexdigest()
    print(f"SHA256: {sha256}")

    session = ort.InferenceSession(str(export_path), providers=["CPUExecutionProvider"])
    input_meta = session.get_inputs()[0]
    output_meta = session.get_outputs()[0]
    print(f"Session input: {input_meta.name} {input_meta.shape}, output: {output_meta.name} {output_meta.shape}")

    expected_rows = 4 + len(GENERAL_CLASSES)
    if output_meta.shape[1] != expected_rows:
        print(f"FAIL: output row count {output_meta.shape[1]} != expected {expected_rows} (4 + 8 classes)")
        return 1

    dataset_yaml = yaml.safe_load((ROOT / "datasets/prepared/avn_general/dataset.yaml").read_text())
    test_images_dir = Path(dataset_yaml["path"]) / "images" / "test"
    sample_image_path = next(iter(sorted(test_images_dir.glob("*.jpg"))), None)
    if sample_image_path is None:
        print("FAIL: no test image found to run a real inference check against.")
        return 1

    image = Image.open(sample_image_path).convert("RGB")
    letterboxed = letterbox(image, 640)
    tensor = (np.asarray(letterboxed, dtype=np.float32) / 255.0).transpose(2, 0, 1)[None, ...]

    try:
        result = session.run(None, {input_meta.name: tensor})
    except Exception as error:  # noqa: BLE001
        print(f"FAIL: ONNX inference raised {type(error).__name__}: {error}")
        return 1

    output = result[0][0]
    print(f"Inference OK on held-out test image {sample_image_path.name}. Output shape {output.shape}.")

    scores_all = output[4:]
    class_ids = np.argmax(scores_all, axis=0)
    scores = scores_all[class_ids, np.arange(scores_all.shape[1])]
    keep = scores >= 0.25
    n_detections = int(keep.sum())
    print(f"Raw candidate boxes above conf 0.25 (pre-NMS): {n_detections}")

    print("\n=== Candidate models/manifest.json entry (NOT written automatically) ===")
    manifest_entry = {
        "version": "avn-general-1",
        "file": "yolov8n_general.onnx",
        "sha256": sha256,
        "license": "AGPL-3.0",
        "source": f"Fine-tuned from Ultralytics YOLOv8n (COCO-pretrained) on datasets/prepared/avn_general "
                   f"(see computer_vision/training/build_general_dataset.py); exported opset=12, imgsz=640.",
        "input": "[1,3,640,640] float32 RGB, letterboxed and normalized to [0,1]",
        "output": f"[1,{expected_rows},8400] float32: xywh plus {len(GENERAL_CLASSES)} class scores, "
                   f"order={GENERAL_CLASSES}",
    }
    print(json.dumps(manifest_entry, indent=2))

    print("\n=== Candidate android detector.json labels/classes fields (NOT written automatically) ===")
    print(json.dumps({"labels": GENERAL_CLASSES, "classes": GENERAL_CLASSES}, indent=2))

    print(f"\nOK: checkpoint exists, exports to valid ONNX, shape matches GENERAL_CLASSES, "
          f"real inference ran on a held-out test image.")
    print(f"Candidate ONNX file: {export_path}")
    print("This file has NOT been copied into android/app/src/main/assets/, and no Android-facing "
          "config has been modified. That is a separate, explicit step.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
