# Models

Weights are never committed. `manifest.json` lists every model the app or the evaluation uses; `scripts/fetch_models.py` checks their SHA-256 and copies verified files into Android assets.

## Architecture: two separate ONNX detectors, not one

The current Android app runs two independent ONNX models concurrently (`CompositeDetector`), not
one fine-tuned general model as earlier revisions of this doc described:

- **General detector** — stock, un-fine-tuned Ultralytics YOLO11m COCO weights, dynamically
  INT8-quantized (`yolo11m_int8_coco_stock.onnx`). Input `[1,3,640,640]` float32; output
  `[1,84,8400]` (4 box values + 80 COCO class scores). Vehicle body types (bicycle, car,
  motorcycle, bus, truck) are merged into one `transportation` display class entirely in software
  via `DetectorConfig.classMap` / `YoloDecoder.kt` — no retraining, no fine-tuning accuracy loss.
  Config: `android/app/src/main/resources/config/detector.json`.
- **Pothole detector** — a dedicated single-class pretrained model from Hugging Face
  (`keremberke/yolov8n-pothole-segmentation`, `pothole_keremberke_n.onnx`). Output
  `[1,37,8400]` (4 box + 1 class score + 32 segmentation mask-coefficient rows the decoder never
  reads). Config: `android/app/src/main/resources/config/detector_pothole.json`.

Both are loaded via `YoloV8OnnxDetector` with per-model `DetectorConfig` instances, constructed
manually in `android/app/src/main/kotlin/avn/app/core/di/ModulesModule.kt` (Hilt cannot
disambiguate two un-annotated instances of the same class without qualifiers, so these two are
built directly rather than through `@Inject`).

The full decision trail for this architecture (why stock + classMap beats fine-tuning on COCO-
native classes, the yolov8n -> ... -> YOLO11m INT8 latency/accuracy progression, and why the
pothole model is separate) lives in `manifest.json`'s `source` fields for each entry below, and
in `docs/decisions/`.

## Accuracy: published vs. self-measured

Every number historically cited here was a *published* benchmark (Ultralytics' own COCO numbers,
or a model card's reported metric), never independently reproduced against this app's real
decode path and real thresholds. That gap is now closed:
`computer_vision/eval/compute_map.py` runs real inference with the exact production
letterbox/decode/NMS/classMap logic against real local data, and
`computer_vision/eval/map_eval_report.md` has the full methodology and results — in short, the
general detector's own 7-class, production-threshold mAP50-95 is 0.6196 (5000 real COCO val2017
images), and the pothole detector's is 0.4175 (only 10 held-out images exist in total for this
dataset, so that number is directional, not statistically solid). Both reports flag `bench`/
`chair` as the weakest classes, consistent with their known status as structurally hard
categories for every public object detector, not a defect of this app's choices.

## Licensing

Both detectors are Ultralytics YOLO derivatives and declare AGPL-3.0. See
`docs/decisions/TD-001-yolov8n-reference-model.md` for the full resolution and `NOTICE.md` for
the required third-party notice text.

## Fetching and verifying

```sh
python scripts/fetch_models.py --android            # fetch, verify, copy app models into assets
python scripts/fetch_models.py --from ~/avn-models  # copy from a folder a teammate shared
python scripts/fetch_models.py --check              # verify only: no downloads, no copies
python scripts/fetch_models.py --history            # fail if any weight file is in Git history
```

For each entry the script keeps a file already in `models/` with the right SHA-256, else copies it
from `--from DIR`, else downloads `url` (https only). Anything whose SHA-256 does not match the
manifest is deleted, never installed. Exit code 1 means a **required** model is still missing.

- `"required": false` marks reference/rollback copies (the superseded fine-tune and the stock
  backup): a fresh clone does not need them and the script does not fail without them.
- `"android_asset": false` keeps a file out of `android/app/src/main/assets/`. Only `detector`
  and `detector-pothole` are copied, under the file names that `detector.json` / `detector_pothole.json`
  load (`modelAsset`); a test checks the two stay in sync.

**Making a fresh clone work without manual export:** upload the two verified ONNX files as assets
of a GitHub Release (for example tag `models-2026-11`) and put each asset's download URL in that
entry's `url`. The script then fetches and verifies them on any machine. Until that is done, share
the files and use `--from`.

The script checks the SHA-256 of each entry in `manifest.json` before copying it into
`android/app/src/main/assets/`. Keep `detector.json` / `detector_pothole.json` (the app's class
order, classMap, and thresholds) synchronized with whatever ONNX asset `manifest.json` actually
points at whenever replacing a model — a mismatch here is exactly the silent-misdecode failure
`DetectorConfig.DEFAULT`'s doc comment warns about.

Both `detector` and `detector-pothole` have `"url": null` — neither is a direct-downloadable
ONNX file, so `fetch_models.py` reports `MISS` for them if missing, by design, rather than
attempting a download that would silently fail. To (re)produce them manually:

- **`detector`** (`yolo11m_int8_coco_stock.onnx`): `pip install ultralytics onnxruntime`, then
  `yolo export model=yolo11m.pt format=onnx imgsz=640` (downloads the stock Ultralytics
  checkpoint automatically), then INT8-quantize with
  `onnxruntime.quantization.quantize_dynamic` (QUInt8 weights).
- **`detector-pothole`** (`pothole_keremberke_n.onnx`): the Hugging Face repo
  (`keremberke/yolov8n-pothole-segmentation`) hosts only a PyTorch checkpoint (`best.pt`), never
  an ONNX export — confirmed via its HF API `siblings` listing, 2026-11-15, which is why this
  entry's `url` is `null` rather than the model-card page (a web page is not a fetchable binary;
  pointing `url` at it would make `fetch_models.py` "succeed" at downloading HTML and fail SHA
  verification with a confusing mismatch). Download `best.pt` from that repo, then run
  `yolo export model=best.pt format=onnx imgsz=640 opset=12`.

Either way, verify the new file's SHA-256 and update `manifest.json`'s `sha256` field to match
before shipping it.

Manifest entry shape:

```json
{
  "name": "detector",
  "version": "yolo11m-int8-coco-onnx-8.4.172",
  "file": "yolo11m_int8_coco_stock.onnx",
  "url": null,
  "sha256": "<64 hex chars>",
  "license": "AGPL-3.0",
  "source": "where it came from, how it was trained/quantized, and now also self-measured mAP",
  "input": "[1,3,640,640] float32 RGB, letterboxed and normalized to [0,1]",
  "output": "[1,84,8400] float32: xywh plus 80 COCO class scores",
  "labels": "COCO 80 labels; app filters + remaps using detector.json"
}
```
