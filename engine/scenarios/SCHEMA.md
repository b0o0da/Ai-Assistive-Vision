# Scenario format (`engine/scenarios/*.json`, `scenario_version` 1)

A scenario is a **timeline of inputs with the decisions the engine must make**. `ScenarioReplayer` runs it against the real `PriorityEngine` with a simulated clock and a simulated TTS, so it is deterministic and needs no phone. The same file is used by unit tests (`ReplayTest`), the laptop demo (`DemoKt`) and the final report. `sample.json` is the smallest example.

```json
{
  "scenario_version": 1,
  "name": "short title",
  "description": "what this scenario shows",
  "tts": { "ms_per_char": 60 },
  "steps": [ ... ],
  "expect": { "spoken": ["..."], "not_spoken": ["..."] }
}
```

| Field | Required | Meaning |
|---|---|---|
| `scenario_version` | yes | must be `1` |
| `name`, `description` | no | text for reports |
| `tts.ms_per_char` | no (60) | simulated speaking speed; an utterance lasts `max(400 ms, text length x ms_per_char)` |
| `steps` | yes | the timeline; sorted by `t_ms` when loaded |
| `expect` | no | whole-scenario checks, see below |

## Steps
Every step has `t_ms` (milliseconds from the start) and **exactly one** of `event`, `speech`, `context`.

### `event`: an `Event` as in `contracts/event.schema.json`
Read leniently: `timestamp` may be left out (the step time is used) and `track_id` is accepted. `type`, `source`, `id` are still required and checked.

### `speech`: a `SpeechCandidate`
| Field | Required | Default |
|---|---|---|
| `id`, `text` | yes | |
| `source` | no | `scenario` |
| `level` | no | `info` (`critical` / `warning` / `info`) |
| `user_requested` | no | false |
| `sensitive` | no | false (true = the log stores only `[redacted N chars]`) |
| `dedupe_key`, `ttl_ms`, `interruptible`, `event_id` | no | engine defaults |

### `context`: the `EngineContext` flags
`listening`, `speaking`, `moving`, `navigation_active`, `offline`, `sos_active` (booleans). A flag you leave out keeps the **default** (`moving` is true, the others false), not the previous step's value, so write the full state you want.

### `expect_decision` (optional, on `event` and `speech` steps)
The decision the engine must return for that input:
```json
"expect_decision": { "outcome": "SUPPRESSED", "reason": "COOLDOWN" }
```
`reason` is optional (omit it to accept any reason). A mismatch is reported as `t=300 ms, e2: expected SUPPRESSED/COOLDOWN but the engine decided ...`.

| `outcome` | Meaning |
|---|---|
| `SPOKEN_NOW` | spoken immediately |
| `SPOKEN_INTERRUPT` | spoken after cutting the current speech |
| `SPOKEN_FROM_QUEUE` | spoken later, from the queue (appears in the log, not as a step result) |
| `QUEUED` | waits for a gap |
| `SUPPRESSED` | never spoken |
| `INTERRUPTED`, `EXPIRED`, `DROPPED` | logged for items that were cut, timed out or pushed out of the queue |

| `reason` | Meaning |
|---|---|
| `OK` | nothing special |
| `INVALID` | input broke the schema |
| `LOW_CONFIDENCE` | below the noise threshold (SOS exempt) |
| `INFO_NOT_REQUESTED` | unrequested info while moving, navigating or during SOS |
| `COOLDOWN` | same thing was spoken recently |
| `DUPLICATE` | same text waiting, speaking or just spoken |
| `WAITING_FOR_GAP` | queued behind current speech (or held while listening) |
| `PREEMPTED` | cut by a critical alert |
| `TTL_EXPIRED` | waited too long |
| `QUEUE_FULL` | pushed out of a full queue |
| `SUPERSEDED` | replaced by a more severe duplicate |

## `expect` (whole scenario)
- `spoken`: the **exact ordered list** of texts the TTS starts (a text that was cut off still counts as spoken).
- `not_spoken`: texts that must never be spoken.

## Rules for writing scenarios
1. Describe the situation in `description` in one or two sentences.
2. Put an `expect_decision` on every step whose outcome is the point of the scenario.
3. Keep timings in round numbers; remember the utterance length (`ms_per_char`) when you plan an interruption.
4. After changing thresholds or rules, run `ReplayTest`: a changed decision must be a deliberate edit to the scenario, reviewed like a code change.
