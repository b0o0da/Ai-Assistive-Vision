# TD-008: Transcript schema and ActionPlan field names (AVN-35, WS02-04)
16 Nov 2026 · Owner: Marwan · Status: **Proposed** (decided when the AVN-35 PR is approved)
Contract impact: [x] yes, contract version 1.0.0 → 1.1.0 (minor, additive; see docs/api-contract.md §7)

## Context
Notion task AVN-35 asks for `transcript.schema.json`, `action_plan.schema.json`, Kotlin models and
8 samples, with an ActionPlan that has `action`, `params`, `speak_text`, `requires_confirmation`
and an empty-transcript case.

What already exists after the Gate 1 freeze (contract 1.0.0):
- **ActionPlan**: schema in `android.schema.json`, Kotlin model, validator and 9 valid fixtures
  (AVN-220, PR #8, Mahmoud). Fields: `steps[{step, arg}]`, `requires_confirmation`, `spoken_ack`, `clarification`.
- **Transcript**: a Kotlin model only (`Transcript(text, confidence, language)` in `Models.kt`,
  under "phone-internal seam types with no JSON schema yet"). No schema, no fixtures.

## Options considered
1. **Rename ActionPlan to the Notion names** (`action`, `params`, `speak_text`). Breaking: major
   bump 2.0.0, changes `/intent` for the backend, the Pydantic model, 9 fixtures, the validator and the
   intent router already built on it (WS-14). Gains nothing: the meaning is the same.
2. **Keep ActionPlan, add the missing Transcript schema** as a minor bump 1.1.0, matching the Kotlin
   model field for field. Map the Notion names to the frozen ones in this record.

## Decision (proposed)
Option 2.

| Notion acceptance name | Frozen ActionPlan field (1.0.0) |
| --- | --- |
| `action` | `steps[].step` (whitelisted `PlanStep` enum; a plan is an ordered list of actions) |
| `params` | `steps[].arg` (string or null per step) |
| `speak_text` | `spoken_ack` (or `clarification` when there is no valid step) |
| `requires_confirmation` | `requires_confirmation` (same) |

Transcript (new `$defs/Transcript` in `contracts/schemas/android.schema.json`):
`text` (string, max 500, **may be empty**), `confidence` (0..1, 0 when empty), `language` (`en` | `ar`).
It lives in `android.schema.json`, not a separate `transcript.schema.json`, because the repo keeps all
on-phone seam types there (README of `contracts/`).

## Consequences
- Contract 1.1.0: `contracts/README.md`, `contracts/fixtures/index.json`, `docs/api-contract.md` bumped.
- 8 transcript fixtures in `contracts/fixtures/transcripts/` (incl. `empty.json`, `arabic_read_this.json`,
  `low_confidence.json`); validated by `scripts/validate_contracts.py` and by `TranscriptContractTest` (Kotlin).
- Kotlin model unchanged (it already had these three fields). No Pydantic change: Transcript never crosses
  the network (`/intent` takes `text`, not a Transcript).
- Privacy: transcripts are user speech; never logged (android/CLAUDE.md logging rule). Fixtures are fake.
- After merge: tag `contract-v1.1.0`; tick **Contract impact** on the Notion TD-008 row.
- Reviews needed: one Android owner (Loay, AVN-35 reviewer) and one backend owner (Mahmoud or Mostafa S.).
