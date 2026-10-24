"""GET /route: walking directions through the Maps proxy (real provider in WS22-12)."""

from typing import Annotated

from fastapi import APIRouter, Query, Request

from app.core.deps import ProviderDep, SettingsDep
from app.core.envelope import Envelope
from app.core.middleware import request_id_of
from app.schemas.api import RouteRequestQuery, RouteResponse
from app.services import gateway

router = APIRouter(tags=["route"])


@router.get("/route", response_model=Envelope)
async def route(
    query: Annotated[RouteRequestQuery, Query()],
    request: Request,
    provider: ProviderDep,
    settings: SettingsDep,
) -> Envelope:
    return await gateway.call(
        provider, "route", query.model_dump(), RouteResponse,
        request_id_of(request), settings.provider_timeout_s,
    )
