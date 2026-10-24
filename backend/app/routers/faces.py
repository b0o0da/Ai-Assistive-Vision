"""POST /faces/enroll and /faces/match: embeddings only, never images (Should tier, WS-08/23)."""

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from app.core.deps import ProviderDep, SettingsDep, refuse
from app.core.envelope import Envelope, success
from app.core.middleware import request_id_of
from app.schemas.api import (
    FacesEnrollRequest,
    FacesEnrollResponse,
    FacesMatchRequest,
    FacesMatchResponse,
)
from app.services import gateway

router = APIRouter(prefix="/faces", tags=["faces"])

NO_CONSENT_MESSAGE = "I can't save this face without permission."


@router.post("/enroll", response_model=Envelope)
async def enroll(
    body: FacesEnrollRequest, request: Request, provider: ProviderDep, settings: SettingsDep
) -> Envelope | JSONResponse:
    if body.consent is not True:
        return refuse(request, NO_CONSENT_MESSAGE)
    return await gateway.call(
        provider, "faces_enroll", body.model_dump(), FacesEnrollResponse,
        request_id_of(request), settings.provider_timeout_s,
    )


@router.post("/match", response_model=Envelope)
async def match(
    body: FacesMatchRequest, request: Request, provider: ProviderDep, settings: SettingsDep
) -> Envelope:
    rid = request_id_of(request)
    env = await gateway.call(
        provider, "faces_match", body.model_dump(), FacesMatchResponse,
        rid, settings.provider_timeout_s,
    )
    if env.fallback:
        # Contract: a failed match is "someone you don't know", never a guess at a name.
        return success(FacesMatchResponse(match=None, similarity=0.0), rid)
    return env
