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


TOO_LARGE = "That request is too large. Please try again with a smaller picture."


class BodySizeLimit:
    """Refuse request bodies over `max_bytes` with 413 and a spoken-safe envelope (WS22-07).

    Checked before routing or JSON parsing, so an oversize image never reaches Pydantic or a
    provider. Content-Length is checked first; a body sent without one (chunked) is read up to
    the limit and replayed to the app. The body itself is never logged.
    """

    def __init__(self, app, max_bytes: int) -> None:
        self.app = app
        self.max_bytes = max_bytes

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        headers = dict(scope.get("headers") or [])
        length = headers.get(b"content-length")
        if length is not None:
            if not length.isdigit() or int(length) > self.max_bytes:
                return await self._refuse(scope, headers, send)
            return await self.app(scope, receive, send)
        if scope.get("method") in ("GET", "HEAD", "OPTIONS"):
            return await self.app(scope, receive, send)

        chunks, size, more = [], 0, True
        while more:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            chunk = message.get("body", b"")
            size += len(chunk)
            if size > self.max_bytes:
                return await self._refuse(scope, headers, send)
            chunks.append(chunk)
            more = message.get("more_body", False)
        body, sent = b"".join(chunks), False

        async def replay():
            nonlocal sent
            if sent:
                return await receive()
            sent = True
            return {"type": "http.request", "body": body, "more_body": False}

        return await self.app(scope, replay, send)

    async def _refuse(self, scope, headers, send) -> None:
        incoming = headers.get(b"x-request-id", b"").decode("latin-1")
        rid = incoming if _VALID_ID.match(incoming) else f"req_{uuid.uuid4().hex[:16]}"
        payload = json.dumps(rejected(TOO_LARGE, rid, ErrorCode.bad_request).model_dump()).encode()
        logger.info(json.dumps({"request_id": rid, "endpoint": scope.get("path"), "status": 413}))
        await send({
            "type": "http.response.start",
            "status": 413,
            "headers": [
                (b"content-type", b"application/json"),
                (b"content-length", str(len(payload)).encode()),
                (b"x-request-id", rid.encode()),
            ],
        })
        await send({"type": "http.response.body", "body": payload})


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