# MOT17 IoU tracker baseline

Generated from the local MOT17 train split using the supplied FRCNN detections. This isolates association quality; it does not measure the app detector or camera performance.

Association: IoU >= 0.30, high score >= 0.45, low score >= 0.10 (low-score boxes can only recover an existing ID), 8-frame lost-track retention. Detection matching for metrics uses IoU >= 0.50.

| Sequence | Frames | GT boxes | Tracked boxes | Detection precision | Detection recall | IDF1 | ID switches | Tracker ms/frame (Python reference) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| MOT17-02-FRCNN | 600 | 18581 | 7772 | 0.818 | 0.342 | 0.339 | 17 | 0.263 |
| MOT17-04-FRCNN | 1050 | 47557 | 28054 | 0.935 | 0.551 | 0.553 | 0 | 0.948 |
| MOT17-05-FRCNN | 837 | 6917 | 3675 | 0.974 | 0.517 | 0.570 | 20 | 0.039 |
| MOT17-09-FRCNN | 525 | 5325 | 2996 | 0.995 | 0.560 | 0.512 | 1 | 0.052 |
| MOT17-10-FRCNN | 654 | 12839 | 9154 | 0.829 | 0.591 | 0.409 | 174 | 0.337 |
| MOT17-11-FRCNN | 900 | 9436 | 5821 | 0.966 | 0.596 | 0.549 | 21 | 0.070 |
| MOT17-13-FRCNN | 750 | 11642 | 7699 | 0.868 | 0.574 | 0.483 | 269 | 0.276 |

Aggregate sequence-weighted IDF1: **0.499** (5316 frames).

Total GT boxes: 112297; tracked boxes: 65171.

Limitations: MOT17 is pedestrian tracking, so this does not validate vehicle approach, near/medium/far calibration, chest-mounted Egyptian street scenes, or detector accuracy. Run the same replay against held-out field video before claiming those behaviors.
