# Computer-vision data (the CV workstream only)

Images and video stay local/private under `datasets/data/` (ignored by Git). This workstream
covers Egyptian street object detection and tracking; it does not modify the Android/iOS app,
OCR, speech, or another team's feature. MOT17 is for tracking evaluation only. COCO is generic
pretraining data and does not provide Egyptian tuk-tuk or microbus labels.

## Current state and honest limitation

The local Egyptian detection set is empty until the team records/imports images and labels them.
The Wikimedia Commons starter source lists only about 12 Egyptian tuk-tuk files and 11 Cairo
microbus files. Those are useful for bootstrapping annotation and checking the pipeline, but are
far too few and scene-biased to claim a production detector. The importer below fetches only
images with an explicit reusable license and saves per-file attribution/license metadata; it does
not scrape Google Images, bypass logins, or create labels automatically.

## 1. Seed with openly licensed images

From the repository root, run:

```sh
python3 datasets/tools/import_commons_egypt_vehicles.py --max-per-category 50
```

Images go into `datasets/data/detection/egyptian/images/commons/`; the JSONL provenance manifest
goes into `datasets/data/detection/egyptian/metadata/commons.jsonl`. The script skips files without
an explicit CC0, Public Domain, CC BY, or CC BY-SA license. Review the manifest and comply with
each image's attribution/share-alike terms before publishing a model or dataset. Imported images
are **not annotated** until a human labels them.

## 2. Collect the real Egyptian scenes with the team's iPhones

No Android phone is required for the CV dataset. Use the iPhones to capture short, varied clips
and stills in ordinary public settings, then AirDrop/copy them to the private dataset folder. Get
consent before filming identifiable people closely; avoid faces and readable plates where
possible, and do not publish raw footage. Never film in restricted/private places. See
[`collection-protocol.md`](collection-protocol.md).

Target scenes: tuk-tuks, Cairo-style microbuses, other vehicles for context, and the specific
hazards in [`labeling-guidelines.md`](labeling-guidelines.md). Capture multiple distances, angles,
occlusions, backgrounds, daylight/shade and (where safe) low light. Prefer diverse locations and
recording sessions over many near-identical frames from one clip.

## 3. Annotate and split without leakage

Import the selected images into CVAT or another box-labeling tool using the class names and
instructions in `labeling-guidelines.md`. Export YOLO detection format into:

```text
datasets/data/detection/egyptian/
  images/{train,val,test}/
  labels/{train,val,test}/
  metadata/commons.jsonl
```

Keep every frame from a single recording session/location in one split; never put adjacent video
frames in train and validation/test. Use a held-out test set that is not used to tune thresholds.
See [`split-policy.md`](split-policy.md). Train/fine-tune only after the held-out labels have been
reviewed. Report per-class precision/recall/mAP and false negatives for tuk-tuks, microbuses and
each hazard class, then evaluate the exported model's latency on the eventual target device.

## Data sources and roles

- COCO 2017: generic object pretraining / pipeline starter; no Egyptian-specialized labels.
- MOT17: pedestrian tracking association evaluation; not vehicle detection training data.
- ~~[Egypt Vehicle Detector on Roboflow Universe](https://universe.roboflow.com/ahmed-saad-fdnmf/egypt_vehicle_detector)~~:
  **dead link, confirmed 404 "Project Not Found" on 2026-11-14.** Do not assume its class
  definitions or guess a replacement project by name similarity — if this supplement is still
  wanted, a human must find and review a specific replacement project's `data.yaml` before any
  class mapping is written against it. Previously described as a small 60-image CC BY 4.0
  candidate supplement; not a substitute for the team's held-out Egyptian footage.
- [Wikimedia Commons tuk-tuks in Egypt](https://commons.wikimedia.org/wiki/Category:Tuk-tuks_in_Egypt)
  and [microbuses in Cairo](https://commons.wikimedia.org/wiki/Category:Microbuses_in_Cairo):
  small, openly licensed seed candidates; verify each file's license in the manifest.

Dataset images, labels, and extracted frames are intentionally excluded from Git. Commit only
scripts, guidelines, and aggregate reports without personal data.
