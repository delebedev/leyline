from __future__ import annotations

import json

from ..debug_api import fetch_api
from ..scry_bridge import _scry_cache_clear, _scry_state


def cmd_state(args: list[str]) -> None:
    state = fetch_api("/api/state")
    if state is None:
        if not args or "--json" in args:
            print('{"source":"unavailable"}')
        else:
            print("Debug API unavailable (is the server running?)")
        return
    print(state)


def cmd_errors(args: list[str]) -> None:
    _scry_cache_clear()
    state = _scry_state()
    if state is None:
        print("{}")
        return
    errors = state.get("errors", [])
    error_count = state.get("error_count", 0)
    print(json.dumps({"errors": errors, "error_count": error_count}, indent=2))
