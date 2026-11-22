# TD-005 Backend authentication scheme (AVN-115)

- **Status:** Decided
- **Contract impact:** [x] Yes, patch 1.2.0 -> 1.2.1 (documents key format, rotation, 401 and 429 rules and the Retry-After header; no schema field, enum value or fixture changed)
- **Workstream:** WS-22 (Backend core and deployment)
- **Decide by:** Gate 1
- **Implemented by:** WS22-04 (not part of this task)

## Question

How does the app prove to the backend that it is an allowed install, so nobody else can spend the paid VLM and Maps quota? The architecture (WS-22) says: per-install API key header plus HTTPS and rate limit (proposed; the spec is silent), keys only in env.

## Options

| Option | For | Against |
| --- | --- | --- |
| A. Static per-install key in `X-API-Key` (chosen) | Already in contract section 3; trivial for Android; revocable per install; fits a 2-3 phone demo | A leaked key works until revoked |
| B. Signed short-lived tokens (JWT/OAuth) | Short exposure | Needs a login flow and a token endpoint; only 9 endpoints are allowed |
| C. One shared key for all installs | Simplest | One leak affects everyone; no per-install limit |
| D. No auth, rate limit by IP only | Nothing to provision | Anyone who finds the URL can spend money |

## Decision

Option A.

1. **Header:** `X-API-Key` on every endpoint except `GET /health`.
2. **Key format:** `avn_` + 32 random bytes in base64url without padding (43 chars), 47 characters in total. The prefix lets the CI secret scan recognise a leaked key.
3. **Server storage:** only SHA-256 hashes, read from the environment (variable name fixed in WS22-04). The raw key is never stored or logged. Comparison is constant-time. Keys are random 256-bit values, so a plain hash is enough.
4. **Client storage:** the key is entered at provisioning of the sideloaded APK and kept in app settings. It is never committed to Git.
5. **Rotation and revocation:** several hashes are valid at once. Rotate by adding the new hash, updating the phone, then removing the old hash. Revoke by removing the hash. No app rebuild is needed.
6. **Failure mapping:** missing, malformed, unknown or revoked key all give `401`, `error.code = unauthorized`, `retryable = false`, with the same spoken message. The reason is never revealed. `403` is not used for keys.
7. **Rate limit:** per key. Over the limit gives `429`, `error.code = rate_limited`, `retryable = true`, plus an HTTP `Retry-After` header (whole seconds). `Retry-After` is a response header only; the envelope is unchanged.
8. **Starting values (config, not contract):** fixed 60 s window, 60 requests overall, 20 for `/describe`, `/ask`, `/screen/summarize` and `/intent`. Tuned from logs.
9. **Envelope, error codes and fixtures are unchanged.** Both responses reuse the existing `unauthorized` and `rate_limited` envelopes (`error.unauthorized.json`, `error.rate_limited.json`, `failures/*.unauthorized.json`, `failures/*.rate_limited.json`).

## Proposed for WS22-04 (confirm there, not decided here)

- Auth is a FastAPI dependency, not middleware, so `/health` stays open. Order: body size (413, already in `BodySizeLimit`) -> key (401) -> rate limit (429) -> `mock_scenario` -> router.
- `MOCK_MODE=true`: auth and rate limit off, so Android can test with no setup. `MOCK_MODE=false`: refuse to start with no keys, and `X-Mock-Scenario` stays ignored so it can never bypass or fake auth.
- Android: on 401 speak the message and do not retry. On 429 speak the message and retry at most once, and only if `Retry-After` is 2 s or less (contract section 3: at most one retry).

## Follow-ups found while reading the code (for WS22-04)

- `http_error_handler` and `_reply` in `backend/app/core/middleware.py` build the response from the status only and drop `exc.headers`. They must pass the headers through, or `Retry-After` is lost.
- `refuse()` in `backend/app/core/deps.py` always uses `ErrorCode.bad_request`, even with status 401 or 429. Do not use it for auth.
- Log only a `key_id` (first 8 hex chars of the hash). Never log the key or its prefix.
- Add a CI secret-scan rule for the `avn_` prefix. Docs and fixtures use `avn_<43 chars>` placeholders only.
- The mock `rate_limited` scenario can also send `Retry-After` so Android can rehearse it.
- There is no rate limiter in `main.py` yet, so "rate limit per key" in contract section 3 is not implemented today.

## Not in scope

Auth code, the key registry, the rate limiter, signed tokens, user accounts, per-user quotas.

## Privacy

The key identifies an install, not a person. It is never logged. `message` and `error` carry no key material and no personal content.
