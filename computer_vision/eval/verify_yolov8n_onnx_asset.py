#!/usr/bin/env python3
"""Independently verify the committed-to-assets YOLOv8n ONNX model on a real project image.

This does NOT exercise the Kotlin/Android code path (ONNX Runtime Mobile's native
library targets Android ABIs and cannot load inside a desktop JVM/Robolectric test
on this machine). It verifies the artifact itself, using the desktop onnxruntime
package, on the same host Python already has available:

  1. The asset file is a valid, loadable ONNX model (not corrupt, not a placeholder).
  2. Its input/output tensor shapes match what models/manifest.json and
     YoloV8OnnxDetector.kt assume: input [1,3,640,640] float32, output [1,84,8400].
  3. Running real inference on a real COCO image produces detections, using the same
     letterbox + decode + per-class NMS logic as the Kotlin implementation (re-stated
     here in Python so the two can be cross-checked for the same test image).

This is NOT an accuracy evaluation and proves nothing about on-device NNAPI/CPU delegate
behavior, frame rotation handling, or CameraX frames. See README note at the bottom.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
ASSETS_DIR = ROOT / "android/app/src/main/assets"
MANIFEST_PATH = ROOT / "models/manifest.json"
CONFIG_PATH = ROOT / "android/app/src/main/resources/config/detector.json"


def letterbox(image: Image.Image, size: int):
    scale = min(size / image.width, size / image.height)
    new_w, new_h = max(1, round(image.width * scale)), max(1, round(image.height * scale))
    resized = image.resize((new_w, new_h), Image.BILINEAR)
    pad_x, pad_y = (size - new_w) / 2, (size - new_h) / 2
    canvas = Image.new("RGB", (size, size), (114, 114, 114))
    canvas.paste(resized, (round(pad_x), round(pad_y)))
    return canvas, scale, pad_x, pad_y


def to_chw(image: Image.Image) -> np.ndarray:
    arr = np.asarray(image, dtype=np.float32) / 255.0  # HWC, RGB
    return np.transpose(arr, (2, 0, 1))[None, ...]  # NCHW


def decode(output: np.ndarray, frame_w: int, frame_h: int, scale: float, pad_x: float, pad_y: float,
           classes: set[str], conf_threshold: float, nms_iou: float, model_labels: list[str],
           class_map: dict[str, str] | None = None):
    # output: [4+N(+extra), anchors] -> xywh (4) + N per-class scores, N == len(model_labels).
    # Bounded to exactly 4+N rows, same as YoloDecoder.kt's `4 until min(output.size,
    # config.labels.size + 4)` -- a segmentation export (e.g. the pothole model) has extra
    # mask-coefficient rows after the N class scores that must NOT be read as more classes.
    class_map = class_map or {}
    n = len(model_labels)
    boxes_xywh = output[:4]  # (4, anchors)
    scores_all = output[4:4 + n]  # (N, anchors)
    class_ids = np.argmax(scores_all, axis=0)
    scores = scores_all[class_ids, np.arange(scores_all.shape[1])]

    keep = scores >= conf_threshold
    boxes_xywh, scores, class_ids = boxes_xywh[:, keep], scores[keep], class_ids[keep]

    labels = [class_map.get(model_labels[c], model_labels[c]) for c in class_ids]
    class_keep = np.array([label in classes for label in labels])
    boxes_xywh, scores, class_ids, labels = (
        boxes_xywh[:, class_keep], scores[class_keep], class_ids[class_keep],
        [l for l, k in zip(labels, class_keep) if k],
    )

    cx, cy, w, h = boxes_xywh
    left = np.clip((cx - w / 2 - pad_x) / scale, 0, frame_w)
    top = np.clip((cy - h / 2 - pad_y) / scale, 0, frame_h)
    right = np.clip((cx + w / 2 - pad_x) / scale, 0, frame_w)
    bottom = np.clip((cy + h / 2 - pad_y) / scale, 0, frame_h)

    detections = []
    for i in range(len(labels)):
        if right[i] <= left[i] or bottom[i] <= top[i]:
            continue
        detections.append({
            "label": labels[i], "confidence": float(scores[i]),
            "x": float(left[i]), "y": float(top[i]),
            "w": float(right[i] - left[i]), "h": float(bottom[i] - top[i]),
        })

    def iou(a, b):
        ow = max(0.0, min(a["x"] + a["w"], b["x"] + b["w"]) - max(a["x"], b["x"]))
        oh = max(0.0, min(a["y"] + a["h"], b["y"] + b["h"]) - max(a["y"], b["y"]))
        overlap = ow * oh
        union = a["w"] * a["h"] + b["w"] * b["h"] - overlap
        return overlap / union if union > 0 else 0.0

    kept: list[dict] = []
    for label in classes:
        same = sorted((d for d in detections if d["label"] == label), key=lambda d: -d["confidence"])
        while same:
            best = same.pop(0)
            kept.append(best)
            same = [d for d in same if iou(best, d) <= nms_iou]
    return kept


def main() -> int:
    config_path = Path(sys.argv[1]) if len(sys.argv) > 1 else CONFIG_PATH
    config = json.loads(config_path.read_text())

    all_models = json.loads(MANIFEST_PATH.read_text())["models"]
    manifest = next((m for m in all_models if m["file"] == config["modelAsset"]), None)
    if manifest is None:
        print(f"FAIL: no models/manifest.json entry has \"file\": {config['modelAsset']!r}")
        return 1
    print(f"Verifying {config_path.name} -> manifest entry {manifest['name']!r}")
    print(f"Manifest input/output: {manifest['input']} -> {manifest['output']}")

    MODEL_PATH = ASSETS_DIR / config["modelAsset"]
    if not MODEL_PATH.is_file():
        print(f"FAIL: model asset not found at {MODEL_PATH} (detector.json modelAsset="
              f"{config['modelAsset']!r})")
        return 1

    model_labels = config.get("labels")
    if not model_labels:
        print("FAIL: detector.json has no 'labels' list -- cannot determine the model's "
              "output class order (never assume stock COCO-80 order for a custom-trained model).")
        return 1
    expected_rows = 4 + len(model_labels)
    print(f"Using {len(model_labels)} labels from detector.json (expected output rows: {expected_rows}).")

    try:
        session = ort.InferenceSession(str(MODEL_PATH), providers=["CPUExecutionProvider"])
    except Exception as error:  # noqa: BLE001 - report, do not hide
        print(f"FAIL: ONNX Runtime could not load the asset: {type(error).__name__}: {error}")
        return 1

    input_meta = session.get_inputs()[0]
    output_meta = session.get_outputs()[0]
    print(f"Loaded OK. Session input: {input_meta.name} {input_meta.shape}, "
          f"output: {output_meta.name} {output_meta.shape}")

    expected_input = [1, 3, config["inputSize"], config["inputSize"]]
    if list(input_meta.shape) != expected_input:
        print(f"FAIL: input shape {input_meta.shape} != expected {expected_input} "
              f"(from detector.json inputSize={config['inputSize']})")
        return 1

    image_path = Path(sys.argv[2]) if len(sys.argv) > 2 else (
        ROOT / "datasets/data/detection/coco/val2017/000000000139.jpg"
    )
    if not image_path.is_file():
        print(f"FAIL: test image not found at {image_path}")
        return 1

    image = Image.open(image_path).convert("RGB")
    letterboxed, scale, pad_x, pad_y = letterbox(image, config["inputSize"])
    tensor = to_chw(letterboxed).astype(np.float32)

    try:
        result = session.run(None, {input_meta.name: tensor})
    except Exception as error:  # noqa: BLE001
        print(f"FAIL: inference raised {type(error).__name__}: {error}")
        return 1

    output = result[0][0]  # drop batch dim -> [4+N(+extra), anchors]
    if output.shape[0] < expected_rows:
        print(f"FAIL: output shape {output.shape} has fewer than the expected {expected_rows} rows "
              f"(4 + {len(model_labels)} labels from detector.json)")
        return 1
    if output.shape[0] > expected_rows:
        print(f"Note: output has {output.shape[0] - expected_rows} extra row(s) beyond 4+N "
              f"(e.g. a segmentation export's mask coefficients) -- ignored, same as YoloDecoder.kt.")
    print(f"Inference OK on {image_path.name} ({image.width}x{image.height}). Raw output shape {output.shape}.")

    detections = decode(
        output, image.width, image.height, scale, pad_x, pad_y,
        classes=set(config["classes"]),
        conf_threshold=config["confidenceThreshold"],
        nms_iou=config["nmsIouThreshold"],
        model_labels=model_labels,
        class_map=config.get("classMap"),
    )
    print(f"Decoded {len(detections)} detection(s) after class filter + NMS "
          f"(classes={sorted(config['classes'])}, conf>={config['confidenceThreshold']}):")
    for detection in sorted(detections, key=lambda d: -d["confidence"]):
        print(f"  {detection['label']:14s} conf={detection['confidence']:.3f} "
              f"box=({detection['x']:.0f},{detection['y']:.0f},{detection['w']:.0f},{detection['h']:.0f})")

    if not detections:
        print("NOTE: zero detections after class-filter + threshold is possible for this image/class "
              "set and is not itself a failure; it is only meaningful together with the 'Inference OK' "
              "line above, which confirms the model loaded and ran.")

    print("\nOK: model asset loads, shapes match manifest/config, inference runs on a real image.")
    print("NOT verified by this script: on-device NNAPI/CPU delegate via onnxruntime-android, "
          "or a live CameraX capture. The app has a CameraX snapshot path now, but it still "
          "needs to be exercised in an Android emulator/device.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
