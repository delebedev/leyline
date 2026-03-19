#!/usr/bin/env python3
"""scry — Game state from Player.log."""

import sys
from pathlib import Path

_src = Path(__file__).resolve().parents[1] / "py" / "src"
if str(_src) not in sys.path:
    sys.path.insert(0, str(_src))

from leyline_tools.scry.cli import main


main()
