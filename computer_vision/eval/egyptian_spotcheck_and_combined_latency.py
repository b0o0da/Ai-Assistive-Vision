#!/usr/bin/env python3
"""Qualitative Egyptian-scene spot check + real combined two-model latency.

Two things this repo never had real numbers for:

  1. Egyptian-scene behavior: every accuracy number so far (COCO val2017, the
     keremberke pothole split) is non-Egyptian stock photography. This runs
     both shipped detectors on real, legally-licensed Egyptian street photos
     (datasets/data/detection/egyptian/images/commons/, fetched by
     datasets/tools/import_commons_egypt_vehicles.py) and prints what each
     model actually detects. There is NO ground truth here (Commons import
     intentionally does not label), so this cannot produce mAP -- it is a
     qualitative sanity check only: does "transportation" fire at all on a
     tuk-tuk/microbus in a real Cairo street scene, not a formal benchmark.

  2. Combined two-model latency: CompositeDetector (CompositeDetector.kt) runs
     both ONNX sessions concurrently via coroutines on-device; nothing has
     measured their combined per-frame cost even on desktop. This runs both
     sessions back-to-back per image (sequential, a safe upper bound on the
     true concurrent cost) and reports real wall-clock numbers.

This is still desktop CPU, NOT the on-device number the project still needs --
see map_eval_report.md and docs/decisions/ for that remaining gap.
"""
from __future__ import annotations

import json
import sys
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify_yolov8n_onnx_asset import decode, letterbox, to_chw  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
ASSETS_DIR = ROOT / "android/app/src/main/assets"
CONFIG_GENERAL = ROOT / "android/app/src/main/resources/config/detector.json"
CONFIG_POTHOLE = ROOT / "android/app/src/main/resources/config/detector_pothole.json"
IMAGE_DIR = ROOT / "datasets/data/detection/egyptian/images/commons"


def load_session(model_path: Path) -> ort.InferenceSession:
    return ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])


def run_one(session: ort.InferenceSession, config: dict, image: Image.Image):
    letterboxed, scale, pad_x, pad_y = letterbox(image, config["inputSize"])
    tensor = to_chw(letterboxed).astype(np.float32)
    input_name = session.get_inputs()[0].name
    t0 = time.perf_counter()
    output = session.run(None, {input_name: tensor})[0][0]
    elapsed = time.perf_counter() - t0
    detections = decode(
        output, image.width, image.height, scale, pad_x, pad_y,
        classes=set(config["classes"]), conf_threshold=config["confidenceThreshold"],
        nms_iou=config["nmsIouThreshold"], model_labels=config["labels"],
        class_map=config.get("classMap"),
    )
    return detections, elapsed


def main() -> int:
    general_config = json.loads(CONFIG_GENERAL.read_text())
    pothole_config = json.loads(CONFIG_POTHOLE.read_text())
    general_session = load_session(ASSETS_DIR / general_config["modelAsset"])
    pothole_session = load_session(ASSETS_DIR / pothole_config["modelAsset"])

    image_paths = sorted(IMAGE_DIR.glob("*.jpg")) + sorted(IMAGE_DIR.glob("*.JPG"))
    if not image_paths:
        raise SystemExit(f"No images found under {IMAGE_DIR}")

    combined_times = []
    any_transportation = 0
    any_detection = 0

    print(f"Running both detectors on {len(image_paths)} real Egyptian street photos "
          f"(unlabeled -- qualitative check only, see docstring)\n")

    for image_path in image_paths:
        image = Image.open(image_path).convert("RGB")
        general_dets, general_ms = run_one(general_session, general_config, image)
        pothole_dets, pothole_ms = run_one(pothole_session, pothole_config, image)
        combined_ms = (general_ms + pothole_ms) * 1000
        combined_times.append(combined_ms)

        all_dets = sorted(general_dets + pothole_dets, key=lambda d: -d["confidence"])
        if any(d["label"] == "transportation" for d in all_dets):
            any_transportation += 1
        if all_dets:
            any_detection += 1

        summary = ", ".join(f"{d['label']}:{d['confidence']:.2f}" for d in all_dets[:5]) or "(none)"
        print(f"{image_path.name[:60]:60s} combined={combined_ms:6.1f}ms  -> {summary}")

    times = np.array(combined_times)
    print("\n=== Combined two-model latency (desktop CPU, sequential sum -- "
          "an upper bound on the real coroutine-concurrent cost) ===")
    print(f"images: {len(times)}  mean={times.mean():.1f}ms  median={np.median(times):.1f}ms  "
          f"min={times.min():.1f}ms  max={times.max():.1f}ms")
    print(f"\nImages with >=1 detection of any kind: {any_detection}/{len(image_paths)}")
    print(f"Images with a 'transportation' detection: {any_transportation}/{len(image_paths)}")
    print("\nNOT measured here: on-device (phone) latency, NNAPI/CPU delegate behavior, "
          "or true coroutine-concurrent (as opposed to sequential) cost.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
