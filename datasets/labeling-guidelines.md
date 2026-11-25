# Detection labels and annotation rules (draft v1)

Use these exact class names when creating the YOLO/CVAT project. Draw tight boxes around visible
objects, including partially occluded objects when their class is still identifiable. Do not
invent a box for an object that is fully hidden. Mark truncated objects at the image boundary with
the visible extent. Exclude unreadable/ambiguous cases from training and add them to a review list.

## Classes

| Class | Include | Exclude / notes |
|---|---|---|
| `person` | Visible people, for general scene context | Do not infer identity; blur/discard clear faces before sharing |
| `car` | Passenger cars and taxis | Do not include tuk-tuks or vans |
| `microbus` | Egyptian shared-route minibus/microbus body style | A generic van with no evidence it is a microbus is `van` only if that class is added; otherwise skip |
| `tuk_tuk` | Three-wheeled motorized tuk-tuks / auto-rickshaws | Exclude bicycles and pedal rickshaws |
| `bus` | Full-size passenger buses | Do not label microbuses as buses |
| `truck` | Goods/commercial trucks | Do not label buses or pickup cars as trucks unless the annotation team defines that boundary |
| `motorcycle` | Motorcycles and scooters | Exclude tuk-tuks |
| `pothole` | Visible road-surface hole/depression | Do not label shadows or repaired patches as potholes |
| `debris` | Discrete material/object obstructing the walking path (e.g. rubble) | Exclude ordinary curb/road texture and small litter not a meaningful obstacle |
| `open_drain` | Open/uncovered drain or manhole hazard | Exclude closed covers and ordinary grates |
| `curb_or_step` | Raised curb or abrupt step relevant to a pedestrian path | Skip ordinary flat sidewalk edges that are not a hazard |
| `low_hanging_obstacle` | Fixed/hanging object intruding into head/upper-body path | Needs explicit project definition before broad annotation; use only clear cases |

Do not use a single generic `obstacle` label for all hazards. These hazards look different and need
separate labels if the product is expected to name/act on them. If a class boundary is unclear,
flag it for review rather than guessing. Keep OCR text recognition separate from these detection
boxes; a sign/book/medicine label is not an obstacle class.

## Annotation and review

Use axis-aligned bounding boxes for the first detector. One box per instance and one line per box
in YOLO format: `class_id x_center y_center width height`, normalized to image width/height. Keep
the class ID order in one versioned YAML. Double-label a random 10% of the seed set and resolve
disagreements before training. Track per-class counts and label corrections in the private data
manifest. Do not label an image as reviewed until another person has checked its boxes and class.
