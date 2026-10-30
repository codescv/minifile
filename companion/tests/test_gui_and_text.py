"""Tests for GUI fallback behavior, text encoding detection, and ConfigStore."""

from __future__ import annotations

import contextlib
import io
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

_COMPANION_ROOT = str(Path(__file__).resolve().parent.parent)
if _COMPANION_ROOT not in sys.path:
    sys.path.insert(0, _COMPANION_ROOT)

from minifile_companion import gui  # noqa: E402
from minifile_companion.config import CompanionConfig, ConfigStore  # noqa: E402
from minifile_companion.textutil import (  # noqa: E402
    UTF8_BOM,
    UTF16_BE_BOM,
    UTF16_LE_BOM,
    decode_text,
    detect_eol,
    encode_text,
    is_probably_text,
)


class TestTextUtilAndConfig(unittest.TestCase):
    """Tests BOM/encoding detection, EOL sniffing, binary detection, and config persistence."""

    def test_bom_and_encoding_roundtrips(self) -> None:
        # UTF-8 with BOM
        raw_utf8_bom = UTF8_BOM + "Hello 世界\r\nLine 2\r\n".encode("utf-8")
        txt1, enc1 = decode_text(raw_utf8_bom)
        self.assertEqual(enc1, "utf-8")
        self.assertEqual(txt1, "Hello 世界\r\nLine 2\r\n")
        self.assertEqual(detect_eol(txt1), "\r\n")

        # UTF-16LE with BOM
        raw_u16le = encode_text("Привет ✓\n", encoding="utf-16-le", include_bom=True)
        self.assertTrue(raw_u16le.startswith(UTF16_LE_BOM))
        self.assertTrue(is_probably_text(raw_u16le))
        txt2, enc2 = decode_text(raw_u16le)
        self.assertEqual(enc2, "utf-16-le")
        self.assertEqual(txt2, "Привет ✓\n")

        # UTF-16BE with BOM
        raw_u16be = encode_text("MiniFile\n", encoding="utf-16-be", include_bom=True)
        self.assertTrue(raw_u16be.startswith(UTF16_BE_BOM))
        txt3, enc3 = decode_text(raw_u16be)
        self.assertEqual(enc3, "utf-16-be")
        self.assertEqual(txt3, "MiniFile\n")

        # Non-UTF-8 Latin-1 fallback
        raw_latin = "caf\xe9".encode("iso-8859-1")
        txt4, enc4 = decode_text(raw_latin)
        self.assertEqual(enc4, "iso-8859-1")
        self.assertEqual(txt4, "café")

        # Binary NUL sniff
        self.assertFalse(is_probably_text(b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR"))
        self.assertTrue(is_probably_text(b""))

    def test_config_store_atomic_persistence_and_permissions(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            cfg_path = Path(tmp) / "sub" / "config.json"
            store = ConfigStore(cfg_path)
            default_cfg = store.load()
            self.assertEqual(default_cfg.port, 2121)

            store.save(
                CompanionConfig(
                    host="192.168.1.99",
                    port=2221,
                    user="chii",
                    password="secret",
                )
            )
            loaded = store.load()
            self.assertEqual(loaded.host, "192.168.1.99")
            self.assertEqual(loaded.port, 2221)
            self.assertEqual(loaded.user, "chii")
            if os.name == "posix":
                mode = cfg_path.stat().st_mode & 0o777
                self.assertEqual(mode, 0o600)


class TestGuiFallback(unittest.TestCase):
    """Tests that `gui` gracefully delegates to Web UI when headless or lacking tkinter."""

    def test_run_gui_delegates_to_webui_when_headless_or_no_tkinter(self) -> None:
        stderr_buf = io.StringIO()
        with mock.patch.object(gui, "run_webui", return_value=0) as mocked_web:
            with mock.patch.dict(os.environ, {"DISPLAY": "", "WAYLAND_DISPLAY": ""}, clear=False):
                with contextlib.redirect_stderr(stderr_buf):
                    rc = gui.run_gui(port=8912, open_browser=False, prefer_tk=True)
        self.assertEqual(rc, 0)
        mocked_web.assert_called_once()
        self.assertEqual(mocked_web.call_args.kwargs["port"], 8912)
        self.assertFalse(mocked_web.call_args.kwargs["open_browser"])


if __name__ == "__main__":
    unittest.main()
