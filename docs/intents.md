# Voice intents

Phrases the rule-based router understands, and what each one becomes. Source of truth: the tables in `RuleIntentParser.kt`. `RuleIntentParserTest` runs every phrase listed here, so a phrase added to the code without being listed in a table, or listed in two tables, fails the build.

The router reads typed text, so everything below can be tried in the Debug tab (debug builds) without a microphone.

## How a phrase is read

1. **Normalise.** Lower case, apostrophes and punctuation removed, spaces collapsed. Leading fillers (`hey`, `okay`, `ok`, `please`, `could you`, `can you`, `would you`, `will you`) and trailing fillers (`please`, `for me`) are dropped.
2. **Emergency phrases first.** An exact match always gives `sos`, offline or online, and never reaches the cloud.
3. **Local commands**, then **plans** from the tables below. A phrase matches the whole text, so `stop the car` is not `stop`.
4. **No rule matched** (`NoMatch`): offline the user hears "I can't do that offline."; online the text goes to `POST /intent`. With no cloud answer the user hears "I didn't understand that. Please say it another way."

The router never proposes a step outside the contract's list, and no phrase produces a send, reply, post, delete, buy or pay action.

## Emergency

Exact phrases only, in any case and with punctuation. Result: plan `[sos]`, no confirmation (the spoken countdown and cancel are the safety).

`sos`, `s o s`, `send sos`, `call sos`, `help`, `help me`, `help now`, `help me now`, `i need help`, `i need help now`, `emergency`, `this is an emergency`, `its an emergency`, `call for help`, `send help`, `i am in danger`, `im in danger`

Not emergencies: `help me find my keys`, `I need help finding the door`, `emergency exit`.

## Plans

| Intent | Phrases | Plan steps | Spoken acknowledgement |
| --- | --- | --- | --- |
| What's around me | `whats around me`, `what is around me`, `whats in front of me`, `what is in front of me`, `describe`, `describe the scene`, `describe my surroundings`, `what do you see`, `whats going on`, `look around`, `tell me whats around me` | `describe` | none |
| Read the screen | `read the screen`, `read screen`, `read my screen`, `whats on the screen`, `what is on the screen`, `whats on my screen`, `what is on my screen` | `read_screen` | Reading the screen. |
| Summarize | `summarize`, `summarize this`, `summarize these`, `summarise`, `summarise this`, `summarise these`, `summarize the screen`, `summarize this screen` | `summarize` | Summarizing. |
| Back | `go back`, `back`, `previous screen`, `go to the previous screen` | `back` | Going back. |
| Read messages | `read my new messages`, `read new messages`, `read my messages`, `read messages`, `check my messages` | `launch_app` WhatsApp, `wait`, `read_screen` | Opening WhatsApp. |
| Scroll | `scroll down` (down), `page down` (down), `scroll up` (up), `page up` (up); `scroll` alone asks "Up or down?" | `scroll` with `up` or `down` | Scrolling down. / Scrolling up. |

## Phrases with a parameter

| Intent | Form | Plan steps | Spoken acknowledgement |
| --- | --- | --- | --- |
| Open an app | `open <app>`, `launch <app>` (up to three words, no `and`, no send/delete/buy/pay/post/confirm words) | `launch_app` with the app name | Opening `<App>`. |
| Open an app and read it | `open <app> and read ...` | `launch_app`, `wait`, `read_screen` | Opening `<App>`. |
| Navigate | `take me to <place>`, `navigate to <place>`, `directions to <place>`, `how do I get to <place>`, `get me to <place>`, `take me <place>` | `navigate` with the place (lower case, leading "the" dropped) | Getting directions to `<Place>`. |
| Tap a label | `tap`, `click`, `press`, `select` + `<label>` | `tap_label` with the label | Tapping `<label>`. |
| Ask about the scene | starts with one of the starters below, camera active | `ask` with the original text | none |

Ask starters: `is there`, `are there`, `how many`, `what color`, `what colour`, `what is this`, `what is that`, `is this`, `is that`, `which one`.

`open` or `launch` alone asks "Which app do you want to open?". `take me` alone asks "Where do you want to go?".

Known app names: `whatsapp` as WhatsApp, `facebook` as Facebook, `chrome` as Chrome, `youtube` as YouTube, `maps` as Maps, `google maps` as Google Maps, `messages` as Messages, `camera` as Camera. Any other short name is title-cased.

Tapping a label with `send`, `delete`, `buy`, `pay`, `post` or `confirm` (or the Arabic equivalents) sets `requires_confirmation` and gets no spoken acknowledgement. The MVP never performs these actions.

## Local commands

The contract's plan steps have no value for these, so the router passes them to a `LocalCommandHandler` and returns an empty plan. Promoting one to a plan step is a contract change (Technical Decision, schema, fixtures, backend whitelist).

| Command | Phrases | Meaning |
| --- | --- | --- |
| `STOP` | `stop`, `stop talking`, `be quiet`, `quiet` | Stop talking now. |
| `CANCEL` | `cancel`, `never mind`, `nevermind` | Cancel what is in progress. |
| `REPEAT` | `repeat`, `repeat that`, `say that again`, `say again`, `what did you say`, `come again` | Say the last message again. |
| `LOUDER` | `louder`, `speak louder`, `volume up`, `turn it up` | Raise the speech volume. |
| `QUIETER` | `quieter`, `softer`, `speak quieter`, `volume down`, `turn it down` | Lower the speech volume. |
| `FASTER` | `faster`, `speak faster`, `speed up` | Speak faster. |
| `SLOWER` | `slower`, `speak slower`, `slow down` | Speak slower. |
| `STOP_NAVIGATION` | `stop navigation`, `stop navigating`, `cancel navigation`, `end navigation`, `cancel route`, `stop directions` | End the current route. |
| `READ_TEXT` | `read this`, `read that`, `read the text`, `read text`, `read the sign`, `read this sign`, `read the label`, `read the menu`, `what does this say`, `what does that say`, `what does it say` | Read the text in front of the camera. |
| `READ_CURRENCY` | `what note is this`, `what bill is this`, `what money is this`, `how much is this note`, `how much is this bill`, `how much money is this`, `identify this note`, `identify the money`, `read the currency`, `read this note`, `read the note`, `what denomination is this` | Say which banknote is in front of the camera. |
| `RECOGNIZE_FACE` | `who is this`, `who is that`, `who is it`, `who is in front of me`, `who is here`, `who am i looking at`, `do i know this person`, `do you know this person`, `recognize this person`, `recognise this person` | Say who the person in front of the camera is, if enrolled. |
| `ENROLL_PERSON` | `enroll <name>` | Start enrolling a person. The name is title-cased. `enroll` alone asks "Who should I enroll?". |

## Not supported on purpose

Sending, replying, forwarding, calling, texting, posting, deleting, buying and paying. Typing `send hi to mom` or `reply yes` gives `NoMatch`. The only phrases that start with "send" are the emergency phrases `send sos` and `send help`, which start the SOS countdown and send nothing to a contact by themselves.

## Adding a phrase

1. Add it to the matching table in `RuleIntentParser.kt`. A phrase may live in only one table.
2. Run `./gradlew test`. `RuleIntentParserTest` checks that every table entry produces its intent and that no phrase is in two tables.
3. Add a near-miss test if the new phrase is short or sounds like another command.
4. Update this file.
