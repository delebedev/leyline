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
- **~~Double DeclareBlockersReq stalls client~~** *(fixed 2026-02-21)*: `pendingBlockersSent` flag in `CombatHandler`. Commit `15d4723786`.
- **~~Hand overflow / Cleanup stall~~** *(fixed 2026-02-21)*: Non-targeting prompt auto-resolve in `TargetingHandler.checkPendingPrompt`. Commit `15d4723786`.
- **No PhaseStopProfile → client stalls at non-essential phases**: `GameBridge.start()` creates `WebPlayerController` with no `phaseStopProfile`, so every phase grants priority. Client receives `ActionsAvailableReq` at CombatDamage/CombatEnd/EndOfTurn where real Arena would auto-pass. If client doesn't respond, bridge times out (120s), engine auto-passes silently, client never gets updated state. Confirmed via recording 2026-02-22_10-36-29. Fix: wire `PhaseStopProfile.createDefaults()`. Blocked on `PhaseOrStepModified` annotation regression — see `docs/plans/phase-stop-profile.md`.
- **Concede**: tried wiring client concede button but doesn't work yet
- **Phase indicators**: glitch where player and AI indicators update in sync

## Protocol / Proto Bugs

- **gameOverBundle missing prevGameStateId**: `BundleBuilder.gameOverBundle()` does not set `setPrevGameStateId()` on any of the 3 GS Diff messages. Breaks the prevGameStateId chain required by the real server. Client may show blank game-over screen or fail to transition to intermission. Fix: chain gs1→gsId, gs2→gs1, gs3→gs2.
- **Face-down cards not implemented**: `ObjectMapper.applyCardFields()` never checks `card.isFaceDown` and never sets `isFacedown=true` or overrides `overlayGrpId`. Morph/manifest/disguise cards would leak identity to opponent. Low priority — morph/manifest rarely triggered in current integration.

## Test / Conformance Gaps

- **AnnotationPipelineTest missing category coverage**: Only PlayLand/CastSpell/Resolve/ZoneTransfer have pipeline annotation-shape tests. Missing: Destroy, Sacrifice, Exile, Countered, Bounce, Draw, Discard, Mill. Proto output shape for these categories is untested.
- **inferCategory fallback incomplete**: `AnnotationPipeline.inferCategory()` (proto-level fallback when events unavailable) handles only 5 zone pairs. Missing 8 zone-pair categories that `AnnotationBuilder.zoneChangedCategory` handles: Discard (Hand→GY), Draw (Lib→Hand), Mill (Lib→GY), Bounce (BF→Hand/Lib), Countered (Stack→GY), Exile (Hand→Exile, any→Exile). Low priority — event-driven path covers these correctly.
