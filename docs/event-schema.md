# Event schema, contract v1 (DRAFT: freeze in `docs/api-contract.md` at the end of Week 2)

Marwan's input to WS-02. Machine-readable version: `contracts/event.schema.json`. Valid fixtures: `contracts/fixtures/events/` (5). Deliberately invalid ones: `tests/fixtures/events/invalid/` (9). Rule from WS-02: everything under `contracts/fixtures/` must pass its schema, so invalid examples live under `tests/`. Both Python (`scripts/validate_contracts.py`) and Kotlin (`ContractTest`) check the same files.

```json
{
  "id": "evt-002",
  "source": "vehicle_detection",
  "type": "vehicle_approaching",
  "label": "Vehicle approaching",
  "side": "left",
  "distance_band": "near",
  "confidence": 0.95,
  "risk": "critical",
  "timestamp": "2026-10-15T12:00:01Z"
}
```

| Field | Required | Values | Notes |
|---|---|---|---|
| `id` | yes | string, 1-64 chars | unique per event; used for logs and `SpeechRequest.event_id` |
| `source` | yes | `object_detection`, `vehicle_detection`, `ocr`, `scene_understanding`, `currency`, `face`, `screen`, `navigation`, `sos`, `system` | module that produced it |
| `type` | yes | `vehicle_approaching`, `obstacle_ahead`, `person_too_close`, `sos`, `text_detected`, `scene_description`, `notification`, `currency_detected`, `face_known`, `face_unknown` | |
| `timestamp` | yes | ISO 8601 UTC with `Z`, e.g. `2026-10-15T12:00:01Z` | no offsets |
| `label` | no | text, up to 120 | what is spoken; the engine makes one from `type` if empty |
| `side` | no | `left`, `center`, `right`, `unknown` | default `unknown` |
| `distance_band` | no | `near`, `medium`, `far`, `unknown` | **never meters**; default `unknown` |
| `confidence` | no | number 0..1 | engine drops events below 0.40 (SOS is exempt) |
| `risk` | no | `critical`, `warning`, `info` | producer's hint; see `risk-levels.md` |

Unknown fields are rejected by the schema (`additionalProperties: false`). At runtime the engine reads leniently (unknown fields ignored).

## Things I decided that need a yes from the team
1. **`risk` is a level (critical/warning/info), not a number.** The spec lists `risk` in the enum registry. If the CV team wants to send a 0..1 score instead, change it before the freeze.
2. **Only `id, source, type, timestamp` are required.** `sos` and `notification` have no geometry or confidence. Spec text lists all nine fields; confirm optional is acceptable.
3. **`unknown` added to `side` and `distance_band`** for events without geometry. The spec says `near|medium|far`; `unknown` is my addition.
4. **`obstacle_ahead` is the canonical name** (the project plan says `obstacle`; the engine accepts both, the schema only the first).
5. **`track_id` is NOT in v1.** It would let the engine mute repeats per tracked object. Without it the engine mutes by `type + side + label`. Proposal for v1.1.
6. **`source` and `type` lists** are my starting registry from the sample files; Moko and Mahmoud should add what they really emit.

## SpeechRequest (what the engine hands to `SpeechOutput.speak`)
`text`, `level` (`critical|warning|info`), `source`, `interruptible`, `event_id`, plus `utteranceId` (added by the engine so callbacks can be matched).

## Context (`updateContext`)
`listening, speaking, moving, navigationActive, offline, sosActive` (booleans). Defaults are conservative: `moving = true`.
