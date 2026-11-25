# Egyptian street image collection with iPhones

This protocol is for the computer-vision dataset. A phone is only the capture device; model
training, annotation and evaluation happen on the development machine. No Android handset is
needed to collect or annotate this data.

## Capture checklist

1. Make a separate session per location and time, e.g. `2026-11-14_cairo_site01_day`. Keep a
   private note of the date, broad area, lighting, phone, and session ID; do not record a person's
   name or precise home location.
2. Capture both stills and short video clips with the standard Camera app. Use the rear camera,
   clean the lens, avoid digital zoom, and frame the whole object. Include wide/context views and
   closer views at safe distances.
3. Include genuine variety: front/side/rear, near/far, partial occlusion, parked/moving, busy and
   quiet backgrounds, sun/shade, and different vehicle colors and body styles. Capture hazards
   separately and in context. Do not step into traffic or approach moving vehicles to get a shot.
4. Capture negative scenes too (no target vehicle/hazard) so the detector learns where not to
   fire. Avoid taking hundreds of almost-identical frames from one stationary viewpoint.
5. Do not deliberately record clear faces or license plates. If a bystander/plate is prominent,
   discard the image or blur it before annotation/sharing. Obtain permission for close identifiable
   people and private property. Do not film restricted locations.
6. AirDrop/copy originals into the private data directory. Preserve session IDs in filenames or a
   separate private capture manifest. Do not upload raw clips to Git, public labeling tools, or a
   public drive. Remove GPS/EXIF metadata from any image that may leave the private team storage.

## Video sampling

Avoid random dense frame extraction: adjacent frames leak nearly identical scenes across splits.
For a first pass, extract at most one frame every 1–2 seconds, then manually remove duplicates and
blur/discard privacy-sensitive frames. Keep the original clip private and keep all extracted frames
from the same recording session in the same train/val/test split. A clip can yield hundreds of
frames, but those frames are not hundreds of independent scenes.

## Practical starting target

Start with 10–20 distinct sessions across multiple public locations and conditions. Annotate a
balanced seed set first, review class counts and missed examples, then collect more where coverage
is weak. A few hundred carefully varied, reviewed examples per rare class are a useful initial
milestone, not a guarantee of deployment-grade accuracy. Keep a separate untouched test set with
whole sessions/locations held out.
