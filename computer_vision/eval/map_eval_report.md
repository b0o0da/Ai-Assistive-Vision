# Self-computed mAP for the shipped ONNX detectors

Date: 2026-11-15 · Script: `computer_vision/eval/compute_map.py`

Until now this project only ever cited Ultralytics' *published* benchmark numbers (e.g. YOLO11m
mAP50-95 51.5 on full 80-class COCO). This is a real, locally-run measurement instead, on the
exact ONNX assets shipped in `android/app/src/main/assets/`, using the exact production decode
path (letterbox, bounded class-score read, confidence filter, NMS, classMap remap — reused from
`computer_vision/eval/verify_yolov8n_onnx_asset.py`'s `decode()`, not reimplemented, to stay
consistent with `YoloDecoder.kt`).

## Methodology

- AP: standard COCO-style AP — greedy per-image/per-class IoU matching (descending confidence,
  each ground-truth box usable once), official 101-point recall interpolation. mAP50 = AP @ IoU
  0.50. mAP50-95 = mean AP over IoU 0.50:0.05:0.95 (10 thresholds). No `pycocotools`/`torchmetrics`
  dependency — implemented directly in NumPy in `compute_map.py`.
- Thresholds used are the app's real production thresholds (not a permissive sweep threshold):
  general detector confidence 0.35 / NMS IoU 0.50 (`detector.json`); pothole confidence 0.25 / NMS
  IoU 0.45 (`detector_pothole.json`). This measures what the app actually outputs to a user, not
  the model's full precision-recall curve.

## General detector — `yolo11m_int8_coco_stock.onnx`

Ground truth: all 5000 real COCO val2017 images at `datasets/prepared/avn_coco/` (YOLO-format
labels for an 11-raw-class subset). Both the model's 80-class predictions and the 11-class ground
truth are merged through the app's own `classMap` (bicycle/car/motorcycle/bus/truck ->
"transportation") before scoring, because that merged 7-class taxonomy is what the app actually
emits — not raw 80-class COCO.

Images evaluated: 5000. Wall-clock: 718.6s (0.144s/image, desktop CPU, single-threaded
onnxruntime `CPUExecutionProvider`).

| class          | GT boxes | preds | AP50  | AP50-95 |
|----------------|---------:|------:|------:|--------:|
| person         |   10,777 | 8,860 | 0.814 |   0.666 |
| transportation |    3,296 | 2,335 | 0.764 |   0.609 |
| bench          |      411 |   214 | 0.578 |   0.470 |
| chair          |    1,771 | 1,028 | 0.609 |   0.465 |
| fire hydrant   |      101 |    85 | 0.897 |   0.798 |
| stop sign      |       75 |    57 | 0.763 |   0.732 |
| parking meter  |       60 |    41 | 0.732 |   0.597 |

**mAP50 = 0.7366, mAP50-95 = 0.6196** (7-class average).

This confirms, with real numbers rather than a one-image spot check, what was already suspected:
`bench` and `chair` remain the weakest classes (AP50-95 0.470 / 0.465) — consistent with their
known status as structurally low-AP categories across essentially all public object detectors,
not a defect specific to this app's quantization or classMap approach. Every other class clears
0.59 AP50-95, well above the earlier from-scratch fine-tune's overall 0.387 mAP50-95
(`models/manifest.json`, `detector-general-finetune-superseded`), which is the real, direct
evidence that switching to stock pretrained weights + software-side class merging was the right
call for COCO-native classes.

This 0.6196 number is **not directly comparable** to Ultralytics' published 51.5 (0.515) — that
figure is full 80-class COCO mAP50-95, typically measured at a near-zero confidence sweep
threshold to trace the whole precision-recall curve. This measurement is a 7-class subset (biased
toward classes this app actually keeps, which happen to be easier/larger/more common than COCO's
hardest classes) at the app's real, higher production confidence threshold (0.35). Both numbers
are legitimate; they answer different questions — "how good is this architecture in general" vs.
"how good is what ships to this app's users."

## Pothole detector — `pothole_keremberke_n.onnx`

Ground truth: the real Hugging Face `keremberke/pothole-segmentation` dataset's `validation` and
`test` splits, fetched via `datasets/tools/import_hf_pothole.py`. **The entire dataset is only 90
images total** (train 80, validation 5, test 5) — both held-out splits were combined to get a
10-image eval set, since 5 alone is too small to report at all.

Images evaluated: 10 (5 validation + 5 test, both genuinely held out from the 80 training images).
Wall-clock: 0.5s.

| class    | GT boxes | preds | AP50  | AP50-95 |
|----------|---------:|------:|------:|--------:|
| pothole  |       10 |    13 | 0.884 |   0.418 |

**mAP50 = 0.8842, mAP50-95 = 0.4175.**

**Caveat, stated plainly: n=10 is not a statistically meaningful sample.** This number is
directional only, not a claim this model is independently verified at 88% AP50. It is, however,
reassuringly close to the 0.858 mAP50 the model card itself reports on its own validation split
(`models/manifest.json`), which is the best corroboration available given there is no larger
labeled pothole set in this repo. Egyptian-road accuracy remains completely unverified by either
number (noted already in `models/manifest.json` and `docs/decisions/`).

## What this does and doesn't prove

- Proves: both shipped ONNX assets run end-to-end and produce real, scorable detections matching
  production decode logic, on real labeled data, at real production thresholds.
- Does not prove: on-device (phone/NNAPI) behavior, combined two-model per-frame latency, or any
  Egyptian-scene accuracy. Those remain open gaps tracked elsewhere (`docs/decisions/`,
  `models/manifest.json`).
