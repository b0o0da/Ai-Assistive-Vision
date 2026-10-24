# Marwan, Week 1: required, done, still open

Source: team plan (Marwan: Priority Engine + Navigation + Models) and WS-16 / WS-28 / WS-07 / WS-02 text.

| # | Required | Status | Where |
|---|---|---|---|
| 1 | Event JSON (id, source, type, label, side, distance_band, confidence, risk, timestamp) | Done, **draft** | `docs/event-schema.md`, `contracts/event.schema.json` |
| 2 | Risk levels Critical / Warning / Info with a score and thresholds | Done, **draft** | `docs/risk-levels.md`, `RiskModel.kt` |
| 3 | Hand off the schema to Moko, Boda, Sayed | **Not done: you must send it** | message below |
| 4 | Contract fixtures that both Kotlin and Python check | Done | `contracts/fixtures/`, `ContractTest`, `scripts/validate_contracts.py` |
| 5 | WS-16 interfaces: `EngineInput.submit(Event / SpeechCandidate)`, `updateContext(6 flags)`, `SpeechOutput.speak(SpeechRequest)` + utterance callbacks | Done | `engine/Contracts.kt` |
| 6 | Engine pure Kotlin, unit-testable on a laptop | Done: 93 tests | `src/test` |
| 7 | Shared scenario replay format | Done: 4 scenarios | `engine/scenarios/`, `ScenarioReplayer` |
| 8 | Engine v1: event -> score -> threshold -> queue, cooldowns, unit tests | Code done (this is a Months 1-2 item, built early) | `PriorityEngine.kt` |
| 9 | `sample_events.json` from Moko | **Waiting**: I used 6 events I wrote; they pass the schema | `examples/sample_events.json` |
| 10 | Contract frozen in `docs/api-contract.md` | Week 2, owned by Mostafa S. (WS-02). `event-schema.md` is your input | - |
| 11 | WS-28 consent gate, privacy docs; WS-07 OCR / QR / color | Done earlier | `privacy/`, `ocr/` |

## Backlog cards (SpeechRequest / scenarios, consent, OCR stand-in, voice contracts)
| Card | Item | Status | Where |
|---|---|---|---|
| WS-16 | `SpeechRequest(text, level, source, interruptible, event_id)` | Done (+ `utteranceId`) | `model/Model.kt` |
| WS-16 | `engine/scenarios/SCHEMA.md`: timeline with expected decisions | Done | `engine/scenarios/SCHEMA.md` |
| WS-16 | `scenarios/sample.json` | Done | `engine/scenarios/sample.json` |
| WS-16 | Engine pure Kotlin | Done | no `android.*` import in `src/main` |
| WS-28 | `Consent.isGranted(scope)` | Done | `privacy/Consent.kt` |
| WS-28 | Gate camera, screen, mic and every cloud client | Done | `SensorGate.kt`, `CloudGate.kt` |
| WS-28 | Revocation | Done | `ConsentManager.revoke`, sensors stop, listeners fire |
| WS-28 | Test: gated calls refuse without consent | Done | `PrivacyTest` (19 tests) |
| WS-07 | `OcrEngine.read(frame): OcrResult{...}` | Done | `ocr/OcrModels.kt` |
| WS-07 | `FakeOcrEngine` for fixture images | Done | `ocr/FakeOcrEngine.kt`, `fixtures/ocr/manifest.json` |
| WS-07 | `TextBlock` | Done | `ocr/OcrModels.kt` |
| WS-07 | Test the fake | Done | `FakeOcrEngineTest` (13 tests) |
| Voice | `Transcript` and `ActionPlan` schemas | Done | `contracts/*.schema.json` |
| Voice | Whitelist of 11 actions | Done | schema + `ActionPlanJson.WHITELIST` |
| Voice | Kotlin data classes + 8 fixtures (SOS, requires_confirmation=true) | Done | `contracts/`, `contracts/fixtures/plans/` |
| Voice | Validate with `scripts/validate_contracts.py` | Done | the script now covers events, transcripts, plans |

The file paths in the cards (`android/engine`, `core/consent/`, `android/contracts`) do not exist in this project: everything lives in one Gradle module under `eg.assistive.core.*`. Move the packages into your real repo layout when you integrate.

## Still open for the whole task list (not Week 1)
Navigation, audio/haptic cues, currency model, face model: **not built yet.**

## Not verified
`build.gradle.kts` was never run (no Gradle access here): tests ran through `kotlinc` plus a small `kotlin.test` stand-in. Run `gradle test` once on your machine or Colab. Nothing has run on Android.

## Message to send (Arabic)
> يا شباب، دي النسخة الأولى من الـ Event JSON وأولويات التنبيه: `docs/event-schema.md` و`docs/risk-levels.md` + `contracts/event.schema.json`.
>
> **Moko:** الـ CV هيبعت إيه بالظبط من: `type` (القايمة في الملف)، `side`، `distance_band` (near/medium/far، مش متر)، `confidence`؟ هل الـ tracker بيدّي `track_id`؟ ابعتلي `sample_events.json` الحقيقي. وهل `risk` ممكن يبقى critical/warning/info من عندك ولا أسيبه للـ Engine؟
>
> **Boda:** الـ Engine هو الوحيد اللي بينادي `SpeechOutput`: `setListener` + `speak(SpeechRequest)` + `stop()`. للمقاطعة بنادي `stop()` وبعدها `speak()`، فاستخدم QUEUE_ADD. محتاج منك callbacks: `onStarted/onDone/onError/onStopped` بنفس `utteranceId`. تمام؟
>
> **Sayed:** محتاج منك مصدر الـ `updateContext`: `listening, speaking, moving, navigationActive, offline, sosActive`، وإن الـ Event يتبعت بالشكل ده في الـ API. وكمان رأيك في `timestamp` ISO UTC.
>
> لو في اعتراض على أي اسم أو قيمة قولوا قبل ما نجمّد الـ contract في الأسبوع التاني.
