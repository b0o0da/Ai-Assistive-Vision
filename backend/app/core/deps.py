"""Shared FastAPI dependencies: the provider for this request and the reply helpers."""

from typing import Annotated

from fastapi import Depends, Header, HTTPException, Request
from fastapi.responses import JSONResponse

from app.core.config import Settings, get_settings
from app.core.envelope import Envelope, ErrorCode, rejected
from app.core.middleware import request_id_of
from app.providers.base import Provider, UnconfiguredProvider
from app.providers.mock import MockProvider, Scenario

SettingsDep = Annotated[Settings, Depends(get_settings)]

# Scenarios that are refused here, before any provider runs: the HTTP error handler then turns
# them into the matching ok:false envelope (error.code + the spoken FALLBACK_MESSAGES text).
_REFUSED_STATUS: dict[Scenario, int] = {
    Scenario.rate_limited: 429,
    Scenario.unauthorized: 401,
    Scenario.bad_request: 400,
}


def mock_scenario(
    settings: SettingsDep,
    x_mock_scenario: Annotated[str | None, Header()] = None,
) -> Scenario:
    """X-Mock-Scenario: ok | timeout | error | provider_error | empty | rate_limited |
    unauthorized | bad_request. Only honoured when MOCK_MODE=true."""
    if not settings.mock_mode or x_mock_scenario is None:
        return Scenario.ok
    try:
        scenario = Scenario(x_mock_scenario.strip().lower())
    except ValueError:
        raise HTTPException(status_code=400) from None
    status = _REFUSED_STATUS.get(scenario)
    if status is not None:
        raise HTTPException(status_code=status)
    return scenario


def get_provider(
    settings: SettingsDep, scenario: Annotated[Scenario, Depends(mock_scenario)]
) -> Provider:
    if settings.mock_mode:
        return MockProvider(settings.mock_fixtures_dir, scenario, settings.provider_timeout_s)
    return UnconfiguredProvider()


ProviderDep = Annotated[Provider, Depends(get_provider)]


def refuse(request: Request, message: str, status: int = 400) -> JSONResponse:
    """ok:false with a spoken-safe message, for requests the backend will not serve."""
    env: Envelope = rejected(message, request_id_of(request), ErrorCode.bad_request)
    return JSONResponse(status_code=status, content=env.model_dump())
