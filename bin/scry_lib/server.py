from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

from scry_lib.tracker import GameTracker


def _make_server(
    tracker: GameTracker,
    lock: threading.Lock,
    port: int,
) -> HTTPServer:
    """Build an HTTPServer wired to the given tracker (testable helper)."""

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:  # noqa: N802
            if self.path == "/health":
                self._json_response(200, {"status": "ok"})
            elif self.path == "/state":
                with lock:
                    data = tracker.to_dict()
                self._json_response(200, data)
            else:
                self._json_response(404, {"error": "not found"})

        def _json_response(self, code: int, body: dict) -> None:
            payload = json.dumps(body).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def log_message(self, format: str, *args: object) -> None:  # noqa: A002
            pass  # suppress request logging

    return HTTPServer(("0.0.0.0", port), Handler)


def run_server(log_path: Path, port: int = 8091) -> None:
    """Start an HTTP server exposing accumulated game state.

    1. Creates a GameTracker
    2. Catches up from the last Full GSM in the log
    3. Spawns a daemon thread that tails the log and feeds the tracker
    4. Starts the HTTP server on 0.0.0.0:port (blocks the calling thread)
    """
    from scry_lib.parser import parse_gre_blocks
    from scry_lib.tail import find_last_full_offset, tail_log

    tracker = GameTracker()
    lock = threading.Lock()

    # Mid-session catch-up: parse from last Full GSM
    offset = find_last_full_offset(log_path)
    start = offset if offset is not None else 0
    for block in parse_gre_blocks(
        tail_log(log_path, follow=False, start_offset=start),
    ):
        tracker.feed(block)

    # Background tailer thread
    def _tail_and_feed() -> None:
        current_size = log_path.stat().st_size
        for block in parse_gre_blocks(
            tail_log(log_path, follow=True, start_offset=current_size),
        ):
            with lock:
                tracker.feed(block)

    tailer = threading.Thread(target=_tail_and_feed, daemon=True)
    tailer.start()

    server = _make_server(tracker, lock, port)
    print(f"scry server listening on http://0.0.0.0:{port}")
    server.serve_forever()
