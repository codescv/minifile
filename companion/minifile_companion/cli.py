"""Command-line interface and interactive REPL shell for MiniFile PC Companion."""

from __future__ import annotations

import argparse
import cmd
import json
import os
import posixpath
import shlex
import subprocess
import sys
import tempfile
import time
from collections.abc import Sequence
from pathlib import Path
from typing import Any

from . import __version__
from .client import (
    ConnectionAuthError,
    MiniFileClient,
    MiniFileError,
    PartialTransferError,
    RemoteEntry,
    normalize_remote_path,
)
from .config import ConfigStore, default_cache_dir
from .gui import run_gui
from .scanner import discover
from .textutil import decode_text
from .updater import (
    UpdateError,
    UpdateNetworkError,
    check_latest,
    download_asset,
    fetch_sha256_for_asset,
    maybe_emit_cached_update_hint,
    record_check_in_config,
    stage_and_apply,
    verify_companion_zip,
)
from .webui import run_webui

EXIT_OK = 0
EXIT_ERROR = 1
EXIT_USAGE = 2
EXIT_CONN_AUTH = 3
EXIT_PARTIAL = 4
EXIT_UPDATE_AVAILABLE = 10


def _human_size(num_bytes: int) -> str:
    if num_bytes < 0:
        return "—"
    if num_bytes < 1024:
        return f"{num_bytes} B"
    val = float(num_bytes)
    for unit in ("KB", "MB", "GB", "TB"):
        val /= 1024.0
        if val < 1024.0 or unit == "TB":
            return f"{val:.1f} {unit}"
    return f"{num_bytes} B"


def _format_mtime(mtime: float | None) -> str:
    if mtime is None:
        return "—"
    return time.strftime("%Y-%m-%d %H:%M", time.localtime(mtime))


def _make_progress_printer(enabled: bool) -> Any:
    start = time.monotonic()
    last_print = 0.0

    def _cb(done: int, total: int, current: str = "") -> None:
        nonlocal last_print
        if not enabled:
            return
        now = time.monotonic()
        if done < total and (now - last_print) < 0.12:
            return
        last_print = now
        elapsed = max(0.001, now - start)
        speed = done / elapsed
        pct = f"{(done * 100 // total):3d}%" if total > 0 else "  —"
        eta_str = ""
        if total > done > 0 and speed > 0:
            rem = int((total - done) / speed)
            eta_str = f" ETA {rem}s"
        label = os.path.basename(current) or current
        line = f"\r{pct}  {_human_size(done)}/{_human_size(total)}  {_human_size(int(speed))}/s{eta_str}  {label[:36]}"
        print(line, end="", file=sys.stderr, flush=True)
        if total > 0 and done >= total:
            print("", file=sys.stderr, flush=True)

    return _cb


class MiniFileShell(cmd.Cmd):
    """Interactive FTP REPL (`minifile shell`)."""

    intro = "MiniFile interactive shell. Type 'help' or '?' for commands, 'exit' to quit."

    def __init__(self, client: MiniFileClient, stdout: Any = None, stdin: Any = None) -> None:
        super().__init__(stdout=stdout or sys.stdout, stdin=stdin or sys.stdin)
        if stdin is not None:
            self.use_rawinput = False
        self.client = client
        self.cwd = "/"
        self.lcwd = Path.cwd()
        self._update_prompt()

    def _update_prompt(self) -> None:
        self.prompt = f"minifile:{self.cwd}> "

    def _resolve(self, arg: str) -> str:
        arg = arg.strip()
        if not arg:
            return self.cwd
        return normalize_remote_path(arg, base=self.cwd)

    def emptyline(self) -> bool:
        return False

    def do_pwd(self, _arg: str) -> None:
        """Print current remote working directory."""
        print(self.cwd, file=self.stdout)

    def do_cd(self, arg: str) -> None:
        """Change current remote directory: cd <dir>"""
        target = self._resolve(arg or "/")
        st = self.client.stat(target)
        if st is None or not st.is_dir:
            print(f"Not a directory: {target}", file=sys.stderr)
            return
        self.cwd = target
        self._update_prompt()

    def do_lcd(self, arg: str) -> None:
        """Change local working directory: lcd <local_dir>"""
        target = Path(arg.strip() or str(Path.home())).expanduser()
        if not target.is_absolute():
            target = (self.lcwd / target).resolve()
        if not target.is_dir():
            print(f"Local directory not found: {target}", file=sys.stderr)
            return
        self.lcwd = target
        print(str(self.lcwd), file=self.stdout)

    def do_lpwd(self, _arg: str) -> None:
        """Print current local working directory."""
        print(str(self.lcwd), file=self.stdout)

    def do_lls(self, arg: str) -> None:
        """List local files: lls [path]"""
        target = Path(arg.strip()).expanduser() if arg.strip() else self.lcwd
        if not target.is_absolute():
            target = self.lcwd / target
        for item in sorted(target.iterdir(), key=lambda p: (not p.is_dir(), p.name.lower())):
            suffix = "/" if item.is_dir() else ""
            print(f"{item.name}{suffix}", file=self.stdout)

    def do_ls(self, arg: str) -> None:
        """List remote directory: ls [-a] [path]"""
        parts = shlex.split(arg)
        show_all = "-a" in parts
        paths = [p for p in parts if not p.startswith("-")]
        target = self._resolve(paths[0]) if paths else self.cwd
        try:
            for e in self.client.list(target, hidden=show_all):
                kind = "d" if e.is_dir else "-"
                print(
                    f"{kind}  {_human_size(e.size):>9}  {_format_mtime(e.mtime)}  {e.name}",
                    file=self.stdout,
                )
        except MiniFileError as exc:
            print(f"Error: {exc}", file=sys.stderr)

    def do_cat(self, arg: str) -> None:
        """Print remote text file: cat <remote_file>"""
        if not arg.strip():
            print("Usage: cat <remote_file>", file=sys.stderr)
            return
        try:
            text, _ = self.client.read_text(self._resolve(arg))
            self.stdout.write(text)
            if text and not text.endswith("\n"):
                self.stdout.write("\n")
        except MiniFileError as exc:
            print(f"Error: {exc}", file=sys.stderr)

    def do_mkdir(self, arg: str) -> None:
        """Create remote directory: mkdir <dir>"""
        if not arg.strip():
            print("Usage: mkdir <dir>", file=sys.stderr)
            return
        try:
            created = self.client.mkdir(self._resolve(arg), parents=True)
            print(created, file=self.stdout)
        except MiniFileError as exc:
            print(f"Error: {exc}", file=sys.stderr)

    def do_rm(self, arg: str) -> None:
        """Remove remote file or folder recursively: rm <path>"""
        if not arg.strip():
            print("Usage: rm <path>", file=sys.stderr)
            return
        try:
            self.client.remove(self._resolve(arg), recursive=True)
        except MiniFileError as exc:
            print(f"Error: {exc}", file=sys.stderr)

    def do_mv(self, arg: str) -> None:
        """Rename remote file/folder: mv <src> <dst>"""
        parts = shlex.split(arg)
        if len(parts) != 2:
            print("Usage: mv <src> <dst>", file=sys.stderr)
            return
        try:
            self.client.rename(self._resolve(parts[0]), self._resolve(parts[1]))
        except MiniFileError as exc:
            print(f"Error: {exc}", file=sys.stderr)

    def do_get(self, arg: str) -> None:
        """Download remote file/dir: get <remote> [local]"""
        parts = shlex.split(arg)
        if not parts:
            print("Usage: get <remote> [local]", file=sys.stderr)
            return
        rpath = self._resolve(parts[0])
        lpath = (
            Path(parts[1]).expanduser()
            if len(parts) > 1
            else (self.lcwd / (posixpath.basename(rpath) or "download"))
        )
        if not lpath.is_absolute():
            lpath = self.lcwd / lpath
        try:
            out = self.client.download(rpath, lpath, overwrite="overwrite")
            print(f"Downloaded -> {out}", file=self.stdout)
        except MiniFileError as exc:
            print(f"Error: {exc}", file=sys.stderr)

    def do_put(self, arg: str) -> None:
        """Upload local file/dir: put <local> [remote]"""
        parts = shlex.split(arg)
        if not parts:
            print("Usage: put <local> [remote]", file=sys.stderr)
            return
        lpath = Path(parts[0]).expanduser()
        if not lpath.is_absolute():
            lpath = self.lcwd / lpath
        rpath = self._resolve(parts[1]) if len(parts) > 1 else self.cwd
        try:
            dst = self.client.upload(lpath, rpath, overwrite="overwrite")
            print(f"Uploaded -> {dst}", file=self.stdout)
        except (MiniFileError, OSError) as exc:
            print(f"Error: {exc}", file=sys.stderr)

    def do_nomedia(self, arg: str) -> None:
        """Toggle .nomedia in directory: nomedia [dir]"""
        target = self._resolve(arg) if arg.strip() else self.cwd
        try:
            cur = self.client.nomedia(target, None)
            state = self.client.nomedia(target, not cur)
            print(f"{target}: .nomedia {'ON' if state else 'OFF'}", file=self.stdout)
        except MiniFileError as exc:
            print(f"Error: {exc}", file=sys.stderr)

    def do_exit(self, _arg: str) -> bool:
        """Exit the shell."""
        return True

    def do_quit(self, _arg: str) -> bool:
        """Exit the shell."""
        return True

    def do_EOF(self, _arg: str) -> bool:  # noqa: N802
        print("", file=self.stdout)
        return True


def _build_global_parent(include_defaults: bool) -> argparse.ArgumentParser:
    default_val = None if include_defaults else argparse.SUPPRESS
    bool_default = False if include_defaults else argparse.SUPPRESS
    p = argparse.ArgumentParser(add_help=False)
    p.add_argument("-H", "--host", default=default_val, help="Phone FTP host / IP")
    p.add_argument("-P", "--port", type=int, default=default_val, help="Phone FTP port (default: 2121)")
    p.add_argument("-u", "--user", default=default_val, help="FTP username (default: anonymous)")
    p.add_argument("-p", "--password", default=default_val, help="FTP password")
    p.add_argument("--timeout", type=float, default=default_val, help="Socket timeout in seconds")
    p.add_argument("--json", action="store_true", default=bool_default, help="Machine-readable JSON output")
    p.add_argument("-v", "--verbose", action="store_true", default=bool_default, help="Verbose logging")
    p.add_argument(
        "--no-update-hint",
        action="store_true",
        default=bool_default,
        help="Suppress cached update availability hint on stderr",
    )
    return p


def build_parser() -> argparse.ArgumentParser:
    root_globals = _build_global_parent(include_defaults=True)
    sub_globals = _build_global_parent(include_defaults=False)

    parser = argparse.ArgumentParser(
        prog="minifile",
        description="MiniFile PC Companion — Wireless FTP CLI & Local Web Desktop GUI",
        parents=[root_globals],
    )
    parser.add_argument(
        "--version", action="version", version=f"minifile-companion {__version__}"
    )

    sub = parser.add_subparsers(dest="command", metavar="<command>")

    # scan
    p_scan = sub.add_parser("scan", parents=[sub_globals], help="Auto-discover MiniFile phone on Wi-Fi LAN")
    p_scan.add_argument("--subnet", default=None, help="CIDR subnet to scan (e.g. 192.168.1.0/24)")
    p_scan.add_argument("--scan-timeout", type=float, default=0.3, help="Per-host TCP probe timeout (s)")
    p_scan.add_argument("--workers", type=int, default=128, help="Concurrent scanner threads")
    p_scan.add_argument("--save", action="store_true", help="Save discovered host/port as default")

    # ls
    p_ls = sub.add_parser("ls", parents=[sub_globals], help="List remote directory entries")
    p_ls.add_argument("remote_path", nargs="?", default="/", help="Remote directory path")
    p_ls.add_argument("-l", "--long", action="store_true", help="Long listing format")
    p_ls.add_argument("-a", "--all", action="store_true", help="Include hidden dotfiles")
    p_ls.add_argument("-R", "--recursive", action="store_true", help="Recursively list subdirectories")

    # tree
    p_tree = sub.add_parser("tree", parents=[sub_globals], help="Render remote directory tree")
    p_tree.add_argument("remote_path", nargs="?", default="/", help="Remote directory path")
    p_tree.add_argument("-L", "--level", type=int, default=None, help="Max display depth")
    p_tree.add_argument("-a", "--all", action="store_true", help="Include hidden dotfiles")

    # get
    p_get = sub.add_parser("get", parents=[sub_globals], help="Download file or directory from phone")
    p_get.add_argument("remote", help="Remote file or directory path")
    p_get.add_argument("local", nargs="?", default=None, help="Local destination path")
    p_get.add_argument("-r", "--recursive", action="store_true", help="Download directories recursively")
    p_get.add_argument("--resume", action="store_true", help="Resume partial file downloads via REST")
    grp_get = p_get.add_mutually_exclusive_group()
    grp_get.add_argument("--overwrite", action="store_true", help="Overwrite existing local files")
    grp_get.add_argument("--skip", action="store_true", help="Skip existing local files")

    # put
    p_put = sub.add_parser("put", parents=[sub_globals], help="Upload file or directory to phone")
    p_put.add_argument("local", help="Local file or directory path")
    p_put.add_argument("remote", nargs="?", default="/", help="Remote destination path")
    p_put.add_argument("-r", "--recursive", action="store_true", help="Upload directories recursively")
    p_put.add_argument("--resume", action="store_true", help="Resume partial file uploads when supported")
    grp_put = p_put.add_mutually_exclusive_group()
    grp_put.add_argument("--overwrite", action="store_true", help="Overwrite existing remote files")
    grp_put.add_argument("--skip", action="store_true", help="Skip existing remote files")

    # sync
    p_sync = sub.add_parser("sync", parents=[sub_globals], help="Synchronize local and phone directories")
    p_sync.add_argument("local_dir", help="Local directory")
    p_sync.add_argument("remote_dir", help="Remote directory on phone")
    grp_sync = p_sync.add_mutually_exclusive_group(required=True)
    grp_sync.add_argument("--to-phone", action="store_true", help="One-way sync PC -> Phone")
    grp_sync.add_argument("--to-pc", action="store_true", help="One-way sync Phone -> PC")
    grp_sync.add_argument("--both", action="store_true", help="Bidirectional sync (newest wins, never deletes)")
    p_sync.add_argument("--delete", action="store_true", help="Mirror deletions in one-way sync")
    p_sync.add_argument("--dry-run", action="store_true", help="Show sync plan without making changes")
    p_sync.add_argument("--exclude", action="append", default=[], help="Glob pattern to exclude (repeatable)")
    p_sync.add_argument("-y", "--yes", action="store_true", help="Skip confirmation prompt for --delete")

    # cat
    p_cat = sub.add_parser("cat", parents=[sub_globals], help="View remote text file contents")
    p_cat.add_argument("remote_file", help="Remote text file path")
    p_cat.add_argument("--encoding", default=None, help="Character encoding (auto-detected if omitted)")
    p_cat.add_argument("-n", "--number", action="store_true", help="Number output lines")

    # edit
    p_edit = sub.add_parser("edit", parents=[sub_globals], help="Edit remote text file in local $EDITOR")
    p_edit.add_argument("remote_file", help="Remote text file path")
    p_edit.add_argument("--editor", default=None, help="Editor command (default: $VISUAL / $EDITOR / nano)")
    p_edit.add_argument("--encoding", default=None, help="Text encoding")
    p_edit.add_argument("--backup", action="store_true", help="Save <file>.bak backup on phone before overwriting")

    # mkdir
    p_mkdir = sub.add_parser(
        "mkdir",
        parents=[sub_globals],
        conflict_handler="resolve",
        help="Create remote directory",
    )
    p_mkdir.add_argument("remote_dir", help="Remote directory path to create")
    p_mkdir.add_argument("-p", "--parents", action="store_true", help="Create parent directories as needed")

    # rm
    p_rm = sub.add_parser("rm", parents=[sub_globals], help="Delete remote files or directories")
    p_rm.add_argument("remote_paths", nargs="+", help="Remote path(s) to delete")
    p_rm.add_argument("-r", "-R", "--recursive", action="store_true", help="Remove directories recursively")
    p_rm.add_argument("-f", "--force", action="store_true", help="Skip confirmation prompt")

    # mv
    p_mv = sub.add_parser("mv", parents=[sub_globals], help="Rename or move a remote file/folder")
    p_mv.add_argument("src", help="Source remote path")
    p_mv.add_argument("dst", help="Destination remote path")
    p_mv.add_argument(
        "--copy-fallback",
        action="store_true",
        help="Fall back to download+upload+delete if RNTO fails across storage backends",
    )

    # nomedia
    p_nm = sub.add_parser("nomedia", parents=[sub_globals], help="Manage or toggle .nomedia in a remote folder")
    p_nm.add_argument("remote_dir", help="Remote directory path")
    grp_nm = p_nm.add_mutually_exclusive_group()
    grp_nm.add_argument("--on", action="store_true", help="Create .nomedia")
    grp_nm.add_argument("--off", action="store_true", help="Remove .nomedia")
    grp_nm.add_argument("--status", action="store_true", help="Query .nomedia existence without changing it")

    # shell
    sub.add_parser("shell", parents=[sub_globals], help="Start interactive REPL shell")

    # gui & web
    for cmd_name, desc in (
        ("gui", "Start Desktop Companion GUI (Tkinter if available, else Web UI)"),
        ("web", "Start localhost Web Desktop Companion in browser"),
    ):
        p_ui = sub.add_parser(cmd_name, parents=[sub_globals], help=desc)
        p_ui.add_argument("--ui-port", "--web-port", dest="ui_port", type=int, default=8765, help="Local HTTP port (default: 8765)")
        p_ui.add_argument("--no-browser", action="store_true", help="Do not launch browser automatically")
        p_ui.add_argument("--jail", default=None, help="Confine local PC browser pane to this directory")
        if cmd_name == "gui":
            p_ui.add_argument("--tk", action="store_true", help="Prefer native Tkinter window when available")

    # update
    p_upd = sub.add_parser("update", parents=[sub_globals], help="Check for or download PC Companion updates from GitHub")
    p_upd.add_argument("--check", action="store_true", help="Check latest GitHub release (exit 10 if update available)")
    p_upd.add_argument("--check-only-notify", action="store_true", help="Refresh cached release check quietly")
    p_upd.add_argument("--pre", action="store_true", help="Include pre-release versions")
    p_upd.add_argument("--apply", action="store_true", help="Swap staged companion directory in place (non-git checkouts only)")
    p_upd.add_argument("--dest", default=None, help="Directory to store downloaded release ZIP")
    p_upd.add_argument("--install-dir", default=None, help="Companion root directory to stage/apply next to")
    p_upd.add_argument("--allow-http-loopback", action="store_true", help=argparse.SUPPRESS)

    return parser


def _resolve_connection_params(args: argparse.Namespace, store: ConfigStore) -> dict[str, Any]:
    cfg = store.load()
    host = (
        args.host
        or os.environ.get("MINIFILE_HOST")
        or cfg.host
        or "127.0.0.1"
    )
    env_port = os.environ.get("MINIFILE_PORT")
    port = (
        int(args.port)
        if args.port is not None
        else int(env_port)
        if env_port
        else int(cfg.port or 2121)
    )
    user = (
        args.user
        or os.environ.get("MINIFILE_USER")
        or cfg.user
        or "anonymous"
    )
    password = (
        args.password
        if args.password is not None
        else os.environ.get("MINIFILE_PASSWORD")
        or os.environ.get("MINIFILE_PASS")
        or cfg.password
        or "minifile@"
    )
    timeout = float(args.timeout) if args.timeout is not None else 10.0
    return {
        "host": host,
        "port": port,
        "user": user,
        "password": password,
        "timeout": timeout,
    }


def _open_connected_client(args: argparse.Namespace, store: ConfigStore) -> MiniFileClient:
    params = _resolve_connection_params(args, store)
    cli = MiniFileClient(**params)
    cli.connect()
    return cli


def _render_tree_node(
    client: MiniFileClient,
    dir_path: str,
    show_all: bool,
    max_depth: int | None,
    cur_depth: int = 0,
    prefix: str = "",
    lines_out: list[str] | None = None,
    counts: dict[str, int] | None = None,
) -> dict[str, Any]:
    if lines_out is None:
        lines_out = []
    if counts is None:
        counts = {"dirs": 0, "files": 0}
    node: dict[str, Any] = {"path": dir_path, "children": []}
    if max_depth is not None and cur_depth >= max_depth:
        return node

    entries = client.list(dir_path, hidden=show_all)
    for idx, entry in enumerate(entries):
        is_last = idx == len(entries) - 1
        branch = "└── " if is_last else "├── "
        suffix = "/" if entry.is_dir else ""
        lines_out.append(f"{prefix}{branch}{entry.name}{suffix}")
        item_dict = entry.to_dict()
        if entry.is_dir:
            counts["dirs"] += 1
            sub_prefix = prefix + ("    " if is_last else "│   ")
            sub_tree = _render_tree_node(
                client,
                entry.path,
                show_all=show_all,
                max_depth=max_depth,
                cur_depth=cur_depth + 1,
                prefix=sub_prefix,
                lines_out=lines_out,
                counts=counts,
            )
            item_dict["children"] = sub_tree["children"]
        else:
            counts["files"] += 1
        node["children"].append(item_dict)
    return node


def _cmd_update(args: argparse.Namespace, store: ConfigStore) -> int:
    allow_loop = bool(
        getattr(args, "allow_http_loopback", False)
        or os.environ.get("MINIFILE_ALLOW_HTTP_LOOPBACK") == "1"
    )
    res = check_latest(
        current=__version__,
        include_pre=bool(args.pre),
        allow_http_loopback=allow_loop,
    )
    record_check_in_config(store, res, forced=True)

    if args.check_only_notify:
        if args.json:
            print(json.dumps(res.to_dict(), ensure_ascii=False))
        return EXIT_OK

    pub_date = (res.published_at or "")[:10]
    date_part = f" ({pub_date})" if pub_date else ""
    if args.check:
        if args.json:
            print(json.dumps(res.to_dict(), ensure_ascii=False))
        else:
            state_txt = "Update available" if res.available else "Up to date"
            print(
                f"Current {res.current} · Latest {res.latest_tag}{date_part} · {state_txt}"
            )
        return EXIT_UPDATE_AVAILABLE if res.available else EXIT_OK

    if not res.available:
        if args.json:
            print(
                json.dumps(
                    {
                        "updated": False,
                        "current": res.current,
                        "latest_tag": res.latest_tag,
                        "message": "Already up to date",
                    },
                    ensure_ascii=False,
                )
            )
        else:
            print(f"Already up to date (v{res.current}).")
        return EXIT_OK

    if not res.asset_url or not res.asset_name:
        raise UpdateError(
            f"Release {res.latest_tag} has no downloadable companion .zip asset"
        )

    cache_dest = (
        Path(args.dest).expanduser()
        if args.dest
        else (default_cache_dir() / "updates")
    )
    expected_sha: str | None = None
    if res.checksums_url:
        expected_sha = fetch_sha256_for_asset(
            res.checksums_url,
            res.asset_name,
            allow_http_loopback=allow_loop,
        )

    zip_path = download_asset(
        res.asset_url,
        dest_dir=cache_dest,
        expected_size=res.asset_size,
        sha256=expected_sha,
        progress=_make_progress_printer(not args.json and sys.stderr.isatty()),
        allow_http_loopback=allow_loop,
        filename=res.asset_name,
    )
    verified_ver = verify_companion_zip(zip_path)
    if verified_ver != res.latest_version:
        raise UpdateError(
            f"Archive version ({verified_ver}) does not match release tag ({res.latest_tag})"
        )

    install_root = (
        Path(args.install_dir).expanduser().resolve()
        if args.install_dir
        else Path(__file__).resolve().parent.parent
    )
    result_path = stage_and_apply(
        zip_path,
        install_dir=install_root,
        apply=bool(args.apply),
        current_version=__version__,
    )

    if args.json:
        print(
            json.dumps(
                {
                    "updated": True,
                    "applied_in_place": bool(args.apply),
                    "version": verified_ver,
                    "path": str(result_path),
                    "archive": str(zip_path),
                },
                ensure_ascii=False,
            )
        )
    else:
        if args.apply:
            print(f"Updated MiniFile Companion in place to v{verified_ver} at {result_path}")
        else:
            print(
                f"Staged MiniFile Companion v{verified_ver} at: {result_path}\n"
                f"Run it with: cd {shlex.quote(str(result_path))} && python3 -m minifile_companion --help"
            )
    return EXIT_OK


def main(argv: Sequence[str] | None = None) -> int:
    raw_args = list(sys.argv[1:] if argv is None else argv)
    # Support optional `cli` prefix (`python3 -m minifile_companion cli ls /`) per DESIGN brief
    if raw_args and raw_args[0] == "cli":
        raw_args = raw_args[1:]

    parser = build_parser()
    try:
        args = parser.parse_args(raw_args)
    except SystemExit as exc:
        return int(exc.code) if isinstance(exc.code, int) else EXIT_USAGE

    if not args.command:
        parser.print_help(sys.stderr)
        return EXIT_USAGE

    store = ConfigStore()

    try:
        if args.command == "update":
            return _cmd_update(args, store)

        if not args.json and not args.no_update_hint:
            maybe_emit_cached_update_hint(store, current=__version__)

        if args.command == "scan":
            port_to_scan = int(args.port) if args.port is not None else int(store.load().port or 2121)
            probe_timeout = (
                float(args.timeout)
                if args.timeout is not None
                else float(args.scan_timeout)
            )
            found = discover(
                subnet=args.subnet,
                port=port_to_scan,
                timeout=probe_timeout,
                workers=int(args.workers),
            )
            if args.save and found:
                store.update_connection(host=found[0].host, port=found[0].port)
            if args.json:
                print(json.dumps([d.to_dict() for d in found], ensure_ascii=False))
            else:
                if not found:
                    print("No MiniFile FTP servers discovered.", file=sys.stderr)
                else:
                    print(f"{'HOST':<16} {'PORT':<6} {'RTT':>8}  BANNER")
                    for d in found:
                        print(f"{d.host:<16} {d.port:<6} {d.rtt_ms:>6.1f}ms  {d.banner}")
            return EXIT_OK

        if args.command in ("gui", "web"):
            conn_info: dict[str, Any] | None = None
            cfg = store.load()
            explicit_host = args.host or os.environ.get("MINIFILE_HOST") or cfg.host
            if explicit_host:
                conn_info = _resolve_connection_params(args, store)
            # Note: for `gui`/`web`, `-P/--port` if explicitly passed without `--ui-port`?
            # Keep `-P` as FTP port and `--ui-port` (default 8765) as local HTTP port;
            # also check if user ran `gui --port 8900` without `-H`:
            ui_port = int(args.ui_port)
            if args.port is not None and args.host is None and args.ui_port == 8765:
                ui_port = int(args.port)
            if args.command == "web":
                return run_webui(
                    port=ui_port,
                    open_browser=not args.no_browser,
                    jail=args.jail,
                    initial_ftp=conn_info,
                    verbose=args.verbose,
                )
            return run_gui(
                port=ui_port,
                open_browser=not args.no_browser,
                jail=args.jail,
                prefer_tk=bool(getattr(args, "tk", False)),
                initial_ftp=conn_info,
                verbose=args.verbose,
            )

        # Commands below require an active FTP connection
        with _open_connected_client(args, store) as client:
            if args.command == "ls":
                rpath = normalize_remote_path(args.remote_path)
                if args.recursive:
                    tree_entries: list[dict[str, Any]] = []
                    for dirpath, dirs, files in client.walk(rpath, hidden=args.all):
                        combined = sorted(
                            [*dirs, *files], key=lambda e: (not e.is_dir, e.name.lower())
                        )
                        if args.json:
                            tree_entries.extend(e.to_dict() for e in combined)
                        else:
                            print(f"{dirpath}:")
                            for e in combined:
                                if args.long:
                                    kind = "d" if e.is_dir else "-"
                                    print(
                                        f"  {kind} {e.size:>10} {_format_mtime(e.mtime)} {e.name}"
                                    )
                                else:
                                    print(f"  {e.name}{'/' if e.is_dir else ''}")
                            print()
                    if args.json:
                        print(
                            json.dumps(
                                {"path": rpath, "entries": tree_entries},
                                ensure_ascii=False,
                            )
                        )
                else:
                    entries = client.list(rpath, hidden=args.all)
                    if args.json:
                        print(
                            json.dumps(
                                {
                                    "path": rpath,
                                    "entries": [e.to_dict() for e in entries],
                                },
                                ensure_ascii=False,
                            )
                        )
                    else:
                        for e in entries:
                            if args.long:
                                kind = "d" if e.is_dir else "-"
                                print(
                                    f"{kind} {e.size:>10} {_format_mtime(e.mtime)} {e.name}"
                                )
                            else:
                                print(f"{e.name}{'/' if e.is_dir else ''}")
                return EXIT_OK

            if args.command == "tree":
                rpath = normalize_remote_path(args.remote_path)
                lines = [rpath]
                counts = {"dirs": 0, "files": 0}
                tree_obj = _render_tree_node(
                    client,
                    rpath,
                    show_all=args.all,
                    max_depth=args.level,
                    lines_out=lines,
                    counts=counts,
                )
                if args.json:
                    tree_obj["counts"] = counts
                    print(json.dumps(tree_obj, ensure_ascii=False))
                else:
                    for ln in lines:
                        print(ln)
                    print(f"\n{counts['dirs']} directories, {counts['files']} files")
                return EXIT_OK

            if args.command == "get":
                rpath = normalize_remote_path(args.remote)
                local_dst = (
                    Path(args.local).expanduser()
                    if args.local
                    else (Path.cwd() / (posixpath.basename(rpath) or "download"))
                )
                policy = "skip" if args.skip else "overwrite"
                out_path = client.download(
                    rpath,
                    local_dst,
                    resume=args.resume,
                    progress=_make_progress_printer(not args.json and sys.stderr.isatty()),
                    overwrite=policy,
                )
                if args.json:
                    print(
                        json.dumps(
                            {"remote": rpath, "local": str(out_path)}, ensure_ascii=False
                        )
                    )
                else:
                    print(f"Downloaded {rpath} -> {out_path}")
                return EXIT_OK

            if args.command == "put":
                local_src = Path(args.local).expanduser()
                if not local_src.exists():
                    raise MiniFileError(f"Local path not found: {local_src}")
                policy = "skip" if args.skip else "overwrite"
                dst_remote = client.upload(
                    local_src,
                    args.remote,
                    progress=_make_progress_printer(not args.json and sys.stderr.isatty()),
                    overwrite=policy,
                    resume=args.resume,
                )
                if args.json:
                    print(
                        json.dumps(
                            {"local": str(local_src), "remote": dst_remote},
                            ensure_ascii=False,
                        )
                    )
                else:
                    print(f"Uploaded {local_src} -> {dst_remote}")
                return EXIT_OK

            if args.command == "sync":
                direction = (
                    "to_phone"
                    if args.to_phone
                    else "to_pc"
                    if args.to_pc
                    else "both"
                )
                if args.delete and not args.dry_run and not args.yes and sys.stdin.isatty():
                    ans = input("Mirror deletions enabled (--delete). Continue? [y/N]: ")
                    if ans.strip().lower() not in ("y", "yes"):
                        print("Aborted.", file=sys.stderr)
                        return EXIT_ERROR
                plan = client.sync(
                    args.local_dir,
                    args.remote_dir,
                    direction=direction,
                    delete=args.delete,
                    dry_run=args.dry_run,
                    exclude=args.exclude,
                    progress=_make_progress_printer(not args.json and sys.stderr.isatty()),
                )
                if args.json:
                    print(json.dumps(plan.to_dict(), ensure_ascii=False))
                else:
                    if args.dry_run:
                        print(f"Dry-run sync plan ({plan.direction}): {len(plan.actions)} action(s)")
                        for act in plan.actions:
                            print(f"  [{act.op}] {act.rel_path} ({act.reason})")
                    else:
                        print(
                            f"Sync ({plan.direction}): ↑{plan.uploaded} ↓{plan.downloaded} "
                            f"del_remote={plan.deleted_remote} del_local={plan.deleted_local} "
                            f"skipped={plan.skipped}"
                        )
                if plan.errors:
                    for rel_p, err_msg in plan.errors:
                        print(f"Failed {rel_p}: {err_msg}", file=sys.stderr)
                    return EXIT_PARTIAL
                return EXIT_OK

            if args.command == "cat":
                text, used_enc = client.read_text(args.remote_file, encoding=args.encoding)
                if args.json:
                    print(
                        json.dumps(
                            {
                                "path": normalize_remote_path(args.remote_file),
                                "encoding": used_enc,
                                "text": text,
                            },
                            ensure_ascii=False,
                        )
                    )
                elif args.number:
                    for idx, line in enumerate(text.splitlines(keepends=True), start=1):
                        sys.stdout.write(f"{idx:6d}\t{line}")
                    if text and not text.endswith("\n"):
                        sys.stdout.write("\n")
                else:
                    sys.stdout.write(text)
                return EXIT_OK

            if args.command == "edit":
                rpath = normalize_remote_path(args.remote_file)
                suffix = Path(posixpath.basename(rpath)).suffix or ".txt"
                orig_text, orig_enc = client.read_text(rpath, encoding=args.encoding)
                use_enc = args.encoding or orig_enc or "utf-8"

                editor_cmd = (
                    args.editor
                    or os.environ.get("VISUAL")
                    or os.environ.get("EDITOR")
                    or ("notepad" if os.name == "nt" else "nano")
                )
                fd, tmp_file = tempfile.mkstemp(prefix="minifile-edit-", suffix=suffix)
                try:
                    with os.fdopen(fd, "w", encoding=use_enc) as fh:
                        fh.write(orig_text)
                    cmd_tokens = shlex.split(editor_cmd, posix=(os.name != "nt"))
                    cmd_tokens.append(tmp_file)
                    proc = subprocess.run(cmd_tokens, check=False)
                    if proc.returncode != 0:
                        raise MiniFileError(
                            f"Editor exited with non-zero status {proc.returncode}"
                        )
                    new_bytes = Path(tmp_file).read_bytes()
                    new_text, _ = decode_text(new_bytes, encoding=use_enc)
                    changed = new_text != orig_text
                    if changed:
                        if args.backup:
                            client.write_text(f"{rpath}.bak", orig_text, encoding=use_enc)
                        client.write_text(rpath, new_text, encoding=use_enc)
                finally:
                    try:
                        os.unlink(tmp_file)
                    except OSError:
                        pass

                if args.json:
                    print(
                        json.dumps(
                            {"path": rpath, "changed": changed, "encoding": use_enc},
                            ensure_ascii=False,
                        )
                    )
                else:
                    print(f"{'Saved changes to' if changed else 'Unchanged:'} {rpath}")
                return EXIT_OK

            if args.command == "mkdir":
                created = client.mkdir(args.remote_dir, parents=args.parents)
                if args.json:
                    print(json.dumps({"path": created}, ensure_ascii=False))
                else:
                    print(created)
                return EXIT_OK

            if args.command == "rm":
                if not args.force and sys.stdin.isatty():
                    ans = input(f"Delete {len(args.remote_paths)} remote path(s)? [y/N]: ")
                    if ans.strip().lower() not in ("y", "yes"):
                        print("Aborted.", file=sys.stderr)
                        return EXIT_ERROR
                deleted: list[str] = []
                failed: list[tuple[str, str]] = []
                for rp in args.remote_paths:
                    try:
                        norm_rp = normalize_remote_path(rp)
                        client.remove(norm_rp, recursive=args.recursive)
                        deleted.append(norm_rp)
                    except MiniFileError as exc:
                        failed.append((rp, str(exc)))

                if args.json:
                    print(
                        json.dumps(
                            {
                                "deleted": deleted,
                                "failed": [{"path": p, "error": e} for p, e in failed],
                            },
                            ensure_ascii=False,
                        )
                    )
                if failed and deleted:
                    raise PartialTransferError(
                        "Some paths failed to delete",
                        succeeded=len(deleted),
                        failed=failed,
                    )
                if failed:
                    raise MiniFileError(failed[0][1])
                return EXIT_OK

            if args.command == "mv":
                dst = client.rename(
                    args.src, args.dst, copy_fallback=bool(args.copy_fallback)
                )
                if args.json:
                    print(
                        json.dumps(
                            {"src": normalize_remote_path(args.src), "dst": dst},
                            ensure_ascii=False,
                        )
                    )
                else:
                    print(f"{normalize_remote_path(args.src)} -> {dst}")
                return EXIT_OK

            if args.command == "nomedia":
                rdir = normalize_remote_path(args.remote_dir)
                if args.status:
                    st_val = client.nomedia(rdir, None)
                elif args.on:
                    st_val = client.nomedia(rdir, True)
                elif args.off:
                    st_val = client.nomedia(rdir, False)
                else:
                    # Default without flag: toggle .nomedia state
                    cur_val = client.nomedia(rdir, None)
                    st_val = client.nomedia(rdir, not cur_val)
                if args.json:
                    print(
                        json.dumps({"path": rdir, "nomedia": st_val}, ensure_ascii=False)
                    )
                else:
                    print(f"{rdir}: .nomedia {'present' if st_val else 'absent'}")
                return EXIT_OK

            if args.command == "shell":
                repl = MiniFileShell(client)
                repl.cmdloop()
                return EXIT_OK

    except PartialTransferError as exc:
        for p, msg in exc.failed:
            print(f"Error ({p}): {msg}", file=sys.stderr)
        return EXIT_PARTIAL
    except (ConnectionAuthError, UpdateNetworkError) as exc:
        if getattr(args, "json", False):
            print(json.dumps({"error": str(exc)}), file=sys.stderr)
        else:
            print(f"Connection/Auth error: {exc}", file=sys.stderr)
        return EXIT_CONN_AUTH
    except (MiniFileError, UpdateError, ValueError, OSError) as exc:
        if getattr(args, "json", False):
            print(json.dumps({"error": str(exc)}), file=sys.stderr)
        else:
            print(f"Error: {exc}", file=sys.stderr)
        return EXIT_ERROR

    return EXIT_OK


if __name__ == "__main__":
    raise SystemExit(main())
