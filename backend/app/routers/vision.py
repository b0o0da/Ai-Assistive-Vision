"""POST /describe and POST /ask: scene description and look-and-ask (WS-15 provides the VLM)."""

from fastapi import APIRouter, Request

from app.core.deps import ProviderDep, SettingsDep
from app.core.envelope import Envelope
from app.core.middleware import request_id_of
from app.schemas.api import AskRequest, AskResponse, DescribeRequest, DescribeResponse
from app.services import gateway

router = APIRouter(tags=["vision"])


@router.post("/describe", response_model=Envelope)
async def describe(
    body: DescribeRequest, request: Request, provider: ProviderDep, settings: SettingsDep
) -> Envelope:
    return await gateway.call(
        provider, "describe", body.model_dump(), DescribeResponse,
        request_id_of(request), settings.provider_timeout_s,
    )


@router.post("/ask", response_model=Envelope)
async def ask(
    body: AskRequest, request: Request, provider: ProviderDep, settings: SettingsDep
) -> Envelope:
    return await gateway.call(
        provider, "ask", body.model_dump(), AskResponse,
        request_id_of(request), settings.provider_timeout_s,
    )
