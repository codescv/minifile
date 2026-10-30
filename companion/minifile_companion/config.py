"""Configuration storage for MiniFile PC Companion (~/.config/minifile/config.json)."""

from __future__ import annotations

import json
import os
import tempfile
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

DEFAULT_FAVORITES: list[dict[str, str]] = [
    {"name": "Download", "path": "/Download"},
    {"name": "DCIM", "path": "/DCIM"},
    {"name": "Documents", "path": "/Documents"},
]


def default_config_path() -> Path:
    """Resolve the configuration file path, honouring MINIFILE_CONFIG and XDG_CONFIG_HOME."""
    override = os.environ.get("MINIFILE_CONFIG")
    if override:
        return Path(override).expanduser()
    xdg = os.environ.get("XDG_CONFIG_HOME")
    base = Path(xdg).expanduser() if xdg else (Path.home() / ".config")
    return base / "minifile" / "config.json"


def default_cache_dir() -> Path:
    """Resolve cache directory (~/.cache/minifile), honouring MINIFILE_CACHE_DIR and XDG_CACHE_HOME."""
    override = os.environ.get("MINIFILE_CACHE_DIR")
    if override:
        return Path(override).expanduser()
    xdg = os.environ.get("XDG_CACHE_HOME")
    base = Path(xdg).expanduser() if xdg else (Path.home() / ".cache")
    return base / "minifile"


@dataclass
class CompanionConfig:
    """Persisted PC Companion settings, favorites, and cached update metadata."""

    host: str = ""
    port: int = 2121
    user: str = "anonymous"
    password: str = "minifile@"
    favorites: list[dict[str, str]] = field(
        default_factory=lambda: [dict(item) for item in DEFAULT_FAVORITES]
    )
    update_hint: bool = True
    update_last_check: float = 0.0
    update_last_force_check: float = 0.0
    update_last_hint: float = 0.0
    update_latest_tag: str = ""
    update_name: str = ""
    update_notes: str = ""
    update_html_url: str = ""
    update_asset_url: str = ""
    update_asset_size: int = -1
    update_published_at: str = ""

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> CompanionConfig:
        raw_favs = data.get("favorites")
        if isinstance(raw_favs, list):
            favorites: list[dict[str, str]] = []
            for item in raw_favs:
                if isinstance(item, dict) and "name" in item and "path" in item:
                    favorites.append(
                        {
                            "name": str(item["name"]).strip() or str(item["path"]),
                            "path": str(item["path"]).strip() or "/",
                        }
                    )
        else:
            favorites = [dict(item) for item in DEFAULT_FAVORITES]

        def _int(val: Any, default: int) -> int:
            try:
                return int(val)
            except (TypeError, ValueError):
                return default

        def _float(val: Any, default: float) -> float:
            try:
                return float(val)
            except (TypeError, ValueError):
                return default

        return cls(
            host=str(data.get("host", "")),
            port=_int(data.get("port", 2121), 2121),
            user=str(data.get("user", "anonymous")),
            password=str(data.get("password", "minifile@")),
            favorites=favorites,
            update_hint=bool(data.get("update_hint", True)),
            update_last_check=_float(data.get("update_last_check", 0.0), 0.0),
            update_last_force_check=_float(data.get("update_last_force_check", 0.0), 0.0),
            update_last_hint=_float(data.get("update_last_hint", 0.0), 0.0),
            update_latest_tag=str(data.get("update_latest_tag", "")),
            update_name=str(data.get("update_name", "")),
            update_notes=str(data.get("update_notes", "")),
            update_html_url=str(data.get("update_html_url", "")),
            update_asset_url=str(data.get("update_asset_url", "")),
            update_asset_size=_int(data.get("update_asset_size", -1), -1),
            update_published_at=str(data.get("update_published_at", "")),
        )

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


class ConfigStore:
    """Atomic reader/writer for CompanionConfig."""

    def __init__(self, path: Path | str | None = None) -> None:
        self.path = Path(path).expanduser() if path is not None else default_config_path()

    def load(self) -> CompanionConfig:
        if not self.path.is_file():
            return CompanionConfig()
        try:
            raw = self.path.read_text(encoding="utf-8")
            parsed = json.loads(raw)
            if isinstance(parsed, dict):
                return CompanionConfig.from_dict(parsed)
        except (OSError, json.JSONDecodeError):
            pass
        return CompanionConfig()

    def save(self, config: CompanionConfig) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        payload = json.dumps(config.to_dict(), indent=2, ensure_ascii=False) + "\n"
        fd, tmp_name = tempfile.mkstemp(
            prefix=".config-", suffix=".tmp", dir=str(self.path.parent)
        )
        try:
            try:
                os.chmod(fd, 0o600)
            except (OSError, NotImplementedError):
                pass
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                handle.write(payload)
            os.replace(tmp_name, self.path)
        except BaseException:
            try:
                os.unlink(tmp_name)
            except OSError:
                pass
            raise

    def update_connection(
        self,
        host: str,
        port: int = 2121,
        user: str = "anonymous",
        password: str | None = None,
    ) -> CompanionConfig:
        cfg = self.load()
        cfg.host = host
        cfg.port = int(port)
        cfg.user = user
        if password is not None:
            cfg.password = password
        self.save(cfg)
        return cfg

    def get_favorites(self) -> list[dict[str, str]]:
        return self.load().favorites

    def set_favorites(self, favorites: list[dict[str, str]]) -> list[dict[str, str]]:
        cfg = self.load()
        cleaned: list[dict[str, str]] = []
        for item in favorites:
            if not isinstance(item, dict):
                continue
            path = str(item.get("path", "")).strip()
            if not path:
                continue
            if not path.startswith("/"):
                path = "/" + path
            name = str(item.get("name", "")).strip() or path.rsplit("/", 1)[-1] or "/"
            cleaned.append({"name": name, "path": path})
        cfg.favorites = cleaned
        self.save(cfg)
        return cleaned
