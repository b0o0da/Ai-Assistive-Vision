# Wake-word and STT eval audio (WS24-11, AVN-234)

Real audio clips are **never committed** (`CLAUDE.md`: "Never commit ... field recordings";
`.gitignore` ignores `datasets/**/*.wav`, `*.m4a`, `*.mp3`). This folder holds only the committed,
content-free parts: this README, `manifest.schema.json` (the format) and `manifest.json` (the
index of what exists and where, with transcripts and consent references, never the audio bytes
themselves). The clips live in the team's private storage; `manifest.json`'s `path` field says
where, relative to that private storage root.

## What's blocked right now

The wake word itself is not decided yet -- TD-002 (`docs/decisions/README.md`) is still **Open**
(openWakeWord vs Porcupine custom keyword). Recording `wake` clips for a specific phrase before
that decision risks throwing the recordings away. Record everything else now; add `wake` clips
once TD-002 resolves.

## Categories (see `docs/demo-script.md` for the exact wording)

- **`command`**: the demo script's spoken commands, said by all 3 speakers, without the wake word
  prefix (that part is recorded separately once TD-002 lands): "What's around me?", "Read this.",
  "Take me to the nearest pharmacy.", "Open WhatsApp and read my new messages.", "Scroll down.",
  "Summarize these.", "Open Facebook and summarize this post.", "Help, SOS.", "Cancel."
- **`noise`**: ambient background with no spoken target at all (street, transit, cafe) -- used to
  check the pipeline doesn't fire on noise alone.
- **`wake`** (blocked, see above): positive clips of the chosen wake word, and a few near-miss
  negative phrases, once TD-002 is decided.

## Consent

Get a short verbal or written OK from each speaker before recording ("I agree to this recording
being used to test the app's speech recognition, kept private, never published"). Record who,
when and how they agreed in `manifest.json`'s `consent` field -- first name or initials only, no
other personal data. Same bystander spirit as `datasets/collection-protocol.md`: don't capture
other identifiable people's conversations in the background of `noise` clips; prefer traffic/transit
ambience over a crowded conversation.

## Adding a clip

1. Record it into the team's private storage (not this repo).
2. Add one entry to `manifest.json` with its `path` (relative to that private root), `category`,
   `speaker`, `consent` and `transcript` (the exact words said, for `command`/`wake`; omit for
   `noise`).
3. Validate it's well-formed JSON before committing just the manifest (the audio itself stays out).

## Done when (task's own acceptance bar)

Positive, negative and street-noise clips; demo command list recorded by all 3 speakers; manifest
frozen (every entry has its consent and transcript filled in, nothing left as a placeholder).
