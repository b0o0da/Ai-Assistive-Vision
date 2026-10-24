# AI Assistive Vision & Navigation — Architecture & Notion Blueprint (3-Month Plan)

Oct 7, 2026 · @mostafa

## Read me first: spec conflicts and assumptions

The spec is internally inconsistent in six places; this blueprint resolves each one as below, so correct me before task generation if any call is wrong.

| # | Conflict in the spec | Resolution used here |
| --- | --- | --- |
| 1 | Spec is a 6-month plan with 6 gates; you have 12 weeks | Compress to 4 gates (below). Spec scope is re-tiered in section 8; the spec's Month 5-6 work (field tests, freeze) is folded into weeks 9-12 |
| 2 | Team table (Section 7) and README/timeline (Sections 8, 13, 23) disagree on roles: e.g. Marwan is CV + Screen AI in one, engine + navigation + faces in the other; Loay is Screen Intelligence in one, integration/testing in the other | Section 7 is authoritative for each person's skill areas. Where Section 7 names no owner (priority engine, SOS, testing), I use Sections 8/13: engine to Marwan, testing/field tests to Loay |
| 3 | "Mostafa Moko" (Sections 8, 13, 23) vs "Mostafa omar" (Section 7) | Treated as the same CV lead. Written "Mostafa O." below; Mostafa Sayed is "Mostafa S." |
| 4 | Section 11 lists 9 endpoints; Section 21 checklist requires only 5 | The 5 are Must. `/route` is Must (thin proxy). `/faces/*` follow the Should-tier face feature. `/sos` is Nice: SOS itself runs on the phone |
| 5 | Navigation is owned by Mostafa O. (Section 7) and Marwan (Section 13) | Mostafa O. owns GPS/route logic; Boda owns voice-driven navigation; Marwan owns navigation-to-engine integration. See section 6 |
| 6 | Detector licence: YOLO nano is AGPL; wake word has two options | Not decided by the spec: logged as Technical Decisions TD-001 and TD-002, due before week 3 |

Assumptions:

- Week 1 is the first week of the 12; no calendar dates here. The daily schedule comes later.
- Android test phone purchase is a day-1 blocker, not a week-1 task: no on-device work starts without it.
- Four gates replace six. Gate 1 (end of week 2): contracts frozen, camera to speech works. Gate 2 (end of week 5): detect, warn, read text, wake word and STT. Gate 3 (end of week 9): complete demo on the phone. Gate 4 (end of week 12): freeze, field-test report, v1.0.
- Scope freeze is end of week 2 (spec said month 1; 3 months needs it earlier). No new features after Gate 3.
- English is the demo language; Arabic is stretch, as the spec states.

## 1. Complete project understanding

The product is an Android voice assistant for blind and low-vision users that sees through the phone camera, reads the phone screen, navigates by voice and sends an SOS, with no need to look at a screen. It is a research prototype and assistive aid: not a medical device, not a white-cane replacement, no traffic-safety guarantee.

**The one idea to remember:** the priority engine is the product. Every detection becomes an event with a risk score, only events above the current threshold are spoken, and anything the user is already hearing is not repeated. The user hears one calm voice, not a stream of labels.

**Target user.** Primary: blind and low-vision users wanting hands-free awareness of surroundings and phone content, using an open-ear headset and a chest/neck-mounted phone. Demo users are the team and consenting volunteers.

**Main user flows (the demo script, Section 19, is the acceptance test):**

1. Wake word, then "What's around me?" gives a short cloud scene description.
2. Walking toward an obstacle gives a spoken warning with near/medium/far band, left/right cue and a vibration pulse.
3. In a crowded room only the important item is spoken; the log shows what was suppressed.
4. A vehicle approaching from the side triggers a critical alert that interrupts everything, with no cloud.
5. Reading signs, menus, medicine boxes (OCR) and, if finished, an EGP banknote.
6. A known enrolled person is named; an unknown person is announced as "someone you don't know".
7. "Take me to the nearest pharmacy" gives walking turn-by-turn on a pre-tested route.
8. "Open WhatsApp and read my new messages", "scroll down", "summarize these", then summarize a Facebook post.
9. Voice SOS sends an SMS with location after a spoken cancel countdown.
10. Airplane mode: detection and warnings still work; reconnect and a cloud question answers.

**Components, as the spec defines them:**

| Area | What it is | Runs where |
| --- | --- | --- |
| Android app | Kotlin + Jetpack Compose shell; CameraX, permissions, audio, API client, SQLite cache, alert-log view | Phone |
| Camera pipeline | CameraX frame sampling, rate-scaled by motion (lower FPS when standing still) | Phone |
| CV pipeline | YOLO nano detection (LiteRT/ONNX), ByteTrack-style tracking, box-size near/far band, side, box-growth vehicle-approach, ML Kit OCR/QR, ML Kit face + MobileFaceNet embeddings, MobileNetV3 currency, HSV color | Phone (OCR fallback in cloud) |
| Voice pipeline | Wake word (openWakeWord or Porcupine) then STT (Android first, Whisper-class upgrade) then rule-based intent (LLM for free-form) then TTS (Android offline for alerts, neural cloud for long reading) | Phone, with backend for upgrades |
| Screen intelligence | AccessibilityService node tree, then screenshot + ML Kit OCR, then screenshot + cloud VLM; NotificationListener for WhatsApp/notifications, read-only | Phone plus backend prompt assembly |
| Backend | FastAPI orchestration, provider abstraction, mock mode, history, directions cache | Cloud/VPS |
| AI | Hosted multimodal VLM/LLM for scene, look-and-ask, screen summary, intent: informational only, never safety-critical | Cloud |
| Priority/risk + context engine | Event schema, risk score, thresholds, cooldowns, speech queue, interruption rules, alert log | Phone |
| Navigation | Location, Maps directions via backend proxy + cache, turn-by-turn TTS, audio/haptic cues | Phone + backend |
| SOS | Voice/emergency keyword, spoken countdown to cancel, SMS/call with location; optional contact push via `/sos` | Phone |
| Database | SQLite cache on phone; PostgreSQL + pgvector on backend (known faces, saved places, users, settings, logs) | Both |

**Local vs cloud rule.** Safety-critical or high-frequency work (obstacles, vehicles, wake word, emergency keywords, SOS) runs on the phone. Deep reasoning (scene, screen summaries, translation) goes to the cloud. With no internet, safety alerts, OCR, loaded navigation and SOS keep working; only VLM answers and screen summaries stop, and the assistant says so.

**External services:** hosted VLM/LLM (Claude or Gemini via `VLM_PROVIDER`), Maps directions API, optional cloud TTS, optional Whisper-class STT, SMS/telephony via Android itself. Every key has a hard spending limit; `MOCK_MODE=true` means zero paid calls.

**Testing:** unit tests (engine, intent parser, tracker, prompt builders), model tests on fixed labeled sets, integration tests (CV to engine, Android to backend), pytest + httpx API tests, screen tests on 2-3 apps plus a mock WhatsApp screen, failure-mode tests, volunteer field tests with written consent, and the full demo run 3 times back-to-back.

**Safety and privacy boundaries (non-negotiable):**

- Never claim meters, vehicle speed or time-to-collision; distance is near/medium/far only.
- VLM output is informational, phrased with "I may be wrong", and never drives a safety alert.
- Faces are opt-in, stored as encrypted embeddings only, never as images; strangers are never named.
- No image storage by default; a consent notice precedes any frame or screen content leaving the phone.
- Never read password or banking screens (`FLAG_SECURE`); announce every screen action first; never tap send, delete, buy or pay without explicit spoken confirmation.
- Distribution is a sideloaded APK (Accessibility policy); demos use a personal account, one user, assistive use only.

## 2. Architecture and data flows

Three pipelines (physical, digital, voice) share one context and priority engine, and nothing reaches the speaker without passing through it. Every other component is either a producer of events into the engine or a consumer of its decisions.

**Physical perception (fully on phone, no cloud):**

```
CameraX frame (sampled, FPS scaled by motion)
 -> Detector (YOLO nano, LiteRT/ONNX, 30-70 ms)
 -> Tracker (ByteTrack-style, <5 ms, stable track IDs)
 -> Near/Far (box-size heuristic -> near | medium | far)
 -> Side (box centre x -> left | center | right)
 -> Vehicle approach (box growth rate over N frames + side; no speed)
 -> Risk (label x band x side x growth x confidence -> risk score)
 -> Event (schema, Section 10 of spec: evt_id, source, type, label, side, distance_band, confidence, risk, timestamp)
 -> Priority engine (threshold, cooldown, duplicate suppression, queue)
 -> Speech decision -> TTS (+ vibration)  | or Ignore (logged)
```

**Digital perception (phone plus cloud):**

```
User command or notification
 -> App launch intent (announced first)
 -> AccessibilityService (wait for window to settle)
 -> UI node tree (text, buttons, scroll containers)   [rung 1: free, offline]
 -> empty/unlabeled? Screenshot (MediaProjection) -> ML Kit OCR   [rung 2]
 -> layout/image/video matters? Screenshot -> backend -> VLM/LLM   [rung 3]
 -> /screen/summarize (prompt assembly, bounded response)
 -> Understanding (summary, list of actionable elements)
 -> Engine (Info-level, spoken because user asked) -> TTS
 -> Optional action (scroll, back, tap labeled button; confirmation if risky)
```

**Voice:**

```
Microphone (always-on, low power)
 -> Wake word (on phone, <200 ms)   | emergency keywords bypass to SOS fast path
 -> STT (Android on-device first; Whisper-class via backend as upgrade)
 -> Intent (rules first; /intent LLM for free-form)
 -> Context (current mode, last events, location, offline state, active navigation)
 -> Action router: describe | ask | read text | screen action | navigate | SOS | settings
      -> or AI call (/describe, /ask, /screen/summarize)
 -> Engine (User-request = high-priority Info) -> TTS
```

**Where the pipelines interact:**

| Interaction point | What happens | Owner of the rule |
| --- | --- | --- |
| All pipelines to engine | Camera events, screen results, notifications, navigation prompts and AI answers all enter as events with a source field | Priority engine |
| Voice command to camera | "What's around me?" or "read this" grabs the latest frame and routes it to `/describe`, `/ask` or OCR | Action router |
| Voice command to screen | Intent chain (launch app, wait, read, summarize) drives the AccessibilityService | Action router |
| Camera hazard during speech | A Critical event interrupts any TTS, including navigation or a screen read, then the interrupted utterance resumes or is dropped by rule | Engine speech queue |
| Navigation and Warning | Turn instructions are queued as time-sensitive Info; a Warning waits for the gap between them, a Critical pre-empts | Engine, with Navigation |
| Offline | A connectivity monitor feeds the context engine; cloud intents get a spoken "I can't do that offline" instead of a timeout | Context engine |
| Idle and stationary | Motion state (IMU/tracker) lets Info events speak and lowers camera FPS | Context engine |
| SOS | Emergency keyword or button pre-empts everything; the countdown is a Critical utterance that blocks other speech except cancel | SOS flow + engine |

**Context engine vs priority engine.** The spec uses "context and priority engine" as one concept. I split it into two modules in one package (`engine/`): the context state (motion, connectivity, active mode, last spoken, navigation active, user-request pending) and the priority decision logic that reads it. Both live in `engine/` and share one owner.

**Processing boundary.** The only data leaving the phone: a compressed frame (512-768 px) for `/describe` or `/ask`; node tree text plus optional screenshot for `/screen/summarize`; free-form command text for `/intent`; a face embedding for `/faces/*`; origin/destination for `/route`; an optional contact push for `/sos`. All behind a consent notice.

## 3. Workstream map

The spec requires 30 workstreams; 27 carry Must-have work, two (WS-08, WS-09) are Should-tier and one (WS-10) is Nice. These IDs (WS-01 to WS-30) are used everywhere below and become rows in the Notion Workstreams database.

| ID | Workstream | In spec? | Tier | Notes |
| --- | --- | --- | --- | --- |
| WS-01 | Repo, tooling, CI and release engineering | Yes (Sec. 8, 9) | Must | Branching, LFS/model download script, `.env.example`, signed sideloadable APK |
| WS-02 | API contracts and event schema | Yes (Sec. 8, 10, 11) | Must | Frozen by end of week 2 in `docs/api-contract.md` |
| WS-03 | Android app core | Yes | Must | Compose shell, permissions, services, SQLite, logging, API client |
| WS-04 | Camera pipeline (CameraX) | Yes | Must | Frame sampler, lifecycle, FPS scaling |
| WS-05 | Object detection (YOLO nano) | Yes | Must | Fine-tune, LiteRT/ONNX export, on-device inference |
| WS-06 | Tracking and spatial estimation | Yes | Must | Tracker, near/medium/far, side, vehicle approach, motion state |
| WS-07 | OCR, QR and color | Yes | Must (OCR), Should (QR, color) | ML Kit; cloud fallback for hard text |
| WS-08 | Face recognition | Yes | Should | Opt-in only; embeddings, never images |
| WS-09 | Currency recognition (EGP) | Yes | Should | Needs own dataset |
| WS-10 | Relative depth (Depth Anything V2-small) | Yes, marked optional | Nice | Only if box-size heuristic fails and battery allows |
| WS-11 | Wake word and audio capture | Yes | Must | openWakeWord or Porcupine; emergency keywords |
| WS-12 | Speech-to-text | Yes | Must | Android recognizer first; Whisper-class is an upgrade |
| WS-13 | Text-to-speech and audio output | Yes | Must | Android offline TTS; neural cloud TTS for long reading |
| WS-14 | Intent and voice flow | Yes | Must | Rule-based fast path; `/intent` for free-form |
| WS-15 | VLM/LLM prompts and providers | Yes | Must | Scene, ask, screen, intent prompts; translation is a prompt variant |
| WS-16 | Priority and context engine | Yes (the core) | Must | Central product component |
| WS-17 | AccessibilityService and screen reading | Yes | Must | Node tree, scroll, back, tap labeled button |
| WS-18 | Screen capture and screen AI | Yes | Must (screenshot, OCR, VLM), Should (Reels) | MediaProjection, fallback ladder, classification |
| WS-19 | Notification reading | Yes | Must | NotificationListener, read-only, WhatsApp |
| WS-20 | Navigation (GPS, Maps, cues) | Yes | Must | Turn-by-turn, audio and haptic cues, `/route` consumer |
| WS-21 | SOS and emergency | Yes | Must | Countdown, SMS and call with location |
| WS-22 | Backend core and deployment | Yes | Must | FastAPI, providers, mock mode, Docker, VPS |
| WS-23 | Database and known-people data | Yes | Must (schema, SQLite), Should (pgvector faces) | PostgreSQL, pgvector, SQLite cache |
| WS-24 | Datasets and model evaluation | Yes (Sec. 14) | Must | Own Egyptian street video, labeling, eval sets |
| WS-25 | System integration | Implied (Sec. 17) | Must | CV to engine, Android to backend, voice to actions |
| WS-26 | Testing, QA and field tests | Yes (Sec. 17) | Must | Unit, integration, E2E, failure modes, volunteers |
| WS-27 | Performance, battery and thermal | Yes (Sec. 16) | Must | 30-minute stable session, FPS scaling |
| WS-28 | Security, privacy and ethics | Yes (Sec. 18, 23) | Must | Consent, opt-in faces, key limits, ethics note |
| WS-29 | Documentation | Yes (Sec. 21) | Must | architecture, api-contract, screen-capabilities, demo-script |
| WS-30 | Final demo and release | Yes (Sec. 19, 21) | Must | Rehearsals, backup video, v1.0 tag |

**Components in your list that the spec does not require as separate workstreams:**

- *Dedicated haptics, translation, counting, medicine-label reading, caller ID, fall detection:* folded in as features of WS-20, WS-15, WS-06/07, or Nice-tier items. They are not their own workstreams.
- *MediaProjection, AccessibilityService, GPS/Maps, SOS:* separate workstreams as listed (WS-17, WS-18, WS-20, WS-21).

**Explicitly excluded by the spec (do not plan any work on them):** glasses or custom hardware, ToF/LiDAR, metric depth, vehicle speed or time-to-collision, stair detection (Future), sending or replying to messages (Future), indoor navigation (Future), controlling banking/secure screens, free silent control of any app, naming arbitrary strangers, first-class Arabic dialect, any traffic-safety guarantee.

## 4. Deep architectural breakdown

Each workstream is described with the same 15 fields so the task generator can lift them directly. Items marked *(proposed)* are my design choices where the spec is silent; everything else comes from the spec. Conventions used in every workstream:

- **Fail soft:** every call returns a bounded result or a spoken fallback, never a crash or a silent hang (spec Sec. 11).
- **Mock first:** each producer ships a fake (mock backend, canned detections, mock screen) before its real implementation.
- **Log everything:** one JSON-lines log with a trace ID per user request or event chain; the alert log is a first-class screen.
- **Shared contracts (defined in WS-02):** `Event`, `ApiEnvelope`, `FrameSource`, `SpeechRequest`, `ActionPlan`, `ScreenSnapshot`.

### WS-01 Repo, tooling, CI and release engineering

- **Purpose / Why:** One repo (`assistive-vision-nav/`) that builds on every laptop and phone, because six people merge twice a week into `develop`.
- **Inputs:** Spec Sec. 8-9 structure; team GitHub accounts; Android test phone.
- **Processing:** Create repo skeleton and folders; branch protection (PR + cross-role review into `develop`); CI: Android build + unit tests, backend pytest, lint; model download script and Git LFS rules; `.env.example`; signed debug/release APK pipeline.
- **Outputs:** Working repo, CI badge, `develop`/`main` rules, tagged gates, signed APK.
- **Internal components:** Branch rules, CI workflows, model fetch script, PR template (links Notion task ID), issue labels.
- **APIs / interfaces:** GitHub PR template field `Notion Task: AVN-xxx`; `scripts/fetch_models.sh`.
- **Data structures:** Model manifest (name, version, hash, source URL, license).
- **Dependencies:** None (root). Phone purchase is independent.
- **Consumers:** Everyone.
- **Error cases:** Large checkpoint committed by mistake; CI red on `develop`; unsigned APK blocked by device policy.
- **Testing:** CI dry run on a trivial PR; fresh-clone build on a second laptop.
- **Performance:** CI under 10 minutes so merges are not blocked.
- **Security / Privacy:** Secrets only in `.env`; secret-scan in CI; no raw checkpoints or face data in Git.
- **Definition of Done:** Fresh clone builds Android + backend; PR without review cannot merge to `develop`; model script downloads all current models; `v1.0` tag procedure documented.

### WS-02 API contracts and event schema

- **Purpose / Why:** Freeze the interfaces by end of week 2 so six people can build against fakes (spec Sec. 8: freeze event schema and backend API in `docs/api-contract.md`).
- **Inputs:** Spec Sec. 10 event schema, Sec. 11 endpoint list.
- **Processing:** Write request/response JSON for the 9 endpoints; define the event schema and enumerations; write fixtures for every response and failure; version the contract.
- **Outputs:** `docs/api-contract.md`, `contracts/` JSON schemas and fixtures *(proposed folder)*, Kotlin and Pydantic models generated or hand-synced from them. Valid `ActionPlan` fixtures live in `contracts/fixtures/plans/valid/` and are listed in `contracts/fixtures/index.json`. Untrusted (deliberately invalid) plans live in `tests/fixtures/plans/untrusted/`, outside `contracts/fixtures/`, because `scripts/validate_contracts.py` requires every file there to pass its schema (AVN-220).
- **Internal components:** Event schema; envelope schema; enum registry (`source`, `type`, `side`, `distance_band`, `risk`); fixture library (ok, fallback, timeout, invalid).
- **APIs / interfaces:** Event: `id, source, type, label, side, distance_band, confidence, risk, timestamp` (exactly the spec). Envelope *(proposed)*: `{ok: bool, fallback: bool, message: string, data: object|null, request_id}`. `distance_band` is `near|medium|far`, never meters.
- **Data structures:** `Event`, `ApiEnvelope`, `SpeechRequest` (text, level, source, interruptible, event\_id), `ActionPlan` (list of steps: launch\_app, wait, read\_screen, summarize, scroll, back, tap\_label, navigate, sos, describe, ask), `ScreenSnapshot` (package, nodes, text, optional screenshot ref, flags).
- **Dependencies:** None (root); input from engine design (Marwan) and CV (Mostafa O.).
- **Consumers:** Every workstream.
- **Error cases:** Android and Python models drift; a field added without a version bump.
- **Testing:** Contract tests validate every fixture against the JSON schema in both Kotlin and Python.
- **Performance:** Payload bounds: images 512-768 px, text length caps, so mobile data stays small.
- **Security / Privacy:** Contract states which fields may carry personal data (images, screen text, embeddings) and that none are persisted by default.
- **Definition of Done:** Contract merged and reviewed by all six; fixtures validate on both sides; a change process exists (PR + Technical Decision entry).

### WS-03 Android app core

- **Purpose / Why:** The shell that hosts every pipeline; the user never sees a screen, so reliability beats UI.
- **Inputs:** Permissions grants, contract models, headset state, connectivity, battery.
- **Processing:** Single-activity Compose app plus a foreground service that owns camera, audio and engine; permission onboarding by voice and screen; SQLite cache; API client; settings; debug/alert-log screen for demos and field tests.
- **Outputs:** Installable app that starts the services and exposes the module interfaces.
- **Internal components:** `MainActivity`, `AssistantService` (foreground), `ModuleRegistry`, `PermissionManager`, `ApiClient`, `LocalStore`, `SettingsStore`, `AlertLogScreen`, `Logger`, `ConnectivityMonitor`.
- **APIs / interfaces:** Kotlin interfaces: `FrameSource`, `Detector`, `SpeechOutput`, `IntentRouter`, `ScreenReader`, `LocationSource`, `EngineInput.submit(Event)`.
- **Data structures:** `AppState` (listening, camera on, offline, battery, headset, navigation active), SQLite tables for settings, log, people cache.
- **Dependencies:** WS-02; WS-01.
- **Consumers:** All Android workstreams.
- **Error cases:** Permission denied, service killed by OS, headset disconnect, camera busy, low battery.
- **Testing:** Instrumented permission-flow tests; service restart test; fake module injection test.
- **Performance:** Foreground service with a persistent notification; no work on the main thread; modules lazy-load models.
- **Security / Privacy:** Consent notice at first run and before cloud calls; no frames written to disk; SQLite holds no images.
- **Definition of Done:** App runs 30 minutes with camera + mic + engine on the test phone; every permission denial produces a spoken explanation; alert-log screen shows all events including suppressed ones.

### WS-04 Camera pipeline (CameraX)

- **Purpose / Why:** Supplies frames to detection, OCR, face and VLM paths at a controlled rate; frame rate is the main battery lever.
- **Inputs:** CameraX `ImageAnalysis` frames, motion state, thermal state, user commands.
- **Processing:** Bind lifecycle to the service; downscale and rotate frames; sample at 15-25 FPS for detection and drop to a low rate when stationary; hold the last frame for still capture on request; compress to 512-768 px JPEG for cloud calls.
- **Outputs:** `FrameSource` stream plus `captureStill()` for describe/ask/OCR.
- **Internal components:** `CameraController`, `FrameSampler`, `StillCapture`, `FrameCompressor`, `FpsGovernor`.
- **APIs / interfaces:** `FrameSource.frames(): Flow<Frame>`, `FrameSource.captureStill(): Frame`, `FpsGovernor.setMode(active|idle|thermal)`.
- **Data structures:** `Frame` (bitmap or buffer, rotation, timestamp, camera orientation).
- **Dependencies:** WS-03 (permissions, service). Does not depend on any model: a `VideoFileFrameSource` replays recorded videos for everyone else.
- **Consumers:** WS-05, 06, 07, 08, 09, 15.
- **Error cases:** Camera permission revoked, camera in use, empty frame, lens covered, app backgrounded.
- **Testing:** Replay a field video through the same interface; empty-frame and rotation tests.
- **Performance:** Zero-copy buffers where possible; target stable 15-25 FPS on the test phone; thermal fallback to lower FPS.
- **Security / Privacy:** Frames stay in memory; the preview is not shown or stored; a still leaves the phone only after consent.
- **Definition of Done:** Live and file-replay sources implement the same interface; FPS governor responds to motion and heat; 30-minute run without leaks or crash.

### WS-05 Object detection (YOLO nano-class)

- **Purpose / Why:** Core hazard awareness: people, vehicles and obstacles on the phone, offline, at 15-25 FPS.
- **Inputs:** `Frame` from WS-04; fine-tuned weights from WS-24.
- **Processing:** Letterbox resize, on-device inference (LiteRT GPU/NPU delegate, ONNX as fallback), NMS, class filter to the project label set (person, vehicle classes, a short obstacle list such as chair/bench/pole-like objects; final list fixed by the labeling guidelines), per-class confidence thresholds.
- **Outputs:** `Detection[]` (label, box, confidence, frame timestamp) to the tracker.
- **Internal components:** `ModelLoader` (lazy, warm-up), `Preprocessor`, `InferenceRunner`, `Postprocessor`, `DelegateSelector` (GPU/NPU/CPU), export scripts in `computer_vision/export/`.
- **APIs / interfaces:** `Detector.detect(frame): List<Detection>`; `FakeDetector` replays a JSON file of detections so engine and tracker work without any model.
- **Data structures:** `Detection{label, conf, x, y, w, h, ts}`; model manifest entry.
- **Dependencies:** WS-04 for live frames (or video-file source), WS-24 for weights. A COCO-pretrained baseline unblocks everything in week 1-2.
- **Consumers:** WS-06, WS-08 (person crops), WS-25.
- **Error cases:** Model missing or corrupt, delegate unsupported, inference slower than frame budget (drop frames, never queue), nighttime low confidence, small objects missed.
- **Testing:** Fixed labeled sets (daylight, then low light) with precision/recall per class; latency and FPS report on the test phone; replay of field videos.
- **Performance:** 30-70 ms/frame, 15-25 FPS; drop-oldest frame policy; warm-up at app start.
- **Security / Privacy:** No frame persistence; weights from a reviewed source. **Licence decision TD-001:** YOLO nano is AGPL; either accept and release the project under compatible terms or switch to an Apache-licensed detector before week 3.
- **Definition of Done:** Fine-tuned model exported, runs on the test phone within the latency target, accuracy report committed, licence decision recorded, `FakeDetector` available.

### WS-06 Tracking and spatial estimation

- **Purpose / Why:** Turns per-frame boxes into stable objects with side, relative distance and approach, so the engine speaks once per object instead of once per frame.
- **Inputs:** `Detection[]`, frame size, motion state (IMU/tracker).
- **Processing:** ByteTrack-style association gives stable track IDs. **Side:** box centre x in thirds of the frame (left/center/right). **Distance band:** box height or area relative to frame height, per class, thresholds calibrated for the fixed chest-mount position: `near | medium | far`, never meters. **Vehicle approach:** box area growth over a sliding window (several consecutive frames), vehicle class, track age above a minimum, and a higher growth threshold while the user is walking (ego-motion also grows boxes), producing `vehicle_approaching` with side. No speed, no time-to-collision. **Motion state:** stationary vs moving from IMU and global box flow.
- **Outputs:** `Event` objects (spec schema) and `MotionState`.
- **Internal components:** `Tracker`, `DistanceBander`, `SideEstimator`, `ApproachDetector`, `MotionEstimator`, `EventBuilder`, calibration config file.
- **APIs / interfaces:** `Tracker.update(detections, ts): List<Track>`; `EventBuilder.build(track): Event?`; calibration JSON (band thresholds per class).
- **Data structures:** `Track{id, label, history[], lastBox, age, lostFrames}`; `Event` per WS-02.
- **Dependencies:** WS-02 schema; consumes `Detection` fixtures so it does not wait for WS-05.
- **Consumers:** WS-16 (engine), WS-27.
- **Error cases:** ID switches, missed frames, box jitter flipping bands (use hysteresis), static car appearing to approach while the user walks, tracker latency spikes.
- **Testing:** Unit tests on synthetic box sequences (approaching, receding, crossing, jitter); replay on labeled videos with an annotated approach ground truth; false-positive rate of `vehicle_approaching` on walking-past-parked-cars clips.
- **Performance:** Under 5 ms per frame on CPU.
- **Security / Privacy:** Operates on boxes only, no images.
- **Definition of Done:** Events conform to the frozen schema; approach detector meets a precision target set at Gate 2 on the field-video set (target value agreed with Marwan); band thresholds calibrated and documented in `docs/architecture.md`; tracker under 5 ms.

### WS-07 OCR, QR and color

- **Purpose / Why:** Read signs, menus, documents and medicine labels on demand; QR and color are cheap Should-tier extras.
- **Inputs:** Still `Frame` on user request ("read this"); optional continuous mode off by default for battery.
- **Processing:** ML Kit Text Recognition (Latin offline); order blocks top-to-bottom, left-to-right; chunk text for TTS; if confidence is low or the text is Arabic, call the cloud fallback via `/ask` with an OCR prompt; QR via ML Kit barcode; color by HSV analysis of a centre region with VLM on request.
- **Outputs:** `OcrResult{text, blocks, language, confidence, source=ml_kit|cloud}` then a `SpeechRequest` at Info level (user-requested).
- **Internal components:** `OcrEngine`, `TextOrderer`, `OcrFallback`, `QrReader`, `ColorEstimator`.
- **APIs / interfaces:** `OcrEngine.read(frame): OcrResult`; fixture images with expected text.
- **Data structures:** `OcrResult`, `TextBlock`.
- **Dependencies:** WS-04 still capture (or image fixtures); WS-22 for cloud fallback (mock supplies canned text).
- **Consumers:** WS-14 (read intent), WS-18 (screenshot OCR fallback), WS-16.
- **Error cases:** Blurry frame, no text found, glare, Arabic or handwriting, partial reading. Spoken guidance: "move the phone closer", "I can't read this".
- **Testing:** 0.1-0.4 s per frame; test set of Egyptian signs, menus and medicine boxes; word error rate recorded per category.
- **Performance:** Run on demand only; no background OCR.
- **Security / Privacy:** Text from medicine labels is read back verbatim only, never interpreted as medical advice; cloud fallback only after consent.
- **Definition of Done:** OCR evaluated on the Egyptian test set with a report; fallback works in mock and real mode; user hears clear spoken failure states.

### WS-08 Face recognition (Should)

- **Purpose / Why:** Name known, opted-in people; announce anyone else generically as "someone you don't know". Never name strangers.
- **Inputs:** Person-class detections, face crops, enrollment audio/consent.
- **Processing:** ML Kit face detection, crop and align, MobileFaceNet/ArcFace-style embedding, cosine similarity against the local cache of known people (synced from the backend); a conservative threshold and multi-frame agreement before naming; below threshold announces "someone you don't know".
- **Outputs:** `FaceEvent{name|unknown, confidence}` into the engine as an Info-level event.
- **Internal components:** `FaceDetector`, `FaceAligner`, `EmbeddingModel`, `FaceMatcher`, `EnrollmentFlow`, `KnownPeopleCache`.
- **APIs / interfaces:** `/faces/enroll` (embedding only), `/faces/match` (embedding to name or unknown); on-device match first, backend match for sync/verification.
- **Data structures:** `Embedding(float[])`, `KnownPerson{id, display_name, embedding(s), created_at, consent_at}`.
- **Dependencies:** WS-05 (person boxes) or fixture crops, WS-23 (schema and pgvector), WS-22 (endpoints), WS-28 (consent).
- **Consumers:** WS-16, WS-14 (enrollment command).
- **Error cases:** Wrong name (worst failure: bias toward "unknown"), angle and low light, model licence, enrollment without consent.
- **Testing:** Small volunteer set; false-accept rate at the chosen threshold, false-reject rate, 50-150 ms per face.
- **Performance:** Run only when a person is near and stationary; one embedding per track, not per frame.
- **Security / Privacy:** Opt-in with written consent; store embeddings only, encrypted; no face images stored; a voice command deletes a person; model licence check.
- **Definition of Done:** Enroll and name a volunteer end-to-end on the phone, unknown path works, accuracy report with FAR/FRR, deletion works.

### WS-09 Currency recognition, EGP (Should)

- **Purpose / Why:** Identify banknote denominations; the spec says no reliable public dataset exists, so this is dataset-bound.
- **Inputs:** Still frame on user request ("what note is this").
- **Processing:** MobileNetV3-class classifier fine-tuned on the team's own dataset (all denominations the dataset covers, both sides, worn notes, varied light); aggregate over several frames; a high confidence threshold else "I'm not sure, hold it flat".
- **Outputs:** `CurrencyResult{denomination|unsure, confidence}` then speech.
- **Internal components:** `CurrencyClassifier`, `FrameVoter`, dataset scripts in `computer_vision/training/`.
- **APIs / interfaces:** `CurrencyClassifier.classify(frame)`.
- **Data structures:** Dataset manifest, label map.
- **Dependencies:** WS-24 dataset collection (printed banknote copies per spec Sec. 6, real notes if available), WS-04.
- **Consumers:** WS-14, WS-16.
- **Error cases:** Wrong denomination (financial harm: prefer "unsure"), folded or worn notes, bad light.
- **Testing:** Held-out set per denomination; confusion matrix; under 50 ms.
- **Performance:** On demand only.
- **Security / Privacy:** Dataset contains no personal data; real-note photos are not published.
- **Definition of Done:** Classifier exported, per-denomination accuracy report, "unsure" path tested; if accuracy is below the agreed bar at Gate 3 the feature is cut, not shipped.

### WS-10 Relative depth (optional)

- **Purpose / Why:** Possible improvement to near/far when the box-size heuristic fails; the spec marks it optional.
- **Inputs / Processing / Outputs:** Depth Anything V2-small run on demand or at low rate, producing a relative depth ordering only (100-300 ms, extra battery).
- **Internal components, interfaces, data:** `DepthEstimator.estimate(frame): RelativeDepthMap`, adapter into `DistanceBander`.
- **Dependencies:** WS-05, WS-06, WS-27 (battery budget). Starts only if the Gate 2 report shows the heuristic is inadequate.
- **Consumers:** WS-06.
- **Error cases:** Latency or heat above budget; misleading depth on glass or mirrors.
- **Testing:** A/B against the heuristic on the same videos.
- **Performance / Security:** Battery-gated; no images stored.
- **Definition of Done:** Default is **not started**. If started: A/B report shows measurable benefit at acceptable battery cost; otherwise closed as "not adopted" in Technical Decisions. Never reported in meters.

### WS-11 Wake word and audio capture

- **Purpose / Why:** Hands-free entry point and the always-on emergency path; must run offline and cheaply.
- **Inputs:** Microphone stream (foreground service), headset routing state.
- **Processing:** Always-on wake-word detection (openWakeWord or Porcupine custom keyword, **decision TD-002**); on wake, play a short earcon, open an STT session; a separate on-device emergency-keyword spotter feeds the SOS fast path without STT or LLM; audio focus ducks TTS while listening.
- **Outputs:** `WakeEvent`, `EmergencyKeywordEvent`, raw audio to STT.
- **Internal components:** `AudioCaptureService`, `WakeWordDetector`, `EmergencyKeywordDetector`, `AudioFocusManager`, `HeadsetMonitor`.
- **APIs / interfaces:** `WakeWord.start()/stop()`, `onWake(): Flow<WakeEvent>`; a `FakeWakeWord` triggered by a debug button.
- **Data structures:** `WakeEvent{ts, confidence}`, audio ring buffer (in memory only).
- **Dependencies:** WS-03 (service, mic permission). The `FakeWakeWord` unblocks WS-12 and WS-14 immediately.
- **Consumers:** WS-12, WS-14, WS-21.
- **Error cases:** False triggers in noise, missed wake, mic taken by another app, headset mic vs phone mic, Bluetooth latency, service killed.
- **Testing:** Recorded street-noise set for false-trigger rate per hour; miss rate at several distances; latency under 200 ms.
- **Performance:** Low-power CPU inference; measure battery cost per hour as part of WS-27.
- **Security / Privacy:** Audio is processed in memory; only post-wake speech goes to STT; nothing is uploaded unless the Whisper-class upgrade is adopted.
- **Definition of Done:** Wake word works on the test phone with the chosen library, emergency keyword path triggers SOS countdown without network, false-trigger and miss rates reported.

### WS-12 Speech-to-text

- **Purpose / Why:** Turn commands into text; spec says Android on-device recognizer first, with a Whisper-class upgrade for Egyptian Arabic and English mixing.
- **Inputs:** Audio after wake word, language setting.
- **Processing:** Android `SpeechRecognizer` (on-device where available) in English for the demo; endpointing and timeout; partial results; confidence; on failure ask once to repeat.
- **Outputs:** `Transcript{text, confidence, language}`.
- **Internal components:** `SttEngine` interface, `AndroidSttEngine`, `FakeSttEngine` (text injected from a debug box or fixture file).
- **APIs / interfaces:** `SttEngine.listen(timeout): Transcript`. The Whisper-class upgrade has **no endpoint in the spec**, so it is Nice-tier and needs a contract change first.
- **Data structures:** `Transcript`.
- **Dependencies:** WS-11 for live audio; `FakeSttEngine` makes WS-14 independent.
- **Consumers:** WS-14.
- **Error cases:** Street noise, silence, recognizer offline pack missing, recognizer busy, wrong language.
- **Testing:** Fixed command list spoken at several noise levels; word accuracy per command.
- **Performance:** Session opened only after wake; closed on endpoint.
- **Security / Privacy:** Android's on-device mode preferred; if the platform recognizer uses the network, disclose it in the consent text.
- **Definition of Done:** All demo commands recognized with an agreed accuracy in a quiet room and in noise; fake engine exists; timeouts speak a recoverable prompt.

### WS-13 Text-to-speech and audio output

- **Purpose / Why:** The only output channel; the engine decides what and when, this workstream decides how it sounds and stops.
- **Inputs:** `SpeechRequest` from the engine (text, level, interruptible, utterance ID).
- **Processing:** Android offline `TextToSpeech` for all alerts and answers; flush-and-speak for Critical, queue for others; chunk long reading text; report start and end of each utterance back to the engine; voice rate and pitch preferences (Should); route to the headset, fall back to the phone speaker plus vibration if the headset disconnects. Neural cloud TTS for long reading is Nice-tier (no endpoint in the spec).
- **Outputs:** Audio, plus `UtteranceStarted/Finished/Interrupted` callbacks.
- **Internal components:** `SpeechOutput` interface, `AndroidTtsSpeaker`, `AudioRouter`, `EarconPlayer`, `FakeSpeaker` (logs text to console and test buffer).
- **APIs / interfaces:** `SpeechOutput.speak(req)`, `stop(utteranceId?)`, `isSpeaking`.
- **Data structures:** `SpeechRequest`, `UtteranceState`.
- **Dependencies:** WS-02 contract; WS-03 service. `FakeSpeaker` lets the engine be tested with no audio.
- **Consumers:** WS-16 only. Nothing else may call TTS directly; this is what stops overlapping voices.
- **Error cases:** TTS engine not installed, voice missing for the language, headset disconnected, audio focus lost to a call, very long text.
- **Testing:** Interruption tests (Critical during an Info utterance), callback ordering, headset unplug test.
- **Performance:** Start speaking within a short budget after a Critical decision (target set at Gate 2).
- **Security / Privacy:** Alerts never contain personal data beyond the enrolled name.
- **Definition of Done:** All speech goes through one speaker object; interruption and resume behave as specified in the engine rules; headset-disconnect handled.

### WS-14 Intent and voice flow

- **Purpose / Why:** Maps a transcript to an action; the rule-based fast path keeps emergencies and common commands working offline and instantly.
- **Inputs:** `Transcript`, current context (offline, navigation active, screen app in front).
- **Processing:** Rules first (open app, read screen, scroll, back, "what's around me", "read this", ask about the scene, navigate to X, SOS, cancel, stop, repeat, enroll person, volume/speed); no match and online: `/intent` returns an `ActionPlan`; offline with no rule match: speak "I can't do that offline". Plans with risky steps get a spoken confirmation step. Follow-ups ("scroll down") reuse the last screen context.
- **Outputs:** `ActionPlan` to the action router, which calls the camera, screen, navigation, SOS or AI modules.
- **Internal components:** `RuleIntentParser`, `IntentApiClient`, `ActionRouter`, `ConfirmationManager`, `ConversationState` (timeouts, last topic), `ActionPlanValidator` (AVN-220).
- **APIs / interfaces:** `IntentRouter.route(transcript): ActionPlan`; `POST /intent`.
- **Data structures:** `ActionPlan{steps[], requires_confirmation, spoken_ack, clarification}`, `PlanStepItem{step, arg?}`, `ValidationResult{plan, droppedSteps, forcedConfirmation}` (all in `avn.contracts`, module `:contracts`).
- **Dependencies:** WS-02; text-only tests need no audio; `/intent` mock returns canned plans.
- **Consumers:** WS-17, 18, 20, 21, 07, 15.
- **Error cases:** Misrecognized command, ambiguous command (ask one short question), LLM returns an invalid plan (validate against the step enum and reject unknown steps), wrong app name.
- **Testing:** Table-driven unit tests on a command corpus including near-misses; plan-validator tests; emergency phrases always route to SOS. `ActionPlanContractTest` (JUnit 4, `:contracts`) round-trips every fixture in `contracts/fixtures/plans/valid/`, checks that each of the 11 whitelisted steps appears in at least one fixture, and runs the validator on `tests/fixtures/plans/untrusted/` and on garbage input(never throws). Run: `./gradlew :contracts:test`.
- **Performance:** Rules resolve in milliseconds; `/intent` budget follows the 1.5-4 s cloud target.
- **Security / Privacy:** The LLM can propose steps only from a whitelist; send/delete/buy/pay steps always demand confirmation; emergency never depends on the cloud.
- **Definition of Done:** Every demo command has a rule or a tested `/intent` path; validator blocks unknown steps; confirmation flow works; offline behavior spoken.

#### WS-14 · ActionPlan contract (AVN-220)

`ActionPlan` is the contract between intent routing and handlers: the `data` of `POST /intent` and the return type of `IntentRouter.route`.

**Files added**

| Path | Purpose |
| --- | --- |
| `android/contracts/src/main/kotlin/avn/contracts/Models.kt` | `ActionPlan`, `PlanStepItem` documented, fields unchanged |
| `android/contracts/src/main/kotlin/avn/contracts/ActionPlanValidator.kt` | `ActionPlanValidator`, `ValidationResult` |
| `android/contracts/src/test/kotlin/avn/contracts/ActionPlanContractTest.kt` | Contract test (JUnit 4) |
| `contracts/fixtures/plans/valid/` | 9 raw `ActionPlan` JSON files; every step appears at least once |
| `tests/fixtures/plans/untrusted/` | 3 LLM-style bad plans: unknown step, all unknown, unconfirmed risky tap. Outside `contracts/fixtures/` on purpose: they are invalid by design |

Wire names are `step`, `arg`, `requires_confirmation`, `spoken_ack`, `clarification`. The `/intent` envelope example stays `contracts/fixtures/api/intent.ok.json`.

**Step whitelist (11):** `launch_app, wait, read_screen, summarize, scroll, back, tap_label, navigate, sos, describe, ask`. Single source: the `PlanStep` enum.

**Validator rules**

1. `parse(raw: String)` works on raw JSON and never throws. An unknown enum value would make kotlinx.serialization throw, so unknown or malformed steps are dropped before decoding and listed in `droppedSteps`.
2. More than `MAX_STEPS` (8, starting value): extra steps are dropped.
3. No valid step left: `clarification` is set (the plan's own, else "I didn't understand that. Please say it another way.") and `requires_confirmation` is false.
4. A `tap_label` whose `arg` contains a risky word (send, delete, buy, pay, post, confirm and Arabic equivalents) forces `requires_confirmation = true` even if the LLM said false. `forcedConfirmation` reports it. The word list is a starting list, to be unified with `RiskyLabelGuard` (WS-17).
5. `sos` never asks confirmation: its spoken countdown is the cancel window. Emergency never depends on the cloud.
6. `validate(plan)` applies the same rules to an already typed plan (rule parser, fakes).

**Who uses what**

| Who | What to do |
| --- | --- |
| Backend (`/intent`, WS-22) | Return `ActionPlan` JSON in the envelope `data`; drop unknown steps with the same whitelist and the same risky-tap rule. Reuse `contracts/fixtures/plans/valid/` and `tests/fixtures/plans/untrusted/` in pytest.The Python mirror is not part of AVN-220 |
| Android `IntentApiClient` | Pass the envelope `data` string to `ActionPlanValidator.parse(...)`. Never decode an LLM plan with `Json.decodeFromString` |
| Android `RuleIntentParser` | Build an `ActionPlan`, pass it through `ActionPlanValidator.validate(...)` |
| Android `ActionRouter` and handlers | Run only the validated `plan`. If `requiresConfirmation` is true, ask first. Speak `spokenAck` first. If `steps` is empty, speak `clarification` |

**Not done in AVN-220:** wiring the validator into `FakeIntentRouter` and the real router (WS14-05, WS14-06), passing conversation context, and a "read this" (OCR) step, which is not in the whitelist.

### WS-15 VLM/LLM prompts and providers

- **Purpose / Why:** All cloud reasoning (scene, look-and-ask, screen summary, intent, OCR fallback, translation) goes through one provider layer so it can be mocked, swapped and cost-capped. Output is informational only and never drives a safety alert.
- **Inputs:** Compressed frame (512-768 px) and question; screen text/tree and optional screenshot; free-form command; context hints.
- **Processing:** Versioned prompt templates in `nlp/prompt_templates/`: `describe` (1-2 short sentences, left/right relative, no safety claims), `ask`, `screen_summarize`, `intent` (returns a plan from the whitelist), `ocr_fallback`, `translate`. A `VlmProvider` interface with Anthropic and Gemini implementations selected by `VLM_PROVIDER`, a `MockProvider`, response caching for repeats, hard output bounds, and "I may be wrong" phrasing for uncertain scenes.
- **Outputs:** Bounded text plus a fallback message on timeout.
- **Internal components:** Template loader, prompt builder, provider adapters, cache, token and cost guard, response sanitizer.
- **APIs / interfaces:** `VlmProvider.complete(prompt, images, max_tokens, timeout)`; consumed by the backend services behind `/describe`, `/ask`, `/screen/summarize`, `/intent`.
- **Data structures:** `PromptTemplate{name, version, system, user_slots}`, `ProviderResult{text, usage, latency, provider}`.
- **Dependencies:** WS-22 hosts it; prompts can be written and evaluated against provider APIs or mock from day 1.
- **Consumers:** WS-22, WS-14, WS-18.
- **Error cases:** Timeout, rate limit, empty or refused response, hallucinated objects, over-long output, provider outage, spend limit hit.
- **Testing:** Prompt-builder unit tests; a small golden set of frames/screens with a rubric (hallucination, brevity, usefulness); timeout and fallback tests; cost-per-call log.
- **Performance:** 1.5-4 s target; images downscaled; cache repeated answers.
- **Security / Privacy:** Spending limits on every key; no image or screen content logged by default; screen content sent only after the consent notice; password/banking screens never sent.
- **Definition of Done:** All prompts versioned and evaluated on the golden set; provider switch works by env var; mock mode returns deterministic answers; limits configured.

### WS-16 Priority and context engine

The central product component. Full specification is in the **Priority engine deep dive** section below. Summary: consumes `Event` and `SpeechRequest` candidates from every pipeline, applies risk, thresholds, cooldowns and duplicate suppression, schedules speech through the single `SpeechOutput`, logs every decision including suppressed ones, and exposes the alert-log view. Primary owner Marwan; the engine is pure Kotlin with no Android dependencies so it is unit-testable on a laptop from week 1.

### WS-17 AccessibilityService and screen reading

- **Purpose / Why:** Rung 1 of the perception ladder: exact text, buttons and scroll containers, free and offline. Works app by app, not universally.
- **Inputs:** `ActionPlan` steps (launch app, wait, read, scroll, back, tap label); window events from the OS.
- **Processing:** Launch intent, then wait until the window settles (debounced content-change events). Traverse `rootInActiveWindow` with depth and node-count caps; per node keep text, content description, view id, class, clickable, scrollable, bounds, password flag. Classify the screen by package and structure: *chat list, chat thread, feed post, settings/other, blocked*. Blocked = password field present, package on a deny-list (banking, wallets), or empty/secure window; nothing from a blocked screen is read or sent. Actions: global back, scroll forward/backward, click a node matched by exact label. Scrolling: after each scroll re-wait, re-extract, de-duplicate overlapping text. Every action is announced before it runs.
- **Outputs:** `ScreenSnapshot{package, screen_type, nodes[], text, clickable_labels[], flags}`.
- **Internal components:** `AssistantAccessibilityService`, `NodeExtractor`, `ScreenClassifier`, `SettleWaiter`, `ActionExecutor`, `RiskyLabelGuard` (send, delete, buy, pay, post, confirm and equivalents), `AppRegistry` (the 2-3 tested apps).
- **APIs / interfaces:** `ScreenReader.snapshot(): ScreenSnapshot`, `ScreenActions.scroll(dir)`, `back()`, `tap(label)`; `FakeScreenReader` loads snapshot JSON fixtures.
- **Data structures:** `ScreenSnapshot`, `NodeInfo`, `AppProfile{package, known_labels, quirks}`.
- **Dependencies:** WS-03 (service shell), WS-02. Fixtures and the mock WhatsApp screen unblock WS-18 and WS-22 before the real service is ready. Needs the Android test phone with "restricted settings" enabled by hand.
- **Consumers:** WS-18, WS-14, WS-16.
- **Error cases:** Empty or unlabeled nodes, UI changed by an app update, service disabled by the OS, scroll container not found, app not installed, secure screen. Fallback is the next rung of the ladder or a spoken "I can't read this screen".
- **Testing:** Mock messaging screens plus 2-3 real apps on one phone model; snapshot golden files per app; OS and app auto-updates frozen before the demo.
- **Performance:** Extraction in tens of milliseconds; caps stop huge trees (feeds) from stalling.
- **Security / Privacy:** Password and banking screens never read; screen text not logged or stored; spoken confirmation before send/delete/buy/pay (spec: such steps are not performed in the MVP beyond read-only); one-time warning that screen content may be sent to a cloud model.
- **Definition of Done:** Open app, read, scroll, back and tap-label work on the tested apps; blocked-screen detection tested; every action announced; fixture-based tests pass without a phone.

### WS-18 Screen capture and screen AI

- **Purpose / Why:** Rungs 2 and 3: screenshot with on-device OCR when nodes are empty, screenshot with cloud VLM when layout, images or video frames matter.
- **Inputs:** `ScreenSnapshot` flags (empty/unlabeled), user question ("summarize this post").
- **Processing:** Capture a screenshot (MediaProjection with its per-session user consent, or the accessibility screenshot API; **decision TD-004** by week 3), downscale to 512-768 px, skip if the screen is secure or blank. Ladder: tree text, then OCR via WS-07, then `/screen/summarize` with tree text plus optional screenshot. Backend assembles the prompt and returns a bounded summary; the phone speaks it. Reels/video (Should): one visible frame plus caption, no audio, stated honestly to the user.
- **Outputs:** `ScreenSummary{text, source=tree|ocr|vlm, confidence_note}` then `SpeechRequest`.
- **Internal components:** `ScreenCaptureManager`, `SecureScreenDetector`, `LadderOrchestrator`, `ScreenSummaryClient`.
- **APIs / interfaces:** `POST /screen/summarize`; `ScreenAi.summarize(snapshot, question): ScreenSummary`.
- **Data structures:** Request: tree text, package, screen\_type, optional image, question. Response: summary text, bounded.
- **Dependencies:** WS-17 (or fixtures), WS-07, WS-22/WS-15 (or mock). Developers can write the ladder against fixtures and the mock endpoint.
- **Consumers:** WS-14, WS-16.
- **Error cases:** User denies capture consent, capture black (secure window), VLM timeout (speak the tree text if present), offline (say summaries are unavailable), hallucinated summary.
- **Testing:** Golden screens through all three rungs; timeout and offline tests; consent-denied test.
- **Performance:** Cloud answer 1.5-4 s; screenshot capture and downscale under a short budget; no screenshots when rung 1 suffices.
- **Security / Privacy:** Consent notice first; secure screens excluded; screenshots held in memory and discarded; classification never stored.
- **Definition of Done:** The ladder picks the cheapest working rung, fallbacks speak clear messages, summary of a mock post and a real Facebook post works, offline message tested.

### WS-19 Notification reading

- **Purpose / Why:** Read new WhatsApp messages and other notifications without opening the app; read-only, which is much more robust than screen automation.
- **Inputs:** `NotificationListenerService` events; user command "read my new messages".
- **Processing:** Filter to allowed packages (WhatsApp by default); extract sender and text; setting for sender-only vs full text; queue as Info events: spoken on request, or when the user is idle and stationary; group by sender; never reply.
- **Outputs:** `NotificationEvent` into the engine and `SpeechRequest` on request.
- **Internal components:** `NotificationCapture`, `NotificationFilter`, `NotificationStore` (in memory, bounded).
- **APIs / interfaces:** `Notifications.pending(): List<Notification>`, `markRead()`; `FakeNotificationSource`.
- **Data structures:** `Notification{package, sender, text, ts}`.
- **Dependencies:** WS-03, WS-16 for speech rules. Fixtures unblock it.
- **Consumers:** WS-14, WS-16.
- **Error cases:** Listener permission revoked, notification content hidden by the lock screen, duplicate notifications, flood of group messages (summarize count instead of reading all).
- **Testing:** Fake notifications through the engine; real WhatsApp on the test phone.
- **Performance:** Event-driven, no polling.
- **Security / Privacy:** Content not persisted; read aloud only through the headset path by default; per-app allow list.
- **Definition of Done:** "Read my new messages" works on a real WhatsApp message and on the mock; group-flood rule tested; no write path exists in code.

### WS-20 Navigation (GPS, Maps, cues)

- **Purpose / Why:** Walking turn-by-turn by voice on a pre-tested route, with audio and haptic cues.
- **Inputs:** Destination from the intent; location updates; `/route` response.
- **Processing:** Fused location; request `GET /route` (origin, destination, walking) through the backend proxy and cache; the backend resolves a spoken destination (for example "nearest pharmacy") with the Maps API inside the proxy, since the spec has no separate places endpoint *(proposed)*; keep the step list and polyline; announce each turn at tuned distances; vibration patterns for left and right; off-route detection leads to one reroute request; arrival announcement. Offline: continue with the already loaded route. Navigation speech enters the engine as time-sensitive Info so a Warning or Critical can preempt it.
- **Outputs:** `NavigationEvent`/`SpeechRequest`, vibration cues, `NavState`.
- **Internal components:** `LocationSource`, `RouteClient`, `RouteTracker`, `TurnAnnouncer`, `HapticCues`, `DestinationResolver` (client side), `FakeLocationSource` (replays a GPX file).
- **APIs / interfaces:** `GET /route`; `Navigation.start(destination)`, `cancel()`, `state`.
- **Data structures:** `Route{steps[], polyline, distance, duration}`, `NavState{active, step_index, off_route}`.
- **Dependencies:** WS-22 (or mock `/route` returning a stored route), WS-16 for speech arbitration, WS-14 for the command. GPX replay lets it be developed at a desk.
- **Consumers:** WS-16, WS-27.
- **Error cases:** GPS drift between buildings, no fix, API quota, destination not found, user walks the wrong way, GPS off.
- **Testing:** GPX replay unit tests for step progression and off-route; field walks of the pre-tested route; cache hit/miss tests.
- **Performance:** GPS rate reduced when standing still; route cached.
- **Security / Privacy:** Location sent only to the backend proxy for `/route`; not stored beyond the cache key policy; Maps key stays on the backend.
- **Definition of Done:** Complete a pre-tested route with correct announcements and cues, reroute once, offline continuation works, GPX tests pass.

### WS-21 SOS and emergency

- **Purpose / Why:** The highest-stakes feature: a voice SOS that works with no internet and cannot fire by accident.
- **Inputs:** Emergency keyword event, an explicit SOS intent, optionally an on-screen button for a sighted helper *(proposed)*; saved contacts; location.
- **Processing:** Critical-level spoken countdown ("Sending SOS in 5 seconds, say cancel to stop"); cancel by voice stops it; on expiry get the best location (fresh fix with timeout, else last known with its age), send SMS with a maps link, optionally place a call; optional `/sos` push when online; result spoken ("SOS sent" or "SOS failed, trying again"); everything logged.
- **Outputs:** SMS, optional call, optional `/sos` call, spoken state.
- **Internal components:** `SosController` (state machine: idle, countdown, sending, sent, failed, cancelled), `ContactStore`, `LocationFixer`, `SmsSender`, `SosApiClient`.
- **APIs / interfaces:** `Sos.trigger(source)`, `Sos.cancel()`; `POST /sos` (optional).
- **Data structures:** `SosRequest{contacts, lat, lon, accuracy, fix_age, ts}`.
- **Dependencies:** WS-11 (emergency keyword, cancel), WS-13, WS-16 (Critical pre-emption); fully testable with fake location, fake SMS sender and the fake speaker.
- **Consumers:** WS-16, WS-26.
- **Error cases:** SMS permission denied, no cellular signal, no GPS fix, accidental trigger, cancel not heard, contact list empty.
- **Testing:** State-machine unit tests; end-to-end to a team phone only. **Development and demo use team numbers; the emergency-number path is disabled in builds and tests.**
- **Performance:** The whole local path works offline in a few seconds beyond the countdown.
- **Security / Privacy:** Contacts stored locally; location shared only on trigger; false-trigger rate measured in field tests.
- **Definition of Done:** Voice SOS sends an SMS with location to a team phone after a countdown, cancel works, offline works, failure states spoken and logged, false-trigger report exists.

### WS-22 Backend core and deployment

- **Purpose / Why:** One thin FastAPI service that hides API keys, bounds every response, caches, and can run fully mocked, so the phone never talks to a paid API directly.
- **Inputs:** HTTPS requests from the app; env: `VLM_API_KEY`, `VLM_PROVIDER`, `MAPS_API_KEY`, `DATABASE_URL`, `MOCK_MODE`.
- **Processing:** Routers validate (Pydantic), call services, services call providers (VLM, Maps) and the database; a timeout wrapper turns any failure into a fallback envelope; request-ID logging. Details per endpoint in the backend section below.
- **Outputs:** `ApiEnvelope` JSON, bounded size.
- **Internal components:** `main.py`, `routers/`, `services/`, `providers/` *(proposed split of the spec's routers/services)*, `schemas/`, `core/{config,auth,timeouts,logging,mock}`, `Dockerfile`, `docker-compose.yml` (backend + PostgreSQL with pgvector).
- **APIs / interfaces:** The 9 endpoints of spec Sec. 11 and nothing else.
- **Data structures:** Pydantic models mirroring WS-02.
- **Dependencies:** WS-02 (contract). The mock backend ships in week 1-2 so Android never waits.
- **Consumers:** Android `ApiClient` (WS-03), WS-26 tests.
- **Error cases:** Provider timeout or error, malformed payload, oversize image, spend limit reached, DB down, VPS restart.
- **Testing:** pytest + httpx with valid, invalid, oversize and timeout payloads; mock-mode suite that never calls a paid API; one integration suite against real providers, run sparingly.
- **Performance:** Per-endpoint timeout under the 1.5-4 s cloud target; image caps; response cache.
- **Security / Privacy:** Per-install API key header plus HTTPS and rate limit *(proposed; the spec is silent on auth)*; keys only in env; spending limits at the provider; request bodies with images or screen text are not logged.
- **Definition of Done:** `docker compose up` starts the service; all 9 endpoints behave per contract in mock mode; real provider path works for `/describe`, `/ask`, `/screen/summarize`, `/intent`; deployed to a VPS; limits set; health endpoint monitored.

### WS-23 Database and known-people data

- **Purpose / Why:** Persist users, faces, saved places, settings and logs on the backend, and keep a fast offline cache on the phone.
- **Inputs:** Enrollment embeddings, settings changes, saved places, history entries.
- **Processing:** PostgreSQL schema with pgvector for face embeddings and saved places; migrations; sync API behavior (known-people list pulled to the phone cache); delete-by-person; SQLite (Room) mirror on the phone for settings, people cache and the alert log.
- **Outputs:** Tables, repositories, sync format.
- **Internal components:** Tables `users`, `known_people`, `face_embeddings(vector)`, `saved_places(vector)`, `settings`, `request_history`, `event_log` *(proposed names)*; migration tool; Room DAOs.
- **APIs / interfaces:** Repository interfaces used by `/faces/*`, `/describe` history; Room DAOs for Android.
- **Data structures:** Embedding length fixed once the face model is chosen (store model version beside each vector).
- **Dependencies:** WS-02; face model choice (WS-08) for vector size; schema design can start immediately.
- **Consumers:** WS-22, WS-08, WS-03.
- **Error cases:** Wrong embedding dimension, migration failure, orphaned embeddings, sync conflicts, DB unavailable (backend falls back and phone uses cache).
- **Testing:** Migration up/down, similarity query tests, delete-cascade test, Room tests.
- **Performance:** Index on vectors; the phone matches locally, so the DB is not on the alert path.
- **Security / Privacy:** Embeddings encrypted, never face images; opt-in timestamp stored; hard delete on request; no image columns exist.
- **Definition of Done:** Schema migrated by script in Docker; enroll, match and delete verified; phone cache syncs and works offline.

### WS-24 Datasets and model evaluation

- **Purpose / Why:** Models are pretrained and lightly fine-tuned; local data and honest evaluation decide whether features ship.
- **Inputs:** Own Egyptian street videos (daylight first, then low light), banknote photos, volunteer faces, signs/menus/medicine boxes, mock screens.
- **Processing:** Labeling guidelines; capture with the chest mount; label in Roboflow or CVAT; train and track in Weights & Biases; fixed held-out sets per task; accuracy and latency reports per device.
- **Outputs:** `datasets/` scripts and guidelines (data itself outside Git), evaluation reports, model manifests.
- **Internal components:** Labeling guide, collection protocol, split policy, eval harness, report template.
- **APIs / interfaces:** `datasets/download.sh`, `computer_vision/export/` scripts, eval CLI.
- **Data structures:** Dataset manifest (source, consent, split, licence).
- **Dependencies:** Phone and mount from WS-01 week 1; a COCO baseline lets CV start before local data exists.
- **Consumers:** WS-05, 07, 08, 09, 26.
- **Error cases:** Biased or tiny data, label drift, leakage between train and test, bystanders' faces in street footage.
- **Testing:** Held-out sets frozen early so numbers stay comparable.
- **Performance:** n/a (offline work).
- **Security / Privacy:** Street footage and volunteer data stored privately, not in Git; consent recorded; bystander handling rule in the guidelines.
- **Definition of Done:** Frozen eval sets for vehicles, text and banknotes; reports with accuracy and latency per task; fine-tuning runs reproducible.

### WS-25 System integration

- **Purpose / Why:** Most failures will be at seams (CV to engine, voice to action, Android to backend), so the seams have an owner.
- **Inputs:** Merged modules on `develop`, contracts, fixtures.
- **Processing:** Weekly integration build; maintain an "integration matrix" (producer, consumer, contract version, status); run seam tests with real and fake modules swapped one at a time; triage integration bugs.
- **Outputs:** Green integration matrix, integration test suite, weekly demo build.
- **Internal components:** Seam tests, build scripts, integration board in Notion.
- **APIs / interfaces:** The shared interfaces listed in WS-03.
- **Data structures:** Integration matrix rows (Notion).
- **Dependencies:** All module workstreams, in a rolling way: each seam is tested as soon as both sides exist, real or fake.
- **Consumers:** WS-26, WS-30.
- **Error cases:** Contract drift, version mismatch, two modules each assuming the other owns a behavior.
- **Testing:** CV to engine event contract; Android to backend against the mock then real; voice to action on text and audio.
- **Performance:** End-to-end latency budget per flow tracked.
- **Security / Privacy:** Verify consent gates and no-logging rules at the seams.
- **Definition of Done:** All seams in the matrix green on a build used for the Gate 3 demo.

### WS-26 Testing, QA and field tests

- **Purpose / Why:** Prove each feature and protect the demo; the alert log from field tests tunes false alarms and misses.
- **Inputs:** Builds, fixtures, test plan, volunteers (written consent).
- **Processing:** Test plan mapped to every feature; unit, model, integration, API, screen, failure-mode (no internet, empty frame, VLM timeout, low battery, headset disconnected), E2E demo run 3 times back-to-back, regression checks per build, volunteer field tests on pre-tested routes.
- **Outputs:** Test evidence in the Notion Testing database, field-test report, bug reports.
- **Internal components:** `tests/` (E2E, field logs, mock screens), device matrix, failure-mode checklist, consent forms.
- **APIs / interfaces:** Test fixtures from WS-02; Notion Evidence template.
- **Data structures:** Test case, test run, evidence attachment.
- **Dependencies:** Rolling; test design starts in week 1, volunteer tests only after Gate 3.
- **Consumers:** Phase gates, WS-30.
- **Error cases:** Flaky screen tests due to OS or app updates, volunteer safety, insufficient logs.
- **Testing:** The workstream is testing; its own check is that every Must feature has at least one test with attached evidence.
- **Performance:** Runs the 30-minute session test jointly with WS-27.
- **Security / Privacy:** Written consent, route pre-testing, never near real traffic for the vehicle scenario; logs anonymized.
- **Definition of Done:** Gate evidence complete; field-test report; E2E run recorded three times.

### WS-27 Performance, battery and thermal

- **Purpose / Why:** A prototype that overheats in 15 minutes cannot demo; the spec requires a stable 30-minute session.
- **Inputs:** Test phone, benchmark app, thermal and battery logs.
- **Processing:** Week-1/2 device benchmark (the spec says month 1; compressed), per-module latency budgets, FPS governor tuning (idle vs moving), delegate choice (GPU/NPU/CPU), thermal and battery logging every minute, power-bank test.
- **Outputs:** Performance budget table, battery and thermal report for a 30-minute session.
- **Internal components:** `PerfLogger`, thermal listener, per-module timers.
- **APIs / interfaces:** `PerfLogger.record(metric)`; debug overlay.
- **Data structures:** Time-series CSV of FPS, ms/frame, temperature, battery.
- **Dependencies:** WS-04, WS-05 first; later all modules.
- **Consumers:** WS-04 FpsGovernor, WS-05, WS-10, WS-11.
- **Error cases:** Throttling mid-demo, thermal shutdown, background kill by battery optimizer.
- **Testing:** 30-minute continuous run with camera, wake word and engine on.
- **Performance:** Targets from the spec: detection 15-25 FPS, tracking under 5 ms, OCR 0.1-0.4 s, face 50-150 ms, wake word under 200 ms.
- **Security / Privacy:** Logs hold no content.
- **Definition of Done:** Report shows stable temperature and acceptable battery drain for 30 minutes, or documented mitigations.

### WS-28 Security, privacy and ethics

- **Purpose / Why:** Camera, microphone, screen and face data are sensitive; the spec makes consent, opt-in and no-storage defaults.
- **Inputs:** Every data flow in Section 2.
- **Processing:** Consent notices (first run, before cloud frames/screen content); opt-in face enrollment and deletion; no image storage by default; deny-list and `FLAG_SECURE` rules; key and secret handling with spending limits; licence audit (AGPL detector, face model licences); wording rules (research prototype, assistive aid, no safety guarantee); ethics and privacy note.
- **Outputs:** Privacy note, consent texts, licence table, threat checklist.
- **Internal components:** `ConsentManager` (Android), policy docs, secret scan.
- **APIs / interfaces:** `Consent.isGranted(scope)` gate used by every cloud call.
- **Data structures:** Consent record (scope, timestamp).
- **Dependencies:** WS-02 data flow definitions; reviews every other workstream.
- **Consumers:** All.
- **Error cases:** A feature sends data without consent, logs include content, a key leaks, a licence conflict discovered late.
- **Testing:** Checklist review per release; unit test that cloud clients refuse without consent.
- **Performance:** n/a.
- **Security / Privacy:** (this workstream)
- **Definition of Done:** Consent gates tested; licence table complete; privacy and ethics note written; no secrets in Git history.

### WS-29 Documentation

- **Purpose / Why:** Required deliverables and the onboarding path for a new developer.
- **Inputs:** Decisions, contracts, test results.
- **Processing:** Maintain `docs/architecture.md`, `docs/api-contract.md`, `docs/screen-capabilities.md`, `docs/demo-script.md`, model README, permissions/sideload guide, README; feed engineering facts to the thesis authors.
- **Outputs:** Docs in the repo, reviewed.
- **Internal components:** Doc templates, owner per file.
- **APIs / interfaces:** n/a.
- **Data structures:** n/a.
- **Dependencies:** Rolling; each doc is updated by the workstream that owns the topic.
- **Consumers:** Everyone, graders, future contributors.
- **Error cases:** Docs drift from code.
- **Testing:** A teammate follows the README on a clean machine.
- **Performance / Security:** No secrets or private data in docs.
- **Definition of Done:** All five required docs reviewed and current at Gate 4.

### WS-30 Final demo and release

- **Purpose / Why:** The graded outcome: the 8-10 minute scripted demo with backups.
- **Inputs:** Frozen build, demo scenarios 1-10, backup plan items.
- **Processing:** Demo script; controlled stations and a pre-tested route; recorded backup video per step; projector view of the alert log; second charged phone, hotspot, mock-mode screens; three full rehearsals; feature freeze; `v1.0` tag.
- **Outputs:** Signed APK, `v1.0`, demo video set, script.
- **Internal components:** Demo checklist, fallback matrix per scenario.
- **APIs / interfaces:** n/a.
- **Data structures:** n/a.
- **Dependencies:** All Must workstreams at Gate 3, WS-26 evidence.
- **Consumers:** Evaluators, the team.
- **Error cases:** No venue internet, device overheats, OS update breaks screen reading, wake word fails in a loud room.
- **Testing:** Three consecutive full runs with no intervention.
- **Performance:** Demo uses a power bank.
- **Security / Privacy:** Never stage the vehicle scenario near real traffic; only consenting people appear.
- **Definition of Done:** Three clean rehearsals recorded; backups ready; `v1.0` tagged from `main`.

### Backend capability breakdown (all 9 endpoints)

Request and response field names below are *(proposed)*; the spec fixes only the endpoint list, the purposes, "bounded response and a fallback message on timeout", and the event schema. Layering for every endpoint: **router** (validate, authenticate) then **service** (business rules, context, caching, history) then **provider** (VLM, Maps, DB, push) behind an interface.

**Shared rules for every endpoint**

- **Authentication:** `X-API-Key` header per install plus HTTPS; `/health` is open. Rate limit per key.
- **Validation:** Pydantic models; images are base64 JPEG capped at 768 px and a byte limit; text fields have length caps; unknown fields rejected; invalid payload gives `ok:false` with a spoken-safe message, never a stack trace.
- **Context:** the app may send a small `context` object *(mode, navigation active, language, recent event summary)*; the backend itself is stateless apart from history, faces and the route cache.
- **Timeouts and failure:** server-side provider timeout, then `ApiEnvelope{ok:true, fallback:true, message:"..."}`; client timeout is the server timeout plus one second; the app speaks the fallback and never retries silently more than once.
- **Logging:** request ID, endpoint, status, latency, provider, token usage; payload content (images, screen text, embeddings) is never logged.
- **Mock mode:** `MOCK_MODE=true` routes every provider call to `MockProvider` with deterministic fixtures; the header `X-Mock-Scenario: ok|timeout|error|empty` injects failures so Android error states can be tested without breaking anything.
- **Testing:** pytest + httpx per endpoint: valid, invalid, oversize, timeout, unauthorized, mock scenarios.

| Endpoint | Who calls it, and why | Request | Service, provider and AI | Response | Failure handling | Android integration |
| --- | --- | --- | --- | --- | --- | --- |
| `GET /health` | App at start and on a timer; deploy monitor. Tells the app whether cloud intents are possible | none | Checks process, DB ping, provider config present; never calls a paid API | `{status, version, mock_mode}` | Slow or failing health marks the backend unreachable and the context engine goes to offline behavior | `ConnectivityMonitor` and `ApiClient` |
| `POST /describe` | Action router on "what's around me" | `image`, optional `context`, `language` | `DescribeService` builds the `describe` prompt, VLM provider, optional cache, stores a text-only history row | `{description}` of one or two short sentences, uncertainty phrasing | Timeout gives "I can't describe the scene right now"; empty provider output also falls back | `DescribeClient`, then `SpeechRequest` at Info, user-requested |
| `POST /ask` | Action router on look-and-ask | `image`, `question`, `context` | `AskService`, `ask` prompt, VLM provider, hallucination-safe wording | `{answer}` | Same as `/describe`; over-long question rejected | `AskClient` |
| `POST /screen/summarize` | `ScreenAi` ladder rung 3 | `package`, `screen_type`, `text` (tree text), optional `image`, `question` | `ScreenService` re-checks the deny-list, assembles the `screen_summarize` prompt, VLM provider | `{summary}` | Timeout: the app speaks the raw tree text if it has it, else a fallback message; blocked package: refuses with a spoken reason | `ScreenSummaryClient` |
| `POST /intent` | Intent router when no local rule matches and the phone is online | `text`, `context` | `IntentService`, `intent` prompt on a small LLM call, strict whitelist validation of returned steps (same rules as `ActionPlanValidator`; pytest reuses `contracts/fixtures/plans/valid/` and `tests/fixtures/plans/untrusted/`)| `{steps[], requires_confirmation, clarification?}` | Invalid or unknown steps are dropped; no valid step gives a clarification question | `IntentApiClient`; offline never reaches it |
| `POST /faces/enroll` | Enrollment flow after spoken consent | `display_name`, `embedding`, `model_version`, `consent:true`; any image field is rejected | `FaceService`, repository write to pgvector, no VLM | `{person_id}` | Dimension mismatch or missing consent rejected; DB failure gives a clear fallback and nothing partial is stored | `EnrollmentFlow` then local cache refresh |
| `POST /faces/match` | Phone for verification or when the local cache is cold | `embedding` | pgvector cosine search, server-side threshold, default result is unknown | `{match: name or null, similarity}` | Fallback is "unknown"; never guesses | `FaceMatcher` (local first, backend second) |
| `GET /route` | `Navigation.start` | `origin`, `destination` (coordinates or text), `mode=walking` | `RouteService` checks the cache (key: rounded origin plus destination), calls the Maps provider, may resolve text destinations | `{steps[], polyline, distance, duration}` | On provider failure return a cached route if present, else a fallback message; quota guard | `RouteClient` |
| `POST /sos` | `SosController` when online, optional | `lat`, `lon`, `accuracy`, `ts`, `contact_ids` | `SosService` pushes to emergency contacts through a provider the spec does not name (Nice-tier) | `{accepted}` | Never blocks the local SMS path; failures are logged and swallowed | `SosApiClient` |

### Android architecture

**Shape.** One Android app module (split into Gradle modules later only if build time hurts), layered as: UI (Compose, minimal: permissions, settings, alert log, debug) over the `AssistantService` foreground service, which owns a set of feature modules behind Kotlin interfaces (camera, detector, engine, voice, screen, navigation, SOS), over a data layer (Room/SQLite, `ApiClient` over OkHttp *(proposed)*, settings). Dependencies are injected so any module can be swapped for its fake.

**How the pieces communicate**

| From | To | Mechanism | Rule |
| --- | --- | --- | --- |
| `FrameSource` | Detector, OCR, Face, Vision capture | Kotlin `Flow<Frame>` with drop-oldest | Slow consumers never back-pressure the camera |
| Detector, Tracker | Engine | `EngineInput.submit(Event)` | Only `Event` objects cross this seam |
| Voice, Screen, Navigation, Notifications, AI answers | Engine | `EngineInput.submit(SpeechCandidate)` | Nobody speaks directly |
| Engine | `SpeechOutput` (TTS) | `SpeechRequest` and utterance callbacks | The single place speech happens |
| Wake word, STT | `IntentRouter` | `Transcript` flow | Emergency keywords bypass to SOS |
| `IntentRouter` | Modules | `ActionPlan` executed by `ActionRouter` | Whitelisted steps only; every plan passes `ActionPlanValidator` first |

**Camera lifecycle.** CameraX is bound to the service (not an activity) with the `camera` foreground-service type so it keeps running with the screen off; it starts after permission and consent, pauses on thermal or low battery, and releases on service stop or headset-less idle timeout.

**Permissions and special access.** Runtime: camera, microphone, fine location, send SMS (and call if used), post notifications. Special, enabled by hand on a sideloaded build: Accessibility service, notification listener, "restricted settings" unlock, battery-optimization exemption; MediaProjection consent per session if that capture path is chosen (TD-004). The onboarding is spoken and also shown on screen for a sighted helper, and a `docs/` permissions guide ships with the APK.

**Background behavior.** One persistent foreground service with the types camera, microphone, location (and mediaProjection when capturing); partial wake lock only while listening; the app must survive screen-off and a 30-minute session; restart policy documented.

**Error handling.** Each module exposes a health state to `AppState`; the engine turns a degraded state into a short spoken notice rate-limited by cooldown, for example "camera unavailable" or "headset disconnected"; unhandled exceptions are caught at module boundaries and logged, never allowed to kill the service.

**Offline behavior.** `ConnectivityMonitor` plus `/health`. Offline: detection, tracking, engine, wake word, STT (on-device), TTS, OCR (Latin), SOS SMS, loaded navigation continue. Cloud intents, scene description, look-and-ask, screen summaries and new routes are refused with one spoken sentence.

**Logging.** Local rolling log with the decision trail; alert-log screen shows spoken and suppressed events with the reason; export for field-test analysis (no frames or screen text inside).

**Testing.** JVM unit tests for engine, intent rules, route tracking, SOS state machine; instrumented tests for permission flow and service restart; fake modules for end-to-end runs on an emulator; real-device tests on the Android test phone only for camera, audio, accessibility and capture.

## 5. Priority engine deep dive (WS-16)

The engine is the only component allowed to cause speech, so it is specified as a pure, clock-injected Kotlin library (`engine/`) with no Android dependencies. Level names (Critical, Warning, Info), the event schema and the cooldown principle come from the spec. **All numbers below are proposed starting values**, stored in one `engine_config.json` and tuned from field-test logs.

**Lifecycle**

```
Detection/Result
 -> Event (spec schema; built by the tracker, or by voice/screen/nav/notification modules)
 -> Gate 1: confidence >= class minimum?              no -> drop (reason: low_confidence)
 -> Risk score (type base x band x side x confidence x approach bonus)
 -> Level: Critical | Warning | Info
 -> Gate 2: level vs current threshold (context-dependent)    below -> suppress (below_threshold)
 -> Gate 3: cooldown / duplicate / already speaking           hit   -> suppress (cooldown | duplicate)
 -> Merge window: combine compatible events into one utterance
 -> Decision: SPEAK NOW (Critical, interrupts) | QUEUE (next gap) | HOLD (Info until idle or asked) | IGNORE
 -> SpeechRequest -> SpeechOutput -> utterance callbacks
 -> Cooldown timer starts/updates; decision record written to the alert log
```

**Event schema.** Exactly the spec fields: `id, source, type, label, side, distance_band, confidence, risk, timestamp`. `source` is `camera` for CV events; the engine also accepts other sources *(proposed)*: `voice`, `screen`, `navigation`, `notification`, `system`. Event types *(proposed vocabulary)*: `vehicle_approaching` (from the spec), `obstacle_ahead`, `person_close`, `person_known`, `person_unknown`, `scene_answer`, `text_result`, `screen_summary`, `notification`, `nav_instruction`, `system_notice`, `sos_countdown`. `distance_band` is `near | medium | far` and `side` is `left | center | right`; hazards are never described in meters.

**Risk score (0-100) and levels.** `risk = base(type, label) x band_weight x side_weight x confidence x approach_bonus`, capped at 100.

| Factor | Starting values |
| --- | --- |
| Base | `vehicle_approaching` 95, obstacle ahead 60, person close 55, static vehicle 50, known/unknown person 30, answers and text 20 |
| Band weight | near 1.0, medium 0.7, far 0.3 |
| Side weight | center 1.0, left/right 0.85 (vehicle approaching ignores side for severity but keeps it for the message) |
| Confidence | Raw detector confidence; below the class minimum the event is dropped before scoring |
| Approach bonus | 1.0 normally; 1.1 when box growth persisted over the minimum window |

| Level | Rule | Meaning (from the spec) |
| --- | --- | --- |
| Critical | risk 80 or more, **and** confidence 0.6 or more **and** persistence over the minimum frames; SOS countdown is always Critical | Interrupts anything |
| Warning | risk 45-79 | Spoken at the next gap |
| Info | below 45, or any non-hazard result | Spoken only when asked, or when the user is idle and stationary |

A false Critical costs trust more than a delayed one, so Critical requires persistence, not a single frame.

**Current threshold (context-dependent).** Moving: speak Warning and above. Stationary and idle: Info may also speak. Crowded scene (many distinct events within a window): raise the Warning floor so only the top items speak. Navigation active: nav instructions are exempt from the floor. A user request lifts any threshold for its own answer.

**Cooldowns, duplicates and merging.**

| Rule | Behavior |
| --- | --- |
| Per-key cooldown | Key is type + label + side + band. Starting values: Critical 3 s, Warning 8 s, Info 30 s |
| Per-track rule | A tracked object is announced once, and again only if its band escalates (far to medium to near), its side changes, or it was lost for more than a reset time and returns |
| Duplicate suppression | An event whose key is already being spoken or is already in the queue is dropped; a newer event with the same key replaces the queued one |
| Merge window | Events inside a short window combine into one utterance, at most two items, highest risk first (for example "Person on your left, chair ahead") |
| Stale expiry | Warning older than about 3 s is dropped, because the scene has changed; Info expires after about 10 s; navigation instructions expire when the next one is due |

**Speech queue and interruptions.** One queue, one speaker. Order: level, then risk, then age. Maximum depth 3; overflow drops the lowest-priority item and logs it.

| Situation | Rule |
| --- | --- |
| Critical arrives while anything is speaking | Flush the speaker immediately and speak the Critical; the interrupted utterance is re-queued from its current sentence if it was user-requested or navigation, dropped if it was Info or a scene detail |
| Warning arrives during Info or a long reading | Waits for the next gap, defined as a sentence boundary for long text and the end of the utterance otherwise |
| Two Criticals | Highest risk first; the second follows without a gap if still valid |
| User request (voice command answer) | Bypasses threshold and idle checks, queued as high-priority Info, does not interrupt a Warning already speaking |
| Wake word fires while speaking | Info is paused or stopped so the microphone is clean; a Critical in progress finishes first, then listening starts |
| Critical arrives while the app is listening | Listening aborts, the Critical speaks, and the app re-prompts |
| SOS countdown | Only countdown speech and "cancel" handling are allowed; every other level is held |
| Navigation instruction due | Queued as time-sensitive Info; a Warning waits for the gap between instructions; a Critical preempts and the missed instruction is re-issued afterward if still relevant. Navigation may use meters from route data; hazards never do |
| Notification arrives | Info; held until asked or until the user is idle and stationary |
| Offline or degraded module | One short spoken notice, rate-limited by cooldown |

**Voice interaction.** The engine exposes `updateContext(listening, speaking, moving, navigationActive, offline, sosActive)` so voice and navigation never need to know each other's rules.

**Logging.** Every event produces a decision record, including events that are never spoken: event id, timestamp, source, type, label, side, band, confidence, risk score, level, threshold at that moment, context snapshot, decision (`spoken`, `queued`, `held`, `suppressed`, `dropped`, `interrupted`, `merged`, `expired`), reason code (`low_confidence`, `below_threshold`, `cooldown`, `duplicate`, `merged`, `expired`, `preempted`, `queue_overflow`), utterance id and the event-to-speech latency. The alert-log screen (demo scenario 3) filters by decision and shows suppression reasons.

**Interfaces.** `EngineInput.submit(Event)`, `submit(SpeechCandidate)`, `updateContext(...)`, and `SpeechOutput.speak(SpeechRequest)` plus utterance callbacks inbound. The engine never touches the camera, the microphone or the network.

**Testing.** Deterministic injected clock; scenario files (a timeline of events with expected decisions) as golden tests; properties checked on every scenario: never two simultaneous utterances, a Critical is never delayed by a lower level, no key repeats inside its cooldown unless an escalation rule fires, queue never exceeds depth; replay of real alert logs offline to tune thresholds. **Done when** every rule in the tables has at least one scenario test, the config is hot-reloadable for field tuning, and the alert-log view shows suppressed events with reasons.

## 6. Team ownership

Every person is primary on exactly five workstreams, secondary on several, and reviewer on three to six, so nobody is isolated and nobody is a single point of failure. The reviewer is never the primary or secondary owner, which satisfies the spec rule that PRs are reviewed by someone from another role. Short names: Mostafa S. (Sayed), Mostafa O. (Omar/Moko), Loay, Marwan, Boda, Mahmoud.

| WS | Workstream | Primary | Supporting | Reviewer |
| --- | --- | --- | --- | --- |
| 01 | Repo, tooling, CI, release | Mostafa S. | Loay | Mahmoud |
| 02 | Contracts and event schema | Mostafa S. | Marwan, Mahmoud | Loay |
| 03 | Android app core | Mostafa S. | Loay | Boda |
| 04 | Camera pipeline | Loay | Mostafa S. | Mostafa O. |
| 05 | Object detection | Mostafa O. | Marwan | Mahmoud |
| 06 | Tracking and spatial estimation | Mostafa O. | Marwan | Loay |
| 07 | OCR, QR, color | Marwan | Mostafa O. | Boda |
| 08 | Face recognition | Marwan | Mahmoud | Mostafa S. |
| 09 | Currency recognition | Mahmoud | Marwan | Mostafa O. |
| 10 | Relative depth (optional) | Mostafa O. | none | Marwan |
| 11 | Wake word and audio capture | Boda | Loay | Mahmoud |
| 12 | Speech-to-text | Boda | Mahmoud | Loay |
| 13 | TTS and audio output | Boda | Loay | Marwan |
| 14 | Intent and voice flow | Boda | Mahmoud | Mostafa S. |
| 15 | VLM/LLM prompts and providers | Mahmoud | Boda | Mostafa S. |
| 16 | Priority and context engine | Marwan | Mostafa O. | Boda |
| 17 | AccessibilityService and screen reading | Loay | Marwan | Mostafa S. |
| 18 | Screen capture and screen AI | Loay | Marwan, Mahmoud | Boda |
| 19 | Notification reading | Marwan | Loay | Mahmoud |
| 20 | Navigation | Mostafa O. | Boda | Marwan |
| 21 | SOS and emergency | Boda | Mostafa O. | Loay |
| 22 | Backend core and deployment | Mostafa S. | Mahmoud | Marwan |
| 23 | Database and known-people data | Mahmoud | Mostafa S. | Mostafa O. |
| 24 | Datasets and model evaluation | Mahmoud | Mostafa O., Loay | Marwan |
| 25 | System integration | Mostafa S. | Loay | Mahmoud |
| 26 | Testing, QA, field tests | Loay | Mahmoud | Boda |
| 27 | Performance, battery, thermal | Mostafa O. | Loay | Mostafa S. |
| 28 | Security, privacy, ethics | Marwan | Mahmoud | Mostafa S. |
| 29 | Documentation | Mahmoud | Loay | Marwan |
| 30 | Final demo and release | Loay | Mostafa S. | Mahmoud |

**Why these assignments.** They follow each person's Section 7 skills (Mostafa S.: Android, FastAPI, Docker, integration; Loay: AccessibilityService, MediaProjection, CameraX; Mostafa O.: YOLO, tracking, distance, GPS and navigation; Marwan: OCR, screen AI and, from Sections 8 and 13, the engine; Boda: wake word, STT, TTS, intent, emergency commands; Mahmoud: AI services, database, datasets). Two judgment calls: the **engine** goes to Marwan because Sections 8 and 13 give him `feature/engine-*` and Section 7 names no engine owner; **navigation** goes to Mostafa O. per Section 7 but starts in week 4, after the detector baseline is stable, with Boda owning the voice side so the load stays workable.

**Collaboration pairs that matter most** (these seams cause most integration bugs): Mostafa O. and Marwan on tracker to engine events; Marwan and Boda on engine to speech; Loay and Mahmoud on screen ladder to `/screen/summarize`; Mostafa S. and Mahmoud on API contract and providers; Boda and Mostafa O. on navigation voice and SOS.

**Branch prefix mapping** (spec Sec. 8, adjusted to the roles above): `feature/cv-*` Mostafa O. and Marwan, `feature/voice-*` Boda, `feature/backend-*` Mostafa S. and Mahmoud, `feature/android-*` Mostafa S. and Loay, `feature/data-*` Mahmoud, `feature/engine-*` Marwan, `feature/screen-*` *(proposed)* Loay, `feature/test-*` Loay. Every PR names its Notion task ID.

## 7. Parallel development strategy

All six people can start meaningful work on day 1 because every cross-module dependency has a stand-in (interface, fake, fixture or contract) that ships in week 1 to 2. Dependencies are modeled between tasks, never between people or whole workstreams.

**Four rules**

1. **Interface first.** The first PR of every module contains its interface, its fake and a contract test. The real implementation replaces the fake later without changing callers.
2. **Fixtures are deliverables.** Detection JSON, event timelines, `ScreenSnapshot` JSON, canned API responses, GPX routes, recorded audio and field videos live in `contracts/` and `tests/fixtures/` *(proposed)* and are reviewed like code.
3. **Swap one seam at a time.** Integration replaces a single fake with the real component per build, so a failure has one suspect.
4. **Contract change = Technical Decision.** After the week-2 freeze, any change to the event schema or API needs a decision entry and a version bump.

**Stand-ins for every dependency**

| Consumer needs | From | Stand-in (available week 1-2) | Replaced by real |
| --- | --- | --- | --- |
| Tracker and engine need detections | WS-05 detector | `FakeDetector` replaying JSON; COCO-pretrained baseline on laptop | Week 3-4 fine-tuned model on phone |
| Engine needs events | WS-06 tracker | Hand-written event timelines (JSON scenarios) | Week 3-4 live events |
| Engine needs to speak | WS-13 TTS | `FakeSpeaker` that logs and records | Week 2 Android TTS |
| Android needs the backend | WS-22 | Mock backend for all 9 endpoints with fixtures and `X-Mock-Scenario` failure injection | Weeks 3-6 real providers |
| Backend needs the VLM | WS-15 | `MockProvider`, deterministic | Week 3 real provider behind `VLM_PROVIDER` |
| Intent router needs speech text | WS-12 STT | `FakeSttEngine` (typed or file text) | Week 3-4 real STT |
| Voice flow needs a trigger | WS-11 wake word | `FakeWakeWord` on a debug button | Week 4 real wake word |
| Screen ladder needs the service | WS-17 | `ScreenSnapshot` fixtures, mock WhatsApp-style screen app | Week 4-6 real screens |
| Screen AI needs screenshots | WS-18 | Image fixtures and secure/blank cases | Week 5-6 live capture |
| Navigation needs GPS and routes | WS-20 / WS-22 | GPX replay and a stored route fixture | Week 5-7 live GPS and `/route` |
| SOS needs SMS, GPS, speaker | WS-21 | Fake SMS sender, fake location, fake speaker; state machine unit tests | Week 6-7 real hardware path |
| Faces need crops and a DB | WS-08 / WS-23 | Fixture crops; Postgres schema independent of the model | Week 6-7 on-device model |
| Currency needs the dataset | WS-24 | Printed-note photos and a laptop baseline classifier | Week 7-8 final model |
| Notifications need the listener | WS-19 | `FakeNotificationSource` | Week 5-6 real WhatsApp |
| Camera needs a phone | WS-04 | `VideoFileFrameSource` replaying recorded videos | Day 1 to week 2 when the phone arrives |
| Testers need features | all | Test plan, fixtures, golden sets written against the contracts | Rolling |

**What each person does in week 1-2 without waiting on anyone**

| Person | Start immediately |
| --- | --- |
| Mostafa S. | Repo, CI, branch rules (WS-01); contract draft v0 (WS-02); mock backend with fixtures (WS-22); Android shell with module interfaces and fakes (WS-03) |
| Loay | Order the Android phones and mount on day 1; record field videos (an iPhone is fine for video collection); `VideoFileFrameSource` and CameraX prototype (WS-04); AccessibilityService spike and the mock messaging screen app (WS-17) |
| Mostafa O. | COCO baseline on laptop, `FakeDetector` JSON, LiteRT export spike, AGPL research for TD-001 (WS-05); tracker and band logic on JSON detections (WS-06) |
| Marwan | Engine core in pure Kotlin with the scenario test harness (WS-16); event schema with Mostafa O. (WS-02); OCR evaluation harness on image fixtures (WS-07) |
| Boda | Command corpus and rule parser as JVM unit tests (WS-14); `FakeSttEngine`; STT and TTS prototypes; wake-word library bake-off on recorded audio for TD-002 (WS-11) |
| Mahmoud | Postgres + pgvector schema in Docker (WS-23); labeling guidelines and collection protocol (WS-24); prompt drafts, golden set, `VlmProvider` and `MockProvider` (WS-15) |

**The only real hard blockers, and how each is minimized**

- **Android test phone** (needed for real inference speed, accessibility, capture, wake word, thermal): buy on day 1; until it arrives everything runs against fakes, an emulator or a borrowed Android phone. Tracked as blocker B-001 in Notion.
- **API keys and spending limits:** create accounts and set limits in week 1; mock mode covers development meanwhile.
- **Detector and wake-word licence/library choice (TD-001, TD-002):** deadline before week 3; both candidates are evaluated in parallel, so the decision delays nothing else.
- **Local training data:** collection starts in week 1 with any phone camera; fine-tuning starts when the first labeled set exists, and the pretrained baseline serves until then.

**Integration ladder (one seam per rung, each owned by WS-25)**

1. Contracts validate in Kotlin and Python.
2. Android calls the mock backend, including failure scenarios.
3. Frame source, detector, tracker, engine, speaker on the phone: the Gate 1 and Gate 2 claim "camera to speech works".
4. Wake word, STT, intent, action router with fakes then real.
5. Real backend and VLM behind the same client.
6. Screen ladder on fixtures, then on the tested apps.
7. Navigation on GPX, then on the pre-tested route; SOS on fakes, then to a team phone.
8. Full ten-scenario demo build.

## 8. Three-month scope classification

The spec's Must tier already fills 12 weeks for six people, so the Should tier is protected only if Must work lands by Gate 3 (end of week 9) and weeks 10 to 12 stay feature-free, as the spec's freeze rule requires. The core demo is scenarios 1 to 4 and 7 to 10 (wake and look, obstacle, priority engine, vehicle, navigation, screen, SOS, offline); reading and known-person fill the rest.

| Feature | Tier | Minimum version kept in Must | Workstream | Cut or defer trigger |
| --- | --- | --- | --- | --- |
| Obstacle, person, vehicle detection with near/medium/far and side | Must | One fine-tuned model, daylight, indoor demo stations | WS-05, 06 | Never cut |
| Approaching-vehicle alert (box growth, no speed claim) | Must | Works on a toy car or recorded video, no cloud | WS-06 | Never cut |
| Priority engine, cooldowns, speech queue, alert log | Must | Everything in the deep dive | WS-16 | Never cut |
| Wake word, STT (Android), TTS (Android), rule-based intent | Must | English commands only | WS-11 to 14 | Never cut |
| Scene description and look-and-ask | Must | `/describe` and `/ask` with one VLM provider | WS-15, 22 | Never cut |
| OCR for signs, menus, documents, medicine labels | Must | ML Kit Latin, on demand | WS-07 | Cloud fallback for Arabic can slip |
| Walking turn-by-turn on a pre-tested route | Must | One tested route, voice start, announcements, one reroute | WS-20 | Cues beyond voice and vibration slip |
| SOS with countdown, SMS and location | Must | Voice trigger, cancel, SMS to team phone | WS-21 | Never cut |
| Open app, read screen, scroll, back (Accessibility) | Must | 2 tested apps plus mock screen | WS-17 | Third app slips |
| Screen summary via the ladder and `/screen/summarize` | Must | Tree text then VLM; OCR rung if time | WS-18 | OCR rung slips before VLM rung |
| WhatsApp and notification reading (read-only) | Must | Real WhatsApp notification read on request | WS-19 | Never cut |
| Backend: `/health`, `/describe`, `/ask`, `/screen/summarize`, `/intent`, `/route`; mock mode; Docker | Must | As specified | WS-22 | Never cut |
| Basic vibration pulse on hazard alerts | Must | One pattern each for warning and critical | WS-20, 16 | Distinct left/right patterns slip |
| Basic motion state (stationary vs moving) | Must | Needed for FPS scaling and the Info idle rule | WS-06, 27 | Path awareness slips |
| Battery and thermal report, field-test report, ethics note | Must | 30-minute session | WS-26, 27, 28 | Never cut |
| Signed APK, permissions guide, docs, demo script, backup video | Must | As listed in Section 21 | WS-01, 29, 30 | Never cut |
| Known-person recognition, unknown announced generically | Should | Enroll 2-3 volunteers, on-device match | WS-08, 23 | Decide at Gate 2; cut if the embedding model or consent flow is not ready |
| `/faces/enroll`, `/faces/match` | Should | Follow the face feature | WS-22, 23 | Cut with faces |
| Currency recognition (EGP) | Should | Few denominations if the dataset is thin, with an "unsure" path | WS-09 | Decide at Gate 2 on dataset size; cut at Gate 3 if below the accuracy bar |
| Reels and video content (one frame plus caption) | Should | Prompt variant on the screenshot path | WS-18 | Cut first when screen work slips |
| Night and low-light tuning | Should | One tuning pass | WS-05, 24 | Slips to documented limitation |
| Voice preferences (speech rate, verbosity) | Should | Rate and verbosity only | WS-13 | Slips |
| Distinct left/right haptic patterns | Should | Two extra patterns | WS-20 | Slips |
| Smart reading and summarization of long text | Should | Chunked reading of OCR text | WS-07, 15 | Slips |
| Path awareness (tracking-based) | Should | Tracker-derived hint only | WS-06 | Slips |
| QR, color, counting | Nice | QR and color only if free | WS-07 | Cut |
| Translation | Nice | A prompt variant in `/ask` | WS-15 | Cut |
| Caller identification, fall detection from IMU | Nice | None; false-alarm tuning does not fit | none | Deferred |
| Whisper-class STT upgrade, neural cloud TTS | Nice | None; the spec has no endpoint for either | WS-12, 13 | Deferred |
| `/sos` backend push to contacts | Nice | Local SMS is the real SOS | WS-21, 22 | Deferred |
| Relative depth (Depth Anything V2-small) | Nice | Only if Gate 2 shows the heuristic failing | WS-10 | Default off |
| Arabic and Egyptian-dialect speech and OCR | Nice | English demo | WS-07, 12 | Stretch only |
| Stair detection, indoor navigation, send/reply to messages, wearable, ToF/LiDAR, metric depth, vehicle speed | Future or excluded | None | none | Not planned |

**Go/no-go rhythm for the Should tier.** Gate 2 (week 5) reviews dataset size and face-model licence; Gate 3 (week 9) is the last point at which a Should feature may enter the demo; after Gate 3 there are no new features, only bug fixing, tuning, field tests and rehearsals.

**Cut order if the schedule slips**, from first to last: Nice items, Reels, night tuning and other polish, currency, faces, the OCR rung of the screen ladder, third tested app. Must items are never traded away; if a Must item is at risk it is escalated through a Phase Gate review, not silently reduced.

## 9. Notion workspace architecture

The workspace has 10 top-level pages and 8 databases. GitHub stays the source of truth for code and the contract docs; Notion is the source of truth for plan, status, decisions and evidence, and every Notion task links to its GitHub branch and PR.

```
AVN Workspace
|
+-- 1. Dashboard                      (no data of its own: linked views only)
+-- 2. Architecture
|     +-- System Overview             (Section 1-2 of this blueprint)
|     +-- Data Flows                  (physical / digital / voice)
|     +-- Priority Engine Spec        (engine deep dive)
|     +-- Contracts & Interfaces      (summary + links to docs/api-contract.md in GitHub)
|     +-- [DB] Workstreams            (30 rows; each page body = its 15-field breakdown)
|     +-- [DB] Integration Seams      (the Dependencies page for cross-workstream interfaces)
+-- 3. Team
|     +-- [DB] Team Members
|     +-- Ownership Matrix            (view of Workstreams: primary / supporting / reviewer)
|     +-- Working Agreements          (branching, PR review, merge cadence, DoD)
+-- 4. Roadmap
|     +-- [DB] Milestones & Gates     (Type = Milestone | Gate; timeline + gate checklists)
|     +-- Scope Tiers                 (Must / Should / Nice / Future + cut order)
+-- 5. Work
|     +-- [DB] Master Tasks           (all views in Section 12; task template in Section 13)
+-- 6. Bugs & Blockers
|     +-- [DB] Bugs & Blockers        (Type = Bug | Blocker | Risk)
+-- 7. Testing
|     +-- [DB] Tests & Evidence       (test cases with latest result + evidence)
|     +-- Field-Test Log              (sessions, volunteers, routes, consent status)
|     +-- Failure-Mode Checklist      (no internet, empty frame, VLM timeout, low battery, headset)
+-- 8. Decisions
|     +-- [DB] Technical Decisions    (TD-001 detector licence, TD-002 wake word, ...)
+-- 9. Documentation
|     +-- Docs Index                  (links to the 5 required GitHub docs)
|     +-- Runbooks                    (dev setup, sideload and permissions, demo day, release)
+-- 10. Final Demo
      +-- Demo Scenarios              (the 10 scenarios; each links tasks, tests, backup video)
      +-- Backup Plan Checklist
      +-- Rehearsal Log
```

**Changes I made to your page list**

- **Dependencies is not a separate database of tasks.** Task-to-task dependencies are a self-relation on Master Tasks (`Blocked by` and `Blocks`), which is what lets dependencies sit between tasks, not people. What is separate is **Integration Seams**, because a seam (producer, consumer, contract version, stand-in, real status) is a different object from a task and drives the parallel strategy.
- **Phase Gates and Milestones are one database** (`Milestones & Gates`, with a Type property). Gates are milestones with a pass/fail checklist, so two databases would duplicate every property.
- **Risks live in Bugs & Blockers** (Type = Risk) instead of a ninth database; the spec's risk table seeds it.
- **Testing and Evidence are one database**, so a gate can roll up passing tests directly.
- **Documentation stays thin**: the docs live in GitHub; Notion holds the index and the runbooks only.
- Added **Architecture** content as database pages (Workstreams) rather than a long static page, so tasks can relate to the exact workstream spec they came from.

**Dashboard contents** (all linked views, filtered, no manual upkeep): current gate and days left, gate checklist progress, my tasks, due this week, blocked tasks and blockers, critical-path tasks, integration seams not green, open Critical bugs, test pass rate by workstream, tasks waiting for review.

**Build path.** Section 10 gives every property so the databases can be created exactly as specified. In the next stage the task rows can be generated as an importable CSV per database, or written directly if a Notion connector is attached to the session.

## 10. Notion databases and properties

Eight databases. Property types use Notion's own names. `Unique ID` gives stable keys (AVN-123) that appear in branch names and PR titles. Views for each database are listed with it; the Master Tasks views are specified in Section 12.

### DB1. Master Tasks

Purpose: every unit of work, with enough fields for the later task generator to fill the Section 13 template.

| Property | Type | Why it exists |
| --- | --- | --- |
| Task ID | Unique ID (prefix AVN) | Stable reference in branches, PRs and bug reports |
| Title | Title | Imperative, one deliverable |
| Workstream | Relation to Workstreams | Links the task to the spec it implements |
| Milestone | Relation to Milestones & Gates | Says which milestone or gate the task feeds |
| Gate | Rollup (Milestone, parent gate name) | Lets any view filter by gate without double entry |
| Status | Status: Backlog, Ready, In progress, In review, Blocked, Done, Cancelled | Drives every board |
| Priority | Select: P0 critical path, P1, P2, P3 | Ordering inside a status |
| Critical path | Checkbox | The Critical view; independent of priority |
| Scope tier | Select: Must, Should, Nice | Protects the core demo and drives cuts |
| Task type | Select: Feature, Interface or fake, Integration, Spike, Dataset or model, Test, Docs, Infra, Bugfix | Filters, and signals fakes to build first |
| Owner | Person | Primary owner; powers My Tasks |
| Supporting | Person (multiple) | Pairing and cross-help |
| Reviewer | Person | Waiting-for-review view; never equals Owner |
| Planned start | Date | Timeline |
| Due | Date | Calendar, Today, This Week |
| Week | Formula (week number from project start date) | Groups work by W1 to W12 without manual typing |
| Days to due | Formula | Overdue and urgency flags |
| Estimate (h) | Number | Load per person per week |
| Blocked by | Relation (self) | Task-level dependency |
| Blocks | Relation (self, reverse of Blocked by) | Shows downstream impact |
| Seam | Relation to Integration Seams | Ties a task to the interface it builds or verifies |
| Works against | Select: Real, Fake or mock, Fixture | Records which stand-in the task uses so the swap to real is visible |
| Spec reference | Text | Spec section(s) and this blueprint's WS ID |
| Expected files | Text | Repo paths the task creates or edits |
| GitHub branch | Text | `feature/<area>-<description>` |
| GitHub PR | URL | Link to the PR |
| PR status | Select: None, Open, Changes requested, Approved, Merged | Waiting-for-review and merged tracking |
| Risk level | Select: Low, Medium, High | Template Risks field, triage |
| Evidence | Relation to Tests & Evidence | Proof for Definition of Done |
| Bugs and blockers | Relation to Bugs & Blockers | Defects found and blockers raised against the task |
| Decision | Relation to Technical Decisions | Decision the task depends on or produces |
| Open must | Formula (checkbox: tier is Must and status is not Done or Cancelled) | Feeds gate readiness rollups |
| Done date | Date | Burn-up; set when status becomes Done |
| Created, Last edited | Created time, Last edited time | Audit |

Body: the Section 13 template. Used by views: Master Board, My Tasks, Today, This Week, Calendar, Timeline, By Workstream, Blocked, Critical, Integration, Waiting for Review, Done, Phase Gates.

### DB2. Workstreams

Purpose: the 30 workstreams (WS-01 to WS-30). Each row's page body holds that workstream's 15-field breakdown from Section 4.

| Property | Type | Why it exists |
| --- | --- | --- |
| Name | Title | Includes the ID, for example "WS-16 Priority and context engine" |
| WS ID | Text | Sort key |
| Group | Select: Foundation, Vision, Voice, AI, Engine, Screen, Navigation and safety, Backend and data, Quality, Delivery | By Group board |
| Tier | Select: Must, Should, Nice | Matches Section 8 |
| Primary owner | Relation to Team Members | Ownership matrix |
| Supporting | Relation to Team Members (multiple) | Ownership matrix |
| Reviewer | Relation to Team Members | Ownership matrix |
| Status | Select: Not started, Active, At risk, Done | Dashboard health |
| Spec sections | Text | Traceability back to the roadmap |
| Stand-ins provided | Text | Names of the fakes and fixtures (for example `FakeDetector`) |
| Repo path | Text | Folder, for example `engine/` |
| Tasks | Relation to Master Tasks (reverse of Workstream) | Rollups |
| Produces seams | Relation to Integration Seams | Interfaces this workstream provides |
| Consumes seams | Relation to Integration Seams | Interfaces it depends on |
| Decisions | Relation to Technical Decisions | Open decisions affecting it |
| Tests | Relation to Tests & Evidence | Coverage |
| Bugs and blockers | Relation to Bugs & Blockers | Health |
| Tasks total | Rollup (Tasks, count) | Size |
| Percent done | Rollup (Tasks, Status, percent in Complete group) | Progress bar |
| Open critical bugs | Rollup (Bugs, Open critical checkbox, checked count) | Risk flag |
| Test pass rate | Rollup (Tests, Passed checkbox, percent checked) | Quality flag |

Views: By group (board), Ownership matrix (table showing three owner columns), At risk (filter), Must only, Progress (table with the rollups).

### DB3. Team Members

Purpose: the six people, their capacity and ownership counts. Small on purpose.

| Property | Type | Why it exists |
| --- | --- | --- |
| Name | Title | Mostafa S., Loay, Mostafa O., Marwan, Boda, Mahmoud |
| Notion user | Person | Links to the account for Me filters |
| Main area | Text | From spec Section 7 |
| Skills | Multi-select (Android, Kotlin, Compose, CameraX, FastAPI, CV, YOLO, OCR, Voice, STT and TTS, LLM, Database, Navigation, Accessibility) | Reviewer matching and pairing |
| Branch prefixes | Multi-select | Matches spec Section 8 |
| Devices | Multi-select (iPhone, Android test phone, laptop GPU) | Who can run what on a real device |
| Weekly capacity (h) | Number | Load planning |
| Primary workstreams | Relation to Workstreams (reverse) | Ownership |
| Supporting workstreams | Relation to Workstreams (reverse) | Ownership |
| Reviewing | Relation to Workstreams (reverse) | Review load |
| Primary count | Rollup (Primary workstreams, count) | Balance check (target 5 each) |
| Review count | Rollup (Reviewing, count) | Balance check |
| At-risk workstreams | Rollup (Primary workstreams, Status) | Early warning |

Views: Directory (gallery), Load (table with counts).

### DB4. Milestones & Gates

Purpose: the four phase gates (G1 end of week 2, G2 end of week 5, G3 end of week 9, G4 end of week 12) and the milestones that lead to them. A gate is a milestone with a pass/fail checklist and a demonstrable claim.

| Property | Type | Why it exists |
| --- | --- | --- |
| Name | Title | "G3 Complete demo on the phone", "M: detector runs on phone" |
| Type | Select: Milestone, Gate | Separates the two on one database |
| Parent gate | Relation (self) | Milestone to its gate; reverse relation is "Milestones" |
| Target week | Select: W1 to W12 | Roadmap grouping |
| Target date | Date | Timeline and calendar |
| Status | Status: Not started, In progress, At risk, Passed, Failed | Gate outcome |
| Demo claim | Text | The sentence that must be demonstrable (from spec Section 12) |
| Exit criteria | Text | Short summary; the full checklist is in the page body |
| Go or no-go | Select: Pending, Go, Go with cuts, No-go | Formal gate decision |
| Scope cuts | Text | What was cut at this gate (ties to Section 8 cut order) |
| Owner | Person | Accountable for the gate review |
| Workstreams | Relation to Workstreams | Which workstreams contribute |
| Tasks | Relation to Master Tasks (reverse) | Rollups |
| Exit tests | Relation to Tests & Evidence | Tests that must pass |
| Decisions due | Relation to Technical Decisions | Decisions that must be made by this gate |
| Blocking issues | Relation to Bugs & Blockers | Issues that can fail the gate |
| Git tag | Text | `gate-1` to `gate-4`, `v1.0` |
| Evidence pack | URL | Link to the gate review page |
| Task progress | Rollup (Tasks, Status, percent Complete) | Readiness |
| Open must tasks | Rollup (Tasks, Open must, checked count) | Gate cannot pass above zero |
| Test pass rate | Rollup (Exit tests, Passed, percent checked) | Readiness |
| Open critical issues | Rollup (Blocking issues, Open critical, checked count) | Gate cannot pass above zero |
| Ready | Formula (checkbox: open must tasks = 0 and critical issues = 0 and pass rate = 100 percent) | One-glance gate readiness |

Views: Timeline (by target date, colored by Type), Gates only (table with the rollups), Gate review (one gate with its checklist), Milestones by gate (grouped by Parent gate).

### DB5. Bugs & Blockers

Purpose: defects, blockers and risks in one place. Type separates them; the spec's risk table seeds the Risk rows.

| Property | Type | Why it exists |
| --- | --- | --- |
| ID | Unique ID (prefix BUG) | Reference in PRs |
| Title | Title | Short, observable symptom |
| Type | Select: Bug, Blocker, Risk | Three views on one database |
| Severity | Select: Critical, High, Medium, Low | Critical means a missed or false safety alert, a crash of the service, a privacy leak, or an SOS failure |
| Status | Status: New, Triaged, In progress, Fixed needs retest, Closed, Won't fix | Flow |
| Workstream | Relation to Workstreams | Ownership |
| Blocking tasks | Relation to Master Tasks | Task sees why it is Blocked; reverse is "Bugs and blockers" |
| Gate impacted | Relation to Milestones & Gates | Gate risk |
| Failing test | Relation to Tests & Evidence | Regression link |
| Found in | Select: Unit test, Integration, E2E, Field test, Rehearsal, Review | Where the process caught it |
| Reporter | Person | Follow-up |
| Assignee | Person | Ownership |
| Device | Select: Android test phone, Second phone, Emulator, Laptop | Reproduction |
| Build or commit | Text | Reproduction |
| Steps to reproduce | Text | Reproduction |
| Likelihood, Impact | Select: Low, Medium, High (Risk type only) | Risk register scoring |
| Mitigation or fallback | Text | Risk type and workaround notes |
| Opened, Resolved on | Created time, Date | Age and cycle time |
| Age (days) | Formula | Stale issue flag |
| Open | Formula (checkbox: status not Closed or Won't fix) | Rollups |
| Open critical | Formula (checkbox: Open and Severity is Critical) | Gate and workstream rollups |

Views: Open by severity (board), Blockers (filter Type is Blocker, Open), Risk register (table by Likelihood and Impact), Field-test findings (Found in is Field test), Needs retest, Closed.

### DB6. Technical Decisions

Purpose: record contract and technology choices (an architecture decision log) so a change after the week-2 contract freeze is deliberate. Initial rows: TD-001 detector licence (AGPL vs Apache), TD-002 wake-word library, TD-003 whether to add endpoints for cloud TTS or Whisper-class STT, TD-004 screenshot capture path, TD-005 backend authentication scheme, TD-006 text-destination handling in `/route`, TD-007 third tested app.

| Property | Type | Why it exists |
| --- | --- | --- |
| ID | Unique ID (prefix TD) | Reference |
| Title | Title | The question |
| Status | Select: Proposed, Decided, Superseded, Rejected | Lifecycle |
| Decision | Text | One-sentence outcome |
| Options and rationale | Page body | Context, options, reasons |
| Owner | Person | Who drives the decision |
| Decide by | Date | Deadline, tied to a gate |
| Decided on | Date | Audit |
| Contract impact | Checkbox | Forces a contract version bump when ticked |
| Contract version | Text | The version it produced |
| Reversibility | Select: Easy, Costly | Triage |
| Workstreams | Relation to Workstreams | Who is affected |
| Tasks | Relation to Master Tasks | Work waiting on or created by it |
| Needed by gate | Relation to Milestones & Gates | Gate pressure |
| Supersedes | Relation (self) | History |

Views: Open decisions (Proposed, sorted by Decide by), Decided log, By workstream, Contract changes (Contract impact ticked).

### DB7. Tests & Evidence

Purpose: one row per test case with its latest result and attached proof; replaces separate test and evidence databases. Run history goes in the page body.

| Property | Type | Why it exists |
| --- | --- | --- |
| ID | Unique ID (prefix TEST) | Reference |
| Test name | Title | What is verified |
| Type | Select: Unit, Model evaluation, Integration, API, Screen, Failure mode, E2E, Field test, Performance, Privacy check | Matches the spec's test strategy |
| Workstream | Relation to Workstreams | Coverage |
| Task | Relation to Master Tasks | Evidence for DoD; reverse is "Evidence" |
| Gate | Relation to Milestones & Gates | Exit tests |
| Demo scenario | Multi-select: 1 to 10 | Ties tests to the demo script |
| Result | Select: Not run, Pass, Fail, Flaky, Blocked | Status |
| Passed | Formula (checkbox: Result is Pass) | Percent-passing rollups |
| Last run | Date | Freshness |
| Run by | Person | Accountability |
| Build or commit | Text | Traceability |
| Device | Select: Android test phone, Second phone, Emulator, Laptop, CI | Environment |
| Metric | Text | For example "detector ms per frame" |
| Measured, Target | Number, Number | Performance evidence |
| Meets target | Formula (checkbox) | Auto-pass for performance tests |
| Repeat count | Number | E2E needs 3 consecutive runs |
| Evidence | Files and media | Screenshots, logs, recordings |
| Evidence link | URL | CI run, W&B report, GitHub file |
| Consent reference | Text | Field tests only: consent form ID |
| Related bugs | Relation to Bugs & Blockers | Failures raised |

Views: By gate (board by Result), Failing now, Not run, Evidence missing (filter Result Pass and Evidence empty), Field tests, Performance targets.

### DB8. Integration Seams

Purpose: the cross-workstream interfaces that make parallel work possible (the stand-in table in Section 7 as a live database).

| Property | Type | Why it exists |
| --- | --- | --- |
| Seam ID | Unique ID (prefix SEAM) | Reference |
| Name | Title | For example "Tracker to engine events" |
| Producer | Relation to Workstreams | Who provides |
| Consumer | Relation to Workstreams | Who depends |
| Contract | Text | Path and section in `docs/api-contract.md` or the interface file |
| Contract version | Text | Matches Technical Decisions |
| Stand-in type | Select: Interface only, Fake, Mock backend, Fixture, Replay | What unblocks the consumer |
| Stand-in location | Text | Repo path |
| Status | Select: Not started, Contract frozen, Stand-in ready, Real producer ready, Real consumer ready, Integrated, Verified in E2E | The ladder |
| Ladder rung | Number (1 to 8) | Order from the integration ladder in Section 7 |
| Target week | Select: W1 to W12 | Planning |
| Owner | Person | The integration owner for the seam |
| Tasks | Relation to Master Tasks (reverse of Seam) | Work on either side |
| Integration test | Relation to Tests & Evidence | Proof |
| Task progress | Rollup (Tasks, Status, percent Complete) | Progress |
| Green | Formula (checkbox: Status is Integrated or Verified in E2E) | Dashboard count |

Views: Integration ladder (table sorted by rung), Not green, By producer, Verified.

## 11. Relations and rollups

Every relation is two-way so either side can roll up the other. Create each pair once and name the reverse property as shown.

| From (property) | To | Cardinality | Reverse property | Used for |
| --- | --- | --- | --- | --- |
| Master Tasks (Workstream) | Workstreams | many to one | Tasks | Workstream progress, By Workstream view |
| Master Tasks (Milestone) | Milestones & Gates | many to one | Tasks | Gate readiness |
| Master Tasks (Blocked by) | Master Tasks | many to many | Blocks | Dependencies between tasks |
| Master Tasks (Seam) | Integration Seams | many to one | Tasks | Integration view, seam progress |
| Master Tasks (Evidence) | Tests & Evidence | many to many | Task | Definition-of-Done proof |
| Master Tasks (Bugs and blockers) | Bugs & Blockers | many to many | Blocking tasks | Blocked view and why |
| Master Tasks (Decision) | Technical Decisions | many to many | Tasks | Work waiting on decisions |
| Workstreams (Primary, Supporting, Reviewer) | Team Members | many to one, many to many, many to one | Primary, Supporting, Reviewing workstreams | Ownership matrix and load balance |
| Workstreams (Produces and Consumes seams) | Integration Seams | many to many each | Producer and Consumer | Parallel-work map |
| Workstreams (Decisions, Tests, Bugs) | Technical Decisions, Tests & Evidence, Bugs & Blockers | many to many | Workstreams | Per-workstream health |
| Milestones & Gates (Parent gate) | Milestones & Gates | many to one | Milestones | Milestone to gate grouping |
| Milestones & Gates (Exit tests) | Tests & Evidence | many to many | Gate | Gate checklist |
| Milestones & Gates (Decisions due, Blocking issues) | Technical Decisions, Bugs & Blockers | many to many | Needed by gate, Gate impacted | Gate risk |
| Integration Seams (Integration test) | Tests & Evidence | many to many | Seam | Seam proof |
| Bugs & Blockers (Failing test) | Tests & Evidence | many to one | Related bugs | Regression link |

| Rollup (on database) | Source | Calculation | Answers |
| --- | --- | --- | --- |
| Percent done (Workstreams) | Tasks, Status | Percent in Complete group | How far along is each workstream |
| Open critical bugs (Workstreams) | Bugs, Open critical | Checked count | Which workstream is unsafe |
| Test pass rate (Workstreams, Gates) | Tests, Passed | Percent checked | Is it verified |
| Primary count, Review count (Team Members) | Workstreams | Count | Is load balanced |
| Task progress (Gates, Seams) | Tasks, Status | Percent Complete | Gate and seam readiness |
| Open must tasks (Gates) | Tasks, Open must | Checked count | A gate cannot pass above zero |
| Open critical issues (Gates) | Blocking issues, Open critical | Checked count | A gate cannot pass above zero |
| Gate (Master Tasks) | Milestone, Parent gate | Show original | Filter tasks by gate with no double entry |

**Automations to switch on** (Notion database automations, no code): when Status becomes Done, set Done date; when Status becomes Blocked and no blocker is related, add a reminder property for the owner; when PR status becomes Approved, notify the owner; when a Technical Decision with Contract impact ticked becomes Decided, create a task "Bump contract version and update fixtures" assigned to Mostafa S.; when a Bug with Severity Critical is created, notify the workstream's primary owner and the gate owner.

## 12. Notion views (Master Tasks)

All thirteen views are on Master Tasks. "Open" below means Status is not Done and not Cancelled. "Me" works because Owner, Supporting and Reviewer are Person properties.

| View | Type | Filter | Group and sort | Why |
| --- | --- | --- | --- | --- |
| Master Board | Board | Status is not Cancelled | Group by Status; sort Priority, then Due; card shows Owner, Workstream, Tier, Due, PR status | The default team picture |
| My Tasks | Board | Open, and (Owner is Me or Supporting contains Me or Reviewer is Me) | Group by Status; sort Due | One person's complete load including reviews |
| Today | Table | Open, Owner is Me, and (Due is on or before today or Status is In progress) | Sort Priority, then Due | The daily start list |
| This Week | Table | Open, Due is within this week | Group by Owner; sort Due | Weekly planning and stand-up |
| Calendar | Calendar | Status is not Cancelled | By Due | Deadlines at a glance |
| Timeline | Timeline | Tier is Must or Should | By Planned start to Due; group by Workstream; show dependency arrows from Blocked by; color by Status | The plan across weeks |
| By Workstream | Table | Status is not Cancelled | Group by Workstream, sub-group by Status | Ownership and progress per workstream |
| Blocked | Table | Status is Blocked, or Deps done is below 100 percent while Status is In progress, or Bugs and blockers is not empty with an open item | Sort Priority | What is stuck and why |
| Critical | Table | Open, and (Critical path is checked or Priority is P0) | Sort Due; show Blocked by, Days to due, Owner | The critical path to the next gate |
| Integration | Table | Seam is not empty, or Task type is Integration | Group by Seam; show Works against, Status | Seam work and fake-to-real swaps |
| Waiting for Review | Board | PR status is Open or Changes requested, or Status is In review | Group by Reviewer; sort oldest first | Review queue per person |
| Done | Table | Status is Done | Group by Week; sort Done date, newest first | History and burn-up |
| Phase Gates | Board | Tier is Must and Status is not Cancelled | Group by Gate (rollup); sub-sort by Due | Gate readiness from the task side; the gate side is the `Gates only` view of Milestones & Gates |

**Two helper properties for Master Tasks** (add them to DB1 with the others): **Deps done** (Rollup of Blocked by, Status, percent Complete) and **Ready to start** (Formula checkbox: Status is Backlog or Ready, and Deps done is 100 percent). A "Ready" filter on the Master Board then shows only work that can begin now, which is how people with free time find the next unblocked task.

**Dashboard blocks** (linked views): Phase Gates for the current gate, Critical, Blocked, This Week, Waiting for Review, Integration Seams not green, Bugs & Blockers filtered to open Critical, and Workstreams sorted by Percent done.

## 13. Task template

The template is the page body of every Master Tasks row. Properties (Task ID, Owner, Reviewer, Due, Branch, PR, Evidence, Risk level) sit in the page header; the body holds the sections below. The next stage fills every section for every task; a task with an empty required section is not valid.

```markdown
# AVN-___  <Imperative title: one deliverable>

Header properties: Workstream | Milestone/Gate | Owner | Supporting | Reviewer | Tier | Priority |
  Critical path | Due | Estimate | Works against (Real/Fake/Fixture) | Seam | Branch | PR

## 1. Objective
One or two sentences stating the observable result when this task is done.

## 2. Context
Where this sits in the system: which pipeline, which neighbors, which spec section and
WS-ID. Name the interfaces and data structures it touches (Event, ApiEnvelope, ...).

## 3. Why
The product reason, tied to a demo scenario or a safety/privacy rule. What fails if skipped.

## 4. Implementation
Numbered steps precise enough to start coding. Name classes, functions, config keys,
thresholds (marked as starting values), libraries and the stand-in being built or replaced.

## 5. Expected files
- path/to/file (create|edit) : purpose

## 6. Inputs
What the code receives: type, source, example value or fixture path.

## 7. Outputs
What it produces: type, consumer, example value, where it is logged.

## 8. Dependencies
- Blocked by: AVN-xxx (relation) : what is needed from it
- Can start with stand-in: yes/no : which fake, fixture or contract
- Blocks: AVN-xxx
- Decisions needed: TD-xxx

## 9. Integration
Seam (SEAM-xxx), the neighbor on each side, the call or event that crosses it, and the
swap from stand-in to real (when and by whom).

## 10. Testing
Specific tests: unit, fixture/replay, integration, device. Each with input, expected
result, and the Tests & Evidence row it will update.

## 11. Acceptance criteria
Testable statements, one per line, each pass/fail. Include failure and offline behavior.

## 12. Definition of Done
- [ ] Acceptance criteria all pass
- [ ] Tests written and passing (CI green)
- [ ] Fake/fixture left in repo for others if this task provides an interface
- [ ] Logs added per engine/backend logging rules; no content logged
- [ ] Consent/privacy rules respected (WS-28 checklist)
- [ ] Docs updated (contract, architecture, or README section)
- [ ] PR reviewed by the Reviewer, merged to develop
- [ ] Evidence attached in Tests & Evidence (screenshot, log, video, CI link)

## 13. Risks
Top risks, likelihood, mitigation, fallback; link BUG rows of Type Risk if they exist.

## 14. GitHub
Branch: feature/<area>-<description> | PR: link | Merge target: develop

## 15. Reviewer
Name and what to check (contract compliance, edge cases, privacy, performance).

## 16. Evidence
Links and attachments added at completion; relation to Tests & Evidence rows.
```

**Depth rule.** A task is "extremely detailed" when a teammate who has not read the spec could implement it from the page alone: Implementation names concrete classes and files, Inputs and Outputs include a real example value, Acceptance criteria are individually testable, and Testing names the test cases. Tasks that only restate the title fail review.

## 14. Rules for the task generation stage

**Generation rules**

1. **Source.** Generate only from this blueprint and the spec. Every task cites a WS ID and spec section. Items marked *(proposed)* may be used but any that change a contract go through a Technical Decision. Unknown values become open questions, never invented facts.
2. **Granularity.** One task is one deliverable, one owner, one branch, one PR, and 4 to 24 estimated hours. Anything larger is split. Estimates are in hours; dates are weekly (Planned start and Due fall in a week W1 to W12) because the daily schedule is a later stage.
3. **Standard task ladder per workstream.** In order: (a) interface and fake or fixture, (b) real implementation in slices, (c) integration task for each seam, (d) test and evidence task, (e) docs update. Interface and fake tasks land in W1 to W2 wherever another workstream consumes the output.
4. **Dependencies are between tasks.** Use `Blocked by` only for a true hard dependency; when a stand-in exists, the consumer task has no blocker and `Works against` is Fake, Mock or Fixture, with a separate later task to swap to the real one.
5. **Parallelism check.** Every person has at least two startable tasks in W1 and W2 with no blockers (Section 7 lists them). No person's tasks in any week exceed their capacity.
6. **Ownership.** Task owner is the workstream's primary or supporting owner from Section 6. Reviewer is a different person from a different role, taken from that workstream's reviewer, and never the owner.
7. **Scope and gates.** Must tasks complete by Gate 3 (W9). Should tasks start only after their Must neighbors are green and carry a go/no-go note. No new tasks after Gate 3 except bugfix, tuning, test, docs and demo. Nice tasks are created but left in Backlog.
8. **Mandatory cross-cutting tasks** (they are easy to forget): consent gate before every cloud call, no content in logs, secure-screen deny-list, face opt-in and deletion, key spending limits, licence audit, `X-Mock-Scenario` failure injection, alert-log screen, 30-minute thermal session, three-run E2E demo rehearsal, signed APK and permissions guide.
9. **Safety wording.** No task may introduce meters for hazards, vehicle speed or time-to-collision, naming of strangers, sending or replying to messages, reading banking or password screens, endpoints beyond the nine, or real emergency numbers in tests or builds. Numeric thresholds are marked "starting value".
10. **Every task** has acceptance criteria that can be marked pass or fail, named tests, and an evidence item; a task that only restates its title is rejected.

**Seed data to create in the same pass**

| Database | Seed |
| --- | --- |
| Workstreams | WS-01 to WS-30 with owners from Section 6 and the 15-field breakdown as page body |
| Team Members | Six people with capacity, devices and branch prefixes |
| Milestones & Gates | G1 (W2), G2 (W5), G3 (W9), G4 (W12) with demo claims and exit tests, plus one milestone per major deliverable |
| Integration Seams | The 15 stand-in rows from Section 7 (all except the testers row), with ladder rungs 1 to 8 |
| Technical Decisions | TD-001 to TD-007 as listed in DB6 |
| Bugs & Blockers | B-001 Android test phone purchase as a Blocker; the 14 risks of spec Section 18 as Risk rows |
| Tests & Evidence | Rows traced from the 10 demo scenarios, the failure-mode list and the performance targets |

**Required checks before the generator finishes:** every Must feature in Section 8 has tasks; each demo scenario traces to tasks and tests; no dependency cycles; every workstream has all five task-ladder types; per-person weekly load within capacity; owner is never reviewer; every task has a gate; counts per database reported.

**Output format.** One importable CSV per database with relation columns written by title or ID, task page bodies in the Section 13 template, and a short load report per person per week. If a Notion connector is attached, the same data can be written directly.

**Confirm before generation (these change the output)**

- Are Mostafa Moko and Mostafa omar the same person, and are the Section 6 owner calls acceptable (engine to Marwan, navigation to Mostafa O., SOS to Boda)?
- Has the Android test phone been ordered, and what is week 1's start date, so Due dates can be written as calendar weeks later?
- Weekly capacity in hours per person (default assumption: 25 productive hours).
- Is the Should tier (faces, currency, Reels) acceptable to decide at Gate 2, and may Reels be cut first?
- TD-003: should extra endpoints for cloud TTS or Whisper-class STT be allowed, or stay out of the spec's nine?
- Which third app is tested for screen reading besides WhatsApp and Facebook?
