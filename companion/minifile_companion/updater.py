"""PC Companion GitHub Release checker and safe self-updater (ARCHITECTURE §11.3)."""

from __future__ import annotations

import functools
import hashlib
import json
import os
import re
import shlex
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from collections.abc import Callable
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

from . import __version__
from .client import ConnectionAuthError, MiniFileError
from .config import ConfigStore

DEFAULT_GITHUB_REPO = "codescv/minifile"
GITHUB_LATEST = f"https://api.github.com/repos/{DEFAULT_GITHUB_REPO}/releases/latest"
GITHUB_LIST = f"https://api.github.com/repos/{DEFAULT_GITHUB_REPO}/releases?per_page=5"
DEFAULT_RELEASE_HTML_URL = f"https://github.com/{DEFAULT_GITHUB_REPO}/releases"
ALLOWED_ASSET_PREFIX = f"https://github.com/{DEFAULT_GITHUB_REPO}/releases/download/"

MAX_REDIRECTS = 5
MAX_RELEASE_JSON_BYTES = 2 * 1024 * 1024
MAX_CHECKSUM_BYTES = 256 * 1024
MAX_ARCHIVE_BYTES = 200 * 1024 * 1024
MAX_ZIP_ENTRIES = 20_000
MAX_UNCOMPRESSED_BYTES = 200 * 1024 * 1024

FORCE_CHECK_COOLDOWN_SECONDS = 60.0
AUTO_CHECK_TTL_SECONDS = 6 * 3600.0
HINT_COOLDOWN_SECONDS = 24 * 3600.0

_SEMVER_RE = re.compile(r"^(\d+)\.(\d+)(?:\.(\d+))?(?:-([0-9A-Za-z.\-]+))?$")
_COMPANION_ZIP_RE = re.compile(r"^minifile-companion-.*\.zip$", re.IGNORECASE)
_DRIVE_RE = re.compile(r"^[A-Za-z]:")
_INIT_VERSION_RE = re.compile(r'^__version__\s*=\s*["\']([^"\']+)["\']', re.MULTILINE)
_SAFE_GIT_TAG_RE = re.compile(r"^v?\d+\.\d+(?:\.\d+)?(?:-[0-9A-Za-z.\-]+)?$")


class UpdateError(MiniFileError):
    """Error raised when verifying or applying a companion update."""


class UpdateNetworkError(ConnectionAuthError, UpdateError):
    """Network, timeout, HTTP status, or rate-limit error while contacting release server."""


class UpdateSecurityError(UpdateError):
    """Security violation during update (redirect downgrade, bad hash, zip-slip, etc.)."""


@functools.total_ordering
@dataclass(frozen=True)
class SemanticVersion:
    """SemVer 2.0 version parser and comparator matching Android SemanticVersion.kt."""

    major: int
    minor: int
    patch: int = 0
    pre_release: str | None = None

    def __str__(self) -> str:
        suffix = f"-{self.pre_release}" if self.pre_release else ""
        return f"{self.major}.{self.minor}.{self.patch}{suffix}"

    def as_tuple(self) -> tuple[int, int, int, str | None]:
        return (self.major, self.minor, self.patch, self.pre_release)

    @property
    def is_prerelease(self) -> bool:
        return self.pre_release is not None

    def __lt__(self, other: object) -> bool:
        if not isinstance(other, SemanticVersion):
            return NotImplemented
        if (self.major, self.minor, self.patch) != (other.major, other.minor, other.patch):
            return (self.major, self.minor, self.patch) < (
                other.major,
                other.minor,
                other.patch,
            )
        return _compare_prerelease(self.pre_release, other.pre_release) < 0

    @classmethod
    def strip_debug_suffix(cls, text: str | None) -> str | None:
        if text is None:
            return None
        s = text.strip()
        if s.lower().endswith("-debug"):
            s = s[:-6]
        return s

    @classmethod
    def parse(cls, text: str | None) -> SemanticVersion | None:
        s = cls.strip_debug_suffix(text)
        if not s:
            return None
        if s.startswith(("v", "V")):
            s = s[1:]
        if "+" in s:
            s = s.split("+", 1)[0]
        m = _SEMVER_RE.match(s)
        if not m:
            return None
        try:
            major = int(m.group(1))
            minor = int(m.group(2))
            patch = int(m.group(3) or "0")
        except ValueError:
            return None
        pre = m.group(4) or None
        if pre is not None and any(part == "" for part in pre.split(".")):
            return None
        return cls(major=major, minor=minor, patch=patch, pre_release=pre)


def _compare_prerelease(a: str | None, b: str | None) -> int:
    if a == b:
        return 0
    if a is None:
        return 1
    if b is None:
        return -1
    pa = a.split(".")
    pb = b.split(".")
    for x, y in zip(pa, pb):
        x_num = int(x) if x.isdigit() else None
        y_num = int(y) if y.isdigit() else None
        if x_num is not None and y_num is not None:
            if x_num != y_num:
                return -1 if x_num < y_num else 1
        elif x_num is not None:
            return -1
        elif y_num is not None:
            return 1
        else:
            if x != y:
                return -1 if x < y else 1
    if len(pa) != len(pb):
        return -1 if len(pa) < len(pb) else 1
    return 0


def parse_version(s: str | None) -> tuple[int, int, int, str | None] | None:
    """Parse version string into (major, minor, patch, pre_release) or None if invalid."""
    parsed = SemanticVersion.parse(s)
    return parsed.as_tuple() if parsed is not None else None


def is_newer(current: str | None, candidate: str | None) -> bool:
    """Return True iff both parse as valid SemVer and candidate > current."""
    cur = SemanticVersion.parse(current)
    cand = SemanticVersion.parse(candidate)
    if cur is None or cand is None:
        return False
    return cand > cur


@dataclass(frozen=True)
class UpdateCheck:
    """Result of check_latest() per ARCHITECTURE §11.3."""

    current: str
    latest_tag: str
    name: str
    notes: str
    html_url: str
    asset_url: str | None
    asset_name: str | None
    asset_size: int
    checksums_url: str | None
    published_at: str | None
    available: bool
    prerelease: bool = False

    @property
    def latest_version(self) -> str:
        t = self.latest_tag
        return t[1:] if t.startswith(("v", "V")) else t

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


class _NoAutoRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Disable automatic urllib redirect following so every hop is audited manually."""

    def redirect_request(
        self, req: Any, fp: Any, code: int, msg: str, headers: Any, newurl: str
    ) -> None:
        return None

    def http_error_301(
        self, req: Any, fp: Any, code: int, msg: str, headers: Any
    ) -> Any:
        return fp

    http_error_302 = http_error_301
    http_error_303 = http_error_301
    http_error_307 = http_error_301
    http_error_308 = http_error_301


def _is_allowed_loopback_host(hostname: str | None) -> bool:
    if not hostname:
        return False
    h = hostname.strip().lower()
    return h in ("127.0.0.1", "localhost", "::1")


def _validate_url(url: str, allow_http_loopback: bool = False) -> urllib.parse.ParseResult:
    parsed = urllib.parse.urlparse(url)
    scheme = parsed.scheme.lower()
    if scheme == "https":
        if not parsed.hostname:
            raise UpdateSecurityError(f"Missing hostname in URL: {url}")
        return parsed
    if scheme == "http":
        if allow_http_loopback and _is_allowed_loopback_host(parsed.hostname):
            return parsed
        raise UpdateSecurityError(f"Insecure HTTP URL rejected: {url}")
    raise UpdateSecurityError(f"Unsupported URL scheme {scheme!r} in {url}")


def _is_allowed_asset_url(url: str, allow_http_loopback: bool = False) -> bool:
    if url.startswith(ALLOWED_ASSET_PREFIX):
        return True
    if allow_http_loopback:
        parsed = urllib.parse.urlparse(url)
        if parsed.scheme.lower() == "http" and _is_allowed_loopback_host(parsed.hostname):
            return True
    return False


def _open_with_redirects(
    url: str,
    headers: dict[str, str] | None = None,
    timeout: float = 15.0,
    opener: urllib.request.OpenerDirector | None = None,
    allow_http_loopback: bool = False,
) -> tuple[Any, str]:
    """Open URL following <= 5 redirects manually with HTTPS & downgrade enforcement."""
    active_opener = opener or urllib.request.build_opener(_NoAutoRedirectHandler())
    current_url = url
    req_headers = {
        "User-Agent": f"MiniFile-Companion/{__version__}",
        **(headers or {}),
    }

    for hop in range(MAX_REDIRECTS + 1):
        parsed_cur = _validate_url(current_url, allow_http_loopback=allow_http_loopback)
        req = urllib.request.Request(current_url, headers=req_headers, method="GET")
        try:
            resp = active_opener.open(req, timeout=timeout)
        except urllib.error.HTTPError as exc:
            if exc.code in (301, 302, 303, 307, 308):
                resp = exc
            else:
                raise

        status = getattr(resp, "status", None) or resp.getcode()
        if status in (301, 302, 303, 307, 308):
            loc = resp.headers.get("Location")
            resp.close()
            if not loc:
                raise UpdateNetworkError(f"Redirect HTTP {status} missing Location header")
            if hop >= MAX_REDIRECTS:
                raise UpdateSecurityError(
                    f"Redirect limit ({MAX_REDIRECTS}) exceeded for {url}"
                )
            next_url = urllib.parse.urljoin(current_url, loc)
            parsed_next = urllib.parse.urlparse(next_url)
            if parsed_cur.scheme.lower() == "https" and parsed_next.scheme.lower() != "https":
                raise UpdateSecurityError(
                    f"Refusing HTTPS downgrade redirect: {current_url} -> {next_url}"
                )
            _validate_url(next_url, allow_http_loopback=allow_http_loopback)
            if parsed_cur.netloc.lower() != parsed_next.netloc.lower():
                req_headers.pop("Authorization", None)
            current_url = next_url
            continue

        return resp, current_url

    raise UpdateSecurityError(f"Redirect limit ({MAX_REDIRECTS}) exceeded")


def _read_bounded(resp: Any, max_bytes: int) -> bytes:
    chunks: list[bytes] = []
    total = 0
    while True:
        chunk = resp.read(65536)
        if not chunk:
            break
        total += len(chunk)
        if total > max_bytes:
            raise UpdateSecurityError(
                f"Response exceeded size cap ({max_bytes} bytes)"
            )
        chunks.append(chunk)
    return b"".join(chunks)


def _pick_companion_zip_from_assets(
    assets: list[dict[str, Any]], allow_http_loopback: bool = False
) -> dict[str, Any] | None:
    allowed: list[dict[str, Any]] = []
    for item in assets:
        if not isinstance(item, dict):
            continue
        name = str(item.get("name") or "").strip()
        url = str(item.get("browser_download_url") or "").strip()
        if not name or not url:
            continue
        if not _is_allowed_asset_url(url, allow_http_loopback=allow_http_loopback):
            continue
        allowed.append(item)

    for item in allowed:
        if _COMPANION_ZIP_RE.match(str(item["name"])):
            return item
    for item in allowed:
        lower = str(item["name"]).lower()
        if "companion" in lower and lower.endswith(".zip"):
            return item
    return None


def _pick_checksums_from_assets(
    assets: list[dict[str, Any]],
    archive_name: str | None,
    allow_http_loopback: bool = False,
) -> dict[str, Any] | None:
    allowed = [
        item
        for item in assets
        if isinstance(item, dict)
        and item.get("name")
        and item.get("browser_download_url")
        and _is_allowed_asset_url(
            str(item["browser_download_url"]), allow_http_loopback=allow_http_loopback
        )
    ]
    for item in allowed:
        if str(item["name"]).strip().lower() == "sha256sums.txt":
            return item
    if archive_name:
        exact = f"{archive_name}.sha256".lower()
        for item in allowed:
            if str(item["name"]).strip().lower() == exact:
                return item
    for item in allowed:
        if str(item["name"]).strip().lower() in ("sha256sums", "checksums.txt"):
            return item
    return None


def parse_release_payload(
    raw_json: str,
    current: str = __version__,
    include_pre: bool = False,
    allow_http_loopback: bool = False,
) -> UpdateCheck:
    """Parse GitHub release JSON (single object or list) into UpdateCheck."""
    try:
        data = json.loads(raw_json)
    except json.JSONDecodeError as exc:
        raise UpdateError(f"Malformed GitHub release JSON: {exc}") from exc

    chosen: dict[str, Any] | None = None
    if isinstance(data, dict):
        if data.get("draft") is True:
            raise UpdateError("Release is a draft")
        if data.get("prerelease") is True and not include_pre:
            # Caller may inspect list if latest was prerelease, else not available
            chosen = data
        else:
            chosen = data
    elif isinstance(data, list):
        candidates = [
            r
            for r in data
            if isinstance(r, dict)
            and r.get("draft") is not True
            and (include_pre or r.get("prerelease") is not True)
        ]
        if not candidates:
            raise UpdateError("No matching non-draft release found")
        chosen = candidates[0]
    else:
        raise UpdateError("Unexpected GitHub release JSON type")

    tag = str(chosen.get("tag_name") or "").strip()
    if not tag:
        raise UpdateError("GitHub release JSON missing tag_name")

    is_pre = bool(chosen.get("prerelease") is True)
    raw_assets = chosen.get("assets") if isinstance(chosen.get("assets"), list) else []
    comp = _pick_companion_zip_from_assets(
        raw_assets, allow_http_loopback=allow_http_loopback
    )
    comp_name = str(comp["name"]).strip() if comp else None
    comp_url = str(comp["browser_download_url"]).strip() if comp else None
    try:
        comp_size = int(comp.get("size", -1)) if comp else -1
    except (TypeError, ValueError):
        comp_size = -1

    chk = _pick_checksums_from_assets(
        raw_assets, comp_name, allow_http_loopback=allow_http_loopback
    )
    chk_url = str(chk["browser_download_url"]).strip() if chk else None

    newer = is_newer(current, tag) and (include_pre or not is_pre)
    return UpdateCheck(
        current=current,
        latest_tag=tag,
        name=str(chosen.get("name") or "").strip() or tag,
        notes=str(chosen.get("body") or ""),
        html_url=str(chosen.get("html_url") or "").strip() or DEFAULT_RELEASE_HTML_URL,
        asset_url=comp_url,
        asset_name=comp_name,
        asset_size=comp_size,
        checksums_url=chk_url,
        published_at=(
            str(chosen["published_at"]) if chosen.get("published_at") else None
        ),
        available=newer,
        prerelease=is_pre,
    )


def check_latest(
    current: str = __version__,
    include_pre: bool = False,
    timeout: float = 15.0,
    opener: urllib.request.OpenerDirector | None = None,
    allow_http_loopback: bool = False,
    api_base: str | None = None,
) -> UpdateCheck:
    """Query GitHub Releases (/releases/latest with /releases?per_page=5 fallback)."""
    base = (
        api_base
        or os.environ.get("MINIFILE_UPDATE_API_URL")
        or f"https://api.github.com/repos/{DEFAULT_GITHUB_REPO}"
    ).rstrip("/")
    latest_url = f"{base}/releases/latest"
    list_url = f"{base}/releases?per_page=5"

    headers = {
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }

    def _fetch(target_url: str) -> str:
        resp, _ = _open_with_redirects(
            target_url,
            headers=headers,
            timeout=timeout,
            opener=opener,
            allow_http_loopback=allow_http_loopback,
        )
        try:
            return _read_bounded(resp, MAX_RELEASE_JSON_BYTES).decode("utf-8")
        finally:
            resp.close()

    raw_text: str | None = None
    if not include_pre:
        try:
            raw_text = _fetch(latest_url)
        except urllib.error.HTTPError as exc:
            if exc.code == 404:
                raw_text = None
            elif exc.code in (403, 429) and exc.headers.get("X-RateLimit-Remaining") == "0":
                raise UpdateNetworkError("GitHub API rate limit exceeded; try again later") from exc
            else:
                raise UpdateNetworkError(f"GitHub API HTTP {exc.code}") from exc
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            raise UpdateNetworkError(f"Network error checking updates: {exc}") from exc

    if raw_text is None:
        try:
            raw_text = _fetch(list_url)
        except urllib.error.HTTPError as exc:
            if exc.code in (403, 429) and exc.headers.get("X-RateLimit-Remaining") == "0":
                raise UpdateNetworkError("GitHub API rate limit exceeded; try again later") from exc
            raise UpdateNetworkError(f"GitHub API HTTP {exc.code}") from exc
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            raise UpdateNetworkError(f"Network error checking updates: {exc}") from exc

    return parse_release_payload(
        raw_text,
        current=current,
        include_pre=include_pre,
        allow_http_loopback=allow_http_loopback,
    )


def parse_sha256_sums(text: str, asset_basename: str) -> str | None:
    """Find the SHA-256 hex digest matching asset_basename in SHA256SUMS.txt."""
    want = os.path.basename(asset_basename).lower()
    single_digest: str | None = None
    non_empty_lines = 0
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        non_empty_lines += 1
        parts = line.split()
        digest = parts[0].lower()
        if len(digest) != 64 or not all(c in "0123456789abcdef" for c in digest):
            continue
        if len(parts) == 1:
            single_digest = digest
        else:
            fname = os.path.basename(parts[-1].lstrip("*")).lower()
            if fname == want:
                return digest
    if non_empty_lines == 1 and single_digest is not None:
        return single_digest
    return None


def fetch_sha256_for_asset(
    checksums_url: str,
    asset_name: str,
    timeout: float = 15.0,
    opener: urllib.request.OpenerDirector | None = None,
    allow_http_loopback: bool = False,
) -> str:
    """Download SHA256SUMS.txt and extract the required SHA-256 digest for asset_name."""
    try:
        resp, _ = _open_with_redirects(
            checksums_url,
            headers={"Accept": "application/octet-stream", "Accept-Encoding": "identity"},
            timeout=timeout,
            opener=opener,
            allow_http_loopback=allow_http_loopback,
        )
        try:
            body = _read_bounded(resp, MAX_CHECKSUM_BYTES).decode("utf-8", errors="replace")
        finally:
            resp.close()
    except UpdateError:
        raise
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        raise UpdateNetworkError(f"Failed to download SHA256SUMS.txt: {exc}") from exc

    digest = parse_sha256_sums(body, asset_name)
    if not digest:
        raise UpdateSecurityError(
            f"SHA256SUMS.txt does not contain an entry for {asset_name!r}"
        )
    return digest


def download_asset(
    url: str,
    dest_dir: Path | str,
    expected_size: int = -1,
    sha256: str | None = None,
    progress: Callable[[int, int], None] | None = None,
    opener: urllib.request.OpenerDirector | None = None,
    allow_http_loopback: bool = False,
    timeout: float = 30.0,
    filename: str | None = None,
) -> Path:
    """Stream asset to <dest>.tmp, verify size, SHA-256, and PK header, then rename atomically."""
    if not _is_allowed_asset_url(url, allow_http_loopback=allow_http_loopback):
        raise UpdateSecurityError(f"Asset URL not in GitHub release allow-list: {url}")

    dest_root = Path(dest_dir)
    dest_root.mkdir(parents=True, exist_ok=True)
    parsed_path = urllib.parse.urlparse(url).path
    inferred_name = filename or os.path.basename(urllib.parse.unquote(parsed_path)) or "companion.zip"
    clean_name = os.path.basename(inferred_name)
    if not clean_name or clean_name in (".", "..") or "\x00" in clean_name:
        raise UpdateSecurityError(f"Unsafe asset filename: {inferred_name!r}")

    final_path = dest_root / clean_name
    tmp_path = dest_root / f"{clean_name}.tmp"

    size_cap = expected_size if expected_size > 0 else MAX_ARCHIVE_BYTES
    hasher = hashlib.sha256()
    written = 0

    try:
        try:
            resp, _ = _open_with_redirects(
                url,
                headers={"Accept": "application/octet-stream", "Accept-Encoding": "identity"},
                timeout=timeout,
                opener=opener,
                allow_http_loopback=allow_http_loopback,
            )
        except UpdateError:
            raise
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            raise UpdateNetworkError(f"Download failed: {exc}") from exc

        try:
            cl_hdr = resp.headers.get("Content-Length")
            cl_val = int(cl_hdr) if cl_hdr and cl_hdr.isdigit() else -1
            if expected_size > 0 and cl_val >= 0 and cl_val != expected_size:
                raise UpdateSecurityError(
                    f"Content-Length ({cl_val}) does not match release asset size ({expected_size})"
                )
            total_target = expected_size if expected_size > 0 else cl_val

            with open(tmp_path, "wb") as out_f:
                while True:
                    chunk = resp.read(65536)
                    if not chunk:
                        break
                    written += len(chunk)
                    if written > size_cap or written > MAX_ARCHIVE_BYTES:
                        raise UpdateSecurityError(
                            f"Download exceeded allowed size ({written} bytes)"
                        )
                    hasher.update(chunk)
                    out_f.write(chunk)
                    if progress is not None:
                        progress(written, total_target)
        finally:
            resp.close()

        if expected_size > 0 and written != expected_size:
            raise UpdateSecurityError(
                f"Downloaded size ({written}) != expected ({expected_size})"
            )
        if cl_val >= 0 and written != cl_val:
            raise UpdateSecurityError(
                f"Downloaded size ({written}) != Content-Length ({cl_val})"
            )

        with open(tmp_path, "rb") as check_f:
            magic = check_f.read(4)
        if magic != b"PK\x03\x04":
            raise UpdateSecurityError("Downloaded asset is not a valid non-empty ZIP archive")

        actual_sha = hasher.hexdigest().lower()
        if sha256 and actual_sha != sha256.strip().lower():
            raise UpdateSecurityError(
                f"SHA-256 verification failed: expected {sha256.lower()}, got {actual_sha}"
            )

        os.replace(tmp_path, final_path)
        return final_path
    except BaseException:
        try:
            if tmp_path.exists():
                tmp_path.unlink()
        except OSError:
            pass
        raise


def _validate_zip_entry_segments(entry_name: str) -> list[str]:
    """Enforce ARCHITECTURE §5.2 & §11.3 zip-slip rules (abort on any unsafe name)."""
    if not entry_name or "\x00" in entry_name:
        raise UpdateSecurityError(f"Unsafe empty or NUL zip entry: {entry_name!r}")
    if "\\" in entry_name:
        raise UpdateSecurityError(f"Backslash rejected in zip entry: {entry_name!r}")
    if entry_name.startswith("/") or _DRIVE_RE.match(entry_name):
        raise UpdateSecurityError(f"Absolute or drive-letter zip entry rejected: {entry_name!r}")

    segments: list[str] = []
    for seg in entry_name.split("/"):
        if seg == "":
            continue
        if seg in (".", ".."):
            raise UpdateSecurityError(
                f"Traversal segment {seg!r} rejected in zip entry: {entry_name!r}"
            )
        segments.append(seg)
    return segments


def _inspect_companion_zip(zip_path: Path) -> tuple[str, str]:
    """Validate zip safety and return (detected_version, strip_prefix)."""
    if not zip_path.is_file():
        raise UpdateError(f"Archive not found: {zip_path}")
    with open(zip_path, "rb") as fh:
        if fh.read(4) != b"PK\x03\x04":
            raise UpdateSecurityError("Invalid ZIP file magic")

    try:
        zf_ctx = zipfile.ZipFile(zip_path, "r")
    except zipfile.BadZipFile as exc:
        raise UpdateSecurityError(f"Corrupt ZIP archive: {exc}") from exc

    with open(zip_path, "rb") as raw_fh, zf_ctx as zf:
        infos = zf.infolist()
        if len(infos) > MAX_ZIP_ENTRIES:
            raise UpdateSecurityError(
                f"ZIP entry count ({len(infos)}) exceeds cap ({MAX_ZIP_ENTRIES})"
            )

        total_uncompressed = 0
        init_candidates: list[tuple[list[str], zipfile.ZipInfo]] = []

        for info in infos:
            if info.header_offset is not None and info.header_offset >= 0:
                raw_fh.seek(info.header_offset)
                lhdr = raw_fh.read(30)
                if len(lhdr) == 30 and lhdr[:4] == b"PK\x03\x04":
                    fname_len = int.from_bytes(lhdr[26:28], "little")
                    if b"\x00" in raw_fh.read(fname_len):
                        raise UpdateSecurityError(
                            "NUL byte rejected in raw ZIP header filename"
                        )
            unix_mode = (info.external_attr >> 16) & 0xFFFF
            if unix_mode and stat.S_ISLNK(unix_mode):
                raise UpdateSecurityError(
                    f"Symlink entry rejected in update archive: {info.filename!r}"
                )
            segs = _validate_zip_entry_segments(info.filename)
            if not segs:
                continue
            total_uncompressed += max(0, info.file_size)
            if total_uncompressed > MAX_UNCOMPRESSED_BYTES:
                raise UpdateSecurityError(
                    f"ZIP uncompressed size exceeds {MAX_UNCOMPRESSED_BYTES} bytes cap"
                )
            if segs == ["minifile_companion", "__init__.py"] or (
                len(segs) == 3 and segs[1:] == ["minifile_companion", "__init__.py"]
            ):
                init_candidates.append((segs, info))

        if not init_candidates:
            raise UpdateSecurityError(
                "Archive missing (companion/)?minifile_companion/__init__.py at depth <= 1"
            )

        # Prefer canonical `companion/minifile_companion/__init__.py`, else depth-0/depth-1 match
        chosen_segs, chosen_info = next(
            (c for c in init_candidates if c[0][0] == "companion"),
            init_candidates[0],
        )
        prefix = chosen_segs[0] if len(chosen_segs) == 3 else ""
        if chosen_info.file_size > 256 * 1024:
            raise UpdateSecurityError("__init__.py in archive is unexpectedly large")
        init_text = zf.read(chosen_info).decode("utf-8", errors="strict")
        m = _INIT_VERSION_RE.search(init_text)
        if not m:
            raise UpdateSecurityError(
                "minifile_companion/__init__.py in archive lacks __version__"
            )
        ver_str = m.group(1).strip()
        if SemanticVersion.parse(ver_str) is None:
            raise UpdateSecurityError(
                f"Invalid __version__ {ver_str!r} inside companion archive"
            )
        return ver_str, prefix


def verify_companion_zip(path: Path | str) -> str:
    """Verify companion ZIP archive safety & structure and return its __version__ string."""
    ver, _ = _inspect_companion_zip(Path(path))
    return ver


def stage_and_apply(
    zip_path: Path | str,
    install_dir: Path | str,
    apply: bool = False,
    current_version: str = __version__,
) -> Path:
    """Safely extract archive to sibling companion-v<ver> and optionally swap with install_dir."""
    archive = Path(zip_path)
    live_dir = Path(install_dir).resolve()
    ver, strip_prefix = _inspect_companion_zip(archive)

    parent_dir = live_dir.parent
    parent_dir.mkdir(parents=True, exist_ok=True)
    staged_target = parent_dir / f"companion-v{ver}"

    tmp_stage = Path(
        tempfile.mkdtemp(prefix=f".companion-v{ver}-stage-", dir=str(parent_dir))
    )
    try:
        stage_real = os.path.realpath(tmp_stage)
        actual_written = 0
        with zipfile.ZipFile(archive, "r") as zf:
            for info in zf.infolist():
                segs = _validate_zip_entry_segments(info.filename)
                if not segs:
                    continue
                if strip_prefix:
                    if segs[0] != strip_prefix:
                        continue
                    segs = segs[1:]
                    if not segs:
                        continue

                dst_item = tmp_stage.joinpath(*segs)
                dst_real = os.path.realpath(dst_item)
                if dst_real != stage_real and not dst_real.startswith(stage_real + os.sep):
                    raise UpdateSecurityError(
                        f"Zip entry escaped staging root: {info.filename!r}"
                    )
                if info.is_dir():
                    dst_item.mkdir(parents=True, exist_ok=True)
                    continue
                dst_item.parent.mkdir(parents=True, exist_ok=True)
                with zf.open(info, "r") as src_f, open(dst_item, "wb") as out_f:
                    while True:
                        buf = src_f.read(65536)
                        if not buf:
                            break
                        actual_written += len(buf)
                        if actual_written > MAX_UNCOMPRESSED_BYTES:
                            raise UpdateSecurityError(
                                "Decompressed stream exceeded 200 MiB zip-bomb limit"
                            )
                        out_f.write(buf)

        if not (tmp_stage / "minifile_companion" / "__init__.py").is_file():
            raise UpdateSecurityError(
                "Staged directory missing minifile_companion/__init__.py"
            )

        if staged_target.exists():
            shutil.rmtree(staged_target)
        os.replace(tmp_stage, staged_target)
    except BaseException:
        shutil.rmtree(tmp_stage, ignore_errors=True)
        raise

    if not apply:
        return staged_target

    # Check git checkout guard and writability before swapping
    for anc in (live_dir, *live_dir.parents):
        if (anc / ".git").exists():
            raise UpdateError(
                f"Refusing --apply swap inside Git repository ({anc}); staged cleanly at {staged_target}"
            )
    if not os.access(parent_dir, os.W_OK):
        raise UpdateError(
            f"Parent directory {parent_dir} is not writable; staged at {staged_target}"
        )

    backup_dir = parent_dir / f"{live_dir.name}-v{current_version}.bak"
    if backup_dir.exists():
        shutil.rmtree(backup_dir)
    if live_dir.exists():
        os.replace(live_dir, backup_dir)
    try:
        os.replace(staged_target, live_dir)
    except BaseException:
        if backup_dir.exists() and not live_dir.exists():
            os.replace(backup_dir, live_dir)
        raise
    return live_dir


def is_source_tree(pkg_file: Path | str | None = None) -> bool:
    """Return True when running from a git checkout or standalone companion folder (not site-packages)."""
    here = Path(pkg_file or __file__).resolve()
    pkg_parent = here.parent.parent
    if pkg_parent.name.lower() in ("site-packages", "dist-packages"):
        return False
    for anc in (pkg_parent, *pkg_parent.parents):
        if (anc / ".git").exists():
            return True
    if (pkg_parent / "minifile.py").is_file() or (pkg_parent / "pyproject.toml").is_file():
        return True
    return False


def is_uv_tool_env(
    prefix: Path | str | None = None,
    pkg_file: Path | str | None = None,
    which_fn: Callable[[str], str | None] = shutil.which,
) -> bool:
    """Return True if installed inside a `uv tool` environment or non-source install with `uv`."""
    pfx_str = str(prefix if prefix is not None else sys.prefix)
    norm_pfx = pfx_str.replace("\\", "/").rstrip("/") + "/"
    if "/uv/tools/" in norm_pfx:
        return True
    try:
        if (Path(pfx_str) / "uv-receipt.toml").is_file():
            return True
    except OSError:
        pass
    if not is_source_tree(pkg_file) and which_fn("uv") is not None:
        return True
    return False


def _validate_release_tag_for_git(tag: str) -> str:
    clean = tag.strip()
    if (
        not clean
        or not _SAFE_GIT_TAG_RE.match(clean)
        or SemanticVersion.parse(clean) is None
    ):
        raise UpdateSecurityError(f"Unsafe or invalid release tag for git install: {tag!r}")
    return clean


def format_uv_upgrade_command(tag: str, repo: str = DEFAULT_GITHUB_REPO) -> str:
    """Build a validated `uv tool install --force` command string for the given release tag."""
    safe_tag = _validate_release_tag_for_git(tag)
    return f"uv tool install --force git+https://github.com/{repo}.git@{safe_tag}"


@dataclass(frozen=True)
class UpdateResult:
    """Outcome of staging or applying a PC Companion update."""

    updated: bool
    applied_in_place: bool
    method: str  # "uv" | "zip" | "none"
    version: str
    tag: str
    path: Path
    archive: Path | None = None
    uv_command: str | None = None
    fallback_reason: str | None = None
    instructions: str = ""

    def to_dict(self) -> dict[str, Any]:
        data: dict[str, Any] = {
            "updated": self.updated,
            "applied_in_place": self.applied_in_place,
            "method": self.method,
            "version": self.version,
            "tag": self.tag,
            "path": str(self.path),
            "archive": str(self.archive) if self.archive is not None else None,
        }
        if self.uv_command:
            data["uv_command"] = self.uv_command
        if self.fallback_reason:
            data["fallback_reason"] = self.fallback_reason
        if self.instructions:
            data["instructions"] = self.instructions
        return data


def apply_update(
    check: UpdateCheck | None = None,
    *,
    apply: bool = True,
    dest_dir: Path | str | None = None,
    install_dir: Path | str | None = None,
    current_version: str = __version__,
    include_pre: bool = False,
    progress: Callable[[int, int], None] | None = None,
    opener: urllib.request.OpenerDirector | None = None,
    allow_http_loopback: bool = False,
    repo: str = DEFAULT_GITHUB_REPO,
    prefix: Path | str | None = None,
    pkg_file: Path | str | None = None,
    which_fn: Callable[[str], str | None] = shutil.which,
    run_cmd: Callable[..., subprocess.CompletedProcess[str]] | None = None,
) -> UpdateResult:
    """Apply or stage a PC Companion update (via `uv tool install --force` or verified release ZIP)."""
    if check is None:
        check = check_latest(
            current=current_version,
            include_pre=include_pre,
            opener=opener,
            allow_http_loopback=allow_http_loopback,
        )

    default_root = Path(pkg_file or __file__).resolve().parent.parent
    if not check.available:
        return UpdateResult(
            updated=False,
            applied_in_place=False,
            method="none",
            version=check.current,
            tag=check.latest_tag,
            path=Path(install_dir).expanduser().resolve() if install_dir else default_root,
            instructions=f"Already up to date (v{check.current}).",
        )

    uv_env = install_dir is None and is_uv_tool_env(
        prefix=prefix, pkg_file=pkg_file, which_fn=which_fn
    )
    uv_cmd_str: str | None = None
    uv_fallback_reason: str | None = None

    if uv_env:
        uv_cmd_str = format_uv_upgrade_command(check.latest_tag, repo=repo)
        if apply:
            uv_bin = which_fn("uv")
            if uv_bin:
                safe_tag = _validate_release_tag_for_git(check.latest_tag)
                cmd_args = [
                    uv_bin,
                    "tool",
                    "install",
                    "--force",
                    f"git+https://github.com/{repo}.git@{safe_tag}",
                ]
                runner = run_cmd or subprocess.run
                try:
                    proc = runner(
                        cmd_args,
                        capture_output=True,
                        text=True,
                        check=False,
                        timeout=120,
                    )
                    if proc.returncode == 0:
                        target_pfx = Path(prefix if prefix is not None else sys.prefix)
                        msg = (
                            f"Updated MiniFile Companion in place to v{check.latest_version} via uv.\n"
                            f"Ran: {uv_cmd_str}\n"
                            f"Verify with: minifile --version"
                        )
                        return UpdateResult(
                            updated=True,
                            applied_in_place=True,
                            method="uv",
                            version=check.latest_version,
                            tag=check.latest_tag,
                            path=target_pfx,
                            archive=None,
                            uv_command=uv_cmd_str,
                            instructions=msg,
                        )
                    err_out = (proc.stderr or proc.stdout or "").strip()
                    last_line = err_out.splitlines()[-1] if err_out else f"exit {proc.returncode}"
                    uv_fallback_reason = (
                        f"uv tool install exited with {proc.returncode}: {last_line}"
                    )
                except (OSError, subprocess.SubprocessError) as exc:
                    uv_fallback_reason = f"uv tool install failed: {exc}"
            else:
                uv_fallback_reason = "`uv` executable not found on PATH"

    if not check.asset_url or not check.asset_name:
        if uv_cmd_str:
            detail = f" ({uv_fallback_reason})" if uv_fallback_reason else ""
            raise UpdateError(
                f"Release {check.latest_tag} has no downloadable companion .zip asset{detail}.\n"
                f"Upgrade manually with: {uv_cmd_str}"
            )
        raise UpdateError(
            f"Release {check.latest_tag} has no downloadable companion .zip asset"
        )

    from .config import default_cache_dir

    cache_dest = (
        Path(dest_dir).expanduser()
        if dest_dir is not None
        else (default_cache_dir() / "updates")
    )
    expected_sha: str | None = None
    if check.checksums_url:
        expected_sha = fetch_sha256_for_asset(
            check.checksums_url,
            check.asset_name,
            opener=opener,
            allow_http_loopback=allow_http_loopback,
        )

    zip_path = download_asset(
        check.asset_url,
        dest_dir=cache_dest,
        expected_size=check.asset_size,
        sha256=expected_sha,
        progress=progress,
        opener=opener,
        allow_http_loopback=allow_http_loopback,
        filename=check.asset_name,
    )
    verified_ver = verify_companion_zip(zip_path)
    if verified_ver != check.latest_version:
        raise UpdateError(
            f"Archive version ({verified_ver}) does not match release tag ({check.latest_tag})"
        )

    # Never overwrite site-packages directly; stage into cache_dest for uv / installed packages
    if install_dir is not None:
        effective_install_dir = Path(install_dir).expanduser().resolve()
        effective_apply = apply
    elif uv_env or not is_source_tree(pkg_file):
        effective_install_dir = (cache_dest / "companion").resolve()
        effective_apply = False
    else:
        effective_install_dir = default_root
        effective_apply = apply

    result_path = stage_and_apply(
        zip_path,
        install_dir=effective_install_dir,
        apply=effective_apply,
        current_version=current_version,
    )

    if uv_env and uv_cmd_str:
        quoted_stage = shlex.quote(str(result_path))
        if uv_fallback_reason:
            instructions = (
                f"Automatic uv upgrade did not complete ({uv_fallback_reason}).\n"
                f"Staged verified MiniFile Companion v{verified_ver} at: {result_path}\n"
                f"Upgrade your uv tool installation from GitHub:\n"
                f"  {uv_cmd_str}\n"
                f"Or install offline from the verified staged directory:\n"
                f"  uv tool install --force {quoted_stage}"
            )
        else:
            instructions = (
                f"Staged verified MiniFile Companion v{verified_ver} at: {result_path}\n"
                f"Apply the update in place with:\n"
                f"  minifile update --apply\n"
                f"Or upgrade directly via uv:\n"
                f"  {uv_cmd_str}\n"
                f"Or install from the verified staged directory:\n"
                f"  uv tool install --force {quoted_stage}"
            )
    elif effective_apply:
        instructions = (
            f"Updated MiniFile Companion in place to v{verified_ver} at {result_path}"
        )
    else:
        instructions = (
            f"Staged MiniFile Companion v{verified_ver} at: {result_path}\n"
            f"Run it with: cd {shlex.quote(str(result_path))} && python3 -m minifile_companion --help"
        )

    return UpdateResult(
        updated=True,
        applied_in_place=effective_apply,
        method="zip",
        version=verified_ver,
        tag=check.latest_tag,
        path=result_path,
        archive=zip_path,
        uv_command=uv_cmd_str,
        fallback_reason=uv_fallback_reason,
        instructions=instructions,
    )


def record_check_in_config(store: ConfigStore, result: UpdateCheck, forced: bool = False) -> None:
    """Persist latest update check metadata into config.json."""
    now = time.time()
    cfg = store.load()
    cfg.update_last_check = now
    if forced:
        cfg.update_last_force_check = now
    cfg.update_latest_tag = result.latest_tag
    cfg.update_name = result.name
    cfg.update_notes = result.notes
    cfg.update_html_url = result.html_url
    cfg.update_asset_url = result.asset_url or ""
    cfg.update_asset_size = result.asset_size
    cfg.update_published_at = result.published_at or ""
    store.save(cfg)


def check_with_cache(
    store: ConfigStore,
    force: bool = False,
    current: str = __version__,
    include_pre: bool = False,
    opener: urllib.request.OpenerDirector | None = None,
    allow_http_loopback: bool = False,
    api_base: str | None = None,
    now_fn: Callable[[], float] = time.time,
) -> dict[str, Any]:
    """Perform a cached or rate-limited (1 per 60s on force=True) update check for Web UI."""
    now = now_fn()
    cfg = store.load()

    def _cached_dict(throttled: bool) -> dict[str, Any]:
        avail = is_newer(current, cfg.update_latest_tag)
        latest_tag = cfg.update_latest_tag or f"v{current}"
        uv_env = is_uv_tool_env()
        uv_cmd: str | None = None
        if uv_env and cfg.update_latest_tag:
            try:
                uv_cmd = format_uv_upgrade_command(cfg.update_latest_tag)
            except UpdateError:
                uv_cmd = None
        return {
            "current": current,
            "latest": latest_tag,
            "name": cfg.update_name or latest_tag,
            "available": avail,
            "notes": cfg.update_notes,
            "html_url": cfg.update_html_url or DEFAULT_RELEASE_HTML_URL,
            "asset_url": cfg.update_asset_url or None,
            "asset_size": cfg.update_asset_size,
            "published_at": cfg.update_published_at or None,
            "checked_at": cfg.update_last_check,
            "throttled": throttled,
            "uv_install": uv_env,
            "uv_command": uv_cmd,
        }

    if force:
        if (
            cfg.update_last_force_check > 0
            and (now - cfg.update_last_force_check) < FORCE_CHECK_COOLDOWN_SECONDS
        ):
            return _cached_dict(throttled=True)
    else:
        if (
            cfg.update_last_check > 0
            and (now - cfg.update_last_check) < AUTO_CHECK_TTL_SECONDS
        ):
            return _cached_dict(throttled=False)

    res = check_latest(
        current=current,
        include_pre=include_pre,
        opener=opener,
        allow_http_loopback=allow_http_loopback,
        api_base=api_base,
    )
    cfg = store.load()
    cfg.update_last_check = now
    if force:
        cfg.update_last_force_check = now
    cfg.update_latest_tag = res.latest_tag
    cfg.update_name = res.name
    cfg.update_notes = res.notes
    cfg.update_html_url = res.html_url
    cfg.update_asset_url = res.asset_url or ""
    cfg.update_asset_size = res.asset_size
    cfg.update_published_at = res.published_at or ""
    store.save(cfg)
    return _cached_dict(throttled=False)


def maybe_emit_cached_update_hint(
    store: ConfigStore,
    current: str = __version__,
    stream: Any = None,
    now_fn: Callable[[], float] = time.time,
) -> bool:
    """Print a non-blocking one-line hint on stderr at most once per 24 h if cache has an update."""
    cfg = store.load()
    if not cfg.update_hint or not cfg.update_latest_tag:
        return False
    if not is_newer(current, cfg.update_latest_tag):
        return False
    now = now_fn()
    if cfg.update_last_hint > 0 and (now - cfg.update_last_hint) < HINT_COOLDOWN_SECONDS:
        return False

    out = stream if stream is not None else sys.stderr
    cmd_hint = "minifile update --apply" if is_uv_tool_env() else "minifile update"
    print(
        f"(new version {cfg.update_latest_tag} available — run `{cmd_hint}`)",
        file=out,
    )
    cfg.update_last_hint = now
    try:
        store.save(cfg)
    except OSError:
        pass
    return True
