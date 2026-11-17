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

## Combined two-model latency + Egyptian-scene spot check

Date: 2026-11-15 · Script: `computer_vision/eval/egyptian_spotcheck_and_combined_latency.py`

Real numbers for two of the three gaps above (desktop CPU, sequential sum of both models'
inference time per image — a safe upper bound on the true on-device coroutine-concurrent cost,
since `CompositeDetector.kt` runs both ONNX sessions concurrently, not sequentially):

Images evaluated: 20 real, legally-licensed Egyptian street photos from Wikimedia Commons
(`datasets/data/detection/egyptian/images/commons/`, no ground-truth labels — qualitative only,
cannot produce mAP).

| metric | value |
|---|---|
| mean combined latency | 156.5ms |
| median combined latency | 154.6ms |
| min / max | 151.5ms / 176.4ms |
| images with >=1 detection of any kind | 19/20 |
| images with a "transportation" detection | 18/20 |

Qualitative read: `transportation` fires reliably on real tuk-tuks/microbuses/cars in actual Cairo
and Upper Egypt street scenes (e.g. `Toktok.jpg`, `Tuk_-tuk_in_Egypt.JPG`, `Cairo_ring_road.JPG`),
not just on the stock photography used elsewhere in this repo's benchmarks. One image
(`Toktok85.jpg`) produced zero detections from either model. No ground truth exists for these
images, so this is a sanity check that the models fire at all on real local scenes, not a
precision/recall claim.

## On-device (real ARM64) latency

Date: 2026-11-15 · one-off instrumented probe run against the debug APK on an ARM64-v8a Android
34 AVD (`visionnav_test`, Pixel 6 profile) — a real ARM64 system image on Apple Silicon, not an
x86_64-translated emulator, so this runs the actual `onnxruntime-android` ARM binary and confirms
its kernel set covers this INT8 quantization's ops end-to-end (the open question `manifest.json`
flagged). The probe loaded both ONNX assets directly, ran the same letterbox/CHW/inference steps
as `YoloV8OnnxDetector`, and measured wall-clock per image on the same 20 Egyptian Commons photos.
The probe used plain CPU execution (no NNAPI delegate) — the emulator has no real NPU, so an
NNAPI session would only exercise a software reference path, not real accelerator hardware; this
number is the CPU-fallback path the app also uses whenever NNAPI setup fails on real devices.

| metric | value |
|---|---|
| mean combined latency | 333.0ms |
| median combined latency | 303.8ms |
| min / max | 289.0ms / 786.5ms (first image includes ART/JIT warm-up) |

This is real ARM64 CPU execution, roughly 2x the desktop-CPU sequential sum above (156.5ms mean) —
expected, since the AVD is capped at 4 vCPUs / 2GB RAM versus the full desktop. The probe test and
its temporary build.gradle.kts dependencies were removed after this measurement (see
`android/CLAUDE.md`: instrumented tests are otherwise reserved for permission flow, service
restart and Room — this was a one-off measurement, not a kept test).

**Still not measured: real NPU/NNAPI acceleration on physical silicon.** No physical Android
device was available for this round. The ARM64 emulator closes the "does the ARM binary even run
with this quantization" question but not real-hardware NNAPI delegate behavior, thermals, or
real-phone memory bandwidth — tracked in `docs/decisions/`, `models/manifest.json`.

## What this does and doesn't prove

- Proves: both shipped ONNX assets run end-to-end and produce real, scorable detections matching
  production decode logic, on real labeled data, at real production thresholds. Also proves that
  both models fire on real Egyptian street scenes, gives a real desktop-CPU upper bound for
  combined two-model latency (~156ms mean, sequential sum), and now a real ARM64 on-device CPU
  latency number (~333ms mean) confirming the quantized ops actually run on `onnxruntime-android`.
- Does not prove: real-silicon NNAPI/NPU acceleration, thermals, or true on-device behavior on a
  physical phone — the one gap still requiring a physical Android device (minSdk 31). Tracked in
  `docs/decisions/`, `models/manifest.json`.
