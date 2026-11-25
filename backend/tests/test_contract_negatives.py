"""WS02-05: the shared must-reject payloads are refused by the Pydantic models too.

The same files are checked against the JSON Schemas (scripts/validate_contracts.py) and the
Kotlin models (android/contracts FixtureTest), so all three layers reject the same drift.
"""

import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from app.core.envelope import Envelope
from app.schemas.event import Event

INVALID = Path(__file__).resolve().parents[2] / "tests" / "fixtures" / "contract_invalid"
ENTRIES = json.loads((INVALID / "index.json").read_text(encoding="utf-8"))["fixtures"]
MODELS = {"event.schema.json": Event, "envelope.schema.json": Envelope}


def test_there_are_at_least_six_negative_cases():
    assert len(ENTRIES) >= 6


@pytest.mark.parametrize("entry", ENTRIES, ids=lambda e: e["fixture"])
def test_payload_is_rejected(entry):
    doc = json.loads((INVALID / entry["fixture"]).read_text(encoding="utf-8"))
    with pytest.raises(ValidationError):
        MODELS[entry["schema"]].model_validate(doc)
