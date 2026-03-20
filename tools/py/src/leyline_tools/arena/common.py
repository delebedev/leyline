from __future__ import annotations

import re
import sys
import time
from typing import NoReturn

from .debug_api import fetch_api


def die(msg: str) -> NoReturn:
    print(msg, file=sys.stderr)
    raise SystemExit(1)


def try_remove(path: str) -> None:
    try:
        import os

        os.remove(path)
    except OSError:
        pass


def parse_coord(s: str) -> tuple[int, int] | None:
    m = re.fullmatch(r"(\d+),(\d+)", s)
    if not m:
        return None
    return int(m.group(1)), int(m.group(2))


def check_help(args: list[str], func) -> None:  # type: ignore[type-arg]
    if "--help" in args or "-h" in args:
        doc = (func.__doc__ or "").strip()
        if doc:
            print(doc)
        raise SystemExit(0)


def poll_state(condition: str, timeout_ms: int) -> bool:
    field, value = condition.split("=", 1)
    deadline = time.time() + timeout_ms / 1000
    while time.time() < deadline:
        body = fetch_api("/api/state")
        if body and f'"{field}"' in body and value.lower() in body.lower():
            return True
        time.sleep(0.2)
    return False
