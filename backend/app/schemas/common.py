"""Shared enums and scalar types: mirrors contracts/schemas/common.schema.json.

Values are frozen at Gate 1. Do not rename or add one without a Technical Decision.
"""

from typing import Annotated, Literal

from pydantic import Field

Source = Literal["camera", "voice", "screen", "navigation", "notification", "system"]

EventType = Literal[
    "vehicle_approaching",
    "obstacle_ahead",
    "person_close",
    "person_known",
    "person_unknown",
    "scene_answer",
    "text_result",
    "screen_summary",
    "notification",
    "nav_instruction",
    "system_notice",
    "sos_countdown",
]

Side = Literal["left", "center", "right"]

# Hazard distance. Never meters, speed or time-to-collision.
DistanceBand = Literal["near", "medium", "far"]

Confidence = Annotated[float, Field(ge=0, le=1)]

# Unix epoch milliseconds.
TimestampMs = Annotated[int, Field(ge=0)]
