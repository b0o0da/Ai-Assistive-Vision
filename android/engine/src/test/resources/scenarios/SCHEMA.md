# Engine scenario format (WS16-01)

A scenario is a timeline of inputs to the priority engine plus the decisions we expect it to make.
Scenarios are golden tests: the replay runner (WS16-02) feeds the steps to the engine with a fake clock
and compares the decision log with `expect`. Kotlin model: `avn.engine.scenario.Scenario`.
`ScenarioLoader.parse()` is the validator: if it accepts a file, the file is valid.

## Top level

| Field | Type | Required | Meaning |
| --- | --- | --- | --- |
| `name` | string | yes | Short id, same as the file name without `.json` |
| `description` | string | yes | One sentence: what situation and what rule it checks |
| `steps` | array of Step | yes, at least 1 | Inputs in time order |
| `expect` | array of Expected | yes, at least 1 | Decisions the engine must log |

Unknown keys anywhere are an error, so a typo fails the test instead of being ignored.

## Step

Exactly **one** of `event`, `candidate`, `context` per step.

| Field | Type | Meaning |
| --- | --- | --- |
| `at_ms` | integer >= 0 | Time since scenario start. Never smaller than the step before it |
| `event` | Event | `contracts/schemas/event.schema.json`, unchanged. Producers send `risk: 0` |
| `candidate` | SpeechCandidate | A non-camera result to speak. Give it an `event_id` if `expect` refers to it |
| `context` | Context | Context change. Only the fields you set change; at least one is required |

### Context fields

All optional booleans, mirroring `EngineInput.updateContext(...)` (architecture §5):
`listening`, `speaking`, `moving`, `navigation_active`, `offline`, `sos_active`.

## Expected

| Field | Type | Required | Values |
| --- | --- | --- | --- |
| `event_id` | string | yes | An `event.id` or `candidate.event_id` from the steps |
| `decision` | string | yes | `spoken`, `queued`, `held`, `suppressed`, `dropped`, `interrupted`, `merged`, `expired` |
| `reason` | string | no | `low_confidence`, `below_threshold`, `cooldown`, `duplicate`, `merged`, `expired`, `preempted`, `queue_overflow` |

Decision and reason values come from architecture §5 "Logging".

## Rules for writing scenarios

- One rule per scenario. Name the file after the rule, for example `cooldown_warning_repeat.json`.
- Expected decisions follow the §5 starting values. When `engine_config.json` values change after tuning
  (WS16-15), update the affected scenarios in the same PR.
- Use only fake data. No real names, messages or screen text.

## Example

See `sample.json`: the user is moving, a far person at confidence 0.15 is dropped as `low_confidence`,
and a user-requested sign reading is `spoken`.
