# OCR accuracy fixtures (WS07-02, AVN-129)

Real, own-made photos the real OCR engine is judged on (WS07-06). This is a different set from
`android/.../test/resources` or `tests/fixtures/ocr/` (WS07-01): those are small invented-text
fixtures replayed by `FakeOcrEngine` in unit tests; these are real photographs used to measure
real accuracy, so they are the opposite of invented -- each one must be a genuine photo someone on
the team took themselves, never downloaded, never containing a real person's private document.

## Status

Empty. Needs at least 20 own-made images across the three categories below before WS07-06 (OCR
accuracy evaluation) can run. See `expected.schema.json` for the format `expected.json` must
follow once images are added.

## Categories (match `docs/demo-script.md` scenario 5: "Read a sign, a menu and a medicine box")

- **`sign`**: a street sign, shop sign or directional/informational sign.
- **`menu`**: a restaurant/cafe menu, printed or handwritten.
- **`medicine_box`**: a medicine box or printed insert. Read back verbatim only in the app --
  never treat as medical advice (`android/CLAUDE.md`, this task's own watch-out).

Vary lighting, angle and distance on purpose, including a few harder shots (glare, small text,
an angle), not just the easy case -- the done-when bar is a usable accuracy measurement, not a
curated highlight reel.

## Adding a photo

1. Take the photo yourself. No downloaded images -- every file here needs to be licence-safe,
   which for this fixture set means "I took it".
2. Save it as `<category>_<short-description>.jpg`, e.g. `sign_pharmacy_01.jpg`.
3. Add one entry to `expected.json` with the **exact** text in the photo, character for character.
4. Add one line to this README's `## Sources` section: filename, roughly where/when, and that you
   took it yourself.
5. If a bystander ends up identifiable in frame, discard the photo or crop/blur them out before
   adding it here (same rule as `datasets/collection-protocol.md`).

## Sources

(Fill in as images are added: `<filename>` -- taken by \<you\>, \<rough place/date\>.)
