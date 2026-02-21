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
  - *Likely fixed (2026-02-21):* `WebPlayerController.playChosenSpellAbility` now passes `mayChooseTargets=true` for targeted spells → engine invokes `selectTargetsInteractively` → `InteractivePromptBridge` → client receives `SelectTargetsReq`. Verified in `TargetingFlowTest` (6 tests). Needs manual client test.
- **Stack visuals**: Sparky's instants stuck on stack visually (effect applies correctly to field)
  - *Possibly improved:* if client was stuck waiting for targeting prompt that never came, the instant targeting fix should unstick it. Needs manual client test.
- **Combat targeting**: attack-all somewhat works; declaring blockers targeting doesn't
  - *Partially fixed (2026-02-21):* `defenderPlayerId` now resolved explicitly in `MatchSession.onDeclareAttackers` (was null). Combat damage resolves for unblocked attackers. Blocker declaration path not changed — may still have issues. Verified in `CombatFlowTest` (7 tests). Needs manual client test for blockers.
- **Hand overflow**: suspected game stuck on 8+ cards (discard not implemented) — not confirmed
- **Concede**: tried wiring client concede button but doesn't work yet
- **Phase indicators**: glitch where player and AI indicators update in sync
