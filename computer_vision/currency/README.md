# EGP banknote fixture set (WS09-02)

Labelled photos of Egyptian banknotes for the currency baseline (WS09-03, owner Mahmoud). The
photos stay on the team drive; Git holds only the manifest that lists them and the tools here.

## Status (25 Nov 2026)

`fixtures/manifest.json` lists **578 banknote crops** (egp_5 116, egp_10 106, egp_20 101, egp_50 82,
egp_100 78, egp_200 95), well over the 20-per-class minimum. Source: Roboflow Universe
"Egypt banknote" v1 by CustomYolo, **CC BY 4.0**, downloaded 25 Nov 2026 with Marwan's account.
Only the `valid` and `test` splits are used (300 images, no augmentation); each labelled box is cut
out as its own image (`import-roboflow --crop`), because most images hold several notes. The
2,096 augmented `train` images are left for training (WS09-03). Photos are on the team drive /
`D:\AVN\Data\currency_egypt_banknote_v1`, never in Git.

What this set is, honestly:
- The images are **synthetic composites**: cut-out banknotes pasted, rotated, onto unrelated scene
  photos. Good for training and for a first sanity check; **not** a stand-in for real phone photos.
  Own photos (option 1 below) are still needed before WS07/WS09 accuracy numbers are trusted.
- Roboflow stretched every image to 640x640, so a note's shape no longer tells its viewing angle,
  and the notes are pasted flat (in-plane rotation only): `angle` is `unknown` for all crops and
  the validator reports missing angle variety. **This is the one open item**: it needs own photos
  taken at different angles.
- Blur: all crops measured `sharp`; no blur variety in this data.
- Lighting (measured mean brightness): normal 549, dim 15, bright 14.
- Labels: all 578 crops were checked visually on one numbered sheet per class (colour and
  design against the class) by Claude on 25 Nov 2026: no wrong label found; a few crops have a
  second note partly covering the labelled one (e.g. egp_10 #92). This is recorded as
  `checked_by: Claude (AI visual check ...; not a human check)`. A human reviewer (PR reviewer)
  can confirm with `contact_sheet.html` and rebuild with their name.
- No polymer 10/20 notes in this data; those two classes are marked optional in `classes.json`.
- Roboflow's readme says the images come from a Kaggle dataset (egyptiris/egyptian-currency)
  whose own licence is not stated: flagged in `datasets/LICENSES.md`.

Ways to complete or improve it, both allowed by `datasets/LICENSES.md`:

1. **Own photos** (preferred, project-owned): printed copies or real notes, phone camera, varied
   lighting (daylight, indoor, dim, flash), angle (flat, tilted, steep) and some blur. Real-note
   photos are never published.
2. **Another open dataset with a CC BY licence**, logged in `datasets/LICENSES.md` before use, e.g.
   "New Egyptian Currency" by Belal Safy Graduation Project (4,787 images, 17 classes front/back,
   CC BY 4.0, needs a fork before export):
   https://universe.roboflow.com/belal-safy-graduation-project/new-egyptian-currency-4yuzo

How this set was made (repeatable):

```
python -m computer_vision.currency.tools import-roboflow EXPORT --map computer_vision/currency/roboflow_egypt_banknote_map.json -o IMAGES --splits valid test --crop
python -m computer_vision.currency.tools autotag IMAGES -o IMAGES/labels.csv     (angle then set to unknown, see above)
python -m computer_vision.currency.tools build IMAGES --source "<dataset url>" --licence "CC BY 4.0" --consent "n/a ..." \
    --collected-by "CustomYolo (Roboflow Universe)" --checked-by "PENDING second-person check (contact sheet)" \
    --labels IMAGES/labels.csv -o computer_vision/currency/fixtures/manifest.json
```

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
