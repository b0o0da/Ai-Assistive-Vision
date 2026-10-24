"""Request and response bodies of the 9 endpoints: mirrors contracts/schemas/api.schema.json.

Frozen at contract v1.0.0. Do not rename or add a field without a Technical Decision
(docs/api-contract.md section 7). Responses are the `data` of an Envelope.
"""

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field

from app.schemas.common import TimestampMs

Language = Literal["en", "ar"]
ScreenType = Literal["chat_list", "chat_thread", "feed_post", "other", "blocked"]
PlanStep = Literal[
    "launch_app", "wait", "read_screen", "summarize", "scroll", "back",
    "tap_label", "navigate", "sos", "describe", "ask",
]

# Base64 JPEG, longest side 512-768 px. Personal data: never logged or stored.
ImageJpegBase64 = Annotated[str, Field(max_length=400_000)]
Embedding = Annotated[list[float], Field(min_length=64, max_length=1024)]


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Context(_Strict):
    mode: Literal["idle", "walking", "navigating", "reading", "screen"] | None = None
    navigation_active: bool | None = None
    language: Language | None = None
    recent_events: str | None = Field(default=None, max_length=300)


class LatLon(_Strict):
    lat: float = Field(ge=-90, le=90)
    lon: float = Field(ge=-180, le=180)


# ---- GET /health ----

class HealthResponse(_Strict):
    status: Literal["ok", "degraded"]
    version: str
    mock_mode: bool


# ---- POST /describe ----

class DescribeRequest(_Strict):
    image: ImageJpegBase64
    context: Context | None = None
    language: Language


class DescribeResponse(_Strict):
    description: str = Field(max_length=400)


# ---- POST /ask ----

class AskRequest(_Strict):
    image: ImageJpegBase64
    question: str = Field(min_length=1, max_length=300)
    context: Context | None = None


class AskResponse(_Strict):
    answer: str = Field(max_length=600)


# ---- POST /screen/summarize ----

class ScreenSummarizeRequest(_Strict):
    package: str = Field(max_length=200)
    screen_type: ScreenType
    text: str = Field(max_length=8000)
    image: ImageJpegBase64 | None = None
    question: str = Field(max_length=300)


class ScreenSummarizeResponse(_Strict):
    summary: str = Field(max_length=1200)


# ---- POST /intent ----

class IntentRequest(_Strict):
    text: str = Field(min_length=1, max_length=300)
    context: Context | None = None


class PlanStepItem(_Strict):
    step: PlanStep
    arg: str | None = Field(default=None, max_length=200)


class ActionPlan(_Strict):
    """IntentResponse = android.schema.json#/$defs/ActionPlan."""

    steps: list[PlanStepItem] = Field(max_length=10)
    requires_confirmation: bool
    spoken_ack: str | None = Field(default=None, max_length=200)
    clarification: str | None = Field(default=None, max_length=200)


# ---- GET /route (query parameters) ----

class RouteRequestQuery(_Strict):
    origin: str = Field(pattern=r"^-?[0-9.]+,-?[0-9.]+$")
    destination: str = Field(min_length=1, max_length=200)
    mode: Literal["walking"]


class RouteStep(_Strict):
    instruction: str = Field(max_length=300)
    maneuver: Literal[
        "start", "straight", "turn_left", "turn_right", "slight_left",
        "slight_right", "u_turn", "arrive", "other",
    ]
    distance_m: float = Field(ge=0)
    end: LatLon


class RouteResponse(_Strict):
    steps: list[RouteStep] = Field(min_length=1)
    polyline: str
    distance_m: float = Field(ge=0)
    duration_s: float = Field(ge=0)
    destination_name: str | None = None


# ---- POST /faces/enroll, /faces/match ----

class FacesEnrollRequest(_Strict):
    display_name: str = Field(min_length=1, max_length=80)
    embedding: Embedding
    model_version: str
    # The contract requires true. False is accepted here only so the router can refuse it with
    # the spoken consent message instead of a generic validation error.
    consent: bool


class FacesEnrollResponse(_Strict):
    person_id: str


class FacesMatchRequest(_Strict):
    embedding: Embedding
    model_version: str


class FacesMatchResponse(_Strict):
    match: str | None
    similarity: float = Field(ge=-1, le=1)


# ---- POST /sos ----

class SosRequest(_Strict):
    lat: float
    lon: float
    accuracy_m: float = Field(ge=0)
    fix_age_s: float = Field(ge=0)
    ts: TimestampMs
    contact_ids: list[str]


class SosResponse(_Strict):
    accepted: bool
