# Risk levels and the risk score

Three levels, decided by the engine from a risk score between 0 and 1:

| Level | Score | What happens |
|---|---|---|
| **Critical** | >= 0.80 | spoken immediately; cuts any interruptible speech; never cut by another alert |
| **Warning** | 0.40 - 0.79 | spoken when the speech is free; waits in the queue (4 s) otherwise |
| **Info** | < 0.40 | silent while walking, navigating or during SOS; spoken when the user is idle or asked |

## Score
```
score = clamp( base(type) + distance + side , 0..1 ) * ( 0.5 + 0.5 * confidence )

base:      sos 1.00 | vehicle_approaching 0.85 | person_too_close 0.60 | obstacle_ahead 0.55
           text_detected 0.15 | scene_description / notification / currency / face 0.10 | unknown type 0.10
distance:  near +0.15 | medium +0.05 | unknown 0 | far -0.10
side:      center +0.05 | otherwise 0
```
- Missing confidence counts as 1.0, so incomplete data never lowers a danger.
- `sos` is always 1.0.
- The producer's `risk` hint is a **floor**: `critical` forces at least 0.80, `warning` at least 0.40. It can never lower the level.

## Worked examples (checked in `RiskModelTest.docExamplesMatchTheModel`; all with the object in the centre)
| Event | Score | Level |
|---|---|---|
| vehicle, near, conf 0.90 | 0.950 | Critical |
| vehicle, medium, conf 0.91 | 0.907 | Critical |
| vehicle, far, conf 0.91 | 0.764 | Warning |
| vehicle, near, conf 0.60 | 0.800 | Critical (exactly on the threshold) |
| vehicle, near, conf 0.59 | 0.795 | Warning |
| obstacle, near, conf 0.91 | 0.716 | Warning |
| obstacle, far, left, conf 0.80 | 0.405 | Warning (barely) |
| text, near, conf 0.91 | 0.334 | Info |

Inside one level the queue is ordered by score, so a nearer, more central, more confident event is spoken first.

## Tuning
Numbers are starter values. `EngineConfig.criticalThreshold` / `warningThreshold` and the table in `RiskModel.kt` are the only places to change. Re-tune on real recordings (Weeks 17-20) using the alert log (`reasonCounts()`), and re-run `ReplayTest` after every change.
