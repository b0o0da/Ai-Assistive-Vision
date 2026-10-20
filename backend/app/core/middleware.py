import json
import logging
import re
import time
import uuid

from fastapi import Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.core.envelope import (
    FALLBACK_MESSAGES,
    GENERIC_ERROR,
    INVALID_REQUEST,
    ErrorCode,
    rejected,
)

logger = logging.getLogger("avn")
_VALID_ID = re.compile(r"^[A-Za-z0-9_.:-]{1,64}$")


def request_id_of(request: Request) -> str:
    return getattr(request.state, "request_id", "req_unknown")


async def request_context(request: Request, call_next):
    """Assign a request id (echo a safe X-Request-Id) and log one JSON line per request.

    Only metadata is logged: never bodies, images, text, embeddings or coordinates.
    """
    incoming = request.headers.get("x-request-id", "")
    request.state.request_id = (
        incoming if _VALID_ID.match(incoming) else f"req_{uuid.uuid4().hex[:16]}"
    )
    start = time.perf_counter()
    response = await call_next(request)
    response.headers["X-Request-Id"] = request.state.request_id
    logger.info(
        json.dumps(
            {
                "request_id": request.state.request_id,
                "endpoint": request.url.path,
                "method": request.method,
                "status": response.status_code,
                "latency_ms": round((time.perf_counter() - start) * 1000, 1),
            }
        )
    )
    return response


def _code_for(status: int) -> ErrorCode:
    if status in (401, 403):
        return ErrorCode.unauthorized
    if status == 429:
        return ErrorCode.rate_limited
    if status in (408, 504):
        return ErrorCode.timeout
    if status >= 500:
        return ErrorCode.provider_error
    return ErrorCode.bad_request


def _reply(request: Request, status: int, message: str) -> JSONResponse:
    return JSONResponse(
        status_code=status,
        content=rejected(message, request_id_of(request), _code_for(status)).model_dump(),
    )


async def validation_error_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
    return _reply(request, 422, INVALID_REQUEST)


async def http_error_handler(request: Request, exc: StarletteHTTPException) -> JSONResponse:
    code = _code_for(exc.status_code)
    return _reply(request, exc.status_code, FALLBACK_MESSAGES[code])


async def unhandled_error_handler(request: Request, exc: Exception) -> JSONResponse:
    logger.exception("unhandled error request_id=%s", request_id_of(request))
    return _reply(request, 500, GENERIC_ERROR)