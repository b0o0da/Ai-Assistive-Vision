"""Maps provider for GET /route (WS22-12): walking directions behind the provider seam.

The phone never sees the Maps key; it calls GET /route and gets the contract's RouteResponse.
Destinations (TD-006, decided with WS20-03): `lat,lon`, free text ("Tahrir Square"), or
"nearest <place>" ("nearest pharmacy"), which is resolved with a Places nearby search ranked by
distance from the origin. Coordinates, destinations and answers are never logged.
"""

import html
import re
from typing import Any

import httpx

from app.providers.base import ProviderError

DIRECTIONS_URL = "https://maps.googleapis.com/maps/api/directions/json"
NEARBY_URL = "https://maps.googleapis.com/maps/api/place/nearbysearch/json"

_LATLON = re.compile(r"^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*$")
_NEAREST = re.compile(r"^\s*(?:the\s+)?(?:nearest|closest)\s+(.+?)\s*$", re.I)
_TAGS = re.compile(r"<[^>]+>")
_SPACES = re.compile(r"\s+")

# Google maneuver -> contract RouteStep.maneuver
MANEUVERS = {
    "straight": "straight",
    "turn-left": "turn_left",
    "turn-right": "turn_right",
    "turn-slight-left": "slight_left",
    "turn-slight-right": "slight_right",
    "turn-sharp-left": "turn_left",
    "turn-sharp-right": "turn_right",
    "uturn-left": "u_turn",
    "uturn-right": "u_turn",
    "keep-left": "slight_left",
    "keep-right": "slight_right",
}


def parse_latlon(text: str) -> tuple[float, float] | None:
    m = _LATLON.match(text)
    if not m:
        return None
    lat, lon = float(m.group(1)), float(m.group(2))
    if -90 <= lat <= 90 and -180 <= lon <= 180:
        return lat, lon
    return None


def nearest_keyword(destination: str) -> str | None:
    m = _NEAREST.match(destination)
    return m.group(1) if m else None


def plain_instruction(raw: str) -> str:
    """Google sends HTML instructions ("Turn <b>left</b> onto ..."); speak them as plain text."""
    text = html.unescape(_TAGS.sub(" ", raw or ""))
    return _SPACES.sub(" ", text).strip()[:300] or "Continue"


def to_route_response(directions: dict[str, Any], destination_name: str | None) -> dict[str, Any]:
    """Google Directions JSON -> contract RouteResponse `data` (first route, first leg)."""
    routes = directions.get("routes") or []
    if not routes or not routes[0].get("legs"):
        raise ProviderError("no route")
    route, leg = routes[0], routes[0]["legs"][0]
    steps = []
    for i, step in enumerate(leg.get("steps") or []):
        maneuver = "start" if i == 0 else MANEUVERS.get(step.get("maneuver") or "", "straight")
        if i > 0 and step.get("maneuver") and step["maneuver"] not in MANEUVERS:
            maneuver = "other"
        end = step["end_location"]
        steps.append({
            "instruction": plain_instruction(step.get("html_instructions", "")),
            "maneuver": maneuver,
            "distance_m": float(step.get("distance", {}).get("value", 0)),
            "end": {"lat": end["lat"], "lon": end["lng"]},
        })
    end = leg["end_location"]
    name = destination_name or leg.get("end_address") or None
    steps.append({
        "instruction": f"You have arrived at {name}"[:300] if name else "You have arrived",
        "maneuver": "arrive",
        "distance_m": 0.0,
        "end": {"lat": end["lat"], "lon": end["lng"]},
    })
    return {
        "steps": steps,
        "polyline": route.get("overview_polyline", {}).get("points", ""),
        "distance_m": float(leg.get("distance", {}).get("value", 0)),
        "duration_s": float(leg.get("duration", {}).get("value", 0)),
        "destination_name": name,
    }


class GoogleMapsRouteProvider:
    """Provider for the "route" endpoint using the Google Directions and Places APIs."""

    def __init__(self, api_key: str, client: httpx.AsyncClient | None = None,
                 language: str = "en") -> None:
        if not api_key:
            raise ValueError("MAPS_API_KEY is empty")
        self.api_key = api_key
        self.client = client
        self.language = language

    async def fetch(self, endpoint: str, payload: dict[str, Any]) -> dict[str, Any] | None:
        if endpoint != "route":
            raise ProviderError(f"maps provider cannot serve {endpoint}")
        origin = payload["origin"]
        destination = payload["destination"]
        name = None
        target = destination
        keyword = nearest_keyword(destination)
        if keyword and parse_latlon(destination) is None:
            place = await self._nearest(origin, keyword)
            if place is None:
                return None  # nothing nearby: gateway speaks the fallback
            loc = place["geometry"]["location"]
            target, name = f"{loc['lat']},{loc['lng']}", place.get("name")
        data = await self._get(DIRECTIONS_URL, {
            "origin": origin, "destination": target, "mode": "walking",
            "language": self.language,
        })
        status = data.get("status")
        if status == "ZERO_RESULTS" or status == "NOT_FOUND":
            return None
        if status != "OK":
            raise ProviderError(f"directions status {status}")
        return to_route_response(data, name)

    async def _nearest(self, origin: str, keyword: str) -> dict[str, Any] | None:
        data = await self._get(NEARBY_URL, {
            "location": origin, "rankby": "distance", "keyword": keyword,
            "language": self.language,
        })
        status = data.get("status")
        if status == "ZERO_RESULTS":
            return None
        if status != "OK":
            raise ProviderError(f"places status {status}")
        results = data.get("results") or []
        return results[0] if results else None

    async def _get(self, url: str, params: dict[str, str]) -> dict[str, Any]:
        params = {**params, "key": self.api_key}
        try:
            if self.client is not None:
                r = await self.client.get(url, params=params)
            else:
                async with httpx.AsyncClient() as client:
                    r = await client.get(url, params=params)
            r.raise_for_status()
            return r.json()
        except (httpx.HTTPError, ValueError) as exc:
            # The message names the failure type only: never the URL (it carries the key).
            raise ProviderError(f"maps request failed: {type(exc).__name__}") from None
