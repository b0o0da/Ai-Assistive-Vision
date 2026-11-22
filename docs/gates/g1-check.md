# Gate 1 exit check (AVN-295, WS25-17)

22 Nov 2026 (week 7) · Owner: Mostafa S. · Reviewer: Mahmoud · Checked on `develop` @ `7e70baf` (PR #44)

**Gate 1 claim** (docs/architecture.md, "Phase gates"): *contracts frozen, camera to speech works.*
Planned for the end of week 2. First check on 27 Oct: NO-GO.

## Verdict: NO-GO (contracts PASS, camera to speech NOT DEMONSTRATED)

Contracts are frozen and checked on both sides. Camera to speech still has no end-to-end run:
the engine pieces are merged, but no assembled engine and no Rung 1 test exist yet. Re-run this
check when the "Close-out" items below are merged.

## 1. Contracts frozen: PASS

| Check | Result | Evidence |
| --- | --- | --- |
| Contract version and status | PASS | `docs/api-contract.md`: Version 1.1.0, FROZEN; change rule in section 7 |
| Fixtures valid, negatives rejected | PASS | `python scripts/validate_contracts.py`: 74 fixtures checked (8 must-reject), 0 failures |
| Backend (Pydantic) matches fixtures | PASS | `cd backend && ruff check . && pytest -q`: all checks passed, 141 passed |
| Kotlin and Pydantic match schemas | PASS | `scripts/check_contract_drift.py` (AVN-266, PR pending): 57 comparisons, 0 drift |
| Kotlin parses every fixture | Not re-run here | `android/contracts` `FixtureTest`; no Android SDK in this check. Confirm with the CI "Android" job on `develop` |
| Tag `contract-v1` (and `contract-v1.1.0` per TD-008) | MISSING | `git tag` is empty |
| Freeze on `main` | MISSING | `main` is still at PR #11 (24 Oct); the frozen contract is only on `develop` |

## 2. Camera to speech (on the phone or on fakes): NOT DEMONSTRATED

| Link in the chain | On develop? | Where |
| --- | --- | --- |
| Frame source | Partly | `camera/FrameSource.kt`, `FakeFrameSource`, `CameraXFrameSource`. **No `VideoFileFrameSource`** (WS04-01) |
| Detector (fake) | Yes | `FakeDetector` + 6 labeled sequences (AVN-4, PR #33) |
| Preprocessing | Yes | `FramePreprocessor` (AVN-6, PR #42) |
| Tracker, side, band, events | Yes | `IouTracker`, `SideEstimator`, `DistanceBander`, `TrackEventBuilder` (AVN-40, -43, -45) |
| Engine parts | Yes | `EventIntake`, `ScenarioRunner` (AVN-51), `RiskScorer` (AVN-53), `CooldownGate`/`DedupKey` (AVN-50, -54), `InterruptRules` (AVN-55), `SpeechQueue` (AVN-56) |
| **Assembled engine** | **No** | Nothing wires intake -> risk -> cooldown -> queue -> SpeechOutput (WS16-03). `feature/engine-marwan` has a PriorityEngine but is based on old `main`, has no task ID and no PR |
| Speaker (fake) | Yes | `FakeSpeaker` (engine tests) |
| **Rung 1 test** | **No** | `Rung1CameraToSpeechTest` (WS25-02) not started |

## 3. Process items

| Item | Status |
| --- | --- |
| Branch protection on `main` and `develop` (WS01-01) | UNCONFIRMED: check GitHub Settings -> Branches, or run `scripts/setup_branch_protection.sh` |
| `docs/branching.md`, CODEOWNERS, PR template | PASS (on develop) |
| CI on every PR (WS01-02) | PASS for contracts, gitleaks, backend, Android build; APK not uploaded as an artifact yet |
| Integration ladder doc (WS25-01) | MISSING: `docs/integration-ladder.md` |
| Decision records due at G1 | TD-001, TD-004, TD-008, TD-009 present. **Missing: TD-002 (wake word), TD-005 (backend auth), TD-006 (text destinations)** |

## Close-out (in order)

1. WS04-01: `VideoFileFrameSource` (Loay).
2. WS16-03: assemble engine v1 from the merged parts, or rebase `feature/engine-marwan` onto `develop` (Marwan).
3. WS25-02: Rung 1 chain and `Rung1CameraToSpeechTest`: video -> FakeDetector -> tracker -> engine -> FakeSpeaker (Loay).
4. TD-002 (Boda), TD-005 (Mahmoud), TD-006 (Mostafa O.) and `docs/integration-ladder.md` (Loay).
5. Confirm branch protection; merge AVN-266 so CI enforces the freeze.
6. Re-run this check. If GO: tag `contract-v1.1.0`, merge `develop` -> `main`, tag `g1`.

## How to re-run

```
python scripts/validate_contracts.py
python scripts/check_contract_drift.py            # after AVN-266 is merged
cd backend && ruff check . && pytest -q
cd android && ./gradlew test                      # plus the Rung 1 test once it exists
```
