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
    provider_timeout_s: float = Field(default=4.0, gt=0, le=30)
    log_level: str = "info"
    # Canned responses for MOCK_MODE. Defaults to the repo's contracts/fixtures/api; the Docker
    # image gets them through a read-only volume (see docker-compose.yml).
    mock_fixtures_dir: Path = Path(__file__).resolve().parents[3] / "contracts" / "fixtures" / "api"

    @property
    def api_keys(self) -> frozenset[str]:
        return frozenset(k.strip() for k in self.app_api_keys.split(",") if k.strip())


@lru_cache
def get_settings() -> Settings:
    return Settings()
