"""Automated Web UI & JSON API tests (ARCHITECTURE §6.3, §8.3, §8.5)."""

from __future__ import annotations

import http.client
import io
import json
import os
import sys
import time
import unittest
import urllib.parse
import zipfile
from pathlib import Path
from typing import Any

_COMPANION_ROOT = str(Path(__file__).resolve().parent.parent)
if _COMPANION_ROOT not in sys.path:
    sys.path.insert(0, _COMPANION_ROOT)

from minifile_companion.webui import CompanionWebServer  # noqa: E402
from tests.ftp_fixture import EmbeddedFtpServer, FtpServerTestCase  # noqa: E402


def _build_multipart_body(
    fields: list[tuple[str, str]],
    files: list[tuple[str, str, bytes]],
    boundary: str = "----MiniFileTestBoundary987654321",
) -> tuple[bytes, str]:
    chunks: list[bytes] = []
    b_bytes = boundary.encode("ascii")
    for name, val in fields:
        chunks.append(b"--" + b_bytes + b"\r\n")
        chunks.append(
            f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode("utf-8")
        )
        chunks.append(val.encode("utf-8") + b"\r\n")
    for field_name, filename, data in files:
        chunks.append(b"--" + b_bytes + b"\r\n")
        chunks.append(
            f'Content-Disposition: form-data; name="{field_name}"; filename="{filename}"\r\n'
            f"Content-Type: application/octet-stream\r\n\r\n".encode("utf-8")
        )
        chunks.append(data + b"\r\n")
    chunks.append(b"--" + b_bytes + b"--\r\n")
    return b"".join(chunks), f"multipart/form-data; boundary={boundary}"


class TestWebUi(FtpServerTestCase):
    """Tests Web UI security gates and every /api/* endpoint."""

    web: CompanionWebServer
    jail_dir: Path

    def setUp(self) -> None:
        super().setUp()
        self.jail_dir = self.local_tmp / "pc_jail"
        self.jail_dir.mkdir(parents=True)
        (self.jail_dir / "pc_hello.txt").write_text("hello from pc", encoding="utf-8")

        self.web = CompanionWebServer(
            host="127.0.0.1",
            port=0,
            jail=self.jail_dir,
            config_path=self.local_tmp / "webui-config.json",
        ).start_background()

    def tearDown(self) -> None:
        self.web.shutdown()
        super().tearDown()

    def raw_request(
        self,
        method: str,
        path: str,
        body: bytes | None = None,
        headers: dict[str, str] | None = None,
        include_token: bool = True,
    ) -> tuple[int, dict[str, str], bytes]:
        conn = http.client.HTTPConnection("127.0.0.1", self.web.port, timeout=15)
        try:
            hdrs: dict[str, str] = {
                "Host": f"127.0.0.1:{self.web.port}",
                "Connection": "close",
            }
            if include_token:
                hdrs["X-MF-Token"] = self.web.token
            if headers:
                hdrs.update(headers)
            conn.request(method, path, body=body, headers=hdrs)
            resp = conn.getresponse()
            data = resp.read()
            resp_hdrs = {k.lower(): v for k, v in resp.getheaders()}
            return resp.status, resp_hdrs, data
        finally:
            conn.close()

    def api_json(
        self,
        method: str,
        path: str,
        payload: Any = None,
        headers: dict[str, str] | None = None,
    ) -> tuple[int, Any]:
        body: bytes | None = None
        hdrs = dict(headers or {})
        if payload is not None:
            body = json.dumps(payload).encode("utf-8")
            hdrs["Content-Type"] = "application/json"
        status, _, raw = self.raw_request(method, path, body=body, headers=hdrs)
        return status, json.loads(raw.decode("utf-8"))

    def test_no_cgi_imported_anywhere_on_python_313(self) -> None:
        self.assertNotIn("cgi", sys.modules)
        pkg_dir = Path(_COMPANION_ROOT) / "minifile_companion"
        for py_file in pkg_dir.glob("*.py"):
            src = py_file.read_text(encoding="utf-8")
            self.assertNotRegex(src, r"(?m)^\s*(import\s+cgi|from\s+cgi\s+import)")

    def test_security_token_host_origin_cookie_and_static_traversal(self) -> None:
        # 1. Missing X-MF-Token -> 401
        st_no_tok, _, _ = self.raw_request("GET", "/api/status", include_token=False)
        self.assertEqual(st_no_tok, 401)

        # 2. Cookie alone is REJECTED on /api/status (header-only except GET /api/download)
        st_cookie_only, _, _ = self.raw_request(
            "GET",
            "/api/status",
            include_token=False,
            headers={"Cookie": f"mf_token={self.web.token}"},
        )
        self.assertEqual(st_cookie_only, 401)

        # 3. Bad Host header -> 403 (DNS rebinding protection)
        st_bad_host, _, _ = self.raw_request(
            "GET", "/api/status", headers={"Host": "evil.attacker.invalid"}
        )
        self.assertEqual(st_bad_host, 403)

        # 4. Foreign Origin on state-changing POST -> 403
        st_bad_origin, _ = self.api_json(
            "POST",
            "/api/local/mkdir",
            {"path": str(self.jail_dir), "name": "csrf"},
            headers={"Origin": "http://evil.example.com"},
        )
        self.assertEqual(st_bad_origin, 403)
        self.assertFalse((self.jail_dir / "csrf").exists())

        # Valid localhost Origin succeeds
        st_ok_origin, _ = self.api_json(
            "POST",
            "/api/local/mkdir",
            {"path": str(self.jail_dir), "name": "legit_dir"},
            headers={"Origin": f"http://127.0.0.1:{self.web.port}"},
        )
        self.assertEqual(st_ok_origin, 200)

        # 5. Root HTML requires ?t= or cookie, sets HttpOnly SameSite=Strict cookie
        st_root_unauth, _, _ = self.raw_request("GET", "/", include_token=False)
        self.assertEqual(st_root_unauth, 401)

        st_root_ok, root_hdrs, root_html = self.raw_request(
            "GET", f"/?t={self.web.token}", include_token=False
        )
        self.assertEqual(st_root_ok, 200)
        self.assertIn("mf_token=", root_hdrs.get("set-cookie", ""))
        self.assertIn("httponly", root_hdrs.get("set-cookie", "").lower())
        self.assertIn(b"MiniFile Companion", root_html)

        # Static JS/CSS served cleanly; path traversal attempts return 404
        st_js, _, js_bytes = self.raw_request("GET", "/static/app.js", include_token=False)
        self.assertEqual(st_js, 200)
        self.assertIn(b"X-MF-Token", js_bytes)

        for trav in (
            "/static/../webui.py",
            "/static/..%2fwebui.py",
            "/..%2f..%2fetc%2fpasswd",
        ):
            st_trav, _, _ = self.raw_request("GET", trav, include_token=False)
            self.assertEqual(st_trav, 404)

    def test_jail_confinement_blocks_local_escape(self) -> None:
        outside_file = self.local_tmp / "outside_secret.txt"
        outside_file.write_text("do not read", encoding="utf-8")

        st1, _ = self.api_json(
            "GET", f"/api/local/list?path={urllib.parse.quote(str(self.local_tmp))}"
        )
        self.assertEqual(st1, 403)

        st2, _ = self.api_json(
            "GET", f"/api/local/text?path={urllib.parse.quote(str(outside_file))}"
        )
        self.assertEqual(st2, 403)

        st3, _ = self.api_json(
            "PUT",
            "/api/local/text",
            {"path": str(outside_file), "text": "pwn", "encoding": "utf-8"},
        )
        self.assertEqual(st3, 403)

    def test_full_api_lifecycle_connect_crud_text_favorites_and_scan(self) -> None:
        # Scan
        st_scan, scan_res = self.api_json(
            "GET", f"/api/scan?subnet={self.host}/32&port={self.port}"
        )
        self.assertEqual(st_scan, 200)
        self.assertEqual(len(scan_res), 1)

        # Connect
        st_conn, conn_res = self.api_json(
            "POST",
            "/api/connect",
            {"host": self.host, "port": self.port, "user": "anonymous", "password": "x", "remember": True},
        )
        self.assertEqual(st_conn, 200)
        self.assertTrue(conn_res["connected"])

        # Phone mkdir, text write/read, nomedia, rename, list, delete
        self.assertEqual(
            self.api_json("POST", "/api/phone/mkdir", {"path": "/", "name": "Albums"})[0],
            200,
        )
        st_put, put_res = self.api_json(
            "PUT",
            "/api/phone/text",
            {"path": "/Albums/readme.md", "text": "Hello WebUI 🌟\n", "encoding": "utf-8"},
        )
        self.assertEqual(st_put, 200)
        self.assertGreater(put_res["size"], 10)

        st_get_txt, txt_res = self.api_json("GET", "/api/phone/text?path=/Albums/readme.md")
        self.assertEqual(st_get_txt, 200)
        self.assertEqual(txt_res["text"], "Hello WebUI 🌟\n")

        st_nm, nm_res = self.api_json(
            "POST", "/api/phone/nomedia", {"path": "/Albums", "state": True}
        )
        self.assertEqual(st_nm, 200)
        self.assertTrue(nm_res["state"])

        st_list, list_res = self.api_json("GET", "/api/phone/list?path=/&hidden=1")
        self.assertEqual(st_list, 200)
        albums_entry = next(e for e in list_res["entries"] if e["name"] == "Albums")
        self.assertTrue(albums_entry["has_nomedia"])

        self.assertEqual(
            self.api_json(
                "POST",
                "/api/phone/rename",
                {"path": "/Albums/readme.md", "new_name": "info.md"},
            )[0],
            200,
        )

        # Local CRUD & text
        self.assertEqual(
            self.api_json(
                "PUT",
                "/api/local/text",
                {"path": str(self.jail_dir / "note.txt"), "text": "local edit", "encoding": "utf-8"},
            )[0],
            200,
        )
        self.assertEqual(
            self.api_json(
                "GET",
                f"/api/local/text?path={urllib.parse.quote(str(self.jail_dir / 'note.txt'))}",
            )[1]["text"],
            "local edit",
        )
        self.assertEqual(
            self.api_json(
                "POST",
                "/api/local/rename",
                {"path": str(self.jail_dir / "note.txt"), "new_name": "note2.txt"},
            )[0],
            200,
        )
        self.assertEqual(
            self.api_json(
                "POST",
                "/api/local/delete",
                {"paths": [str(self.jail_dir / "note2.txt")], "recursive": False},
            )[1]["deleted"],
            1,
        )

        # Favorites CRUD
        new_favs = [{"name": "Albums", "path": "/Albums"}]
        st_fav, saved_favs = self.api_json("PUT", "/api/favorites", new_favs)
        self.assertEqual(st_fav, 200)
        self.assertEqual(saved_favs, new_favs)
        self.assertEqual(self.api_json("GET", "/api/favorites")[1], new_favs)

        # Delete remote folder & disconnect
        del_status, del_res = self.api_json(
            "POST", "/api/phone/delete", {"paths": ["/Albums"], "recursive": True}
        )
        self.assertEqual(del_status, 200)
        self.assertEqual(del_res["deleted"], 1)

        st_disc, disc_res = self.api_json("POST", "/api/disconnect", {})
        self.assertEqual(st_disc, 200)
        self.assertFalse(disc_res["connected"])

    def test_streaming_multipart_upload_large_binary_utf8_and_download_zip(self) -> None:
        self.api_json("POST", "/api/connect", {"host": self.host, "port": self.port})

        # > 5.5 MiB binary payload containing tricky "\r\n--" subsequences + UTF-8 filename
        large_payload = (b"A" * 4096 + b"\r\n--fakeboundary\r\n" + os.urandom(1024)) * 1120
        self.assertGreater(len(large_payload), 5 * 1024 * 1024)
        utf8_fname = "测试 файл ✓ (1).txt"
        utf8_bytes = "UTF-8 multipart content ✓\n".encode("utf-8")

        body, ctype = _build_multipart_body(
            fields=[("relpath", "SubFolder")],
            files=[
                ("file", utf8_fname, utf8_bytes),
                ("file", "../../large.bin", large_payload),
            ],
        )
        status, _, resp_bytes = self.raw_request(
            "POST",
            "/api/upload?dst=/Uploads",
            body=body,
            headers={"Content-Type": ctype, "Content-Length": str(len(body))},
        )
        self.assertEqual(status, 200)
        stored = json.loads(resp_bytes.decode("utf-8"))["stored"]
        self.assertIn(f"/Uploads/SubFolder/{utf8_fname}", stored)
        # Traversal prefix "../../" sanitized to basename "large.bin" inside /Uploads
        self.assertIn("/Uploads/large.bin", stored)

        # Download single file using ONLY mf_token cookie (simulating browser navigation)
        dl_status, dl_hdrs, dl_bytes = self.raw_request(
            "GET",
            "/api/download?path=/Uploads/large.bin",
            include_token=False,
            headers={"Cookie": f"mf_token={self.web.token}"},
        )
        self.assertEqual(dl_status, 200)
        self.assertIn("attachment", dl_hdrs.get("content-disposition", ""))
        self.assertEqual(dl_bytes, large_payload)

        # Download entire /Uploads directory as on-the-fly .zip
        z_status, z_hdrs, z_bytes = self.raw_request(
            "GET",
            "/api/download?path=/Uploads",
            include_token=False,
            headers={"Cookie": f"mf_token={self.web.token}"},
        )
        self.assertEqual(z_status, 200)
        self.assertEqual(z_hdrs.get("content-type"), "application/zip")
        with zipfile.ZipFile(io.BytesIO(z_bytes), "r") as zf:
            names = set(zf.namelist())
            self.assertIn("large.bin", names)
            self.assertIn(f"SubFolder/{utf8_fname}", names)
            self.assertEqual(zf.read(f"SubFolder/{utf8_fname}"), utf8_bytes)

    def test_transfer_queue_and_cancellation(self) -> None:
        self.api_json("POST", "/api/connect", {"host": self.host, "port": self.port})
        src_file = self.jail_dir / "queue_item.bin"
        src_file.write_bytes(os.urandom(128 * 1024))

        st, enq = self.api_json(
            "POST",
            "/api/transfer",
            {
                "direction": "up",
                "items": [{"src": str(src_file), "dst": "/queued_out.bin"}],
                "overwrite": "overwrite",
            },
        )
        self.assertEqual(st, 200)
        job_id = enq["job_id"]

        # Wait for job completion
        deadline = time.monotonic() + 5.0
        final_job = None
        while time.monotonic() < deadline:
            _, jobs = self.api_json("GET", "/api/transfers")
            final_job = next(j for j in jobs if j["job_id"] == job_id)
            if final_job["state"] in ("completed", "failed", "cancelled"):
                break
            time.sleep(0.05)

        assert final_job is not None
        self.assertEqual(final_job["state"], "completed")

        # Test mid-stream cancellation against a slow fixture server
        if not self.is_external:
            slow_items = []
            for idx in range(12):
                p = self.jail_dir / f"slow_{idx}.bin"
                p.write_bytes(b"X" * (128 * 1024))
                slow_items.append({"src": str(p), "dst": f"/slow_{idx}.bin"})
            with EmbeddedFtpServer(
                self.local_tmp / "slow_ftp", transfer_chunk_delay=0.05
            ) as slow_srv:
                self.api_json(
                    "POST",
                    "/api/connect",
                    {"host": slow_srv.host, "port": slow_srv.port},
                )
                _, slow_enq = self.api_json(
                    "POST",
                    "/api/transfer",
                    {
                        "direction": "up",
                        "items": slow_items,
                    },
                )
                slow_id = slow_enq["job_id"]
                wait_run = time.monotonic() + 2.0
                while time.monotonic() < wait_run:
                    _, jobs = self.api_json("GET", "/api/transfers")
                    cur_state = next(j for j in jobs if j["job_id"] == slow_id)["state"]
                    if cur_state == "running":
                        break
                    time.sleep(0.02)
                c_st, c_res = self.api_json("POST", f"/api/transfers/{slow_id}/cancel", {})
                self.assertEqual(c_st, 200)
                self.assertTrue(c_res["ok"])

                deadline = time.monotonic() + 5.0
                cancelled_state = ""
                while time.monotonic() < deadline:
                    _, jobs = self.api_json("GET", "/api/transfers")
                    cancelled_state = next(j for j in jobs if j["job_id"] == slow_id)["state"]
                    if cancelled_state == "cancelled":
                        break
                    time.sleep(0.05)
                self.assertEqual(cancelled_state, "cancelled")


if __name__ == "__main__":
    unittest.main()
