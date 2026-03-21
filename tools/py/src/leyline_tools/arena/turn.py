"""arena turn -- structured turn state for agent decision-making."""

from __future__ import annotations

from .common import check_help, die
from .scry_bridge import _scry_cache_clear, _scry_state


def cmd_turn(args: list[str]) -> None:
    """Structured turn state for agent decision-making."""
    check_help(args, cmd_turn)
    _scry_cache_clear()
    state = _scry_state()
    if state is None:
        die("scry unavailable -- is a game in progress?")

    ti = state.get("turn_info", {})
    turn = ti.get("turn_number", 0)
    phase = ti.get("phase", "").replace("Phase_", "")
    step = ti.get("step", "")
    if step:
        phase += f"/{step.replace('Step_', '')}"
    active = ti.get("active_player")
    is_our_turn = active == 1

    # Life totals
    our_life = opp_life = "?"
    for p in state.get("players", []):
        if p.get("seat") == 1:
            our_life = p.get("life", "?")
        elif p.get("seat") == 2:
            opp_life = p.get("life", "?")

    # Hand
    hand = state.get("hand", [])
    hand_names = [c.get("name", "?") for c in hand if isinstance(c, dict)]

    # Actions (seat 1 only)
    actions = [a for a in state.get("actions", []) if a.get("seatId") == 1]

    # Classify actions
    lands: list[str] = []
    casts: list[str] = []
    other_actions: list[str] = []
    for a in actions:
        at = a.get("actionType", "")
        name = a.get("name", "?")
        if at == "ActionType_Play":
            lands.append(name)
        elif at == "ActionType_Cast":
            casts.append(name)
        elif at == "ActionType_Activate_Mana":
            pass  # don't show mana abilities — noise
        else:
            short = at.replace("ActionType_", "")
            other_actions.append(f"{short}: {name}")

    # Battlefield from zones
    bf_ours: list[str] = []
    bf_opp: list[str] = []
    for zone in state.get("zones", []):
        if zone.get("type") != "ZoneType_Battlefield":
            continue
        for obj in zone.get("objects", []):
            if not isinstance(obj, dict):
                continue
            name = obj.get("name", "?")
            pt = obj.get("p/t", "")
            label = f"{name} {pt}" if pt else name
            # Zone owner=None for battlefield; we can't split by controller here.
            # Just list all.
            bf_ours.append(label)

    # Output
    turn_label = "Your turn" if is_our_turn else "Opp turn"
    print(f"T{turn} {phase} | {turn_label} | Life {our_life}-{opp_life}")
    print(f"Hand ({len(hand)}): {', '.join(hand_names) if hand_names else '(empty)'}")

    if lands:
        print(f"Can play land: {', '.join(lands)}")
    if casts:
        print(f"Can cast: {', '.join(casts)}")
    if other_actions:
        print(f"Other actions: {', '.join(other_actions)}")
    if not lands and not casts and not other_actions:
        if is_our_turn:
            print("No actions available -- pass priority")
        else:
            print("Opponent's turn -- pass priority")

    if bf_ours:
        print(f"Battlefield: {', '.join(bf_ours)}")
