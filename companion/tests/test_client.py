"""Unit & integration tests for MiniFileClient (ARCHITECTURE §6.3, §6.4, §8.3)."""

from __future__ import annotations

import os
import socket
import sys
import tempfile
import time
import unittest
from pathlib import Path

_COMPANION_ROOT = str(Path(__file__).resolve().parent.parent)
if _COMPANION_ROOT not in sys.path:
    sys.path.insert(0, _COMPANION_ROOT)

from minifile_companion.client import (  # noqa: E402
    ConnectionAuthError,
    MiniFileClient,
    MiniFileError,
    PathSecurityError,
    ReadOnlyServerError,
    RemoteNotFoundError,
    normalize_remote_path,
    parse_257_path,
    parse_list_line,
    safe_local_child,
)
from tests.ftp_fixture import EmbeddedFtpServer, FtpServerTestCase  # noqa: E402


class TestPathAndParsers(unittest.TestCase):
    """Pure unit tests for path normalization, local confinement, and reply parsers."""

    def test_normalize_remote_path_clamps_traversal(self) -> None:
        self.assertEqual(normalize_remote_path("/"), "/")
        self.assertEqual(normalize_remote_path(""), "/")
        self.assertEqual(normalize_remote_path("/../.."), "/")
        self.assertEqual(normalize_remote_path("/a/b/../../c"), "/c")
        self.assertEqual(normalize_remote_path("//a///b/./c/"), "/a/b/c")
        self.assertEqual(normalize_remote_path("sub/item.txt", base="/Download"), "/Download/sub/item.txt")

    def test_normalize_remote_path_rejects_nul_and_crlf(self) -> None:
        with self.assertRaises(PathSecurityError):
            normalize_remote_path("/bad\x00name.txt")
        with self.assertRaises(PathSecurityError):
            normalize_remote_path("/bad\r\nDELE /x")

    def test_safe_local_child_prevents_traversal_and_symlinks(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "jail"
            outside = Path(tmp) / "outside"
            root.mkdir()
            outside.mkdir()

            ok = safe_local_child(root, "sub/notes.txt")
            self.assertTrue(str(ok).startswith(str(root)))

            for bad in ("../evil.txt", "a/../../evil.txt", "/etc/passwd", "bad\x00.txt"):
                with self.assertRaises(PathSecurityError):
                    safe_local_child(root, bad)

            link = root / "escape_link"
            os.symlink(outside, link)
            with self.assertRaises(PathSecurityError):
                safe_local_child(root, "escape_link/secret.txt")

    def test_parse_257_doubled_quotes(self) -> None:
        self.assertEqual(parse_257_path('257 "/Download" is current directory.'), "/Download")
        self.assertEqual(
            parse_257_path('257 "/quote ""dir"" name" created.'),
            '/quote "dir" name',
        )

    def test_parse_list_line_recent_and_old_dates(self) -> None:
        now = 1793361600.0  # 2026-10-30 UTC
        e1 = parse_list_line(
            "-rw-r--r-- 1 owner group     4096 Oct 29 20:14 report final.pdf",
            "/Docs",
            now_ts=now,
        )
        self.assertIsNotNone(e1)
        assert e1 is not None
        self.assertFalse(e1.is_dir)
        self.assertEqual(e1.size, 4096)
        self.assertEqual(e1.name, "report final.pdf")
        self.assertEqual(e1.path, "/Docs/report final.pdf")
        self.assertIsNotNone(e1.mtime)

        e2 = parse_list_line(
            "drwxr-xr-x 1 owner group        0 May  2  2023 Archive",
            "/",
            now_ts=now,
        )
        self.assertIsNotNone(e2)
        assert e2 is not None
        self.assertTrue(e2.is_dir)
        self.assertEqual(e2.name, "Archive")
        self.assertIsNotNone(e2.mtime)


class TestMiniFileClient(FtpServerTestCase):
    """Integration tests against EmbeddedFtpServer (or Kotlin FtpServerEngine via MINIFILE_TEST_SERVER)."""

    def test_connect_banner_and_features(self) -> None:
        with self.make_client() as cli:
            self.assertIsNotNone(cli.server_info)
            assert cli.server_info is not None
            self.assertIn("MiniFile", cli.server_info.banner)
            self.assertTrue(cli.server_info.utf8_enabled)
            for feat in ("UTF8", "MLSD", "MLST", "SIZE", "MDTM", "EPSV", "PASV"):
                self.assertIn(feat, cli.server_info.features)
            self.assertTrue(cli.noop())
            self.assertEqual(cli.pwd(), "/")

    def test_connect_unreachable_raises_connection_auth_error(self) -> None:
        # Allocate a closed ephemeral port
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.bind(("127.0.0.1", 0))
            dead_port = s.getsockname()[1]
        cli = MiniFileClient("127.0.0.1", dead_port, timeout=1.0)
        with self.assertRaises(ConnectionAuthError):
            cli.connect()

    def test_credentials_auth_success_and_rejection(self) -> None:
        self.require_fixture("Custom auth server requires embedded fixture")
        with tempfile.TemporaryDirectory() as tmp:
            with EmbeddedFtpServer(tmp, credentials=("alice", "s3cr3t")):
                pass
            with EmbeddedFtpServer(tmp, credentials=("alice", "s3cr3t")) as srv:
                bad_cli = MiniFileClient(srv.host, srv.port, user="alice", password="wrong")
                with self.assertRaises(ConnectionAuthError):
                    bad_cli.connect()

                ok_cli = MiniFileClient(srv.host, srv.port, user="alice", password="s3cr3t")
                info = ok_cli.connect()
                self.assertIn("MiniFile", info.banner)
                ok_cli.close()

    def test_utf8_roundtrip_and_quoted_mkdir(self) -> None:
        utf8_name = "测试 файл ✓ (1).txt"
        content = "Hello MiniFile! 你好 Привет 🚀\nLine 2\n"
        with self.make_client() as cli:
            created = cli.mkdir('/quote "dir" name', parents=True)
            self.assertEqual(created, '/quote "dir" name')

            remote_file = f'/quote "dir" name/{utf8_name}'
            written = cli.write_text(remote_file, content, encoding="utf-8")
            self.assertEqual(written, len(content.encode("utf-8")))

            entries = cli.list('/quote "dir" name')
            self.assertEqual([e.name for e in entries], [utf8_name])

            st = cli.stat(remote_file)
            self.assertIsNotNone(st)
            assert st is not None
            self.assertEqual(st.size, written)
            self.assertFalse(st.is_dir)

            read_back, enc = cli.read_text(remote_file)
            self.assertEqual(enc, "utf-8")
            self.assertEqual(read_back, content)

            if self.server_root is not None:
                disk_file = self.server_root / 'quote "dir" name' / utf8_name
                self.assertTrue(disk_file.is_file())
                self.assertEqual(disk_file.read_text(encoding="utf-8"), content)

    def test_list_fallback_when_mlsd_disabled_and_pasv_522_epsv_fallback(self) -> None:
        self.require_fixture("Custom MLSD-disabled / PASV-522 server requires fixture")
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "folderA").mkdir()
            (root / "hello.txt").write_bytes(b"12345")
            (root / ".secret").write_bytes(b"x")
            with EmbeddedFtpServer(
                root, enable_mlsx=False, force_pasv_522=True
            ) as srv:
                with MiniFileClient(srv.host, srv.port) as cli:
                    vis = cli.list("/", hidden=False)
                    self.assertEqual([e.name for e in vis], ["folderA", "hello.txt"])
                    all_e = cli.list("/", hidden=True)
                    self.assertIn(".secret", [e.name for e in all_e])

                    st_file = cli.stat("/hello.txt")
                    self.assertIsNotNone(st_file)
                    assert st_file is not None
                    self.assertEqual(st_file.size, 5)
                    self.assertFalse(st_file.is_dir)

                    st_dir = cli.stat("/folderA")
                    self.assertIsNotNone(st_dir)
                    assert st_dir is not None
                    self.assertTrue(st_dir.is_dir)
                    self.assertIsNone(cli.stat("/does-not-exist"))

    def test_recursive_upload_walk_download_and_progress(self) -> None:
        src = self.local_tmp / "src_tree"
        (src / "sub1" / "deep").mkdir(parents=True)
        (src / "empty_dir").mkdir(parents=True)
        (src / "root.txt").write_text("root data", encoding="utf-8")
        (src / "sub1" / "deep" / "binary.bin").write_bytes(os.urandom(150_000))

        up_events: list[tuple[int, int]] = []
        dn_events: list[tuple[int, int, str]] = []

        with self.make_client() as cli:
            cli.upload(
                src,
                "/backup/tree",
                progress=lambda done, total: up_events.append((done, total)),
            )
            self.assertTrue(len(up_events) >= 2)
            self.assertEqual(up_events[-1][0], up_events[-1][1])

            walked_dirs = [d for d, _, _ in cli.walk("/backup/tree")]
            self.assertIn("/backup/tree/empty_dir", walked_dirs)
            self.assertIn("/backup/tree/sub1/deep", walked_dirs)

            dst = self.local_tmp / "restored_tree"
            cli.download(
                "/backup/tree",
                dst,
                progress=lambda d, t, cur: dn_events.append((d, t, cur)),
            )
            self.assertTrue((dst / "empty_dir").is_dir())
            self.assertEqual((dst / "root.txt").read_text(encoding="utf-8"), "root data")
            self.assertEqual(
                (dst / "sub1" / "deep" / "binary.bin").read_bytes(),
                (src / "sub1" / "deep" / "binary.bin").read_bytes(),
            )
            self.assertTrue(len(dn_events) >= 2)
            self.assertEqual(dn_events[-1][0], dn_events[-1][1])

    def test_download_and_upload_resume_and_overwrite_policies(self) -> None:
        payload = b"0123456789ABCDEF" * 8192  # 128 KiB
        full_src = self.local_tmp / "full.bin"
        full_src.write_bytes(payload)

        with self.make_client() as cli:
            cli.upload(full_src, "/full.bin")

            # Partial local file -> resume download via REST
            partial_local = self.local_tmp / "partial_dl.bin"
            partial_local.write_bytes(payload[:40_000])
            cli.download("/full.bin", partial_local, resume=True)
            self.assertEqual(partial_local.read_bytes(), payload)

            # Overwrite="skip" keeps modified local file untouched
            partial_local.write_bytes(b"KEEP_ME")
            cli.download("/full.bin", partial_local, overwrite="skip")
            self.assertEqual(partial_local.read_bytes(), b"KEEP_ME")

            # Overwrite="ask" with confirm=False skips; confirm=True overwrites
            cli.download(
                "/full.bin", partial_local, overwrite="ask", confirm=lambda _s, _d: False
            )
            self.assertEqual(partial_local.read_bytes(), b"KEEP_ME")
            cli.download(
                "/full.bin", partial_local, overwrite="ask", confirm=lambda _s, _d: True
            )
            self.assertEqual(partial_local.read_bytes(), payload)

    def test_upload_resume_falls_back_cleanly_on_saf_504(self) -> None:
        self.require_fixture("SAF 504 REST+STOR simulation requires fixture")
        payload = b"SAF-DATA-" * 5000
        src = self.local_tmp / "saf_up.bin"
        src.write_bytes(payload)

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "saf_up.bin").write_bytes(payload[:1000])
            with EmbeddedFtpServer(root, reject_rest_stor=True) as srv:
                with MiniFileClient(srv.host, srv.port) as cli:
                    cli.upload(src, "/saf_up.bin", resume=True)
                    self.assertFalse(cli._upload_rest_supported)
            self.assertEqual((root / "saf_up.bin").read_bytes(), payload)

    def test_mkdir_remove_rename_and_copy_fallback(self) -> None:
        with self.make_client() as cli:
            cli.mkdir("/a/b/c", parents=True)
            cli.write_text("/a/b/c/file.txt", "abc")
            # Non-recursive remove on non-empty dir fails
            with self.assertRaises(MiniFileError):
                cli.remove("/a", recursive=False)

            cli.rename("/a/b/c/file.txt", "/a/b/c/renamed.txt")
            self.assertIsNone(cli.stat("/a/b/c/file.txt"))
            self.assertIsNotNone(cli.stat("/a/b/c/renamed.txt"))

            cli.remove("/a", recursive=True)
            self.assertIsNone(cli.stat("/a"))

        if not self.is_external:
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                (root / "src.txt").write_text("cross-backend", encoding="utf-8")
                with EmbeddedFtpServer(root, reject_rename=True) as srv:
                    with MiniFileClient(srv.host, srv.port) as cli:
                        with self.assertRaises(MiniFileError):
                            cli.rename("/src.txt", "/dst.txt", copy_fallback=False)
                        cli.rename("/src.txt", "/dst.txt", copy_fallback=True)
                        text, _ = cli.read_text("/dst.txt")
                        self.assertEqual(text, "cross-backend")
                        self.assertIsNone(cli.stat("/src.txt"))

    def test_nomedia_status_enable_disable(self) -> None:
        with self.make_client() as cli:
            cli.mkdir("/Pictures/Private", parents=True)
            self.assertFalse(cli.nomedia("/Pictures/Private", None))
            self.assertTrue(cli.nomedia("/Pictures/Private", True))
            self.assertTrue(cli.nomedia("/Pictures/Private", None))
            self.assertFalse(cli.nomedia("/Pictures/Private", False))
            self.assertFalse(cli.nomedia("/Pictures/Private", None))
            # Idempotent second disable
            self.assertFalse(cli.nomedia("/Pictures/Private", False))

    def test_sync_to_phone_to_pc_both_delete_dryrun_and_exclude(self) -> None:
        pc_dir = self.local_tmp / "vault_pc"
        pc_dir.mkdir()
        (pc_dir / "keep.md").write_text("v1", encoding="utf-8")
        (pc_dir / "ignore.tmp").write_text("temp", encoding="utf-8")
        (pc_dir / "sub").mkdir()
        (pc_dir / "sub" / "nested.txt").write_text("nested", encoding="utf-8")

        with self.make_client() as cli:
            # 1. Dry-run makes zero changes
            dry = cli.sync(
                pc_dir, "/Vault", direction="to_phone", dry_run=True, exclude=("*.tmp",)
            )
            self.assertTrue(dry.dry_run)
            self.assertEqual(dry.uploaded, 0)
            self.assertIsNone(cli.stat("/Vault"))

            # 2. Real sync --to-phone with exclude
            res_up = cli.sync(
                pc_dir, "/Vault", direction="to_phone", exclude=("*.tmp",)
            )
            self.assertEqual(res_up.uploaded, 2)
            self.assertIsNone(cli.stat("/Vault/ignore.tmp"))
            self.assertEqual(cli.read_text("/Vault/sub/nested.txt")[0], "nested")

            # 3. Sync --to-pc into fresh folder
            pc_clone = self.local_tmp / "vault_clone"
            res_dn = cli.sync(pc_clone, "/Vault", direction="to_pc")
            self.assertEqual(res_dn.downloaded, 2)
            self.assertEqual((pc_clone / "keep.md").read_text(encoding="utf-8"), "v1")

            # Immediate re-sync skips unchanged files (within 2s mtime tolerance)
            res_noop = cli.sync(pc_clone, "/Vault", direction="both")
            self.assertEqual(res_noop.uploaded, 0)
            self.assertEqual(res_noop.downloaded, 0)
            self.assertEqual(res_noop.skipped, 2)

            # 4. Bidirectional --both: newest wins & never deletes unique files
            (pc_clone / "pc_only.txt").write_text("from pc", encoding="utf-8")
            cli.write_text("/Vault/phone_only.txt", "from phone")
            future_ts = time.time() + 10.0
            (pc_clone / "keep.md").write_text("v2-newer-on-pc", encoding="utf-8")
            os.utime(pc_clone / "keep.md", (future_ts, future_ts))

            res_both = cli.sync(pc_clone, "/Vault", direction="both", delete=True)
            self.assertEqual(res_both.uploaded, 2)  # pc_only.txt + keep.md
            self.assertEqual(res_both.downloaded, 1)  # phone_only.txt
            self.assertEqual(res_both.deleted_local, 0)
            self.assertEqual(res_both.deleted_remote, 0)
            self.assertEqual(cli.read_text("/Vault/keep.md")[0], "v2-newer-on-pc")
            self.assertEqual(
                (pc_clone / "phone_only.txt").read_text(encoding="utf-8"), "from phone"
            )

            # 5. One-way mirror --to-phone --delete removes phone_only.txt when deleted locally
            (pc_clone / "phone_only.txt").unlink()
            res_mirror = cli.sync(pc_clone, "/Vault", direction="to_phone", delete=True)
            self.assertEqual(res_mirror.deleted_remote, 1)
            self.assertIsNone(cli.stat("/Vault/phone_only.txt"))

    def test_sync_handles_missing_mlsd_modify_fact(self) -> None:
        self.require_fixture("SAF omit_modify mode requires embedded fixture")
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "same.txt").write_bytes(b"1234")
            (root / "diff.txt").write_bytes(b"12")
            pc_dir = self.local_tmp / "saf_pc"
            pc_dir.mkdir()
            (pc_dir / "same.txt").write_bytes(b"1234")
            (pc_dir / "diff.txt").write_bytes(b"123456")

            with EmbeddedFtpServer(root, omit_modify=True) as srv:
                with MiniFileClient(srv.host, srv.port) as cli:
                    entries = {e.name: e for e in cli.list("/")}
                    self.assertIsNone(entries["same.txt"].mtime)
                    plan = cli.sync(pc_dir, "/", direction="to_phone")
                    self.assertEqual(plan.skipped, 1)
                    self.assertEqual(plan.uploaded, 1)
            self.assertEqual((root / "diff.txt").read_bytes(), b"123456")

    def test_security_root_clamping_symlink_jail_readonly_and_idle_timeout(self) -> None:
        with self.make_client() as cli:
            cli.write_text("/inside.txt", "safe")
            # ls /../.. clamps to root listing without leaking outside root
            root_names = [e.name for e in cli.list("/../..")]
            self.assertIn("inside.txt", root_names)
            if self.fixture_server is not None:
                # Verify client normalized `..` before sending MLSD/LIST over the wire
                mlsd_args = [
                    arg for cmd, arg in self.fixture_server.command_log if cmd in ("MLSD", "LIST")
                ]
                for sent_arg in mlsd_args:
                    self.assertNotIn("..", sent_arg)

        # Symlink escape inside server root -> 550 Access denied
        if self.server_root is not None:
            with tempfile.TemporaryDirectory() as outside_tmp:
                outside_secret = Path(outside_tmp) / "secret.txt"
                outside_secret.write_text("top-secret", encoding="utf-8")
                evil_link = self.server_root / "evil_symlink"
                os.symlink(outside_tmp, evil_link)
                try:
                    with self.make_client() as cli:
                        with self.assertRaises(MiniFileError):
                            cli.list("/evil_symlink")
                        with self.assertRaises(MiniFileError):
                            cli.read_text("/evil_symlink/secret.txt")
                finally:
                    evil_link.unlink(missing_ok=True)

        if not self.is_external:
            # Read-only server -> surfaces "Phone server is read-only"
            with tempfile.TemporaryDirectory() as ro_tmp:
                with EmbeddedFtpServer(ro_tmp, read_only=True) as ro_srv:
                    with MiniFileClient(ro_srv.host, ro_srv.port) as ro_cli:
                        with self.assertRaises(ReadOnlyServerError) as ctx:
                            ro_cli.write_text("/blocked.txt", "nope")
                        self.assertIn("Phone server is read-only", str(ctx.exception))

            # Idle timeout -> server closes control connection with 421
            with tempfile.TemporaryDirectory() as idle_tmp:
                with EmbeddedFtpServer(idle_tmp, idle_timeout=0.25) as idle_srv:
                    idle_cli = MiniFileClient(idle_srv.host, idle_srv.port)
                    idle_cli.connect()
                    time.sleep(0.45)
                    self.assertFalse(idle_cli.noop())
                    idle_cli.close()


if __name__ == "__main__":
    unittest.main()
