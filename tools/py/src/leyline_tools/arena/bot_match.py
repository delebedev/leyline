from __future__ import annotations

import json
import time
from pathlib import Path

from .common import check_help, die
from .hand import _fuzzy_card_match
from .interaction import cmd_click
from .macos import capture_window, ocr
from .nav import detect_screen, exec_step, poll_ocr, try_dismiss_popups, wait_condition
from .scry_bridge import _scry_cache_clear, _scry_state, get_current_scene


_DECK_GRID_X = [82, 270, 458, 646]
_DECK_GRID_Y = [430, 500]
_PLAY_BUTTON = (867, 519)


def _pick_deck_by_name(name: str) -> None:
    img = "/tmp/arena/_deck_grid.png"
    Path(img).parent.mkdir(parents=True, exist_ok=True)
    bounds = capture_window(img)
    if bounds is None:
        die("MTGA window not found for deck OCR")

    code, stdout, _ = ocr(img)
    from .common import try_remove

    try_remove(img)
    if code != 0 or not stdout.strip():
        die("OCR failed on deck grid")

    items = json.loads(stdout)
    target = name.lower()
    best_score = 0.0
    best_item = None
    for item in items:
        cy = item.get("cy", 0)
        cx = item.get("cx", 0)
        if cy < 350 or cy > 550 or cx > 800:
            continue
        text = item.get("text", "").lower()
        if target in text or text in target:
            score = 1.0
        else:
            score = _fuzzy_card_match(text, name)
        if score > best_score:
            best_score = score
            best_item = item

    if best_item is None or best_score < 0.4:
        deck_texts = [
            item.get("text", "")
            for item in items
            if 350 < item.get("cy", 0) < 550 and item.get("cx", 0) < 800
        ]
        die(f"Deck '{name}' not found (available: {deck_texts})")

    cx, cy = best_item["cx"], best_item["cy"]
    matched = best_item.get("text", "?")
    print(f"Picking deck '{matched}' at ({cx},{cy}) (score={best_score:.2f})")
    cmd_click([f"{cx},{cy}"])


def cmd_bot_match(args: list[str], commands: dict[str, object]) -> None:
    check_help(args, cmd_bot_match)
    from .screens import find_path

    deck_spec: str | int = 0
    it = iter(args)
    for a in it:
        if a == "--deck":
            try:
                val = next(it)
            except StopIteration:
                die("--deck requires a value")
            try:
                deck_spec = int(val)
            except ValueError:
                deck_spec = val
        else:
            die(f"Unknown flag: {a}")

    try_dismiss_popups(commands)
    current = detect_screen()
    if current is None:
        die("Cannot detect current screen")

    print(f"Starting from: {current}")
    if current in ("InGame", "Mulligan"):
        if current == "Mulligan":
            print("At mulligan — keeping hand")
            cmd_click(["Keep", "--retry", "3"])
            time.sleep(3)
        _scry_cache_clear()
        state = _scry_state()
        if state:
            ti = state.get("turn_info", {})
            print(
                f"✓ in game — T{ti.get('turn_number', '?')} "
                f"{ti.get('phase', '').replace('Phase_', '')}"
            )
        else:
            print("✓ in game")
        return

    if current in ("Result", "EventResult"):
        path = find_path(current, "Home")
        if path:
            for t in path:
                for step in t.get("steps", []):
                    exec_step(step, commands)
                wait = t.get("wait")
                if wait:
                    wait_condition(wait, t.get("wait_timeout", 10))
            current = "Home"

    if current == "DeckSelected":
        pass
    elif current in ("Home", "RecentlyPlayed", "FindMatch"):
        if current == "Home":
            print("Opening play blade...")
            cmd_click(["867,519"])
            time.sleep(2)

        print("Selecting Bot Match...")
        cmd_click(["867,99"])
        time.sleep(1)

        if not poll_ocr("Bot Match", present=True, timeout_ms=3000):
            die("Bot Match not visible after opening Find Match tab")
        cmd_click(["842,396"])
        time.sleep(1)

        ok = poll_ocr("Edit Deck", present=True, timeout_ms=5000)
        if not ok:
            pass
    else:
        die(f"Don't know how to start bot match from {current}")

    if isinstance(deck_spec, str):
        _pick_deck_by_name(deck_spec)
    else:
        col = deck_spec % 4
        row = deck_spec // 4
        if row >= len(_DECK_GRID_Y):
            die(
                f"Deck index {deck_spec} out of range (max {len(_DECK_GRID_Y) * 4 - 1})"
            )
        dx, dy = _DECK_GRID_X[col], _DECK_GRID_Y[row]
        print(f"Picking deck #{deck_spec} at ({dx},{dy})")
        cmd_click([f"{dx},{dy}"])
    time.sleep(1)

    cmd_click([f"{_PLAY_BUTTON[0]},{_PLAY_BUTTON[1]}"])
    print("Waiting for match...")

    print("Waiting for mulligan...")
    if poll_ocr("Keep", present=True, timeout_ms=30000):
        print("Keeping hand...")
        cmd_click(["Keep", "--retry", "3"])

    print("Waiting for game start...")
    for _ in range(15):
        time.sleep(1)
        _scry_cache_clear()
        state = _scry_state()
        if state:
            ti = state.get("turn_info", {})
            turn = ti.get("turn_number")
            if turn is not None and turn > 0:
                hand = state.get("hand", [])
                hand_names = [c.get("name", "?") for c in hand]
                print(
                    f"✓ game ready — T{turn} "
                    f"hand ({len(hand)}): {', '.join(hand_names)}"
                )
                return

    scene = get_current_scene()
    if scene == "InGame":
        print("✓ game ready (turn not yet reported by scry)")
    else:
        die(f"Game did not start (current scene: {scene})")
