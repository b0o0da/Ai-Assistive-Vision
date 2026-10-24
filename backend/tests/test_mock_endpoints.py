"""WS22-01: all 9 endpoints answer from the contract fixtures in MOCK_MODE, for every scenario."""

import json
from pathlib import Path

import httpx
import pytest

from app.core.config import Settings, get_settings
from app.core.envelope import Envelope
from app.main import create_app
from app.schemas import api
from app.services import gateway
from app.services.gateway import FALLBACKS

FIXTURES = Path(__file__).resolve().parents[2] / "contracts" / "fixtures"
API = FIXTURES / "api"

# name, method, path, request fixture (None = no body)
ENDPOINTS = [
    ("describe", "POST", "/describe", "describe.request.json"),
    ("ask", "POST", "/ask", "ask.request.json"),
    ("screen_summarize", "POST", "/screen/summarize", "screen_summarize.request.json"),
    ("intent", "POST", "/intent", "intent.request.json"),
    ("route", "GET", "/route", "route.request.json"),
    ("faces_enroll", "POST", "/faces/enroll", "faces_enroll.request.json"),
    ("faces_match", "POST", "/faces/match", "faces_match.request.json"),
    ("sos", "POST", "/sos", "sos.request.json"),
]


def load(name: str) -> dict:
    return json.loads((API / name).read_text(encoding="utf-8"))


def make_client(**overrides) -> httpx.AsyncClient:
    app = create_app()
    settings = Settings(**{"mock_mode": True, "provider_timeout_s": 0.05, **overrides})
    app.dependency_overrides[get_settings] = lambda: settings
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test")


@pytest.fixture
async def client():
    async with make_client() as c:
        yield c


async def send(client, method, path, body, scenario=None):
    headers = {"X-Mock-Scenario": scenario} if scenario else {}
    if method == "GET":
        return await client.get(path, params=body, headers=headers)
    return await client.post(path, json=body, headers=headers)


@pytest.mark.parametrize(("name", "method", "path", "req"), ENDPOINTS)
async def test_ok_scenario_serves_the_fixture(client, name, method, path, req):
    r = await send(client, method, path, load(req), "ok")
    assert r.status_code == 200
    body = Envelope.model_validate(r.json())
    assert body.ok and not body.fallback and body.message == "" and body.error is None
    assert body.data == load(f"{name}.ok.json")["data"]


@pytest.mark.parametrize(("name", "method", "path", "req"), ENDPOINTS)
async def test_no_scenario_header_means_ok(client, name, method, path, req):
    r = await send(client, method, path, load(req))
    assert r.status_code == 200 and r.json()["ok"] and not r.json()["fallback"]


@pytest.mark.parametrize(
    ("scenario", "code"), [("timeout", "timeout"), ("error", "provider_error"), ("empty", None)]
)
@pytest.mark.parametrize(("name", "method", "path", "req"), ENDPOINTS)
async def test_failure_scenarios_fall_back_with_spoken_message(
    client, name, method, path, req, scenario, code
):
    r = await send(client, method, path, load(req), scenario)
    assert r.status_code == 200
    body = Envelope.model_validate(r.json())
    assert body.ok
    if name == "faces_match":
        # Contract: a failed match is reported as unknown, never as a fallback or a name.
        assert not body.fallback and body.data == {"match": None, "similarity": 0.0}
        return
    assert body.fallback and body.data is None
    assert body.message == FALLBACKS[name]
    assert (body.error.code if body.error else None) == code


async def test_unknown_scenario_is_a_400_envelope(client):
    r = await send(client, "POST", "/describe", load("describe.request.json"), "explode")
    assert r.status_code == 400
    body = Envelope.model_validate(r.json())
    assert body.ok is False and body.error.code == "bad_request" and body.message


async def test_health_ignores_the_scenario_header(client):
    r = await client.get("/health", headers={"X-Mock-Scenario": "error"})
    assert r.status_code == 200 and r.json()["data"]["mock_mode"] is True


async def test_scenario_header_is_ignored_outside_mock_mode():
    async with make_client(mock_mode=False) as c:
        r = await send(c, "POST", "/ask", load("ask.request.json"), "explode")
    assert r.status_code == 200
    body = Envelope.model_validate(r.json())
    # No real provider yet: every call fails soft with the spoken fallback.
    assert body.fallback and body.error.code == "provider_error"
    assert body.message == FALLBACKS["ask"]


async def test_unknown_field_is_rejected(client):
    req = load("describe.request.json") | {"distance_m": 3}
    r = await send(client, "POST", "/describe", req)
    assert r.status_code == 422
    body = Envelope.model_validate(r.json())
    assert body.ok is False and body.error.code == "bad_request"


async def test_oversize_image_is_rejected(client):
    req = load("ask.request.json") | {"image": "A" * 400_001}
    r = await send(client, "POST", "/ask", req)
    assert r.status_code == 422 and r.json()["ok"] is False


async def test_blocked_screen_is_refused_before_any_provider(client):
    req = load("screen_summarize.request.json") | {"screen_type": "blocked"}
    r = await send(client, "POST", "/screen/summarize", req)
    assert r.status_code == 400
    assert r.json()["message"] == load("screen_summarize.blocked.json")["message"]


async def test_enroll_without_consent_is_refused(client):
    req = load("faces_enroll.request.json") | {"consent": False}
    r = await send(client, "POST", "/faces/enroll", req)
    assert r.status_code == 400
    assert r.json()["message"] == load("faces_enroll.no_consent.json")["message"]


async def test_enroll_rejects_an_image_field(client):
    req = load("faces_enroll.request.json") | {"image": "<base64>"}
    r = await send(client, "POST", "/faces/enroll", req)
    assert r.status_code == 422


@pytest.mark.parametrize(
    "params",
    [
        {"origin": "30.0444,31.2357", "mode": "walking"},
        {"origin": "here", "destination": "pharmacy", "mode": "walking"},
        {"origin": "30.0444,31.2357", "destination": "pharmacy", "mode": "driving"},
        {"origin": "30.0444,31.2357", "destination": "pharmacy", "mode": "walking", "x": "1"},
    ],
)
async def test_route_query_is_validated(client, params):
    r = await client.get("/route", params=params)
    assert r.status_code == 422 and r.json()["ok"] is False


async def test_provider_output_outside_the_contract_becomes_a_fallback():
    class BadPlan:
        async def fetch(self, endpoint, payload):
            return {"steps": [{"step": "delete_everything"}], "requires_confirmation": False}

    env = await gateway.call(BadPlan(), "intent", {}, api.ActionPlan, "req_t", 1.0)
    assert env.fallback and env.data is None and env.error.code == "provider_error"


# Contract test: every API fixture parses into the matching Pydantic model.
MODELS = {
    "HealthResponse": api.HealthResponse,
    "DescribeRequest": api.DescribeRequest,
    "DescribeResponse": api.DescribeResponse,
    "AskRequest": api.AskRequest,
    "AskResponse": api.AskResponse,
    "ScreenSummarizeRequest": api.ScreenSummarizeRequest,
    "ScreenSummarizeResponse": api.ScreenSummarizeResponse,
    "IntentRequest": api.IntentRequest,
    "IntentResponse": api.ActionPlan,
    "RouteRequestQuery": api.RouteRequestQuery,
    "RouteResponse": api.RouteResponse,
    "FacesEnrollRequest": api.FacesEnrollRequest,
    "FacesEnrollResponse": api.FacesEnrollResponse,
    "FacesMatchRequest": api.FacesMatchRequest,
    "FacesMatchResponse": api.FacesMatchResponse,
    "SosRequest": api.SosRequest,
    "SosResponse": api.SosResponse,
}
INDEX = json.loads((FIXTURES / "index.json").read_text(encoding="utf-8"))["fixtures"]
API_ENTRIES = [e for e in INDEX if e["fixture"].startswith("api/")]


@pytest.mark.parametrize("entry", API_ENTRIES, ids=lambda e: e["fixture"])
def test_every_api_fixture_parses_into_the_pydantic_models(entry):
    doc = json.loads((FIXTURES / entry["fixture"]).read_text(encoding="utf-8"))
    if entry["envelope"]:
        doc = Envelope.model_validate(doc).data
    if entry["schema"] != "envelope-only":
        MODELS[entry["schema"].split("/")[-1]].model_validate(doc)
