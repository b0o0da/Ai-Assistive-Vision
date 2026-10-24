"""POST /intent: free-form command to an ActionPlan (WS-14 calls it when no local rule matches)."""

from fastapi import APIRouter, Request

from app.core.deps import ProviderDep, SettingsDep
from app.core.envelope import Envelope
from app.core.middleware import request_id_of
from app.schemas.api import ActionPlan, IntentRequest
from app.services import gateway

router = APIRouter(tags=["intent"])


@router.post("/intent", response_model=Envelope)
async def intent(
    body: IntentRequest, request: Request, provider: ProviderDep, settings: SettingsDep
) -> Envelope:
    # The ActionPlan model rejects any step outside the 11-step whitelist, so an invalid plan
    # becomes a spoken fallback. Dropping single bad steps arrives with the real LLM (WS22-13).
    return await gateway.call(
        provider, "intent", body.model_dump(), ActionPlan,
        request_id_of(request), settings.provider_timeout_s,
    )
