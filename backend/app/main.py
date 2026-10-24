"""AVN backend app factory.

The 9 contract endpoints with envelope, request ids and fail-soft error handlers.
MOCK_MODE=true answers from contracts/fixtures/api (X-Mock-Scenario picks ok, timeout, error
or empty). Auth (WS22-04), real providers (B2 to B4) and deploy (B5) come later:
docs/claude-code/backend-prompts.md.
"""

import logging

from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError
from starlette.exceptions import HTTPException as StarletteHTTPException

from app import __version__
from app.core.config import get_settings
from app.core.middleware import (
    http_error_handler,
    request_context,
    unhandled_error_handler,
    validation_error_handler,
)
from app.routers import faces, health, intent, route, screen, sos, vision


def create_app() -> FastAPI:
    settings = get_settings()
    logging.basicConfig(level=settings.log_level.upper(), format="%(message)s")

    app = FastAPI(title="AVN backend", version=__version__)
    app.middleware("http")(request_context)
    app.add_exception_handler(RequestValidationError, validation_error_handler)
    app.add_exception_handler(StarletteHTTPException, http_error_handler)
    app.add_exception_handler(Exception, unhandled_error_handler)
    for module in (health, vision, screen, intent, route, faces, sos):
        app.include_router(module.router)
    return app


app = create_app()
