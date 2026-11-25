# Tests (WS-26)

- `fixtures/detections/` — FakeDetector JSON (per-frame boxes)
- `fixtures/contract_invalid/` — payloads every contract layer must reject (JSON Schema, Pydantic, Kotlin); see `index.json`
- `fixtures/scenarios/` — priority-engine timelines with expected decisions
- `fixtures/commands/` — spoken-command corpus for the intent parser (Boda)
- `fixtures/routes/` — GPX files for navigation replay
- `fixtures/audio/` — short audio clips (no personal speech)
- `fixtures/images/` — OCR and VLM golden set (no faces of non-consenting people)
- `mock_screens/` — mock messaging screens for the AccessibilityService
- `e2e/` — end-to-end demo checks
- `field_logs/` — field-test alert logs (ignored by Git; anonymised exports go to Notion evidence)

Shared contract fixtures live in `contracts/fixtures/`.
