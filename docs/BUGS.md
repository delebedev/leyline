# Nexus: Current State

## What Works

- Engine wired end-to-end: auth → mulligan → keep → game loop
- Hand served to client, cards playable
- Mulligan presented (can't re-deal hand yet)
- Turn loop works — Sparky plays cards, applies instants, attacks
- Player can play lands and creatures
- AI cards visible on battlefield (fixed: pendingMessageCount gate bug)
- Phase skip / priority passing — clunky but functional

## Bugs / Short-Term Improvements

- **Instant targeting**: player can cast instants but can't target them
- **Stack visuals**: Sparky's instants stuck on stack visually (effect applies correctly to field)
- **Combat targeting**: attack-all somewhat works; declaring blockers targeting doesn't
- **Hand overflow**: suspected game stuck on 8+ cards (discard not implemented) — not confirmed
- **Concede**: tried wiring client concede button but doesn't work yet
- **Phase indicators**: glitch where player and AI indicators update in sync
