"""WS02-01: the Python Event model matches contracts/schemas/event.schema.json and its fixtures."""

import json
from pathlib import Path
from typing import get_args

import pytest
from pydantic import ValidationError

from app.schemas import common
from app.schemas.event import Event

CONTRACTS = Path(__file__).resolve().parents[2] / "contracts"
EVENT_FIXTURES = sorted((CONTRACTS / "fixtures" / "events").glob("*.json"))
COMMON = json.loads((CONTRACTS / "schemas" / "common.schema.json").read_text())["$defs"]
SCHEMA = json.loads((CONTRACTS / "schemas" / "event.schema.json").read_text())


def valid_event(**overrides):
    base = json.loads(EVENT_FIXTURES[0].read_text())
    base.update(overrides)
    return base


def test_there_are_at_least_six_event_fixtures():
    assert len(EVENT_FIXTURES) >= 6


@pytest.mark.parametrize("path", EVENT_FIXTURES, ids=lambda p: p.name)
def test_event_fixture_round_trips(path):
    raw = json.loads(path.read_text())
    event = Event.model_validate(raw)
    assert event.model_dump(mode="json") == raw


def test_fields_match_the_schema_exactly():
    assert set(Event.model_fields) == set(SCHEMA["properties"]) == set(SCHEMA["required"])


@pytest.mark.parametrize(
    ("name", "literal"),
    [
        ("Source", common.Source),
        ("EventType", common.EventType),
        ("Side", common.Side),
        ("DistanceBand", common.DistanceBand),
    ],
)
def test_enums_match_common_schema(name, literal):
    assert list(get_args(literal)) == COMMON[name]["enum"]


def test_side_and_distance_band_are_limited():
    assert set(get_args(common.Side)) == {"left", "center", "right"}
    assert set(get_args(common.DistanceBand)) == {"near", "medium", "far"}


@pytest.mark.parametrize("field", ["distance_m", "meters", "speed", "ttc"])
def test_no_metre_or_speed_field_is_accepted(field):
    with pytest.raises(ValidationError):
        Event.model_validate(valid_event(**{field: 3}))


@pytest.mark.parametrize(
    "overrides",
    [
        {"side": "behind"},
        {"distance_band": "2m"},
        {"distance_band": 2},
        {"confidence": 1.5},
        {"risk": 101},
        {"id": "event_1"},
        {"timestamp": -1},
        {"type": "explosion"},
    ],
)
def test_invalid_values_are_rejected(overrides):
    with pytest.raises(ValidationError):
        Event.model_validate(valid_event(**overrides))


def test_side_and_distance_band_may_be_null():
    event = Event.model_validate(valid_event(side=None, distance_band=None))
    assert event.side is None and event.distance_band is None
