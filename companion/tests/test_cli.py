"""Automated CLI tests covering all subcommands, JSON schema, REPL shell, and exit codes."""

from __future__ import annotations

import contextlib
import io
import json
import socket
import subprocess
import sys
import unittest
from pathlib import Path

_COMPANION_ROOT = str(Path(__file__).resolve().parent.parent)
if _COMPANION_ROOT not in sys.path:
    sys.path.insert(0, _COMPANION_ROOT)

from minifile_companion.cli import (  # noqa: E402
    EXIT_CONN_AUTH,
    EXIT_ERROR,
    EXIT_OK,
    EXIT_PARTIAL,
    EXIT_USAGE,
    MiniFileShell,
    main,
)
from minifile_companion.config import ConfigStore  # noqa: E402
from tests.ftp_fixture import FtpServerTestCase  # noqa: E402


class TestCli(FtpServerTestCase):
    """Tests every CLI verb via main(argv) and subprocess shim execution."""

    def run_cli(self, *args: str) -> tuple[int, str, str]:
        out_buf = io.StringIO()
        err_buf = io.StringIO()
        with contextlib.redirect_stdout(out_buf), contextlib.redirect_stderr(err_buf):
            code = main(list(args))
        return code, out_buf.getvalue(), err_buf.getvalue()

    def conn_flags(self) -> list[str]:
        return ["-H", self.host, "-P", str(self.port)]

    def test_usage_and_connection_exit_codes(self) -> None:
        code_no_args, _, _ = self.run_cli()
        self.assertEqual(code_no_args, EXIT_USAGE)

        code_bad_opt, _, _ = self.run_cli("--unknown-flag")
        self.assertEqual(code_bad_opt, EXIT_USAGE)

        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.bind(("127.0.0.1", 0))
            closed_port = s.getsockname()[1]

        code_conn, _, err = self.run_cli(
            "ls", "/", "-H", "127.0.0.1", "-P", str(closed_port), "--timeout", "0.5"
        )
        self.assertEqual(code_conn, EXIT_CONN_AUTH)
        self.assertIn("Connection/Auth error", err)

    def test_scan_and_save_default_host(self) -> None:
        code, out, _ = self.run_cli(
            "scan",
            "--subnet",
            f"{self.host}/32",
            "-P",
            str(self.port),
            "--timeout",
            "0.5",
            "--save",
            "--json",
        )
        self.assertEqual(code, EXIT_OK)
        data = json.loads(out)
        self.assertEqual(len(data), 1)
        self.assertEqual(data[0]["host"], self.host)
        self.assertEqual(data[0]["port"], self.port)

        cfg = ConfigStore().load()
        self.assertEqual(cfg.host, self.host)
        self.assertEqual(cfg.port, self.port)

    def test_mkdir_put_ls_tree_cat_mv_nomedia_get_rm_and_cli_prefix(self) -> None:
        src_file = self.local_tmp / "hello.txt"
        src_file.write_text("alpha\nbeta\n", encoding="utf-8")
        hidden_src = self.local_tmp / ".dotfile"
        hidden_src.write_text("hidden", encoding="utf-8")

        # mkdir -p (also using optional `cli` prefix)
        c1, out1, _ = self.run_cli("cli", "mkdir", "/Docs/Sub", "-p", "--json", *self.conn_flags())
        self.assertEqual(c1, EXIT_OK)
        self.assertEqual(json.loads(out1)["path"], "/Docs/Sub")

        # put files
        c2, out2, _ = self.run_cli("put", str(src_file), "/Docs/Sub/", "--json", *self.conn_flags())
        self.assertEqual(c2, EXIT_OK)
        self.assertEqual(json.loads(out2)["remote"], "/Docs/Sub/hello.txt")

        c2h, _, _ = self.run_cli("put", str(hidden_src), "/Docs/Sub/.dotfile", *self.conn_flags())
        self.assertEqual(c2h, EXIT_OK)

        # ls without -a hides .dotfile; ls -a includes .dotfile; ls -R recurses
        c_ls, out_ls, _ = self.run_cli("ls", "/Docs/Sub", "--json", *self.conn_flags())
        self.assertEqual(c_ls, EXIT_OK)
        names_vis = [e["name"] for e in json.loads(out_ls)["entries"]]
        self.assertEqual(names_vis, ["hello.txt"])

        c_lsa, out_lsa, _ = self.run_cli("ls", "/Docs/Sub", "-a", "-l", *self.conn_flags())
        self.assertEqual(c_lsa, EXIT_OK)
        self.assertIn(".dotfile", out_lsa)
        self.assertIn("hello.txt", out_lsa)

        c_lsr, out_lsr, _ = self.run_cli("ls", "/", "-R", "--json", *self.conn_flags())
        self.assertEqual(c_lsr, EXIT_OK)
        all_paths = [e["path"] for e in json.loads(out_lsr)["entries"]]
        self.assertIn("/Docs/Sub/hello.txt", all_paths)

        # tree
        c_tree, out_tree, _ = self.run_cli("tree", "/Docs", "-L", "2", "--json", *self.conn_flags())
        self.assertEqual(c_tree, EXIT_OK)
        tree_json = json.loads(out_tree)
        self.assertEqual(tree_json["counts"]["dirs"], 1)
        self.assertEqual(tree_json["counts"]["files"], 1)

        # cat -n
        c_cat, out_cat, _ = self.run_cli("cat", "/Docs/Sub/hello.txt", "-n", *self.conn_flags())
        self.assertEqual(c_cat, EXIT_OK)
        self.assertIn("1\talpha", out_cat)
        self.assertIn("2\tbeta", out_cat)

        # mv
        c_mv, _, _ = self.run_cli(
            "mv", "/Docs/Sub/hello.txt", "/Docs/Sub/greetings.txt", *self.conn_flags()
        )
        self.assertEqual(c_mv, EXIT_OK)

        # nomedia: toggle -> status -> off
        c_nm1, out_nm1, _ = self.run_cli("nomedia", "/Docs/Sub", "--json", *self.conn_flags())
        self.assertEqual(c_nm1, EXIT_OK)
        self.assertTrue(json.loads(out_nm1)["nomedia"])

        c_nm2, out_nm2, _ = self.run_cli(
            "nomedia", "/Docs/Sub", "--status", "--json", *self.conn_flags()
        )
        self.assertEqual(c_nm2, EXIT_OK)
        self.assertTrue(json.loads(out_nm2)["nomedia"])

        c_nm3, out_nm3, _ = self.run_cli(
            "nomedia", "/Docs/Sub", "--off", "--json", *self.conn_flags()
        )
        self.assertEqual(c_nm3, EXIT_OK)
        self.assertFalse(json.loads(out_nm3)["nomedia"])

        # get
        dl_target = self.local_tmp / "downloaded.txt"
        c_get, _, _ = self.run_cli(
            "get", "/Docs/Sub/greetings.txt", str(dl_target), *self.conn_flags()
        )
        self.assertEqual(c_get, EXIT_OK)
        self.assertEqual(dl_target.read_text(encoding="utf-8"), "alpha\nbeta\n")

        # rm partial failure -> EXIT_PARTIAL (4)
        c_part, _, err_part = self.run_cli(
            "rm",
            "-f",
            "/Docs/Sub/greetings.txt",
            "/Docs/Sub/nonexistent.txt",
            *self.conn_flags(),
        )
        self.assertEqual(c_part, EXIT_PARTIAL)
        self.assertIn("nonexistent.txt", err_part)

        # rm single missing -> EXIT_ERROR (1)
        c_err, _, _ = self.run_cli("rm", "-f", "/missing_file.txt", *self.conn_flags())
        self.assertEqual(c_err, EXIT_ERROR)

        # rm -rf directory -> EXIT_OK (0)
        c_rm, _, _ = self.run_cli("rm", "-rf", "/Docs", *self.conn_flags())
        self.assertEqual(c_rm, EXIT_OK)

    def test_edit_with_mock_editor_and_backup(self) -> None:
        with self.make_client() as cli:
            cli.write_text("/config.ini", "port=2121\n")

        editor_script = (
            f"{sys.executable} -c "
            "\"import pathlib, sys; p = pathlib.Path(sys.argv[1]); "
            "p.write_text(p.read_text() + 'readonly=false\\n')\""
        )
        code, out, _ = self.run_cli(
            "edit",
            "/config.ini",
            "--editor",
            editor_script,
            "--backup",
            "--json",
            *self.conn_flags(),
        )
        self.assertEqual(code, EXIT_OK)
        res = json.loads(out)
        self.assertTrue(res["changed"])

        with self.make_client() as cli:
            self.assertEqual(cli.read_text("/config.ini")[0], "port=2121\nreadonly=false\n")
            self.assertEqual(cli.read_text("/config.ini.bak")[0], "port=2121\n")

    def test_sync_cli_and_minifile_shim_subprocess(self) -> None:
        sync_local = self.local_tmp / "sync_src"
        sync_local.mkdir()
        (sync_local / "note.md").write_text("# Sync Test\n", encoding="utf-8")

        code, out, _ = self.run_cli(
            "sync",
            str(sync_local),
            "/SyncedNotes",
            "--to-phone",
            "--json",
            *self.conn_flags(),
        )
        self.assertEqual(code, EXIT_OK)
        self.assertEqual(json.loads(out)["uploaded"], 1)

        # Verify standalone minifile.py shim works via subprocess
        shim = Path(_COMPANION_ROOT) / "minifile.py"
        proc = subprocess.run(
            [sys.executable, str(shim), "cat", "/SyncedNotes/note.md", *self.conn_flags()],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(proc.returncode, EXIT_OK)
        self.assertEqual(proc.stdout, "# Sync Test\n")

    def test_interactive_repl_shell(self) -> None:
        local_item = self.local_tmp / "repl.txt"
        local_item.write_text("from repl", encoding="utf-8")
        dl_dir = self.local_tmp / "repl_dl"
        dl_dir.mkdir()

        commands = "\n".join(
            [
                "pwd",
                "mkdir /ReplDir",
                "cd /ReplDir",
                "pwd",
                f"put {local_item}",
                "ls",
                "cat repl.txt",
                "nomedia",
                f"lcd {dl_dir}",
                "get repl.txt pulled.txt",
                "cd /",
                "rm /ReplDir",
                "exit",
                "",
            ]
        )
        in_stream = io.StringIO(commands)
        out_stream = io.StringIO()
        with self.make_client() as cli:
            shell = MiniFileShell(cli, stdout=out_stream, stdin=in_stream)
            shell.cmdloop()

        output = out_stream.getvalue()
        self.assertIn("/ReplDir", output)
        self.assertIn("from repl", output)
        self.assertIn(".nomedia ON", output)
        self.assertEqual((dl_dir / "pulled.txt").read_text(encoding="utf-8"), "from repl")
        with self.make_client() as cli:
            self.assertIsNone(cli.stat("/ReplDir"))

    def test_version_flag_and_pyproject_metadata_sync(self) -> None:
        import minifile_companion

        code, out, _ = self.run_cli("--version")
        self.assertEqual(code, EXIT_OK)
        self.assertIn(minifile_companion.__version__, out)
        self.assertEqual(minifile_companion.__version__, "1.0.10")

        repo_root = Path(_COMPANION_ROOT).parent
        comp_pyproject = Path(_COMPANION_ROOT) / "pyproject.toml"
        root_pyproject = repo_root / "pyproject.toml"
        for pyproj in (comp_pyproject, root_pyproject):
            if pyproj.is_file():
                raw = pyproj.read_text(encoding="utf-8")
                self.assertIn(f'version = "{minifile_companion.__version__}"', raw)
                self.assertIn('minifile = "minifile_companion.cli:main"', raw)
                self.assertIn('minifile-companion = "minifile_companion.cli:main"', raw)
                self.assertIn('minifile_companion = ["static/*"]', raw)


if __name__ == "__main__":
    unittest.main()
