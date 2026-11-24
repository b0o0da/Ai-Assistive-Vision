# Detector latency: ONNX Runtime vs LiteRT (WS05-04)

22 Nov 2026 · Owner: Mostafa O.

## What this measures

Whether a LiteRT (TensorFlow Lite) detector, with a GPU or NNAPI delegate, beats the
already-shipped ONNX Runtime/XNNPACK CPU detector (`YoloV8OnnxDetector`, `models/manifest.json`'s
"real NPU/NNAPI delegate acceleration ... still unverified" note). `TfliteDetector` implements the
same `Detector` interface against the same model architecture, so the two are a fair comparison of
runtime and delegate, not of model choice.

**No physical Android phone was available for this round** (same gap `PerfLogger`'s own doc
comment already tracks for the 30-minute thermal/battery report). Every number below is from the
Android Studio emulator (`visionnav_test`, arm64-v8a system image, API 34) with a synthetic camera
feed, not a physical device. Treat these as relative numbers between delegates on the *same*
emulator, not absolute phone performance -- an emulator's CPU and its GPU/NNAPI passthrough
are both known to differ from real silicon.

## Model and setup

- Weights: `models/stock_coco80_backup/yolov8n_coco_stock.onnx` (identical bytes to the shipped
  `yolo11m_int8_coco_stock.onnx`'s *predecessor* checkpoint -- the actual shipped YOLO11m weights
  are not present in this checkout right now; see `models/manifest.json`'s `url: null` entries).
  Converted to TFLite with `onnx2tf` (float32, no quantization): input transposed from the ONNX
  model's NCHW `[1,3,640,640]` to LiteRT's native NHWC `[1,640,640,3]`; output stays `[1,84,8400]`,
  unchanged, so the existing `YoloDecoder.decode` is reused as-is.
- Verified the conversion itself first: ran both the original ONNX model (ONNX Runtime) and the
  converted `.tflite` (LiteRT) on the same random input off-device; max absolute output difference
  was `0.0014` (float32 rounding noise, not a conversion bug).
- `TfliteDetector` mirrors `YoloV8OnnxDetector`'s structure: a lazily-created, warmed-up
  `Interpreter` singleton; one dummy inference at service start (`warmUp`); frames that arrive
  while an inference is already running are dropped, not queued (`busy` flag) -- the two gaps
  `YoloV8OnnxDetector` doesn't have, since this ticket asked for them explicitly.
- Each run: rebuild with `-Pavn.realSeams=detector,camera` and `TfliteDetector`'s `accelerator`
  hardcoded for that run (this wiring is a benchmark harness only, not committed -- see
  "What's not in this PR" below), install on the emulator, repeatedly trigger "Check scene",
  read `inference_ms` off `adb logcat`'s `AVN` JSON lines (the same `PerfLogger.measure` call
  `YoloV8OnnxDetector` already uses).

## Results

| Accelerator | Samples | Wall time | Mean | p50 | p95 | Min–max | FPS (1000/mean) | Crashes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **CPU (XNNPACK)** | 239 | 177 s (~3 min) | 194.2 ms | 193 ms | 210 ms | 179–313 ms | 5.15 | 0 |
| **NNAPI** | 40 | ~32 s | 369.4 ms | 366 ms | 399 ms | 345–454 ms | 2.71 | 0 |
| **GPU** | 0 (fell back to NNAPI) | — | — | — | — | — | — | — |

- **GPU delegate: unsupported on this emulator.** `CompatibilityList().isDelegateSupportedOnThisDevice`
  returned `false` at interpreter-creation time (logged once as `gpu_delegate_unsupported`);
  `TfliteDetector` fell back to NNAPI exactly as designed, no crash. This is an emulator-GPU-passthrough
  limitation, not evidence either way about a real phone's GPU delegate.
- **NNAPI is 1.9x *slower* than plain CPU/XNNPACK here**, not faster -- the emulator's NNAPI
  implementation has no real NPU behind it, only a software driver, so the extra delegate
  machinery is pure overhead on this hardware. This is the opposite of what NNAPI is for on a real
  phone with an actual NPU, which is exactly why this number cannot be trusted as a stand-in for
  real-device NNAPI and the open gap stays open.
- **CPU/XNNPACK stayed stable for the full ~3-minute run**: 239 consecutive inferences, 0 failures,
  the app process still alive and responsive at the end, model loaded exactly once (the
  warm-up/singleton works as intended).
- For reference, the shipped ONNX Runtime detector on the same emulator class of hardware was
  previously measured (`models/manifest.json`) at ~333 ms mean *combined* across both the general
  and pothole models together on the real YOLO11m INT8 checkpoint -- not the same model or the same
  single-vs-combined measurement as this table, so treat it as context, not a strict apples-to-apples
  comparison.

## Conclusion

On this emulator, LiteRT/XNNPACK (194 ms mean) is in the same ballpark as the existing ONNX
Runtime/XNNPACK path, and LiteRT's own GPU and NNAPI delegates do not beat plain CPU here -- GPU
isn't available at all, and NNAPI is slower than CPU. **This spike does not find a case to switch
the shipped detector off ONNX Runtime.** The real question this ticket set out to answer -- real
NPU/NNAPI acceleration on physical silicon -- is still open and needs a physical Android phone,
same gap `models/manifest.json` and `PerfLogger`'s doc comment already flag; this round only rules
out the emulator as a way to answer it.

## What's not in this PR

`TfliteDetector.kt` is not wired into `ModulesModule`'s `detector()` provider: this ticket is a
latency spike, not a decision to add a second detector runtime to the shipped app, and the
conclusion above doesn't support that switch anyway. The benchmark wiring used to collect the
numbers above was a one-line temporary edit to `detector()`, rebuilt three times (one per
accelerator) and reverted before this PR; reproduce it by constructing `TfliteDetector` directly
with the accelerator under test. The converted `.tflite` file itself is not committed (`*.tflite`
is already in `.gitignore`, same TD-001 "weights SHA-256-fetched, not committed" policy as the
`.onnx` models) -- regenerate it with `onnx2tf -i <model>.onnx -o <out dir>` from any of this
repo's existing ONNX checkpoints.
