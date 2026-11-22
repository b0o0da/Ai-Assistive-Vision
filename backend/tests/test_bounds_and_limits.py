"""WS22-07 (AVN-195): per-endpoint time budgets, text capped to the contract, 413 over 600 KB."""

import asyncio
import json
import time
from pathlib import Path
from typing import Any

import httpx
import pytest

from app.core.config import Settings, get_settings
from app.core.deps import get_provider
from app.core.envelope import Envelope
from app.core.middleware import TOO_LARGE
from app.main import create_app
from app.schemas.api import ActionPlan, AskResponse, DescribeResponse, RouteResponse
from app.services import gateway
from app.services.bounds import cap_strings, truncate_text

JSON_HEADERS = {"Content-Type": "application/json"}
API = Path(__file__).resolve().parents[2] / "contracts" / "fixtures" / "api"


def load(name: str) -> dict:
    return json.loads((API / name).read_text(encoding="utf-8"))


class StubProvider:
    """Answers with fixed data after an optional delay."""

    def __init__(self, data: dict[str, Any] | None, delay_s: float = 0.0) -> None:
        self.data, self.delay_s = data, delay_s

    async def fetch(self, endpoint: str, payload: dict[str, Any]) -> dict[str, Any] | None:
        await asyncio.sleep(self.delay_s)
        return self.data


def client_with(provider=None, **overrides) -> httpx.AsyncClient:
    app = create_app()
    settings = Settings(**{"mock_mode": True, **overrides})
    app.dependency_overrides[get_settings] = lambda: settings
    if provider is not None:
        app.dependency_overrides[get_provider] = lambda: provider
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test")


# ------------------------------------------------------------------ time budgets


def test_budget_is_per_endpoint_and_never_above_the_ceiling():
    s = Settings(provider_timeout_s=4.0)
    assert s.budget_for("faces_match") == 1.5
    assert s.budget_for("describe") == 4.0
    assert s.budget_for("unknown") == 4.0
    low = Settings(provider_timeout_s=0.5)
    assert low.budget_for("describe") == 0.5
    custom = Settings(endpoint_budgets_s={"describe": 0.2})
    assert custom.budget_for("describe") == 0.2


async def test_slow_provider_gets_fallback_within_the_endpoint_budget():
    provider = StubProvider(load("describe.ok.json")["data"], delay_s=2.0)
    async with client_with(provider, endpoint_budgets_s={"describe": 0.1}) as c:
        start = time.perf_counter()
        r = await c.post("/describe", json=load("describe.request.json"))
        elapsed = time.perf_counter() - start
    env = Envelope.model_validate(r.json())
    assert r.status_code == 200
    assert env.ok and env.fallback and env.data is None
    assert env.error is not None and env.error.code == "timeout"
    assert env.message == gateway.FALLBACKS["describe"]
    assert elapsed < 1.0


async def test_fast_provider_inside_budget_succeeds():
    provider = StubProvider(load("ask.ok.json")["data"], delay_s=0.01)
    async with client_with(provider, endpoint_budgets_s={"ask": 0.5}) as c:
        r = await c.post("/ask", json=load("ask.request.json"))
    env = Envelope.model_validate(r.json())
    assert env.ok and not env.fallback and env.data["answer"]


# ------------------------------------------------------------------ text caps


def test_truncate_keeps_short_text():
    assert truncate_text("A chair ahead.", 400) == "A chair ahead."


def test_truncate_cuts_at_sentence_end():
    text = "A table is ahead. Two chairs are on the left. " + "x" * 500
    out = truncate_text(text, 100)
    assert out == "A table is ahead. Two chairs are on the left."


def test_truncate_falls_back_to_word_boundary():
    text = ("word " * 200).strip()
    out = truncate_text(text, 50)
    assert len(out) <= 50 and out.endswith("word.") and "  " not in out


def test_truncate_hard_cut_when_no_space():
    assert truncate_text("y" * 900, 400) == "y" * 400


def test_cap_strings_handles_nested_lists():
    data = load("route.ok.json")["data"]
    data["steps"][0]["instruction"] = "Turn left now. " + "z" * 600
    capped = cap_strings(data, RouteResponse)
    assert capped["steps"][0]["instruction"] == "Turn left now."
    RouteResponse.model_validate(capped)
    assert data["steps"][0]["instruction"].startswith("Turn left now. zzz")  # input not mutated


def test_cap_strings_handles_optional_fields():
    plan = {"steps": [], "requires_confirmation": False, "clarification": "Which app? " * 40}
    capped = cap_strings(plan, ActionPlan)
    assert len(capped["clarification"]) <= 200
    ActionPlan.model_validate(capped)


@pytest.mark.parametrize(
    ("endpoint", "path", "req", "model", "key", "limit"),
    [
        ("describe", "/describe", "describe.request.json", DescribeResponse, "description", 400),
        ("ask", "/ask", "ask.request.json", AskResponse, "answer", 600),
    ],
)
async def test_long_provider_answer_is_capped_not_dropped(endpoint, path, req, model, key, limit):
    long = "There is a door ahead on the right. " * 50
    async with client_with(StubProvider({key: long})) as c:
        r = await c.post(path, json=load(req))
    env = Envelope.model_validate(r.json())
    assert env.ok and not env.fallback
    assert 0 < len(env.data[key]) <= limit
    assert env.data[key].endswith(".")
    model.model_validate(env.data)


async def test_answer_still_invalid_after_capping_becomes_fallback():
    async with client_with(StubProvider({"description": 42})) as c:
        r = await c.post("/describe", json=load("describe.request.json"))
    env = Envelope.model_validate(r.json())
    assert env.fallback and env.error is not None and env.error.code == "provider_error"


# ------------------------------------------------------------------ 600 KB body limit


async def test_oversize_body_is_refused_with_413_envelope():
    body = load("describe.request.json")
    body["image"] = "A" * 650_000
    async with client_with() as c:
        r = await c.post("/describe", json=body, headers={"X-Request-Id": "req_big1"})
    assert r.status_code == 413
    env = Envelope.model_validate(r.json())
    assert not env.ok and env.message == TOO_LARGE
    assert env.error is not None and env.error.code == "bad_request"
    assert env.request_id == "req_big1" == r.headers["x-request-id"]


async def test_body_just_under_the_limit_reaches_validation():
    body = load("describe.request.json")
    body["image"] = "A" * 590_000  # under 600 KB but over the contract's 400,000 chars
    async with client_with() as c:
        r = await c.post("/describe", json=body)
    assert r.status_code == 422  # refused by the schema, not by the size limit
    assert Envelope.model_validate(r.json()).message != TOO_LARGE


async def test_chunked_oversize_body_without_content_length_is_refused():
    async def chunks():
        for _ in range(7):
            yield b"x" * 100_000

    async with client_with() as c:
        r = await c.post("/describe", content=chunks(), headers=JSON_HEADERS)
    assert r.status_code == 413
    assert Envelope.model_validate(r.json()).message == TOO_LARGE


async def test_chunked_small_body_is_replayed_to_the_app():
    payload = json.dumps(load("describe.request.json")).encode()

    async def chunks():
        yield payload[:10]
        yield payload[10:]

    async with client_with() as c:
        r = await c.post("/describe", content=chunks(), headers=JSON_HEADERS)
    assert r.status_code == 200
    assert Envelope.model_validate(r.json()).ok


async def test_get_route_is_not_affected():
    async with client_with() as c:
        r = await c.get("/route", params=load("route.request.json"))
    assert r.status_code == 200
