from __future__ import annotations

import json
import re
import time
from pathlib import Path

from .common import check_help, die, parse_coord, try_remove
from .hand import _find_hand_card
from .interaction import _click_960, _verified_drag
from .macos import capture_window, click_screen, ocr
from .scry_bridge import _scry_cache_clear, _scry_state


BATTLEFIELD_DROP = (480, 300)
ACTION_BUTTON = (888, 504)

_MODAL_DONE = (480, 491)
_MODAL_PATTERNS = re.compile(r"(?i)\b(Surveil|Scry|Explore|Discard)\b")


def _auto_dismiss_modal() -> None:
    time.sleep(1.5)
    img = "/tmp/arena/_modal_check.png"
    Path(img).parent.mkdir(parents=True, exist_ok=True)

    bounds = capture_window(img, hires=True)
    if bounds is None:
        return

    code, stdout, _ = ocr(img)
    try_remove(img)
    if code != 0 or not stdout.strip():
        return

    try:
        items = json.loads(stdout)
    except json.JSONDecodeError:
        return

    modal_type = None
    saw_done = False
    saw_submit = False
    for item in items:
        text = item.get("text", "")
        m = _MODAL_PATTERNS.search(text)
        if m:
            modal_type = m.group(1).title()
        if text.strip().lower() == "done":
            saw_done = True
        if text.strip().lower().startswith("submit"):
            saw_submit = True

    if modal_type:
        if saw_submit:
            # Discard/selection prompts use Submit button (top-right action area)
            _click_960(*ACTION_BUTTON)
        else:
            _click_960(*_MODAL_DONE)
        time.sleep(0.5)
        btn = "Submit" if saw_submit else ("Done" if saw_done else "inferred")
        print(f"  (auto-dismissed {modal_type} via {btn})")


def cmd_play(args: list[str]) -> None:
    check_help(args, cmd_play)
    if not args:
        die("Usage: arena play <card-name> [--to x,y]")

    name_parts = []
    drop_to = BATTLEFIELD_DROP
    i = 0
    while i < len(args):
        if args[i] == "--to" and i + 1 < len(args):
            coord = parse_coord(args[i + 1])
            if coord:
                drop_to = coord
            i += 2
            continue
        if not args[i].startswith("--"):
            name_parts.append(args[i])
        i += 1

    card_name = " ".join(name_parts)
    if not card_name:
        die("No card name provided")

    result = _find_hand_card(card_name)
    if result is None:
        die(f"Card '{card_name}' not found in hand")

    (cx, cy), instance_id = result
    print(f"Playing {card_name} (id={instance_id}) from ({cx},{cy})")

    ok = _verified_drag((cx, cy), drop_to, instance_id)
    if not ok:
        die(f"✗ Failed to play {card_name} after 3 attempts")

    print(f"✓ {card_name} played successfully")
    _auto_dismiss_modal()


def cmd_land(args: list[str]) -> None:
    check_help(args, cmd_land)

    _scry_cache_clear()
    state = _scry_state()
    if state is None:
        die("✗ scry unavailable")

    hand = state.get("hand", [])
    actions = state.get("actions", [])
    if not hand:
        die("✗ hand is empty")

    hand_ids = {c.get("id") for c in hand}
    playable_land = None
    for a in actions:
        if a.get("seatId") != 1:
            continue
        if a.get("actionType") != "ActionType_Play":
            continue
        if a.get("manaCost"):
            continue
        iid = a.get("instanceId")
        if iid in hand_ids:
            playable_land = a
            break

    if playable_land is None:
        print("✗ no playable land in hand")
        raise SystemExit(1)

    land_name = playable_land.get("name", "Land")
    instance_id = playable_land["instanceId"]
    result = _find_hand_card(land_name)
    if result is None:
        die(f"✗ {land_name} in hand but OCR can't locate it")

    (cx, cy), _ = result
    print(f"Playing {land_name} (id={instance_id}) from ({cx},{cy})")

    ok = _verified_drag((cx, cy), BATTLEFIELD_DROP, instance_id)
    if not ok:
        die(f"✗ failed to play {land_name} after 3 attempts")

    _scry_cache_clear()
    post = _scry_state()
    if post:
        post_hand = post.get("hand", [])
        print(f"✓ {land_name} played (hand: {len(post_hand)} cards)")
    else:
        print(f"✓ {land_name} played")


def cmd_attack_all(args: list[str]) -> None:
    check_help(args, cmd_attack_all)
    _scry_cache_clear()
    pre = _scry_state()
    if pre is None:
        die("✗ scry unavailable")

    ti = pre.get("turn_info", {})
    phase = ti.get("phase", "")
    step = ti.get("step", "")
    active = ti.get("active_player")
    opp_life_pre = None
    for p in pre.get("players", []):
        if p.get("seat") == 2:
            opp_life_pre = p.get("life")

    in_attack = "Combat" in phase and "DeclareAttack" in (step or "")
    if active != 1:
        print("✗ not our turn")
        raise SystemExit(1)
    if not in_attack:
        phase_label = phase.replace("Phase_", "")
        if step:
            phase_label += f"/{step.replace('Step_', '')}"
        print(f"✗ not in DeclareAttack (current: {phase_label}), pass first")
        raise SystemExit(1)

    pre_gsid = pre.get("game_state_id", 0)
    click_screen(*ACTION_BUTTON)
    time.sleep(1.5)
    click_screen(*ACTION_BUTTON)

    for _ in range(8):
        time.sleep(1.0)
        _scry_cache_clear()
        post = _scry_state()
        if post and post.get("game_state_id", 0) > pre_gsid + 2:
            break
    else:
        post = _scry_state()

    if post:
        opp_life_post = None
        for p in post.get("players", []):
            if p.get("seat") == 2:
                opp_life_post = p.get("life")
        post_phase = post.get("turn_info", {}).get("phase", "")
        post_step = post.get("turn_info", {}).get("step", "")

        damage = ""
        if opp_life_pre is not None and opp_life_post is not None:
            dmg = opp_life_pre - opp_life_post
            if dmg > 0:
                damage = f", {dmg} damage"

        phase_label = post_phase.replace("Phase_", "")
        if post_step:
            phase_label += f"/{post_step.replace('Step_', '')}"

        print(f"✓ attacked{damage} (opp life: {opp_life_post}, now: {phase_label})")
    else:
        print("✓ attacked (scry unavailable for result)")
