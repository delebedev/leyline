# Puzzle Design Guide

How to write `.pzl` files that produce reliable, unambiguous signals.

## Core Principles

**Minimum cards.** No lands/spells if you're testing combat. 1 library card per player to survive draw step. Every extra card is noise.

**Force determinism.** Don't rely on Forge AI making the "obvious" play. AI is conservative — won't trade down even with collective lethal on board. Use rules-text constraints (`Juggernaut` must attack) instead of hoping AI heuristics cooperate.

**Life totals as kill switch.** Set human life to exactly lethal damage so unblocked = death. Turns Survive goal into a diagnostic: alive = mechanic worked, dead = mechanic broken.

## AI Behavior Traps

Forge AI evaluates trades individually, not collectively:

- **2x 2/2 vs 3 life** — AI sees each 2/2 dying to a 3/3 blocker → doesn't attack. False positive on Survive goal.
- **Juggernaut vs 3 life** — rules text forces attack regardless of board state. Deterministic.
- **5/5 vs 3 life, no blockers** — AI sees free lethal → attacks. But add a 6/6 blocker and AI may refuse.

Safe forced-attack creatures on Arena: `Juggernaut` (5/3, must attack).

## Goal Selection

| Goal | Tests | False positive risk |
|------|-------|-------------------|
| `Survive` | "Did I live?" | AI might not attack → survive without testing mechanic |
| `Destroy Specified Creatures` | "Did target die?" | Creature only dies if combat happened correctly |
| `Win` | "Did opponent die?" | Opponent might die to wrong cause |

**`Destroy Specified Creatures`** is the strongest single-puzzle goal for combat — the creature can only die if the AI attacked AND blocking/damage worked. If AI doesn't attack, creature survives, turn limit fires `LosesGame`.

**`Survive` + forced attacker** is simpler when you just need pass/fail on blocking.

## Paired Puzzles

When one puzzle can't disambiguate, use two with complementary goals:

| Puzzle A (Survive) | Puzzle B (Destroy) | Diagnosis |
|---|---|---|
| WIN | WIN | Mechanic works |
| LOSE | LOSE | Can't block — broken |
| WIN | LOSE | AI not attacking — engine bug |

## HumanControl

`HumanControl:true` gives the human player control of both seats. Useful for MatchHarness scripted runs — the solution script drives both sides deterministically. Awkward for Arena playtesting (human clicks for both players).

## Failure Classes

Design puzzles so each failure mode is distinguishable:

| Outcome | Meaning |
|---------|---------|
| WIN (game over, human alive) | Mechanic works as intended |
| LOSE (human died) | Mechanic broken — damage/targeting/resolution failed |
| LOSE (turn limit) | Required action never happened (AI didn't attack, effect didn't trigger) |
| TIMEOUT (no game over) | Engine hang — prompt/priority wiring broken |
| CRASH (exception) | Card missing, unhandled action type, NPE |

## Template: Combat Blocking

```ini
[metadata]
Name:Block or Die
Goal:Survive
Turns:2
Difficulty:Easy
Description:Forced attacker. Block to survive.

[state]
ActivePlayer=AI
ActivePhase=Main1
HumanLife=3
AILife=20

humanbattlefield=Centaur Courser;Centaur Courser
humanlibrary=Forest
aibattlefield=Juggernaut
ailibrary=Mountain
```

Why it works: Juggernaut (5/3) must attack. Human at 3 life = lethal unblocked. Two 3/3s double-block to kill it. Library has 1 card to survive draw.

## Template: Spell Targeting (Player)

```ini
[metadata]
Name:Bolt Face
Goal:Win
Turns:1
Difficulty:Easy
Description:Cast Lightning Bolt targeting opponent.

[state]
ActivePlayer=Human
ActivePhase=Main1
HumanLife=20
AILife=3

humanhand=Lightning Bolt
humanbattlefield=Mountain
humanlibrary=Mountain
ailibrary=Mountain
```

Why it works: One spell, one land, exact lethal. No AI interaction needed.

## Template: Destroy Specified Creatures

```ini
[metadata]
Name:Kill the Blocker
Goal:Destroy Specified Creatures
Targets:Creature.OppCtrl
Turns:2
Difficulty:Easy
Description:Attack with bigger creature to destroy blocker.

[state]
ActivePlayer=Human
ActivePhase=Main1
HumanLife=20
AILife=20

humanbattlefield=Centaur Courser
humanlibrary=Forest
aibattlefield=Grizzly Bears
ailibrary=Mountain
```

## Checklist

Before committing a puzzle:

- [ ] `just card-grp "<name>"` for every card — no output = NPE crash
- [ ] Library has ≥1 card per player (draw step)
- [ ] AI behavior is deterministic (forced attack, no heuristic dependency)
- [ ] Goal distinguishes "mechanic worked" from "nothing happened"
- [ ] Life totals create clear pass/fail boundary
- [ ] No unnecessary cards (lands, extra creatures, hand cards)
