---
summary: "How to write .pzl files: minimum cards, forced determinism, life totals as kill switch, AI behavior traps, and safe forced-attack creatures."
read_when:
  - "writing a new .pzl puzzle file"
  - "debugging a flaky or non-deterministic puzzle"
  - "understanding AI behavior quirks in puzzle mode"
---
# Puzzle Design Guide

How to write `.pzl` files that produce reliable, unambiguous signals.

## Core Principles

**Minimum cards.** No lands/spells if you're testing combat. 1 library card per player to survive draw step. Every extra card is noise.

**Force determinism.** Don't rely on Forge AI making the "obvious" play. AI is conservative — won't trade down even with collective lethal on board. Use rules-text constraints (`Juggernaut` must attack) instead of hoping AI heuristics cooperate.

**Life totals as kill switch.** Set human life to exactly lethal damage so unblocked = death. Turns Survive goal into a diagnostic: alive = mechanic worked, dead = mechanic broken.

**One win path.** If testing a specific mechanic, close off alternative wins. Testing activated ability? Give AI a blocker so combat can't win. Testing a spell? Don't give creatures that could attack for lethal. The puzzle should only be solvable through the mechanic under test.

**No summoning sickness.** Puzzle-placed creatures have sickness cleared by default (Forge `GameState` calls `setSickness(false)`). Tap abilities work immediately. Add `|SummonSick` to opt in.

## AI Behavior Traps

Forge AI evaluates trades individually, not collectively:

- **2x 2/2 vs 3 life** — AI sees each 2/2 dying to a 3/3 blocker → doesn't attack. False positive on Survive goal.
- **Juggernaut vs 3 life** — rules text forces attack regardless of board state. Deterministic.
- **5/5 vs 3 life, no blockers** — AI sees free lethal → attacks. But add a 6/6 blocker and AI may refuse.

Safe forced-attack creatures on Arena: `Juggernaut` (5/3, must attack).

### AI Hand: Defensive Reliable, Offensive Unreliable

AI won't cast spells it doesn't need. A pump spell in hand when AI already has lethal on board? AI ignores it. An extra creature when AI is already winning? AI may not bother.

**Flip the threat.** Instead of "AI casts something, human reacts" (heuristic-dependent), make the human the aggressor: "human threatens lethal, AI *must* react or die." Defensive casts are reliable — AI always tries to not die.

- **Offensive (unreliable):** AI has Giant Growth + attacking creature. AI already deals lethal without pump → never casts it.
- **Defensive (reliable):** Human attacks for lethal. AI has Unsummon → AI *must* bounce or die → always casts it.

When a puzzle needs AI to cast from hand, ensure the board makes that spell the AI's only path to survival.

### Mono-Color When Possible

Fewer colors = less noise. No color-screw risk, fewer lands to set up, cleaner board reads. The counterspell puzzle (Air Elemental + Cancel vs Unsummon) works as all-blue — one land type for both sides.

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

## Template: Activated Ability

```ini
[metadata]
Name:Ping for Lethal
Goal:Win
Turns:1
Difficulty:Easy
Description:Tap Goblin Fireslinger to ping opponent for lethal. Attacking is suicide.

[state]
ActivePlayer=Human
ActivePhase=Main1
HumanLife=20
AILife=1

humanbattlefield=Goblin Fireslinger
humanlibrary=Mountain
aibattlefield=Centaur Courser
ailibrary=Mountain
```

Why it works: Fireslinger (1/1) can't attack into Centaur Courser (3/3) — it dies. Only win path is the tap ability targeting opponent. Eliminates combat as alternative win condition.

**Pattern: use enemy blockers to close off combat wins** when testing non-combat mechanics.

## Pre-Flight: What Protocol Path Will This Hit?

A puzzle can be perfectly designed and still hang if the mechanic under test routes through an unimplemented protocol path. The legend rule puzzle (two Isamaru, choose which to keep) was minimal, deterministic, one win path — but it exercised `SelectNReq` routing for SBA choices, which wasn't wired. Result: infinite re-prompt loop (#123).

**Before writing a puzzle for a new mechanic, trace the protocol path it will take:**

1. **What Forge method handles it?** Find the `GameAction`/`SpellAbility`/`PlayerController` method. Legend rule → `GameAction.handleLegendRule()` → `chooseSingleEntityForEffect()`.

2. **What WebPlayerController override catches it?** That's where Forge blocks waiting for our response. Legend rule → `WebPlayerController.chooseSingleEntityForEffect()` → `PromptRequest(promptType="choose_cards")`.

3. **How does TargetingHandler/MatchSession route that prompt?** Follow `checkPendingPrompt()` — does it match surveil/scry? Modal? candidateRefs? Auto-resolve? Legend rule had candidateRefs → routed to `SelectTargetsReq` (wrong — should be `SelectNReq`).

4. **Is the outbound message type implemented?** Check `BundleBuilder` for the builder method. Check `MatchHandler` for the client response dispatch.

Quick check: `just wire coverage` shows which message types are handled vs observed. `docs/catalog.yaml` shows mechanic status. If the mechanic's protocol path touches anything marked `missing` or `partial`, expect a hang or crash — which is still valuable (the puzzle found the bug), but know going in.

**Puzzles that find protocol gaps are good.** The legend rule puzzle's value was discovering the `SelectTargetsReq` vs `SelectNReq` routing bug. But if the goal is to test a mechanic that *should* work end-to-end, trace the path first to avoid surprises.

### Case Study: Legend Rule Puzzle

The challenge: legend rule is an SBA (state-based action) — it happens automatically, not something you "cast." How do you build a Win puzzle around a passive rule?

**Make the SBA the only path to victory.** The trick is to create a board where the player *can't win without* triggering the rule:

```
humanhand=Isamaru, Hound of Konda
humanbattlefield=Isamaru, Hound of Konda|Tapped;Fervor;Plains;Plains
AILife=2
```

- First Isamaru is `|Tapped` — can't attack, dead weight
- Second Isamaru in hand — casting it triggers legend rule (choose which to keep)
- Keep the fresh (untapped) copy, sacrifice the tapped one
- Fervor grants haste — new Isamaru can attack immediately
- AI at 2 life — Isamaru is 2/2, exact lethal

7 cards total (Isamaru x2, Fervor, 2 Plains, 2 library padding). Every card has a job. No alternative win path — the tapped Isamaru can't attack, no other creatures, Fervor doesn't deal damage. The *only* way to win is: cast → legend rule → keep untapped → haste attack → lethal.

**Pattern: test passive rules by making them the bottleneck.** Block all other win paths, then set up a board where the rule *must* fire for the player to win. Works for any SBA or triggered ability — toughness-based removal (enchant own creature with -X/-X to clear a blocker), sacrifice triggers, etc.

## Template: Mechanic-as-Enabler (Library Search)

```ini
[metadata]
Name:Library Search Lethal
Goal:Win
Turns:1
Difficulty:Medium
Description:Cast Sylvan Ranger to search for Mountain, play it, then Lava Axe for lethal.

[state]
ActivePlayer=Human
ActivePhase=Main1
HumanLife=20
AILife=5

humanhand=Sylvan Ranger;Lava Axe
humanbattlefield=Forest;Forest;Mountain;Mountain;Mountain;Mountain
humanlibrary=Mountain;Forest
aibattlefield=Forest;Forest
ailibrary=Forest
```

**Pattern: mechanic enables mana for lethal.** The mechanic under test isn't the kill — it's the *enabler*. Player has 4 Mountains + 2 Forests (6 mana). Lava Axe costs 4R (needs 5 Mountains). Without the search, player is 1 Mountain short and can't win.

Win path: Sylvan Ranger (1G) → ETB search → find Mountain → play Mountain → 5 Mountains available → Lava Axe (4R) → 5 damage → lethal.

**Why Turns:1.** Everything happens in one turn. If it can't win in 1 turn, the puzzle fails — which is exactly the signal. A stall during SearchReq (engine blocks waiting for a response we never send) produces TIMEOUT, not LOSE. Clean failure mode separation:

| Outcome | Meaning |
|---------|---------|
| WIN | Search + land play + spell all worked |
| TIMEOUT | SearchReq not implemented (engine hangs on prompt) |
| LOSE (turn limit) | Search resolved but land didn't arrive or mana didn't work |

**Why not test search in isolation.** A puzzle that just searches and survives only proves the ETB trigger doesn't crash. By chaining search → land → spell → lethal, we verify the *result* of the search (card actually in hand, playable, produces mana). If the search "succeeds" but returns the wrong card or doesn't add it to hand, Lava Axe can't be cast and the puzzle fails.

**Protocol path:** Sylvan Ranger ETB → `chooseSingleEntityForEffect()` → `SearchReq` (GRE type 44) → `SearchResp` → ZoneTransfer(Library→Hand, category `Put`). SearchReq is not implemented (#169) — this puzzle will TIMEOUT until it is. That's the acceptance test.

## Checklist

Before committing a puzzle:

- [ ] `just card-grp "<name>"` for every card — no output = NPE crash
- [ ] Library has ≥1 card per player (draw step)
- [ ] AI behavior is deterministic (forced attack, no heuristic dependency)
- [ ] Goal distinguishes "mechanic worked" from "nothing happened"
- [ ] Life totals create clear pass/fail boundary
- [ ] No unnecessary cards (lands, extra creatures, hand cards)
- [ ] Trace the protocol path (see Pre-Flight above) — know what message types the mechanic will hit
