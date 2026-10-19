"""Event v0: mirrors contracts/schemas/event.schema.json (WS-02).

Every producer submits an Event to the priority engine on the phone. The backend only uses this
type to read and write the same shape (alert log, tests); it never raises a safety event itself.
"""

from pydantic import BaseModel, ConfigDict, Field

from app.schemas.common import Confidence, DistanceBand, EventType, Side, Source, TimestampMs


class Event(BaseModel):
    # Unknown fields are rejected, so a stray "distance_m" can never slip through.
    model_config = ConfigDict(extra="forbid", strict=True)

    id: str = Field(pattern=r"^evt_[A-Za-z0-9_-]{1,64}$")
    source: Source
    type: EventType
    label: str = Field(max_length=64)
    side: Side | None
    distance_band: DistanceBand | None
    confidence: Confidence
    # Producers send 0; the engine computes the real value.
    risk: int = Field(ge=0, le=100)
    timestamp: TimestampMs
