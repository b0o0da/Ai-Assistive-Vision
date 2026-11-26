# Jitter fixture (WS06-03)

`chair_jitter.json`: one chair that does not move, seen for 60 frames (10 fps) with the kind of
box noise a real detector produces. Its true size sits just under the chair's near/medium line in
`config/bands.json`, so the raw band keeps flipping. `BoxSmootherTest` checks that smoothing lowers
the box variance and the number of band flips, and that it lags real motion by under 2 frames.

Authored (seeded random, seed 7), same JSON format as `tests/fixtures/detections/`. No images.
