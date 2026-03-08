from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

DEFAULT_LOG = Path.home() / "Library/Logs/Wizards of the Coast/MTGA/Player.log"


def _cmd_state(args: argparse.Namespace) -> None:
    log_path = Path(args.log)

    if not log_path.exists():
        print(json.dumps({"error": f"log not found: {log_path}", "state": None}, indent=2))
        return

    from scry_lib.parser import parse_gre_blocks
    from scry_lib.tracker import GameTracker

    if args.no_catchup:
        # Parse entire file from beginning
        lines = log_path.read_text().splitlines()
    else:
        # Catchup: scan backward for last Full GSM, replay from there
        from scry_lib.tail import scan_backward_for_full, tail_log

        offset = scan_backward_for_full(log_path)
        start = offset if offset is not None else 0
        lines = list(tail_log(log_path, follow=False, start_offset=start))

    tracker = GameTracker()
    for block in parse_gre_blocks(lines):
        tracker.feed(block)

    print(json.dumps(tracker.to_dict(), indent=2))


def _cmd_stream(args: argparse.Namespace) -> None:
    log_path = Path(args.log)

    if not log_path.exists():
        print(f"error: log not found: {log_path}", file=sys.stderr)
        sys.exit(1)

    from scry_lib.parser import parse_gre_blocks
    from scry_lib.tail import tail_log

    lines = tail_log(log_path, follow=args.follow, start_offset=0)

    for block in parse_gre_blocks(lines):
        msg_types = [m.get("type", "") for m in block.messages]
        gsids = [
            m.get("gameStateId")
            for m in block.messages
            if m.get("gameStateId") is not None
        ]
        record = {
            "match_id": block.match_id,
            "timestamp": block.timestamp,
            "message_types": msg_types,
            "gsids": gsids,
        }
        print(json.dumps(record))


def _cmd_serve(args: argparse.Namespace) -> None:
    log_path = Path(args.log)

    if not log_path.exists():
        print(f"error: log not found: {log_path}", file=sys.stderr)
        sys.exit(1)

    from scry_lib.server import run_server

    run_server(log_path, port=args.port)


def main() -> None:
    parser = argparse.ArgumentParser(
        prog="scry",
        description="Arena Player.log GRE parser and game state tracker.",
    )
    subs = parser.add_subparsers(dest="command")

    # state
    p_state = subs.add_parser("state", help="Parse log and print accumulated game state as JSON")
    p_state.add_argument("--log", default=str(DEFAULT_LOG), help="Path to Player.log")
    p_state.add_argument("--no-catchup", action="store_true", help="Parse entire file instead of scanning for last Full GSM")

    # stream
    p_stream = subs.add_parser("stream", help="Stream GRE blocks as JSONL")
    p_stream.add_argument("--log", default=str(DEFAULT_LOG), help="Path to Player.log")
    p_stream.add_argument("-f", "--follow", action="store_true", help="Keep tailing (like tail -f)")

    # serve
    p_serve = subs.add_parser("serve", help="HTTP server for live game state")
    p_serve.add_argument("--log", default=str(DEFAULT_LOG), help="Path to Player.log")
    p_serve.add_argument("--port", type=int, default=8091, help="HTTP port")

    args = parser.parse_args()

    if args.command is None:
        parser.print_help()
        return

    dispatch = {
        "state": _cmd_state,
        "stream": _cmd_stream,
        "serve": _cmd_serve,
    }
    dispatch[args.command](args)
