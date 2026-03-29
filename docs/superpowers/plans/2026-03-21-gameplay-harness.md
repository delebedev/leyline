# Gameplay Harness Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give agents a structured way to play MTGA bot matches — MTG rules in context, structured turn state, post-session analysis.

**Architecture:** Three layers: (1) gameplay skill with MTG rules + decision framework loaded before play, (2) `arena turn` CLI command producing agent-friendly turn state, (3) `arena debrief` CLI command for post-session analysis. Existing `arena play`/`land`/`attack-all`/`click` are the execution layer — unchanged.

**Tech Stack:** Python (arena CLI), Markdown (skill)

---

### Task 1: Gameplay Skill

**Files:**
- Create: `.claude/skills/gameplay/SKILL.md`

This is the highest-value piece. Agents load it before any play session. It prevents rule violations and gives decision structure.

- [ ] **Step 1: Write the skill file**

```markdown
---
name: gameplay
description: Load before playing MTGA bot matches. MTG rules, turn structure, decision framework for agent-driven gameplay.
---

# Gameplay: Agent Play Guide

You are playing Magic: The Gathering Arena against Sparky (bot).
Your goal is NOT to play optimally — it's to cast spells, exercise
mechanics, and mine data. Play fast, play reasonable, don't get stuck.

## Before You Start

1. Confirm server running: `arena health`
2. Start match: `arena bot-match --deck "<name>"`
3. Wait for game to begin (bot-match handles mulligan)

## Turn Loop

Every turn when you have priority:

1. **Read state:** `arena turn`
2. **Follow the phase rule** (see below)
3. **Execute ONE action** via arena commands
4. **Check result** in next `arena turn` output
5. **Pass priority** when done: `arena click 888,504`

Repeat until game ends or target turn reached.

## Phase Rules

**Your Main Phase (pre-combat):**
1. Play a land IF you haven't this turn AND have one → `arena land`
2. Cast cheapest playable spell that advances your mode → `arena play "<name>"`
3. If nothing to do → pass (click action button)

**Combat (DeclareAttack):**
- Attack mode: `arena attack-all` (simple — sends everything)
- Defend mode: skip, just pass
- Mining mode: attack if ahead on board, skip if behind

**Opponent's Turn:**
- Pass priority immediately unless you have an instant and a reason
- Don't overthink — click action button to pass

**End of Turn:**
- Click action button to pass to next turn

## Hard Rules (NEVER violate)

1. **ONE land per turn.** After `arena land` succeeds, do NOT play another land this turn.
   Check `arena turn` output — if `lands_played: 1`, stop playing lands.
2. **Pay mana.** Only cast spells that appear in `actions` with `actionType: ActionType_Cast`
   or `ActionType_Play`. If it's not in actions, you can't afford it.
3. **Don't loop.** If an action fails twice, skip it and pass.
   Stuck = `arena click 888,504` to pass priority.
4. **Don't play during opponent's turn** unless you have a clear instant-speed response.
5. **Dismiss prompts.** Scry/Surveil → click Done. Target selection → pick first valid target.
   Don't agonize over choices.

## Play Modes

Agent receives a mode directive. Modes affect creature combat only — casting follows curve regardless.

| Mode | Combat | Priority |
|------|--------|----------|
| `mining` | Attack when ahead on board, block when behind | Cast on curve, fast passes |
| `attack` | Always attack-all | Aggressive, skip defense |
| `defend` | Never attack, always block | Hold mana for instants |

Default: `mining`

## Arena Commands Reference

| Action | Command |
|--------|---------|
| Read turn state | `arena turn` |
| Play a land | `arena land` |
| Cast a spell | `arena play "Card Name"` |
| Attack with all | `arena attack-all` |
| Pass priority | `arena click 888,504` |
| Navigate home | `arena navigate Home` |

## Recovery

- **Stuck priority:** `arena click 888,504` (pass button) 3 times with 2s gaps
- **Unknown modal:** `arena click 480,300` then `arena click 888,504`
- **Game frozen:** `just scry state` to diagnose, then `arena navigate Home`
- **Scry stale:** actions list empty when it shouldn't be → `arena turn` again (cache clears)

## Session Journaling

Log every turn decision to `/tmp/gameplay-journal.md`:
```
T3 Main1 | Hand: 5 | Life 20-20 | Played: Mountain, cast Goblin Guide
T3 Combat | attack-all | opp 20→17
T4 Main1 | Hand: 3 | Life 20-17 | cast Lightning Bolt (no target prompt)
```

At session end, run `arena debrief` and include output in your report.
```

- [ ] **Step 2: Verify skill appears in skill list**

Run: `ls .claude/skills/gameplay/SKILL.md`
Expected: file exists

- [ ] **Step 3: Commit**

```bash
git add .claude/skills/gameplay/SKILL.md
git commit -m "feat: add gameplay skill for agent-driven MTGA play"
```

---

### Task 2: `arena turn` Command

**Files:**
- Create: `tools/py/src/leyline_tools/arena/turn.py`
- Modify: `tools/py/src/leyline_tools/arena/cli.py` (add import + COMMANDS entry)

Produces structured turn state for agent consumption. Combines scry state into a decision-ready format.

- [ ] **Step 1: Write `turn.py`**

```python
"""arena turn — structured turn state for agent decision-making."""
from __future__ import annotations

import json

from .common import check_help, die
from .scry_bridge import _scry_cache_clear, _scry_state


def cmd_turn(args: list[str]) -> None:
    check_help(args, cmd_turn)
    _scry_cache_clear()
    state = _scry_state()
    if state is None:
        die("scry unavailable — is a game in progress?")

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
    hand_names = [c.get("name", "?") for c in hand]

    # Actions (seat 1 only)
    actions = [a for a in state.get("actions", []) if a.get("seatId") == 1]

    # Classify actions
    lands = []
    casts = []
    other_actions = []
    for a in actions:
        at = a.get("actionType", "")
        name = a.get("name", "?")
        cost = a.get("manaCost", "")
        if at == "ActionType_Play" and not cost:
            lands.append(name)
        elif at in ("ActionType_Cast", "ActionType_Play"):
            casts.append(f"{name} ({cost})" if cost else name)
        else:
            short = at.replace("ActionType_", "")
            other_actions.append(f"{short}: {name}")

    # Lands played this turn: if no land in actions but lands in hand, already played
    hand_ids = {c.get("id") for c in hand}
    has_land_in_hand = any(
        a.get("actionType") == "ActionType_Play"
        and not a.get("manaCost")
        and a.get("instanceId") in hand_ids
        for a in actions
    )
    lands_played = 0 if has_land_in_hand else (1 if any(
        c.get("name", "").lower() in ("swamp", "island", "mountain", "plains", "forest")
        or c.get("type", "").startswith("Land")
        for c in hand
    ) else 0)
    # Simpler: if we can play a land, lands_played=0. If we can't but have lands in hand, =1.
    # If no lands in hand, unknown — report "?"
    hand_has_land = any("Land" in c.get("type", "") or c.get("name", "") in
        ("Swamp", "Island", "Mountain", "Plains", "Forest") for c in hand)
    if has_land_in_hand:
        lands_played_str = "0"
    elif hand_has_land:
        lands_played_str = "1 (no land-play action available)"
    else:
        lands_played_str = "n/a (no lands in hand)"

    # Battlefield summary from scry zones
    bf = state.get("battlefield", [])
    bf_ours = [c.get("name", "?") for c in bf if c.get("controller") == 1]
    bf_opp = [c.get("name", "?") for c in bf if c.get("controller") == 2]

    # Output
    print(f"T{turn} {phase} | {'Your turn' if is_our_turn else 'Opp turn'} | Life {our_life}-{opp_life}")
    print(f"Hand ({len(hand)}): {', '.join(hand_names) if hand_names else '(empty)'}")
    print(f"Lands played: {lands_played_str}")
    if lands:
        print(f"Can play land: {', '.join(lands)}")
    if casts:
        print(f"Can cast: {', '.join(casts)}")
    if other_actions:
        print(f"Other actions: {', '.join(other_actions)}")
    if not lands and not casts and not other_actions:
        if is_our_turn:
            print("No actions available — pass priority")
        else:
            print("Opponent's turn — pass priority")
    if bf_ours or bf_opp:
        print(f"Board: you={', '.join(bf_ours) if bf_ours else '(empty)'} | opp={', '.join(bf_opp) if bf_opp else '(empty)'}")
```

- [ ] **Step 2: Register in cli.py**

Add to imports:
```python
from .turn import cmd_turn
```

Add to COMMANDS dict:
```python
"turn": cmd_turn,
```

Add to COMMAND_HELP dict:
```python
"turn": "Structured turn state for agent decision-making",
```

- [ ] **Step 3: Test manually**

Run: `just arena turn`
Expected: structured output with turn number, phase, hand, actions

- [ ] **Step 4: Commit**

```bash
git add tools/py/src/leyline_tools/arena/turn.py tools/py/src/leyline_tools/arena/cli.py
git commit -m "feat: add arena turn command for agent decision context"
```

---

### Task 3: `arena debrief` Command

**Files:**
- Create: `tools/py/src/leyline_tools/arena/debrief.py`
- Modify: `tools/py/src/leyline_tools/arena/cli.py` (add import + COMMANDS entry)

Post-session analysis. Parses session JSONL + scry for structured report.

- [ ] **Step 1: Write `debrief.py`**

```python
"""arena debrief — post-session analysis of gameplay."""
from __future__ import annotations

import json
from collections import Counter
from pathlib import Path

from .common import check_help


def _latest_session() -> Path | None:
    sessions = Path("/tmp/arena/sessions")
    if not sessions.exists():
        return None
    for date_dir in sorted(sessions.iterdir(), reverse=True):
        if not date_dir.is_dir():
            continue
        logs = sorted(date_dir.glob("*.jsonl"), reverse=True)
        if logs:
            return logs[0]
    return None


def cmd_debrief(args: list[str]) -> None:
    check_help(args, cmd_debrief)

    # Find session log
    if args and not args[0].startswith("--"):
        log_path = Path(args[0])
    else:
        log_path = _latest_session()

    if log_path is None or not log_path.exists():
        print("No session log found. Play a game first.")
        return

    entries = []
    for line in log_path.read_text().splitlines():
        if line.strip():
            try:
                entries.append(json.loads(line))
            except json.JSONDecodeError:
                continue

    if not entries:
        print(f"Empty session: {log_path}")
        return

    # Basic stats
    total = len(entries)
    errors = [e for e in entries if e.get("error")]
    duration_s = entries[-1].get("t", 0) / 1000 if entries else 0

    cmd_counts = Counter(e.get("cmd") for e in entries)
    error_counts = Counter(e.get("cmd") for e in errors)

    # Detect patterns
    repeated_fails = []
    prev_fail = None
    fail_streak = 0
    for e in entries:
        key = f"{e.get('cmd')} {json.dumps(e.get('args', []))}"
        if e.get("error"):
            if key == prev_fail:
                fail_streak += 1
            else:
                if fail_streak >= 2:
                    repeated_fails.append((prev_fail, fail_streak))
                prev_fail = key
                fail_streak = 1
        else:
            if fail_streak >= 2:
                repeated_fails.append((prev_fail, fail_streak))
            prev_fail = None
            fail_streak = 0
    if fail_streak >= 2:
        repeated_fails.append((prev_fail, fail_streak))

    # Detect double land plays
    land_plays = [e for e in entries if e.get("cmd") == "land" and not e.get("error")]
    # Group by approximate time (within 60s = same turn)
    double_lands = []
    for i in range(1, len(land_plays)):
        gap = land_plays[i].get("t", 0) - land_plays[i - 1].get("t", 0)
        if gap < 60000:  # within 60s
            double_lands.append(land_plays[i].get("ts", "?"))

    # Output report
    print(f"=== Session Debrief ===")
    print(f"Log: {log_path}")
    print(f"Duration: {duration_s:.0f}s | Commands: {total} | Errors: {len(errors)}")
    print()

    print("Command usage:")
    for cmd, count in cmd_counts.most_common():
        err = error_counts.get(cmd, 0)
        err_str = f" ({err} failed)" if err else ""
        print(f"  {cmd}: {count}{err_str}")

    if repeated_fails:
        print()
        print("Repeated failures (stuck loops):")
        for fail, count in repeated_fails:
            print(f"  {count}x consecutive: {fail}")

    if double_lands:
        print()
        print("RULE VIOLATION: Double land plays detected:")
        for ts in double_lands:
            print(f"  at {ts}")

    if errors:
        print()
        print("Error details:")
        for e in errors[:10]:
            ts = e.get("ts", "?")
            cmd = e.get("cmd", "?")
            stderr = e.get("stderr", "")
            print(f"  [{ts}] {cmd} {e.get('args', [])}")
            if stderr:
                print(f"    → {stderr[:200]}")

    # Suggestions
    print()
    print("--- Suggestions ---")
    if repeated_fails:
        print("- Reduce retry loops — pass priority after 2 failures")
    if double_lands:
        print("- Check `arena turn` lands_played before playing land")
    if error_counts.get("play", 0) > 2:
        print("- Card play failures: check hand detection / card names")
    if error_counts.get("click", 0) > 2:
        print("- Click failures: check OCR text matching or use coords")
    if not errors and not repeated_fails:
        print("Clean session — no issues detected")
```

- [ ] **Step 2: Register in cli.py**

Same pattern as Task 2.

- [ ] **Step 3: Test with existing session data**

Run: `just arena debrief`
Expected: session summary or "No session log found"

- [ ] **Step 4: Commit**

```bash
git add tools/py/src/leyline_tools/arena/debrief.py tools/py/src/leyline_tools/arena/cli.py
git commit -m "feat: add arena debrief command for post-session analysis"
```

---

### Task 4-6: Three Bot Match Sessions (Sonnet)

After Tasks 1-3 are committed, dispatch three parallel sonnet agents. Each plays a bot match to turn 6 using the gameplay skill, different deck, `arena turn` for state, journals to file.

**Session 1:** Deck "Red aggro", mode `attack`
**Session 2:** Deck "Green stompy", mode `mining`
**Session 3:** Deck "Black aggro", mode `defend`

Each agent:
1. Loads gameplay skill
2. `arena bot-match --deck "<name>"`
3. Plays turns using `arena turn` → decide → execute loop
4. Journals to `/tmp/session-{N}-journal.md`
5. Stops after turn 6 or game end
6. Runs `arena debrief`
7. Reports: journal + debrief output

**Success criteria:** Each session produces a journal + debrief. We analyze findings together.
