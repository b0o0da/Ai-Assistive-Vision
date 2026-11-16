# TD-004: Screenshot capture path (WS18-01, AVN-100)
16 Nov 2026 · Owner: Marwan · Status: **Open: desk research done, device test pending**
Contract impact: [x] none

## Context
WS-18 needs one screenshot when the accessibility tree is empty or unlabeled (ladder rung 2, then
OCR via WS-07, then `/screen/summarize`). TD-004 picks how the screenshot is taken. Notion acceptance:
both options tried on the test phone, consent behaviour and API-level limits documented, decision recorded.
minSdk is 31 (android/CLAUDE.md), so every API below Android 11 is irrelevant.

## Options considered

| | A. `AccessibilityService.takeScreenshot()` | B. MediaProjection |
|---|---|---|
| API level | 30+ (Android 11). Window-level `takeScreenshotOfWindow()` 34+ | 21+; Android 14 adds stricter rules (below) |
| User consent | None beyond enabling the accessibility service, which WS-17 already needs | System consent dialog **before every session**; a session = one `createVirtualDisplay()` call; the token is single-use |
| Extra setup | `android:canTakeScreenshot="true"` in the service config | Foreground service of type `mediaProjection` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission (target 34+), started before the projection |
| Rate limit | About 1 per second; faster calls fail with `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT` | None for single frames once the session runs |
| Secure screens | `FLAG_SECURE` windows → `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` (documented for the window API, 34+) | Secure windows come out black |
| Eyes-free UX | Nothing to tap per capture | A system dialog the blind user has to find and confirm each session, or ask a sighted helper |
| Visible indicator | None | Cast or screen-recording indicator while the session runs |
| Fit with our ladder | One call, `HardwareBuffer` → `Bitmap`, downscale to 512–768 px | Needs `ImageReader` + virtual display, then stop the session |

Error codes for A (AOSP `AccessibilityService`): 1 internal error, 2 no accessibility access,
3 interval too short, 4 invalid display, 5 invalid window (34+), 6 secure window (34+).

## Proposed decision (to confirm on the phone)
**A: `AccessibilityService.takeScreenshot()`**, with B kept as the fallback if A fails on the test phone.

Why: the app already runs an AccessibilityService for screen reading (WS-17); A adds no per-session
dialog, which matters most for a user who cannot see the screen. It respects `FLAG_SECURE` by itself (our
privacy rule: never read secure windows). The 1-per-second limit is fine for "read my screen"; it only
rules out continuous capture, which the spec does not ask for (Reels = one visible frame).

## Device test still to do (blocks "Decided")
On the team test phone (Android 12+), one capture with each option:

| # | Step | A | B |
|---|---|---|---|
| 1 | Capture the home screen, save nothing, log only width/height and time | | |
| 2 | Time from request to bitmap (ms, median of 10) | | |
| 3 | Two captures 300 ms apart: second one fails with code 3 (A only) | | n/a |
| 4 | Banking or any `FLAG_SECURE` app: error 6 (A) / black frame (B) | | |
| 5 | What the user hears and has to do for consent (with TalkBack on) | | |
| 6 | After reboot: does it still work without re-consent? | | |

Screenshots are never stored or logged (root CLAUDE.md privacy rule); record numbers only.

## Consequences
- If A: add `canTakeScreenshot` to the accessibility service config (WS-17, Loay); `docs/permissions-guide.md`
  item 7 (MediaProjection consent per session) can be removed.
- If B: foreground service type `mediaProjection` in the manifest, a spoken consent flow, and the
  permissions guide stays as is.
- No contract change either way.

## Sources
- AccessibilityService reference: https://developer.android.com/reference/android/accessibilityservice/AccessibilityService.TakeScreenshotCallback
- API 34 diff (window screenshot, new error codes): https://developer.android.com/sdk/api_diff/34/changes/android.accessibilityservice.AccessibilityService
- Media projection guide (consent per session, single-use token, FGS type): https://developer.android.com/media/grow/media-projection
