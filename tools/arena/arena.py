#!/usr/bin/env python3
"""arena — MTGA UI automation."""

import sys
from pathlib import Path

_src = Path(__file__).resolve().parents[1] / "py" / "src"
if str(_src) not in sys.path:
    sys.path.insert(0, str(_src))

from leyline_tools.arena.cli import main


main()
