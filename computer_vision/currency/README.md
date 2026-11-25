# EGP banknote fixture set (WS09-02)

Labelled photos of Egyptian banknotes for the currency baseline (WS09-03, owner Mahmoud). The
photos stay on the team drive; Git holds only the manifest that lists them and the tools here.

## Status (25 Nov 2026)

Tools, schema and tests are ready. **No photos yet**, so the acceptance bar (at least 20 photos
per class, lighting/angle/blur variety, labels checked by a second person, licence and consent
noted) is not met. Two ways to fill it, both allowed by `datasets/LICENSES.md`:

1. **Own photos** (preferred, project-owned): printed copies or real notes, phone camera, varied
   lighting (daylight, indoor, dim, flash), angle (flat, tilted, steep) and some blur. Real-note
   photos are never published.
2. **An open dataset with a CC BY licence**, logged in `datasets/LICENSES.md` before use. Candidates
   checked on 25 Nov 2026 (not downloaded; Roboflow needs a signed-in account to export):
   - "Egypt banknote" by CustomYolo, v1 (2,396 images incl. augmentations, CC BY 4.0):
     https://universe.roboflow.com/customyolo/egypt-banknote-b3jlr/dataset/1
   - "New Egyptian Currency" by Belal Safy Graduation Project (4,787 images, 17 classes front/back,
     CC BY 4.0, no exported version yet, needs a fork): https://universe.roboflow.com/belal-safy-graduation-project/new-egyptian-currency-4yuzo
   CC BY needs attribution in the app's notices. Horizontal-flip augmentations mirror the print;
   keep them for training only, not for the fixture set.

## Classes

`classes.json` (PROPOSED until WS09-01 confirms): egp_5, egp_10, egp_10_polymer, egp_20,
egp_20_polymer, egp_50, egp_100, egp_200. Front and back are the same class.

## How to add photos

```
IMAGES/egp_50/front_daylight_01.jpg      one folder per class id
IMAGES/labels.csv                        file,lighting,angle,blur  (file relative to IMAGES)

python -m computer_vision.currency.tools build IMAGES --source own_photo --licence project-owned \
    --consent "own notes, photographer" --collected-by <you> --checked-by <second person> \
    --labels IMAGES/labels.csv -o computer_vision/currency/fixtures/manifest.json
python -m computer_vision.currency.tools sheet computer_vision/currency/fixtures/manifest.json --images IMAGES -o sheet.html
python -m computer_vision.currency.tools validate computer_vision/currency/fixtures/manifest.json --images IMAGES
```

`sheet.html` is the contact sheet the second person uses to check every label. Commit only
`manifest.json`; `.gitignore` blocks images under `computer_vision/currency/`.

From a Roboflow YOLO export (after it is logged in `datasets/LICENSES.md`):

```
python -m computer_vision.currency.tools import-roboflow EXPORT --map map.json -o IMAGES
```

`map.json` maps the dataset's class names to the ids above, e.g. `{"50 front": "egp_50", "50 back": "egp_50"}`.
Images whose boxes map to more than one class are skipped.

## Tests

```
python -m unittest computer_vision/currency/test_fixture_manifest.py
```

`test_fixture_manifest_valid` and the other checks run on throwaway files: per-class minimum,
lighting/angle variety, known licence, second-person check, duplicates, changed or missing
files, contact sheet, CLI and the Roboflow import.
