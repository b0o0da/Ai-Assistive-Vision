from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Settings from the environment (or the repo-root .env). Keys never live in code."""

    model_config = SettingsConfigDict(
        env_file=("../.env", ".env"), env_file_encoding="utf-8", extra="ignore"
    )

    mock_mode: bool = True
    vlm_provider: Literal["anthropic", "gemini"] = "anthropic"
    vlm_api_key: str = ""
    maps_api_key: str = ""
    database_url: str = "postgresql+psycopg://avn:avn@db:5432/avn"
    app_api_keys: str = "dev-install-key"
    # Hard ceiling for any provider call. Each endpoint also has its own budget below; the
    # gateway uses the smaller of the two (WS22-07).
    provider_timeout_s: float = Field(default=4.0, gt=0, le=30)
    # Per-endpoint time budgets inside the 1.5-4 s cloud target (docs/architecture.md WS-22).
    # Override with ENDPOINT_BUDGETS_S='{"describe": 3.5}' (JSON). Unknown endpoints: ceiling.
    endpoint_budgets_s: dict[str, float] = Field(
        default_factory=lambda: {
            "describe": 4.0,
            "ask": 4.0,
            "screen_summarize": 4.0,
            "intent": 3.0,
            "route": 3.0,
            "faces_enroll": 2.0,
            "faces_match": 1.5,
            "sos": 2.0,
        }
    )
    # Request bodies above this are refused with 413 before parsing. 600 KB covers the
    # contract's 400,000-char base64 image plus the other fields (WS22-07).
    max_body_bytes: int = Field(default=600_000, gt=0)
    log_level: str = "info"
    # Canned responses for MOCK_MODE. Defaults to the repo's contracts/fixtures/api; the Docker
    # image gets them through a read-only volume (see docker-compose.yml).
    mock_fixtures_dir: Path = Path(__file__).resolve().parents[3] / "contracts" / "fixtures" / "api"

    def budget_for(self, endpoint: str) -> float:
        """Seconds the gateway waits for this endpoint's provider: never above the ceiling."""
        budget = self.endpoint_budgets_s.get(endpoint, self.provider_timeout_s)
        return min(max(budget, 0.001), self.provider_timeout_s)

    @property
    def api_keys(self) -> frozenset[str]:
        return frozenset(k.strip() for k in self.app_api_keys.split(",") if k.strip())


@lru_cache
def get_settings() -> Settings:
    return Settings()
