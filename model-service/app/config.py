"""Shared backend/model configuration without changing the process environment."""

import os
from pathlib import Path

from dotenv import dotenv_values


def config_path() -> Path:
    configured = os.getenv("APP_ENV_FILE", "").strip()
    return Path(configured).expanduser().resolve() if configured else Path(__file__).resolve().parents[2] / ".env"


def read_config() -> dict[str, str]:
    path = config_path()
    if not path.exists():
        return {}
    # Do not interpolate values: a key or password containing '$' must stay literal.
    return {key: value.strip() for key, value in dotenv_values(path, interpolate=False).items() if value is not None}


def config_value(values: dict[str, str], name: str, default: str = "", *, allow_empty: bool = False) -> str:
    if name in values and (values[name] or allow_empty):
        return values[name]
    return os.getenv(name, "").strip() or default
