#!/usr/bin/env python3
"""Generate the WS05-03 (AVN-4) detection sequences in tests/fixtures/detections/.

Authored, not recorded: boxes follow a scripted path (linear growth for approaches, a little
deterministic jitter). Same output on every run (fixed seed), so diffs stay readable.

Usage:  python scripts/make_detection_fixtures.py
Writes: person_close.json, car_from_right.json, crowded.json, empty.json, night.json and
        adds the metadata block to chair_approach.json (its frames, owned by AVN-40, are kept as is).

`expected` lists, for the LAST frame, every object's label with the band and side a correct
DistanceBander / SideEstimator must give (config/bands.json, frame thirds). The Kotlin test
DetectionSequenceFixturesTest checks the app's real bander against these labels.
"""
from __future__ import annotations

import json
import random
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "tests" / "fixtures" / "detections"
W, H = 1080, 1920
STEP_MS = 100


def lerp(a: float, b: float, t: float) -> float:
    return a + (b - a) * t


def box(label: str, conf: float, x: float, y: float, w: float, h: float) -> dict:
    # Clip to the frame like a real detector does.
    x, y = max(0.0, x), max(0.0, y)
    w, h = min(w, W - x), min(h, H - y)
    return {"label": label, "confidence": round(conf, 3), "x": round(x, 1), "y": round(y, 1), "w": round(w, 1), "h": round(h, 1)}


def approach(label, conf, start, end, frames, rng, jitter=4.0):
    """start/end = (x, y, w, h). Linear growth from start to end."""
    out = []
    for i in range(frames):
        t = i / (frames - 1)
        x, y, w, h = (lerp(s, e, t) for s, e in zip(start, end))
        jx, jy = (rng.uniform(-jitter, jitter) for _ in range(2))
        out.append(box(label, conf, x + jx, y + jy, w, h))
    return out


def sequence(name, description, frames, expected):
    return {
        "name": name,
        "description": description,
        "source": "authored",
        "frameWidth": W,
        "frameHeight": H,
        "expected": expected,
        "frames": [{"timestampMs": i * STEP_MS, "detections": dets} for i, dets in enumerate(frames)],
    }


def main() -> None:
    rng = random.Random(42)
    files = {}

    # 1. A person walks toward the user on the left: far -> near.
    person = approach("person", 0.88, (180, 560, 120, 300), (60, 700, 380, 950), 15, rng)
    files["person_close"] = sequence(
        "person_close",
        "One person walks toward the user on the left; box grows from far to near.",
        [[b] for b in person],
        [{"label": "person", "band": "near", "side": "left"}],
    )

    # 2. A car enters from the right edge and comes closer: far -> near, stays on the right.
    car = approach("vehicle", 0.82, (960, 700, 120, 90), (640, 800, 420, 640), 12, rng)
    files["car_from_right"] = sequence(
        "car_from_right",
        "A car enters from the right edge and approaches; box grows and moves inward but stays in the right third.",
        [[b] for b in car],
        [{"label": "vehicle", "band": "near", "side": "right"}],
    )

    # 3. Crowded pavement: six people, small drift, none near.
    # (x, y, w, h) at start; each drifts a little. Three clearly medium (height ~0.21, bottom ~0.58)
    # and three clearly far (height ~0.11, bottom ~0.40), well away from the band thresholds.
    people = [
        (40, 720, 120, 400), (250, 560, 90, 210), (420, 730, 120, 400),
        (620, 560, 90, 210), (800, 720, 120, 400), (960, 560, 90, 210),
    ]
    crowd_frames = []
    for i in range(12):
        dets = []
        for j, (x, y, w, h) in enumerate(people):
            drift = (i * (1 if j % 2 else -1)) * 2.0
            dets.append(box("person", 0.7 + 0.03 * j, x + drift + rng.uniform(-3, 3), y + rng.uniform(-3, 3), w, h))
        crowd_frames.append(dets)
    files["crowded"] = sequence(
        "crowded",
        "Six people on a busy pavement, small drift: three at medium distance (one per third), three far.",
        crowd_frames,
        [],  # filled below from the last frame
    )

    # 4. Nothing in view.
    files["empty"] = sequence("empty", "Empty corridor: no detections in any frame.", [[] for _ in range(10)], [])

    # 5. Night: low confidence, the person drops out in some frames (detector flicker).
    night_person = approach("person", 0.38, (820, 640, 110, 300), (760, 640, 200, 520), 14, rng, jitter=8.0)
    night_frames = []
    for i, b in enumerate(night_person):
        b = dict(b, confidence=round(0.3 + 0.1 * rng.random(), 3))
        night_frames.append([] if i in (3, 4, 8) else [b])
    files["night"] = sequence(
        "night",
        "Night street: one person on the right at low confidence (0.30-0.40), missing in frames 3, 4 and 8.",
        night_frames,
        [{"label": "person", "band": "medium", "side": "right"}],
    )

    # Crowded: expected = every box of the last frame (bands/sides written out by hand below).
    files["crowded"]["expected"] = [
        {"label": "person", "band": "medium", "side": "left"},
        {"label": "person", "band": "far", "side": "left"},
        {"label": "person", "band": "medium", "side": "center"},
        {"label": "person", "band": "far", "side": "center"},
        {"label": "person", "band": "medium", "side": "right"},
        {"label": "person", "band": "far", "side": "right"},
    ]

    for name, doc in files.items():
        (OUT / f"{name}.json").write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8", newline="\n")

    # chair_approach.json: keep AVN-40's frames, add the metadata block in front of them.
    chair_path = OUT / "chair_approach.json"
    chair = json.loads(chair_path.read_text(encoding="utf-8"))
    meta = sequence(
        "chair_approach",
        "A chair straight ahead gets closer; box grows every frame (AVN-40 frames, unchanged).",
        [],
        [{"label": "chair", "band": "medium", "side": "center"}],
    )
    meta["frames"] = chair["frames"]
    chair_path.write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8", newline="\n")
    print("wrote", ", ".join(sorted(list(files) + ["chair_approach"])))


if __name__ == "__main__":
    main()
