# WS-16 Priority and context engine

Pure Kotlin (`eg.assistive.core.engine`). No Android classes. Every pipeline calls `EngineInput`; the engine is the **only** caller of `SpeechOutput`.

```kotlin
interface EngineInput {
    fun submit(event: Event): DecisionRecord
    fun submit(candidate: SpeechCandidate): DecisionRecord
    fun updateContext(context: EngineContext)      // listening, speaking, moving, navigationActive, offline, sosActive
}
interface SpeechOutput {
    fun setListener(listener: UtteranceListener)   // onStarted / onDone / onError / onStopped
    fun speak(request: SpeechRequest)              // text, level, source, interruptible, eventId (+ utteranceId)
    fun stop()
}
```

## Decision pipeline (order matters)

| # | Step | Result if it fails |
|---|---|---|
| 1 | Validate (id, type present; confidence 0..1; speech text not blank) | `SUPPRESSED / INVALID` |
| 2 | Noise: confidence < 0.40 (SOS exempt) | `SUPPRESSED / LOW_CONFIDENCE` |
| 3 | Risk score and level (`risk-levels.md`) | - |
| 4 | Context: unrequested INFO is dropped while `moving`, `navigationActive` or `sosActive` | `SUPPRESSED / INFO_NOT_REQUESTED` |
| 5 | Cooldown per key (critical 2 s, warning 5 s, info 10 s). Asked-for, **closer** or **more severe** repeats bypass it | `SUPPRESSED / COOLDOWN` |
| 6 | Duplicate text (same text waiting, speaking, or spoken < 3 s ago) | `SUPPRESSED / DUPLICATE` |
| 7 | Schedule | `SPOKEN_NOW / SPOKEN_INTERRUPT / QUEUED` |

Scheduling: a critical alert calls `stop()` then `speak()` if the current speech is interruptible; if the current speech is itself critical it waits at the front of the queue. When `listening` is true only critical alerts speak; the rest is held and released by `updateContext(listening = false)`.

Queue: ordered by level, then score, then arrival. TTL: critical 2 s, warning 4 s, info 8 s, OCR chunks 60 s. At most 8 automatic items wait (lowest dropped, `QUEUE_FULL`); items the user asked for are never dropped for space. A user-requested reading cut by a critical alert goes back to the front and resumes.

Cooldown key: `type:trackId` if there is a track id, otherwise `type:side:label`. Speech: `dedupeKey`, or `source:text`.

## Alert log
`engine.alertLog`: `entries()`, `spoken()`, `suppressed()`, `notSpoken()`, `outcomeCounts()`, `reasonCounts()`, `render()`. In-memory ring buffer (500), never written to disk. `SpeechCandidate(sensitive = true)` text is stored as `[redacted N chars]`.

## Replay format
`engine/scenarios/*.json`: timed `event`, `speech` and `context` steps plus `expect` (exact spoken order, texts that must not be spoken). `ScenarioReplayer` runs them against the real engine with a simulated clock and TTS (`ms_per_char`). Same files for unit tests, the laptop demo (`DemoKt`) and the final report. Four scenarios are included.

## Threading and callbacks
All public methods are synchronized. The adapter must report `onDone` / `onError` with the `utteranceId` it received; stale ids and `onStopped` are ignored. Call `tick()` about every 250 ms so queue items expire. If a callback is lost the engine recovers after 20 s.

## Tunables
`EngineConfig`. Everything is a starter value; calibrate on recordings in Weeks 17-20.
