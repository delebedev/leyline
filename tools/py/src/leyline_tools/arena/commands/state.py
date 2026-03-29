from __future__ import annotations

from ..debug_api import fetch_api


def cmd_state(args: list[str]) -> None:
    state = fetch_api("/api/state")
    if state is None:
        if not args or "--json" in args:
            print('{"source":"unavailable"}')
        else:
            print("Debug API unavailable (is the server running?)")
        return
    print(state)
