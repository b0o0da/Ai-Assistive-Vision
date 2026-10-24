# Detector export (AVN-5 / WS-05)

Exports a PyTorch detector checkpoint to ONNX and to TFLite (fp16 + int8),
and verifies the exported outputs against the original PyTorch model on a
batch of images. See `docs/architecture.md`'s WS-05 section and
`models/README.md` for the contract this plugs into (`Detector.detect(frame)`,
the `(1, N, 6)` boxes-xyxy+score+class output shape, the manifest schema).

## Still open -- read before using this for the real detector

This tooling is generic export plumbing. Two things it deliberately does
**not** do, because they are not this script's call to make:

- **TD-001 (detector licence) is still Open** (`docs/decisions/README.md`):
  YOLO nano is AGPL-3.0; the project must either accept that and release
  compatibly, or switch to an Apache-licensed detector, before week 3.
  This package never imports a concrete detector library for exactly that
  reason -- see the "Model loader contract" below.
- **No real, reviewed checkpoint exists yet.** `models/manifest.json` is
  still empty (`{"version": 1, "models": []}`). This script does not add
  entries to it -- that needs a reviewed, licensed checkpoint plus a real
  URL and sha256, which is a separate step once TD-001 and the checkpoint
  source are settled.

Everything below has only been exercised against a tiny synthetic fixture
model (`fixtures/toy_detector.py`), not a real detector. The numeric
tolerances in `verify.py` are provisional for that reason -- re-derive
them against the real accuracy budget (WS-06/`eval/`) once a real
checkpoint is in the loop.

## Model loader contract

`export.py` never imports a specific detector. Instead you pass
`--model-loader module.path:function_name`, where the function is:

```python
def load(checkpoint_path: str | None) -> torch.nn.Module: ...
```

- Returns an eval()-mode module whose `forward()` takes one `(1, 3, H, W)`
  float32 NCHW tensor (letterboxed) and returns one `(1, N, 6)` tensor
  (boxes xyxy + score + class), matching `models/README.md`'s manifest
  `input`/`output` fields.
- `checkpoint_path` is passed through as-is; the loader decides what it
  means (a state_dict file, a TorchScript file, or ignored for a
  self-contained fixture).

`fixtures/toy_detector.py` is the reference implementation: a tiny CNN
with weights generated from a fixed seed, used only so this package's own
tests and CLI smoke-runs work without a real checkpoint. It is not a
detector, not trained, not YOLO, and carries no licence question.

## Setup

Use a project-local virtualenv -- do not install these into the system
Python (this package's TensorFlow/numpy combination was resolved fresh
specifically to be mutually compatible; see `requirements.txt`'s header
comment for why older pins can break):

```bash
python3 -m venv .venv
. .venv/bin/activate
pip install -r computer_vision/export/requirements.txt
```

## Usage

```bash
python computer_vision/export/export.py \
  --model-loader computer_vision.export.fixtures.toy_detector:load_toy_detector \
  --output-dir /tmp/export-out \
  --input-shape 1,3,320,320 \
  --calibration-images path/to/calib/*.png
```

Produces, inside `--output-dir`:

- `detector.onnx`
- `detector_float16.tflite`
- `detector_full_integer_quant.tflite` (int8; only if `--calibration-images`
  is given -- int8 needs representative data)
- `export_run.json`, a small receipt of the input shape/seed used (not
  `models/manifest.json` -- see "Still open" above)

Omit `--calibration-images` to skip int8 and only get ONNX + fp16.

To compare an export against the original PyTorch model, use
`verify.run_pytorch` / `verify.run_onnx` / `verify.run_tflite` plus
`verify.compare(...)` -- see `test_export.py` for a worked example on 20
images.

## Tests

```bash
pytest computer_vision/export/test_export.py -v
```

Run and passing as of this writing (8/8, ~13s, isolated venv per
"Setup" above):

```
test_onnx_and_tflite_fp16_and_int8_are_all_produced PASSED
test_no_checkpoint_file_is_written_anywhere_in_the_export_output PASSED
test_export_is_reproducible_given_the_same_seed PASSED
test_onnx_output_matches_pytorch_within_tolerance_on_20_images PASSED
test_tflite_fp16_output_matches_pytorch_within_tolerance_on_20_images PASSED
test_tflite_int8_output_matches_pytorch_within_tolerance_on_20_images PASSED
test_loader_rejects_a_malformed_spec PASSED
test_calibration_npy_is_nhwc_for_onnx2tf PASSED
```

No real images, checkpoints, or weights are written into the repo by
this test run -- the fixture model's weights live only in memory, and
calibration/test images are generated in a pytest temp dir.

## Known rough edges

- `onnx2tf.convert()` tries, on its own, to fetch a small internal
  sample-image `.npy` from GitHub/Wasabi the first time it sees a 4D,
  3-channel input, independent of the calibration data this script
  supplies. `export_tflite()` pre-seeds a harmless synthetic placeholder
  of the expected shape so this never needs network access or leaves a
  stray file in the repo -- see `_stub_onnx2tf_sample_data`'s docstring
  in `export.py` if that behaviour ever changes upstream.
- Reproducibility is checked at two levels: the ONNX export is asserted
  byte-identical across two runs with the same seed; the TFLite siblings
  are compared by *inference output* instead, since TensorFlow's graph
  building does not guarantee byte-identical `.tflite` files for an
  identical input graph.
