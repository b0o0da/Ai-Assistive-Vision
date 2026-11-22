# Permissions and sideload guide

WS30-06 (AVN-253). The app is distributed as a sideloaded APK (Accessibility policy). A sighted
helper does this once per phone. Target: a teammate who did not write this guide sets up a reset
phone in under 15 minutes (record the time in the last section).

## Install

1. Allow installs from unknown sources for the file manager or browser, then install the APK.
2. **Restricted settings** (Android 13+): Settings → Apps → AVN → ⋮ → *Allow restricted
   settings*. Do this before the Accessibility and Notification access steps. On some sideloaded
   builds the SMS permission dialog is also blocked until this is done (to be confirmed on the
   test phone).
3. Open the app and follow the spoken onboarding.

## What each permission is for

| Permission | Why the app needs it | How to grant | If refused |
| --- | --- | --- | --- |
| Camera | Obstacle, person and vehicle warnings; reading text | Onboarding → Grant | No warnings and no reading; the app says so |
| Microphone | Wake word and voice commands | Onboarding → Grant | Voice commands off; buttons still work |
| Precise location | Walking directions; location in the SOS message | Onboarding → Grant, choose *Precise* | No navigation; SOS sent without a location |
| Send SMS | SOS message to your contacts | Onboarding → Grant | SOS cannot send; the app says so |
| Notifications | The foreground-service notification that keeps the assistant running | Onboarding → Grant | Android may stop the assistant in the background |
| Accessibility service | Reading, scrolling and going back in WhatsApp and Facebook (read-only) | Settings → Accessibility → AVN → On | Screen reading off |
| Notification access | Reading new WhatsApp messages aloud | Settings → Notifications → Notification access → AVN → Allow | "Read my new messages" off |
| Battery: Unrestricted | Stops Android from killing the assistant | Settings → Apps → AVN → Battery → *Unrestricted* | The assistant may stop after a few minutes with the screen off |
| Screen capture (per session) | Summaries of screens with no readable text (TD-004) | Accept the system prompt when asked | Only the text the app can read is used |

Cloud consent is separate from Android permissions (`docs/consent.md`): the app asks before it
ever sends a camera picture or screen content to the backend, and every cloud feature has an
off switch.

## App settings

In the app's Settings enter the backend URL, the install API key and the emergency contacts
(team phones during development; real emergency numbers are rejected). The Onboarding screen
lists every item above with a Grant button, re-checks them each time the app returns to the
front, and speaks the reason whenever one is refused. "Start assistant" needs at least one of
camera, microphone or location.

## Demo phone: freeze before the demo

- Turn off automatic system updates and Play Store auto-update (tested app versions must not
  change; WS17-10, WS17-12).
- Note the Android version, security patch and the WhatsApp and Facebook versions in
  `docs/device-matrix.md` (WS26-03).
- Do Not Disturb off, media volume high, screen lock 5 minutes or more.
- Keep a power bank attached during rehearsals and the demo.

## Troubleshooting

| Problem | Fix |
| --- | --- |
| Accessibility switch is greyed out | Do the *Allow restricted settings* step, then try again |
| The assistant stopped in the background | Open the app and press "Start assistant"; Android may refuse camera and microphone restart until the app is in front |
| No SOS SMS | Check the SMS permission and that the contact is on the team allow-list |
| "I can't connect to the service" | Backend URL or API key wrong in Settings; check `/health` from the phone's browser |
| WhatsApp messages not read | Notification access off, or WhatsApp notifications muted for that chat |

## Test phone record

Exact menu names differ by phone brand. Fill this in on the test phone:

| Item | Value |
| --- | --- |
| Phone model / Android version | |
| Exact path to *Allow restricted settings* | |
| Exact path to Notification access | |
| Setup time by a non-author (target under 15 min) | |
| Who tested, date | |
