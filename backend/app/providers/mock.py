"""MockProvider: answers from contracts/fixtures/api/<endpoint>.ok.json (MOCK_MODE=true).

X-Mock-Scenario picks the behaviour so the app can rehearse every failure path offline:
ok -> the fixture's data, timeout -> never answers in time, error -> provider failure,
empty -> provider returned nothing.
"""

import asyncio
import json
from enum import StrEnum
from functools import lru_cache
from pathlib import Path
from typing import Any

from app.providers.base import ProviderError


class Scenario(StrEnum):
    ok = "ok"
    timeout = "timeout"
    error = "error"
    empty = "empty"


@lru_cache(maxsize=32)
def load_fixture_data(fixtures_dir: Path, endpoint: str) -> dict[str, Any]:
    raw = json.loads((fixtures_dir / f"{endpoint}.ok.json").read_text(encoding="utf-8"))
    return raw["data"]


class MockProvider:
    def __init__(self, fixtures_dir: Path, scenario: Scenario, timeout_s: float) -> None:
        self.fixtures_dir = fixtures_dir
        self.scenario = scenario
        self.timeout_s = timeout_s

    async def fetch(self, endpoint: str, payload: dict[str, Any]) -> dict[str, Any] | None:
        if self.scenario is Scenario.timeout:
            # Outlast the service's timeout so the real timeout path runs.
            await asyncio.sleep(self.timeout_s + 1)
        if self.scenario is Scenario.error:
            raise ProviderError(f"mock error for {endpoint}")
        if self.scenario is Scenario.empty:
            return None
        return dict(load_fixture_data(self.fixtures_dir, endpoint))
