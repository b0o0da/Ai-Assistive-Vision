# Route replay fixture (WS20-01, AVN-20)

A stored GPX walk so navigation (WS-20) can be built and tested at a desk, with no GPS and no
phone (architecture.md: "GPX replay lets it be developed at a desk").

- `pharmacy_walk.gpx`: 10 `<trkpt>` fixes, 2 seconds apart, walking the same three-step route as
  the "nearest pharmacy" example in `contracts/fixtures/api/route.ok.json` (demo-script.md
  scenario 7). **Authored**, not recorded on a phone -- plain synthetic coordinates, like the
  authored detection sequences in `tests/fixtures/detections/`.
- Format: GPX 1.1, `<trkpt lat="" lon="">` with a `<time>` child (ISO-8601, UTC). No accuracy or
  speed extensions -- the replay fixture only needs position and time.
- Parsed by `avn.app.navigation.GpxTrack.parse(xml)` (pure Kotlin, no Android dependency).

## Two copies, one source of truth

This file is also bundled as a classpath resource at
`android/app/src/main/resources/routes/pharmacy_walk.gpx`, which `FakeLocationSource` loads at
runtime (an Android asset/resource must live inside the app module; it cannot read this
top-level `tests/` folder on a real phone). Both copies must stay byte-identical -- this one is
the version JVM tests load directly (`avn.repoRoot` system property, same pattern as
`tests/fixtures/detections/`), and the one under `resources/` is what the running app replays.
If you edit the route, copy the file to both paths.

## Tests

- `GpxTrackTest`: parser correctness (point count, lat/lon, `<time>` parsing, missing-`<time>`
  fallback).
- `RouteTrackerGpxReplayTest`: feeds every fix in `pharmacy_walk.gpx` through the real
  `RouteTracker` against a hand-built route whose steps end at this fixture's own waypoints --
  asserts step progression to arrival with no off-route flag, then a second case that perturbs one
  fix to confirm off-route detection still fires on GPX-sourced data (architecture.md WS-20
  testing: "GPX replay unit tests for step progression and off-route").
- `FakeLocationSourceTest`: the bundled resource loads, `lastKnown()` returns the first fix.
