"""Compound arena commands — high-level flows that block and return JSON."""

from __future__ import annotations

import json
import random
import time
from pathlib import Path

from .common import die, try_remove
from .macos import capture_window, ocr
from .nav import (
    detect_screen,
    dismiss_all_popups,
    exec_step,
    poll_ocr,
    wait_condition,
)


# ---------------------------------------------------------------------------
# concede
# ---------------------------------------------------------------------------

_CONCEDE_SCREENS = {"InGame", "Mulligan", "ConcedMenu", "Result", "EventResult"}


def cmd_concede(_args: list[str], commands: dict[str, object]) -> None:
    """Idempotent: 'make sure we are not in a match anymore.'

    From InGame:      cog -> Concede -> dismiss result -> natural resting place
    From ConcedMenu:  Concede -> dismiss result -> natural resting place
    From Result:      dismiss -> Home
    From EventResult: dismiss -> EventLobby
    From anywhere else: no-op, return current screen
    """
    screen = detect_screen()
    if screen is None:
        die("Cannot detect current screen")

    if screen not in _CONCEDE_SCREENS:
        print(json.dumps({"ok": True, "screen": screen, "noop": True}))
        return

    # InGame/Mulligan -> ConcedMenu
    if screen in ("InGame", "Mulligan"):
        exec_step("click 940,42", commands)  # cog
        exec_step("sleep 1", commands)
        screen = "ConcedMenu"

    # ConcedMenu -> Result/EventResult
    if screen == "ConcedMenu":
        exec_step('click "Concede" --retry 3', commands)
        # Wait for either Result or EventResult
        deadline = time.time() + 15
        while time.time() < deadline:
            screen = detect_screen()
            if screen in ("Result", "EventResult"):
                break
            time.sleep(1)
        else:
            die("Result screen did not appear after conceding")

    # Result -> Home (center click dismisses result overlay)
    # Wait for the result animation to finish before clicking
    if screen == "Result":
        exec_step("sleep 3", commands)
        for _ in range(5):
            exec_step("click 480,300", commands)
            exec_step("sleep 3", commands)
            if wait_condition("scene=Home", 5):
                break
        else:
            die("Did not return to Home after dismissing result")
        dismiss_all_popups(commands)
        print(json.dumps({"ok": True, "screen": "Home"}))
        return

    # EventResult -> EventLobby
    if screen == "EventResult":
        exec_step("click 478,551", commands)  # "Click to Continue"
        if not wait_condition("scene=EventLanding", 10):
            die("Did not return to EventLobby after dismissing event result")
        dismiss_all_popups(commands)
        print(json.dumps({"ok": True, "screen": "EventLobby"}))
        return


# ---------------------------------------------------------------------------
# start-bot-match
# ---------------------------------------------------------------------------

# Deck grid coords (960-wide logical)
_DECK_GRID_X = [82, 270, 458, 646]
_DECK_GRID_Y = [430, 500]


def _ocr_deck_names() -> list[dict]:
    """OCR the deck grid and return items in the deck name region."""
    img = "/tmp/arena/_deck_grid.png"
    Path(img).parent.mkdir(parents=True, exist_ok=True)
    bounds = capture_window(img)
    if bounds is None:
        die("MTGA window not found for deck OCR")
    code, stdout, _ = ocr(img)
    try_remove(img)
    if code != 0 or not stdout.strip():
        return []
    try:
        items = json.loads(stdout)
    except (json.JSONDecodeError, ValueError):
        return []
    # Filter to deck name region: y 350-550, x < 800
    # Exclude UI elements like "Edit Deck", "My Decks", "Double-click to Edit"
    ui_labels = {"edit deck", "my decks", "double-click to edit", "double-click"}
    return [
        item
        for item in items
        if 350 < item.get("cy", 0) < 550
        and item.get("cx", 0) < 800
        and item.get("text", "").lower() not in ui_labels
    ]


def _click_deck_art(item: dict, commands: dict[str, object]) -> None:
    """Click deck card art — ~80px above the OCR label center."""
    cx = item["cx"]
    cy = item["cy"] - 80
    exec_step(f"click {cx},{cy}", commands)


def _pick_deck_random(commands: dict[str, object]) -> str:
    """Click a random available deck. Returns the deck name."""
    items = _ocr_deck_names()
    if not items:
        # Fallback: click first grid position
        cx, cy = _DECK_GRID_X[0], _DECK_GRID_Y[0]
        exec_step(f"click {cx},{cy}", commands)
        return "deck-0"
    chosen = random.choice(items)
    name = chosen.get("text", "?")
    _click_deck_art(chosen, commands)
    return name


def _pick_deck_by_name(name: str, commands: dict[str, object]) -> str:
    """Click deck matching name. Returns matched name."""
    from .hand import _fuzzy_card_match

    items = _ocr_deck_names()
    target = name.lower()
    best_score = 0.0
    best_item = None
    for item in items:
        text = item.get("text", "").lower()
        if target in text or text in target:
            score = 1.0
        else:
            score = _fuzzy_card_match(text, name)
        if score > best_score:
            best_score = score
            best_item = item

    if best_item is None or best_score < 0.4:
        deck_texts = [item.get("text", "") for item in items]
        die(f"Deck '{name}' not found (available: {deck_texts})")

    matched = best_item.get("text", "?")
    _click_deck_art(best_item, commands)
    return matched


def _pick_deck_by_index(index: int, commands: dict[str, object]) -> str:
    """Click deck at grid index. Returns label."""
    col = index % 4
    row = index // 4
    if row >= len(_DECK_GRID_Y):
        die(f"Deck index {index} out of range (max {len(_DECK_GRID_Y) * 4 - 1})")
    cx, cy = _DECK_GRID_X[col], _DECK_GRID_Y[row]
    exec_step(f"click {cx},{cy}", commands)
    return f"deck-{index}"


def _ensure_home(commands: dict[str, object]) -> None:
    """Get to Home from anywhere. Concedes if in-game."""
    screen = detect_screen()
    if screen == "Home":
        return
    if screen in _CONCEDE_SCREENS:
        # Reuse concede logic but capture its output
        cmd_concede([], commands)
        screen = detect_screen()
        if screen == "Home":
            return
    # Navigate to Home
    from .nav import cmd_navigate

    cmd_navigate(["Home"], commands)


def cmd_start_bot_match(args: list[str], commands: dict[str, object]) -> None:
    """From anywhere -> in-game with a bot match. Blocks until game is ready.

    Usage: arena start-bot-match [--deck NAME|N]
    Default: picks a random available deck.
    """
    deck_spec: str | int | None = None
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
        elif a in ("--help", "-h"):
            print("Usage: arena start-bot-match [--deck NAME|N]")
            return
        else:
            die(f"Unknown flag: {a}")

    # Step 1: Get to the right starting point
    screen = detect_screen()
    if screen is None:
        die("Cannot detect current screen")

    if screen in _CONCEDE_SCREENS:
        cmd_concede([], commands)
        screen = detect_screen()

    # Step 2: Get to FindMatch tab with Bot Match queue
    if screen not in ("FindMatch", "DeckSelected"):
        if screen != "Home":
            _ensure_home(commands)
        from .nav import cmd_navigate

        cmd_navigate(["FindMatch"], commands)

    # Step 3: Click Bot Match in sidebar (it may already be selected, but
    # clicking it again is harmless and ensures the deck grid shows)
    if screen != "DeckSelected":
        exec_step('click "Bot Match" --retry 3 --bottom', commands)
        exec_step("sleep 1", commands)
        # Verify we see the deck grid (My Decks section)
        if not poll_ocr("My Decks", present=True, timeout_ms=5000):
            die("Deck grid did not appear after selecting Bot Match")

    # Step 4: Pick deck (clicking art selects it)
    if deck_spec is None:
        deck_name = _pick_deck_random(commands)
    elif isinstance(deck_spec, str):
        deck_name = _pick_deck_by_name(deck_spec, commands)
    else:
        deck_name = _pick_deck_by_index(deck_spec, commands)
    exec_step("sleep 1", commands)

    # Verify deck selected
    if not poll_ocr("Edit Deck", present=True, timeout_ms=3000):
        # Retry with first grid slot
        cx, cy = _DECK_GRID_X[0], _DECK_GRID_Y[0]
        exec_step(f"click {cx},{cy}", commands)
        exec_step("sleep 1", commands)
        if not poll_ocr("Edit Deck", present=True, timeout_ms=3000):
            die("No deck selected — 'Edit Deck' not visible")

    # Step 5: Click Play button (bottom-right, use --bottom to avoid
    # hitting "Play" text in event descriptions)
    exec_step('click "Play" --retry 3 --bottom', commands)

    # Step 6: Wait for game to load, handle mulligan
    if poll_ocr("Keep", present=True, timeout_ms=30000):
        exec_step("sleep 1", commands)
        exec_step('click "Keep" --retry 3', commands)

    # Step 7: Wait for InGame scene
    if not wait_condition("scene=InGame", 15):
        scene_check = detect_screen()
        if scene_check != "InGame":
            die(f"Game did not start (screen: {scene_check})")

    print(json.dumps({"ok": True, "screen": "InGame", "deck": deck_name}))
