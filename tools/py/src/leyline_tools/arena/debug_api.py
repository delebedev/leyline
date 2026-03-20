from __future__ import annotations

from urllib.error import URLError
from urllib.request import Request, urlopen


def fetch_api(path: str) -> str | None:
    try:
        req = Request(f"http://localhost:8090{path}")
        with urlopen(req, timeout=2) as resp:
            if resp.status == 200:
                return resp.read().decode()
    except (URLError, OSError, TimeoutError):
        pass
    return None
