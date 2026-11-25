"""GET /route: walking directions through the Maps proxy (WS22-12, WS-20).

Order: fresh cache hit -> provider (Maps, or fixtures in MOCK_MODE) -> on fallback, the last
good route for the same area and destination -> else the spoken fallback from the gateway.
"""

from typing import Annotated

from fastapi import APIRouter, Depends, Query, Request

from app.core.deps import SettingsDep, get_provider
from app.core.envelope import Envelope, success
from app.core.middleware import request_id_of
from app.providers.base import Provider
from app.providers.maps import GoogleMapsRouteProvider
from app.schemas.api import RouteRequestQuery, RouteResponse
from app.services import gateway
from app.services.route_cache import route_cache, route_key

router = APIRouter(tags=["route"])


def get_route_provider(
    settings: SettingsDep, provider: Annotated[Provider, Depends(get_provider)]
) -> Provider:
    """MOCK_MODE or no MAPS_API_KEY: the shared provider (fixtures / fail-soft). Else Google."""
    if settings.mock_mode or not settings.maps_api_key:
        return provider
    return GoogleMapsRouteProvider(settings.maps_api_key)


@router.get("/route", response_model=Envelope)
async def route(
    query: Annotated[RouteRequestQuery, Query()],
    request: Request,
    provider: Annotated[Provider, Depends(get_route_provider)],
    settings: SettingsDep,
) -> Envelope:
    rid = request_id_of(request)
    # No cache in MOCK_MODE: X-Mock-Scenario must always show the failure being rehearsed.
    use_cache = not settings.mock_mode
    key = route_key(query.origin, query.destination)
    cached = route_cache.fresh(key) if use_cache else None
    if cached is not None:
        return success(cached, rid)
    env = await gateway.call(
        provider, "route", query.model_dump(), RouteResponse, rid, settings.budget_for("route"),
    )
    if not use_cache:
        return env
    if env.ok and not env.fallback and env.data is not None:
        route_cache.put(key, env.data)
        return env
    stale = route_cache.stale(key)
    return success(stale, rid) if stale is not None else env
