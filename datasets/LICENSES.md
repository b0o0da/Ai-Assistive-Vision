# Dataset and model licence log (WS-24)

Tracks where every dataset and model came from, under what licence, what we use it for,
and what consent covers it. Update this file in the same PR that adds a new source.

Last checked: 2026-10-25 (AVN-73). Not legal advice; re-verify before release.

**Rules**
- Data itself is never committed to Git (team drive only). This file lists sources, not content.
- A blank or unverified licence is written as `UNKNOWN - flagged`, never guessed.
- Real-note photos and volunteer face data are never published.
- Bystanders in street footage: follow the bystander rule in `collection-protocol.md` (to be added).

## Models

| Name | Source | Licence | Use in project | Consent | Notes |
| --- | --- | --- | --- | --- | --- |
| Ultralytics YOLO nano-class detector | github.com/ultralytics/ultralytics | AGPL-3.0, or paid Ultralytics Enterprise License | WS-05 object detection | n/a | AGPL applies to fine-tuned models too. Decision pending: TD-001 (accept AGPL for the whole project, buy Enterprise, or switch to an Apache-licensed detector before week 3) |
| COCO-pretrained baseline weights | UNKNOWN - flagged | UNKNOWN - flagged | WS-05 baseline before fine-tuning | n/a | Licence depends on which weights file; if from Ultralytics it is AGPL-3.0 |
| Google ML Kit (text, barcode, face detection) | developers.google.com/ml-kit | ML Kit Terms of Service + Google APIs Terms of Service | WS-07 OCR/QR, WS-08 face detection | n/a | Runs on-device. SDK sends performance metrics to Google; disclose in the consent text (WS-28) |
| Face embedding model (MobileFaceNet/ArcFace-style) | UNKNOWN - flagged | UNKNOWN - flagged | WS-08 face recognition (Should) | Volunteer written consent | InsightFace-trained weights are non-commercial research only. Pick weights with a verified licence first |
| Currency classifier (MobileNetV3-class) | UNKNOWN - flagged | UNKNOWN - flagged | WS-09 EGP recognition (Should) | n/a | Check licence of the pretrained base weights |
| openWakeWord pre-trained models | github.com/dscripka/openWakeWord | CC BY-NC-SA 4.0 (models) | WS-11 wake word (candidate) | n/a | Non-commercial + ShareAlike. Library code licence: UNKNOWN - flagged. Decision pending: TD-002 |
| Porcupine (Picovoice) | picovoice.ai | Picovoice Terms of Use / plan-based | WS-11 wake word (candidate) | n/a | Free tier reportedly ends 2026-06-30 (unofficial source). Verify on picovoice.ai. Decision pending: TD-002 |
| Depth Anything V2-Small | github.com/DepthAnything/Depth-Anything-V2 | Apache-2.0 | WS-10 (optional, default off) | n/a | Base/Large/Giant are CC-BY-NC-4.0: do not use those |

## Datasets

| Name | Source | Licence | Use in project | Consent | Notes |
| --- | --- | --- | --- | --- | --- |
| COCO | cocodataset.org | Annotations: CC BY 4.0. Images: Flickr Terms of Use (per-image licences) | WS-05 baseline | n/a | COCO does not own the image copyright; do not redistribute images |
| Egyptian street video | Own recordings (chest mount) | Project-owned | WS-05/06 training and eval | Per collection protocol | Stored privately; bystander rule applies |
| Signs, menus, medicine boxes | Own photos | Project-owned | WS-07 OCR test set | Per collection protocol | Ground-truth transcripts included |
| EGP banknote photos | Own (printed copies, real notes if available) | Project-owned | WS-09 | Per collection protocol | Real-note photos never published |
| Volunteer faces | Own, volunteers | Project-owned | WS-08 test set | Written consent required | Embeddings only, never published |
| Street-noise and command audio | Own recordings | Project-owned | WS-11/12 tests | Per collection protocol | |
| Mock screens | Own | Project-owned | WS-17/18 tests | n/a | No real user content |