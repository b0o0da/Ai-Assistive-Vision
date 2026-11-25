# Demo script (acceptance test)

WS-30 (AVN-248, AVN-249). 8–10 minutes, run three times back to back before Gate 4 (WS30-07).
Every step has a pass rule and a fallback, so the demo never stops on stage.

**Roles.** *User*: wears the chest mount and headset, eyes closed or blindfolded. *Operator*:
holds the laptop with the projected alert log and the backup videos, and calls the fallbacks.
*Safety lead*: stands next to the user for scenarios 2, 4 and 7.

**Wording.** Lines in quotes are what the user says, and the gist of what the phone answers. The
app's exact sentences may differ; the pass rule is what matters. `<wake word>` is the phrase
chosen in TD-002.

## Summary

| # | Scenario | Time | Pass when | Fallback |
| --- | --- | --- | --- | --- |
| 1 | Wake word, "What's around me?" | 0:45 | Short scene description spoken | Mock-mode answer, then video |
| 2 | Walk toward an obstacle | 0:50 | One warning with band and side, plus vibration | Video replay source |
| 3 | Crowded room | 0:50 | Only the important item spoken; log shows suppressed | Projected alert log |
| 4 | Vehicle from the side (toy or video) | 0:45 | Critical alert cuts in, no cloud | Video replay source |
| 5 | Read a sign, menu, medicine box | 1:00 | Text read correctly | Printed test cards |
| 6 | Known / unknown person (if shipped) | 0:45 | Name / "someone you don't know" | Video |
| 7 | "Take me to the nearest pharmacy" | 1:15 | Turn-by-turn on the pre-tested route | GPX replay |
| 8 | WhatsApp read, scroll, summarize | 1:30 | Correct reading and summary | Mock messaging app |
| 9 | Voice SOS | 1:00 | Countdown, cancel works, SMS reaches team phone | Video |
| 10 | Airplane mode | 0:50 | Warnings still work; cloud answer after reconnect | Video |
| | **Total** | **9:30** | | |

Skip 6 if faces were cut at Gate 2 (WS08-10) and say so in one sentence; the total is then 8:45.

---

## Before the audience arrives (15 minutes)

- [ ] Demo phone at 100 %, power bank attached; second phone charged with the same build.
- [ ] Build: signed release APK from the `v1.0` tag (WS30-05); version shown in Settings.
- [ ] Permissions all green on the Onboarding screen (see `docs/permissions-guide.md`).
- [ ] Backend `/health` returns `ok` from the venue network; hotspot ready as backup.
- [ ] Settings: verbosity normal, speech rate normal, haptics on, mock mode **off**.
- [ ] Alert log open on the laptop, mirrored to the projector, filter "All".
- [ ] Stations set: obstacle (chair) at 4 m, crowded table, vehicle station, text cards, route start.
- [ ] WhatsApp: 3 unread messages sent from a team phone in the last 10 minutes.
- [ ] SOS contacts = team phones only (`SosRecipientPolicy`, WS21-07). Team phone 2 on the desk, sound on.
- [ ] Backup videos 1–10 open in one player playlist (WS30-04).
- [ ] Auto-updates off for the OS and every tested app (frozen versions, WS17-10).

---

## 1. Wake word and "What's around me?" (0:45)

**Setup.** User standing at the obstacle station, phone on the chest mount, online.

**Script.**
1. User: "`<wake word>`" → earcon.
2. User: "What's around me?"
3. Phone (cloud /describe): one or two short sentences, for example "A chair ahead, a table on
   your left." No distances in meters, no safety claim.

**Pass.** Earcon within about 1 s of the wake word; answer starts within about 4 s.

**Fallback.** No earcon after two tries → operator presses the debug wake button (FakeWakeWord,
WS11-01) and says "the wake word is the next thing we tune". Cloud timeout → the phone says "I
can't describe the scene right now." (that *is* the designed fallback; say so). Phone silent →
play video 1.

## 2. Walk toward an obstacle (0:50)

**Setup.** Chair in the middle of a clear path, 4 m from the user. Safety lead beside the user.

**Script.**
1. User walks slowly toward the chair.
2. Phone: Warning, for example "Chair ahead, close." with a vibration pattern. Spoken once, not
   repeated every step (cooldown, WS16-05).
3. User steps left; phone says nothing new unless the side or band changes.

**Pass.** One warning with band (near/medium/far) and side; vibration; no repeat inside the
cooldown.

**Fallback.** No warning by 1.5 m → safety lead stops the user; operator switches the camera to
the video replay source (VideoFileFrameSource) and replays clip `chair_approach`.

## 3. Crowded room (0:50)

**Setup.** Table with 4–6 items and two people standing nearby. Alert log projected.

**Script.**
1. User turns slowly toward the table.
2. Phone speaks only the most important item (for example the person close on the right).
3. Operator points at the alert log: several events marked *suppressed* with reasons
   (`below_threshold`, `duplicate`, `cooldown`).

**Pass.** At most one or two utterances; the log shows suppressed events with reasons. This is
the scenario that shows the priority engine is the product.

**Fallback.** Too many utterances → operator lowers verbosity in Settings and repeats once; if
still noisy, show the projected log from rehearsal (screenshot in the backup folder).

## 4. Vehicle approaching from the side (0:45)

**Never near real traffic.** Toy car on a ramp, or the recorded street video on a large screen.

**Setup.** Phone is speaking something long and Info-level (for example reading a menu card).

**Script.**
1. Operator starts the toy car / video from the user's right.
2. Phone cuts the menu reading and says, for example, "Car approaching, right." (Critical).
3. Operator notes: no cloud call happened (show airplane icon if you want to make the point).

**Pass.** The Critical alert interrupts the Info speech immediately; side is correct; no speed or
time-to-collision is claimed.

**Fallback.** Not detected → replay clip `car_from_right` through the video replay source; if the
whole camera path fails, play video 4.

## 5. Read a sign, a menu and a medicine box (1:00)

**Setup.** Three printed cards from the OCR fixture set (WS07-02: sign, menu, medicine box),
good light.

**Script.**
1. User: "`<wake word>`, read this." holding the sign card.
2. Phone reads the sign text.
3. Repeat with the menu (long text is summarised, then "say read more"), then the medicine box,
   read verbatim, never interpreted as advice.

**Pass.** Text matches the card; the phone asks to move closer when blocks are too small.

**Fallback.** "No readable text found" → hold steadier and closer, retry once; then read from the
backup photo in the app's debug OCR screen; then video 5.

## 6. Known and unknown person (0:45, only if faces shipped)

**Setup.** One enrolled volunteer with written consent; one non-enrolled team member.

**Script.**
1. Volunteer stands in front of the user → phone: the volunteer's name.
2. Non-enrolled person steps in → phone: "someone you don't know".
3. Optional: "`<wake word>`, forget <name>" → confirmation, then they are no longer named.

**Pass.** Name only for the enrolled person; strangers are never named.

**Fallback.** Wrong or no name → say "this is exactly why the default is 'someone you don't
know'" and play video 6.

## 7. "Take me to the nearest pharmacy" (1:15)

**Setup.** Route walked and verified beforehand (WS20-11); start point marked; GPS fix acquired;
safety lead walks beside the user.

**Script.**
1. User: "`<wake word>`, take me to the nearest pharmacy."
2. Phone: "Getting directions to …", then the first instruction.
3. User walks the first one or two turns; phone announces each turn with left/right vibration.
4. Operator shows the remaining route on the laptop and stops the walk after two turns.

**Pass.** Destination resolved; turn announcements at the right places; hazard alerts still cut
in during navigation.

**Fallback.** No GPS fix or no network → start the GPX replay of the same route (WS20-01) and
show the announcements; the backend also serves the last good route from its cache (WS22-12).

## 8. WhatsApp and Facebook (1:30)

**Setup.** Three unread WhatsApp messages from a team phone; one Facebook post ready. Supported
app versions frozen (WS17-10).

**Script.**
1. User: "`<wake word>`, open WhatsApp and read my new messages." → phone: "Opening WhatsApp", then
   reads sender and message for each.
2. User: "Scroll down." → phone: "Scrolling down", reads the next items.
3. User: "Summarize these." → short summary (cloud /screen/summarize).
4. User: "Open Facebook and summarize this post." → summary of the post.
5. Operator notes: read-only; the assistant never sends, replies or likes.

**Pass.** Correct sender and text; scroll reads new items without repeating; summaries are
short. Banking or password screens are refused ("I can't read this screen").

**Fallback.** Real app changed its layout → switch to the mock messaging app (WS17-06 / WS30-03)
with the demo toggle (under 5 s) and repeat; cloud summary fails → the phone reads the screen
text instead, which is the designed fallback.

## 9. Voice SOS (1:00)

**Team phones only. The emergency-number path is disabled in this build.**

**Setup.** Team phone 2 on the desk, sound on, visible to the audience.

**Script.**
1. User: "`<wake word>`, help, SOS." → Critical countdown: "Sending S O S in 10 seconds. Say
   cancel to stop."
2. User: "Cancel." → "S O S cancelled." Nothing is sent.
3. User triggers it again and lets the countdown run → "S O S sent."
4. Operator shows team phone 2: SMS with a maps link and the fix age.

**Pass.** Cancel stops the send; the second run delivers the SMS with location; works offline
(SMS only).

**Fallback.** SMS does not arrive within 30 s → show the app's "sent" state and the alert log;
play video 9.

## 10. Airplane mode (0:50)

**Setup.** Obstacle station again.

**Script.**
1. Operator turns on airplane mode on the demo phone.
2. User walks toward the chair → the warning still comes (on-device detection and TTS).
3. User: "What's around me?" → "I can't do that offline." (or the describe fallback).
4. Operator turns airplane mode off; user asks again → cloud answer.

**Pass.** Safety path works with no network; cloud features say so clearly and recover after
reconnect.

**Fallback.** Cloud answer slow after reconnect → wait 5 s and retry once; then video 10.

---

## Rules

- Never stage scenario 4 near real traffic. Toy car or recorded video only.
- Only people who signed consent appear on camera or in enrolment.
- SOS goes to team phones only; real emergency numbers are rejected in every build.
- Say what the app is: a research prototype and an assistive aid, not a safety device.
- Second charged phone, power bank and hotspot within reach.
- After each rehearsal, write the result per scenario in `docs/evidence/rehearsal-log.md`.
