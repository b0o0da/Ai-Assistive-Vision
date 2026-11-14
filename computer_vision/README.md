# Computer vision (WS-05, WS-06, WS-09, WS-10, WS-24)

- `training/` — fine-tuning scripts (detector, currency classifier). Runs tracked in Weights & Biases.
- `export/` — export to LiteRT (TFLite) or ONNX, plus a check that the exported model matches `models/manifest.json` input/output.
- `eval/` — evaluation on frozen held-out sets: precision/recall per class, latency per device, reports committed as Markdown.

Data never goes in Git (see `datasets/README.md`). Licence decision for the detector: TD-001.

Egyptian vehicle/hazard collection, Commons import, iPhone capture, annotation classes and
session-level split policy are documented under `datasets/`. The Commons importer downloads only
explicit CC0/Public Domain/CC BY/CC BY-SA candidates and records attribution; it does not produce
labels. The locally captured, labeled, session-separated Egyptian set is the required source for
final local-scene detector evaluation. COCO remains generic pretraining, and MOT17 remains tracking
association evaluation.

## Current local data workflows

Prepare the already downloaded COCO train/validation images and annotations as a YOLO-format
starter subset (the images are symlinked, not duplicated):

```sh
python3 computer_vision/training/prepare_coco_yolo.py --split both
```

The default classes are person, common vehicles, bench/chair, fire hydrant, stop sign and parking
meter. This is a starter subset; the final hazard label set still needs the WS-24 labeling
guidelines. Generated labels, the YAML manifest and class counts go under ignored
`datasets/prepared/avn_coco/`.

The MOT17 association baseline can be replayed without image loading using the benchmark's
FRCNN detections and ground truth:

```sh
python3 computer_vision/eval/evaluate_mot17_iou_tracker.py
```

It writes `eval/mot17_iou_tracker_report.md`. The script needs NumPy and SciPy. This measures the
association baseline separately from detector quality; MOT17 has pedestrian sequences only and
does not validate vehicle approach or chest-mounted Egyptian street scenes. Detector training and
on-device export remain gated on TD-001 and a model-training machine.
