# Consent gate (WS28-01)

Rule: no sensor starts and no content leaves the phone without the user's consent for that capability.
Code: `android/app/src/main/kotlin/avn/app/core/consent/` (`ConsentManager.kt`, `ConsentGuard.kt`).

## Scopes

| Scope | Gates | Checked by |
| --- | --- | --- |
| `CAMERA` | Starting the camera frame source | Camera pipeline (WS-04) |
| `MICROPHONE` | Starting wake word and speech recognition | Voice (WS-11, WS-12) |
| `SCREEN_READING` | Reading the screen through Accessibility or capture | Screen (WS-17, WS-18) |
| `CLOUD_FRAMES` | `/describe`, `/ask` uploads | `ApiClient` (WS-22) |
| `CLOUD_SCREEN` | `/screen/summarize` uploads | `ApiClient` (WS-22) |
| `FACES` | Face enrolment and matching | Faces (WS-08) |

Each scope is separate: camera consent does not allow cloud uploads of camera pictures.
Android runtime permissions are a different, extra check; both must pass.

## How to use it

Inject `ConsentGuard` (provided as a singleton in `ModulesModule`) and wrap the start or the call:

```kotlin
gate.guard(ConsentScope.CAMERA, onDenied = { denied ->
    engine.submit(SpeechCandidate(denied.spokenMessage, Source.SYSTEM, EventType.SYSTEM_NOTICE))
    null
}) {
    frameSource.frames()
}
```

- The gate reads the store on every call and never caches, so a revoke takes effect at the next start or call.
- A denied check never throws. It returns `ConsentCheck.Denied` with a short sentence to speak.
- Producers never speak the message themselves; it goes to the engine like any other result.

## Granting and revoking

The Settings screen shows one switch per scope (`ConsentScope.label`). Grants are stored with their time in
SharedPreferences (`consent`). Nothing is granted by default.

## Not done in WS28-01

Wiring the gate into each start point belongs to that seam's owner, once the real component exists:
camera start (WS-04), wake word and STT start (WS-11, WS-12), screen reading (WS-17, WS-18), `ApiClient`
cloud calls (WS-22). The privacy review checklist (WS28-07) checks that each one does.

## Tests

`android/app/src/test/kotlin/avn/app/core/consent/ConsentGuardTest.kt`: blocks camera, microphone and screen
without consent, blocks cloud calls without consent, allows with consent, honours a revoke on the next call.

## Relation to `avn.app.data.api.ConsentGate`

`BackendClient` (AVN-110) already checks cloud scopes through its own one-method `ConsentGate`
(`avn.app.data.api`), wired to `ConsentManager::isGranted` in `ModulesModule`. Both read the same
`ConsentManager`, so a revoke applies to both at the next call. `ConsentGuard` is named differently
so the two never clash on import; it adds the spoken denial message for sensor starts.
