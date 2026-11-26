# Raw detector outputs (WS05-07)

Authored, pre-postprocessing detector outputs: what a detector returns before the spec-label
filter, per-class thresholds and NMS. `DetectionPostprocessorTest` replays each file through
`DetectionPostprocessor` and checks `expected` (labels after mapping, highest confidence first).

| File | What it checks |
|---|---|
| `person_duplicates.json` | three overlapping boxes for one person collapse to one; a second person stays |
| `car_duplicates_and_cat.json` | `car` and `truck` for the same box map to `transportation` and collapse; `cat` and `tv` are dropped |
| `mixed_overlap.json` | overlapping boxes of different classes both stay; duplicate chair removed; weak and degenerate boxes dropped |

Boxes are in pixels of a 1080x1920 frame. No images; authored numbers only.
