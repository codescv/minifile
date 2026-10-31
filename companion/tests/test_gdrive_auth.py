"""Unit tests for Google Drive OAuth 2.0 loopback + PKCE helper (`minifile gdrive-auth`)."""

from __future__ import annotations

import datetime
import io
import json
import sys
import threading
import unittest
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from unittest import mock

_COMPANION_ROOT = str(Path(__file__).resolve().parent.parent)
if _COMPANION_ROOT not in sys.path:
    sys.path.insert(0, _COMPANION_ROOT)

from minifile_companion.cli import EXIT_CONN_AUTH, EXIT_OK, main  # noqa: E402
from minifile_companion.gdrive_auth import (  # noqa: E402
    DRIVE_SCOPE,
    GDriveAuthError,
    OAuthCallbackServer,
    build_auth_url,
    compute_s256_challenge,
    exchange_code_for_tokens,
    generate_pkce_pair,
    generate_state,
    run_gdrive_auth,
    wait_for_oauth_code,
)


class _FakeHttpResponse:
    def __init__(self, payload: dict[str, object]) -> None:
        self._raw = json.dumps(payload).encode("utf-8")

    def read(self) -> bytes:
        return self._raw

    def __enter__(self) -> _FakeHttpResponse:
        return self

    def __exit__(self, exc_type: object, exc: object, tb: object) -> bool:
        return False


class GDriveAuthTests(unittest.TestCase):
    def test_pkce_s256_rfc7636_vector_and_pair_shape(self) -> None:
        # RFC 7636 Appendix B test vector
        rfc_verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        self.assertEqual(
            compute_s256_challenge(rfc_verifier),
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
        )

        verifier, challenge = generate_pkce_pair()
        self.assertEqual(len(verifier), 43)
        self.assertNotIn("=", verifier)
        self.assertEqual(challenge, compute_s256_challenge(verifier))

        state = generate_state()
        self.assertGreaterEqual(len(state), 21)
        self.assertNotIn("=", state)

    def test_build_auth_url_contains_offline_consent_and_s256(self) -> None:
        url = build_auth_url(
            client_id="client-id.apps.example",
            redirect_uri="http://127.0.0.1:54321/oauth2callback",
            code_challenge="chal_test_123",
            state="state_test_456",
        )
        parsed = urllib.parse.urlsplit(url)
        qs = urllib.parse.parse_qs(parsed.query)
        self.assertEqual(qs["client_id"], ["client-id.apps.example"])
        self.assertEqual(qs["redirect_uri"], ["http://127.0.0.1:54321/oauth2callback"])
        self.assertEqual(qs["response_type"], ["code"])
        self.assertEqual(qs["scope"], [DRIVE_SCOPE])
        self.assertEqual(qs["code_challenge"], ["chal_test_123"])
        self.assertEqual(qs["code_challenge_method"], ["S256"])
        self.assertEqual(qs["state"], ["state_test_456"])
        self.assertEqual(qs["access_type"], ["offline"])
        self.assertEqual(qs["prompt"], ["consent"])

    def test_callback_server_ignores_favicon_and_wrong_state_and_never_echoes_query(self) -> None:
        expected_state = "state_good_xyz"
        secret_marker = "XSS_MARKER_DO_NOT_ECHO"
        with OAuthCallbackServer(port=0, expected_state=expected_state) as server:
            port = server.server_address[1]
            base = f"http://127.0.0.1:{port}"

            def simulate_browser() -> None:
                # 1. /favicon.ico -> 404
                with self.assertRaises(urllib.error.HTTPError) as fav_ctx:
                    urllib.request.urlopen(f"{base}/favicon.ico", timeout=3.0)
                self.assertEqual(fav_ctx.exception.code, 404)
                fav_ctx.exception.close()

                # 2. Wrong state -> 400, server keeps waiting
                with self.assertRaises(urllib.error.HTTPError) as bad_ctx:
                    urllib.request.urlopen(
                        f"{base}/oauth2callback?code=wrong_code&state=wrong_{secret_marker}",
                        timeout=3.0,
                    )
                self.assertEqual(bad_ctx.exception.code, 400)
                bad_body = bad_ctx.exception.read().decode("utf-8", errors="replace")
                self.assertNotIn(secret_marker, bad_body)
                bad_ctx.exception.close()

                # 3. Valid callback -> 200 fixed HTML (no echoed query values)
                with urllib.request.urlopen(
                    f"{base}/oauth2callback?code=code_{secret_marker}&state={expected_state}",
                    timeout=3.0,
                ) as ok_resp:
                    self.assertEqual(ok_resp.status, 200)
                    ok_body = ok_resp.read().decode("utf-8", errors="replace")
                    self.assertNotIn(secret_marker, ok_body)
                    self.assertNotIn(expected_state, ok_body)

            t = threading.Thread(target=simulate_browser, daemon=True)
            t.start()
            code = wait_for_oauth_code(server, timeout_s=5.0)
            t.join(timeout=3.0)
            self.assertEqual(code, f"code_{secret_marker}")

    def test_callback_server_handles_access_denied(self) -> None:
        expected_state = "state_deny_1"
        with OAuthCallbackServer(port=0, expected_state=expected_state) as server:
            port = server.server_address[1]

            def simulate_denial() -> None:
                with self.assertRaises(urllib.error.HTTPError) as ctx:
                    urllib.request.urlopen(
                        f"http://127.0.0.1:{port}/oauth2callback?error=access_denied&state={expected_state}",
                        timeout=3.0,
                    )
                self.assertEqual(ctx.exception.code, 400)
                ctx.exception.close()

            t = threading.Thread(target=simulate_denial, daemon=True)
            t.start()
            with self.assertRaises(GDriveAuthError) as err_ctx:
                wait_for_oauth_code(server, timeout_s=5.0)
            t.join(timeout=3.0)
            self.assertIn("denied", str(err_ctx.exception).lower())

    def test_exchange_code_for_tokens_with_mocked_urlopen(self) -> None:
        captured: dict[str, object] = {}

        def fake_urlopen(req: urllib.request.Request, timeout: float = 15.0) -> _FakeHttpResponse:
            captured["url"] = req.full_url
            captured["method"] = req.get_method()
            captured["body"] = (req.data or b"").decode("utf-8")
            captured["timeout"] = timeout
            return _FakeHttpResponse(
                {
                    "access_token": "tok_test_access_token_value_12345",
                    "refresh_token": "refresh_test_value_67890",
                    "expires_in": 3600,
                    "token_type": "Bearer",
                    "scope": DRIVE_SCOPE,
                }
            )

        fixed_now = datetime.datetime(2026, 10, 31, 6, 0, 0, tzinfo=datetime.timezone.utc)
        tokens = exchange_code_for_tokens(
            code="auth_code_test_1",
            client_id="client-id.apps.example",
            client_secret="secret_test_value",
            redirect_uri="http://127.0.0.1:43210/oauth2callback",
            code_verifier="verifier_test_value_12345678901234567890123",
            token_endpoint="https://token.example/oauth2/token",
            urlopen_fn=fake_urlopen,
            now_utc=fixed_now,
        )

        self.assertEqual(captured["method"], "POST")
        form = urllib.parse.parse_qs(str(captured["body"]))
        self.assertEqual(form["code"], ["auth_code_test_1"])
        self.assertEqual(form["client_id"], ["client-id.apps.example"])
        self.assertEqual(form["client_secret"], ["secret_test_value"])
        self.assertEqual(form["grant_type"], ["authorization_code"])
        self.assertEqual(
            form["code_verifier"], ["verifier_test_value_12345678901234567890123"]
        )

        expected_keys = {
            "access_token",
            "refresh_token",
            "expiry",
            "client_id",
            "client_secret",
            "token_type",
            "scope",
        }
        self.assertEqual(set(tokens.keys()), expected_keys)
        self.assertEqual(tokens["access_token"], "tok_test_access_token_value_12345")
        self.assertEqual(tokens["refresh_token"], "refresh_test_value_67890")
        self.assertEqual(tokens["expiry"], "2026-10-31T07:00:00Z")
        self.assertEqual(tokens["client_id"], "client-id.apps.example")
        self.assertEqual(tokens["client_secret"], "secret_test_value")
        self.assertEqual(tokens["token_type"], "Bearer")
        self.assertEqual(tokens["scope"], DRIVE_SCOPE)

    def test_end_to_end_run_gdrive_auth_and_cli_subcommand(self) -> None:
        def fake_browser(auth_url: str) -> bool:
            qs = urllib.parse.parse_qs(urllib.parse.urlsplit(auth_url).query)
            redirect_uri = qs["redirect_uri"][0]
            state = qs["state"][0]

            def hit_callback() -> None:
                urllib.request.urlopen(
                    f"{redirect_uri}?code=code_from_browser&state={state}",
                    timeout=3.0,
                ).close()

            threading.Thread(target=hit_callback, daemon=True).start()
            return True

        def fake_urlopen(req: urllib.request.Request, timeout: float = 15.0) -> _FakeHttpResponse:
            form = urllib.parse.parse_qs((req.data or b"").decode("utf-8"))
            self.assertEqual(form["code"], ["code_from_browser"])
            return _FakeHttpResponse(
                {
                    "access_token": "tok_test_cli_flow_token_abcdef1234",
                    "refresh_token": "refresh_test_cli_flow_999",
                    "expires_in": 1800,
                    "token_type": "Bearer",
                    "scope": DRIVE_SCOPE,
                }
            )

        out = io.StringIO()
        err = io.StringIO()
        res = run_gdrive_auth(
            client_id="client-id.apps.example",
            client_secret="secret_test_cli",
            port=0,
            open_browser=True,
            json_only=True,
            timeout_s=5.0,
            urlopen_fn=fake_urlopen,
            browser_opener=fake_browser,
            stdout=out,
            stderr=err,
        )
        parsed_stdout = json.loads(out.getvalue())
        self.assertEqual(parsed_stdout, res)
        self.assertEqual(parsed_stdout["access_token"], "tok_test_cli_flow_token_abcdef1234")
        self.assertEqual(parsed_stdout["refresh_token"], "refresh_test_cli_flow_999")

        # Also verify CLI subcommand dispatch and missing client-id exit code
        with (
            mock.patch.dict("os.environ", {"MINIFILE_GDRIVE_CLIENT_ID": ""}, clear=False),
            mock.patch("sys.stdin.isatty", return_value=False),
            mock.patch("sys.stderr", new_callable=io.StringIO),
        ):
            exit_code = main(["gdrive-auth", "--no-browser", "--json-only"])
            self.assertEqual(exit_code, EXIT_CONN_AUTH)

        with (
            mock.patch(
                "minifile_companion.cli.run_gdrive_auth",
                return_value=res,
            ) as mocked_run,
        ):
            code = main(
                [
                    "gdrive-auth",
                    "--client-id",
                    "client-id.apps.example",
                    "--client-secret",
                    "secret_test_cli",
                    "--no-browser",
                    "--json-only",
                ]
            )
            self.assertEqual(code, EXIT_OK)
            mocked_run.assert_called_once_with(
                client_id="client-id.apps.example",
                client_secret="secret_test_cli",
                port=0,
                open_browser=False,
                json_only=True,
            )


if __name__ == "__main__":
    unittest.main()
