"""Self-test for the export pipeline (AVN-5 / WS-05), run against the
synthetic ToyDetector fixture rather than a real checkpoint -- see
fixtures/toy_detector.py's docstring for why.

Covers the task's "Done when" criteria as far as they can be covered
without a real checkpoint or a TD-001 decision:
  - TFLite fp16 + int8 and ONNX are all produced.
  - The export is reproducible (re-running it from the same seed produces
    byte-identical ONNX and matching TFLite outputs).
  - Exported-model outputs match the original PyTorch model's outputs
    within tolerance, on 20 images.
  - No checkpoint is written to the repo (the fixture model's weights
    live only in memory for the duration of the test).
"""
from __future__ import annotations

import hashlib
from pathlib import Path

import numpy as np
import pytest

from computer_vision.export import export, verify
from computer_vision.export.fixtures.synthetic_images import generate_synthetic_batch
from computer_vision.export.fixtures.toy_detector import load_toy_detector

INPUT_SHAPE = (1, 3, 320, 320)
NUM_TEST_IMAGES = 20
LOADER_SPEC = "computer_vision.export.fixtures.toy_detector:load_toy_detector"


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


@pytest.fixture(scope="module")
def test_images() -> list[np.ndarray]:
    return generate_synthetic_batch(NUM_TEST_IMAGES, height=320, width=320, seed=42)


@pytest.fixture(scope="module")
def exported(tmp_path_factory: pytest.TempPathFactory, test_images: list[np.ndarray]):
    output_dir = tmp_path_factory.mktemp("export_out")
    paths = export.run_export(
        loader_spec=LOADER_SPEC,
        checkpoint_path=None,
        output_dir=output_dir,
        input_shape=INPUT_SHAPE,
        calibration_images=test_images,
        seed=0,
    )
    return paths


def test_onnx_and_tflite_fp16_and_int8_are_all_produced(exported: export.ExportPaths):
    assert exported.onnx_path.exists(), "ONNX export was not produced"
    assert exported.tflite_fp16_path.exists(), "TFLite fp16 export was not produced"
    assert exported.tflite_int8_path != Path(), "TFLite int8 was not produced (no calibration data?)"
    assert exported.tflite_int8_path.exists(), "TFLite int8 export was not produced"


def test_no_checkpoint_file_is_written_anywhere_in_the_export_output(exported: export.ExportPaths):
    # The fixture loader takes no checkpoint and writes none; guard against a future
    # loader accidentally dropping a .pt/.pth/.ckpt file into the export output dir.
    output_dir = exported.onnx_path.parent
    stray_checkpoints = (
        list(output_dir.glob("*.pt"))
        + list(output_dir.glob("*.pth"))
        + list(output_dir.glob("*.ckpt"))
    )
    assert stray_checkpoints == []


def test_export_is_reproducible_given_the_same_seed(tmp_path_factory, test_images):
    out_a = tmp_path_factory.mktemp("repro_a")
    out_b = tmp_path_factory.mktemp("repro_b")

    paths_a = export.run_export(
        loader_spec=LOADER_SPEC,
        checkpoint_path=None,
        output_dir=out_a,
        input_shape=INPUT_SHAPE,
        calibration_images=test_images,
        seed=7,
    )
    paths_b = export.run_export(
        loader_spec=LOADER_SPEC,
        checkpoint_path=None,
        output_dir=out_b,
        input_shape=INPUT_SHAPE,
        calibration_images=test_images,
        seed=7,
    )

    assert _sha256(paths_a.onnx_path) == _sha256(paths_b.onnx_path), (
        "ONNX export is not byte-reproducible across two runs with the same seed"
    )

    # TFLite conversion goes through TensorFlow's own graph-building machinery, which does
    # not guarantee byte-identical output files even when the input ONNX is identical -- so
    # compare *inference outputs* on the test images instead of file hashes for the TFLite
    # siblings.
    out_fp16_a = verify.run_tflite(str(paths_a.tflite_fp16_path), test_images)
    out_fp16_b = verify.run_tflite(str(paths_b.tflite_fp16_path), test_images)
    np.testing.assert_allclose(out_fp16_a, out_fp16_b, atol=1e-6)


def test_onnx_output_matches_pytorch_within_tolerance_on_20_images(
    exported: export.ExportPaths, test_images: list[np.ndarray]
):
    model = load_toy_detector()
    reference = verify.run_pytorch(model, test_images)
    candidate = verify.run_onnx(str(exported.onnx_path), test_images)

    result = verify.compare("onnx_vs_pytorch", reference, candidate, verify.ONNX_ATOL)
    assert result.passed, (
        f"ONNX output diverged from PyTorch beyond tolerance: "
        f"max_abs_diff={result.max_abs_diff} tolerance={result.tolerance} "
        f"per_image_max_diff={result.per_image_max_diff}"
    )


def test_tflite_fp16_output_matches_pytorch_within_tolerance_on_20_images(
    exported: export.ExportPaths, test_images: list[np.ndarray]
):
    model = load_toy_detector()
    reference = verify.run_pytorch(model, test_images)
    candidate = verify.run_tflite(str(exported.tflite_fp16_path), test_images)

    result = verify.compare("tflite_fp16_vs_pytorch", reference, candidate, verify.TFLITE_FP16_ATOL)
    assert result.passed, (
        f"TFLite fp16 output diverged from PyTorch beyond tolerance: "
        f"max_abs_diff={result.max_abs_diff} tolerance={result.tolerance} "
        f"per_image_max_diff={result.per_image_max_diff}"
    )


def test_tflite_int8_output_matches_pytorch_within_tolerance_on_20_images(
    exported: export.ExportPaths, test_images: list[np.ndarray]
):
    model = load_toy_detector()
    reference = verify.run_pytorch(model, test_images)
    candidate = verify.run_tflite(str(exported.tflite_int8_path), test_images)

    result = verify.compare("tflite_int8_vs_pytorch", reference, candidate, verify.TFLITE_INT8_ATOL)
    assert result.passed, (
        f"TFLite int8 output diverged from PyTorch beyond tolerance: "
        f"max_abs_diff={result.max_abs_diff} tolerance={result.tolerance} "
        f"per_image_max_diff={result.per_image_max_diff}"
    )


def test_loader_rejects_a_malformed_spec():
    with pytest.raises(ValueError):
        export.resolve_loader("no_colon_here")


def test_calibration_npy_is_nhwc_for_onnx2tf(tmp_path):
    images = generate_synthetic_batch(2, height=4, width=6, seed=0)  # CHW, (3, 4, 6)
    npy_path = export.build_calibration_npy(images, (2, 3, 4, 6), tmp_path / "calib.npy")
    loaded = np.load(npy_path)
    assert loaded.shape == (2, 4, 6, 3)
