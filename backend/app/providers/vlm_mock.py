"""MockVlmProvider: deterministic canned answers per template, no network (MOCK_MODE=true).

Scenarios (same as MockProvider): ok | timeout | error | empty.
Never logs prompts, images or answers.
"""

from collections.abc import Sequence

from app.providers.base import ProviderError
from app.providers.mock import Scenario
from app.providers.vlm import DEFAULT_MAX_TOKENS, DEFAULT_TIMEOUT_S, VlmProvider

CANNED: dict[str, str] = {
    "describe": (
        "A narrow corridor with a door ahead and a chair on your right. "
        "I may be wrong about small objects."
    ),
    "ask": "It looks like a closed wooden door in front of you. I may be wrong.",
    "screen_summarize": (
        "A chat with two new messages. The latest asks if you are free tonight. "
        "I may have missed some details."
    ),
    "intent": '{"steps": [{"step": "describe"}], "requires_confirmation": false}',
}
DEFAULT_TEMPLATE = "describe"


class MockVlmProvider(VlmProvider):
    def __init__(self, scenario: Scenario = Scenario.ok) -> None:
        self.scenario = scenario

    async def complete(
        self,
        prompt: str,
        images: Sequence[str] = (),
        max_tokens: int = DEFAULT_MAX_TOKENS,
        timeout: float = DEFAULT_TIMEOUT_S,  # noqa: ASYNC109
        *,
        template: str | None = None,
    ) -> str:
        if self.scenario is Scenario.timeout:
            raise TimeoutError("mock timeout")
        if self.scenario is Scenario.error:
            raise ProviderError("mock VLM error")
        if self.scenario is Scenario.empty:
            return ""
        return CANNED.get(template or DEFAULT_TEMPLATE, CANNED[DEFAULT_TEMPLATE])