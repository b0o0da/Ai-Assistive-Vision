# Models

Weights are never committed. `manifest.json` lists every model the app or the evaluation uses; `scripts/fetch_models.py` checks their SHA-256 and copies verified files into Android assets.

## General object detector

The current Android app uses a fine-tuned Ultralytics YOLOv8s ONNX model. Its input is `[1,3,640,640]`; its output is `[1,12,8400]` (four box values and eight class scores). The output classes, in order, are `person`, `transportation`, `pothole`, `bench`, `chair`, `fire hydrant`, `stop sign`, and `parking meter`.

The Colab training/export workflow is in `computer_vision/training/train_yolo_colab.ipynb`. The verified export is `computer_vision/training/exported/yolov8s_general_colab.onnx`; the Android asset is `android/app/src/main/assets/yolov8s_general.onnx`. Model binaries are ignored by Git, so they must be retained or regenerated separately. To stage this export for the model fetch script:

```sh
cp computer_vision/training/exported/yolov8s_general_colab.onnx models/yolov8s_general.onnx
python scripts/fetch_models.py --android
```

The script checks the SHA-256 in `manifest.json` before copying. The reported held-out test mAP50-95 is 0.387; `bench` and `chair` recall are weak, and the training data does not establish Egyptian-scene accuracy. The model metadata declares AGPL-3.0; the app uses ONNX Runtime Mobile for inference.

Manifest entry:

```json
{
  "name": "detector",
  "version": "coco-baseline-0.1",
  "file": "detector.tflite",
  "url": "https://.../detector.tflite",
  "sha256": "<64 hex chars>",
  "license": "AGPL-3.0 | Apache-2.0 | ...",
  "source": "where it came from and how it was trained",
  "input": "e.g. 320x320x3 float32, letterboxed",
  "output": "e.g. [1, N, 6] boxes xyxy + score + class",
  "labels": "detector_labels.txt"
}
```

`android/app/src/main/resources/config/detector.json` contains the app's class order and thresholds. Keep it, the Kotlin fallback config, and the ONNX output order synchronized whenever replacing the model.
