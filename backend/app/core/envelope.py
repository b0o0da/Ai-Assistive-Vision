"""Response envelope: docs/api-contract.md section 4, contracts/schemas/envelope.schema.json."""

from enum import Enum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, model_validator

ENVELOPE_VERSION = "1.0"

GENERIC_ERROR = "Something went wrong with that request. Please try again."
INVALID_REQUEST = "Sorry, I could not understand that request."


class ErrorCode(str, Enum):
    timeout = "timeout"
    rate_limited = "rate_limited"
    unauthorized = "unauthorized"
    bad_request = "bad_request"
    provider_error = "provider_error"


# Default speakable sentence per code. Endpoints may pass a more specific message.
FALLBACK_MESSAGES = {
    ErrorCode.timeout: "I can't do that right now. It's taking too long.",
    ErrorCode.rate_limited: "Too many requests. Please try again in a moment.",
    ErrorCode.unauthorized: "I can't connect to the service. The app needs to be set up again.",
    ErrorCode.bad_request: "Sorry, I could not understand that request.",
    ErrorCode.provider_error: "The service had a problem. Please try again shortly.",
}

RETRYABLE = {
    ErrorCode.timeout: True,
    ErrorCode.rate_limited: True,
    ErrorCode.unauthorized: False,
    ErrorCode.bad_request: False,
    ErrorCode.provider_error: True,
}


class ErrorInfo(BaseModel):
    model_config = ConfigDict(extra="forbid", use_enum_values=True)

    code: ErrorCode
    retryable: bool


class Envelope(BaseModel):
    model_config = ConfigDict(extra="forbid")

    version: str = ENVELOPE_VERSION
    ok: bool
    fallback: bool
    message: str = Field(max_length=300)
    data: dict[str, Any] | None
    request_id: str = Field(min_length=1, max_length=64)
    error: ErrorInfo | None = None

    @model_validator(mode="after")
    def check_rules(self) -> "Envelope":
        if self.version != ENVELOPE_VERSION:
            raise ValueError("unsupported envelope version")
        if (not self.ok or self.fallback) and not self.message:
            raise ValueError("message must be non-empty when ok is false or fallback is true")
        if not self.ok and self.error is None:
            raise ValueError("error is required when ok is false")
        if self.fallback and self.data is not None:
            raise ValueError("data must be null when fallback is true")
        return self


def _error(code: ErrorCode | None) -> ErrorInfo | None:
    return None if code is None else ErrorInfo(code=code, retryable=RETRYABLE[code])


def success(data: BaseModel | dict[str, Any], request_id: str) -> Envelope:
    payload = data.model_dump() if isinstance(data, BaseModel) else data
    return Envelope(ok=True, fallback=False, message="", data=payload, request_id=request_id)


def fallback(message: str, request_id: str, code: ErrorCode | None = None) -> Envelope:
    """Provider timed out, failed or returned nothing: the phone speaks `message`."""
    return Envelope(
        ok=True, fallback=True, message=message, data=None,
        request_id=request_id, error=_error(code),
    )


def rejected(
    message: str, request_id: str, code: ErrorCode = ErrorCode.bad_request
) -> Envelope:
    """Invalid, oversize, unauthorized or refused request."""
    return Envelope(
        ok=False, fallback=False, message=message, data=None,
        request_id=request_id, error=_error(code),
    )