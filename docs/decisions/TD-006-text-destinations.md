# TD-006: Text destinations in GET /route (WS20-03, AVN-119)

22 Nov 2026 · Owner: Mostafa O. · Status: Decided
Contract impact: [x] none

## Context

WS-20 navigation starts from whatever destination phrase the user actually says ("take me to
the nearest pharmacy", "work", "30.0452,31.2368"), carried through WS-14's `ActionPlan.navigate`
step as free text. `GET /route` needs to turn that into walking directions. TD-006 decides where
the text-to-place resolution happens: inside the backend's Maps proxy, or on the phone before it
ever calls `/route`.

## Options considered

- **A. Backend resolves text inside the Maps proxy.** `destination` stays a free string; the real
  Maps provider (WS22-12) does a places/geocoding lookup when it isn't `lat,lon`, same call that
  already fetches the walking route.
- **B. `/route` accepts coordinates only.** The phone (or another backend step) geocodes the
  spoken destination into `lat,lon` first, then calls `/route`.

## Decision

**A.** The backend resolves text destinations inside the Maps proxy. `RouteRequestQuery.destination`
stays a plain string (`backend/app/schemas/api.py`, 1-200 chars, no coordinate-only pattern — unlike
`origin`, which is always the phone's live GPS fix and keeps its `lat,lon` pattern). `RouteResponse`
already carries back `destination_name`, what the proxy resolved the text to.

Why: a second geocoding round trip from the phone would add a network hop and a new failure mode
for no benefit, and geocoding needs a Maps API key, which must stay server-side (`backend/CLAUDE.md`
rule 12: keys never leave the backend). The backend already owns every paid outside Maps call, so
resolving the destination is the same provider call as fetching the route, not a separate one.

## Consequences (code, contract, licence, schedule)

- **Contract:** no change. `docs/api-contract.md`'s `destination=<lat,lon or text>` and
  `RouteRequestQuery.destination: str` were already written this way — this decision formalizes
  the existing contract, it doesn't move it.
- **Fixtures:** `contracts/fixtures/api/route.request.json` already uses a text destination
  (`"nearest pharmacy"`), and `route.ok.json` already returns the resolved `destination_name`
  (`"Pharmacy (example)"`). WS20-03's "fixture request updated" acceptance criterion is satisfied
  by the existing fixtures; nothing to add.
- **Code:** the real Maps provider (WS22-12, not yet built — `backend/app/providers/` only has
  `mock.py` today) must do a places/geocoding lookup whenever `destination` doesn't parse as
  `lat,lon`, in addition to the directions call. That provider's job, not a new field or endpoint.
- **Schedule:** none. This closes WS20-03 before the Gate 1 contract freeze without touching any
  frozen field.
