# Test report: <test name> (TEST-xxx)

<!-- Template: docs/templates/test-report-template.md. Delete these comments before merging.
     Evidence is links only. Never attach raw frames, face images, screen text or recordings
     of people, and never paste secrets. -->

Date: YYYY-MM-DD | Run by: <name> | Workstream: WS-<nn> | Gate: G<n>
Build or commit: <short hash> | Result: Pass / Fail / Flaky / Blocked

## Summary

Two or three sentences: what was tested and the outcome.

## Scope

Feature under test, the demo scenario number (1 to 10) it supports, and what is out of scope.

## Environment

- Device: Android test phone / Second phone / Emulator / Laptop / CI
- Backend: real provider or mock mode (`MOCK_MODE`), scenario header if used
- Connectivity: online / offline

## Method

Numbered steps, precise enough for a teammate to repeat the run.

## Results

| Metric | Target | Measured | Meets target |
| --- | --- | --- | --- |
| <for example detector ms per frame> | <target> | <value> | yes / no |

## Evidence

- Link to CI run, log file, screenshot or report (link only).

## Issues found

- BUG-xxx: <short symptom> (severity)

## Consent reference

Field tests only: consent form ID. Never names or contact details.

## Conclusion

Pass or fail against the acceptance criteria, and the next step.