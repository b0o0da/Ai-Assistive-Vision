# Dataset split policy

The split unit is a **capture session/location**, not an image or frame. Assign the session before
sampling frames and keep every still/clip/frame from that session in exactly one split. This
prevents nearly duplicate video frames and repeated backgrounds from making validation look
artificially strong.

- `train`: roughly 70% of sessions, used to fit weights.
- `val`: roughly 15%, used for model/threshold selection.
- `test`: roughly 15%, held untouched until the model choice is frozen.

With few sessions, keep at least one whole location/session for validation and one different
location/session for test; report the actual counts rather than forcing these percentages. Ensure
each split has examples of each target class where possible. If a rare class is absent from test,
say so and collect another session. Do not split one video across train and validation.

For public web images, all files from the same original photo series/page/uploader should remain in
one split. Check perceptual near-duplicates across splits. Publish aggregate metrics only; keep
faces, plates, location-sensitive metadata and raw footage private.
