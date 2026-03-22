---
name: write-puzzle
description: Write a Forge .pzl puzzle file for a specific game scenario. Knows card names, AI behavior, and how to set up boards that reliably trigger bugs.
---

## What I do

Create a `.pzl` puzzle file that sets up an exact board state for testing. Output: the puzzle file + explanation of why the setup works.

## When to use me

- "write a puzzle where AI attacks with flyers"
- "I need a board state with mana dorks and lands for autotap testing"
- "set up a scenario where blocking matters"
- As part of Tier 1/2 reproduction in the dev-loop

## Reference

Full docs: `docs/puzzles.md`. Format spec: `docs/puzzle-format.md`. Existing puzzles: `matchdoor/src/test/resources/puzzles/`.

## Format

```ini
[metadata]
Name:<descriptive name>
Goal:Win
Turns:10
Difficulty:Tutorial
Description:<what this tests and why>

[state]
ActivePlayer=Human
ActivePhase=Main1
HumanLife=20
AILife=20

humanbattlefield=<semicolon-separated card names>
humanhand=<cards in hand>
humanlibrary=<cards in library>
aibattlefield=<AI's permanents>
ailibrary=<AI's library>
```

## Rules for good puzzles

### Planeswalkers on battlefield need explicit loyalty
Forge doesn't auto-set starting loyalty for pre-placed permanents. Without counters, SBA immediately kills them (0 loyalty).
```
humanbattlefield=Liliana, Death's Majesty|Counters:LOYALTY=5
```
**Simpler alternative:** put the planeswalker in hand and cast it — Forge handles ETB loyalty correctly.

### Card names must be exact Forge names
- Use full English names: `Llanowar Elves`, `Lightning Bolt`, `Grizzly Bears`
- Check `just card-grp "<name>"` if unsure — card must exist in Forge
- Prefer simple cards (vanilla/french vanilla) over complex ones for test clarity
- Avoid cards with ETB triggers unless testing triggers — they add noise

### ActivePlayer is always Human
- Only `Main1` phase is supported as starting phase
- If you need AI to act first, set `ActivePlayer=Human` and pass the turn

### Forcing AI behavior
- **AI attacks when profitable:** give AI more power than our toughness, or set human life low
- **AI blocks when threatened:** give AI creatures and attack with bigger ones
- **AI casts spells:** put castable spells in AI hand with enough mana on battlefield (but AI hand isn't reliable — prefer battlefield setup)
- **AI does nothing useful:** give AI no creatures or only tapped ones

### Library matters
- Always include 5+ cards in both libraries — game draws fail otherwise
- Use basic lands as filler: `Plains;Plains;Plains;Plains;Plains`

### Life totals as forcing functions
- `HumanLife=5` + AI has 3-power creatures → AI always attacks
- `AILife=3` + human has Lightning Bolt → one-shot kill scenario
- `HumanLife=1` → tests lethal blocking decisions

### Zone keywords
- `humanbattlefield`, `humanhand`, `humanlibrary`, `humangraveyard`, `humanexile`
- `aibattlefield`, `aihand`, `ailibrary`, `aigraveyard`, `aiexile`

## Validation (mandatory)

After writing the puzzle, **always run:**
```bash
just puzzle-check matchdoor/src/test/resources/puzzles/my-puzzle.pzl
```
This checks every non-basic-land card against Arena's client DB. `FAIL` = card has no grpId → puzzle will NPE at runtime. Fix by picking a different card (`just card-grp "<name>"` to search).

## Testing the puzzle

### Tier 1 — unit test (no server)
```kotlin
val puzzleText = File("src/test/resources/puzzles/my-puzzle.pzl").readText()
val (bridge, game, _) = base.startPuzzleAtMain1(puzzleText)
// assert on game state, actions, etc.
```

### Tier 2 — in-game (server + client)
```bash
just serve-puzzle matchdoor/src/test/resources/puzzles/my-puzzle.pzl
# then connect client via arena launch
```

## Output format

When writing a puzzle, output:

1. **The `.pzl` file** — ready to save
2. **Why this setup works** — 2-3 sentences on why the board state reliably triggers the scenario
3. **What to test** — what to look for (action types, visual state, AI behavior)
4. **Suggested file path** — `matchdoor/src/test/resources/puzzles/<name>.pzl`
