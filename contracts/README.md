# Contracts — version 1.0.0

The shared language between the Android app and the backend. Status: **FROZEN at Gate 1** (tag `contract-v1`). Changes follow the process below and §7 of `docs/api-contract.md`.

- `schemas/` — JSON Schema (draft 2020-12): `common` (enums), `event`, `envelope`, `api` (the 9 endpoints), `android` (on-phone seam types: SpeechRequest, ActionPlan, ScreenSnapshot).
- `fixtures/` — example documents for every response and failure. `fixtures/index.json` maps each fixture to its schema.
- Kotlin models (`android/contracts`) and Pydantic models (`backend/app/schemas`) are written to match these files, and both test suites parse every fixture.

Check everything: `python scripts/validate_contracts.py` (CI runs it on every PR). It also checks that every payload in `tests/fixtures/contract_invalid/` (missing id, metres in the band, a `distance_m` field, unknown source, bad side, envelope rule breaks) is **rejected**; the Pydantic tests and the Kotlin `FixtureTest` reject the same files.

## Change process after the freeze
1. Write a Technical Decision in `docs/decisions/` (and Notion) with **Contract impact** ticked.
2. Bump the version here, in `fixtures/index.json` and in `docs/api-contract.md` (major for breaking, minor for additive, patch for wording).
3. Update schemas, fixtures, Kotlin and Pydantic models in the same PR.
4. Reviewed by at least one Android and one backend owner; tag `contract-vX.Y.Z` after merge.
