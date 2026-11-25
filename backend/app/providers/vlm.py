"""VlmProvider: the low-level seam to a hosted vision-language model (WS-15).

`Provider` (base.py) answers a whole endpoint; this one turns a prompt (+ optional images)
into text. Services build on it; real adapters (WS15-07) implement `complete` only.
Never log prompts, images or answers.
"""

from abc import ABC, abstractmethod
from collections.abc import Sequence

from app.core.config import Settings
from app.providers.base import ProviderError

DEFAULT_MAX_TOKENS = 300
DEFAULT_TIMEOUT_S = 4.0


class VlmProvider(ABC):
    @abstractmethod
    async def complete(
        self,
        prompt: str,
        images: Sequence[str] = (),
        max_tokens: int = DEFAULT_MAX_TOKENS,
        timeout: float = DEFAULT_TIMEOUT_S,  # noqa: ASYNC109
        *,
        template: str | None = None,
    ) -> str:
        """Return the model's text. `images` are base64 JPEGs. `template` names the prompt
        (describe | ask | screen_summarize | intent) so mocks can answer per template."""

    async def describe(self, image: str, prompt: str = "", **kw) -> str:
        return await self.complete(prompt, (image,), template="describe", **kw)

    async def ask(self, image: str, question: str, **kw) -> str:
        return await self.complete(question, (image,), template="ask", **kw)

    async def summarize(self, text: str, image: str | None = None, **kw) -> str:
        images = (image,) if image else ()
        return await self.complete(text, images, template="screen_summarize", **kw)


class UnconfiguredVlmProvider(VlmProvider):
    """MOCK_MODE is off and no real adapter exists yet: fail soft via ProviderError."""

    async def complete(self, prompt, images=(), max_tokens=DEFAULT_MAX_TOKENS,
                       timeout=DEFAULT_TIMEOUT_S,  # noqa: ASYNC109
                       *, template=None) -> str:
        raise ProviderError("no real VLM provider configured")


def get_vlm_provider(settings: Settings, scenario=None) -> VlmProvider:
    """MOCK_MODE=true -> MockVlmProvider (zero paid calls); else the real one (WS15-07)."""
    if settings.mock_mode:
        from app.providers.mock import Scenario
        from app.providers.vlm_mock import MockVlmProvider

        return MockVlmProvider(scenario or Scenario.ok)
    return UnconfiguredVlmProvider()