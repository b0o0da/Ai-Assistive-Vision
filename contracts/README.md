# Contracts — version 1.1.0

The shared language between the Android app and the backend. Status: **FROZEN at Gate 1** (tag `contract-v1`). Changes follow the process below and §7 of `docs/api-contract.md`.

- `schemas/` — JSON Schema (draft 2020-12): `common` (enums), `event`, `envelope`, `api` (the 9 endpoints), `android` (on-phone seam types: SpeechRequest, ActionPlan, ScreenSnapshot, Transcript).
- `fixtures/` — example documents for every response and failure. `fixtures/index.json` maps each fixture to its schema.
- Kotlin models (`android/contracts`) and Pydantic models (`backend/app/schemas`) are written to match these files, and both test suites parse every fixture.

Check everything: `python scripts/validate_contracts.py` (CI runs it on every PR). It also checks that every payload in `tests/fixtures/contract_invalid/` (missing id, metres in the band, a `distance_m` field, unknown source, bad side, envelope rule breaks) is **rejected**; the Pydantic tests and the Kotlin `FixtureTest` reject the same files.

## Change process after the freeze
1. Write a Technical Decision in `docs/decisions/` (and Notion) with **Contract impact** ticked.
2. Bump the version here, in `fixtures/index.json` and in `docs/api-contract.md` (major for breaking, minor for additive, patch for wording).
3. Update schemas, fixtures, Kotlin and Pydantic models in the same PR.
4. Reviewed by at least one Android and one backend owner; tag `contract-vX.Y.Z` after merge.

## Drift check and freeze lock (AVN-266, WS25-14)
`python scripts/check_contract_drift.py` runs in CI after `validate_contracts.py` and fails the build when:
- a Kotlin model in `android/contracts` or a Pydantic model in `backend/app/schemas` / `backend/app/core/envelope.py` has a field name or enum value the schema does not (or lacks one it has). The mapping of schema types to classes is the `OBJECTS` / `ENUMS` table at the top of the script; add a row when you add a mirrored type.
- any frozen file changed: `docs/api-contract.md`, `contracts/schemas/*.json`, `contracts/fixtures/**/*.json`. Their SHA-256 hashes and the contract version are in `contract.lock.json`.

After steps 1-3 above, regenerate the lock in the same PR: `python scripts/check_contract_drift.py --relock`. It refuses unless the version line in `docs/api-contract.md` is higher than the locked one **and** a `docs/decisions/TD-*.md` says `Contract impact: [x] yes ... -> X.Y.Z` for that version. Tests: `python -m pytest scripts/tests -q`.
