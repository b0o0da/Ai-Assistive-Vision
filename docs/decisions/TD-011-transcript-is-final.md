# TD-011: Should Transcript carry an is_final flag? (AVN-88)
Week 2 / Mahmoud / Status: Decided
Contract impact: [x] yes, contract version 1.2.1 -> 1.3.0 (see docs/api-contract.md section 7)

## Context
`Transcript` (TD-008, contract 1.1.0) has `text`, `confidence` and `language`. The task AVN-88 (WS12-02) asks for `isFinal` as well, because Android `SpeechRecognizer` emits partial results before the final one and the intent router must tell them apart. The task text also says `locale` in one place and `language` in another.

## Options considered
1. Add `is_final` as an optional boolean with default true (additive, minor bump).
2. Add `is_final` as a required field (existing fixtures and all `Transcript(...)` callers break; major bump).
3. No flag; infer partial results from the engine API (hides the contract in code, nothing to test).

## Decision
Option 1. `Transcript{text, confidence, language, is_final}`.
- Kotlin: `@SerialName("is_final") val isFinal: Boolean = true`, so `FakeSttEngine`, `RuleIntentRouterTest` and the older tests keep compiling.
- Schema: `is_final` is `{"type": "boolean", "default": true}` and not in `required`; absent means true. The 8 existing fixtures stay valid and keep their hashes.
- Field name stays `language` (not `locale`): the schema, architecture, `FakeSttEngine` and `RuleIntentRouterTest` already use it.
- New fixtures: `transcripts/final.json` (is_final true) and `transcripts/partial.json` (is_final false). An empty transcript remains valid.
- Contract version 1.2.1 -> 1.3.0 (additive = minor).

## Consequences (code, contract, licence, schedule)
- Code: `Models.kt`, `TranscriptContractTest.kt` (reads fields from the serializer descriptor, because the Kotlin Json does not encode defaults).
- Contract: schema, fixtures, `index.json`, `contract.lock.json` (relock), `docs/api-contract.md` change log. The Pydantic mirror has no Transcript model today (no endpoint carries it), so nothing to change on the backend.
- Consent: if the platform recognizer uses the network, the consent text must say so (task "Watch out"). This does not change the contract.
- Privacy: fixtures are fake data; transcripts must never be written to logs.
- Schedule: unblocks WS12-04 (`AndroidSttEngine` wrapper, which sets `is_final = false` for partial results).
