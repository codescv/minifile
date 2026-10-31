"""OAuth 2.0 loopback + PKCE (S256) helper for Google Drive (`minifile gdrive-auth`).

Uses Python 3.10+ standard library modules only. Prints a JSON credentials object
ready to paste into the Android Files app (`Add Server -> Google Drive -> Paste Token / JSON`).
"""

from __future__ import annotations

import base64
import datetime
import getpass
import hashlib
import http.server
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from collections.abc import Callable
from typing import Any

from .client import ConnectionAuthError

DEFAULT_AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
DEFAULT_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"
CALLBACK_PATH = "/oauth2callback"

_SUCCESS_HTML = (
    b"<!doctype html><html><head><meta charset='utf-8'><title>Signed in</title></head>"
    b"<body><p>Signed in. You can close this tab and copy the JSON from your terminal into the Files app.</p>"
    b"</body></html>"
)
_DENIED_HTML = (
    b"<!doctype html><html><head><meta charset='utf-8'><title>Sign-in denied</title></head>"
    b"<body><p>Google sign-in was denied. Return to your terminal.</p></body></html>"
)
_BAD_STATE_HTML = (
    b"<!doctype html><html><head><meta charset='utf-8'><title>Invalid request</title></head>"
    b"<body><p>Invalid OAuth callback request.</p></body></html>"
)
_NOT_FOUND_HTML = (
    b"<!doctype html><html><head><meta charset='utf-8'><title>Not found</title></head>"
    b"<body><p>Not found.</p></body></html>"
)


class GDriveAuthError(ConnectionAuthError):
    """Raised when Google Drive OAuth authorization or token exchange fails."""


def b64url_no_pad(raw: bytes) -> str:
    """Encode bytes as unpadded URL-safe Base64 ASCII."""
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def compute_s256_challenge(verifier: str) -> str:
    """Compute RFC 7636 S256 code_challenge for a PKCE code_verifier."""
    digest = hashlib.sha256(verifier.encode("ascii")).digest()
    return b64url_no_pad(digest)


def generate_pkce_pair() -> tuple[str, str]:
    """Return `(code_verifier, code_challenge)` using 32 cryptographic random bytes."""
    verifier = b64url_no_pad(secrets.token_bytes(32))
    return verifier, compute_s256_challenge(verifier)


def generate_state() -> str:
    """Return a 16-byte cryptographic random OAuth `state` token."""
    return b64url_no_pad(secrets.token_bytes(16))


def build_auth_url(
    *,
    client_id: str,
    redirect_uri: str,
    code_challenge: str,
    state: str,
    auth_endpoint: str = DEFAULT_AUTH_ENDPOINT,
    scope: str = DRIVE_SCOPE,
) -> str:
    """Construct the Google OAuth 2.0 authorization URL with PKCE S256 and offline consent."""
    query = urllib.parse.urlencode(
        [
            ("client_id", client_id),
            ("redirect_uri", redirect_uri),
            ("response_type", "code"),
            ("scope", scope),
            ("code_challenge", code_challenge),
            ("code_challenge_method", "S256"),
            ("state", state),
            ("access_type", "offline"),
            ("prompt", "consent"),
        ]
    )
    sep = "&" if "?" in auth_endpoint else "?"
    return f"{auth_endpoint}{sep}{query}"


class _OAuthCallbackHandler(http.server.BaseHTTPRequestHandler):
    server: OAuthCallbackServer  # type: ignore[assignment]

    def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
        # Never log OAuth callback URLs containing authorization codes or state.
        return

    def _send_html(self, status: int, body: bytes) -> None:
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802
        parsed = urllib.parse.urlsplit(self.path)
        if parsed.path != CALLBACK_PATH:
            self._send_html(404, _NOT_FOUND_HTML)
            return

        params = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
        got_state = params.get("state", [None])[0]
        got_error = params.get("error", [None])[0]
        got_code = params.get("code", [None])[0]

        if got_state is not None and got_state != self.server.expected_state:
            self._send_html(400, _BAD_STATE_HTML)
            return

        if got_error:
            self.server.auth_error = GDriveAuthError("Google sign-in was denied")
            self._send_html(400, _DENIED_HTML)
            return

        if not got_code or got_state != self.server.expected_state:
            self._send_html(400, _BAD_STATE_HTML)
            return

        self.server.auth_code = got_code
        self._send_html(200, _SUCCESS_HTML)


class OAuthCallbackServer(http.server.HTTPServer):
    """Single-user loopback HTTP server awaiting the OAuth 2.0 redirect."""

    allow_reuse_address = True

    def __init__(self, port: int, expected_state: str) -> None:
        super().__init__(("127.0.0.1", port), _OAuthCallbackHandler)
        self.timeout = 0.5
        self.expected_state = expected_state
        self.auth_code: str | None = None
        self.auth_error: GDriveAuthError | None = None


def wait_for_oauth_code(server: OAuthCallbackServer, timeout_s: float = 300.0) -> str:
    """Serve loopback requests until a valid `code` arrives, denial occurs, or `timeout_s` expires."""
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        remaining = deadline - time.monotonic()
        server.timeout = max(0.05, min(0.5, remaining))
        server.handle_request()
        if server.auth_error is not None:
            raise server.auth_error
        if server.auth_code is not None:
            return server.auth_code
    raise GDriveAuthError("Timed out waiting for Google OAuth callback in browser.")


def exchange_code_for_tokens(
    *,
    code: str,
    client_id: str,
    client_secret: str,
    redirect_uri: str,
    code_verifier: str,
    token_endpoint: str = DEFAULT_TOKEN_ENDPOINT,
    urlopen_fn: Callable[..., Any] = urllib.request.urlopen,
    now_utc: datetime.datetime | None = None,
) -> dict[str, str]:
    """Exchange authorization `code` + PKCE `code_verifier` for OAuth tokens."""
    form_pairs = [
        ("code", code),
        ("client_id", client_id),
        ("redirect_uri", redirect_uri),
        ("grant_type", "authorization_code"),
        ("code_verifier", code_verifier),
    ]
    if client_secret:
        form_pairs.append(("client_secret", client_secret))

    encoded = urllib.parse.urlencode(form_pairs).encode("utf-8")
    req = urllib.request.Request(
        token_endpoint,
        data=encoded,
        method="POST",
        headers={
            "Content-Type": "application/x-www-form-urlencoded",
            "Accept": "application/json",
        },
    )

    try:
        with urlopen_fn(req, timeout=15.0) as resp:
            raw_body = resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as exc:
        err_body = ""
        try:
            err_body = exc.read().decode("utf-8", errors="replace")
        except Exception:
            pass
        detail = _extract_oauth_error(err_body) or f"HTTP {exc.code}"
        raise GDriveAuthError(f"Token exchange failed: {detail}") from exc
    except urllib.error.URLError as exc:
        raise GDriveAuthError(f"Token exchange network error: {exc.reason}") from exc

    try:
        parsed = json.loads(raw_body)
    except json.JSONDecodeError as exc:
        raise GDriveAuthError("Token endpoint returned invalid JSON.") from exc

    if not isinstance(parsed, dict):
        raise GDriveAuthError("Token endpoint returned unexpected payload.")

    access_token = str(parsed.get("access_token") or "").strip()
    if not access_token:
        detail = _extract_oauth_error(raw_body) or "missing access_token"
        raise GDriveAuthError(f"Token exchange failed: {detail}")

    refresh_token = str(parsed.get("refresh_token") or "").strip()
    token_type = str(parsed.get("token_type") or "Bearer").strip() or "Bearer"
    scope = str(parsed.get("scope") or DRIVE_SCOPE).strip() or DRIVE_SCOPE

    try:
        expires_in = int(parsed.get("expires_in", 3600))
    except (TypeError, ValueError):
        expires_in = 3600

    base_now = now_utc or datetime.datetime.now(datetime.timezone.utc)
    expiry_dt = (base_now + datetime.timedelta(seconds=max(0, expires_in))).astimezone(
        datetime.timezone.utc
    )
    expiry_iso = expiry_dt.replace(microsecond=0).strftime("%Y-%m-%dT%H:%M:%SZ")

    return {
        "access_token": access_token,
        "refresh_token": refresh_token,
        "expiry": expiry_iso,
        "client_id": client_id,
        "client_secret": client_secret,
        "token_type": token_type,
        "scope": scope,
    }


def _extract_oauth_error(raw_body: str) -> str:
    if not raw_body:
        return ""
    try:
        obj = json.loads(raw_body)
    except json.JSONDecodeError:
        return ""
    if not isinstance(obj, dict):
        return ""
    err = str(obj.get("error") or "").strip()
    desc = str(obj.get("error_description") or "").strip()
    if err and desc:
        return f"{err} ({desc})"
    return err or desc


def run_gdrive_auth(
    *,
    client_id: str | None = None,
    client_secret: str | None = None,
    port: int = 0,
    open_browser: bool = True,
    json_only: bool = False,
    timeout_s: float = 300.0,
    auth_endpoint: str = DEFAULT_AUTH_ENDPOINT,
    token_endpoint: str = DEFAULT_TOKEN_ENDPOINT,
    urlopen_fn: Callable[..., Any] = urllib.request.urlopen,
    browser_opener: Callable[[str], bool] = webbrowser.open,
    stdout: Any = None,
    stderr: Any = None,
) -> dict[str, str]:
    """Run interactive PKCE loopback Google Drive authorization and print JSON credentials."""
    out = stdout or sys.stdout
    err = stderr or sys.stderr

    resolved_id = (client_id or os.environ.get("MINIFILE_GDRIVE_CLIENT_ID") or "").strip()
    if not resolved_id and sys.stdin.isatty():
        print("Google OAuth Desktop Client ID: ", end="", file=err, flush=True)
        resolved_id = sys.stdin.readline().strip()
    if not resolved_id:
        raise GDriveAuthError(
            "OAuth Client ID is required (pass --client-id YOUR_CLIENT_ID.apps.googleusercontent.com)."
        )

    if client_secret is not None:
        resolved_secret = client_secret.strip()
    elif "MINIFILE_GDRIVE_CLIENT_SECRET" in os.environ:
        resolved_secret = os.environ["MINIFILE_GDRIVE_CLIENT_SECRET"].strip()
    elif sys.stdin.isatty():
        resolved_secret = getpass.getpass(
            "Client secret (required for Desktop-type clients, Enter if none): ",
            stream=err,
        ).strip()
    else:
        resolved_secret = ""

    verifier, challenge = generate_pkce_pair()
    state = generate_state()

    with OAuthCallbackServer(port=port, expected_state=state) as server:
        actual_port = int(server.server_address[1])
        redirect_uri = f"http://127.0.0.1:{actual_port}{CALLBACK_PATH}"
        auth_url = build_auth_url(
            client_id=resolved_id,
            redirect_uri=redirect_uri,
            code_challenge=challenge,
            state=state,
            auth_endpoint=auth_endpoint,
        )

        launched = False
        if open_browser:
            try:
                launched = bool(browser_opener(auth_url))
            except Exception:
                launched = False

        if not json_only:
            print(
                "Authorize Google Drive access in your browser (listening on "
                f"127.0.0.1:{actual_port}):\n  {auth_url}\n",
                file=err,
                flush=True,
            )
        elif not open_browser or not launched:
            print(auth_url, file=err, flush=True)

        code = wait_for_oauth_code(server, timeout_s=timeout_s)

    result = exchange_code_for_tokens(
        code=code,
        client_id=resolved_id,
        client_secret=resolved_secret,
        redirect_uri=redirect_uri,
        code_verifier=verifier,
        token_endpoint=token_endpoint,
        urlopen_fn=urlopen_fn,
    )

    if not json_only:
        print(
            "Paste the JSON below into Files -> + Add Server -> Google Drive -> Paste Token / JSON:",
            file=err,
            flush=True,
        )
        print(json.dumps(result, indent=2, ensure_ascii=False), file=out, flush=True)
    else:
        print(json.dumps(result, ensure_ascii=False), file=out, flush=True)

    return result
