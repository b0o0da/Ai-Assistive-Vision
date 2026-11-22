"""WS22-12 (AVN-292): GET /route with the Google Maps provider, the route cache and fallbacks.

No network: Google is replaced by an httpx.MockTransport that serves recorded-shape JSON.
"""

import json
from pathlib import Path

import httpx
import pytest

from app.core.config import Settings, get_settings
from app.core.envelope import Envelope
from app.main import create_app
from app.providers.base import ProviderError
from app.providers.maps import (
    GoogleMapsRouteProvider,
    nearest_keyword,
    parse_latlon,
    plain_instruction,
    to_route_response,
)
from app.routers.route import get_route_provider
from app.schemas.api import RouteResponse
from app.services.gateway import FALLBACKS
from app.services.route_cache import RouteCache, route_cache, route_key

API = Path(__file__).resolve().parents[2] / "contracts" / "fixtures" / "api"
QUERY = json.loads((API / "route.request.json").read_text(encoding="utf-8"))

DIRECTIONS = {
    "status": "OK",
    "routes": [{
        "overview_polyline": {"points": "abc123"},
        "legs": [{
            "distance": {"value": 210},
            "duration": {"value": 170},
            "end_address": "12 Example St, Cairo",
            "end_location": {"lat": 30.0452, "lng": 31.2368},
            "steps": [
                {"html_instructions": "Head <b>north</b> on Example&nbsp;St",
                 "distance": {"value": 120}, "end_location": {"lat": 30.0447, "lng": 31.2359}},
                {"html_instructions": "Turn <b>right</b>", "maneuver": "turn-right",
                 "distance": {"value": 80}, "end_location": {"lat": 30.0452, "lng": 31.2366}},
                {"html_instructions": "Take the ferry", "maneuver": "ferry",
                 "distance": {"value": 10}, "end_location": {"lat": 30.0452, "lng": 31.2368}},
            ],
        }],
    }],
}
NEARBY = {
    "status": "OK",
    "results": [{"name": "El Ezaby Pharmacy",
                 "geometry": {"location": {"lat": 30.0452, "lng": 31.2368}}}],
}


class FakeGoogle:
    """httpx transport standing in for maps.googleapis.com; records what was asked."""

    def __init__(self, directions=DIRECTIONS, nearby=NEARBY, fail: bool = False) -> None:
        self.directions, self.nearby, self.fail = directions, nearby, fail
        self.calls: list[httpx.Request] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.calls.append(request)
        if self.fail:
            return httpx.Response(503)
        if request.url.path.endswith("nearbysearch/json"):
            return httpx.Response(200, json=self.nearby)
        return httpx.Response(200, json=self.directions)

    def provider(self) -> GoogleMapsRouteProvider:
        client = httpx.AsyncClient(transport=httpx.MockTransport(self))
        return GoogleMapsRouteProvider("test-key", client=client)


@pytest.fixture(autouse=True)
def _clean_cache():
    route_cache.clear()
    yield
    route_cache.clear()


def client_for(provider, **overrides) -> httpx.AsyncClient:
    app = create_app()
    settings = Settings(**{"mock_mode": False, "maps_api_key": "test-key", **overrides})
    app.dependency_overrides[get_settings] = lambda: settings
    app.dependency_overrides[get_route_provider] = lambda: provider
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test")


# ------------------------------------------------------------------ parsing helpers


def test_destination_forms():
    assert parse_latlon("30.04,31.23") == (30.04, 31.23)
    assert parse_latlon(" -1.5 , 2 ") == (-1.5, 2.0)
    assert parse_latlon("95,10") is None
    assert parse_latlon("Tahrir Square") is None
    assert nearest_keyword("nearest pharmacy") == "pharmacy"
    assert nearest_keyword("the closest ATM ") == "ATM"
    assert nearest_keyword("Tahrir Square") is None


def test_html_instructions_become_plain_speech():
    raw = "Turn <b>left</b> onto <div>Nile&nbsp;St</div>"
    assert plain_instruction(raw) == "Turn left onto Nile St"
    assert plain_instruction("") == "Continue"


def test_google_json_maps_to_contract():
    data = to_route_response(DIRECTIONS, "El Ezaby Pharmacy")
    RouteResponse.model_validate(data)
    assert [s["maneuver"] for s in data["steps"]] == ["start", "turn_right", "other", "arrive"]
    assert data["steps"][0]["instruction"] == "Head north on Example St"
    assert data["steps"][-1]["instruction"] == "You have arrived at El Ezaby Pharmacy"
    assert data["distance_m"] == 210 and data["duration_s"] == 170
    assert data["polyline"] == "abc123"


def test_end_address_used_when_no_place_name():
    assert to_route_response(DIRECTIONS, None)["destination_name"] == "12 Example St, Cairo"


def test_no_route_is_a_provider_error():
    with pytest.raises(ProviderError):
        to_route_response({"status": "OK", "routes": []}, None)


# ------------------------------------------------------------------ provider calls


async def test_nearest_place_is_resolved_then_routed():
    google = FakeGoogle()
    data = await google.provider().fetch("route", QUERY)
    assert data["destination_name"] == "El Ezaby Pharmacy"
    nearby, directions = google.calls
    assert nearby.url.params["rankby"] == "distance" and nearby.url.params["keyword"] == "pharmacy"
    assert nearby.url.params["location"] == QUERY["origin"]
    assert directions.url.params["destination"] == "30.0452,31.2368"
    assert directions.url.params["mode"] == "walking"


async def test_text_destination_goes_straight_to_directions():
    google = FakeGoogle()
    await google.provider().fetch("route", {**QUERY, "destination": "Tahrir Square"})
    assert len(google.calls) == 1
    assert google.calls[0].url.params["destination"] == "Tahrir Square"


async def test_nothing_nearby_returns_none():
    google = FakeGoogle(nearby={"status": "ZERO_RESULTS", "results": []})
    assert await google.provider().fetch("route", QUERY) is None


async def test_http_failure_hides_the_key():
    google = FakeGoogle(fail=True)
    with pytest.raises(ProviderError) as err:
        await google.provider().fetch("route", QUERY)
    assert "test-key" not in str(err.value)


async def test_denied_status_is_a_provider_error():
    google = FakeGoogle(directions={"status": "REQUEST_DENIED"})
    with pytest.raises(ProviderError):
        await google.provider().fetch("route", {**QUERY, "destination": "30.1,31.2"})


# ------------------------------------------------------------------ endpoint + cache


async def test_route_endpoint_serves_google_route():
    async with client_for(FakeGoogle().provider()) as c:
        r = await c.get("/route", params=QUERY)
    env = Envelope.model_validate(r.json())
    assert env.ok and not env.fallback
    RouteResponse.model_validate(env.data)
    assert env.data["destination_name"] == "El Ezaby Pharmacy"


async def test_second_request_is_served_from_cache():
    google = FakeGoogle()
    async with client_for(google.provider()) as c:
        await c.get("/route", params=QUERY)
        moved = {**QUERY, "origin": "30.04442,31.23568"}  # same ~100 m area
        r = await c.get("/route", params=moved)
    assert Envelope.model_validate(r.json()).ok
    assert len(google.calls) == 2  # nearby + directions, once


async def test_failure_serves_last_good_route():
    good = FakeGoogle()
    async with client_for(good.provider()) as c:
        await c.get("/route", params=QUERY)
    for k, (stored, data) in list(route_cache._items.items()):
        route_cache._items[k] = (stored - 3600, data)  # an hour old: stale, not expired
    async with client_for(FakeGoogle(fail=True).provider()) as c:
        r = await c.get("/route", params=QUERY)
    env = Envelope.model_validate(r.json())
    assert env.ok and not env.fallback
    assert env.data["destination_name"] == "El Ezaby Pharmacy"


async def test_failure_without_cache_speaks_fallback():
    async with client_for(FakeGoogle(fail=True).provider()) as c:
        r = await c.get("/route", params=QUERY)
    env = Envelope.model_validate(r.json())
    assert env.fallback and env.data is None
    assert env.message == FALLBACKS["route"]


async def test_mock_mode_never_caches():
    app = create_app()
    settings = Settings(mock_mode=True, provider_timeout_s=0.05)
    app.dependency_overrides[get_settings] = lambda: settings
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t") as c:
        ok = await c.get("/route", params=QUERY, headers={"X-Mock-Scenario": "ok"})
        bad = await c.get("/route", params=QUERY, headers={"X-Mock-Scenario": "error"})
    assert Envelope.model_validate(ok.json()).ok
    assert Envelope.model_validate(bad.json()).fallback


def test_real_provider_chosen_only_with_key_and_mock_off():
    sentinel = object()
    assert get_route_provider(Settings(mock_mode=True, maps_api_key="k"), sentinel) is sentinel
    assert get_route_provider(Settings(mock_mode=False, maps_api_key=""), sentinel) is sentinel
    assert isinstance(get_route_provider(Settings(mock_mode=False, maps_api_key="k"), sentinel),
                      GoogleMapsRouteProvider)


# ------------------------------------------------------------------ cache unit tests


def test_cache_key_rounds_origin_and_normalises_destination():
    assert route_key("30.04441,31.23571", " Nearest  Pharmacy ") == "30.044,31.236|nearest pharmacy"


def test_cache_fresh_stale_and_expiry():
    now = [0.0]
    cache = RouteCache(fresh_s=10, stale_s=100, clock=lambda: now[0])
    cache.put("k", {"a": 1})
    assert cache.fresh("k") == {"a": 1}
    now[0] = 50
    assert cache.fresh("k") is None and cache.stale("k") == {"a": 1}
    now[0] = 200
    assert cache.stale("k") is None and "k" not in cache._items


def test_cache_is_bounded():
    cache = RouteCache(max_items=2)
    for k in "abc":
        cache.put(k, {})
    assert list(cache._items) == ["b", "c"]
