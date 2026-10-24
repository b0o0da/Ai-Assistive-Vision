"""Calls a provider with a timeout and turns every outcome into an Envelope.

Timeout, provider error or empty output never reach the phone as an error: they become
ok:true, fallback:true with the endpoint's spoken sentence (docs/api-contract.md section 5).
"""

import asyncio
import logging
from typing import Any

from pydantic import BaseModel, ValidationError

from app.core.envelope import Envelope, ErrorCode, fallback, success
from app.providers.base import Provider, ProviderError

logger = logging.getLogger("avn")

# Spoken fallback per endpoint: docs/api-contract.md "Fallback messages".
FALLBACKS = {
    "describe": "I can't describe the scene right now.",
    "ask": "I can't describe the scene right now.",
    "screen_summarize": "I can't summarize this screen right now.",
    "intent": "I didn't understand that. Please say it another way.",
    "route": "I can't get directions right now.",
    "faces_enroll": "I can't save this face right now.",
    # Never spoken: the router turns a failed match into "unknown" (match: null).
    "faces_match": "I don't recognise this person.",
    "sos": "I couldn't reach the server. Your SMS alert was still sent.",
}


async def call(
    provider: Provider,
    endpoint: str,
    payload: dict[str, Any],
    response_model: type[BaseModel],
    request_id: str,
    timeout_s: float,
) -> Envelope:
    message = FALLBACKS[endpoint]
    try:
        data = await asyncio.wait_for(provider.fetch(endpoint, payload), timeout_s)
    except TimeoutError:
        _log(request_id, endpoint, "timeout")
        return fallback(message, request_id, ErrorCode.timeout)
    except ProviderError:
        _log(request_id, endpoint, "provider_error")
        return fallback(message, request_id, ErrorCode.provider_error)
    if not data:
        _log(request_id, endpoint, "empty")
        return fallback(message, request_id)
    try:
        # Never pass on a provider answer that breaks the frozen contract.
        return success(response_model.model_validate(data), request_id)
    except ValidationError:
        _log(request_id, endpoint, "invalid_output")
        return fallback(message, request_id, ErrorCode.provider_error)


def _log(request_id: str, endpoint: str, outcome: str) -> None:
    # Metadata only: never the payload, answer, image, text or coordinates.
    logger.info('{"request_id": "%s", "endpoint": "%s", "fallback": "%s"}',
                request_id, endpoint, outcome)
