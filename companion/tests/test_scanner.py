"""Unit tests for LAN subnet scanner (ARCHITECTURE §6.3, §8.3)."""

from __future__ import annotations

import socket
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

_COMPANION_ROOT = str(Path(__file__).resolve().parent.parent)
if _COMPANION_ROOT not in sys.path:
    sys.path.insert(0, _COMPANION_ROOT)

from minifile_companion.scanner import (  # noqa: E402
    detect_local_subnet,
    discover,
    iter_subnet_hosts,
    probe_host,
)
from tests.ftp_fixture import EmbeddedFtpServer  # noqa: E402


class TestScanner(unittest.TestCase):
    """Tests for banner matching, non-MiniFile filtering, timeouts, and CIDR expansion."""

    def test_subnet_detection_and_host_expansion(self) -> None:
        self.assertEqual(detect_local_subnet("127.0.0.1"), "127.0.0.1/32")
        self.assertEqual(detect_local_subnet("192.168.50.42"), "192.168.50.0/24")
        self.assertEqual(iter_subnet_hosts("127.0.0.1/32"), ["127.0.0.1"])
        self.assertEqual(iter_subnet_hosts("127.0.0.1"), ["127.0.0.1"])
        hosts_30 = iter_subnet_hosts("192.168.1.0/30")
        self.assertEqual(hosts_30, ["192.168.1.1", "192.168.1.2"])
        with self.assertRaises(ValueError):
            iter_subnet_hosts("10.0.0.0/8")

    def test_discover_matches_minifile_banner_and_rejects_foreign_banner(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            with EmbeddedFtpServer(tmp, banner="MiniFile FTP Server ready") as mf_srv:
                found = discover(
                    subnet="127.0.0.1/32",
                    port=mf_srv.port,
                    timeout=0.5,
                    workers=8,
                )
                self.assertEqual(len(found), 1)
                self.assertEqual(found[0].host, "127.0.0.1")
                self.assertEqual(found[0].port, mf_srv.port)
                self.assertIn("MiniFile", found[0].banner)

            with EmbeddedFtpServer(tmp, banner="vsFTPd 3.0.5 generic server") as other_srv:
                found_other = discover(
                    subnet="127.0.0.1/32",
                    port=other_srv.port,
                    timeout=0.5,
                    workers=4,
                )
                self.assertEqual(found_other, [])

    def test_probe_handles_slow_tarpit_timeout(self) -> None:
        tarpit = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        tarpit.bind(("127.0.0.1", 0))
        tarpit.listen(2)
        port = tarpit.getsockname()[1]
        stop_evt = threading.Event()

        def _hold_open() -> None:
            try:
                conn, _ = tarpit.accept()
                stop_evt.wait(1.0)
                conn.close()
            except OSError:
                pass

        th = threading.Thread(target=_hold_open, daemon=True)
        th.start()
        try:
            t0 = time.monotonic()
            res = probe_host("127.0.0.1", port=port, timeout=0.12)
            elapsed = time.monotonic() - t0
            self.assertIsNone(res)
            self.assertLess(elapsed, 0.8)
        finally:
            stop_evt.set()
            tarpit.close()


if __name__ == "__main__":
    unittest.main()
