"""Provider seam: the only place the backend talks to the outside world (VLM, LLM, Maps, DB).

Real providers arrive in phases B2 to B4 (docs/claude-code/backend-prompts.md). Until then
MOCK_MODE=true serves the contract fixtures and anything else answers with a spoken fallback.
"""

from typing import Any, Protocol


class ProviderError(Exception):
    """The provider failed. The service turns this into a fallback envelope."""


class Provider(Protocol):
    async def fetch(self, endpoint: str, payload: dict[str, Any]) -> dict[str, Any] | None:
        """Return the endpoint's response `data`, or None when the provider gave nothing."""
        ...


class UnconfiguredProvider:
    """Used when MOCK_MODE is off and no real provider exists yet: every call fails soft."""

    async def fetch(self, endpoint: str, payload: dict[str, Any]) -> dict[str, Any] | None:
        raise ProviderError(f"no real provider configured for {endpoint}")
