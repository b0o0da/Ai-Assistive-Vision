# AVN Android app — rules for Claude Code

## What this app is
An Android voice assistant for blind and low-vision users (research prototype, assistive aid, not a medical device, no traffic-safety guarantee). The user never looks at the screen. Camera hazard awareness, phone-screen reading, voice navigation and SOS, all hands-free through an open-ear headset and a chest-mounted phone.

The one idea: **the priority engine is the product.** Every producer (camera, voice, screen, navigation, notifications, AI answers) submits events or speech candidates to the engine. Only the engine may cause speech. Nothing calls TTS directly.

## Source of truth
- Architecture and workstreams: `docs/architecture.md` (and the blueprint it came from).
- API and event contract: `docs/api-contract.md` and `contracts/` (JSON schemas + fixtures). Frozen after Gate 1. Never change a field name, type or enum value without being told that a Technical Decision entry exists; if a change seems needed, stop and say so.
- Engine numbers: `engine/src/main/resources/engine_config.json`. Never hard-code thresholds in code.

## Stack (do not swap without asking)
- Kotlin, Jetpack Compose (minimal UI), Coroutines + Flow, Hilt for DI.
- Gradle Kotlin DSL with a version catalog (`gradle/libs.versions.toml`). Use current stable versions; do not add a dependency that is not in the catalog without asking.
- minSdk 31, targetSdk and compileSdk = latest stable. Foreground service types must be declared (camera, microphone, location, mediaProjection).
- CameraX (ImageAnalysis), LiteRT (TFLite) for the detector with GPU → NNAPI/NPU → CPU delegate fallback, ML Kit Text Recognition (Latin) and Barcode, Android SpeechRecognizer, Android TextToSpeech, Fused Location Provider, Room, OkHttp + kotlinx.serialization.
- Wake word library is undecided (TD-002: openWakeWord vs Porcupine). Build behind interfaces; never commit to one. Detector licence is resolved (TD-001: YOLO AGPL-3.0 accepted; see `NOTICE.md` and `docs/decisions/TD-001-yolov8n-reference-model.md`) — a field-test APK must bundle that notice before distribution.

## Module layout
- `:contracts` — pure Kotlin/JVM. Data classes for Event, ApiEnvelope, SpeechRequest, SpeechCandidate, ActionPlan, ScreenSnapshot, plus enums. kotlinx.serialization. No Android imports.
- `:engine` — pure Kotlin/JVM. Priority + context engine. Depends only on `:contracts`. Injected clock. No Android imports, no I/O except loading config.
- `:app` — everything Android, organised by feature package:
  `core/` (service, state, permissions, consent, logging, connectivity, di),
  `data/` (api client, room, settings),
  `camera/`, `vision/` (detector, tracker, ocr, faces, currency),
  `voice/` (wakeword, stt, tts, intent, router),
  `screen/` (accessibility, capture, ladder, notifications),
  `navigation/`, `sos/`, `perf/`, `ui/`, `debug/`.

## Architecture rules
1. **Interface first.** Every feature has a Kotlin interface, a Fake implementation and a test before the real implementation. Real and fake are swapped by DI, never by `if` checks in callers.
2. **One speaker.** Only the engine calls `SpeechOutput`. Producers call `EngineInput.submit(Event)` or `submit(SpeechCandidate)`.
3. **Frames** flow as `Flow<Frame>` with drop-oldest. A slow consumer never back-pressures the camera. Frames are never written to disk.
4. **Every network call** goes through `ApiClient`, returns a typed result built from `ApiEnvelope`, has a timeout (server timeout + 1 s), retries at most once, and yields a spoken-safe fallback message. Every cloud call first checks `ConsentManager.isGranted(scope)`.
5. **Fail soft.** Catch exceptions at module boundaries, log them, set the module's health in `AppState`, and let the engine speak a short rate-limited notice. A module failure must never kill `AssistantService`.
6. **No work on the main thread.** Models lazy-load and warm up off the main thread.
7. **Logging:** JSON lines with a trace ID per request or event chain. Never log frames, screenshots, screen text, notification text, transcripts of private content or embeddings.

## Safety and privacy rules (non-negotiable)
- Hazard distance is `near | medium | far` only. Never meters, speed or time-to-collision. Navigation may use meters from route data; hazards never do.
- VLM/LLM output is informational, uses "I may be wrong" phrasing when uncertain, and never produces a Critical or Warning event.
- Never read or send content from password fields, `FLAG_SECURE` windows or deny-listed packages (banking, wallets).
- Announce every screen action before doing it. Never tap send, delete, buy, pay, post or confirm without explicit spoken confirmation; in the MVP these are not performed at all.
- No write path for messages exists in code (notifications are read-only).
- Faces: opt-in only, embeddings only, never images; strangers are "someone you don't know".
- SOS: the emergency-number path is disabled in all builds; SMS goes only to contacts from config/settings (team phones during development).
- Secrets only in `local.properties` / env, never in Git. The app holds only the backend URL and per-install API key, never provider keys.

## Testing rules
- JVM unit tests for `:contracts`, `:engine`, intent rules, tracker, route tracking, SOS state machine, ladder logic. These must run with `./gradlew test` on a laptop with no phone.
- Contract tests: every fixture in `contracts/fixtures/` must deserialize into the Kotlin models.
- Engine: scenario JSON golden tests with an injected clock.
- Instrumented tests only for permission flow, service restart and Room.
- Before saying a task is done: run `./gradlew assembleDebug test lint` and report the real result. If something fails, say so. Never claim success you did not observe.

## Git
- Work on a `feature/<area>-<short-name>` branch, never on `develop` or `main`.
- Commit in small steps. Commit messages start with the Notion task ID if one is given (`AVN-123: ...`).
- Do not push or open PRs unless asked.

## How to work
- Read the relevant section of `docs/architecture.md` and `docs/api-contract.md` before coding a feature.
- If the docs and this file disagree, or a decision is missing, stop and ask; do not invent a contract field.
- At the end of each phase, summarise: files created, tests added and their result, what is still faked, and open questions.
