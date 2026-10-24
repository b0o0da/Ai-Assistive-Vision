"""Compare exported-model outputs (ONNX, TFLite fp16, TFLite int8) against
the original PyTorch model's outputs, on a batch of images.

Covers the AVN-5 "Done when" criterion: "laptop output matches PyTorch
within agreed tolerance on 20 images." The tolerances below
(`ONNX_ATOL`, `TFLITE_FP16_ATOL`, `TFLITE_INT8_ATOL`) are provisional,
chosen for this self-test pipeline's synthetic fixture model -- they have
NOT been validated against the real production detector's accuracy
requirements, because no real checkpoint exists yet and TD-001 (detector
licence) is still Open. Whoever wires in the real checkpoint should
re-derive these from an actual accuracy budget (eval/ WS-06) rather than
assuming these numbers transfer.
"""
from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np
import onnxruntime as ort
import torch

from computer_vision.export.export import INPUT_NAME

# Provisional tolerances -- see module docstring.
ONNX_ATOL = 1e-4
TFLITE_FP16_ATOL = 0.05
TFLITE_INT8_ATOL = 0.15


@dataclass
class ComparisonResult:
    label: str
    max_abs_diff: float
    mean_abs_diff: float
    tolerance: float
    passed: bool
    per_image_max_diff: list[float] = field(default_factory=list)


def run_pytorch(model: torch.nn.Module, images: list[np.ndarray]) -> np.ndarray:
    outputs = []
    with torch.no_grad():
        for chw in images:
            tensor = torch.from_numpy(chw).unsqueeze(0).float()
            out = model(tensor)
            outputs.append(out.squeeze(0).numpy())
    return np.stack(outputs, axis=0)  # (n, N, 6)


def run_onnx(onnx_path: str, images: list[np.ndarray]) -> np.ndarray:
    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    output_name = session.get_outputs()[0].name
    outputs = []
    for chw in images:
        batched = chw[np.newaxis, ...].astype(np.float32)
        (out,) = session.run([output_name], {INPUT_NAME: batched})
        outputs.append(out.squeeze(0))
    return np.stack(outputs, axis=0)


def _dequantize(array: np.ndarray, quant_params: dict) -> np.ndarray:
    scale, zero_point = quant_params["quantization"]
    if scale == 0:
        return array.astype(np.float32)
    return (array.astype(np.float32) - zero_point) * scale


def run_tflite(tflite_path: str, images: list[np.ndarray]) -> np.ndarray:
    """Run a TFLite model (float32/float16/int8) over CHW images, returning float outputs.

    Handles quantized int8 input/output transparently via the model's own
    quantization parameters, so callers don't need to know whether a given
    .tflite file is float or fully integer quantized.
    """
    try:
        from ai_edge_litert.interpreter import Interpreter
    except ImportError:  # pragma: no cover - fallback for environments without ai-edge-litert
        from tensorflow.lite import Interpreter  # type: ignore[import-not-found]

    interpreter = Interpreter(model_path=str(tflite_path))
    interpreter.allocate_tensors()
    input_detail = interpreter.get_input_details()[0]
    output_detail = interpreter.get_output_details()[0]

    outputs = []
    for chw in images:
        hwc = chw.transpose(1, 2, 0)[np.newaxis, ...]  # (1, H, W, C), matches TF layout

        if input_detail["dtype"] in (np.int8, np.uint8):
            scale, zero_point = input_detail["quantization"]
            quantized = np.round(hwc / scale + zero_point)
            dtype_info = np.iinfo(input_detail["dtype"])
            quantized = np.clip(quantized, dtype_info.min, dtype_info.max)
            payload = quantized.astype(input_detail["dtype"])
        else:
            payload = hwc.astype(input_detail["dtype"])

        interpreter.set_tensor(input_detail["index"], payload)
        interpreter.invoke()
        raw_out = interpreter.get_tensor(output_detail["index"])

        if output_detail["dtype"] in (np.int8, np.uint8):
            out = _dequantize(raw_out, output_detail)
        else:
            out = raw_out.astype(np.float32)
        outputs.append(out.squeeze(0))
    return np.stack(outputs, axis=0)


def compare(label: str, reference: np.ndarray, candidate: np.ndarray, tolerance: float) -> ComparisonResult:
    assert reference.shape == candidate.shape, (
        f"{label}: shape mismatch, reference={reference.shape} candidate={candidate.shape}"
    )
    diff = np.abs(reference.astype(np.float64) - candidate.astype(np.float64))
    per_image_max = diff.reshape(diff.shape[0], -1).max(axis=1).tolist()
    max_diff = float(diff.max())
    mean_diff = float(diff.mean())
    return ComparisonResult(
        label=label,
        max_abs_diff=max_diff,
        mean_abs_diff=mean_diff,
        tolerance=tolerance,
        passed=max_diff <= tolerance,
        per_image_max_diff=per_image_max,
    )
