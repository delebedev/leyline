from __future__ import annotations

import json
import socket
import threading
import time
import urllib.request
from http.server import HTTPServer
from pathlib import Path

import pytest


FIXTURES = Path(__file__).parent / "fixtures"


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("", 0))
        return s.getsockname()[1]


@pytest.fixture()
def server_url():
    """Start the scry HTTP server on a free port against the game_start fixture."""
    from scry_lib.parser import parse_gre_blocks
    from scry_lib.server import _make_server
    from scry_lib.tail import scan_backward_for_full, tail_log
    from scry_lib.tracker import GameTracker

    log_path = FIXTURES / "game_start.jsonl"
    port = _free_port()

    tracker = GameTracker()
    lock = threading.Lock()

    offset = scan_backward_for_full(log_path)
    start = offset if offset is not None else 0
    for block in parse_gre_blocks(
        tail_log(log_path, follow=False, start_offset=start),
    ):
        tracker.feed(block)

    srv = _make_server(tracker, lock, port)
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    time.sleep(0.3)

    yield f"http://127.0.0.1:{port}"

    srv.shutdown()


def _get(url: str) -> tuple[int, dict, dict[str, str]]:
    """GET url, return (status, json_body, headers)."""
    req = urllib.request.Request(url)
    try:
        resp = urllib.request.urlopen(req, timeout=5)
        body = json.loads(resp.read())
        headers = {k.lower(): v for k, v in resp.getheaders()}
        return resp.status, body, headers
    except urllib.error.HTTPError as e:
        body = json.loads(e.read())
        headers = {k.lower(): v for k, v in e.headers.items()}
        return e.code, body, headers


class TestHealth:
    def test_health_returns_ok(self, server_url: str):
        status, body, _ = _get(f"{server_url}/health")
        assert status == 200
        assert body == {"status": "ok"}


class TestState:
    def test_state_returns_json(self, server_url: str):
        status, body, _ = _get(f"{server_url}/state")
        assert status == 200
        assert "match_id" in body

    def test_state_has_game_state_id(self, server_url: str):
        status, body, _ = _get(f"{server_url}/state")
        assert status == 200
        assert body.get("game_state_id", 0) > 0


class TestNotFound:
    def test_unknown_path_returns_404(self, server_url: str):
        status, body, _ = _get(f"{server_url}/nope")
        assert status == 404
        assert body == {"error": "not found"}


class TestCORS:
    def test_cors_header_present(self, server_url: str):
        _, _, headers = _get(f"{server_url}/health")
        assert headers.get("access-control-allow-origin") == "*"

    def test_cors_on_state(self, server_url: str):
        _, _, headers = _get(f"{server_url}/state")
        assert headers.get("access-control-allow-origin") == "*"
