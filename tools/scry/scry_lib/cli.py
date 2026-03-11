from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

DEFAULT_LOG = Path.home() / "Library/Logs/Wizards of the Coast/MTGA/Player.log"


def _cmd_state(args: argparse.Namespace) -> None:
    log_path = Path(args.log)

    if not log_path.exists():
        print(
            json.dumps({"error": f"log not found: {log_path}", "state": None}, indent=2)
        )
        return

    from scry_lib.errors import ClientError
    from scry_lib.models import SceneChange
    from scry_lib.parser import GREBlock, parse_log
    from scry_lib.tracker import GameTracker

    if args.no_catchup:
        # Parse entire file from beginning
        lines = log_path.read_text().splitlines()
    else:
        # Catchup: scan backward for last Full GSM, replay from there
        from scry_lib.tail import find_last_full_offset, tail_log

        offset = find_last_full_offset(log_path)
        start = offset if offset is not None else 0
        lines = list(tail_log(log_path, follow=False, start_offset=start))

    tracker = GameTracker()
    for event in parse_log(lines):
        if isinstance(event, GREBlock):
            tracker.feed(event)
        elif isinstance(event, ClientError):
            tracker.feed_error(event)
        elif isinstance(event, SceneChange):
            tracker.feed_scene(event)

    resolver = None
    if args.cards:
        from scry_lib.cards import CardResolver, find_arena_db

        db = find_arena_db()
        if db:
            resolver = CardResolver(db)

    print(json.dumps(tracker.to_dict(card_resolver=resolver), indent=2))


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


def _cmd_scene(args: argparse.Namespace) -> None:
    """One-shot: parse Player.log for the latest scene (lobby screen)."""
    log_path = Path(args.log)
    if not log_path.exists():
        print(json.dumps({"error": f"log not found: {log_path}", "current": None}))
        return

    from scry_lib.models import SceneChange
    from scry_lib.parser import _SCENE_CHANGE_RE

    # Scan from end — we only need the last SceneChange line
    current: str | None = None
    history: list[dict] = []
    for line in log_path.read_text().splitlines():
        m = _SCENE_CHANGE_RE.search(line)
        if m:
            try:
                raw = json.loads(m.group(1))
            except (json.JSONDecodeError, ValueError):
                continue
            sc = SceneChange.from_raw(raw)
            current = sc.to_scene
            history.append(sc.to_dict())

    # Keep last N
    history = history[-20:]
    print(json.dumps({"current": current, "history": history}, indent=2))


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
    p_state = subs.add_parser(
        "state", help="Parse log and print accumulated game state as JSON"
    )
    p_state.add_argument("--log", default=str(DEFAULT_LOG), help="Path to Player.log")
    p_state.add_argument(
        "--no-catchup",
        action="store_true",
        help="Parse entire file instead of scanning for last Full GSM",
    )
    p_state.add_argument(
        "--cards",
        action="store_true",
        default=True,
        help="Resolve card names from Arena DB (default: on)",
    )
    p_state.add_argument(
        "--no-cards",
        dest="cards",
        action="store_false",
        help="Disable card name resolution",
    )

    # stream
    p_stream = subs.add_parser("stream", help="Stream GRE blocks as JSONL")
    p_stream.add_argument("--log", default=str(DEFAULT_LOG), help="Path to Player.log")
    p_stream.add_argument(
        "-f", "--follow", action="store_true", help="Keep tailing (like tail -f)"
    )

    # scene
    p_scene = subs.add_parser(
        "scene", help="Show current lobby screen from Player.log scene changes"
    )
    p_scene.add_argument("--log", default=str(DEFAULT_LOG), help="Path to Player.log")

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
        "scene": _cmd_scene,
        "serve": _cmd_serve,
    }
    dispatch[args.command](args)
