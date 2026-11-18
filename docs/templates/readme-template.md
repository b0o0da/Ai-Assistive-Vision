# <Module name>

<!-- Template: docs/templates/readme-template.md. Delete these comments before merging.
     Never paste secrets, keys, personal data or real contact details into docs. -->

Owner: <name> | Workstream: WS-<nn> | Tier: Must / Should / Nice | Status: Not started / Active / Done

## Purpose

One or two sentences: what this module does, and which demo scenario or safety rule it serves.

## Status and scope

What works now, what is still a fake or mock, and what is out of scope
(see `docs/architecture.md`, section 8).

## Repository layout

| Path | What it holds |
| --- | --- |
| `path/to/folder/` | <one line> |

## Getting started

Commands to build, run and test this module on a clean machine. Start in mock mode.

```
<command 1>
<command 2>
```

## Interfaces and fakes

Public interfaces, the fake or fixture that stands in for them, and who consumes them.

## Configuration

Names of environment variables and config files only (for example `MOCK_MODE`).
Never write real values. Point to `.env.example`.

## Testing

Which tests cover this module, how to run them, and where the evidence is kept.

## Privacy and safety

Consent gate, what is never logged (frames, screen text, notification text, embeddings),
and any safety wording rules that apply (no meters for hazards, no speed claims).

## Key documents

- `docs/<related-doc>.md`