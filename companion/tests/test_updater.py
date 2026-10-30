"""Unit & integration tests for PC Companion GitHub auto-updater (ARCHITECTURE §11.3)."""

from __future__ import annotations

import contextlib
import hashlib
import http.client
import io
import json
import os
import sys
import tempfile
import threading
import unittest
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

_COMPANION_ROOT = str(Path(__file__).resolve().parent.parent)
if _COMPANION_ROOT not in sys.path:
    sys.path.insert(0, _COMPANION_ROOT)

from minifile_companion.cli import EXIT_OK, EXIT_UPDATE_AVAILABLE, main  # noqa: E402
from minifile_companion.config import ConfigStore  # noqa: E402
from minifile_companion.updater import (  # noqa: E402
    SemanticVersion,
    UpdateError,
    UpdateNetworkError,
    UpdateSecurityError,
    _open_with_redirects,
    check_latest,
    check_with_cache,
    download_asset,
    fetch_sha256_for_asset,
    is_newer,
    maybe_emit_cached_update_hint,
    parse_version,
    stage_and_apply,
    verify_companion_zip,
)
from minifile_companion.webui import CompanionWebServer  # noqa: E402


def _make_companion_zip_bytes(
    version: str = "1.1.0",
    with_prefix: bool = True,
    extra_entries: list[tuple[str, bytes]] | None = None,
) -> bytes:
    buf = io.BytesIO()
    prefix = "companion/" if with_prefix else ""
    with zipfile.ZipFile(buf, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr(
            f"{prefix}minifile_companion/__init__.py",
            f'"""MiniFile."""\n__version__ = "{version}"\n',
        )
        zf.writestr(
            f"{prefix}minifile_companion/cli.py",
            "def main(argv=None):\n    return 0\n",
        )
        zf.writestr(f"{prefix}minifile.py", "#!/usr/bin/env python3\n")
        for ename, edata in extra_entries or []:
            zf.writestr(ename, edata)
    return buf.getvalue()


class _MockReleaseServer:
    """Configurable local HTTP server simulating GitHub Releases API & asset downloads."""

    def __init__(self) -> None:
        self.routes: dict[str, tuple[int, dict[str, str], bytes]] = {}
        self.request_counts: dict[str, int] = {}
        parent = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, fmt: str, *args: Any) -> None:
                pass

            def do_GET(self) -> None:  # noqa: N802
                parent.request_counts[self.path] = parent.request_counts.get(self.path, 0) + 1
                entry = parent.routes.get(self.path)
                if entry is None:
                    self.send_response(404)
                    self.send_header("Content-Length", "2")
                    self.end_headers()
                    self.wfile.write(b"{}")
                    return
                code, hdrs, body = entry
                self.send_response(code)
                for k, v in hdrs.items():
                    self.send_header(k, v)
                if "Content-Length" not in hdrs and code not in (301, 302, 303, 307, 308):
                    self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                if body:
                    self.wfile.write(body)

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.httpd.server_port
        self.base_url = f"http://127.0.0.1:{self.port}"
        self._thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)

    def __enter__(self) -> _MockReleaseServer:
        self._thread.start()
        return self

    def __exit__(self, exc_type: Any, exc: Any, tb: Any) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()
        self._thread.join(timeout=2.0)


class TestUpdater(unittest.TestCase):
    """Tests SemanticVersion, HTTP security, SHA-256, zip-slip/bomb guards, staging, CLI & Web."""

    def setUp(self) -> None:
        super().setUp()
        self._tmp_obj = tempfile.TemporaryDirectory(prefix="mf-updater-test-")
        self.tmp = Path(self._tmp_obj.name)
        self._orig_cfg = os.environ.get("MINIFILE_CONFIG")
        self._orig_cache = os.environ.get("MINIFILE_CACHE_DIR")
        self._orig_api = os.environ.get("MINIFILE_UPDATE_API_URL")
        os.environ["MINIFILE_CONFIG"] = str(self.tmp / "cfg" / "config.json")
        os.environ["MINIFILE_CACHE_DIR"] = str(self.tmp / "cache")

    def tearDown(self) -> None:
        for key, orig in (
            ("MINIFILE_CONFIG", self._orig_cfg),
            ("MINIFILE_CACHE_DIR", self._orig_cache),
            ("MINIFILE_UPDATE_API_URL", self._orig_api),
        ):
            if orig is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = orig
        self._tmp_obj.cleanup()
        super().tearDown()

    def test_semantic_version_grammar_and_ordering(self) -> None:
        self.assertEqual(parse_version("v1.2.3"), (1, 2, 3, None))
        self.assertEqual(parse_version("1.2"), (1, 2, 0, None))
        self.assertEqual(parse_version("V2.0.1-beta.1+build.9"), (2, 0, 1, "beta.1"))
        self.assertEqual(parse_version("1.0.0-DEBUG"), (1, 0, 0, None))

        for bad in ("", "abc", "1", "1.2.3.4", "1.2.0-beta..1", None):
            self.assertIsNone(parse_version(bad))

        self.assertTrue(is_newer("1.0.0", "v1.0.1"))
        self.assertTrue(is_newer("1.0.0", "1.1.0"))
        self.assertTrue(is_newer("1.1.0-alpha.1", "1.1.0-alpha.2"))
        self.assertTrue(is_newer("1.1.0-rc.1", "1.1.0"))
        self.assertFalse(is_newer("1.1.0", "1.1.0-rc.1"))
        self.assertFalse(is_newer("1.0.0", "1.0.0"))
        self.assertFalse(is_newer("1.0.0", "garbage"))
        self.assertGreater(SemanticVersion(1, 2, 0), SemanticVersion(1, 1, 9))

    def test_http_security_redirects_downgrade_and_loopback_flag(self) -> None:
        with _MockReleaseServer() as srv:
            # 1. Loopback HTTP rejected when allow_http_loopback=False (production default)
            with self.assertRaises(UpdateSecurityError):
                check_latest(
                    current="1.0.0",
                    allow_http_loopback=False,
                    api_base=srv.base_url,
                )

            # 2. Non-loopback HTTP ALWAYS rejected even when allow_http_loopback=True
            with self.assertRaises(UpdateSecurityError):
                _open_with_redirects(
                    "http://example.com/releases/latest",
                    allow_http_loopback=True,
                )

            # 3. Chain of 5 redirects succeeds; 6 redirects fails
            for i in range(5):
                srv.routes[f"/hop{i}"] = (302, {"Location": f"/hop{i + 1}"}, b"")
            srv.routes["/hop5"] = (200, {"Content-Type": "text/plain"}, b"ok")
            resp, final_url = _open_with_redirects(
                f"{srv.base_url}/hop0", allow_http_loopback=True
            )
            self.assertEqual(resp.read(), b"ok")
            resp.close()
            self.assertTrue(final_url.endswith("/hop5"))

            for i in range(6):
                srv.routes[f"/long{i}"] = (302, {"Location": f"/long{i + 1}"}, b"")
            srv.routes["/long6"] = (200, {}, b"too far")
            with self.assertRaises(UpdateSecurityError):
                _open_with_redirects(f"{srv.base_url}/long0", allow_http_loopback=True)

            # 4. HTTPS -> HTTP downgrade redirect rejected even with allow_http_loopback=True
            class FakeHttpsRedirectOpener:
                def open(self, req: Any, timeout: float = 15.0) -> Any:
                    class FakeResp:
                        status = 302
                        headers = {"Location": f"{srv.base_url}/hop5"}

                        def getcode(self) -> int:
                            return 302

                        def close(self) -> None:
                            pass

                    return FakeResp()

            with self.assertRaises(UpdateSecurityError):
                _open_with_redirects(
                    "https://api.github.com/repos/codescv/minifile/releases/latest",
                    opener=FakeHttpsRedirectOpener(),  # type: ignore[arg-type]
                    allow_http_loopback=True,
                )

    def test_release_parsing_list_fallback_prerelease_rate_limit_and_allowlist(self) -> None:
        with _MockReleaseServer() as srv:
            # /releases/latest returns 404 -> falls back to /releases?per_page=5
            releases_list = [
                {"tag_name": "v2.0.0-draft", "draft": True, "prerelease": False, "assets": []},
                {
                    "tag_name": "v1.3.0-beta.1",
                    "draft": False,
                    "prerelease": True,
                    "name": "Beta",
                    "body": "beta notes",
                    "assets": [
                        {
                            "name": "minifile-companion-v1.3.0-beta.1.zip",
                            "browser_download_url": f"{srv.base_url}/dl/comp-beta.zip",
                            "size": 100,
                        }
                    ],
                },
                {
                    "tag_name": "v1.2.0",
                    "draft": False,
                    "prerelease": False,
                    "name": "MiniFile v1.2.0",
                    "body": "stable notes",
                    "assets": [
                        # Foreign host asset must be ignored by allow-list!
                        {
                            "name": "minifile-companion-v1.2.0.zip",
                            "browser_download_url": "https://evil.attacker.invalid/minifile-companion-v1.2.0.zip",
                            "size": 999,
                        },
                        {
                            "name": "minifile-companion-v1.2.0.zip",
                            "browser_download_url": f"{srv.base_url}/dl/minifile-companion-v1.2.0.zip",
                            "size": 456,
                        },
                    ],
                },
            ]
            srv.routes["/releases?per_page=5"] = (
                200,
                {"Content-Type": "application/json"},
                json.dumps(releases_list).encode("utf-8"),
            )

            stable = check_latest(
                current="1.0.0",
                include_pre=False,
                allow_http_loopback=True,
                api_base=srv.base_url,
            )
            self.assertTrue(stable.available)
            self.assertEqual(stable.latest_tag, "v1.2.0")
            self.assertEqual(
                stable.asset_url, f"{srv.base_url}/dl/minifile-companion-v1.2.0.zip"
            )
            self.assertEqual(stable.asset_size, 456)

            pre = check_latest(
                current="1.0.0",
                include_pre=True,
                allow_http_loopback=True,
                api_base=srv.base_url,
            )
            self.assertTrue(pre.available)
            self.assertEqual(pre.latest_tag, "v1.3.0-beta.1")

            # Rate-limit 403 with X-RateLimit-Remaining: 0 -> UpdateNetworkError
            srv.routes["/releases/latest"] = (
                403,
                {"X-RateLimit-Remaining": "0"},
                b'{"message":"rate limit"}',
            )
            with self.assertRaises(UpdateNetworkError):
                check_latest(
                    current="1.0.0",
                    allow_http_loopback=True,
                    api_base=srv.base_url,
                )

    def test_download_size_and_sha256_mismatch_delete_tmp(self) -> None:
        zip_bytes = _make_companion_zip_bytes("1.1.0")
        good_sha = hashlib.sha256(zip_bytes).hexdigest()

        with _MockReleaseServer() as srv:
            srv.routes["/dl/comp.zip"] = (
                200,
                {"Content-Type": "application/zip"},
                zip_bytes,
            )
            dl_dir = self.tmp / "downloads"

            # Wrong expected size -> raises & cleans up .tmp
            with self.assertRaises(UpdateSecurityError):
                download_asset(
                    f"{srv.base_url}/dl/comp.zip",
                    dl_dir,
                    expected_size=len(zip_bytes) + 99,
                    allow_http_loopback=True,
                )
            self.assertEqual(list(dl_dir.glob("*.tmp")), [])

            # Wrong SHA-256 -> raises & cleans up .tmp
            with self.assertRaises(UpdateSecurityError):
                download_asset(
                    f"{srv.base_url}/dl/comp.zip",
                    dl_dir,
                    expected_size=len(zip_bytes),
                    sha256="0" * 64,
                    allow_http_loopback=True,
                )
            self.assertEqual(list(dl_dir.glob("*.tmp")), [])
            self.assertFalse((dl_dir / "comp.zip").exists())

            # Matching size & SHA-256 succeeds
            saved = download_asset(
                f"{srv.base_url}/dl/comp.zip",
                dl_dir,
                expected_size=len(zip_bytes),
                sha256=good_sha,
                allow_http_loopback=True,
            )
            self.assertTrue(saved.is_file())
            self.assertEqual(verify_companion_zip(saved), "1.1.0")

    def test_verify_companion_zip_rejects_zipslip_and_zipbomb_and_accepts_both_layouts(self) -> None:
        # Both canonical `companion/` prefix and root-level layouts pass
        z_prefixed = self.tmp / "prefixed.zip"
        z_prefixed.write_bytes(_make_companion_zip_bytes("1.2.3", with_prefix=True))
        self.assertEqual(verify_companion_zip(z_prefixed), "1.2.3")

        z_flat = self.tmp / "flat.zip"
        z_flat.write_bytes(_make_companion_zip_bytes("1.2.4", with_prefix=False))
        self.assertEqual(verify_companion_zip(z_flat), "1.2.4")

        # Zip-slip entries must abort the whole update
        for unsafe_entry in (
            "../evil.py",
            "companion/../../evil.py",
            "/etc/passwd",
            "companion\\back.py",
            "C:evil.py",
        ):
            bad_zip = self.tmp / "unsafe.zip"
            bad_zip.write_bytes(
                _make_companion_zip_bytes("1.2.0", extra_entries=[(unsafe_entry, b"x")])
            )
            with self.assertRaises(UpdateSecurityError):
                verify_companion_zip(bad_zip)

        # Raw NUL byte inside ZIP filename header (bypassing Python zipfile's NUL truncation)
        nul_zip = self.tmp / "raw_nul.zip"
        nul_bytes = _make_companion_zip_bytes(
            "1.2.0", extra_entries=[("companion/nul_X.py", b"x")]
        ).replace(b"nul_X.py", b"nul\x00X.py")
        nul_zip.write_bytes(nul_bytes)
        with self.assertRaises(UpdateSecurityError):
            verify_companion_zip(nul_zip)

        # Zip-bomb header (> 200 MiB declared file_size) must abort
        bomb_zip = self.tmp / "bomb.zip"
        raw_bytes = bytearray(_make_companion_zip_bytes("1.2.0"))
        with zipfile.ZipFile(bomb_zip, "w") as zf:
            zf.writestr("companion/minifile_companion/__init__.py", '__version__ = "1.2.0"\n')
        # Craft a ZipInfo with >200 MiB declared uncompressed size via monkeypatch check
        orig_infolist = zipfile.ZipFile.infolist

        def _fake_huge_infolist(zf_self: zipfile.ZipFile) -> list[zipfile.ZipInfo]:
            infos = orig_infolist(zf_self)
            infos[0].file_size = 250 * 1024 * 1024
            return infos

        zipfile.ZipFile.infolist = _fake_huge_infolist  # type: ignore[method-assign]
        try:
            with self.assertRaises(UpdateSecurityError):
                verify_companion_zip(bomb_zip)
        finally:
            zipfile.ZipFile.infolist = orig_infolist  # type: ignore[method-assign]
        _ = raw_bytes

    def test_stage_and_apply_and_git_checkout_refusal(self) -> None:
        zip_file = self.tmp / "minifile-companion-v1.1.0.zip"
        zip_file.write_bytes(_make_companion_zip_bytes("1.1.0", with_prefix=True))

        # 1. Stage-only (`apply=False`) creates sibling companion-v1.1.0 without touching live dir
        workspace = self.tmp / "plain_install"
        live_comp = workspace / "companion"
        (live_comp / "minifile_companion").mkdir(parents=True)
        (live_comp / "minifile_companion" / "__init__.py").write_text(
            '__version__ = "1.0.0"\n', encoding="utf-8"
        )

        staged = stage_and_apply(zip_file, live_comp, apply=False, current_version="1.0.0")
        self.assertEqual(staged, workspace / "companion-v1.1.0")
        self.assertIn(
            "1.1.0",
            (staged / "minifile_companion" / "__init__.py").read_text(encoding="utf-8"),
        )
        self.assertIn(
            "1.0.0",
            (live_comp / "minifile_companion" / "__init__.py").read_text(encoding="utf-8"),
        )

        # 2. `apply=True` swaps in a plain non-git folder and keeps backup
        applied = stage_and_apply(zip_file, live_comp, apply=True, current_version="1.0.0")
        self.assertEqual(applied, live_comp)
        self.assertIn(
            "1.1.0",
            (live_comp / "minifile_companion" / "__init__.py").read_text(encoding="utf-8"),
        )
        self.assertTrue((workspace / "companion-v1.0.0.bak").is_dir())

        # 3. `apply=True` inside a git checkout refuses to swap running tree!
        git_repo = self.tmp / "git_checkout"
        (git_repo / ".git").mkdir(parents=True)
        git_comp = git_repo / "companion"
        (git_comp / "minifile_companion").mkdir(parents=True)
        (git_comp / "minifile_companion" / "__init__.py").write_text(
            '__version__ = "1.0.0"\n', encoding="utf-8"
        )
        with self.assertRaises(UpdateError):
            stage_and_apply(zip_file, git_comp, apply=True, current_version="1.0.0")
        # Live git checkout untouched, while sibling staged folder exists
        self.assertIn(
            "1.0.0",
            (git_comp / "minifile_companion" / "__init__.py").read_text(encoding="utf-8"),
        )
        self.assertTrue((git_repo / "companion-v1.1.0").is_dir())

    def test_cli_update_exit_10_full_download_webui_throttle_and_hint(self) -> None:
        zip_bytes = _make_companion_zip_bytes("1.1.0", with_prefix=True)
        sha_hex = hashlib.sha256(zip_bytes).hexdigest()
        sums_body = (
            f"{'a' * 64}  minifile-v1.1.0-release.apk\n"
            f"{sha_hex}  minifile-companion-v1.1.0.zip\n"
        ).encode("utf-8")

        with _MockReleaseServer() as srv:
            release_obj = {
                "tag_name": "v1.1.0",
                "name": "MiniFile v1.1.0",
                "body": "- Faster sync\n- Auto-update support",
                "html_url": "https://github.com/codescv/minifile/releases/tag/v1.1.0",
                "published_at": "2026-10-30T20:00:00Z",
                "draft": False,
                "prerelease": False,
                "assets": [
                    {
                        "name": "minifile-companion-v1.1.0.zip",
                        "browser_download_url": f"{srv.base_url}/dl/minifile-companion-v1.1.0.zip",
                        "size": len(zip_bytes),
                    },
                    {
                        "name": "SHA256SUMS.txt",
                        "browser_download_url": f"{srv.base_url}/dl/SHA256SUMS.txt",
                        "size": len(sums_body),
                    },
                ],
            }
            srv.routes["/releases/latest"] = (
                200,
                {"Content-Type": "application/json"},
                json.dumps(release_obj).encode("utf-8"),
            )
            srv.routes["/dl/minifile-companion-v1.1.0.zip"] = (
                200,
                {"Content-Type": "application/zip"},
                zip_bytes,
            )
            srv.routes["/dl/SHA256SUMS.txt"] = (
                200,
                {"Content-Type": "text/plain"},
                sums_body,
            )

            # Verify SHA256SUMS helper
            self.assertEqual(
                fetch_sha256_for_asset(
                    f"{srv.base_url}/dl/SHA256SUMS.txt",
                    "minifile-companion-v1.1.0.zip",
                    allow_http_loopback=True,
                ),
                sha_hex,
            )

            os.environ["MINIFILE_UPDATE_API_URL"] = srv.base_url

            # 1. CLI `update --check` returns exit code 10 when update is available
            out_chk = io.StringIO()
            with contextlib.redirect_stdout(out_chk):
                rc_chk = main(["update", "--check", "--allow-http-loopback"])
            self.assertEqual(rc_chk, EXIT_UPDATE_AVAILABLE)
            self.assertIn("v1.1.0", out_chk.getvalue())

            # 2. CLI `update` stages companion-v1.1.0 next to install_dir and exits 0
            fake_install = self.tmp / "cli_install" / "companion"
            fake_install.mkdir(parents=True)
            out_apply = io.StringIO()
            with contextlib.redirect_stdout(out_apply):
                rc_up = main(
                    [
                        "update",
                        "--allow-http-loopback",
                        "--dest",
                        str(self.tmp / "dl_cache"),
                        "--install-dir",
                        str(fake_install),
                        "--json",
                    ]
                )
            self.assertEqual(rc_up, EXIT_OK)
            up_json = json.loads(out_apply.getvalue())
            self.assertTrue(up_json["updated"])
            self.assertTrue(
                (self.tmp / "cli_install" / "companion-v1.1.0" / "minifile_companion" / "__init__.py").is_file()
            )

            # 3. 24-hour cached hint throttling
            store = ConfigStore(self.tmp / "hint-config.json")
            fake_clock = [1_000_000.0]
            check_with_cache(
                store,
                force=True,
                current="1.0.0",
                allow_http_loopback=True,
                api_base=srv.base_url,
                now_fn=lambda: fake_clock[0],
            )
            hint_buf1 = io.StringIO()
            self.assertTrue(
                maybe_emit_cached_update_hint(
                    store, current="1.0.0", stream=hint_buf1, now_fn=lambda: fake_clock[0]
                )
            )
            self.assertIn("v1.1.0", hint_buf1.getvalue())
            # Second call 1 hour later is suppressed
            fake_clock[0] += 3600.0
            hint_buf2 = io.StringIO()
            self.assertFalse(
                maybe_emit_cached_update_hint(
                    store, current="1.0.0", stream=hint_buf2, now_fn=lambda: fake_clock[0]
                )
            )
            # After 25 hours, hint prints again
            fake_clock[0] += 25 * 3600.0
            hint_buf3 = io.StringIO()
            self.assertTrue(
                maybe_emit_cached_update_hint(
                    store, current="1.0.0", stream=hint_buf3, now_fn=lambda: fake_clock[0]
                )
            )

            # 4. Web UI `/api/update` schema + 60-second `force=1` rate limiting
            web = CompanionWebServer(
                host="127.0.0.1",
                port=0,
                config_path=self.tmp / "web-upd-cfg.json",
                update_api_base=srv.base_url,
                update_allow_http=True,
            ).start_background()
            try:
                before_hits = srv.request_counts.get("/releases/latest", 0)
                conn = http.client.HTTPConnection("127.0.0.1", web.port, timeout=5)
                hdrs = {"Host": f"127.0.0.1:{web.port}", "X-MF-Token": web.token}

                conn.request("GET", "/api/update?force=1", headers=hdrs)
                r1 = conn.getresponse()
                d1 = json.loads(r1.read().decode("utf-8"))
                self.assertEqual(r1.status, 200)
                self.assertTrue(d1["available"])
                self.assertEqual(d1["latest"], "v1.1.0")
                self.assertFalse(d1["throttled"])
                self.assertEqual(
                    srv.request_counts.get("/releases/latest", 0), before_hits + 1
                )

                # Immediate second force=1 within 60 s -> throttled=True, no extra GitHub call!
                conn.request("GET", "/api/update?force=1", headers=hdrs)
                r2 = conn.getresponse()
                d2 = json.loads(r2.read().decode("utf-8"))
                self.assertEqual(r2.status, 200)
                self.assertTrue(d2["throttled"])
                self.assertEqual(
                    srv.request_counts.get("/releases/latest", 0), before_hits + 1
                )
                conn.close()
            finally:
                web.shutdown()


if __name__ == "__main__":
    unittest.main()
