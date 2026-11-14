# TD-001: Use the reference YOLOv8n detector for the prototype

2026-11-13 · Owner: Mostafa O. · Status: Decided for prototype
Contract impact: [x] none  [ ] yes

## Context

The reference Smart Vision Assistant uses Ultralytics YOLOv8n on its ESP32-CAM path. The AVN
Android app needs a small pretrained detector that runs locally on phone frames. A matching
YOLOv8n COCO ONNX export already exists in the workspace and its metadata identifies Ultralytics
8.4.172, 640x640 input, 80 COCO classes, and AGPL-3.0.

## Options considered

- Use the reference project's YOLOv8n COCO model (AGPL-3.0 metadata).
- Keep searching for an Apache-licensed detector and weights (TD-001's original alternative).

## Decision

Use the reference YOLOv8n COCO model for the local Android prototype. Run it through ONNX Runtime
Mobile, filter detections using `config/detector.json`, and keep model weights out of Git.

## Consequences (code, contract, licence, schedule)

- Android adds ONNX Runtime and a real `Detector` implementation behind the existing DI seam.
- The checked ONNX artifact is ignored by Git and verified with the hash in `models/manifest.json`.
- The model metadata declares AGPL-3.0. Any distribution of the integrated detector must meet the
  applicable license terms; the repository's existing MIT license file has not been changed here.
- This selects a pretrained baseline only. It does not claim fine-tuning or Egyptian-scene accuracy.
