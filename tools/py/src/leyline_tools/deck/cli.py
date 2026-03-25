"""CLI for deck coverage tooling."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from leyline_tools.deck.collection import load_collection
from leyline_tools.deck.coverage import pick_best_colors, run


def main() -> None:
    parser = argparse.ArgumentParser(
        prog="deck",
        description="Build mechanic-dense decks for recording sessions",
    )
    sub = parser.add_subparsers(dest="command")

    # deck coverage FDN FIN
    cov = sub.add_parser("coverage", help="Score sets and build a coverage deck")
    cov.add_argument("sets", nargs="+", help="Set codes (e.g. FDN FIN)")
    cov.add_argument("--size", type=int, default=60, help="Deck size (default: 60)")
    cov.add_argument("--lands", type=int, default=25, help="Land count (default: 25)")
    cov.add_argument("--colors", type=str, default=None,
                     help="Color identity filter, e.g. WB, RG, WUB (default: any 2)")
    cov.add_argument("--no-collection", action="store_true",
                     help="Skip collection filtering (use all cards)")
    cov.add_argument("--log", type=Path, help="Path to Player.log (auto-detected if omitted)")
    cov.add_argument("--fd-frames", type=Path,
                     help="Path to fd-frames.jsonl from proxy recording")

    # deck collection — show collection stats
    col = sub.add_parser("collection", help="Show collection stats from Player.log")
    col.add_argument("--log", type=Path, help="Path to Player.log")
    col.add_argument("--fd-frames", type=Path, help="Path to fd-frames.jsonl")

    args = parser.parse_args()
    if not args.command:
        parser.print_help()
        sys.exit(1)

    if args.command == "collection":
        coll = load_collection(
            fd_frames=args.fd_frames,
            player_log=args.log,
        )
        print(f"Collection: {len(coll)} unique cards, {sum(coll.values())} total copies")
        return

    if args.command == "coverage":
        collection = None
        if not args.no_collection:
            collection = load_collection(
                fd_frames=args.fd_frames,
                player_log=args.log,
            )
            print(f"Collection loaded: {len(collection)} unique cards")
        # Parse colors: explicit string like "WB" or default best-2
        valid_colors = set("WUBRG")
        if args.colors:
            colors = {c.upper() for c in args.colors}
            bad = colors - valid_colors
            if bad:
                print(f"Unknown colors: {bad}. Use W/U/B/R/G.", file=sys.stderr)
                sys.exit(1)
        else:
            colors = pick_best_colors(args.sets, collection, n=2)
            print(f"Auto-picked colors: {''.join(sorted(colors))}")

        run(args.sets, deck_size=args.size, land_count=args.lands,
            collection=collection, colors=colors)


if __name__ == "__main__":
    main()
