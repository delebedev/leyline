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
4. **Read state again** after each action — confirm it worked
5. **Pass priority** when done: `arena click 888,504`

Repeat until game ends or target turn reached.

**Pacing:** Don't rush. After each action, wait 1-2s for animations, then `arena turn` again.
If `arena turn` shows same state twice, you need to pass priority.

## Phase Rules

**Your Main Phase (pre-combat or Main1):**
1. Play a land IF `arena turn` shows `Can play land:` → `arena land`
2. Cast cheapest playable spell → `arena play "<name>"`
3. If nothing to do → pass (click action button)

**Your Main Phase 2 (post-combat):**
1. Cast remaining affordable spells
2. Pass

**Combat (DeclareAttack):**
- Attack mode: `arena attack-all`
- Defend mode: skip, just pass
- Mining mode: attack if you have more creatures than opponent

**Opponent's Turn:**
- Pass priority immediately. Click action button.
- Exception: if you have a clear instant-speed play and mode is `defend`

**End Step / Cleanup:**
- Click action button to pass

## Hard Rules (NEVER violate)

1. **ONE land per turn.** After `arena land` succeeds, do NOT play another land.
   If `arena turn` does NOT show `Can play land:`, the land drop is used. STOP.
2. **Only cast from actions.** Only play cards that appear in `arena turn` under
   `Can cast:`. If a card is in your hand but not in "Can cast", you can't afford it.
3. **Don't loop.** If an action fails twice, SKIP IT. Pass priority instead.
   Two failures = move on. `arena click 888,504`
4. **Don't play spells on opponent's turn** unless mode is `defend` and you have an instant.
5. **Dismiss prompts quickly.** Scry/Surveil → `arena click 480,491` (Done button).
   Target selection → pick first valid option. Don't agonize.
6. **One action at a time.** Read state → act → read state. Never chain blind actions.

## Play Modes

Agent receives a mode directive. Modes affect creature combat only —
spell casting always follows mana curve regardless of mode.

| Mode | Combat Behavior | Notes |
|------|----------------|-------|
| `mining` | Attack when you have more creatures | Default. Cast on curve, fast passes |
| `attack` | Always `arena attack-all` | Aggressive. Skip blocking |
| `defend` | Never attack. Block when able | Hold mana for instants if available |

## Arena Commands Quick Reference

| Action | Command |
|--------|---------|
| Read turn state | `arena turn` |
| Play a land | `arena land` |
| Cast a spell | `arena play "Card Name"` |
| Attack with all | `arena attack-all` |
| Pass priority | `arena click 888,504` |
| Dismiss modal | `arena click 480,491` |
| Check screen | `arena where` |
| Check errors | `arena errors` |
| Navigate home | `arena navigate Home` |

## Prompts (IMPORTANT)

`arena turn` detects UI prompts and prints `PROMPT: <text>`. Handle these:

- **"Discard a card"** → click a card in hand (rightmost), then `arena click 886,504` (Submit)
- **"Explore"** → auto-dismissed after play. If stuck, `arena click 480,491` (Done)
- **"Surveil" / "Scry"** → auto-dismissed after play. If stuck, `arena click 480,491` (Done)
- **"Choose a target"** → click a creature on the battlefield. Pick opponent's if removing,
  yours if buffing. When unsure, click the first creature you see.
- **"Select"** → generic selection prompt. Click the highlighted option, then Submit.

If you see a PROMPT line, handle it BEFORE trying to play more cards.

## Recovery

- **"No actions available"** → `arena click 888,504` (pass). This is normal.
- **Stuck (same state 3x)** → `arena click 888,504` three times, 2s gaps between.
- **Unknown modal/popup** → `arena click 480,300` then `arena click 888,504`
- **Game frozen** → `arena errors` to diagnose, then `arena navigate Home`
- **Scry shows turn 0** → game hasn't started or has ended. Check `arena where`.

## Session Journaling

Write a log line after EVERY turn to your journal file:

```
T3 Main1 | Hand: 5 | Life 20-18 | Played: Mountain, cast Goblin Guide
T3 Combat | attack-all | opp 18→15
T4 Opp turn | passed priority
T4 Main1 | Hand: 3 | Life 20-15 | cast Lightning Bolt → face
```

Format: `T{turn} {phase} | {what happened}`

At session end, run `arena debrief` and include the output.

## What NOT To Do

- Don't try to read card text or evaluate complex board states
- Don't hold cards for "the right moment" — cast on curve
- Don't attempt to do combat math — attack-all or don't attack
- Don't retry failed plays more than twice
- Don't try to play cards that aren't in your `arena turn` actions list
- Don't navigate menus during a game — use arena commands only
