# TD-001: Use the reference YOLOv8n detector for the prototype

2026-11-13 · Owner: Mostafa O. · Status: Resolved (compatible release terms defined, 2026-11-15)
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

## Update, 2026-11-15: how the AGPL-3.0 term is actually satisfied

The model choice itself was already decided above; what was still open was *how* this project
meets AGPL-3.0 for the weights it ships (now two files: `yolo11m_int8_coco_stock.onnx` and
`pothole_keremberke_n.onnx`, both Ultralytics-derived and both AGPL-3.0 per their model cards —
see `models/manifest.json`). That is now resolved as follows, given the project's actual
distribution shape:

- This is a non-commercial, academic capstone project. The only planned distribution is a signed
  APK sideloaded to field testers at Gate 4 (`README.md`), not a Play Store listing or a paid
  product, and no server hosts the model as a network service.
- The app's own code stays MIT (unchanged) and is already fully public on GitHub
  (`github.com/b0o0da/Ai-Assistive-Vision`). Model weights are never committed to Git; they are
  fetched and SHA-256-verified via `scripts/fetch_models.py` against `models/manifest.json`, so
  nothing about the model is hidden or closed.
- AGPL-3.0's actual obligation on a distributed copy is to provide the complete corresponding
  source (or, for a network service, a source offer) to whoever receives it. Because the full
  source — app code, model-fetch script, exact SHA-256-pinned weight identity, and training/export
  notes (`models/manifest.json`, `computer_vision/`) — is already public, that obligation is met
  for anyone who receives the APK, provided the APK itself discloses where to find it.
- Concrete, now-required step for whoever builds the distributed APK: bundle a notice pointing
  receivers at the public source, rather than relying on them already knowing it exists. See
  `NOTICE.md` at the repo root for the exact text; it should be surfaced on-device (e.g. an
  About/Licenses screen, or at minimum packaged into `android/app/src/main/assets/`) before any
  APK is handed to a field tester. This UI wiring is tracked as a small follow-up, not yet done —
  the licensing *decision and compliance text* are resolved; the in-app surfacing of that text is
  an implementation task against Gate 4, not a licensing question.
- This reasoning applies only to the current non-commercial, source-public, sideloaded-APK
  distribution. If the project is ever sold, closed-sourced, or offered as a hosted service to
  others, re-open this decision — those distribution shapes change the analysis.
