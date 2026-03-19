from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

DEFAULT_LOG = Path.home() / "Library/Logs/Wizards of the Coast/MTGA/Player.log"


def _format_mana_cost(mana_cost: list) -> str:
    """Format mana cost list to compact string like '1R', '2R', 'R'."""
    parts = []
    for c in mana_cost:
        colors = c.get("color", [])
        count = c.get("count", 0)
        if not colors:
            parts.append(str(count))
            continue
        color_char = colors[0].replace("ManaColor_", "")[0]  # Red→R, Generic→G, etc.
        if color_char == "G" and "Generic" in colors[0]:
            parts.append(str(count))
        else:
            parts.append(f"{count if count > 1 else ''}{color_char}")
    return "".join(parts) or "0"


def _print_brief(data: dict) -> None:
    """Print compact human-readable game state summary."""
    ti = data.get("turn_info")
    if ti is None:
        scene = data.get("scene", {}).get("current")
        if scene:
            print(f"Scene: {scene} | No active game")
        else:
            print("No game state")
        return

    # Turn header
    phase = (ti.get("phase") or "").replace("Phase_", "")
    step = ti.get("step")
    if step:
        step = step.replace("Step_", "")
        phase = f"{phase}/{step}"
    active = ti.get("active_player", "?")
    priority = ti.get("priority_player", "?")
    players = data.get("players", [])
    life_str = "-".join(str(p.get("life", "?")) for p in players)
    active_tag = "You" if active == 1 else "Opp"
    priority_tag = ""
    if priority != active:
        priority_tag = f" priority={'You' if priority == 1 else 'Opp'}"
    print(
        f"T{ti.get('turn_number', '?')} {phase} | {active_tag} active{priority_tag} | Life {life_str}"
    )

    # Hand
    hand = data.get("hand", [])
    hand_names = [c.get("name", "?") for c in hand]
    print(f"Hand ({len(hand)}): {', '.join(hand_names) if hand_names else '(empty)'}")

    # Actions — group by type, our seat only
    actions = data.get("actions", [])
    plays = []
    casts = []
    activates = []
    for a in actions:
        if a.get("seatId") != 1:
            continue
        at = a.get("actionType", "")
        name = a.get("name", "?")
        if "Activate_Mana" in at:
            continue  # skip mana activations
        mc = a.get("manaCost", [])
        cost = _format_mana_cost(mc) if mc else ""
        if "Play" in at:
            plays.append(name)
        elif "Cast" in at:
            casts.append(f"{name} ({cost})" if cost else name)
        elif "Activate" in at:
            activates.append(name)
    if plays:
        print(f"Play: {', '.join(plays)}")
    if casts:
        print(f"Cast: {', '.join(casts)}")
    if activates:
        print(f"Activate: {', '.join(activates)}")
    if not plays and not casts and not activates:
        print("No actions")

    # Battlefield summary
    zones = data.get("zones", [])
    our_bf = []
    opp_bf = []
    our_gy = []
    opp_gy = []
    stack = []
    for z in zones:
        zt = z.get("type", "")
        owner = z.get("owner")
        objs = z.get("objects", [])
        named = [o for o in objs if isinstance(o, dict) and o.get("name")]
        if "Battlefield" in zt:
            for o in named:
                name = o["name"]
                pt = o.get("p/t")
                tapped = " (T)" if o.get("tapped") else ""
                label = f"{name} {pt}{tapped}" if pt else f"{name}{tapped}"
                # Heuristic: our Mountains have seatId in mana actions
                # For now, can't distinguish sides without owner on BF zone
                # Just list all
                our_bf.append(label)
        elif "Stack" in zt:
            for o in named:
                stack.append(o["name"])
        elif "Graveyard" in zt:
            names_list = [o["name"] for o in named]
            if owner == 1:
                our_gy = names_list
            elif owner == 2:
                opp_gy = names_list

    if stack:
        print(f"Stack: {', '.join(stack)}")
    if our_bf:
        # Collapse duplicates
        from collections import Counter

        counts = Counter(our_bf)
        bf_strs = [f"{v}x {k}" if v > 1 else k for k, v in counts.items()]
        print(f"BF: {', '.join(bf_strs)}")
    if our_gy:
        print(f"GY: {', '.join(our_gy)}")
    if opp_gy:
        print(f"Opp GY: {', '.join(opp_gy)}")

    # Errors
    errs = data.get("error_count", 0)
    if errs:
        print(f"Errors: {errs}")


def _cmd_state(args: argparse.Namespace) -> None:
    log_path = Path(args.log)

    if not log_path.exists():
        if args.json:
            print(
                json.dumps(
                    {"error": f"log not found: {log_path}", "state": None}, indent=2
                )
            )
        else:
            print(f"error: log not found: {log_path}")
        return

    from .errors import ClientError
    from .models import SceneChange
    from .parser import GREBlock, parse_log
    from .tracker import GameTracker

    if args.no_catchup:
        # Parse entire file from beginning
        lines = log_path.read_text().splitlines()
    else:
        # Catchup: scan backward for last Full GSM, replay from there
        from .tail import find_last_full_offset, tail_log

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
        from .cards import CardResolver, find_arena_db

        db = find_arena_db()
        if db:
            resolver = CardResolver(db)

    data = tracker.to_dict(card_resolver=resolver)

    if args.json:
        print(json.dumps(data, indent=2))
    else:
        _print_brief(data)


def _cmd_stream(args: argparse.Namespace) -> None:
    log_path = Path(args.log)

    if not log_path.exists():
        print(f"error: log not found: {log_path}", file=sys.stderr)
        sys.exit(1)

    from .parser import parse_gre_blocks
    from .tail import tail_log

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

    from .models import SceneChange
    from .parser import _SCENE_CHANGE_RE

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

    from .server import run_server

    run_server(log_path, port=args.port)


def main() -> None:
    parser = argparse.ArgumentParser(
        prog="scry",
        description="Arena Player.log GRE parser and game state tracker.",
    )
    subs = parser.add_subparsers(dest="command")

    # state
    p_state = subs.add_parser(
        "state", help="Print game state (brief by default, --json for full)"
    )
    p_state.add_argument("--log", default=str(DEFAULT_LOG), help="Path to Player.log")
    p_state.add_argument(
        "--json",
        action="store_true",
        help="Output full JSON instead of brief summary",
    )
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


if __name__ == "__main__":
    main()
