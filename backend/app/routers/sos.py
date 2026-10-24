"""POST /sos: optional server copy of an alert. The real SOS is the phone's own SMS (WS-21)."""

from fastapi import APIRouter, Request

from app.core.deps import ProviderDep, SettingsDep
from app.core.envelope import Envelope
from app.core.middleware import request_id_of
from app.schemas.api import SosRequest, SosResponse
from app.services import gateway

router = APIRouter(tags=["sos"])


@router.post("/sos", response_model=Envelope)
async def sos(
    body: SosRequest, request: Request, provider: ProviderDep, settings: SettingsDep
) -> Envelope:
    return await gateway.call(
        provider, "sos", body.model_dump(), SosResponse,
        request_id_of(request), settings.provider_timeout_s,
    )
