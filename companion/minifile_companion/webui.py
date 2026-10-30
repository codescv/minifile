"""Localhost Web Desktop Companion HTTP server and JSON API (pure Python 3.13 stdlib)."""

from __future__ import annotations

import email.parser
import html
import http.cookies
import io
import json
import mimetypes
import os
import posixpath
import secrets
import shutil
import stat
import sys
import tempfile
import threading
import time
import urllib.parse
import uuid
import webbrowser
import zipfile
from collections.abc import Iterator
from dataclasses import dataclass, field
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

from . import __version__
from .client import (
    CHUNK_SIZE,
    ConnectionAuthError,
    MiniFileClient,
    MiniFileError,
    PathSecurityError,
    ReadOnlyServerError,
    RemoteNotFoundError,
    TransferCancelledError,
    normalize_remote_path,
    validate_entry_name,
)
from .config import ConfigStore
from .scanner import discover
from .textutil import decode_text, encode_text
from .updater import (
    UpdateError,
    UpdateSecurityError,
    check_with_cache,
)

STATIC_DIR = Path(__file__).resolve().parent / "static"
MAX_JSON_BODY_BYTES = 16 * 1024 * 1024
MAX_TEXT_EDIT_BYTES = 5 * 1024 * 1024
MULTIPART_HEADER_LIMIT = 32 * 1024


class _HttpApiError(Exception):
    """Structured HTTP error returned as JSON."""

    def __init__(self, status: int, message: str) -> None:
        super().__init__(message)
        self.status = int(status)
        self.message = message


class _BoundedReader:
    """Wrap an HTTP rfile stream so reads never block past Content-Length."""

    def __init__(self, rfile: Any, remaining: int) -> None:
        self._rfile = rfile
        self.remaining = max(0, int(remaining))

    def read(self, size: int = 65536) -> bytes:
        if self.remaining <= 0:
            return b""
        to_read = min(size, self.remaining)
        data = self._rfile.read(to_read)
        self.remaining -= len(data)
        return data


@dataclass
class MultipartPart:
    """Single part parsed from a streaming multipart/form-data request."""

    name: str
    filename: str | None
    content_type: str
    headers: dict[str, str]


def parse_multipart_boundary(content_type_header: str) -> bytes:
    """Extract the multipart boundary token from a Content-Type header without `cgi`."""
    if not content_type_header:
        raise _HttpApiError(HTTPStatus.BAD_REQUEST, "Missing Content-Type header")
    msg = email.parser.HeaderParser().parsestr(f"Content-Type: {content_type_header}\r\n\r\n")
    if msg.get_content_type().lower() != "multipart/form-data":
        raise _HttpApiError(
            HTTPStatus.BAD_REQUEST, "Expected Content-Type: multipart/form-data"
        )
    boundary = msg.get_param("boundary")
    if not boundary or not isinstance(boundary, str):
        raise _HttpApiError(HTTPStatus.BAD_REQUEST, "Missing multipart boundary")
    raw = boundary.encode("latin-1", errors="strict")
    if not raw or len(raw) > 256:
        raise _HttpApiError(HTTPStatus.BAD_REQUEST, "Invalid multipart boundary")
    return raw


def iter_multipart_parts(
    rfile: Any,
    content_length: int,
    boundary: bytes,
) -> Iterator[tuple[MultipartPart, Iterator[bytes]]]:
    """Stream multipart/form-data parts over rfile with O(1) rolling-buffer memory."""
    reader = _BoundedReader(rfile, content_length)
    first_marker = b"--" + boundary
    delim = b"\r\n--" + boundary
    holdback = len(delim) + 8
    buf = bytearray()

    # Locate opening `--<boundary>`
    while True:
        idx = buf.find(first_marker)
        if idx != -1:
            end_marker = idx + len(first_marker)
            while len(buf) < end_marker + 2:
                more = reader.read(4096)
                if not more:
                    break
                buf.extend(more)
            del buf[:end_marker]
            break
        more = reader.read(65536)
        if not more:
            raise _HttpApiError(
                HTTPStatus.BAD_REQUEST, "Multipart opening boundary not found"
            )
        buf.extend(more)
        if len(buf) > 1024 * 1024:
            raise _HttpApiError(HTTPStatus.BAD_REQUEST, "Multipart preamble too large")

    if buf.startswith(b"--"):
        return
    if buf.startswith(b"\r\n"):
        del buf[:2]
    elif buf.startswith(b"\n"):
        del buf[:1]

    done_all = False
    header_parser = email.parser.HeaderParser()

    while not done_all:
        # Read part headers up to \r\n\r\n
        while b"\r\n\r\n" not in buf:
            if len(buf) > MULTIPART_HEADER_LIMIT:
                raise _HttpApiError(
                    HTTPStatus.BAD_REQUEST, "Multipart part headers too large"
                )
            more = reader.read(16384)
            if not more:
                raise _HttpApiError(
                    HTTPStatus.BAD_REQUEST, "Truncated multipart part headers"
                )
            buf.extend(more)

        hdr_end = buf.index(b"\r\n\r\n")
        raw_headers = bytes(buf[:hdr_end])
        del buf[: hdr_end + 4]

        parsed_hdrs = header_parser.parsestr(raw_headers.decode("utf-8", errors="replace"))
        disp_type = parsed_hdrs.get_content_disposition()
        if disp_type != "form-data":
            raise _HttpApiError(
                HTTPStatus.BAD_REQUEST, "Invalid multipart Content-Disposition"
            )
        field_name = parsed_hdrs.get_param("name", header="content-disposition") or ""
        raw_filename = parsed_hdrs.get_filename()
        part_meta = MultipartPart(
            name=str(field_name),
            filename=str(raw_filename) if raw_filename is not None else None,
            content_type=parsed_hdrs.get_content_type(),
            headers={k.lower(): v for k, v in parsed_hdrs.items()},
        )

        part_consumed = False

        def _stream_part() -> Iterator[bytes]:
            nonlocal done_all, part_consumed
            try:
                while True:
                    pos = buf.find(delim)
                    if pos != -1:
                        if pos > 0:
                            yield bytes(buf[:pos])
                        del buf[: pos + len(delim)]
                        while len(buf) < 2:
                            more_tail = reader.read(4096)
                            if not more_tail:
                                break
                            buf.extend(more_tail)
                        if buf.startswith(b"--"):
                            done_all = True
                        elif buf.startswith(b"\r\n"):
                            del buf[:2]
                        elif buf.startswith(b"\n"):
                            del buf[:1]
                        else:
                            done_all = True
                        part_consumed = True
                        return

                    if len(buf) > holdback:
                        emit_len = len(buf) - holdback
                        yield bytes(buf[:emit_len])
                        del buf[:emit_len]

                    more = reader.read(CHUNK_SIZE)
                    if not more:
                        raise _HttpApiError(
                            HTTPStatus.BAD_REQUEST,
                            "Unexpected EOF inside multipart body",
                        )
                    buf.extend(more)
            finally:
                part_consumed = True

        gen = _stream_part()
        yield part_meta, gen
        if not part_consumed:
            for _ in gen:
                pass


@dataclass
class TransferJob:
    """State for an asynchronous PC <-> Phone transfer job in the Web UI."""

    job_id: str
    direction: str  # "up" | "down"
    items: list[dict[str, str]]
    overwrite: str = "overwrite"
    state: str = "queued"  # queued | running | completed | cancelled | failed
    current: str = ""
    done_bytes: int = 0
    total_bytes: int = 0
    speed: float = 0.0
    error: str | None = None
    created_at: float = field(default_factory=time.time)
    cancel_event: threading.Event = field(default_factory=threading.Event, repr=False)

    def to_dict(self) -> dict[str, Any]:
        return {
            "job_id": self.job_id,
            "direction": self.direction,
            "state": self.state,
            "current": self.current,
            "done_bytes": self.done_bytes,
            "total_bytes": self.total_bytes,
            "speed": round(self.speed, 1),
            "error": self.error,
        }


class CompanionState:
    """Thread-safe state shared across HTTP handler requests."""

    def __init__(
        self,
        token: str,
        jail: Path | None = None,
        config_store: ConfigStore | None = None,
        update_api_base: str | None = None,
        update_install_dir: Path | None = None,
        update_allow_http: bool = False,
    ) -> None:
        self.token = token
        self.jail: Path | None = Path(os.path.realpath(jail)) if jail is not None else None
        self.config_store = config_store or ConfigStore()
        self.update_api_base = update_api_base
        self.update_install_dir = update_install_dir
        self.update_allow_http = update_allow_http

        self._lock = threading.RLock()
        self.client: MiniFileClient | None = None

        self._jobs_lock = threading.RLock()
        self.jobs: dict[str, TransferJob] = {}
        self.job_order: list[str] = []

    def close(self) -> None:
        with self._jobs_lock:
            for job in self.jobs.values():
                job.cancel_event.set()
        with self._lock:
            if self.client is not None:
                self.client.close()
                self.client = None

    def get_status(self) -> dict[str, Any]:
        with self._lock:
            cfg = self.config_store.load()
            if self.client is not None and self.client.connected:
                banner = (
                    self.client.server_info.banner
                    if self.client.server_info is not None
                    else ""
                )
                return {
                    "connected": True,
                    "host": self.client.host,
                    "port": self.client.port,
                    "user": self.client.user,
                    "server_banner": banner,
                    "version": __version__,
                    "jail": str(self.jail) if self.jail else None,
                }
            return {
                "connected": False,
                "host": cfg.host,
                "port": cfg.port,
                "user": cfg.user,
                "server_banner": "",
                "version": __version__,
                "jail": str(self.jail) if self.jail else None,
            }

    def connect_ftp(
        self,
        host: str,
        port: int = 2121,
        user: str = "anonymous",
        password: str = "minifile@",
        remember: bool = False,
    ) -> dict[str, Any]:
        if not host or not host.strip():
            raise _HttpApiError(HTTPStatus.BAD_REQUEST, "Host is required")
        new_client = MiniFileClient(
            host=host.strip(),
            port=int(port),
            user=user or "anonymous",
            password=password if password is not None else "minifile@",
        )
        new_client.connect()
        with self._lock:
            if self.client is not None:
                self.client.close()
            self.client = new_client
        if remember:
            self.config_store.update_connection(
                host=new_client.host,
                port=new_client.port,
                user=new_client.user,
                password=new_client.password,
            )
        return self.get_status()

    def disconnect_ftp(self) -> dict[str, Any]:
        with self._lock:
            if self.client is not None:
                self.client.close()
                self.client = None
        return self.get_status()

    def require_client(self) -> MiniFileClient:
        with self._lock:
            if self.client is None or not self.client.connected:
                raise _HttpApiError(HTTPStatus.BAD_REQUEST, "Not connected to phone FTP server")
            return self.client

    def resolve_local_path(self, raw_path: str | None) -> Path:
        if raw_path is not None and "\x00" in raw_path:
            raise _HttpApiError(HTTPStatus.BAD_REQUEST, "Invalid NUL byte in path")
        if not raw_path or not raw_path.strip():
            target = self.jail if self.jail is not None else Path.home()
        else:
            target = Path(raw_path).expanduser()
        real = Path(os.path.realpath(target))
        if self.jail is not None:
            jail_str = str(self.jail)
            real_str = str(real)
            if real_str != jail_str and not real_str.startswith(jail_str + os.sep):
                raise _HttpApiError(
                    HTTPStatus.FORBIDDEN, "Local path is outside the configured --jail directory"
                )
        return real

    def local_roots(self) -> list[dict[str, str]]:
        if self.jail is not None:
            return [{"name": "Jailed Root", "path": str(self.jail)}]
        roots: list[dict[str, str]] = [{"name": "Home", "path": str(Path.home().resolve())}]
        if os.name == "nt":
            for letter in "CDEFGHIJKLMNOPQRSTUVWXYZ":
                drive = f"{letter}:\\"
                if os.path.exists(drive):
                    roots.append({"name": drive, "path": drive})
        else:
            roots.append({"name": "Root (/)", "path": "/"})
        return roots

    def enqueue_transfer(
        self,
        direction: str,
        items: list[dict[str, str]],
        overwrite: str = "overwrite",
    ) -> TransferJob:
        base_client = self.require_client()
        worker_client = base_client.clone_unconnected()
        norm_dir = direction.strip().lower()
        if norm_dir not in ("up", "down"):
            raise _HttpApiError(
                HTTPStatus.BAD_REQUEST, "direction must be 'up' or 'down'"
            )
        if not items or not isinstance(items, list):
            raise _HttpApiError(HTTPStatus.BAD_REQUEST, "items must be a non-empty list")

        validated_items: list[dict[str, str]] = []
        for item in items:
            if not isinstance(item, dict):
                continue
            src = str(item.get("src") or "").strip()
            dst = str(item.get("dst") or "").strip()
            if not src or not dst:
                raise _HttpApiError(
                    HTTPStatus.BAD_REQUEST, "Each transfer item requires src and dst"
                )
            if norm_dir == "up":
                src_local = self.resolve_local_path(src)
                dst_remote = normalize_remote_path(dst)
                validated_items.append({"src": str(src_local), "dst": dst_remote})
            else:
                src_remote = normalize_remote_path(src)
                dst_local = self.resolve_local_path(dst)
                validated_items.append({"src": src_remote, "dst": str(dst_local)})

        if not validated_items:
            raise _HttpApiError(HTTPStatus.BAD_REQUEST, "No valid transfer items")

        job = TransferJob(
            job_id=uuid.uuid4().hex[:12],
            direction=norm_dir,
            items=validated_items,
            overwrite=overwrite or "overwrite",
        )
        with self._jobs_lock:
            self.jobs[job.job_id] = job
            self.job_order.append(job.job_id)

        thread = threading.Thread(
            target=self._run_transfer_worker,
            args=(job, worker_client),
            daemon=True,
            name=f"minifile-transfer-{job.job_id}",
        )
        thread.start()
        return job

    def _run_transfer_worker(self, job: TransferJob, client: MiniFileClient) -> None:
        if job.cancel_event.is_set():
            job.state = "cancelled"
            return

        job.state = "running"
        t0 = time.monotonic()
        try:
            client.connect()
            cumulative_done = 0
            # Precompute rough total bytes across items
            grand_total = 0
            for item in job.items:
                if job.direction == "up":
                    lp = Path(item["src"])
                    if lp.is_file():
                        grand_total += lp.stat().st_size
                    elif lp.is_dir():
                        for r, _, fs in os.walk(lp):
                            for fn in fs:
                                try:
                                    grand_total += (Path(r) / fn).stat().st_size
                                except OSError:
                                    pass
                else:
                    st = client.stat(item["src"])
                    if st is not None and not st.is_dir:
                        grand_total += max(0, st.size)
            job.total_bytes = grand_total

            for item in job.items:
                if job.cancel_event.is_set():
                    raise TransferCancelledError("Transfer cancelled")
                item_base = cumulative_done
                item_last = 0

                def _progress(done: int, total: int, cur: str) -> bool:
                    nonlocal item_last
                    if job.cancel_event.is_set():
                        return False
                    item_last = done
                    job.current = os.path.basename(cur) or cur
                    job.done_bytes = item_base + done
                    if total > 0 and job.done_bytes > job.total_bytes:
                        job.total_bytes = item_base + total
                    elapsed = max(0.001, time.monotonic() - t0)
                    job.speed = job.done_bytes / elapsed
                    return True

                if job.direction == "up":
                    client.upload(
                        item["src"],
                        item["dst"],
                        progress=_progress,
                        overwrite=job.overwrite,
                    )
                else:
                    client.download(
                        item["src"],
                        item["dst"],
                        progress=_progress,
                        overwrite=job.overwrite,
                    )
                if job.cancel_event.is_set():
                    raise TransferCancelledError("Transfer cancelled")
                cumulative_done += item_last

            if job.cancel_event.is_set():
                raise TransferCancelledError("Transfer cancelled")
            job.done_bytes = max(job.done_bytes, job.total_bytes)
            job.state = "completed"
        except TransferCancelledError:
            job.state = "cancelled"
        except Exception as exc:
            if job.cancel_event.is_set():
                job.state = "cancelled"
            else:
                job.state = "failed"
                job.error = str(exc)
        finally:
            client.close()

    def list_transfers(self) -> list[dict[str, Any]]:
        with self._jobs_lock:
            return [self.jobs[jid].to_dict() for jid in reversed(self.job_order)]

    def cancel_transfer(self, job_id: str) -> bool:
        with self._jobs_lock:
            job = self.jobs.get(job_id)
            if job is None:
                return False
            job.cancel_event.set()
            if job.state == "queued":
                job.state = "cancelled"
            return True


def _format_local_perm(mode: int) -> str:
    return stat.filemode(mode)


def _content_disposition_attachment(filename: str) -> str:
    ascii_fallback = "".join(
        ch if 32 <= ord(ch) < 127 and ch not in ('"', "\\", ";") else "_"
        for ch in filename
    ).strip() or "download"
    utf8_quoted = urllib.parse.quote(filename, safe="")
    return f"attachment; filename=\"{ascii_fallback}\"; filename*=UTF-8''{utf8_quoted}"


class CompanionRequestHandler(BaseHTTPRequestHandler):
    """HTTP request handler enforcing Host/Origin/Token security and serving SPA + JSON API."""

    protocol_version = "HTTP/1.1"
    timeout = 10
    server: CompanionHTTPServer  # type: ignore[assignment]

    def log_message(self, fmt: str, *args: Any) -> None:
        if getattr(self.server, "verbose", False):
            super().log_message(fmt, *args)

    @property
    def state(self) -> CompanionState:
        return self.server.companion_state

    def _allowed_hosts(self) -> set[str]:
        p = self.server.server_port
        return {
            f"127.0.0.1:{p}",
            f"localhost:{p}",
            f"[::1]:{p}",
        }

    def _allowed_origins(self) -> set[str]:
        p = self.server.server_port
        return {
            f"http://127.0.0.1:{p}",
            f"http://localhost:{p}",
            f"http://[::1]:{p}",
        }

    def _extract_cookie_token(self) -> str | None:
        raw_cookie = self.headers.get("Cookie")
        if not raw_cookie:
            return None
        jar = http.cookies.SimpleCookie()
        try:
            jar.load(raw_cookie)
        except http.cookies.CookieError:
            return None
        morsel = jar.get("mf_token")
        return morsel.value if morsel is not None else None

    def _authorize(
        self,
        parsed_url: urllib.parse.ParseResult,
        method: str,
    ) -> None:
        """Central security gate for every incoming HTTP request (ARCHITECTURE §6.3)."""
        host_hdr = (self.headers.get("Host") or "").strip().lower()
        if host_hdr not in self._allowed_hosts():
            raise _HttpApiError(
                HTTPStatus.FORBIDDEN, "Forbidden Host header (DNS rebinding guard)"
            )

        if method in ("POST", "PUT", "DELETE", "PATCH"):
            allowed_origins = self._allowed_origins()
            origin = (self.headers.get("Origin") or "").strip().lower().rstrip("/")
            if origin:
                if origin not in allowed_origins:
                    raise _HttpApiError(
                        HTTPStatus.FORBIDDEN, "Cross-origin request rejected"
                    )
            else:
                referer = (self.headers.get("Referer") or "").strip().lower()
                if referer and not any(
                    referer == ao or referer.startswith(ao + "/")
                    for ao in allowed_origins
                ):
                    raise _HttpApiError(
                        HTTPStatus.FORBIDDEN, "Foreign Referer rejected"
                    )

        path = parsed_url.path
        expected = self.state.token

        if path.startswith("/api/"):
            hdr_tok = (self.headers.get("X-MF-Token") or "").strip()
            if hdr_tok and secrets.compare_digest(hdr_tok, expected):
                return
            # Cookie authentication is strictly limited to GET /api/download browser navigations
            if method == "GET" and path == "/api/download":
                cookie_tok = self._extract_cookie_token()
                if cookie_tok and secrets.compare_digest(cookie_tok, expected):
                    return
            raise _HttpApiError(HTTPStatus.UNAUTHORIZED, "Missing or invalid X-MF-Token")

        # Root HTML entry requires initial `?t=<token>` query param or valid `mf_token` cookie
        if path in ("/", "/index.html"):
            qs = urllib.parse.parse_qs(parsed_url.query)
            q_tok = (qs.get("t") or [""])[0]
            cookie_tok = self._extract_cookie_token() or ""
            if (q_tok and secrets.compare_digest(q_tok, expected)) or (
                cookie_tok and secrets.compare_digest(cookie_tok, expected)
            ):
                return
            raise _HttpApiError(HTTPStatus.UNAUTHORIZED, "Unauthorized session token")

    def _send_json(
        self,
        status: int,
        payload: Any,
        extra_headers: dict[str, str] | None = None,
    ) -> None:
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(int(status))
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        if extra_headers:
            for k, v in extra_headers.items():
                self.send_header(k, v)
        self.end_headers()
        self.wfile.write(raw)

    def _read_json_body(self) -> Any:
        raw_len = self.headers.get("Content-Length")
        if not raw_len:
            return {}
        try:
            length = int(raw_len)
        except ValueError as exc:
            raise _HttpApiError(HTTPStatus.BAD_REQUEST, "Invalid Content-Length") from exc
        if length < 0 or length > MAX_JSON_BODY_BYTES:
            raise _HttpApiError(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, "JSON body too large")
        if length == 0:
            return {}
        body = self.rfile.read(length)
        try:
            return json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise _HttpApiError(HTTPStatus.BAD_REQUEST, f"Malformed JSON: {exc}") from exc

    def _serve_static(self, url_path: str) -> None:
        static_real = os.path.realpath(STATIC_DIR)
        if url_path in ("/", "/index.html"):
            index_file = Path(static_real) / "index.html"
            if not index_file.is_file():
                raise _HttpApiError(HTTPStatus.NOT_FOUND, "index.html missing")
            html_text = index_file.read_text(encoding="utf-8")
            meta_tag = (
                f'<meta name="mf-token" content="{html.escape(self.state.token, quote=True)}">'
            )
            html_text = html_text.replace("<!--MF_TOKEN_META-->", meta_tag, 1)
            body = html_text.encode("utf-8")
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header(
                "Set-Cookie",
                f"mf_token={self.state.token}; Path=/; HttpOnly; SameSite=Strict",
            )
            self.end_headers()
            self.wfile.write(body)
            return

        rel = urllib.parse.unquote(url_path)
        if rel.startswith("/static/"):
            rel = rel[len("/static/") :]
        else:
            rel = rel.lstrip("/")
        if "\x00" in rel or not rel:
            raise _HttpApiError(HTTPStatus.NOT_FOUND, "Not found")

        joined = os.path.join(static_real, rel)
        resolved = os.path.realpath(joined)
        if not resolved.startswith(static_real + os.sep) or not os.path.isfile(resolved):
            raise _HttpApiError(HTTPStatus.NOT_FOUND, "Static asset not found")

        ctype, _ = mimetypes.guess_type(resolved)
        if resolved.endswith(".js"):
            ctype = "application/javascript; charset=utf-8"
        elif resolved.endswith(".css"):
            ctype = "text/css; charset=utf-8"
        elif not ctype:
            ctype = "application/octet-stream"

        with open(resolved, "rb") as fh:
            data = fh.read()
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-cache")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(data)

    def _dispatch(self, method: str) -> None:
        parsed = urllib.parse.urlparse(self.path)
        try:
            self._authorize(parsed, method)
            if not parsed.path.startswith("/api/"):
                if method != "GET":
                    raise _HttpApiError(HTTPStatus.METHOD_NOT_ALLOWED, "Method not allowed")
                self._serve_static(parsed.path)
                return
            self._handle_api(method, parsed)
        except _HttpApiError as err:
            self._send_json(err.status, {"error": err.message})
        except ReadOnlyServerError as err:
            self._send_json(HTTPStatus.FORBIDDEN, {"error": str(err)})
        except RemoteNotFoundError as err:
            self._send_json(HTTPStatus.NOT_FOUND, {"error": str(err)})
        except PathSecurityError as err:
            self._send_json(HTTPStatus.BAD_REQUEST, {"error": str(err)})
        except ConnectionAuthError as err:
            self._send_json(HTTPStatus.BAD_GATEWAY, {"error": str(err)})
        except UpdateSecurityError as err:
            self._send_json(HTTPStatus.BAD_GATEWAY, {"error": str(err)})
        except (MiniFileError, FileNotFoundError, PermissionError, OSError) as err:
            status = (
                HTTPStatus.NOT_FOUND
                if isinstance(err, FileNotFoundError)
                else HTTPStatus.FORBIDDEN
                if isinstance(err, PermissionError)
                else HTTPStatus.BAD_REQUEST
            )
            self._send_json(status, {"error": str(err)})
        except Exception as err:
            self._send_json(
                HTTPStatus.INTERNAL_SERVER_ERROR, {"error": f"Internal error: {err}"}
            )

    def do_GET(self) -> None:  # noqa: N802
        self._dispatch("GET")

    def do_POST(self) -> None:  # noqa: N802
        self._dispatch("POST")

    def do_PUT(self) -> None:  # noqa: N802
        self._dispatch("PUT")

    def do_DELETE(self) -> None:  # noqa: N802
        self._dispatch("DELETE")

    def _handle_api(
        self, method: str, parsed: urllib.parse.ParseResult
    ) -> None:
        path = parsed.path.rstrip("/") or "/"
        qs = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)

        if method == "GET" and path == "/api/status":
            self._send_json(HTTPStatus.OK, self.state.get_status())
            return

        if method == "POST" and path == "/api/connect":
            body = self._read_json_body()
            if not isinstance(body, dict):
                raise _HttpApiError(HTTPStatus.BAD_REQUEST, "JSON object expected")
            status = self.state.connect_ftp(
                host=str(body.get("host") or ""),
                port=int(body.get("port") or 2121),
                user=str(body.get("user") or "anonymous"),
                password=str(body.get("password") if body.get("password") is not None else "minifile@"),
                remember=bool(body.get("remember")),
            )
            self._send_json(HTTPStatus.OK, status)
            return

        if method == "POST" and path == "/api/disconnect":
            self._send_json(HTTPStatus.OK, self.state.disconnect_ftp())
            return

        if method == "GET" and path == "/api/scan":
            subnet = (qs.get("subnet") or [""])[0].strip() or None
            port_str = (qs.get("port") or ["2121"])[0].strip() or "2121"
            timeout_str = (qs.get("timeout") or ["0.3"])[0].strip() or "0.3"
            try:
                port = int(port_str)
                timeout = float(timeout_str)
            except ValueError as exc:
                raise _HttpApiError(HTTPStatus.BAD_REQUEST, f"Invalid scan params: {exc}") from exc
            found = discover(subnet=subnet, port=port, timeout=timeout)
            self._send_json(HTTPStatus.OK, [d.to_dict() for d in found])
            return

        if method == "GET" and path == "/api/phone/list":
            client = self.state.require_client()
            rpath = normalize_remote_path((qs.get("path") or ["/"])[0] or "/")
            hidden = (qs.get("hidden") or ["1"])[0] not in ("0", "false", "False")
            all_entries = client.list(rpath, hidden=True)
            dir_has_nomedia = any(e.name == ".nomedia" and not e.is_dir for e in all_entries)
            entries = (
                all_entries
                if hidden
                else [e for e in all_entries if not e.name.startswith(".")]
            )
            child_dirs = [e for e in entries if e.is_dir]
            if len(child_dirs) <= 32:
                for d_entry in child_dirs:
                    try:
                        d_entry.has_nomedia = client.nomedia(d_entry.path, None)
                    except MiniFileError:
                        d_entry.has_nomedia = False
            parent = None if rpath == "/" else (posixpath.dirname(rpath) or "/")
            self._send_json(
                HTTPStatus.OK,
                {
                    "path": rpath,
                    "parent": parent,
                    "has_nomedia": dir_has_nomedia,
                    "entries": [e.to_dict() for e in entries],
                },
            )
            return

        if method == "GET" and path == "/api/local/list":
            raw_path = (qs.get("path") or [""])[0]
            hidden = (qs.get("hidden") or ["1"])[0] not in ("0", "false", "False")
            local_dir = self.state.resolve_local_path(raw_path)
            if not local_dir.is_dir():
                raise _HttpApiError(HTTPStatus.NOT_FOUND, f"Not a directory: {local_dir}")

            dir_has_nomedia = (local_dir / ".nomedia").exists()
            items: list[dict[str, Any]] = []
            with os.scandir(local_dir) as it:
                for de in it:
                    name = de.name
                    if not hidden and name.startswith("."):
                        continue
                    try:
                        st = de.stat(follow_symlinks=True)
                        is_dir = stat.S_ISDIR(st.st_mode)
                        size = 0 if is_dir else st.st_size
                        mtime: float | None = st.st_mtime
                        perm = _format_local_perm(st.st_mode)
                    except OSError:
                        is_dir = de.is_dir(follow_symlinks=False)
                        size = 0
                        mtime = None
                        perm = "----------"
                    has_nm = False
                    if is_dir:
                        try:
                            has_nm = (Path(de.path) / ".nomedia").exists()
                        except OSError:
                            has_nm = False
                    items.append(
                        {
                            "name": name,
                            "path": str(Path(local_dir) / name),
                            "is_dir": is_dir,
                            "size": size,
                            "mtime": mtime,
                            "perm": perm,
                            "has_nomedia": has_nm,
                            "is_hidden": name.startswith("."),
                        }
                    )
            items.sort(key=lambda x: (not x["is_dir"], x["name"].lower(), x["name"]))
            parent_path: str | None = None
            cand_parent = local_dir.parent
            if cand_parent != local_dir:
                if self.state.jail is None or (
                    str(cand_parent) == str(self.state.jail)
                    or str(cand_parent).startswith(str(self.state.jail) + os.sep)
                ):
                    parent_path = str(cand_parent)
            self._send_json(
                HTTPStatus.OK,
                {
                    "path": str(local_dir),
                    "parent": parent_path,
                    "roots": self.state.local_roots(),
                    "has_nomedia": dir_has_nomedia,
                    "entries": items,
                },
            )
            return

        if method == "POST" and path == "/api/phone/mkdir":
            client = self.state.require_client()
            body = self._read_json_body()
            base_path = str(body.get("path") or "/")
            name = body.get("name")
            if name is not None and str(name).strip():
                clean_name = validate_entry_name(str(name).strip())
                target = normalize_remote_path(posixpath.join(base_path, clean_name))
            else:
                target = normalize_remote_path(base_path)
            created = client.mkdir(target, parents=bool(body.get("parents", False)))
            self._send_json(HTTPStatus.OK, {"path": created})
            return

        if method == "POST" and path == "/api/local/mkdir":
            body = self._read_json_body()
            base_dir = self.state.resolve_local_path(str(body.get("path") or ""))
            name = validate_entry_name(str(body.get("name") or "").strip())
            new_dir = self.state.resolve_local_path(str(base_dir / name))
            new_dir.mkdir(parents=False, exist_ok=False)
            self._send_json(HTTPStatus.OK, {"path": str(new_dir)})
            return

        if method == "POST" and path == "/api/phone/rename":
            client = self.state.require_client()
            body = self._read_json_body()
            src = normalize_remote_path(str(body.get("path") or ""))
            new_name = validate_entry_name(str(body.get("new_name") or "").strip())
            parent = posixpath.dirname(src) or "/"
            dst = normalize_remote_path(posixpath.join(parent, new_name))
            renamed = client.rename(src, dst)
            self._send_json(HTTPStatus.OK, {"path": renamed})
            return

        if method == "POST" and path == "/api/local/rename":
            body = self._read_json_body()
            src_local = self.state.resolve_local_path(str(body.get("path") or ""))
            if self.state.jail is not None and src_local == self.state.jail:
                raise _HttpApiError(HTTPStatus.FORBIDDEN, "Cannot rename jail root")
            new_name = validate_entry_name(str(body.get("new_name") or "").strip())
            dst_local = self.state.resolve_local_path(str(src_local.parent / new_name))
            if dst_local.exists():
                raise _HttpApiError(HTTPStatus.CONFLICT, f"Target already exists: {new_name}")
            src_local.rename(dst_local)
            self._send_json(HTTPStatus.OK, {"path": str(dst_local)})
            return

        if method == "POST" and path == "/api/phone/delete":
            client = self.state.require_client()
            body = self._read_json_body()
            paths = body.get("paths") or []
            recursive = bool(body.get("recursive", True))
            deleted = 0
            failed: list[dict[str, str]] = []
            for p in paths:
                try:
                    client.remove(str(p), recursive=recursive)
                    deleted += 1
                except ReadOnlyServerError:
                    raise
                except Exception as exc:
                    failed.append({"path": str(p), "error": str(exc)})
            self._send_json(HTTPStatus.OK, {"deleted": deleted, "failed": failed})
            return

        if method == "POST" and path == "/api/local/delete":
            body = self._read_json_body()
            paths = body.get("paths") or []
            recursive = bool(body.get("recursive", True))
            deleted = 0
            failed = []
            for p in paths:
                try:
                    lp = self.state.resolve_local_path(str(p))
                    if self.state.jail is not None and lp == self.state.jail:
                        raise _HttpApiError(HTTPStatus.FORBIDDEN, "Cannot delete jail root")
                    if lp.is_dir() and not lp.is_symlink():
                        if recursive:
                            shutil.rmtree(lp)
                        else:
                            lp.rmdir()
                    else:
                        lp.unlink()
                    deleted += 1
                except Exception as exc:
                    failed.append({"path": str(p), "error": str(exc)})
            self._send_json(HTTPStatus.OK, {"deleted": deleted, "failed": failed})
            return

        if method == "POST" and path == "/api/phone/nomedia":
            client = self.state.require_client()
            body = self._read_json_body()
            rpath = normalize_remote_path(str(body.get("path") or "/"))
            raw_state = body.get("state", "toggle")
            if raw_state == "toggle":
                cur = client.nomedia(rpath, None)
                res_state = client.nomedia(rpath, not cur)
            elif raw_state is None:
                res_state = client.nomedia(rpath, None)
            else:
                res_state = client.nomedia(rpath, bool(raw_state))
            self._send_json(HTTPStatus.OK, {"path": rpath, "state": res_state})
            return

        if method == "GET" and path == "/api/phone/text":
            client = self.state.require_client()
            rpath = normalize_remote_path((qs.get("path") or [""])[0])
            enc = (qs.get("encoding") or [""])[0].strip() or None
            st = client.stat(rpath)
            if st is None or st.is_dir:
                raise RemoteNotFoundError(f"Not a remote file: {rpath}")
            if st.size > MAX_TEXT_EDIT_BYTES:
                raise _HttpApiError(
                    HTTPStatus.REQUEST_ENTITY_TOO_LARGE,
                    f"File exceeds {MAX_TEXT_EDIT_BYTES} bytes inline editor limit",
                )
            text, used_enc = client.read_text(rpath, encoding=enc)
            self._send_json(
                HTTPStatus.OK, {"path": rpath, "text": text, "encoding": used_enc}
            )
            return

        if method == "PUT" and path == "/api/phone/text":
            client = self.state.require_client()
            body = self._read_json_body()
            rpath = normalize_remote_path(str(body.get("path") or ""))
            text = str(body.get("text") if body.get("text") is not None else "")
            enc = str(body.get("encoding") or "utf-8")
            written = client.write_text(rpath, text, encoding=enc)
            self._send_json(HTTPStatus.OK, {"path": rpath, "size": written})
            return

        if method == "GET" and path == "/api/local/text":
            raw_path = (qs.get("path") or [""])[0]
            enc = (qs.get("encoding") or [""])[0].strip() or None
            lp = self.state.resolve_local_path(raw_path)
            if not lp.is_file():
                raise _HttpApiError(HTTPStatus.NOT_FOUND, f"Not a file: {lp}")
            if lp.stat().st_size > MAX_TEXT_EDIT_BYTES:
                raise _HttpApiError(
                    HTTPStatus.REQUEST_ENTITY_TOO_LARGE,
                    f"File exceeds {MAX_TEXT_EDIT_BYTES} bytes inline editor limit",
                )
            data = lp.read_bytes()
            text, used_enc = decode_text(data, encoding=enc)
            self._send_json(
                HTTPStatus.OK, {"path": str(lp), "text": text, "encoding": used_enc}
            )
            return

        if method == "PUT" and path == "/api/local/text":
            body = self._read_json_body()
            raw_path = str(body.get("path") or "")
            if not raw_path:
                raise _HttpApiError(HTTPStatus.BAD_REQUEST, "path is required")
            # Validate parent inside jail, then final target inside jail
            raw_expanded = Path(raw_path).expanduser()
            parent_real = self.state.resolve_local_path(str(raw_expanded.parent))
            fname = validate_entry_name(raw_expanded.name)
            lp = self.state.resolve_local_path(str(parent_real / fname))
            text = str(body.get("text") if body.get("text") is not None else "")
            enc = str(body.get("encoding") or "utf-8")
            encoded = encode_text(text, encoding=enc)
            fd, tmp_name = tempfile.mkstemp(
                prefix=f".{fname}.", suffix=".tmp", dir=str(parent_real)
            )
            try:
                with os.fdopen(fd, "wb") as fh:
                    fh.write(encoded)
                os.replace(tmp_name, lp)
            except BaseException:
                try:
                    os.unlink(tmp_name)
                except OSError:
                    pass
                raise
            self._send_json(HTTPStatus.OK, {"path": str(lp), "size": len(encoded)})
            return

        if method == "POST" and path == "/api/transfer":
            body = self._read_json_body()
            job = self.state.enqueue_transfer(
                direction=str(body.get("direction") or ""),
                items=body.get("items") or [],
                overwrite=str(body.get("overwrite") or "overwrite"),
            )
            self._send_json(HTTPStatus.OK, {"job_id": job.job_id})
            return

        if method == "GET" and path == "/api/transfers":
            self._send_json(HTTPStatus.OK, self.state.list_transfers())
            return

        if method == "POST" and path.startswith("/api/transfers/") and path.endswith("/cancel"):
            parts = path.split("/")
            # ["", "api", "transfers", "<id>", "cancel"]
            if len(parts) == 5:
                job_id = parts[3]
                ok = self.state.cancel_transfer(job_id)
                if not ok:
                    raise _HttpApiError(HTTPStatus.NOT_FOUND, f"Unknown transfer job: {job_id}")
                self._send_json(HTTPStatus.OK, {"ok": True})
                return

        if method == "POST" and path == "/api/upload":
            self._handle_multipart_upload(qs)
            return

        if method == "GET" and path == "/api/download":
            self._handle_download(qs)
            return

        if path == "/api/favorites":
            if method == "GET":
                self._send_json(HTTPStatus.OK, self.state.config_store.get_favorites())
                return
            if method == "PUT":
                body = self._read_json_body()
                if not isinstance(body, list):
                    raise _HttpApiError(
                        HTTPStatus.BAD_REQUEST, "Favorites body must be a JSON array"
                    )
                saved = self.state.config_store.set_favorites(body)
                self._send_json(HTTPStatus.OK, saved)
                return

        if path == "/api/update":
            if method == "GET":
                force_flag = (qs.get("force") or ["0"])[0].strip() in ("1", "true", "True")
                inc_pre = (qs.get("pre") or ["0"])[0].strip() in ("1", "true", "True")
                try:
                    payload = check_with_cache(
                        store=self.state.config_store,
                        force=force_flag,
                        current=__version__,
                        include_pre=inc_pre,
                        allow_http_loopback=self.state.update_allow_http,
                        api_base=self.state.update_api_base,
                    )
                    self._send_json(HTTPStatus.OK, payload)
                except UpdateError as exc:
                    self._send_json(HTTPStatus.BAD_GATEWAY, {"error": str(exc)})
                return

        raise _HttpApiError(HTTPStatus.NOT_FOUND, f"Unknown API endpoint: {method} {path}")

    def _handle_multipart_upload(self, qs: dict[str, list[str]]) -> None:
        base_client = self.state.require_client()
        dst_dir = normalize_remote_path((qs.get("dst") or ["/"])[0] or "/")
        raw_len = self.headers.get("Content-Length")
        if not raw_len or not raw_len.isdigit():
            raise _HttpApiError(
                HTTPStatus.BAD_REQUEST, "Content-Length required for multipart upload"
            )
        content_length = int(raw_len)
        boundary = parse_multipart_boundary(self.headers.get("Content-Type") or "")

        upload_client = base_client.clone_unconnected()
        upload_client.connect()
        stored: list[str] = []
        pending_relpath: str | None = None
        try:
            for part_meta, part_chunks in iter_multipart_parts(
                self.rfile, content_length, boundary
            ):
                if part_meta.filename is None:
                    # Optional metadata form field (e.g. relative directory path for folder drop)
                    val_bytes = b"".join(part_chunks)
                    if part_meta.name == "relpath":
                        pending_relpath = val_bytes.decode("utf-8", errors="replace").strip()
                    continue

                raw_fname = part_meta.filename
                if "\x00" in raw_fname:
                    raise _HttpApiError(HTTPStatus.BAD_REQUEST, "NUL byte in filename")

                # Sanitize filename strictly per ARCHITECTURE §6.3
                clean_basename = os.path.basename(raw_fname.replace("\\", "/")).strip()
                validate_entry_name(clean_basename)

                target_dir = dst_dir
                if pending_relpath:
                    if "\x00" in pending_relpath:
                        raise _HttpApiError(HTTPStatus.BAD_REQUEST, "Invalid relpath")
                    rel_segs = [
                        validate_entry_name(seg)
                        for seg in pending_relpath.replace("\\", "/").split("/")
                        if seg and seg != "."
                    ]
                    if rel_segs:
                        target_dir = normalize_remote_path(
                            posixpath.join(dst_dir, "/".join(rel_segs))
                        )
                    pending_relpath = None

                remote_target = normalize_remote_path(
                    posixpath.join(target_dir, clean_basename)
                )
                upload_client.upload_stream(remote_target, part_chunks, ensure_parents=True)
                stored.append(remote_target)
        finally:
            upload_client.close()

        self._send_json(HTTPStatus.OK, {"stored": stored})

    def _handle_download(self, qs: dict[str, list[str]]) -> None:
        source = ((qs.get("source") or ["phone"])[0] or "phone").strip().lower()
        raw_path = (qs.get("path") or [""])[0]
        if not raw_path:
            raise _HttpApiError(HTTPStatus.BAD_REQUEST, "Missing path query parameter")

        if source == "local":
            lp = self.state.resolve_local_path(raw_path)
            if not lp.exists():
                raise _HttpApiError(HTTPStatus.NOT_FOUND, f"Not found: {lp}")
            if lp.is_file():
                fsize = lp.stat().st_size
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header("Content-Length", str(fsize))
                self.send_header(
                    "Content-Disposition", _content_disposition_attachment(lp.name)
                )
                self.send_header("X-Content-Type-Options", "nosniff")
                self.end_headers()
                with open(lp, "rb") as fh:
                    shutil.copyfileobj(fh, self.wfile, length=CHUNK_SIZE)
                return

            with tempfile.SpooledTemporaryFile(max_size=8 * 1024 * 1024) as spool:
                with zipfile.ZipFile(spool, "w", compression=zipfile.ZIP_DEFLATED) as zf:
                    for root, dirs, files in os.walk(lp):
                        dirs.sort()
                        files.sort()
                        rel_root = os.path.relpath(root, lp)
                        if rel_root not in (".", "") and not dirs and not files:
                            zf.writestr("/".join(Path(rel_root).parts) + "/", b"")
                        for fn in files:
                            full = Path(root) / fn
                            arc = "/".join(Path(os.path.relpath(full, lp)).parts)
                            zf.write(full, arcname=arc)
                zip_size = spool.tell()
                spool.seek(0)
                zip_name = f"{lp.name or 'folder'}.zip"
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", "application/zip")
                self.send_header("Content-Length", str(zip_size))
                self.send_header(
                    "Content-Disposition", _content_disposition_attachment(zip_name)
                )
                self.send_header("X-Content-Type-Options", "nosniff")
                self.end_headers()
                shutil.copyfileobj(spool, self.wfile, length=CHUNK_SIZE)
            return

        # Default: stream file or zipped directory from phone over a dedicated FTP session
        base_client = self.state.require_client()
        rpath = normalize_remote_path(raw_path)
        dl_client = base_client.clone_unconnected()
        dl_client.connect()
        try:
            entry = dl_client.stat(rpath)
            if entry is None:
                raise RemoteNotFoundError(f"Remote path not found: {rpath}")

            if not entry.is_dir:
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", "application/octet-stream")
                if entry.size >= 0:
                    self.send_header("Content-Length", str(entry.size))
                self.send_header(
                    "Content-Disposition", _content_disposition_attachment(entry.name)
                )
                self.send_header("X-Content-Type-Options", "nosniff")
                self.end_headers()
                for chunk in dl_client.download_stream(rpath):
                    self.wfile.write(chunk)
                return

            # Directory -> zip on the fly into SpooledTemporaryFile and stream
            with tempfile.SpooledTemporaryFile(max_size=8 * 1024 * 1024) as spool:
                with zipfile.ZipFile(spool, "w", compression=zipfile.ZIP_DEFLATED) as zf:
                    for dirpath, dirs, files in dl_client.walk(rpath, hidden=True):
                        rel_dir = posixpath.relpath(dirpath, rpath)
                        if rel_dir not in (".", "") and not dirs and not files:
                            zf.writestr(rel_dir.strip("/") + "/", b"")
                        for f_entry in files:
                            arc_name = (
                                f_entry.name
                                if rel_dir in (".", "")
                                else f"{rel_dir}/{f_entry.name}"
                            )
                            with zf.open(arc_name, "w") as zout:
                                for chunk in dl_client.download_stream(f_entry.path):
                                    zout.write(chunk)

                zip_size = spool.tell()
                spool.seek(0)
                base_label = posixpath.basename(rpath) or "phone-root"
                zip_name = f"{base_label}.zip"
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", "application/zip")
                self.send_header("Content-Length", str(zip_size))
                self.send_header(
                    "Content-Disposition", _content_disposition_attachment(zip_name)
                )
                self.send_header("X-Content-Type-Options", "nosniff")
                self.end_headers()
                shutil.copyfileobj(spool, self.wfile, length=CHUNK_SIZE)
        finally:
            dl_client.close()


class CompanionHTTPServer(ThreadingHTTPServer):
    """ThreadingHTTPServer bound to loopback carrying CompanionState."""

    daemon_threads = True
    allow_reuse_address = True

    def __init__(
        self,
        server_address: tuple[str, int],
        companion_state: CompanionState,
        verbose: bool = False,
    ) -> None:
        self.companion_state = companion_state
        self.verbose = verbose
        super().__init__(server_address, CompanionRequestHandler)

    def handle_error(self, request: Any, client_address: Any) -> None:
        exc = sys.exc_info()[1]
        if isinstance(exc, (BrokenPipeError, ConnectionResetError, TimeoutError)) and not self.verbose:
            return
        super().handle_error(request, client_address)


class CompanionWebServer:
    """High-level wrapper managing CompanionHTTPServer lifecycle for CLI & tests."""

    def __init__(
        self,
        host: str = "127.0.0.1",
        port: int = 8765,
        token: str | None = None,
        jail: Path | str | None = None,
        config_path: Path | str | None = None,
        initial_ftp: dict[str, Any] | None = None,
        update_api_base: str | None = None,
        update_install_dir: Path | str | None = None,
        update_allow_http: bool = False,
        verbose: bool = False,
    ) -> None:
        if host not in ("127.0.0.1", "localhost", "::1"):
            raise ValueError("Web UI must bind to loopback (127.0.0.1 / localhost) only")
        self.token = token or secrets.token_urlsafe(32)
        store = ConfigStore(config_path)
        self.state = CompanionState(
            token=self.token,
            jail=Path(jail) if jail is not None else None,
            config_store=store,
            update_api_base=update_api_base,
            update_install_dir=(
                Path(update_install_dir) if update_install_dir is not None else None
            ),
            update_allow_http=update_allow_http,
        )
        self.httpd = CompanionHTTPServer((host, int(port)), self.state, verbose=verbose)
        self._thread: threading.Thread | None = None

        if initial_ftp and initial_ftp.get("host"):
            try:
                self.state.connect_ftp(
                    host=str(initial_ftp["host"]),
                    port=int(initial_ftp.get("port", 2121)),
                    user=str(initial_ftp.get("user", "anonymous")),
                    password=str(initial_ftp.get("password", "minifile@")),
                    remember=False,
                )
            except Exception:
                pass

    @property
    def host(self) -> str:
        return str(self.httpd.server_address[0])

    @property
    def port(self) -> int:
        return int(self.httpd.server_port)

    @property
    def launch_url(self) -> str:
        return f"http://127.0.0.1:{self.port}/?t={self.token}"

    def start_background(self) -> CompanionWebServer:
        self._thread = threading.Thread(
            target=self.httpd.serve_forever,
            kwargs={"poll_interval": 0.1},
            daemon=True,
            name="minifile-webui",
        )
        self._thread.start()
        return self

    def serve_forever(self) -> None:
        try:
            self.httpd.serve_forever()
        finally:
            self.shutdown()

    def shutdown(self) -> None:
        try:
            self.httpd.shutdown()
        except Exception:
            pass
        try:
            self.httpd.server_close()
        except Exception:
            pass
        self.state.close()
        if self._thread is not None and self._thread.is_alive():
            self._thread.join(timeout=2.0)


def run_webui(
    port: int = 8765,
    open_browser: bool = True,
    jail: str | None = None,
    initial_ftp: dict[str, Any] | None = None,
    verbose: bool = False,
) -> int:
    """Start the local Web Desktop Companion and block until Ctrl+C."""
    server = CompanionWebServer(
        host="127.0.0.1",
        port=port,
        jail=jail,
        initial_ftp=initial_ftp,
        verbose=verbose,
    )
    url = server.launch_url
    print(f"MiniFile Web Desktop Companion running at: {url}", file=sys.stderr)
    print("Press Ctrl+C to stop.", file=sys.stderr)
    if open_browser:
        try:
            webbrowser.open(url)
        except Exception:
            pass
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping MiniFile Web Desktop Companion...", file=sys.stderr)
        server.shutdown()
    return 0
