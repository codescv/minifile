"""Core UTF-8 FTP client, recursive file transfers, folder sync, and .nomedia management."""

from __future__ import annotations

import calendar
import fnmatch
import ftplib
import inspect
import io
import os
import posixpath
import re
import shutil
import tempfile
import threading
import time
from collections.abc import Callable, Iterator, Sequence
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

from .scanner import Discovered, discover
from .textutil import decode_text, encode_text

CHUNK_SIZE = 65536
MTIME_TOLERANCE_SECONDS = 2.0

_MONTHS: dict[str, int] = {
    "jan": 1,
    "feb": 2,
    "mar": 3,
    "apr": 4,
    "may": 5,
    "jun": 6,
    "jul": 7,
    "aug": 8,
    "sep": 9,
    "oct": 10,
    "nov": 11,
    "dec": 12,
}

# Matches standard UNIX `ls -l` lines emitted by MiniFile FTP LIST:
# e.g. "drwxr-xr-x 1 owner group     4096 Oct 30 20:14 Download"
# or   "-rw-r--r-- 1 owner group      128 May 02  2024 notes.txt"
_LIST_LINE_RE = re.compile(
    r"^(?P<perm>[d\-lcbps][rwxstST\-]{9})\s+"
    r"\d+\s+\S+\s+\S+\s+"
    r"(?P<size>\d+)\s+"
    r"(?P<mon>[A-Za-z]{3})\s+"
    r"(?P<day>\d{1,2})\s+"
    r"(?P<time_or_year>\d{1,2}:\d{2}|\d{4}) "
    r"(?P<name>.+)$"
)


class MiniFileError(Exception):
    """Base error for MiniFile PC Companion operations."""


class ConnectionAuthError(MiniFileError):
    """Connection failure, network timeout, or FTP 530 authentication error."""


class ReadOnlyServerError(MiniFileError):
    """Server rejected a mutating operation because read-only mode is active."""


class PathSecurityError(MiniFileError):
    """Unsafe remote or local path rejected (e.g., NUL byte or traversal escape)."""


class RemoteNotFoundError(MiniFileError):
    """Requested remote file or directory does not exist."""


class TransferCancelledError(MiniFileError):
    """Transfer cancelled by caller or progress callback."""


class PartialTransferError(MiniFileError):
    """Some items succeeded while one or more items failed."""

    def __init__(
        self,
        message: str,
        succeeded: int = 0,
        failed: list[tuple[str, str]] | None = None,
    ) -> None:
        super().__init__(message)
        self.succeeded = succeeded
        self.failed: list[tuple[str, str]] = list(failed or [])


@dataclass
class ServerInfo:
    """Metadata negotiated when connecting to an FTP server."""

    host: str
    port: int
    banner: str
    features: set[str] = field(default_factory=set)
    utf8_enabled: bool = True

    def to_dict(self) -> dict[str, Any]:
        return {
            "host": self.host,
            "port": self.port,
            "banner": self.banner,
            "features": sorted(self.features),
            "utf8_enabled": self.utf8_enabled,
        }


@dataclass
class RemoteEntry:
    """Single remote file or directory entry."""

    name: str
    path: str
    is_dir: bool
    size: int
    mtime: float | None = None
    perm: str = ""
    has_nomedia: bool = False

    @property
    def is_hidden(self) -> bool:
        return self.name.startswith(".")

    def to_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "path": self.path,
            "is_dir": self.is_dir,
            "size": self.size,
            "mtime": self.mtime,
            "perm": self.perm,
            "has_nomedia": self.has_nomedia,
            "is_hidden": self.is_hidden,
        }


@dataclass
class SyncAction:
    """Single planned or executed synchronization step."""

    op: str
    rel_path: str
    local_path: str
    remote_path: str
    size: int = 0
    reason: str = ""

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class SyncPlan:
    """Summary and action list for a folder sync run."""

    direction: str
    dry_run: bool
    actions: list[SyncAction] = field(default_factory=list)
    uploaded: int = 0
    downloaded: int = 0
    deleted_remote: int = 0
    deleted_local: int = 0
    skipped: int = 0
    errors: list[tuple[str, str]] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "direction": self.direction,
            "dry_run": self.dry_run,
            "actions": [a.to_dict() for a in self.actions],
            "uploaded": self.uploaded,
            "downloaded": self.downloaded,
            "deleted_remote": self.deleted_remote,
            "deleted_local": self.deleted_local,
            "skipped": self.skipped,
            "errors": [{"path": p, "error": err} for p, err in self.errors],
        }


def normalize_remote_path(path: str | None, base: str = "/") -> str:
    """Normalize a remote FTP path to a canonical '/'-rooted POSIX path without '..' segments."""
    if path is None:
        raw = base
    else:
        raw = str(path)
    if "\x00" in raw or "\r" in raw or "\n" in raw:
        raise PathSecurityError("Remote path contains control or NUL characters")

    raw = raw.replace("\\", "/").strip()
    if not raw:
        raw = base
    if not raw.startswith("/"):
        base_clean = base.replace("\\", "/")
        if not base_clean.startswith("/"):
            base_clean = "/" + base_clean
        raw = posixpath.join(base_clean, raw)

    segments: list[str] = []
    for seg in raw.split("/"):
        if not seg or seg == ".":
            continue
        if seg == "..":
            if segments:
                segments.pop()
            continue
        segments.append(seg)

    return "/" + "/".join(segments)


def validate_entry_name(name: str) -> str:
    """Validate a single path component received from a remote server or user input."""
    if not name or name in (".", ".."):
        raise PathSecurityError(f"Unsafe entry name: {name!r}")
    if "/" in name or "\\" in name or "\x00" in name:
        raise PathSecurityError(f"Unsafe separator or NUL in entry name: {name!r}")
    return name


def safe_local_child(root_dir: Path, rel_posix_path: str) -> Path:
    """Resolve rel_posix_path inside root_dir, refusing traversal or symlink escapes."""
    if not rel_posix_path or "\x00" in rel_posix_path:
        raise PathSecurityError("Empty or NUL byte in relative path")
    if rel_posix_path.startswith(("/", "\\")) or re.match(r"^[A-Za-z]:", rel_posix_path):
        raise PathSecurityError(f"Absolute path refused: {rel_posix_path!r}")
    parts = [p for p in rel_posix_path.replace("\\", "/").split("/") if p and p != "."]
    if not parts:
        raise PathSecurityError("Empty relative path")
    for part in parts:
        validate_entry_name(part)

    root_real = os.path.realpath(root_dir)
    candidate = root_dir.joinpath(*parts)
    cand_real = os.path.realpath(candidate)
    if cand_real != root_real and not cand_real.startswith(root_real + os.sep):
        raise PathSecurityError(
            f"Refusing local path escape outside {root_dir}: {rel_posix_path}"
        )
    return candidate


def parse_257_path(reply: str) -> str:
    """Extract path from an RFC 959 257 reply where embedded quotes are doubled."""
    if not reply.startswith("257"):
        raise MiniFileError(f"Expected 257 reply, got: {reply}")
    first_quote = reply.find('"')
    if first_quote == -1:
        parts = reply.split(None, 2)
        return parts[1] if len(parts) > 1 else "/"
    out: list[str] = []
    i = first_quote + 1
    n = len(reply)
    while i < n:
        ch = reply[i]
        if ch == '"':
            if i + 1 < n and reply[i + 1] == '"':
                out.append('"')
                i += 2
                continue
            break
        out.append(ch)
        i += 1
    return "".join(out) or "/"


def parse_ftp_timestamp(ts: str | None) -> float | None:
    """Parse RFC 3659 YYYYMMDDHHMMSS[.sss] UTC timestamp to epoch seconds."""
    if not ts:
        return None
    ts = ts.strip()
    if len(ts) < 14:
        return None
    main = ts[:14]
    if not main.isdigit():
        return None
    frac = 0.0
    if len(ts) > 14 and ts[14] == ".":
        frac_digits = ts[15:]
        if frac_digits.isdigit():
            frac = float("0." + frac_digits)
    try:
        tm = (
            int(main[0:4]),
            int(main[4:6]),
            int(main[6:8]),
            int(main[8:10]),
            int(main[10:12]),
            int(main[12:14]),
            0,
            0,
            0,
        )
        epoch = float(calendar.timegm(tm)) + frac
        if epoch <= 0:
            return None
        return epoch
    except (ValueError, OverflowError):
        return None


def parse_mlsx_line(line: str, parent_dir: str) -> RemoteEntry | None:
    """Parse a single MLSD or MLST fact line into a RemoteEntry."""
    stripped = line.rstrip("\r\n")
    if not stripped:
        return None
    # MLST lines inside 250-... have a single leading space per RFC 3659 §7.2
    if stripped.startswith(" "):
        stripped = stripped[1:]
    semi_space = stripped.find(" ")
    if semi_space == -1:
        return None
    facts_part = stripped[:semi_space]
    name = stripped[semi_space + 1 :]
    if not name or name in (".", ".."):
        return None

    facts: dict[str, str] = {}
    for token in facts_part.split(";"):
        if not token or "=" not in token:
            continue
        k, v = token.split("=", 1)
        facts[k.strip().lower()] = v.strip()

    entry_type = facts.get("type", "file").lower()
    if entry_type in ("cdir", "pdir"):
        return None
    is_dir = entry_type == "dir"
    try:
        size = int(facts.get("size", "0"))
    except ValueError:
        size = 0
    mtime = parse_ftp_timestamp(facts.get("modify"))
    perm = facts.get("perm", "")

    if name.startswith("/"):
        entry_path = normalize_remote_path(name)
        entry_name = posixpath.basename(entry_path) or "/"
    else:
        validate_entry_name(name)
        entry_name = name
        entry_path = normalize_remote_path(posixpath.join(parent_dir, entry_name))

    return RemoteEntry(
        name=entry_name,
        path=entry_path,
        is_dir=is_dir,
        size=0 if is_dir and size < 0 else size,
        mtime=mtime,
        perm=perm,
    )


def parse_list_line(line: str, parent_dir: str, now_ts: float | None = None) -> RemoteEntry | None:
    """Parse a UNIX `ls -l` style FTP LIST line."""
    m = _LIST_LINE_RE.match(line.rstrip("\r\n"))
    if not m:
        return None
    perm = m.group("perm")
    size = int(m.group("size"))
    mon_str = m.group("mon").lower()
    day = int(m.group("day"))
    time_or_year = m.group("time_or_year")
    name = m.group("name")

    if perm.startswith("l") and " -> " in name:
        name = name.split(" -> ", 1)[0]
    if name in (".", ".."):
        return None
    validate_entry_name(name)

    is_dir = perm.startswith("d")
    mtime: float | None = None
    month = _MONTHS.get(mon_str)
    if month is not None:
        ref = time.gmtime(now_ts if now_ts is not None else time.time())
        try:
            if ":" in time_or_year:
                hh, mm = (int(x) for x in time_or_year.split(":", 1))
                year = ref.tm_year
                ts_candidate = float(calendar.timegm((year, month, day, hh, mm, 0, 0, 0, 0)))
                if ts_candidate > (now_ts if now_ts is not None else time.time()) + 86400 * 2:
                    ts_candidate = float(
                        calendar.timegm((year - 1, month, day, hh, mm, 0, 0, 0, 0))
                    )
                mtime = ts_candidate
            else:
                year = int(time_or_year)
                mtime = float(calendar.timegm((year, month, day, 0, 0, 0, 0, 0, 0)))
        except ValueError:
            mtime = None

    entry_path = normalize_remote_path(posixpath.join(parent_dir, name))
    return RemoteEntry(
        name=name,
        path=entry_path,
        is_dir=is_dir,
        size=size,
        mtime=mtime,
        perm=perm,
    )


def _invoke_progress(
    callback: Callable[..., Any] | None,
    done_bytes: int,
    total_bytes: int,
    current_path: str,
) -> None:
    """Invoke a caller-provided progress callback regardless of whether it takes 1, 2, or 3 args."""
    if callback is None:
        return
    try:
        sig = inspect.signature(callback)
        positional = [
            p
            for p in sig.parameters.values()
            if p.kind in (inspect.Parameter.POSITIONAL_ONLY, inspect.Parameter.POSITIONAL_OR_KEYWORD)
        ]
        has_varargs = any(
            p.kind == inspect.Parameter.VAR_POSITIONAL for p in sig.parameters.values()
        )
        if has_varargs:
            res = callback(done_bytes, total_bytes)
        elif len(positional) >= 3:
            res = callback(done_bytes, total_bytes, current_path)
        elif len(positional) == 2:
            res = callback(done_bytes, total_bytes)
        elif len(positional) == 1:
            res = callback((done_bytes, total_bytes, current_path))
        else:
            res = callback()
    except (ValueError, TypeError):
        res = callback(done_bytes, total_bytes)
    if res is False:
        raise TransferCancelledError("Transfer cancelled")


class _Utf8FTP(ftplib.FTP):
    """ftplib.FTP subclass defaulting to UTF-8 and falling back to EPSV on RFC 2428 522 replies."""

    encoding = "utf-8"

    def makepasv(self) -> tuple[str, int]:
        if self.af == ftplib.socket.AF_INET:
            try:
                return super().makepasv()
            except ftplib.error_perm as exc:
                if str(exc).startswith("522"):
                    host, port = ftplib.parse229(self.sendcmd("EPSV"), self.sock.getpeername())
                    return host, port
                raise
        return super().makepasv()


class MiniFileClient:
    """Full UTF-8 FTP client tailored to the MiniFile Android FTP Server."""

    def __init__(
        self,
        host: str,
        port: int = 2121,
        user: str = "anonymous",
        password: str = "minifile@",
        timeout: float = 10.0,
        encoding: str = "utf-8",
    ) -> None:
        self.host = host
        self.port = int(port)
        self.user = user or "anonymous"
        self.password = password if password is not None else "minifile@"
        self.timeout = float(timeout)
        self.encoding = encoding
        self._ftp: _Utf8FTP | None = None
        self._lock = threading.RLock()
        self.server_info: ServerInfo | None = None
        self._mlsd_supported = True
        self._mlst_supported = True
        self._upload_rest_supported = True

    def __enter__(self) -> MiniFileClient:
        if self._ftp is None:
            self.connect()
        return self

    def __exit__(self, exc_type: Any, exc: Any, tb: Any) -> None:
        self.close()

    @property
    def connected(self) -> bool:
        return self._ftp is not None and self._ftp.sock is not None

    def clone_unconnected(self) -> MiniFileClient:
        """Create a fresh client instance sharing connection credentials (for worker threads)."""
        return MiniFileClient(
            host=self.host,
            port=self.port,
            user=self.user,
            password=self.password,
            timeout=self.timeout,
            encoding=self.encoding,
        )

    def _ensure_ftp(self) -> _Utf8FTP:
        if self._ftp is None or self._ftp.sock is None:
            raise ConnectionAuthError("Not connected to FTP server")
        return self._ftp

    def _translate_ftp_error(self, exc: Exception, context: str = "") -> MiniFileError:
        msg = str(exc).strip()
        lower = msg.lower()
        if "read-only" in lower or "read only" in lower or "readonly" in lower:
            return ReadOnlyServerError("Phone server is read-only")
        if msg.startswith("530"):
            return ConnectionAuthError(f"Authentication failed: {msg}")
        if msg.startswith("421"):
            self._drop_dead_socket()
            return ConnectionAuthError(f"Connection closed by server: {msg}")
        if msg.startswith("550"):
            prefix = f"{context}: " if context else ""
            if "not found" in lower or "no such" in lower or "does not exist" in lower:
                return RemoteNotFoundError(f"{prefix}{msg}")
            return MiniFileError(f"{prefix}{msg}")
        if isinstance(exc, (OSError, EOFError)):
            self._drop_dead_socket()
            return ConnectionAuthError(f"Network error: {msg}")
        return MiniFileError(f"{context}: {msg}" if context else msg)

    def _drop_dead_socket(self) -> None:
        if self._ftp is not None:
            try:
                self._ftp.close()
            except Exception:
                pass
            self._ftp = None

    def connect(self) -> ServerInfo:
        """Connect, authenticate, negotiate FEAT + OPTS UTF8 ON, and enable passive mode."""
        with self._lock:
            self.close()
            ftp = _Utf8FTP()
            ftp.encoding = self.encoding
            try:
                banner = ftp.connect(self.host, self.port, timeout=self.timeout)
                ftp.login(self.user, self.password)
            except ftplib.all_errors as exc:
                try:
                    ftp.close()
                except Exception:
                    pass
                raise ConnectionAuthError(
                    f"Failed to connect/authenticate to {self.host}:{self.port}: {exc}"
                ) from exc

            features: set[str] = set()
            try:
                feat_resp = ftp.sendcmd("FEAT")
                for raw_line in feat_resp.splitlines()[1:]:
                    line = raw_line.strip()
                    if not line or line.upper().startswith(("211 ", "211-FEATURES")):
                        continue
                    if line.startswith("211-"):
                        line = line[4:].strip()
                    if not line or line.upper() == "END":
                        continue
                    upper = line.upper()
                    features.add(upper)
                    head = upper.split(None, 1)[0]
                    features.add(head)
            except ftplib.all_errors:
                pass

            utf8_enabled = "UTF8" in features
            try:
                opts_resp = ftp.sendcmd("OPTS UTF8 ON")
                if opts_resp.startswith(("200", "202")):
                    utf8_enabled = True
            except ftplib.all_errors:
                pass

            ftp.set_pasv(True)
            try:
                ftp.sendcmd("TYPE I")
            except ftplib.all_errors:
                pass

            self._ftp = ftp
            self._mlsd_supported = ("MLSD" in features) or (not features)
            self._mlst_supported = ("MLST" in features) or (not features)
            self._upload_rest_supported = True
            self.server_info = ServerInfo(
                host=self.host,
                port=self.port,
                banner=banner.strip(),
                features=features,
                utf8_enabled=utf8_enabled,
            )
            return self.server_info

    def close(self) -> None:
        with self._lock:
            ftp = self._ftp
            self._ftp = None
            if ftp is not None:
                try:
                    ftp.quit()
                except Exception:
                    try:
                        ftp.close()
                    except Exception:
                        pass

    def noop(self) -> bool:
        """Send NOOP to verify connection health."""
        with self._lock:
            ftp = self._ensure_ftp()
            try:
                ftp.voidcmd("NOOP")
                return True
            except ftplib.all_errors:
                self._drop_dead_socket()
                return False

    def pwd(self) -> str:
        with self._lock:
            ftp = self._ensure_ftp()
            try:
                resp = ftp.sendcmd("PWD")
                return normalize_remote_path(parse_257_path(resp))
            except ftplib.all_errors as exc:
                raise self._translate_ftp_error(exc, "PWD") from exc

    def _retr_lines(self, cmd: str) -> list[str]:
        ftp = self._ensure_ftp()
        lines: list[str] = []
        ftp.retrlines(cmd, lines.append)
        return lines

    def list(self, path: str = "/", hidden: bool = True) -> list[RemoteEntry]:
        """List entries in a remote directory using MLSD with fallback to LIST."""
        norm = normalize_remote_path(path)
        with self._lock:
            entries: list[RemoteEntry] = []
            used_mlsd = False
            if self._mlsd_supported:
                try:
                    raw_lines = self._retr_lines(f"MLSD {norm}")
                    for line in raw_lines:
                        parsed = parse_mlsx_line(line, norm)
                        if parsed is not None:
                            entries.append(parsed)
                    used_mlsd = True
                except ftplib.error_perm as exc:
                    code = str(exc)[:3]
                    if code in ("500", "501", "502", "504"):
                        self._mlsd_supported = False
                    else:
                        raise self._translate_ftp_error(exc, f"LIST {norm}") from exc
                except ftplib.all_errors as exc:
                    raise self._translate_ftp_error(exc, f"LIST {norm}") from exc

            if not used_mlsd:
                try:
                    raw_lines = self._retr_lines(f"LIST {norm}")
                    now_ts = time.time()
                    for line in raw_lines:
                        parsed = parse_list_line(line, norm, now_ts=now_ts)
                        if parsed is not None:
                            entries.append(parsed)
                except ftplib.all_errors as exc:
                    raise self._translate_ftp_error(exc, f"LIST {norm}") from exc

            if not hidden:
                entries = [e for e in entries if not e.name.startswith(".")]

            entries.sort(key=lambda e: (not e.is_dir, e.name.lower(), e.name))
            return entries

    def stat(self, path: str) -> RemoteEntry | None:
        """Query metadata for a single remote path via MLST or SIZE/MDTM/CWD fallback."""
        norm = normalize_remote_path(path)
        if norm == "/":
            return RemoteEntry(name="/", path="/", is_dir=True, size=0, mtime=None, perm="el")

        parent = posixpath.dirname(norm) or "/"
        name = posixpath.basename(norm)
        with self._lock:
            ftp = self._ensure_ftp()
            if self._mlst_supported:
                try:
                    resp = ftp.sendcmd(f"MLST {norm}")
                    for raw_line in resp.splitlines()[1:]:
                        if raw_line.strip().startswith("250"):
                            continue
                        entry = parse_mlsx_line(raw_line, parent)
                        if entry is not None:
                            entry.name = name
                            entry.path = norm
                            return entry
                except ftplib.error_perm as exc:
                    code = str(exc)[:3]
                    if code == "550":
                        return None
                    if code in ("500", "501", "502", "504"):
                        self._mlst_supported = False
                    else:
                        raise self._translate_ftp_error(exc, f"STAT {norm}") from exc
                except ftplib.all_errors as exc:
                    raise self._translate_ftp_error(exc, f"STAT {norm}") from exc

            # Fallback using SIZE + MDTM + CWD
            try:
                ftp.voidcmd("TYPE I")
                size_val = ftp.size(norm)
                if size_val is not None:
                    mtime_val = self._mdtm(norm)
                    return RemoteEntry(
                        name=name,
                        path=norm,
                        is_dir=False,
                        size=int(size_val),
                        mtime=mtime_val,
                    )
            except ftplib.error_perm as exc:
                if not str(exc).startswith("550"):
                    raise self._translate_ftp_error(exc, f"SIZE {norm}") from exc
            except ftplib.all_errors as exc:
                raise self._translate_ftp_error(exc, f"SIZE {norm}") from exc

            # SIZE returned 550 or None: test whether path is a directory via CWD
            orig_cwd = "/"
            try:
                orig_cwd = self.pwd()
            except MiniFileError:
                pass
            try:
                ftp.cwd(norm)
                try:
                    ftp.cwd(orig_cwd)
                except ftplib.all_errors:
                    pass
                return RemoteEntry(
                    name=name,
                    path=norm,
                    is_dir=True,
                    size=0,
                    mtime=self._mdtm(norm),
                )
            except ftplib.error_perm as exc:
                if str(exc).startswith("550"):
                    return None
                raise self._translate_ftp_error(exc, f"STAT {norm}") from exc
            except ftplib.all_errors as exc:
                raise self._translate_ftp_error(exc, f"STAT {norm}") from exc

    def _mdtm(self, norm_path: str) -> float | None:
        ftp = self._ensure_ftp()
        try:
            resp = ftp.sendcmd(f"MDTM {norm_path}")
            if resp.startswith("213"):
                parts = resp.split(None, 1)
                if len(parts) == 2:
                    return parse_ftp_timestamp(parts[1].strip())
        except ftplib.all_errors:
            return None
        return None

    def walk(
        self, path: str = "/", hidden: bool = True
    ) -> Iterator[tuple[str, list[RemoteEntry], list[RemoteEntry]]]:
        """Recursively walk a remote directory top-down (like os.walk)."""
        norm = normalize_remote_path(path)
        entries = self.list(norm, hidden=hidden)
        dirs = [e for e in entries if e.is_dir]
        files = [e for e in entries if not e.is_dir]
        yield norm, dirs, files
        for d in dirs:
            yield from self.walk(d.path, hidden=hidden)

    def _should_overwrite(
        self,
        overwrite: str,
        src: str,
        dst: str,
        confirm: Callable[[str, str], bool] | None,
    ) -> bool:
        policy = (overwrite or "overwrite").lower()
        if policy == "skip":
            return False
        if policy == "overwrite":
            return True
        if policy in ("error", "raise"):
            raise FileExistsError(f"Destination already exists: {dst}")
        if policy == "ask":
            if confirm is not None:
                return bool(confirm(src, dst))
            return True
        raise ValueError(f"Unknown overwrite policy: {overwrite!r}")

    def _download_single_file(
        self,
        remote_norm: str,
        local_file: Path,
        remote_entry: RemoteEntry,
        resume: bool,
        overwrite: str,
        confirm: Callable[[str, str], bool] | None,
        on_chunk: Callable[[int], None] | None,
    ) -> bool:
        exists_locally = local_file.exists()
        if exists_locally and local_file.is_dir():
            raise MiniFileError(f"Cannot overwrite local directory with file: {local_file}")

        offset = 0
        mode = "wb"
        if exists_locally:
            if resume:
                local_size = local_file.stat().st_size
                if 0 < local_size < remote_entry.size:
                    offset = local_size
                    mode = "ab"
                elif local_size == remote_entry.size and remote_entry.size > 0:
                    if on_chunk is not None:
                        on_chunk(local_size)
                    return False
            else:
                if not self._should_overwrite(overwrite, remote_norm, str(local_file), confirm):
                    return False

        local_file.parent.mkdir(parents=True, exist_ok=True)
        with self._lock:
            ftp = self._ensure_ftp()
            try:
                ftp.voidcmd("TYPE I")
                try:
                    conn, _ = ftp.ntransfercmd(
                        f"RETR {remote_norm}", rest=offset if offset > 0 else None
                    )
                except ftplib.error_perm as exc:
                    if offset > 0 and str(exc)[:3] in ("500", "502", "504"):
                        offset = 0
                        mode = "wb"
                        conn, _ = ftp.ntransfercmd(f"RETR {remote_norm}", rest=None)
                    else:
                        raise
                try:
                    if offset > 0 and on_chunk is not None:
                        on_chunk(offset)
                    with open(local_file, mode) as out_f:
                        while True:
                            buf = conn.recv(CHUNK_SIZE)
                            if not buf:
                                break
                            out_f.write(buf)
                            if on_chunk is not None:
                                on_chunk(len(buf))
                finally:
                    conn.close()
                ftp.voidresp()
            except TransferCancelledError:
                self._drop_dead_socket()
                raise
            except ftplib.all_errors as exc:
                raise self._translate_ftp_error(exc, f"RETR {remote_norm}") from exc

        if remote_entry.mtime is not None:
            try:
                os.utime(local_file, (remote_entry.mtime, remote_entry.mtime))
            except OSError:
                pass
        return True

    def download(
        self,
        remote: str,
        local: str | os.PathLike[str],
        resume: bool = False,
        progress: Callable[..., Any] | None = None,
        overwrite: str = "ask",
        confirm: Callable[[str, str], bool] | None = None,
    ) -> Path:
        """Download a remote file or directory recursively to local disk."""
        remote_norm = normalize_remote_path(remote)
        entry = self.stat(remote_norm)
        if entry is None:
            raise RemoteNotFoundError(f"Remote path not found: {remote_norm}")

        local_path = Path(local)
        if not entry.is_dir:
            if local_path.exists() and local_path.is_dir():
                target_file = safe_local_child(local_path, entry.name)
            else:
                target_file = local_path
            done = 0
            total = max(0, entry.size)
            _invoke_progress(progress, 0, total, remote_norm)

            def _chunk_cb(n: int) -> None:
                nonlocal done
                done += n
                _invoke_progress(progress, done, total, remote_norm)

            self._download_single_file(
                remote_norm,
                target_file,
                entry,
                resume=resume,
                overwrite=overwrite,
                confirm=confirm,
                on_chunk=_chunk_cb,
            )
            return target_file

        # Directory download
        local_path.mkdir(parents=True, exist_ok=True)
        plan_files: list[tuple[RemoteEntry, Path]] = []
        total_bytes = 0
        for dirpath, dirs, files in self.walk(remote_norm, hidden=True):
            rel_dir = posixpath.relpath(dirpath, remote_norm)
            if rel_dir in (".", ""):
                cur_local_dir = local_path
            else:
                cur_local_dir = safe_local_child(local_path, rel_dir)
                cur_local_dir.mkdir(parents=True, exist_ok=True)
            for d in dirs:
                sub_local = safe_local_child(cur_local_dir, d.name)
                sub_local.mkdir(parents=True, exist_ok=True)
            for f in files:
                dst_file = safe_local_child(cur_local_dir, f.name)
                plan_files.append((f, dst_file))
                total_bytes += max(0, f.size)

        done_bytes = 0
        _invoke_progress(progress, 0, total_bytes, remote_norm)
        for f_entry, dst_file in plan_files:

            def _dir_chunk_cb(n: int, cur: str = f_entry.path) -> None:
                nonlocal done_bytes
                done_bytes += n
                _invoke_progress(progress, done_bytes, total_bytes, cur)

            self._download_single_file(
                f_entry.path,
                dst_file,
                f_entry,
                resume=resume,
                overwrite=overwrite,
                confirm=confirm,
                on_chunk=_dir_chunk_cb,
            )

        return local_path

    def download_stream(
        self,
        remote: str,
        offset: int = 0,
        chunk_size: int = CHUNK_SIZE,
    ) -> Iterator[bytes]:
        """Stream bytes from a remote file as a generator (holds client lock while streaming)."""
        remote_norm = normalize_remote_path(remote)
        with self._lock:
            ftp = self._ensure_ftp()
            try:
                ftp.voidcmd("TYPE I")
                conn, _ = ftp.ntransfercmd(
                    f"RETR {remote_norm}", rest=offset if offset > 0 else None
                )
            except ftplib.all_errors as exc:
                raise self._translate_ftp_error(exc, f"RETR {remote_norm}") from exc

            completed_cleanly = False
            try:
                while True:
                    buf = conn.recv(chunk_size)
                    if not buf:
                        completed_cleanly = True
                        break
                    yield buf
            finally:
                try:
                    conn.close()
                except Exception:
                    pass
                if completed_cleanly:
                    try:
                        ftp.voidresp()
                    except ftplib.all_errors as exc:
                        raise self._translate_ftp_error(exc, f"RETR {remote_norm}") from exc
                else:
                    self._drop_dead_socket()

    def upload_stream(
        self,
        remote: str,
        chunks: Iterator[bytes],
        ensure_parents: bool = True,
        on_chunk: Callable[[int], None] | None = None,
    ) -> int:
        """Stream chunks directly into FTP STOR without buffering whole file in memory."""
        remote_norm = normalize_remote_path(remote)
        if remote_norm == "/":
            raise MiniFileError("Cannot upload file over root '/'")
        parent = posixpath.dirname(remote_norm) or "/"
        if ensure_parents and parent != "/":
            self.mkdir(parent, parents=True)

        total_written = 0
        with self._lock:
            ftp = self._ensure_ftp()
            try:
                ftp.voidcmd("TYPE I")
                conn = ftp.transfercmd(f"STOR {remote_norm}")
                try:
                    for chunk in chunks:
                        if not chunk:
                            continue
                        conn.sendall(chunk)
                        total_written += len(chunk)
                        if on_chunk is not None:
                            on_chunk(len(chunk))
                finally:
                    conn.close()
                ftp.voidresp()
            except TransferCancelledError:
                self._drop_dead_socket()
                raise
            except ftplib.all_errors as exc:
                raise self._translate_ftp_error(exc, f"STOR {remote_norm}") from exc
        return total_written

    def _upload_single_file(
        self,
        local_file: Path,
        remote_norm: str,
        resume: bool,
        overwrite: str,
        confirm: Callable[[str, str], bool] | None,
        on_chunk: Callable[[int], None] | None,
    ) -> bool:
        existing = self.stat(remote_norm)
        if existing is not None and existing.is_dir:
            raise MiniFileError(f"Remote target is a directory: {remote_norm}")

        local_size = local_file.stat().st_size
        offset = 0
        if existing is not None:
            if resume and self._upload_rest_supported and 0 < existing.size < local_size:
                offset = existing.size
            elif resume and existing.size == local_size and local_size > 0:
                if on_chunk is not None:
                    on_chunk(local_size)
                return False
            elif not resume:
                if not self._should_overwrite(overwrite, str(local_file), remote_norm, confirm):
                    return False

        parent = posixpath.dirname(remote_norm) or "/"
        if parent != "/":
            self.mkdir(parent, parents=True)

        with self._lock:
            ftp = self._ensure_ftp()
            try:
                ftp.voidcmd("TYPE I")
                conn = None
                if offset > 0:
                    try:
                        conn, _ = ftp.ntransfercmd(f"STOR {remote_norm}", rest=offset)
                    except ftplib.error_perm as exc:
                        if str(exc)[:3] in ("500", "502", "504"):
                            self._upload_rest_supported = False
                            offset = 0
                            conn = None
                        else:
                            raise
                if conn is None:
                    conn, _ = ftp.ntransfercmd(f"STOR {remote_norm}", rest=None)

                try:
                    with open(local_file, "rb") as in_f:
                        if offset > 0:
                            in_f.seek(offset)
                            if on_chunk is not None:
                                on_chunk(offset)
                        while True:
                            buf = in_f.read(CHUNK_SIZE)
                            if not buf:
                                break
                            conn.sendall(buf)
                            if on_chunk is not None:
                                on_chunk(len(buf))
                finally:
                    conn.close()
                ftp.voidresp()
            except TransferCancelledError:
                self._drop_dead_socket()
                raise
            except ftplib.all_errors as exc:
                raise self._translate_ftp_error(exc, f"STOR {remote_norm}") from exc

        return True

    def upload(
        self,
        local: str | os.PathLike[str],
        remote: str = "/",
        progress: Callable[..., Any] | None = None,
        overwrite: str = "ask",
        resume: bool = False,
        confirm: Callable[[str, str], bool] | None = None,
    ) -> str:
        """Upload a local file or directory recursively to the remote server."""
        local_path = Path(local)
        if not local_path.exists():
            raise FileNotFoundError(f"Local path does not exist: {local_path}")

        remote_raw = str(remote or "/")
        trailing_slash = remote_raw.endswith("/") and len(remote_raw) > 1
        remote_norm = normalize_remote_path(remote_raw)

        if local_path.is_file():
            existing = self.stat(remote_norm)
            if (existing is not None and existing.is_dir) or remote_norm == "/" or trailing_slash:
                target_remote = normalize_remote_path(
                    posixpath.join(remote_norm, local_path.name)
                )
            else:
                target_remote = remote_norm

            total = local_path.stat().st_size
            done = 0
            _invoke_progress(progress, 0, total, str(local_path))

            def _file_chunk_cb(n: int) -> None:
                nonlocal done
                done += n
                _invoke_progress(progress, done, total, str(local_path))

            self._upload_single_file(
                local_path,
                target_remote,
                resume=resume,
                overwrite=overwrite,
                confirm=confirm,
                on_chunk=_file_chunk_cb,
            )
            return target_remote

        # Directory upload
        self.mkdir(remote_norm, parents=True)
        plan_files: list[tuple[Path, str]] = []
        total_bytes = 0
        for root, dirs, files in os.walk(local_path):
            dirs.sort()
            files.sort()
            rel = os.path.relpath(root, local_path)
            if rel in (".", ""):
                cur_remote_dir = remote_norm
            else:
                rel_posix = "/".join(Path(rel).parts)
                cur_remote_dir = normalize_remote_path(posixpath.join(remote_norm, rel_posix))
                self.mkdir(cur_remote_dir, parents=True)

            for d in dirs:
                sub_remote = normalize_remote_path(posixpath.join(cur_remote_dir, d))
                self.mkdir(sub_remote, parents=True)

            for fname in files:
                fpath = Path(root) / fname
                if fpath.is_symlink() and not fpath.exists():
                    continue
                target_file_remote = normalize_remote_path(
                    posixpath.join(cur_remote_dir, fname)
                )
                plan_files.append((fpath, target_file_remote))
                total_bytes += fpath.stat().st_size

        done_bytes = 0
        _invoke_progress(progress, 0, total_bytes, str(local_path))
        for src_file, dst_remote in plan_files:

            def _dir_chunk_cb(n: int, cur: str = str(src_file)) -> None:
                nonlocal done_bytes
                done_bytes += n
                _invoke_progress(progress, done_bytes, total_bytes, cur)

            self._upload_single_file(
                src_file,
                dst_remote,
                resume=resume,
                overwrite=overwrite,
                confirm=confirm,
                on_chunk=_dir_chunk_cb,
            )

        return remote_norm

    def read_text(self, remote: str, encoding: str | None = None) -> tuple[str, str]:
        """Download a remote text file and return (decoded_text, encoding)."""
        remote_norm = normalize_remote_path(remote)
        buf = io.BytesIO()
        with self._lock:
            ftp = self._ensure_ftp()
            try:
                ftp.voidcmd("TYPE I")
                ftp.retrbinary(f"RETR {remote_norm}", buf.write, blocksize=CHUNK_SIZE)
            except ftplib.all_errors as exc:
                raise self._translate_ftp_error(exc, f"RETR {remote_norm}") from exc
        return decode_text(buf.getvalue(), encoding=encoding)

    def write_text(self, remote: str, text: str, encoding: str = "utf-8") -> int:
        """Encode text and upload it to a remote file, returning bytes written."""
        remote_norm = normalize_remote_path(remote)
        data = encode_text(text, encoding=encoding)
        parent = posixpath.dirname(remote_norm) or "/"
        if parent != "/":
            self.mkdir(parent, parents=True)
        with self._lock:
            ftp = self._ensure_ftp()
            try:
                ftp.voidcmd("TYPE I")
                ftp.storbinary(
                    f"STOR {remote_norm}", io.BytesIO(data), blocksize=CHUNK_SIZE
                )
            except ftplib.all_errors as exc:
                raise self._translate_ftp_error(exc, f"STOR {remote_norm}") from exc
        return len(data)

    def mkdir(self, path: str, parents: bool = False) -> str:
        """Create a remote directory (recursively if parents=True)."""
        norm = normalize_remote_path(path)
        if norm == "/":
            return "/"

        with self._lock:
            ftp = self._ensure_ftp()
            if not parents:
                try:
                    resp = ftp.sendcmd(f"MKD {norm}")
                    return normalize_remote_path(parse_257_path(resp))
                except ftplib.all_errors as exc:
                    raise self._translate_ftp_error(exc, f"MKD {norm}") from exc

            # Create intermediate segments from root
            segments = [s for s in norm.split("/") if s]
            cur = ""
            for seg in segments:
                cur = f"{cur}/{seg}"
                existing = self.stat(cur)
                if existing is not None:
                    if not existing.is_dir:
                        raise MiniFileError(
                            f"Cannot create directory '{cur}': file already exists"
                        )
                    continue
                try:
                    ftp.sendcmd(f"MKD {cur}")
                except ftplib.error_perm as exc:
                    err = self._translate_ftp_error(exc, f"MKD {cur}")
                    if isinstance(err, ReadOnlyServerError):
                        raise err from exc
                    recheck = self.stat(cur)
                    if recheck is None or not recheck.is_dir:
                        raise err from exc
                except ftplib.all_errors as exc:
                    raise self._translate_ftp_error(exc, f"MKD {cur}") from exc
            return norm

    def remove(self, path: str, recursive: bool = False) -> None:
        """Delete a remote file or directory (recursively if recursive=True)."""
        norm = normalize_remote_path(path)
        if norm == "/":
            raise PathSecurityError("Refusing to delete FTP root '/'")

        entry = self.stat(norm)
        if entry is None:
            raise RemoteNotFoundError(f"Remote path not found: {norm}")

        with self._lock:
            ftp = self._ensure_ftp()
            if not entry.is_dir:
                try:
                    ftp.delete(norm)
                    return
                except ftplib.all_errors as exc:
                    raise self._translate_ftp_error(exc, f"DELE {norm}") from exc

            if not recursive:
                try:
                    ftp.rmd(norm)
                    return
                except ftplib.all_errors as exc:
                    raise self._translate_ftp_error(exc, f"RMD {norm}") from exc

            for child in self.list(norm, hidden=True):
                self.remove(child.path, recursive=True)

            try:
                ftp.rmd(norm)
            except ftplib.all_errors as exc:
                raise self._translate_ftp_error(exc, f"RMD {norm}") from exc

    def rename(self, src: str, dst: str, copy_fallback: bool = False) -> str:
        """Rename/move a remote entry via RNFR/RNTO (with optional cross-backend copy+delete fallback)."""
        src_norm = normalize_remote_path(src)
        dst_norm = normalize_remote_path(dst)
        if src_norm == "/":
            raise PathSecurityError("Cannot rename root directory '/'")
        if src_norm == dst_norm:
            return dst_norm

        with self._lock:
            ftp = self._ensure_ftp()
            try:
                ftp.rename(src_norm, dst_norm)
                return dst_norm
            except ftplib.error_perm as exc:
                err = self._translate_ftp_error(exc, f"MV {src_norm} -> {dst_norm}")
                if isinstance(err, ReadOnlyServerError) or not copy_fallback:
                    raise err from exc
            except ftplib.all_errors as exc:
                raise self._translate_ftp_error(exc, f"MV {src_norm} -> {dst_norm}") from exc

        # Cross-backend fallback when explicitly requested (mv --copy-fallback)
        src_stat = self.stat(src_norm)
        if src_stat is None:
            raise RemoteNotFoundError(f"Source path not found: {src_norm}")
        with tempfile.TemporaryDirectory(prefix="minifile-mv-") as tmp_dir:
            tmp_target = Path(tmp_dir) / src_stat.name
            self.download(src_norm, tmp_target, overwrite="overwrite")
            self.upload(tmp_target, dst_norm, overwrite="overwrite")
            self.remove(src_norm, recursive=src_stat.is_dir)
        return dst_norm

    def nomedia(self, dir_path: str, state: bool | None = None) -> bool:
        """Query or set .nomedia file presence inside a remote directory."""
        dir_norm = normalize_remote_path(dir_path)
        target = normalize_remote_path(posixpath.join(dir_norm, ".nomedia"))

        with self._lock:
            ftp = self._ensure_ftp()
            exists = False
            try:
                ftp.voidcmd("TYPE I")
                sz = ftp.size(target)
                exists = sz is not None
            except ftplib.error_perm as exc:
                if not str(exc).startswith("550"):
                    err = self._translate_ftp_error(exc, f"SIZE {target}")
                    raise err from exc
                exists = False
            except ftplib.all_errors as exc:
                raise self._translate_ftp_error(exc, f"SIZE {target}") from exc

            if state is None:
                return exists

            if state:
                try:
                    ftp.voidcmd("TYPE I")
                    ftp.storbinary(f"STOR {target}", io.BytesIO(b""))
                except ftplib.all_errors as exc:
                    raise self._translate_ftp_error(exc, f"STOR {target}") from exc
                return True

            # state is False -> DELE <dir>/.nomedia
            try:
                ftp.delete(target)
            except ftplib.error_perm as exc:
                err = self._translate_ftp_error(exc, f"DELE {target}")
                if isinstance(err, ReadOnlyServerError):
                    raise err from exc
                if not str(exc).startswith("550"):
                    raise err from exc
            except ftplib.all_errors as exc:
                raise self._translate_ftp_error(exc, f"DELE {target}") from exc
            return False

    @staticmethod
    def _is_excluded(rel_posix: str, name: str, patterns: Sequence[str]) -> bool:
        for pat in patterns:
            if fnmatch.fnmatch(name, pat) or fnmatch.fnmatch(rel_posix, pat):
                return True
        return False

    def _scan_local_tree(
        self, local_root: Path, exclude: Sequence[str]
    ) -> tuple[dict[str, Path], set[str]]:
        files_map: dict[str, Path] = {}
        dirs_set: set[str] = set()
        if not local_root.exists():
            return files_map, dirs_set
        for root, dirs, files in os.walk(local_root):
            dirs.sort()
            files.sort()
            rel_dir_os = os.path.relpath(root, local_root)
            rel_dir = "" if rel_dir_os in (".", "") else "/".join(Path(rel_dir_os).parts)

            kept_dirs: list[str] = []
            for d in dirs:
                d_rel = f"{rel_dir}/{d}" if rel_dir else d
                if self._is_excluded(d_rel, d, exclude):
                    continue
                kept_dirs.append(d)
                dirs_set.add(d_rel)
            dirs[:] = kept_dirs

            for fname in files:
                f_rel = f"{rel_dir}/{fname}" if rel_dir else fname
                if self._is_excluded(f_rel, fname, exclude):
                    continue
                files_map[f_rel] = Path(root) / fname
        return files_map, dirs_set

    def _scan_remote_tree(
        self, remote_root: str, exclude: Sequence[str]
    ) -> tuple[dict[str, RemoteEntry], set[str]]:
        files_map: dict[str, RemoteEntry] = {}
        dirs_set: set[str] = set()
        root_stat = self.stat(remote_root)
        if root_stat is None or not root_stat.is_dir:
            return files_map, dirs_set

        def _walk_filtered(cur_dir: str, rel_prefix: str) -> None:
            for entry in self.list(cur_dir, hidden=True):
                rel = f"{rel_prefix}/{entry.name}" if rel_prefix else entry.name
                if self._is_excluded(rel, entry.name, exclude):
                    continue
                if entry.is_dir:
                    dirs_set.add(rel)
                    _walk_filtered(entry.path, rel)
                else:
                    files_map[rel] = entry

        _walk_filtered(remote_root, "")
        return files_map, dirs_set

    def sync(
        self,
        local_dir: str | os.PathLike[str],
        remote_dir: str,
        direction: str = "both",
        delete: bool = False,
        dry_run: bool = False,
        exclude: Sequence[str] = (),
        progress: Callable[..., Any] | None = None,
    ) -> SyncPlan:
        """Synchronize a local directory and a remote phone directory."""
        dir_map = {
            "to_phone": "to_phone",
            "to-phone": "to_phone",
            "up": "to_phone",
            "upload": "to_phone",
            "to_pc": "to_pc",
            "to-pc": "to_pc",
            "down": "to_pc",
            "download": "to_pc",
            "both": "both",
            "bidirectional": "both",
        }
        norm_dir = dir_map.get(direction.strip().lower())
        if norm_dir is None:
            raise ValueError(
                f"Invalid sync direction {direction!r}; expected 'to_phone', 'to_pc', or 'both'"
            )
        if norm_dir == "both":
            # DESIGN §4.3: --both = newest wins, never deletes
            delete = False

        local_root = Path(local_dir)
        remote_root = normalize_remote_path(remote_dir)
        plan = SyncPlan(direction=norm_dir, dry_run=dry_run)

        local_files, local_dirs = self._scan_local_tree(local_root, exclude)
        remote_files, remote_dirs = self._scan_remote_tree(remote_root, exclude)

        # Directory creation actions
        if norm_dir in ("to_phone", "both"):
            for d_rel in sorted(local_dirs):
                if d_rel not in remote_dirs:
                    plan.actions.append(
                        SyncAction(
                            op="mkdir_remote",
                            rel_path=d_rel,
                            local_path=str(safe_local_child(local_root, d_rel)),
                            remote_path=normalize_remote_path(
                                posixpath.join(remote_root, d_rel)
                            ),
                            reason="missing_on_phone",
                        )
                    )
        if norm_dir in ("to_pc", "both"):
            for d_rel in sorted(remote_dirs):
                if d_rel not in local_dirs:
                    plan.actions.append(
                        SyncAction(
                            op="mkdir_local",
                            rel_path=d_rel,
                            local_path=str(safe_local_child(local_root, d_rel)),
                            remote_path=normalize_remote_path(
                                posixpath.join(remote_root, d_rel)
                            ),
                            reason="missing_on_pc",
                        )
                    )

        all_rel_files = sorted(set(local_files.keys()) | set(remote_files.keys()))
        for rel in all_rel_files:
            l_path = local_files.get(rel)
            r_entry = remote_files.get(rel)
            target_local = l_path if l_path is not None else safe_local_child(local_root, rel)
            target_remote = (
                r_entry.path
                if r_entry is not None
                else normalize_remote_path(posixpath.join(remote_root, rel))
            )

            if l_path is not None and r_entry is None:
                st = l_path.stat()
                if norm_dir in ("to_phone", "both"):
                    plan.actions.append(
                        SyncAction(
                            op="upload",
                            rel_path=rel,
                            local_path=str(l_path),
                            remote_path=target_remote,
                            size=st.st_size,
                            reason="only_on_pc",
                        )
                    )
                elif delete and norm_dir == "to_pc":
                    plan.actions.append(
                        SyncAction(
                            op="delete_local",
                            rel_path=rel,
                            local_path=str(l_path),
                            remote_path=target_remote,
                            size=st.st_size,
                            reason="deleted_on_phone",
                        )
                    )
                else:
                    plan.skipped += 1
                continue

            if r_entry is not None and l_path is None:
                if norm_dir in ("to_pc", "both"):
                    plan.actions.append(
                        SyncAction(
                            op="download",
                            rel_path=rel,
                            local_path=str(target_local),
                            remote_path=r_entry.path,
                            size=max(0, r_entry.size),
                            reason="only_on_phone",
                        )
                    )
                elif delete and norm_dir == "to_phone":
                    plan.actions.append(
                        SyncAction(
                            op="delete_remote",
                            rel_path=rel,
                            local_path=str(target_local),
                            remote_path=r_entry.path,
                            size=max(0, r_entry.size),
                            reason="deleted_on_pc",
                        )
                    )
                else:
                    plan.skipped += 1
                continue

            # Present on both sides
            assert l_path is not None and r_entry is not None
            st = l_path.stat()
            l_size = st.st_size
            l_mtime = st.st_mtime
            r_size = r_entry.size
            r_mtime = r_entry.mtime

            if r_mtime is None:
                #ARCHITECTURE §6.4 #4: mtime None -> always copy if sizes differ
                if l_size != r_size:
                    chosen_op = "download" if norm_dir == "to_pc" else "upload"
                    plan.actions.append(
                        SyncAction(
                            op=chosen_op,
                            rel_path=rel,
                            local_path=str(l_path),
                            remote_path=r_entry.path,
                            size=l_size if chosen_op == "upload" else max(0, r_size),
                            reason="size_diff_unknown_mtime",
                        )
                    )
                else:
                    plan.skipped += 1
                continue

            diff = l_mtime - r_mtime
            if abs(diff) <= MTIME_TOLERANCE_SECONDS and l_size == r_size:
                plan.skipped += 1
                continue

            if norm_dir == "to_phone":
                if diff > MTIME_TOLERANCE_SECONDS or (
                    abs(diff) <= MTIME_TOLERANCE_SECONDS and l_size != r_size
                ):
                    plan.actions.append(
                        SyncAction(
                            op="upload",
                            rel_path=rel,
                            local_path=str(l_path),
                            remote_path=r_entry.path,
                            size=l_size,
                            reason="newer_on_pc",
                        )
                    )
                else:
                    plan.skipped += 1
            elif norm_dir == "to_pc":
                if diff < -MTIME_TOLERANCE_SECONDS or (
                    abs(diff) <= MTIME_TOLERANCE_SECONDS and l_size != r_size
                ):
                    plan.actions.append(
                        SyncAction(
                            op="download",
                            rel_path=rel,
                            local_path=str(l_path),
                            remote_path=r_entry.path,
                            size=max(0, r_size),
                            reason="newer_on_phone",
                        )
                    )
                else:
                    plan.skipped += 1
            else:  # both: newest wins
                if diff > MTIME_TOLERANCE_SECONDS or (
                    abs(diff) <= MTIME_TOLERANCE_SECONDS and diff >= 0 and l_size != r_size
                ):
                    plan.actions.append(
                        SyncAction(
                            op="upload",
                            rel_path=rel,
                            local_path=str(l_path),
                            remote_path=r_entry.path,
                            size=l_size,
                            reason="newer_on_pc",
                        )
                    )
                elif diff < -MTIME_TOLERANCE_SECONDS or (
                    abs(diff) <= MTIME_TOLERANCE_SECONDS and diff < 0 and l_size != r_size
                ):
                    plan.actions.append(
                        SyncAction(
                            op="download",
                            rel_path=rel,
                            local_path=str(l_path),
                            remote_path=r_entry.path,
                            size=max(0, r_size),
                            reason="newer_on_phone",
                        )
                    )
                else:
                    plan.skipped += 1

        # Directory deletions (deepest first) for mirror modes
        if delete and norm_dir == "to_phone":
            for d_rel in sorted(remote_dirs - local_dirs, key=lambda s: s.count("/"), reverse=True):
                plan.actions.append(
                    SyncAction(
                        op="delete_remote_dir",
                        rel_path=d_rel,
                        local_path=str(safe_local_child(local_root, d_rel)),
                        remote_path=normalize_remote_path(posixpath.join(remote_root, d_rel)),
                        reason="deleted_on_pc",
                    )
                )
        elif delete and norm_dir == "to_pc":
            for d_rel in sorted(local_dirs - remote_dirs, key=lambda s: s.count("/"), reverse=True):
                plan.actions.append(
                    SyncAction(
                        op="delete_local_dir",
                        rel_path=d_rel,
                        local_path=str(safe_local_child(local_root, d_rel)),
                        remote_path=normalize_remote_path(posixpath.join(remote_root, d_rel)),
                        reason="deleted_on_phone",
                    )
                )

        if dry_run:
            return plan

        # Execute sync plan
        if norm_dir in ("to_phone", "both"):
            self.mkdir(remote_root, parents=True)
        if norm_dir in ("to_pc", "both"):
            local_root.mkdir(parents=True, exist_ok=True)

        total_bytes = sum(a.size for a in plan.actions if a.op in ("upload", "download"))
        done_bytes = 0
        _invoke_progress(progress, 0, total_bytes, remote_root)

        for action in plan.actions:
            try:
                if action.op == "mkdir_remote":
                    self.mkdir(action.remote_path, parents=True)
                elif action.op == "mkdir_local":
                    Path(action.local_path).mkdir(parents=True, exist_ok=True)
                elif action.op == "upload":
                    def _up_cb(n: int, cur: str = action.rel_path) -> None:
                        nonlocal done_bytes
                        done_bytes += n
                        _invoke_progress(progress, done_bytes, total_bytes, cur)

                    self._upload_single_file(
                        Path(action.local_path),
                        action.remote_path,
                        resume=False,
                        overwrite="overwrite",
                        confirm=None,
                        on_chunk=_up_cb,
                    )
                    plan.uploaded += 1
                elif action.op == "download":
                    r_stat = remote_files.get(action.rel_path) or self.stat(action.remote_path)
                    if r_stat is None:
                        raise RemoteNotFoundError(action.remote_path)

                    def _dn_cb(n: int, cur: str = action.rel_path) -> None:
                        nonlocal done_bytes
                        done_bytes += n
                        _invoke_progress(progress, done_bytes, total_bytes, cur)

                    self._download_single_file(
                        action.remote_path,
                        Path(action.local_path),
                        r_stat,
                        resume=False,
                        overwrite="overwrite",
                        confirm=None,
                        on_chunk=_dn_cb,
                    )
                    plan.downloaded += 1
                elif action.op == "delete_remote":
                    self.remove(action.remote_path, recursive=False)
                    plan.deleted_remote += 1
                elif action.op == "delete_remote_dir":
                    if self.stat(action.remote_path) is not None:
                        self.remove(action.remote_path, recursive=True)
                        plan.deleted_remote += 1
                elif action.op == "delete_local":
                    lp = Path(action.local_path)
                    if lp.exists():
                        lp.unlink()
                        plan.deleted_local += 1
                elif action.op == "delete_local_dir":
                    lp = Path(action.local_path)
                    if lp.exists():
                        shutil.rmtree(lp)
                        plan.deleted_local += 1
            except ReadOnlyServerError:
                raise
            except TransferCancelledError:
                raise
            except Exception as exc:
                plan.errors.append((action.rel_path, str(exc)))

        return plan


__all__ = [
    "ConnectionAuthError",
    "Discovered",
    "MiniFileClient",
    "MiniFileError",
    "PartialTransferError",
    "PathSecurityError",
    "ReadOnlyServerError",
    "RemoteEntry",
    "RemoteNotFoundError",
    "ServerInfo",
    "SyncAction",
    "SyncPlan",
    "TransferCancelledError",
    "discover",
    "normalize_remote_path",
    "parse_257_path",
    "parse_ftp_timestamp",
    "parse_list_line",
    "parse_mlsx_line",
    "safe_local_child",
]
