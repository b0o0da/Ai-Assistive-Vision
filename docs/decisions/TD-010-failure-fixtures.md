# TD-010 Failure-case fixtures for the contract (AVN-37)

- **Status:** Decided
- **Contract impact:** [x] Yes, minor bump 1.1.0 -> 1.2.0 (the mock-only `X-Mock-Scenario` header accepts four new values; no schema field changed)
- **Workstream:** WS-02 (API contracts and event schema)

## Decision

Add 19 failure fixtures in `contracts/fixtures/failures/` so Android can rehearse every failure path before the real backend exists. Together with the failure fixtures already in `contracts/fixtures/api/`, each of the five cloud endpoints (`describe`, `ask`, `screen_summarize`, `intent`, `route`) now has all five cases: timeout, provider_error (5xx), rate_limited (429), unauthorized (401), bad_request (malformed request).

## Mapping

| Case | error.code | ok | fallback | HTTP | X-Mock-Scenario |
| --- | --- | --- | --- | --- | --- |
| timeout | timeout | true | true | 504 | `timeout` |
| 5xx | provider_error | true | true | 5xx | `error` or `provider_error` |
| 429 | rate_limited | false | false | 429 | `rate_limited` |
| 401 | unauthorized | false | false | 401 | `unauthorized` |
| malformed request | bad_request | false | false | 400/422 | `bad_request` |

Files already in `api/` keep covering some cases: `describe.timeout`, `describe.provider_error`, `ask.timeout`, `screen_summarize.timeout`, `intent.fallback` (provider_error), `route.fallback` (timeout).

## Mock backend

`mock_scenario` in `backend/app/core/deps.py` refuses `rate_limited`, `unauthorized` and `bad_request` with 429, 401 and 400 before any router runs. The existing HTTP error handler builds the envelope, so `error.code`, `retryable` and the spoken `message` (`FALLBACK_MESSAGES`) match the fixtures. This applies to every endpoint including `/faces/match`, which otherwise reports a failed match as unknown. `provider_error` behaves exactly like `error` (`Scenario` in `backend/app/providers/mock.py`). `/health` ignores the header, and an unknown value is still a 400 `bad_request`. Tests: `backend/tests/test_mock_endpoints.py`.

## Not in scope

- A malformed provider response cannot be a valid envelope, so it belongs in `tests/fixtures/contract_invalid/`, not here.

## Privacy

Failure fixtures carry no images, screen text or embeddings. `message` never contains personal content.