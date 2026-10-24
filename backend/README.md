# AVN backend

Thin FastAPI service between the Android app and paid cloud services. Rules: `backend/CLAUDE.md`. Contract: `docs/api-contract.md` and `contracts/`.

## Status

All 9 contract endpoints answer in mock mode (WS22-01): `GET /health`, `POST /describe`, `/ask`, `/screen/summarize`, `/intent`, `GET /route`, `POST /faces/enroll`, `/faces/match`, `/sos`. With `MOCK_MODE=true` each one returns the data of `contracts/fixtures/api/<endpoint>.ok.json`.

Still to build, from `docs/claude-code/backend-prompts.md`: `X-API-Key` auth and rate limit (WS22-04, after TD-005), image decode and size checks (WS22-07), real providers (B2), database and faces (B3), route proxy (B4), deploy (B5). With `MOCK_MODE=false` every endpoint except `/health` answers with its spoken fallback until a real provider exists.

## Mock scenarios

Send `X-Mock-Scenario` to rehearse the failure paths. It is honoured only when `MOCK_MODE=true`.

| Value | What the server does | Envelope |
| --- | --- | --- |
| `ok` (or no header) | Returns the endpoint's fixture | `ok:true, fallback:false` |
| `timeout` | Waits past `PROVIDER_TIMEOUT_S` | `fallback:true`, error `timeout` |
| `error` | Provider fails | `fallback:true`, error `provider_error` |
| `empty` | Provider returns nothing | `fallback:true`, error `null` |
| anything else | Refuses the request | HTTP 400, `ok:false`, error `bad_request` |

`/faces/match` never falls back: a failed match is returned as unknown (`match: null`). A `screen_type` of `blocked` is refused with "I can't read this screen." and `/faces/enroll` without `consent: true` is refused, in every mode.

```bash
curl -X POST localhost:8000/ask -H "Content-Type: application/json" -H "X-Mock-Scenario: timeout" \
  -d @../contracts/fixtures/api/ask.request.json
```

## Run

```bash
cp ../.env.example ../.env       # keep MOCK_MODE=true until you have keys
docker compose up --build        # http://localhost:8000/docs
```

Without Docker (Python 3.12+):

```bash
python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"
ruff check . && pytest
uvicorn app.main:app --reload
```
