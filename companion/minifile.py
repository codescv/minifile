#!/usr/bin/env python3
"""Standalone CLI & GUI shim for MiniFile PC Companion."""

from __future__ import annotations

import sys
from pathlib import Path

_COMPANION_DIR = str(Path(__file__).resolve().parent)
if _COMPANION_DIR not in sys.path:
    sys.path.insert(0, _COMPANION_DIR)

from minifile_companion.cli import main  # noqa: E402

if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
