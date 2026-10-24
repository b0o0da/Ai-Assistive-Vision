# Technical decisions

Each decision is also a row in the Notion "Technical Decisions" database. Record the outcome here when it is made: context, options, decision, consequences.

| ID | Question | Options | Due | Status |
| --- | --- | --- | --- | --- |
| TD-001 | Detector licence | YOLO nano (AGPL: accept and release compatibly) vs an Apache-licensed detector | Before week 3 | Open |
| TD-002 | Wake word library | openWakeWord vs Porcupine custom keyword | Before week 3 | Open |
| TD-004 | Screenshot method | MediaProjection (per-session consent) vs AccessibilityService.takeScreenshot | Week 3 | Open |
| TD-007 | Third tested app for screen reading | e.g. Chrome (enables web reading) vs another app | Week 3 | Open |

Template for a decision file `TD-xxx-short-name.md`:

```
# TD-xxx: <question>
Date · Owner · Status: Decided
Contract impact: [ ] none  [ ] yes, contract version X.Y.Z → X.Y.Z (see docs/api-contract.md §7)
## Context
## Options considered
## Decision
## Consequences (code, contract, licence, schedule)
```
