"""Tests for VlmProvider + MockVlmProvider: deterministic, bounded, no network."""

import socket

import pytest

from app.core.config import Settings
from app.providers.base import ProviderError
from app.providers.mock import Scenario
from app.providers.vlm import UnconfiguredVlmProvider, get_vlm_provider
from app.providers.vlm_mock import CANNED, MockVlmProvider

LIMITS = {"describe": 400, "ask": 600, "screen_summarize": 1200}


@pytest.fixture(autouse=True)
def no_network(monkeypatch):
    real_connect = socket.socket.connect
    real_connect_ex = socket.socket.connect_ex

    def is_local(address):
        return isinstance(address, tuple) and address[0] in ("127.0.0.1", "::1", "localhost")

    def guarded_connect(self, address):
        if not is_local(address):
            raise AssertionError("network call attempted")
        return real_connect(self, address)

    def guarded_connect_ex(self, address):
        if not is_local(address):
            raise AssertionError("network call attempted")
        return real_connect_ex(self, address)

    # Only the event loop's internal localhost socket pair is allowed; anything else fails.
    monkeypatch.setattr(socket.socket, "connect", guarded_connect)
    monkeypatch.setattr(socket.socket, "connect_ex", guarded_connect_ex)


async def test_same_input_same_output():
    p = MockVlmProvider()
    a = await p.complete("hi", template="describe")
    b = await p.complete("hi", template="describe")
    assert a == b


@pytest.mark.parametrize("template", list(CANNED))
async def test_each_template_has_nonempty_answer(template):
    out = await MockVlmProvider().complete("x", template=template)
    assert out.strip()
    if template in LIMITS:
        assert len(out) <= LIMITS[template]


async def test_wrappers_use_right_template():
    p = MockVlmProvider()
    assert await p.describe("img") == CANNED["describe"]
    assert await p.ask("img", "what is it?") == CANNED["ask"]
    assert await p.summarize("text") == CANNED["screen_summarize"]
    assert await p.summarize("text", image="img") == CANNED["screen_summarize"]


async def test_unknown_template_falls_back_to_describe():
    assert await MockVlmProvider().complete("x", template="nope") == CANNED["describe"]


async def test_scenario_timeout():
    with pytest.raises(TimeoutError):
        await MockVlmProvider(Scenario.timeout).complete("x")


async def test_scenario_error():
    with pytest.raises(ProviderError):
        await MockVlmProvider(Scenario.error).complete("x")


async def test_scenario_empty():
    assert await MockVlmProvider(Scenario.empty).complete("x") == ""


def test_factory_mock_mode_on():
    assert isinstance(get_vlm_provider(Settings(mock_mode=True)), MockVlmProvider)


async def test_factory_mock_mode_off_fails_soft():
    p = get_vlm_provider(Settings(mock_mode=False))
    assert isinstance(p, UnconfiguredVlmProvider)
    with pytest.raises(ProviderError):
        await p.complete("x")