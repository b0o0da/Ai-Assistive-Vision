# Detection sequences (WS05-03, AVN-4)

Detector output streams for the tracker, the band/side estimators and engine scenarios, so they
can run before the real detector does. Boxes only, never images.

- Format: `detection_sequence.schema.json`. Frame 1080 x 1920 (portrait, chest mount), one frame every 100 ms.
- `expected`: every object in the **last** frame with its correct band and side. `DetectionSequenceFixturesTest`
  checks them against the app's real `DistanceBander` + `SideEstimator` (`config/bands.json`).
- Replay: `DetectionSequences.parse(json)` then `FakeDetector.load(sequence)`; each `detect()` returns the next frame.
- Regenerate: `python scripts/make_detection_fixtures.py` (fixed seed, same output every run).
- Validate: `python scripts/validate_contracts.py` also validates every file listed in `index.json`.

## Fixture index

| File | Frames | Objects | Path of the main object | Expected at the last frame |
|---|---|---|---|---|
| `chair_approach.json` | 15 | 1 chair | grows straight ahead (frames from AVN-40) | chair · medium · center |
| `person_close.json` | 15 | 1 person | far → near, left third | person · near · left |
| `car_from_right.json` | 12 | 1 vehicle | enters at the right edge, medium → near | vehicle · near · right |
| `crowded.json` | 12 | 6 people | small drift, no approach | 3 medium (left, center, right) + 3 far (left, center, right) |
| `empty.json` | 10 | none | no detections | — |
| `night.json` | 14 | 1 person | confidence 0.30–0.40, missing in frames 3, 4, 8; far → medium | person · medium · right |

`crossing.json` and `occlusion.json` are AVN-40's tracker fixtures; they keep their original shape and are not in `index.json`.

All six are **authored** (`"source": "authored"`), not recorded on the phone. When real recordings exist
(WS-04 / WS-05), add them with `"source": "recorded"` next to these.
