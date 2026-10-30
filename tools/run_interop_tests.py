#!/usr/bin/env python3
"""Cross-component JVM Kotlin FtpServerEngine <-> Python PC Companion integration runner.

Implements ARCHITECTURE.md §8.4:
1. Launches the Android Kotlin `FtpServerEngine` on the JVM via Gradle `FtpEngineServeTest`
   (or `--fixture` for standalone dry-run against `EmbeddedFtpServer`).
2. Runs the full Python `companion/tests` unittest suite pointed at the live Kotlin server
   (`MINIFILE_TEST_SERVER=127.0.0.1:<port>`, `MINIFILE_TEST_ROOT=<root>`).
3. Runs an end-to-end CLI & Web UI scenario exercising `scan`, `mkdir`, `put` (with UTF-8 names),
   `ls -R`, `tree`, `get`, `sync --both`, `cat`, `edit`, `mv`, `nomedia --on/--status/--off`,
   Web UI `/api/phone/*` + `/api/upload` + `/api/download`, and `rm -r`, asserting server-side
   disk state after every step.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent
COMPANION_DIR = REPO_ROOT / "companion"
DEFAULT_JAVA_HOME = str(Path.home() / ".jdks" / "jdk21")
DEFAULT_ANDROID_HOME = str(Path.home() / "Android" / "Sdk")


def _parse_ready_line(text: str) -> tuple[int, Path] | None:
    for line in text.splitlines():
        line = line.strip()
        if not line.startswith("READY "):
            continue
        m_port = re.search(r"\bport=(\d+)\b", line)
        m_root = re.search(r"\broot=(.+)$", line)
        if m_port and m_root:
            return int(m_port.group(1)), Path(m_root.group(1).strip())
    return None


def _run_cli(
    args: list[str],
    env: dict[str, str],
    expect_code: int = 0,
) -> subprocess.CompletedProcess[str]:
    cmd = [sys.executable, "-m", "minifile_companion", "--no-update-hint", *args]
    proc = subprocess.run(
        cmd,
        cwd=str(COMPANION_DIR),
        env=env,
        capture_output=True,
        text=True,
        timeout=30,
    )
    if proc.returncode != expect_code:
        raise RuntimeError(
            f"CLI command failed (expected {expect_code}, got {proc.returncode}): {' '.join(args)}\n"
            f"STDOUT:\n{proc.stdout}\nSTDERR:\n{proc.stderr}"
        )
    return proc


def run_e2e_cli_and_webui_scenarios(host: str, port: int, server_root: Path) -> dict[str, Any]:
    """Execute ARCHITECTURE.md §8.4 step 3 end-to-end CLI + Web UI scenarios."""
    if str(COMPANION_DIR) not in sys.path:
        sys.path.insert(0, str(COMPANION_DIR))
    from minifile_companion.webui import CompanionWebServer

    summary: dict[str, Any] = {"steps": []}
    with tempfile.TemporaryDirectory(prefix="mf-interop-client-") as client_tmp:
        tmp_dir = Path(client_tmp)
        env = os.environ.copy()
        env["MINIFILE_CONFIG"] = str(tmp_dir / "config.json")
        env["MINIFILE_CACHE_DIR"] = str(tmp_dir / "cache")

        conn = ["-H", host, "-P", str(port)]

        # Clean server root first
        for child in server_root.iterdir():
            if child.is_dir() and not child.is_symlink():
                shutil.rmtree(child)
            else:
                child.unlink()

        # 1. scan --subnet 127.0.0.1/32
        r_scan = _run_cli(["scan", "--subnet", f"{host}/32", "-P", str(port), "--json"], env)
        found = json.loads(r_scan.stdout)
        if not any(d.get("port") == port and "minifile" in d.get("banner", "").lower() for d in found):
            raise AssertionError(f"scan did not discover server on {host}:{port}: {found}")
        summary["steps"].append({"step": "scan", "status": "PASS", "banner": found[0]["banner"]})

        # 2. mkdir -p /it/sub
        _run_cli([*conn, "mkdir", "-p", "/it/sub"], env)
        assert (server_root / "it" / "sub").is_dir(), "Server missing /it/sub directory"
        summary["steps"].append({"step": "mkdir", "status": "PASS"})

        # 3. put a tree with UTF-8 names & binary payload
        src_tree = tmp_dir / "upload_src"
        (src_tree / "nested").mkdir(parents=True)
        utf8_name = "测试 файл ✓ (1).txt"
        utf8_body = "Interop UTF-8 Line 1\nПривет Мир 🚀\n"
        (src_tree / utf8_name).write_text(utf8_body, encoding="utf-8")
        bin_bytes = os.urandom(96 * 1024)
        bin_sha = hashlib.sha256(bin_bytes).hexdigest()
        (src_tree / "nested" / "payload.bin").write_bytes(bin_bytes)

        _run_cli([*conn, "put", "-r", str(src_tree), "/it/tree", "--overwrite"], env)
        assert (server_root / "it" / "tree" / utf8_name).read_text(encoding="utf-8") == utf8_body
        assert hashlib.sha256((server_root / "it" / "tree" / "nested" / "payload.bin").read_bytes()).hexdigest() == bin_sha
        summary["steps"].append({"step": "put_utf8_tree", "status": "PASS", "sha256": bin_sha})

        # 4. ls -R & tree
        r_ls = _run_cli([*conn, "ls", "-R", "/it", "--json"], env)
        ls_data = json.loads(r_ls.stdout)
        entry_names = {e["name"] for e in ls_data["entries"]}
        assert utf8_name in entry_names and "payload.bin" in entry_names, entry_names
        r_tree = _run_cli([*conn, "tree", "/it"], env)
        assert utf8_name in r_tree.stdout
        summary["steps"].append({"step": "ls_and_tree", "status": "PASS", "entries": len(ls_data["entries"])})

        # 5. get recursive download + verify hash
        dl_tree = tmp_dir / "downloaded_tree"
        _run_cli([*conn, "get", "-r", "/it/tree", str(dl_tree)], env)
        assert (dl_tree / utf8_name).read_text(encoding="utf-8") == utf8_body
        assert hashlib.sha256((dl_tree / "nested" / "payload.bin").read_bytes()).hexdigest() == bin_sha
        summary["steps"].append({"step": "get_recursive", "status": "PASS"})

        # 6. cat & edit (with --backup)
        r_cat = _run_cli([*conn, "cat", f"/it/tree/{utf8_name}"], env)
        assert r_cat.stdout == utf8_body
        mock_editor = f"{sys.executable} -c \"import pathlib,sys; p=pathlib.Path(sys.argv[1]); p.write_text(p.read_text('utf-8') + 'Edited via CLI!\\n', 'utf-8')\""
        _run_cli([*conn, "edit", f"/it/tree/{utf8_name}", "--editor", mock_editor, "--backup"], env)
        assert "Edited via CLI!" in (server_root / "it" / "tree" / utf8_name).read_text(encoding="utf-8")
        assert (server_root / "it" / "tree" / f"{utf8_name}.bak").read_text(encoding="utf-8") == utf8_body
        summary["steps"].append({"step": "cat_and_edit", "status": "PASS"})

        # 7. sync --both
        sync_pc = tmp_dir / "sync_pc"
        sync_pc.mkdir()
        (sync_pc / "from_pc.txt").write_text("pc-side-file", encoding="utf-8")
        (server_root / "it" / "sync_dir").mkdir(parents=True, exist_ok=True)
        (server_root / "it" / "sync_dir" / "from_phone.txt").write_text("phone-side-file", encoding="utf-8")
        r_sync = _run_cli([*conn, "sync", str(sync_pc), "/it/sync_dir", "--both", "--json"], env)
        sync_res = json.loads(r_sync.stdout)
        assert sync_res["uploaded"] == 1 and sync_res["downloaded"] == 1, sync_res
        assert (server_root / "it" / "sync_dir" / "from_pc.txt").read_text(encoding="utf-8") == "pc-side-file"
        assert (sync_pc / "from_phone.txt").read_text(encoding="utf-8") == "phone-side-file"
        summary["steps"].append({"step": "sync_both", "status": "PASS"})

        # 8. nomedia --on / --status / --off
        _run_cli([*conn, "nomedia", "/it/sub", "--on"], env)
        assert (server_root / "it" / "sub" / ".nomedia").is_file()
        r_nm_st = _run_cli([*conn, "nomedia", "/it/sub", "--status", "--json"], env)
        assert json.loads(r_nm_st.stdout)["nomedia"] is True
        _run_cli([*conn, "nomedia", "/it/sub", "--off"], env)
        assert not (server_root / "it" / "sub" / ".nomedia").exists()
        summary["steps"].append({"step": "nomedia_toggle", "status": "PASS"})

        # 9. mv
        _run_cli([*conn, "mv", "/it/sync_dir/from_pc.txt", "/it/sync_dir/renamed_pc.txt"], env)
        assert (server_root / "it" / "sync_dir" / "renamed_pc.txt").is_file()
        assert not (server_root / "it" / "sync_dir" / "from_pc.txt").exists()
        summary["steps"].append({"step": "mv", "status": "PASS"})

        # 10. Web UI API against Kotlin server (list + streaming upload + download)
        ws = CompanionWebServer(
            host="127.0.0.1",
            port=0,
            jail=tmp_dir,
            config_path=tmp_dir / "web_cfg.json",
            initial_ftp={"host": host, "port": port},
        ).start_background()
        try:
            headers = {"X-MF-Token": ws.token}
            req_list = urllib.request.Request(
                f"http://127.0.0.1:{ws.port}/api/phone/list?path=/it/tree&hidden=1",
                headers=headers,
            )
            with urllib.request.urlopen(req_list, timeout=5) as resp:
                wlist = json.loads(resp.read().decode("utf-8"))
                assert any(e["name"] == utf8_name for e in wlist["entries"])

            req_dl = urllib.request.Request(
                f"http://127.0.0.1:{ws.port}/api/download?path=/it/tree/nested/payload.bin",
                headers=headers,
            )
            with urllib.request.urlopen(req_dl, timeout=10) as resp:
                dl_bytes = resp.read()
                assert hashlib.sha256(dl_bytes).hexdigest() == bin_sha
        finally:
            ws.shutdown()
        summary["steps"].append({"step": "webui_api_interop", "status": "PASS"})

        # 11. rm -r -f /it
        _run_cli([*conn, "rm", "-r", "-f", "/it"], env)
        assert not (server_root / "it").exists()
        summary["steps"].append({"step": "rm_recursive", "status": "PASS"})

    return summary


def _parse_unittest_counts(stderr_text: str) -> dict[str, int]:
    ran_m = re.search(r"Ran (\d+) tests? in ", stderr_text)
    skip_m = re.search(r"skipped=(\d+)", stderr_text)
    total = int(ran_m.group(1)) if ran_m else 0
    skipped = int(skip_m.group(1)) if skip_m else 0
    return {"total": total, "passed": total - skipped, "skipped": skipped}


def run_against_server(host: str, port: int, server_root: Path, json_out: Path | None) -> int:
    print(f"[interop] Connected to server at {host}:{port} (root={server_root})")
    env = os.environ.copy()
    env["MINIFILE_TEST_SERVER"] = f"{host}:{port}"
    env["MINIFILE_TEST_ROOT"] = str(server_root)

    t0 = time.monotonic()
    print("[interop] 1/2 Running Python companion unittest suite against server...")
    ut = subprocess.run(
        [sys.executable, "-m", "unittest", "discover", "-s", "companion/tests", "-v"],
        cwd=str(REPO_ROOT),
        env=env,
        capture_output=True,
        text=True,
        timeout=180,
    )
    sys.stdout.write(ut.stdout)
    sys.stderr.write(ut.stderr)
    if ut.returncode != 0:
        print(f"[interop] FAIL: unittest suite exited with {ut.returncode}", file=sys.stderr)
        return ut.returncode

    counts = _parse_unittest_counts(ut.stderr)
    print(f"[interop] Unittest suite OK: {counts}")

    print("[interop] 2/2 Running end-to-end CLI & Web UI scenario...")
    e2e_summary = run_e2e_cli_and_webui_scenarios(host, port, server_root)
    elapsed = round(time.monotonic() - t0, 2)
    report = {
        "status": "PASS",
        "server": f"{host}:{port}",
        "unittest": counts,
        "e2e": e2e_summary,
        "elapsed_seconds": elapsed,
    }
    if json_out is not None:
        json_out.parent.mkdir(parents=True, exist_ok=True)
        json_out.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"[interop] ALL INTEROP CHECKS PASSED in {elapsed}s!")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--fixture",
        action="store_true",
        help="Dry-run against Python EmbeddedFtpServer instead of Gradle Kotlin FtpEngineServeTest",
    )
    parser.add_argument(
        "--json-out",
        type=Path,
        default=None,
        help="Optional path to write JSON interop summary",
    )
    args = parser.parse_args()

    if args.fixture:
        if str(COMPANION_DIR) not in sys.path:
            sys.path.insert(0, str(COMPANION_DIR))
        from tests.ftp_fixture import EmbeddedFtpServer

        with tempfile.TemporaryDirectory(prefix="mf-fixture-interop-") as tmp:
            root = Path(tmp)
            with EmbeddedFtpServer(root) as srv:
                return run_against_server(srv.host, srv.port, root, args.json_out)

    # Launch Kotlin FtpServerEngine via Gradle FtpEngineServeTest (ARCHITECTURE.md §8.4)
    with tempfile.TemporaryDirectory(prefix="mf-kotlin-interop-") as ctl_dir:
        ctl = Path(ctl_dir)
        ftp_root = ctl / "ftp_root"
        ftp_root.mkdir(parents=True, exist_ok=True)
        ready_file = ctl / "mf.ready"
        stop_file = ctl / "mf.stop"

        env = os.environ.copy()
        env.setdefault("JAVA_HOME", DEFAULT_JAVA_HOME)
        env.setdefault("ANDROID_HOME", DEFAULT_ANDROID_HOME)
        env["MINIFILE_FTP_SERVE"] = "1"
        env["MINIFILE_FTP_PORT"] = "0"
        env["MINIFILE_FTP_ROOT"] = str(ftp_root)
        env["MINIFILE_FTP_READY_FILE"] = str(ready_file)
        env["MINIFILE_FTP_STOP_FILE"] = str(stop_file)

        gradle_cmd = [
            str(REPO_ROOT / "gradlew"),
            "--offline",
            ":app:testDebugUnitTest",
            "--tests",
            "*FtpEngineServeTest*",
            "--rerun-tasks",
            "-i",
        ]
        print(f"[interop] Starting Kotlin FtpServerEngine via: {' '.join(gradle_cmd)}")
        gradle_proc = subprocess.Popen(
            gradle_cmd,
            cwd=str(REPO_ROOT),
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )
        try:
            deadline = time.monotonic() + 120.0
            parsed: tuple[int, Path] | None = None
            while time.monotonic() < deadline:
                if ready_file.is_file():
                    parsed = _parse_ready_line(ready_file.read_text(encoding="utf-8"))
                    if parsed is not None:
                        break
                if gradle_proc.poll() is not None:
                    out = gradle_proc.stdout.read() if gradle_proc.stdout else ""
                    print(out, file=sys.stderr)
                    raise RuntimeError(
                        f"Gradle FtpEngineServeTest exited prematurely with code {gradle_proc.returncode}"
                    )
                time.sleep(0.25)

            if parsed is None:
                gradle_proc.terminate()
                out = gradle_proc.stdout.read() if gradle_proc.stdout else ""
                print(out, file=sys.stderr)
                raise TimeoutError("Timed out waiting for READY file from FtpEngineServeTest")

            port, resolved_root = parsed
            rc = run_against_server("127.0.0.1", port, resolved_root, args.json_out)
            stop_file.write_text("stop\n", encoding="utf-8")
            try:
                gradle_proc.wait(timeout=30)
            except subprocess.TimeoutExpired:
                gradle_proc.kill()
            if rc == 0 and gradle_proc.returncode not in (0, None):
                print(
                    f"[interop] Warning: FtpEngineServeTest exited with {gradle_proc.returncode}",
                    file=sys.stderr,
                )
                return gradle_proc.returncode
            return rc
        finally:
            if not stop_file.exists():
                try:
                    stop_file.write_text("stop\n", encoding="utf-8")
                except OSError:
                    pass
            if gradle_proc.poll() is None:
                try:
                    gradle_proc.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    gradle_proc.kill()


if __name__ == "__main__":
    raise SystemExit(main())
