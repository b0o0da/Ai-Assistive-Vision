# Third-party notices

This application's own code is licensed under the MIT License (see `LICENSE`).

## Object detection models (AGPL-3.0)

This app's on-device object detectors are derived from Ultralytics YOLO models, which are
licensed under AGPL-3.0:

- General detector: Ultralytics YOLO11m, stock COCO weights, dynamically INT8-quantized
  (`yolo11m_int8_coco_stock.onnx`).
- Pothole detector: a YOLOv8n-based checkpoint from Hugging Face
  (`keremberke/yolov8n-pothole-segmentation`, itself an Ultralytics YOLOv8 derivative)
  (`pothole_keremberke_n.onnx`).

Exact versions and SHA-256 hashes are pinned in `models/manifest.json`.

Per AGPL-3.0, the complete corresponding source for this application — including the code that
loads and runs these models, and the script that fetches and verifies the model weights — is
publicly available at:

**https://github.com/b0o0da/Ai-Assistive-Vision**

No model weights are committed to that repository; `scripts/fetch_models.py` fetches and verifies
them against the SHA-256 hashes in `models/manifest.json` before they are packaged into the app.

See `docs/decisions/TD-001-yolov8n-reference-model.md` for the full licensing decision record.
