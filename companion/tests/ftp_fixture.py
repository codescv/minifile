"""Embedded pure-Python FTP server fixture implementing ARCHITECTURE §6.1 & §6.4."""

from __future__ import annotations

import os
import posixpath
import shutil
import socket
import stat
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from typing import Any

_COMPANION_ROOT = str(Path(__file__).resolve().parent.parent)
if _COMPANION_ROOT not in sys.path:
    sys.path.insert(0, _COMPANION_ROOT)

from minifile_companion.client import MiniFileClient  # noqa: E402

_EN_MONTHS = (
    "Jan",
    "Feb",
    "Mar",
    "Apr",
    "May",
    "Jun",
    "Jul",
    "Aug",
    "Sep",
    "Oct",
    "Nov",
    "Dec",
)


def _format_ftp_mtime(epoch: float) -> str:
    tm = time.gmtime(epoch)
    return (
        f"{tm.tm_year:04d}{tm.tm_mon:02d}{tm.tm_mday:02d}"
        f"{tm.tm_hour:02d}{tm.tm_min:02d}{tm.tm_sec:02d}"
    )


def _format_list_date(epoch: float, now: float) -> str:
    tm = time.gmtime(epoch)
    mon = _EN_MONTHS[tm.tm_mon - 1]
    if abs(now - epoch) < 180 * 86400:
        return f"{mon} {tm.tm_mday:2d} {tm.tm_hour:02d}:{tm.tm_min:02d}"
    return f"{mon} {tm.tm_mday:2d}  {tm.tm_year:04d}"


class FtpJailError(Exception):
    """Raised when a virtual FTP path fails validation or canonical root confinement."""


class FtpPathResolver:
    """Virtual root path resolver matching Android FtpPathResolver + LocalFileSystem.isInside."""

    def __init__(self, root_dir: Path) -> None:
        self.root_dir = Path(os.path.realpath(root_dir))

    def is_inside(self, candidate: Path) -> bool:
        root_real = os.path.realpath(self.root_dir)
        cand_real = os.path.realpath(candidate)
        return cand_real == root_real or cand_real.startswith(root_real + os.sep)

    def resolve(self, cwd: str, raw_arg: str) -> tuple[str, Path]:
        if "\x00" in raw_arg or "\\" in raw_arg:
            raise FtpJailError("Illegal path character")

        combined = raw_arg if raw_arg.startswith("/") else posixpath.join(cwd, raw_arg)
        segs: list[str] = []
        for seg in combined.split("/"):
            if not seg or seg == ".":
                continue
            if seg == "..":
                if segs:
                    segs.pop()
                continue
            if "\\" in seg or "\x00" in seg:
                raise FtpJailError("Illegal segment")
            segs.append(seg)

        vpath = "/" + "/".join(segs)
        target = self.root_dir.joinpath(*segs) if segs else self.root_dir
        if not self.is_inside(target):
            raise FtpJailError("Symlink or canonical path escapes FTP root")
        return vpath, target


class _FtpSession(threading.Thread):
    """Single client control session on EmbeddedFtpServer."""

    def __init__(self, server: EmbeddedFtpServer, conn: socket.socket, peer: Any) -> None:
        super().__init__(daemon=True)
        self.server = server
        self.conn = conn
        self.peer = peer
        self.cwd = "/"
        self.authed = False
        self.pending_user: str | None = None
        self.rest_offset: int = 0
        self.rnfr_path: Path | None = None
        self.pasv_listener: socket.socket | None = None
        self.active_target: tuple[str, int] | None = None
        self.closed = False

    def send_reply(self, code: int, message: str) -> None:
        lines = message.splitlines() or [""]
        if len(lines) == 1:
            payload = f"{code} {lines[0]}\r\n"
        else:
            body = "".join(f"{code}-{ln}\r\n" for ln in lines[:-1])
            payload = f"{body}{code} {lines[-1]}\r\n"
        self.conn.sendall(payload.encode("utf-8"))

    def _close_data_listener(self) -> None:
        if self.pasv_listener is not None:
            try:
                self.pasv_listener.close()
            except OSError:
                pass
            self.pasv_listener = None
        self.active_target = None

    def _open_data_socket(self) -> socket.socket:
        if self.pasv_listener is not None:
            listener = self.pasv_listener
            self.pasv_listener = None
            try:
                listener.settimeout(10.0)
                data_sock, data_peer = listener.accept()
                # Anti-bounce check: data peer IP must match control peer IP
                if data_peer[0] != self.peer[0]:
                    data_sock.close()
                    raise PermissionError("Data peer IP mismatch")
                return data_sock
            finally:
                listener.close()
        if self.active_target is not None:
            host, port = self.active_target
            self.active_target = None
            return socket.create_connection((host, port), timeout=10.0)
        raise RuntimeError("No PASV/EPSV/PORT data channel established")

    def close_session(self) -> None:
        self.closed = True
        self._close_data_listener()
        try:
            self.conn.close()
        except OSError:
            pass

    def _bind_passive_listener(self) -> socket.socket:
        self._close_data_listener()
        af = self.conn.family
        bind_ip = "::1" if af == socket.AF_INET6 else "127.0.0.1"
        if self.server.passive_ports:
            for p in self.server.passive_ports:
                s = socket.socket(af, socket.SOCK_STREAM)
                s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                try:
                    s.bind((bind_ip, p))
                    s.listen(1)
                    self.pasv_listener = s
                    return s
                except OSError:
                    s.close()
            raise OSError("No free passive port in configured range")
        s = socket.socket(af, socket.SOCK_STREAM)
        s.bind((bind_ip, 0))
        s.listen(1)
        self.pasv_listener = s
        return s

    def _format_mlsx_entry(self, entry_path: Path, display_name: str) -> str:
        st = entry_path.stat()
        is_dir = stat.S_ISDIR(st.st_mode)
        kind = "dir" if is_dir else "file"
        size = 0 if is_dir else st.st_size
        perm = "el" if is_dir else "r"
        if not self.server.read_only:
            perm += "cmdf" if is_dir else "adfw"
        facts = [f"type={kind}", f"size={size}"]
        if not self.server.omit_modify and st.st_mtime > 0:
            facts.append(f"modify={_format_ftp_mtime(st.st_mtime)}")
        facts.append(f"perm={perm}")
        return ";".join(facts) + f"; {display_name}"

    def run(self) -> None:
        try:
            self.conn.settimeout(self.server.idle_timeout)
            self.send_reply(220, self.server.banner)
            buf = b""
            while not self.closed and not self.server.stopping:
                try:
                    chunk = self.conn.recv(4096)
                except socket.timeout:
                    try:
                        self.send_reply(421, "Idle timeout, closing control connection.")
                    except OSError:
                        pass
                    break
                if not chunk:
                    break
                buf += chunk
                while b"\n" in buf:
                    raw_line, buf = buf.split(b"\n", 1)
                    line = raw_line.rstrip(b"\r").decode("utf-8", errors="replace")
                    if not line:
                        continue
                    self._handle_command(line)
                    if self.closed:
                        break
        except OSError:
            pass
        finally:
            self.close_session()
            self.server.unregister_session(self)

    def _handle_command(self, line: str) -> None:
        if " " in line:
            verb, arg = line.split(" ", 1)
        else:
            verb, arg = line, ""
        cmd = verb.upper()
        self.server.command_log.append((cmd, arg))

        if cmd == "QUIT":
            self.send_reply(221, "Goodbye.")
            self.closed = True
            return
        if cmd == "NOOP":
            self.send_reply(200, "NOOP ok.")
            return
        if cmd == "SYST":
            self.send_reply(215, "UNIX Type: L8")
            return
        if cmd == "FEAT":
            feats = ["211-Features:"]
            if self.server.enable_utf8:
                feats.append(" UTF8")
            if self.server.enable_mlsx:
                feats.append(" MLST type*;size*;modify*;perm*;")
                feats.append(" MLSD")
            feats.extend(
                [
                    " SIZE",
                    " MDTM",
                    " REST STREAM",
                    " EPSV",
                    " PASV",
                    " TVFS",
                    "211 End",
                ]
            )
            self.conn.sendall(("".join(f"{ln}\r\n" for ln in feats)).encode("utf-8"))
            return
        if cmd == "OPTS":
            if arg.strip().upper() == "UTF8 ON":
                self.send_reply(200, "UTF8 mode enabled.")
            else:
                self.send_reply(501, "Unsupported OPTS.")
            return
        if cmd == "HELP":
            self.send_reply(214, "MiniFile FTP Server")
            return
        if cmd == "USER":
            self.pending_user = arg
            if self.server.credentials is None:
                self.send_reply(331, "Anonymous login ok, send password.")
            else:
                self.send_reply(331, "Username ok, need password.")
            return
        if cmd == "PASS":
            if self.pending_user is None:
                self.send_reply(503, "Login with USER first.")
                return
            if self.server.credentials is None:
                self.authed = True
                self.send_reply(230, "User logged in.")
            else:
                exp_u, exp_p = self.server.credentials
                if self.pending_user == exp_u and arg == exp_p:
                    self.authed = True
                    self.send_reply(230, "User logged in.")
                else:
                    self.authed = False
                    self.send_reply(530, "Login incorrect.")
            return

        if not self.authed:
            self.send_reply(530, "Please login with USER and PASS.")
            return

        if cmd in ("PWD", "XPWD"):
            quoted = self.cwd.replace('"', '""')
            self.send_reply(257, f'"{quoted}" is current directory.')
            return
        if cmd == "CDUP":
            arg = ".."
            cmd = "CWD"
        if cmd == "CWD":
            try:
                vpath, target = self.server.resolver.resolve(self.cwd, arg or "/")
            except FtpJailError:
                self.send_reply(550, "Access denied.")
                return
            if not target.is_dir():
                self.send_reply(550, "Not a directory.")
                return
            self.cwd = vpath
            self.send_reply(250, "Directory changed.")
            return
        if cmd == "TYPE":
            self.send_reply(200, f"Type set to {arg or 'I'}.")
            return
        if cmd == "MODE":
            self.send_reply(200, "Mode S ok.")
            return
        if cmd == "STRU":
            self.send_reply(200, "Structure F ok.")
            return
        if cmd == "PASV":
            if self.conn.family == socket.AF_INET6 or self.server.force_pasv_522:
                self.send_reply(522, "Network protocol not supported, use (1)")
                return
            listener = self._bind_passive_listener()
            local_ip = self.conn.getsockname()[0]
            port = listener.getsockname()[1]
            h1, h2, h3, h4 = local_ip.split(".")
            p1, p2 = divmod(port, 256)
            self.send_reply(
                227, f"Entering Passive Mode ({h1},{h2},{h3},{h4},{p1},{p2})"
            )
            return
        if cmd == "EPSV":
            if arg.strip().upper() == "ALL":
                self.send_reply(200, "EPSV ALL ok.")
                return
            listener = self._bind_passive_listener()
            port = listener.getsockname()[1]
            self.send_reply(229, f"Entering Extended Passive Mode (|||{port}|)")
            return
        if cmd == "PORT":
            nums = [int(x) for x in arg.strip().split(",")]
            host = ".".join(str(x) for x in nums[:4])
            port = nums[4] * 256 + nums[5]
            self._close_data_listener()
            self.active_target = (host, port)
            self.send_reply(200, "PORT command successful.")
            return
        if cmd == "REST":
            try:
                self.rest_offset = max(0, int(arg.strip()))
                self.send_reply(350, f"Restarting at {self.rest_offset}.")
            except ValueError:
                self.send_reply(501, "Invalid REST offset.")
            return

        # Read-only gate for mutating verbs
        if cmd in ("STOR", "APPE", "DELE", "MKD", "XMKD", "RMD", "XRMD", "RNFR", "RNTO"):
            if self.server.read_only:
                self.rest_offset = 0
                self.send_reply(550, "Read-only server")
                return

        if cmd == "SIZE":
            try:
                _, target = self.server.resolver.resolve(self.cwd, arg)
            except FtpJailError:
                self.send_reply(550, "Access denied.")
                return
            if not target.is_file():
                self.send_reply(550, "Not a regular file.")
                return
            self.send_reply(213, str(target.stat().st_size))
            return

        if cmd == "MDTM":
            try:
                _, target = self.server.resolver.resolve(self.cwd, arg)
            except FtpJailError:
                self.send_reply(550, "Access denied.")
                return
            if not target.exists():
                self.send_reply(550, "File not found.")
                return
            self.send_reply(213, _format_ftp_mtime(target.stat().st_mtime))
            return

        if cmd == "MLST":
            if not self.server.enable_mlsx:
                self.send_reply(502, "MLST disabled.")
                return
            try:
                vpath, target = self.server.resolver.resolve(self.cwd, arg or self.cwd)
            except FtpJailError:
                self.send_reply(550, "Access denied.")
                return
            if not target.exists():
                self.send_reply(550, "No such file or directory.")
                return
            fact_line = self._format_mlsx_entry(target, vpath)
            raw = f"250-Listing {vpath}\r\n {fact_line}\r\n250 End\r\n"
            self.conn.sendall(raw.encode("utf-8"))
            return

        if cmd in ("MLSD", "LIST", "NLST"):
            if cmd == "MLSD" and not self.server.enable_mlsx:
                self.send_reply(502, "MLSD disabled.")
                return
            try:
                _, target = self.server.resolver.resolve(self.cwd, arg or self.cwd)
            except FtpJailError:
                self.send_reply(550, "Access denied.")
                return
            if not target.is_dir():
                self.send_reply(550, "Not a directory.")
                return

            lines: list[str] = []
            now_ts = time.time()
            for child in sorted(target.iterdir(), key=lambda c: c.name):
                if not self.server.resolver.is_inside(child):
                    continue
                if cmd == "MLSD":
                    lines.append(self._format_mlsx_entry(child, child.name))
                elif cmd == "NLST":
                    lines.append(child.name)
                else:
                    st = child.stat()
                    perm = stat.filemode(st.st_mode)
                    sz = 0 if stat.S_ISDIR(st.st_mode) else st.st_size
                    dt = _format_list_date(st.st_mtime, now_ts)
                    lines.append(f"{perm} 1 owner group {sz:8d} {dt} {child.name}")

            try:
                data_sock = self._open_data_socket()
            except Exception:
                self.send_reply(425, "Can't open data connection.")
                return
            self.send_reply(150, "Opening data connection.")
            try:
                payload = "".join(f"{ln}\r\n" for ln in lines).encode("utf-8")
                data_sock.sendall(payload)
            finally:
                data_sock.close()
            self.send_reply(226, "Transfer complete.")
            return

        if cmd == "RETR":
            offset = self.rest_offset
            self.rest_offset = 0
            try:
                _, target = self.server.resolver.resolve(self.cwd, arg)
            except FtpJailError:
                self.send_reply(550, "Access denied.")
                return
            if not target.is_file():
                self.send_reply(550, "File not found.")
                return
            try:
                data_sock = self._open_data_socket()
            except Exception:
                self.send_reply(425, "Can't open data connection.")
                return
            self.send_reply(150, "Opening binary mode data connection.")
            try:
                with open(target, "rb") as fh:
                    if offset > 0:
                        fh.seek(offset)
                    while True:
                        buf = fh.read(65536)
                        if not buf:
                            break
                        if self.server.transfer_chunk_delay > 0:
                            time.sleep(self.server.transfer_chunk_delay)
                        data_sock.sendall(buf)
            except OSError:
                self.send_reply(426, "Connection closed; transfer aborted.")
                return
            finally:
                data_sock.close()
            self.send_reply(226, "Transfer complete.")
            return

        if cmd in ("STOR", "APPE"):
            offset = self.rest_offset
            self.rest_offset = 0
            if cmd == "STOR" and offset > 0 and self.server.reject_rest_stor:
                self._close_data_listener()
                self.send_reply(504, "REST not supported for STOR on this volume")
                return
            try:
                _, target = self.server.resolver.resolve(self.cwd, arg)
            except FtpJailError:
                self.send_reply(550, "Access denied.")
                return
            if not target.parent.is_dir() or not self.server.resolver.is_inside(target.parent):
                self._close_data_listener()
                self.send_reply(550, "Parent directory does not exist.")
                return
            if target.exists() and target.is_dir():
                self._close_data_listener()
                self.send_reply(550, "Target is a directory.")
                return
            try:
                data_sock = self._open_data_socket()
            except Exception:
                self.send_reply(425, "Can't open data connection.")
                return
            self.send_reply(150, "Ok to send data.")
            try:
                if cmd == "APPE":
                    fmode = "ab"
                elif offset > 0 and target.exists():
                    fmode = "r+b"
                else:
                    fmode = "wb"
                with open(target, fmode) as fh:
                    if cmd == "STOR" and offset > 0 and fmode == "r+b":
                        fh.seek(offset)
                        fh.truncate()
                    while True:
                        buf = data_sock.recv(65536)
                        if not buf:
                            break
                        if self.server.transfer_chunk_delay > 0:
                            time.sleep(self.server.transfer_chunk_delay)
                        fh.write(buf)
            finally:
                data_sock.close()
            self.send_reply(226, "Transfer complete.")
            return

        if cmd == "DELE":
            try:
                _, target = self.server.resolver.resolve(self.cwd, arg)
            except FtpJailError:
                self.send_reply(550, "Access denied.")
                return
            if not target.exists() or target.is_dir():
                self.send_reply(550, "File not found.")
                return
            target.unlink()
            self.send_reply(250, "File deleted.")
            return

        if cmd in ("MKD", "XMKD"):
            try:
                vpath, target = self.server.resolver.resolve(self.cwd, arg)
            except FtpJailError:
                self.send_reply(550, "Access denied.")
                return
            if not target.parent.is_dir() or target.exists():
                self.send_reply(550, "Cannot create directory.")
                return
            target.mkdir()
            quoted = vpath.replace('"', '""')
            self.send_reply(257, f'"{quoted}" created.')
            return

        if cmd in ("RMD", "XRMD"):
            try:
                vpath, target = self.server.resolver.resolve(self.cwd, arg)
            except FtpJailError:
                self.send_reply(550, "Access denied.")
                return
            if vpath == "/" or not target.is_dir():
                self.send_reply(550, "Not a removable directory.")
                return
            try:
                target.rmdir()
            except OSError:
                self.send_reply(550, "Directory not empty.")
                return
            self.send_reply(250, "Directory removed.")
            return

        if cmd == "RNFR":
            try:
                vpath, target = self.server.resolver.resolve(self.cwd, arg)
            except FtpJailError:
                self.send_reply(550, "Access denied.")
                return
            if vpath == "/" or not target.exists():
                self.send_reply(550, "Source not found.")
                return
            self.rnfr_path = target
            self.send_reply(350, "Ready for RNTO.")
            return

        if cmd == "RNTO":
            src = self.rnfr_path
            self.rnfr_path = None
            if src is None:
                self.send_reply(503, "RNFR required first.")
                return
            if self.server.reject_rename:
                self.send_reply(550, "Cross-backend rename not supported.")
                return
            try:
                _, dst = self.server.resolver.resolve(self.cwd, arg)
            except FtpJailError:
                self.send_reply(550, "Access denied.")
                return
            if not dst.parent.is_dir():
                self.send_reply(550, "Destination parent missing.")
                return
            os.replace(src, dst)
            self.send_reply(250, "Rename successful.")
            return

        if cmd in ("ABOR", "STAT"):
            self.send_reply(226, "OK.")
            return

        self.send_reply(502, f"Command {cmd} not implemented.")


class EmbeddedFtpServer:
    """In-process threaded FTP server for companion unit & integration tests."""

    def __init__(
        self,
        root_dir: Path | str,
        host: str = "127.0.0.1",
        port: int = 0,
        read_only: bool = False,
        credentials: tuple[str, str] | None = None,
        banner: str = "MiniFile FTP Server ready",
        idle_timeout: float = 30.0,
        enable_mlsx: bool = True,
        enable_utf8: bool = True,
        omit_modify: bool = False,
        reject_rest_stor: bool = False,
        reject_rename: bool = False,
        force_pasv_522: bool = False,
        passive_ports: range | None = None,
        transfer_chunk_delay: float = 0.0,
    ) -> None:
        self.root_dir = Path(root_dir)
        self.root_dir.mkdir(parents=True, exist_ok=True)
        self.resolver = FtpPathResolver(self.root_dir)
        self.host = host
        self.read_only = read_only
        self.credentials = credentials
        self.banner = banner
        self.idle_timeout = idle_timeout
        self.enable_mlsx = enable_mlsx
        self.enable_utf8 = enable_utf8
        self.omit_modify = omit_modify
        self.reject_rest_stor = reject_rest_stor
        self.reject_rename = reject_rename
        self.force_pasv_522 = force_pasv_522
        self.passive_ports = passive_ports
        self.transfer_chunk_delay = transfer_chunk_delay
        self.command_log: list[tuple[str, str]] = []

        af = socket.AF_INET6 if ":" in host else socket.AF_INET
        self._sock = socket.socket(af, socket.SOCK_STREAM)
        self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._sock.settimeout(0.05)
        self._sock.bind((host, port))
        self._sock.listen(16)
        self.port = int(self._sock.getsockname()[1])

        self.stopping = False
        self._sessions_lock = threading.Lock()
        self._sessions: set[_FtpSession] = set()
        self._accept_thread: threading.Thread | None = None

    def __enter__(self) -> EmbeddedFtpServer:
        self.start()
        return self

    def __exit__(self, exc_type: Any, exc: Any, tb: Any) -> None:
        self.stop()

    def start(self) -> EmbeddedFtpServer:
        self._accept_thread = threading.Thread(
            target=self._accept_loop, daemon=True, name="ftp-fixture-accept"
        )
        self._accept_thread.start()
        return self

    def _accept_loop(self) -> None:
        while not self.stopping:
            try:
                conn, peer = self._sock.accept()
            except TimeoutError:
                continue
            except OSError:
                break
            sess = _FtpSession(self, conn, peer)
            with self._sessions_lock:
                self._sessions.add(sess)
            sess.start()

    def unregister_session(self, sess: _FtpSession) -> None:
        with self._sessions_lock:
            self._sessions.discard(sess)

    def stop(self) -> None:
        self.stopping = True
        try:
            self._sock.close()
        except OSError:
            pass
        with self._sessions_lock:
            sessions = list(self._sessions)
        for s in sessions:
            s.close_session()
        if self._accept_thread is not None and self._accept_thread.is_alive():
            self._accept_thread.join(timeout=2.0)


def parse_external_server_env() -> tuple[str, int] | None:
    """Parse MINIFILE_TEST_SERVER=host:port when running against the Kotlin engine."""
    raw = (os.environ.get("MINIFILE_TEST_SERVER") or "").strip()
    if not raw:
        return None
    if raw.startswith("[") and "]:" in raw:
        h, p = raw[1:].split("]:", 1)
        return h, int(p)
    host, port_s = raw.rsplit(":", 1)
    return host, int(port_s)


def parse_pasv_ports_env() -> range | None:
    raw = (os.environ.get("MINIFILE_TEST_PASV_PORTS") or "").strip()
    if not raw or "-" not in raw:
        return None
    lo_s, hi_s = raw.split("-", 1)
    return range(int(lo_s), int(hi_s) + 1)


class FtpServerTestCase(unittest.TestCase):
    """Base TestCase running seamlessly against EmbeddedFtpServer OR external MINIFILE_TEST_SERVER."""

    is_external: bool = False
    host: str = "127.0.0.1"
    port: int = 0
    server_root: Path | None = None
    fixture_server: EmbeddedFtpServer | None = None
    _tmp_dir_obj: tempfile.TemporaryDirectory[str] | None = None
    _local_tmp_obj: tempfile.TemporaryDirectory[str] | None = None
    local_tmp: Path

    def setUp(self) -> None:
        super().setUp()
        self._local_tmp_obj = tempfile.TemporaryDirectory(prefix="mf-test-local-")
        self.local_tmp = Path(self._local_tmp_obj.name)

        # Isolate companion config & cache for every test
        self._orig_config_env = os.environ.get("MINIFILE_CONFIG")
        self._orig_cache_env = os.environ.get("MINIFILE_CACHE_DIR")
        os.environ["MINIFILE_CONFIG"] = str(self.local_tmp / "config" / "config.json")
        os.environ["MINIFILE_CACHE_DIR"] = str(self.local_tmp / "cache")

        ext = parse_external_server_env()
        if ext is not None:
            self.is_external = True
            self.host, self.port = ext
            self.fixture_server = None
            ext_root = (os.environ.get("MINIFILE_TEST_ROOT") or "").strip()
            self.server_root = Path(ext_root) if ext_root else None
            self._wipe_server_state()
        else:
            self.is_external = False
            self._tmp_dir_obj = tempfile.TemporaryDirectory(prefix="mf-test-ftp-")
            self.server_root = Path(self._tmp_dir_obj.name)
            self.fixture_server = EmbeddedFtpServer(
                root_dir=self.server_root,
                host="127.0.0.1",
                port=0,
                passive_ports=parse_pasv_ports_env(),
            ).start()
            self.host = self.fixture_server.host
            self.port = self.fixture_server.port

    def _wipe_server_state(self) -> None:
        if self.server_root is not None and self.server_root.is_dir():
            for child in self.server_root.iterdir():
                if child.is_dir() and not child.is_symlink():
                    shutil.rmtree(child)
                else:
                    child.unlink()
            return
        with MiniFileClient(self.host, self.port) as cli:
            for entry in cli.list("/", hidden=True):
                cli.remove(entry.path, recursive=True)

    def require_fixture(self, reason: str = "Requires embedded FTP fixture custom mode") -> None:
        if self.is_external:
            self.skipTest(reason)

    def make_client(self, **kwargs: Any) -> MiniFileClient:
        return MiniFileClient(host=self.host, port=self.port, **kwargs)

    def tearDown(self) -> None:
        if self.fixture_server is not None:
            self.fixture_server.stop()
            self.fixture_server = None
        if self._tmp_dir_obj is not None:
            self._tmp_dir_obj.cleanup()
            self._tmp_dir_obj = None
        if self._orig_config_env is None:
            os.environ.pop("MINIFILE_CONFIG", None)
        else:
            os.environ["MINIFILE_CONFIG"] = self._orig_config_env
        if self._orig_cache_env is None:
            os.environ.pop("MINIFILE_CACHE_DIR", None)
        else:
            os.environ["MINIFILE_CACHE_DIR"] = self._orig_cache_env
        if self._local_tmp_obj is not None:
            self._local_tmp_obj.cleanup()
            self._local_tmp_obj = None
        super().tearDown()
