#!/usr/bin/env python3
"""Export a PyTorch detector checkpoint to ONNX and TFLite (fp16 + int8).

AVN-5 / WS-05. Detector-agnostic on purpose: TD-001 (YOLO nano AGPL vs an
Apache-licensed detector, see docs/decisions/README.md) is still Open, so
this file never imports a concrete detector library (no `ultralytics`, no
hardcoded YOLO head). Instead the caller supplies a *model loader*: a
"module:function" reference to a zero- or one-argument callable that
returns an already-built, eval()-mode `torch.nn.Module`.

Model loader contract
----------------------
    def load(checkpoint_path: str | None) -> torch.nn.Module: ...

- Must be importable (`python -c "import module"` works from the repo root
  or with PYTHONPATH set).
- Must return a module whose forward() takes a single `(1, 3, H, W)`
  float32 NCHW tensor (letterboxed, as per docs/architecture.md WS-05 and
  models/README.md's manifest "input" field) and returns a single tensor
  shaped `(1, N, 6)` -- boxes xyxy + score + class, per the same spec.
- The loader, not this script, decides what `checkpoint_path` means (a
  state_dict file, a TorchScript file, or ignored entirely for a
  self-contained fixture). `checkpoint_path` may be None.
- The real production detector's loader (once TD-001 is decided and a
  reviewed checkpoint exists) plugs in here without any change to this
  file -- see computer_vision/export/fixtures/toy_detector.py for the
  reference loader used by this package's own tests.

What this script does NOT do
-----------------------------
- It does not download, train or pick a checkpoint.
- It does not touch models/manifest.json -- adding a real entry there
  requires a reviewed, licensed checkpoint and a URL/sha256, none of which
  exist yet (see docs/decisions/README.md, TD-001: Open).
- It does not decide TD-001.

Usage
-----
    python export.py \\
        --model-loader computer_vision.export.fixtures.toy_detector:load_toy_detector \\
        --output-dir /tmp/export-out \\
        --input-shape 1,3,320,320 \\
        --calibration-images /tmp/calib/*.png
"""
from __future__ import annotations

import argparse
import contextlib
import importlib
import json
import os
import random
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

import numpy as np
import onnx2tf
import torch

INPUT_NAME = "images"
OUTPUT_NAME = "detections"
DEFAULT_OPSET = 17


def set_deterministic_seed(seed: int) -> None:
    """Pin every RNG this pipeline touches, so repeated runs are reproducible.

    (Matches the "Done when" criterion: "TFLite fp16+int8 and ONNX produced
    reproducibly." onnx2tf/TensorFlow's own internal numerics can still
    differ in the last bits across platforms; this covers the Python-level
    sources of nondeterminism we control: the loader's weight init and our
    own synthetic-data generation.)
    """
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    torch.use_deterministic_algorithms(True, warn_only=True)


def resolve_loader(spec: str) -> Callable[[str | None], torch.nn.Module]:
    """Resolve a "module.path:function_name" string to a callable.

    Kept deliberately dumb (importlib + getattr) -- this is the one seam
    that lets export.py stay detector-agnostic (CLAUDE.md: "Build behind
    interfaces; never commit to one").
    """
    if ":" not in spec:
        raise ValueError(f"--model-loader must be 'module:function', got {spec!r}")
    module_name, func_name = spec.split(":", 1)
    module = importlib.import_module(module_name)
    try:
        return getattr(module, func_name)
    except AttributeError as exc:
        raise ValueError(f"{module_name!r} has no attribute {func_name!r}") from exc


def load_model(loader_spec: str, checkpoint_path: str | None) -> torch.nn.Module:
    loader = resolve_loader(loader_spec)
    model = loader(checkpoint_path)
    model.eval()
    return model


@dataclass(frozen=True)
class ExportPaths:
    onnx_path: Path
    tflite_fp16_path: Path
    tflite_int8_path: Path
    manifest_path: Path


def export_onnx(
    model: torch.nn.Module,
    input_shape: tuple[int, int, int, int],
    onnx_path: Path,
    opset: int = DEFAULT_OPSET,
) -> None:
    onnx_path.parent.mkdir(parents=True, exist_ok=True)
    dummy = torch.zeros(*input_shape, dtype=torch.float32)
    with torch.no_grad():
        torch.onnx.export(
            model,
            dummy,
            str(onnx_path),
            input_names=[INPUT_NAME],
            output_names=[OUTPUT_NAME],
            opset_version=opset,
            dynamic_axes=None,  # fixed shape: the app's detector input is a fixed letterboxed size
            do_constant_folding=True,
        )


def build_calibration_npy(
    calibration_images: list[np.ndarray],
    input_shape: tuple[int, int, int, int],
    npy_path: Path,
) -> Path:
    """Stack calibration images into the NHWC float32 array onnx2tf expects.

    onnx2tf's `custom_input_op_name_np_data_path` loads a .npy file and
    documents its on-disk layout as the *TensorFlow* input shape (NHWC),
    transposing internally for the ONNX-side comparison -- see
    onnx2tf/utils/common_functions.py's "# NHWC -> NCHW" transpose. Calling
    code here hands us images as NCHW (to match the PyTorch model's
    forward()); this just moves the channel axis.
    """
    _, c, h, w = input_shape
    batch = np.stack(calibration_images, axis=0).astype(np.float32)  # (n, c, h, w)
    assert batch.shape[1:] == (c, h, w), f"calibration image shape mismatch: {batch.shape}"
    nhwc = batch.transpose(0, 2, 3, 1)  # (n, h, w, c)
    npy_path.parent.mkdir(parents=True, exist_ok=True)
    np.save(npy_path, nhwc)
    return npy_path


@contextlib.contextmanager
def _chdir(path: Path):
    previous = os.getcwd()
    os.chdir(path)
    try:
        yield
    finally:
        os.chdir(previous)


def _stub_onnx2tf_sample_data(cache_dir: Path) -> None:
    """Pre-seed onnx2tf's own internal "test image" cache file.

    Independent of our `custom_input_op_name_np_data_path` calibration
    data, onnx2tf's `convert()` unconditionally tries to fetch a small
    sample-image .npy from GitHub/Wasabi the first time it sees a 4D,
    3-channel, batch<=20 input, for an internal sanity check -- it looks
    for `calibration_image_sample_data_20x128x128x3_float32.npy` in the
    current working directory before trying the network. We don't use
    that internal check and don't want this export to require network
    access or leave a stray file in the repo, so we write a harmless
    synthetic placeholder of the expected shape into the (disposable)
    output directory and run the conversion with that as cwd.
    """
    name = "calibration_image_sample_data_20x128x128x3_float32.npy"
    path = cache_dir / name
    if not path.exists():
        rng = np.random.default_rng(0)
        placeholder = rng.random((20, 128, 128, 3), dtype=np.float64).astype(np.float32)
        np.save(path, placeholder)


def export_tflite(
    onnx_path: Path,
    output_dir: Path,
    calibration_npy: Path | None,
) -> dict[str, Path]:
    """Run onnx2tf once to produce float32/float16/int8 TFLite siblings.

    onnx2tf names outputs after the ONNX file's stem, e.g. `detector.onnx`
    -> `detector_float16.tflite`, `detector_full_integer_quant.tflite`.
    Full int8 quantization needs representative calibration data; without
    it onnx2tf still runs but only emits float32/float16.
    """
    output_dir.mkdir(parents=True, exist_ok=True)
    stem = onnx_path.stem

    custom_input = None
    if calibration_npy is not None:
        # mean=0, std=1 (TensorFlow/NHWC-shaped, per onnx2tf's convention): our calibration
        # images are already normalized to [0, 1] float32, so onnx2tf's internal
        # `(data - mean) / std` is a no-op and just passes them through.
        zero_mean = np.zeros((1, 1, 1, 3), dtype=np.float32)
        unit_std = np.ones((1, 1, 1, 3), dtype=np.float32)
        custom_input = [[INPUT_NAME, str(calibration_npy.resolve()), zero_mean, unit_std]]

    _stub_onnx2tf_sample_data(output_dir)
    with _chdir(output_dir):
        onnx2tf.convert(
            input_onnx_file_path=str(onnx_path.resolve()),
            output_folder_path=str(output_dir.resolve()),
            output_integer_quantized_tflite=calibration_npy is not None,
            custom_input_op_name_np_data_path=custom_input,
            non_verbose=True,
        )

    fp16 = output_dir / f"{stem}_float16.tflite"
    int8 = output_dir / f"{stem}_full_integer_quant.tflite"
    produced = {"float16": fp16}
    if int8.exists():
        produced["int8"] = int8
    return produced


def write_run_manifest(
    output_dir: Path,
    onnx_path: Path,
    tflite_paths: dict[str, Path],
    input_shape: tuple[int, int, int, int],
    seed: int,
) -> Path:
    """Write a small JSON record of what this run produced (not models/manifest.json --

    that file is for reviewed, licensed, shippable models; see this
    script's module docstring). This is a local export-run receipt so a
    human can see exactly what was produced and with which input shape.
    """
    manifest = {
        "input_shape_nchw": list(input_shape),
        "input_name": INPUT_NAME,
        "output_name": OUTPUT_NAME,
        "onnx": str(onnx_path),
        "tflite": {k: str(v) for k, v in tflite_paths.items()},
        "seed": seed,
    }
    manifest_path = output_dir / "export_run.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest_path


def run_export(
    loader_spec: str,
    checkpoint_path: str | None,
    output_dir: Path,
    input_shape: tuple[int, int, int, int],
    calibration_images: list[np.ndarray] | None,
    opset: int = DEFAULT_OPSET,
    seed: int = 0,
) -> ExportPaths:
    set_deterministic_seed(seed)
    model = load_model(loader_spec, checkpoint_path)

    onnx_path = output_dir / "detector.onnx"
    export_onnx(model, input_shape, onnx_path, opset=opset)

    calibration_npy = None
    if calibration_images:
        calibration_npy = build_calibration_npy(
            calibration_images, input_shape, output_dir / "calibration.npy"
        )

    tflite_paths = export_tflite(onnx_path, output_dir, calibration_npy)
    manifest_path = write_run_manifest(output_dir, onnx_path, tflite_paths, input_shape, seed)

    return ExportPaths(
        onnx_path=onnx_path,
        tflite_fp16_path=tflite_paths["float16"],
        tflite_int8_path=tflite_paths.get("int8", Path()),
        manifest_path=manifest_path,
    )


def _parse_input_shape(text: str) -> tuple[int, int, int, int]:
    parts = tuple(int(p) for p in text.split(","))
    if len(parts) != 4:
        raise argparse.ArgumentTypeError("--input-shape must be 'N,C,H,W', e.g. 1,3,320,320")
    return parts  # type: ignore[return-value]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--model-loader",
        required=True,
        help="module:function returning an eval()-mode torch.nn.Module; see module docstring",
    )
    parser.add_argument("--checkpoint", default=None, help="passed through to the loader as-is")
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument(
        "--input-shape", default="1,3,320,320", type=_parse_input_shape, help="N,C,H,W"
    )
    parser.add_argument(
        "--calibration-images",
        nargs="*",
        default=None,
        help="PNG/JPG paths for int8 calibration; omitted means no int8 output",
    )
    parser.add_argument("--opset", type=int, default=DEFAULT_OPSET)
    parser.add_argument("--seed", type=int, default=0)
    args = parser.parse_args(argv)

    calibration_images = None
    if args.calibration_images:
        from PIL import Image

        _, c, h, w = args.input_shape
        calibration_images = []
        for p in args.calibration_images:
            img = Image.open(p).convert("RGB").resize((w, h))
            arr = np.asarray(img, dtype=np.float32) / 255.0  # HWC, [0, 1]
            calibration_images.append(arr.transpose(2, 0, 1))  # CHW
        if c != 3:
            raise ValueError("calibration image loading only supports 3-channel input_shape")

    paths = run_export(
        loader_spec=args.model_loader,
        checkpoint_path=args.checkpoint,
        output_dir=args.output_dir,
        input_shape=args.input_shape,
        calibration_images=calibration_images,
        opset=args.opset,
        seed=args.seed,
    )

    print(f"onnx:        {paths.onnx_path}")
    print(f"tflite fp16: {paths.tflite_fp16_path}")
    print(f"tflite int8: {paths.tflite_int8_path or '(not produced -- no calibration images given)'}")
    print(f"run record:  {paths.manifest_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
