# AVN Android — Claude Code prompt pack

How to use this file:

1. **Part A** goes into the repo as `android/CLAUDE.md`. Claude Code reads it automatically in every session, so every teammate's session follows the same rules.
2. **Part B** is the kickoff prompt. Paste it once (Mostafa S., week 1). It makes Claude Code read the docs and propose a plan without writing code.
3. **Part C** has one prompt per phase. Paste them one at a time, in order, after the previous phase is reviewed and merged. Each phase names its owner, so each person runs their own phase on their own branch.

Do not paste everything at once and ask for "the whole app". A single giant run produces a large, untested codebase that nobody on the team owns. The phases below follow the blueprint's rule: interface and fake first, real implementation second, one seam swapped at a time.

---

## Part A — `android/CLAUDE.md`

```markdown
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
- Wake word library is undecided (TD-002: openWakeWord vs Porcupine). Build behind interfaces; never commit to one. Detector licence is resolved (TD-001: YOLO AGPL-3.0 accepted; see `docs/decisions/TD-001-yolov8n-reference-model.md` and `NOTICE.md`).

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
```

---

## Part B — Kickoff prompt (paste once, week 1)

```text
You are building the Android app for the AVN project (AI Assistive Vision & Navigation): an Android voice assistant for blind and low-vision users. Read android/CLAUDE.md first; its rules apply to everything you do.

Context you must read before planning:
- docs/architecture.md (system architecture, workstreams WS-01..WS-30, Android architecture section, priority engine deep dive)
- docs/api-contract.md and contracts/ if they exist
If any of these are missing, tell me which, and plan Phase 1 to draft them from the facts in this prompt.

Do NOT write code yet. Enter plan mode and produce:
1. The Gradle module layout and package tree you will create (follow CLAUDE.md).
2. The list of Kotlin interfaces you will define, each with its fake, grouped by feature.
3. The manifest: permissions, foreground service types, services (AssistantService, AccessibilityService, NotificationListenerService), and the special-access steps a sideloaded build needs.
4. A phase-by-phase plan matching the phases I will send you (1 skeleton, 2 contracts + API client, 3 engine, 4 voice + speech, 5 camera + vision, 6 cloud features + consent, 7 screen + notifications, 8 navigation + SOS, 9 performance + hardening + Should-tier stubs), with the tests each phase adds.
5. Every place where the docs are ambiguous or a Technical Decision (TD-001 detector licence, TD-002 wake word library, TD-004 screenshot method) blocks you, and the interface you will use so it doesn't block.
6. The exact versions you intend to put in the version catalog, so I can check them.

Key facts (from the frozen blueprint) in case the docs are incomplete:

EVENT (exact fields): id, source, type, label, side, distance_band, confidence, risk, timestamp
- source: camera | voice | screen | navigation | notification | system
- type: vehicle_approaching, obstacle_ahead, person_close, person_known, person_unknown, scene_answer, text_result, screen_summary, notification, nav_instruction, system_notice, sos_countdown
- side: left | center | right ; distance_band: near | medium | far (never meters)

API ENVELOPE: { ok: bool, fallback: bool, message: string, data: object|null, request_id: string }
Header X-API-Key on every call except /health. Header X-Mock-Scenario: ok|timeout|error|empty for failure injection against the mock backend.

ENDPOINTS (backend = FastAPI; the app never calls a paid API directly):
- GET /health -> {status, version, mock_mode}
- POST /describe {image(base64 JPEG <=768px), context?, language} -> {description}
- POST /ask {image, question, context?} -> {answer}
- POST /screen/summarize {package, screen_type, text, image?, question} -> {summary}
- POST /intent {text, context} -> {steps[], requires_confirmation, clarification?}
- POST /faces/enroll {display_name, embedding, model_version, consent:true} -> {person_id}   (Should tier)
- POST /faces/match {embedding} -> {match: name|null, similarity}                          (Should tier)
- GET /route {origin, destination, mode=walking} -> {steps[], polyline, distance, duration}
- POST /sos {lat, lon, accuracy, ts, contact_ids} -> {accepted}                              (Nice tier, never blocks local SMS)

SHARED TYPES: SpeechRequest(text, level, source, interruptible, event_id); ActionPlan(steps[], requires_confirmation, spoken_ack) with step enum launch_app, wait, read_screen, summarize, scroll, back, tap_label, navigate, sos, describe, ask; ScreenSnapshot(package, screen_type, nodes[], text, clickable_labels[], flags); screen_type: chat_list | chat_thread | feed_post | other | blocked.

Stop after the plan and wait for my approval.
```

---

## Part C — Phase prompts

Paste one phase at a time. Each starts with "Read android/CLAUDE.md" so it also works in a fresh session.

### Phase 1 — Skeleton and app core (WS-01, WS-03 · owner Mostafa S.)

```text
Read android/CLAUDE.md. Branch: feature/android-skeleton.

Build the app skeleton:
1. Gradle: settings with :app, :contracts, :engine; version catalog; Kotlin JVM toolchain; ktlint or detekt; a `./gradlew check` that runs unit tests + lint for all modules.
2. :app with Hilt, a single MainActivity (Compose) and these screens: Onboarding (permissions + consent, every step also spoken via the engine once it exists; for now a placeholder SpeechOutput), Settings (backend URL, API key, emergency contacts, speech rate), Alert Log (empty list bound to a repository), Debug (buttons: fake wake word, inject transcript text, inject event JSON, toggle offline, choose X-Mock-Scenario).
3. AssistantService: foreground service with types camera|microphone|location declared, persistent notification, START_STICKY, owns a ModuleRegistry. Survives screen-off. Partial wake lock only while listening (stub).
4. core/: AppState (StateFlow: listening, cameraOn, offline, battery, headsetConnected, navigationActive, sosActive, moduleHealth map), PermissionManager (runtime: camera, mic, fine location, SEND_SMS, POST_NOTIFICATIONS; special: accessibility, notification listener, battery optimisation exemption) with a spoken explanation string for every denial, ConsentManager (scopes: cloud_frames, cloud_screen, faces; stored with timestamp), ConnectivityMonitor, JsonLinesLogger with trace IDs and a content-free policy, BatteryMonitor, HeadsetMonitor.
5. data/: Room database with tables settings, alert_log, known_people_cache (no image columns), DAOs.
6. Define ALL feature interfaces now (empty Fake implementations that compile and log): FrameSource, Detector, Tracker, OcrEngine, SpeechOutput, WakeWord, EmergencyKeywordDetector, SttEngine, IntentRouter, ActionRouter, ScreenReader, ScreenActions, ScreenCapture, ScreenAi, NotificationSource, LocationSource, Navigation, Sos, EngineInput. Bind fakes in a Hilt module `FakeModulesModule` and leave a `RealModulesModule` switched by a build flag.
7. Manifest with all permissions and service declarations (AccessibilityService and NotificationListener declared but their classes are stubs for now). Add docs/permissions-guide.md describing the manual steps on a sideloaded APK including "restricted settings".
8. GitHub Actions workflow android.yml: assembleDebug + check on PRs touching android/.

Tests: Room DAO test, AppState reducer tests, a Hilt test that the fake graph builds, ConsentManager test.
Done when: fresh clone builds, app installs, service starts and survives screen-off for 5 minutes on a phone or emulator, check is green. Report results and stop.
```

### Phase 2 — Contracts and API client (WS-02, WS-22 client side · owner Mostafa S.)

```text
Read android/CLAUDE.md. Branch: feature/android-contracts.

1. In :contracts, implement Kotlin models exactly matching docs/api-contract.md and contracts/*.json: Event and its enums, ApiEnvelope<T>, request/response bodies for all 9 endpoints, SpeechRequest, SpeechCandidate, ActionPlan + step enum, ScreenSnapshot + NodeInfo, Transcript, OcrResult, Route, NavState, SosRequest. kotlinx.serialization, unknown keys rejected in tests.
2. Contract test: load every file in contracts/fixtures/ and deserialize it into the right model; round-trip serialize and compare. Fail the build if a fixture has no matching model.
3. In :app data/api: ApiClient on OkHttp with X-API-Key, X-Request-Id, optional X-Mock-Scenario from Debug settings, per-endpoint timeouts (server timeout + 1 s), one retry max, image helper that downscales to 512-768 px JPEG. Every function returns ApiResult (Success(data) | Fallback(message) | Offline | Error(spokenMessage)). Consent check before /describe, /ask, /screen/summarize, /faces/*.
4. Typed clients: HealthClient, DescribeClient, AskClient, ScreenSummaryClient, IntentApiClient, RouteClient, FaceClient, SosApiClient.
5. ConnectivityMonitor uses network state + /health polling to set AppState.offline.

Tests: MockWebServer tests for each client covering ok, fallback, timeout, error, empty, unauthorized, and consent-denied (no request sent).
Done when all tests pass and the Debug screen can call /health and /describe against the mock backend with each X-Mock-Scenario. Report and stop.
```

### Phase 3 — Priority and context engine (WS-16 · owner Marwan)

```text
Read android/CLAUDE.md. Branch: feature/engine-core.

Implement :engine as pure Kotlin with an injected Clock. All numbers live in engine_config.json (hot-reloadable via a reload() call).

Pipeline per event:
1. Gate 1: confidence >= class minimum, else drop (low_confidence).
2. risk = base(type,label) x band_weight x side_weight x confidence x approach_bonus, capped at 100.
   Base: vehicle_approaching 95, obstacle_ahead 60, person_close 55, static vehicle 50, person_known/unknown 30, answers/text 20.
   Band: near 1.0, medium 0.7, far 0.3. Side: center 1.0, left/right 0.85 (vehicle_approaching ignores side for severity, keeps it in the message). Approach bonus 1.1 when growth persisted over the minimum window.
3. Level: Critical if risk >= 80 AND confidence >= 0.6 AND persistence >= min frames (SOS countdown always Critical); Warning 45-79; Info below 45 or any non-hazard.
4. Gate 2 threshold from context: moving -> Warning+; stationary and idle -> Info allowed; crowded (many distinct events in window) -> raise Warning floor; navigation instructions exempt; user-requested answers bypass threshold.
5. Gate 3: per-key cooldown (key = type+label+side+band; Critical 3 s, Warning 8 s, Info 30 s); per-track rule (announce once, again only on band escalation, side change, or return after reset time); duplicate suppression (same key speaking or queued -> drop; newer replaces queued).
6. Merge window: combine up to two compatible items, highest risk first ("Person on your left, chair ahead").
7. Stale expiry: Warning ~3 s, Info ~10 s, nav instruction when next is due.
8. Decision: SPEAK_NOW | QUEUE | HOLD | IGNORE. One queue, depth 3, order level > risk > age, overflow drops lowest and logs queue_overflow.

Interruption rules: Critical flushes speaker and speaks; interrupted user-requested or navigation speech re-queues from the current sentence, Info/scene detail dropped. Warning waits for next gap (sentence boundary for long text). Two Criticals: highest risk first. User request = high-priority Info, does not interrupt a speaking Warning. Wake word while speaking: pause/stop Info; Critical finishes first. Critical while listening: abort listening, speak, re-prompt. SOS countdown active: only countdown and cancel speech allowed. Degraded module/offline: one short notice, rate-limited.

Interfaces: EngineInput.submit(Event), submit(SpeechCandidate), updateContext(listening, speaking, moving, navigationActive, offline, sosActive); SpeechOutput.speak(SpeechRequest)/stop(id) with UtteranceStarted/Finished/Interrupted callbacks fed back into the engine.

Decision log record for EVERY event: id, ts, source, type, label, side, band, confidence, risk, level, threshold, context snapshot, decision (spoken|queued|held|suppressed|dropped|interrupted|merged|expired), reason (low_confidence|below_threshold|cooldown|duplicate|merged|expired|preempted|queue_overflow), utterance id, event-to-speech latency. Expose it as a Flow for the Alert Log screen.

Tests: scenario JSON files (timeline of events + context changes + expected decisions) under engine/src/test/resources/scenarios/, at least one per rule above, run against FakeSpeaker and a fake clock. Property checks on every scenario: never two simultaneous utterances; a Critical is never delayed by a lower level; no key repeats inside its cooldown unless an escalation rule fires; queue never exceeds 3.
Then wire the engine into :app (EngineInput singleton in AssistantService, Alert Log screen shows spoken and suppressed decisions with reasons, filter by decision).
Done when ./gradlew :engine:test is green and injecting events from the Debug screen shows correct decisions in the Alert Log. Report and stop.
```

### Phase 4 — Speech output and voice flow (WS-11..14 · owner Boda)

```text
Read android/CLAUDE.md. Branch: feature/voice-core.

1. SpeechOutput: AndroidTtsSpeaker (offline TextToSpeech, flush for Critical, queue for others, chunk long text by sentence, utterance callbacks to engine, speech rate setting), AudioRouter (headset first; on disconnect fall back to phone speaker + vibration and submit a system_notice), EarconPlayer (wake earcon), FakeSpeaker kept for tests.
2. WakeWord interface with FakeWakeWord (Debug button) and two adapter classes OpenWakeWordDetector and PorcupineWakeWordDetector behind a config flag (TD-002 undecided: implement whichever library builds cleanly, leave the other as a documented TODO stub). EmergencyKeywordDetector interface feeding SOS directly without STT/LLM. AudioCaptureService part of AssistantService, audio only in memory, audio focus ducks TTS while listening.
3. SttEngine: AndroidSttEngine (SpeechRecognizer, prefer on-device, English, endpointing, timeout, partial results, ask once to repeat on failure) and FakeSttEngine (Debug text box / fixture file).
4. Intent: RuleIntentParser covering: what's around me; read this; ask about the scene (any question with "what/is/how many" while camera mode); open <app>; read the screen; scroll up/down; go back; summarize this/these; read my new messages; take me to <destination>; stop/cancel navigation; SOS / help / emergency phrases; cancel; stop; repeat; louder/slower/faster; enroll <name> (Should). Unmatched + online -> IntentApiClient (/intent); unmatched + offline -> "I can't do that offline". Validate every plan against the step whitelist and drop unknown steps; steps with risky labels require ConfirmationManager. ConversationState keeps last screen context for follow-ups like "scroll down".
5. ActionRouter executes ActionPlan steps by calling the module interfaces (fakes for modules not built yet) and submits results to the engine as user-requested SpeechCandidates.

Tests: table-driven command corpus with near-misses (put the corpus in tests/fixtures/commands.json); emergency phrases ALWAYS route to SOS; plan validator; confirmation flow; offline message; TTS interruption ordering using FakeSpeaker.
Done when, on the phone, fake wake word -> spoken command -> rule intent -> spoken answer works end to end through the engine, and all tests pass. Report and stop.
```

### Phase 5 — Camera, detection, tracking, OCR (WS-04..07 · owners Loay, Mostafa O., Marwan)

```text
Read android/CLAUDE.md. Branch: feature/cv-pipeline.

1. FrameSource: CameraFrameSource (CameraX ImageAnalysis bound to AssistantService lifecycle, not an activity; downscale + rotate; drop-oldest; captureStill(); compress to 512-768 px JPEG for cloud) and VideoFileFrameSource (replays an mp4 from app storage at the recorded rate, same interface). FpsGovernor with modes active (15-25 FPS), idle (low rate when stationary), thermal (reduced) driven by MotionState and thermal status.
2. Detector: LiteRtDetector (loads model + label map named in models/manifest.json; letterbox, inference, NMS, class filter to the project label set, per-class confidence thresholds; DelegateSelector GPU -> NPU/NNAPI -> CPU with logged choice; warm-up at start; if inference exceeds the frame budget drop frames, never queue). FakeDetector replays detection JSON. Do not commit model weights; add scripts/fetch_models.sh reading the manifest. TD-001 is resolved (AGPL-3.0 accepted, see `docs/decisions/TD-001-yolov8n-reference-model.md`): code still must not depend on a specific model family beyond input size and output format, documented in the manifest.
3. Tracker (ByteTrack-style IoU + score association, stable IDs, lostFrames), SideEstimator (thirds of frame width), DistanceBander (box height/area relative to frame, per-class thresholds from calibration.json, hysteresis so bands don't flicker), ApproachDetector (box area growth over a sliding window for vehicle classes, minimum track age, higher threshold while walking, emits vehicle_approaching with side; no speed), MotionEstimator (IMU + global box flow -> stationary|moving), EventBuilder -> Event per contract -> EngineInput.submit. Tracker must run under 5 ms per frame; add a timing test.
4. OCR: MlKitOcrEngine on captureStill(), TextOrderer (top-to-bottom, left-to-right), chunked for TTS; low confidence or non-Latin -> OcrFallback via /ask with an OCR prompt (consent-gated); spoken failure guidance ("move the phone closer", "I can't read this"). Medicine text read verbatim only. QR via ML Kit barcode (Should, behind flag).
5. Wire "what's around me" / "read this" intents to captureStill() + /describe or OCR.

Tests (JVM): synthetic box sequences for approaching, receding, crossing, jitter, and walking past parked cars (must not fire); band hysteresis; side thirds; EventBuilder output validates against the contract; OCR ordering on fixture blocks.
Done when VideoFileFrameSource + FakeDetector produce correct spoken warnings through the engine on an emulator, and on the phone CameraFrameSource + LiteRtDetector (COCO baseline) runs at the measured FPS logged by PerfLogger. Report FPS, ms/frame and chosen delegate, then stop.
```

### Phase 6 — Cloud features and consent (WS-15 client side, WS-28 · owner Mostafa S. with Mahmoud)

```text
Read android/CLAUDE.md. Branch: feature/android-cloud.

1. Consent: first-run notice plus a spoken notice before the first cloud frame and the first cloud screen content; per-scope grant/revoke in Settings; every cloud client refuses without consent (unit test per client).
2. DescribeClient/AskClient flows: "what's around me" and look-and-ask send the latest still (512-768 px) with a small context object (mode, navigation active, language, recent event summary). Answers enter the engine as scene_answer user-requested Info; they can never produce Warning or Critical (add a test that asserts this in the engine adapter).
3. Fallbacks spoken exactly per contract: timeout -> "I can't describe the scene right now"; offline -> "I can't do that offline".
4. Health-driven offline mode: when /health fails, cloud intents are refused with one sentence while detection, OCR, SOS and loaded navigation continue.
Tests: consent-denied sends nothing; each X-Mock-Scenario produces the right spoken text; VLM answers never escalate. Report and stop.
```

### Phase 7 — Screen reading, screen AI, notifications (WS-17..19 · owners Loay, Marwan)

```text
Read android/CLAUDE.md. Branch: feature/screen-core.

1. AssistantAccessibilityService: launch app by intent (announce first), SettleWaiter (debounced content-change events with timeout), NodeExtractor over rootInActiveWindow with depth and node-count caps (text, contentDescription, viewId, class, clickable, scrollable, bounds, isPassword), ScreenClassifier (chat_list, chat_thread, feed_post, other, blocked). Blocked = password field present OR package on deny-list OR empty/secure window: nothing is read or sent. ActionExecutor: global back, scroll forward/backward on the scroll container, click node by exact label; every action announced first; RiskyLabelGuard blocks send/delete/buy/pay/post/confirm (and Arabic equivalents) entirely in the MVP. After scroll: re-wait, re-extract, de-duplicate overlapping text. AppRegistry with profiles for the 2-3 tested apps (WhatsApp, Facebook, plus one TBD; Chrome if TD-007 picks it).
2. FakeScreenReader loading ScreenSnapshot JSON fixtures from tests/fixtures/screens/; a small separate debug module or app :mockscreens with a WhatsApp-style chat list and thread for testing on-device.
3. ScreenCapture behind an interface with two implementations (MediaProjection with per-session consent; AccessibilityService.takeScreenshot) — TD-004 decides which is default; downscale to 512-768 px; skip if secure/blank; never stored.
4. LadderOrchestrator: rung 1 tree text -> rung 2 screenshot + ML Kit OCR when nodes empty/unlabeled -> rung 3 /screen/summarize with tree text + optional screenshot when layout/images matter or the user asks to summarize. Result ScreenSummary(text, source tree|ocr|vlm). On VLM timeout speak tree text if any; offline says summaries are unavailable.
5. NotificationListenerService: allow-list (WhatsApp default), extract sender + text, sender-only vs full-text setting, in-memory bounded store, group by sender, flood rule (speak a count instead of reading all), Info events held until asked or idle and stationary. No reply/write code anywhere.

Tests: golden ScreenSnapshot fixtures through classifier and ladder; blocked-screen cases; risky-label guard; dedupe after scroll; consent-denied capture; notification flood. Done when "open WhatsApp and read my new messages", "scroll down", "summarize these" work on the mock screens and fixtures with tests green. Report and stop.
```

### Phase 8 — Navigation and SOS (WS-20, WS-21 · owners Mostafa O., Boda)

```text
Read android/CLAUDE.md. Branch: feature/android-nav-sos.

Navigation:
1. LocationSource: FusedLocationSource (rate reduced when stationary) and FakeLocationSource replaying a GPX file from tests/fixtures/routes/.
2. Navigation.start(destination): RouteClient GET /route (destination may be text like "nearest pharmacy"; the backend resolves it), keep steps + polyline, RouteTracker for step progression and off-route detection (one reroute request), TurnAnnouncer at tuned distances as nav_instruction time-sensitive Info, HapticCues (one pattern warning, one critical; left/right patterns behind a Should flag), arrival announcement, offline continuation with the loaded route, cancel().
Tests: GPX replay for step progression, off-route + single reroute, arrival, offline continuation, Critical pre-empting an instruction and the instruction being re-issued.

SOS:
3. SosController state machine idle -> countdown -> sending -> sent | failed | cancelled. Triggered by EmergencyKeywordDetector, SOS intent, or a helper button on screen. Countdown is a Critical utterance "Sending SOS in 5 seconds, say cancel to stop"; only cancel is listened for. On expiry: LocationFixer (fresh fix with timeout, else last known + its age), SmsSender with a maps link to contacts from settings, optional SosApiClient POST /sos when online (never blocks SMS), spoken result, everything logged. Emergency services numbers are hard-blocked in code.
Tests: full state machine with fake clock, fake location, fake SMS sender, fake speaker; offline path; cancel at every state; empty contact list; SMS permission denied.
Done when both pass tests and, on the phone, a GPX route is announced correctly and a voice SOS sends an SMS to a team number. Report and stop.
```

### Phase 9 — Performance, hardening and Should-tier stubs (WS-27, WS-08, WS-09 · owners Mostafa O., Marwan, Mahmoud)

```text
Read android/CLAUDE.md. Branch: feature/android-perf.

1. PerfLogger: per-module timers, FPS, ms/frame, battery %, thermal status every minute, written to CSV (no content). Debug overlay toggle. A 30-minute session script/checklist in docs/perf-test.md.
2. Thermal and battery policies: FpsGovernor thermal mode, pause camera on critical thermal or low battery with a spoken notice, release camera on headset-less idle timeout.
3. Robustness: service restart restores state; module health shown in Debug; every module exception caught at its boundary (add a test that a throwing fake module does not stop the service).
4. Should tier behind feature flags, default OFF, interface + fake + real skeleton only unless I say otherwise:
   - Faces: FaceDetector (ML Kit) -> FaceAligner -> EmbeddingModel (model from manifest, version stored with each vector) -> FaceMatcher (cosine, conservative threshold, multi-frame agreement, default unknown) -> person_known / person_unknown events; EnrollmentFlow requires spoken consent, sends embeddings only via /faces/enroll, voice delete command. Never store images.
   - Currency: CurrencyClassifier + FrameVoter with an "I'm not sure, hold it flat" path.
5. Export of the alert log + perf CSV for field-test analysis (Share intent, content-free).
Done when a 30-minute run with camera, wake word and engine on completes without crash or leak and the CSV shows stable temperature, or the report lists mitigations. Report and stop.
```

---

## Tips for the team

- Run each phase in **plan mode first** (Shift+Tab in Claude Code), check the plan, then let it write code.
- Keep each session to one phase. Start a fresh session for the next phase; `CLAUDE.md` carries the rules over.
- Review every PR like a teammate wrote it. Claude Code is fast, but the owner of the workstream is still responsible for the code.
- Real-device work (camera speed, wake word, accessibility, screenshots, thermal) cannot be verified on an emulator. Claude Code will say "untested on device" for those; the owner must test on the AVN test phone.
- After the contract freeze, if Claude Code says a contract field is missing, log a Technical Decision in Notion before letting it change anything in `contracts/`.
