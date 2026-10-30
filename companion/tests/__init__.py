"""Automated unit & integration test suite for MiniFile PC Companion."""

from __future__ import annotations

import sys
from pathlib import Path

_COMPANION_ROOT = str(Path(__file__).resolve().parent.parent)
if _COMPANION_ROOT not in sys.path:
    sys.path.insert(0, _COMPANION_ROOT)
