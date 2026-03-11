# Forge Oracle — Proxy Mode (Investigation)

## Intent

Evaluate feasibility of asking Forge "what would you play?" when playing against real Arena servers (proxy mode). Forge engine isn't running — we'd need to reconstruct a Forge Game from protocol data and evaluate it. Two approaches to investigate: snapshot eval via Game API, and snapshot eval via puzzle infrastructure. Decide which (if either) is viable before building.

## Why

Local mode oracle is trivial but only works against Sparky/bot matches. Proxy mode is where we play real games and record protocol sessions. Having Forge evaluate real game positions would make proxy recordings much smarter — better plays, longer games, more diverse protocol coverage.

## Investigation tracks

### Track A: Forge Game API construction

Can we programmatically construct a `Game` object with arbitrary board state? Not through .pzl text format — through the Java API directly. Questions:

- What's the minimum setup to get a `Game` that `SpellAbilityPicker` can evaluate?
- Can we add cards to zones by name? (`Card` construction from card name)
- Can we set tapped state, counters, attachments, life totals?
- Does the Game need a full history or can it work from a snapshot?
- What breaks if the Game has no prior turns/phases?

### Track B: Puzzle infrastructure limits

What can and can't the existing puzzle parser handle? How far could we stretch it?

- Current .pzl format capabilities (zones, life, turn, phase)
- Missing: enchantment attachments, +1/+1 counters, stack, emblems, triggered abilities waiting
- Could the parser be extended, or is the Game API approach strictly better?

### Track C: Card mapping

Arena grpId → Forge card name. We have `just card <grpId>` for individual lookups. Questions:

- Is there a bulk mapping table or do we need to build one?
- What percentage of Arena Standard/Explorer cards exist in Forge's DB?
- Digital-only cards (perpetual, conjure, seek) — how many, how often do they appear in real games?

## Key files

- `forge/forge-game/src/main/java/forge/game/Game.java` — core game object
- `forge/forge-game/src/main/java/forge/game/GameState*.java` — any state serialization/deserialization
- `forge/forge-game/src/main/java/forge/game/zone/` — zone management
- `forge/forge-game/src/main/java/forge/game/card/Card.java` — card construction
- `forge/forge-ai/src/main/java/forge/ai/simulation/SpellAbilityPicker.java` — what Game state it actually reads
- `forge/forge-game/src/main/java/forge/game/GameAction.java` — game actions, may have setup helpers
- `docs/rosetta.md` — Arena↔Forge zone/type mappings
- `bin/scry_lib/tracker.py` — scry state format (what we'd map FROM)
- `matchdoor/src/main/kotlin/leyline/matchdoor/bridge/` — existing Forge↔Arena mapping code (reference for reverse direction)

## Guidance

This is a research task — read code, assess feasibility, write findings. No implementation yet.

1. Start with SpellAbilityPicker — trace what it reads from `Game` and `Player`. Understand minimum viable Game state.
2. Search Forge for any existing "construct game from scratch" utilities (test setup, puzzle loading, `GameState` deserializers).
3. Assess Track A vs Track B — which gives us a usable Game with less effort.
4. Card name resolution: check if `matchdoor/bridge` already has a grpId↔name table we can reverse.
5. Write up findings with concrete recommendation: feasible/not, estimated effort, blockers.
