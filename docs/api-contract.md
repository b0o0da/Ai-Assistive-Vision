# AVN API and event contract

**Version 0.2.0-draft · status: DRAFT, frozen at Gate 1 (end of week 2) after review by all six members.**
Machine-readable source: `contracts/schemas/` and `contracts/fixtures/`. If this page and the schemas disagree, the schemas win and this page is fixed.

## 1. Conventions
- JSON, UTF-8, `snake_case` field names.
- Timestamps: integer Unix epoch **milliseconds**.
- Hazard distance is `near | medium | far`. Never meters, speed or time-to-collision. Route data may use meters.
- Unknown fields are rejected (`additionalProperties: false`).
- Fields that may carry personal data: `image`, `text` (screen), `question`, `embedding`, coordinates. They are never logged and never stored by default.

## 2. Event (phone-internal, every producer → priority engine)

| Field | Type | Notes |
| --- | --- | --- |
| `id` | string | `evt_` + id |
| `source` | enum | `camera, voice, screen, navigation, notification, system` |
| `type` | enum | `vehicle_approaching, obstacle_ahead, person_close, person_known, person_unknown, scene_answer, text_result, screen_summary, notification, nav_instruction, system_notice, sos_countdown` |
| `label` | string | class or short label (`car`, `chair`, `whatsapp`) |
| `side` | enum or null | `left, center, right` |
| `distance_band` | enum or null | `near, medium, far` |
| `confidence` | number 0–1 | detector or source confidence |
| `risk` | integer 0–100 | computed by the engine formula (architecture §5) |
| `timestamp` | integer | epoch ms |

Example: `contracts/fixtures/events/vehicle_approaching_left.json`.

## 3. Transport rules (all endpoints)
- HTTPS. Header `X-API-Key: <per-install key>` on every endpoint except `GET /health`. Rate limit per key.
- Header `X-Request-Id` (optional, echoed as `request_id`).
- Header `X-Mock-Scenario: ok | timeout | error | empty` — honoured only when the server runs with `MOCK_MODE=true`.
- Images: base64 JPEG, longest side 512–768 px, ≤ 400,000 base64 characters.
- Server provider timeout `PROVIDER_TIMEOUT_S` (default 4 s). Client timeout = server timeout + 1 s. The client retries at most once.

## 4. Envelope (every response)

Schema: `contracts/schemas/envelope.schema.json`. Backend: `backend/app/core/envelope.py`. Android: `ApiEnvelope` in `Models.kt`.

```json
{
  "version": "1.0",
  "ok": true,
  "fallback": false,
  "message": "",
  "data": { },
  "request_id": "req_123",
  "error": null
}
```

| Field | Type | Meaning |
| --- | --- | --- |
| `version` | string | Envelope version, currently `"1.0"`. The client rejects other values. |
| `ok` | boolean | `false` when the request itself was rejected. |
| `fallback` | boolean | `true` when the provider timed out, failed or returned nothing. |
| `message` | string, max 300 | Sentence the phone speaks as is (the `fallback_message`). Never empty when `ok` is false or `fallback` is true. |
| `data` | object or null | The endpoint's response. Always null when `fallback` is true or `ok` is false. |
| `request_id` | string, 1-64 | Echo of `X-Request-Id`, or generated. |
| `error` | object or null | `{code, retryable}`. Required when `ok` is false; set on fallbacks that have a cause. |

| Case | ok | fallback | message | data | error |
| --- | --- | --- | --- | --- | --- |
| Success | true | false | "" | response object | null |
| Provider timeout, error or empty output | true | true | speakable sentence | null | `timeout` or `provider_error` |
| Invalid, oversize, unauthorized, rate-limited or refused request | false | false | speakable sentence | null | required |

The phone speaks `message` whenever `data` is null. A stack trace is never returned.

### Error codes

| Code | retryable | Default spoken message | Typical HTTP status |
| --- | --- | --- | --- |
| `timeout` | true | "I can't do that right now. It's taking too long." | 408, 504 |
| `rate_limited` | true | "Too many requests. Please try again in a moment." | 429 |
| `unauthorized` | false | "I can't connect to the service. The app needs to be set up again." | 401, 403 |
| `bad_request` | false | "Sorry, I could not understand that request." | 400, 404, 405, 422 |
| `provider_error` | true | "The service had a problem. Please try again shortly." | 5xx |

The `code` is fixed. An endpoint may replace the default `message` with a more specific sentence (see "Fallback messages" below). Messages never contain personal content.

### Privacy

- `data` may carry personal content: scene descriptions, screen summaries, face match names.
- Requests may carry images, screen text and embeddings.
- None of these are persisted or logged by default. Logs hold only request id, endpoint, status and latency.
- `message` and `error` must never contain personal content.

## 5. Endpoints

| Endpoint | Request | Response `data` | Tier |
| --- | --- | --- | --- |
| `GET /health` | — | `{status, version, mock_mode}` | Must |
| `POST /describe` | `{image, context?, language}` | `{description}` (≤ 400 chars, 1–2 sentences) | Must |
| `POST /ask` | `{image, question, context?}` | `{answer}` (≤ 600 chars) | Must |
| `POST /screen/summarize` | `{package, screen_type, text, image?, question}` | `{summary}` (≤ 1200 chars) | Must |
| `POST /intent` | `{text, context?}` | `ActionPlan {steps[], requires_confirmation, spoken_ack?, clarification?}` | Must |
| `GET /route` | query `origin=lat,lon`, `destination=<lat,lon or text>`, `mode=walking` | `{steps[], polyline, distance_m, duration_s, destination_name?}` | Must |
| `POST /faces/enroll` | `{display_name, embedding, model_version, consent:true}` | `{person_id}` | Should |
| `POST /faces/match` | `{embedding, model_version}` | `{match: name or null, similarity}` | Should |
| `POST /sos` | `{lat, lon, accuracy_m, fix_age_s, ts, contact_ids}` | `{accepted}` | Nice |

`context` (optional on describe, ask, intent): `{mode: idle|walking|navigating|reading|screen, navigation_active, language: en|ar, recent_events (≤300 chars)}`.

### Plan steps (whitelist for `/intent`)
`launch_app, wait, read_screen, summarize, scroll, back, tap_label, navigate, sos, describe, ask` — each step `{step, arg?}`. The server drops unknown steps. No valid step → `clarification` is set. Any step that could send, delete, buy or pay sets `requires_confirmation: true`.

### Fallback messages (spoken)
| Endpoint | Fallback |
| --- | --- |
| `/describe`, `/ask` | "I can't describe the scene right now." |
| `/screen/summarize` | phone speaks raw tree text if it has it; else "I can't summarize this screen right now." |
| `/screen/summarize` blocked | `ok:false`, "I can't read this screen." |
| `/intent` | "I didn't understand that. Please say it another way." |
| `/route` | cached route if present; else "I can't get directions right now." |
| `/faces/match` | `match: null` (unknown) |
| `/sos` | failure swallowed; local SMS is the real SOS |

## 6. Change log
| Version | Date | Change |
| --- | --- | --- |
| 0.1.0-draft | Week 1 | First draft from the architecture blueprint (field names marked "proposed" there). |
| 0.2.0-draft | Week 2 | Envelope gains `version` and `error {code, retryable}`; five error codes with default spoken messages; privacy note. AVN-33. |