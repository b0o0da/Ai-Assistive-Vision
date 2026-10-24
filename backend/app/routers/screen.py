"""POST /screen/summarize (WS-18 screen ladder, last rung)."""

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from app.core.deps import ProviderDep, SettingsDep, refuse
from app.core.envelope import Envelope
from app.core.middleware import request_id_of
from app.schemas.api import ScreenSummarizeRequest, ScreenSummarizeResponse
from app.services import gateway

router = APIRouter(tags=["screen"])

BLOCKED_MESSAGE = "I can't read this screen."


@router.post("/screen/summarize", response_model=Envelope)
async def screen_summarize(
    body: ScreenSummarizeRequest, request: Request, provider: ProviderDep, settings: SettingsDep
) -> Envelope | JSONResponse:
    # Banking, password and secure screens are refused before any provider sees them.
    # The package deny-list check arrives with WS28-08; the phone already marks them blocked.
    if body.screen_type == "blocked":
        return refuse(request, BLOCKED_MESSAGE)
    return await gateway.call(
        provider, "screen_summarize", body.model_dump(), ScreenSummarizeResponse,
        request_id_of(request), settings.provider_timeout_s,
    )
