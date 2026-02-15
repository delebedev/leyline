# Nexus Basic Features — Design

Goal: all basic features working (turns, Sparky AI visibility, play land, play spell, attack). Tests green first, then client validation.

## Section 1: Fix DRAW Stall (keystone, unlocks 14 tests)

**Root cause:** GameBridgeTest.advanceToMain1() races with engine thread — reads live `game.phaseHandler.phase` instead of pending's snapshotted phase. No seed → random hands → smartPhaseSkip auto-passes DRAW without creating a pending.

**Fix:**
- Port ConformanceTestBase's `awaitFreshPending()` pattern (retry loop, 50ms sleep, freshness + future check)
- Read `pending.state.phase` instead of live game phase
- All tests use `seed = 42L`
- Extract shared helper to avoid duplication between GameBridgeTest and ConformanceTestBase

## Section 2: Load-Bearing Protocol Fixes

Three silent-drop issues from Arena client reference:

1. **Action embedding in GameStateMessage** — client reads `GameStateMessage.actions[]`, not just `ActionsAvailableReq`. Ensure StateMapper populates the `actions` field in buildFromGame/buildDiffFromGame.
2. **`cardTypes` in GameObjectInfo** — determines battlefield row (Land → bottom, Creature → top). Verify StateMapper sets the repeated `cardTypes` field, not just `type`.
3. **Game-start bundle shape** — Arena sends 3 GRE messages (Diff SendHiFi + Full SendAndRecord w/actions + ActionsAvailableReq). Current BundleBuilder.gameStart() builds 4. Reconcile against `arena-*` goldens.

## Section 3: Cast Spell Conformance

StateMapper categorizes zone transfers (hand→stack = CastSpell, stack→battlefield = Resolve) but untested against Arena golden.

- Test comparing cast-spell annotations against `arena-cast-creature.json`
- Verify annotation categories: CastSpell has ManaPaid + TappedUntappedPermanent + AbilityInstanceCreated
- Verify 4-message pattern (2x aiActionDiff: cast + resolve pairs)

## Section 4: Progressive Test Re-enablement

Re-enable GameBridgeTest tests as fixes land:
1. `gameStartBundleHasCorrectShape` — bundle structure
2. `playLandMovesCardToBattlefield` — land play flow
3. `gameObjectsHaveCardTypeFields` — cardTypes rendering
4. `landPlayProducesZoneTransferAnnotation` — annotation correctness
5. `postActionStateHasConsistentInstanceIds` — instanceId consistency
6. `phaseTransitionEmitsTwoDiffs` — phase flow
7. Combat tests (declareAttackers, declareBlockers, selectTargets)
8. Timer/zone/player info tests (fullStateHasTimers, zoneVisibilityMatchesRealArena, playerInfoHasTimerIds, embeddedActionsHaveActionIdAndSeatId, gameStartBundleGsIdsAscending)

## Section 5: Client Validation

After tests green:
1. `just serve` with proto dump
2. Connect Arena client
3. Play: mulligan → land → spell → combat → AI turn
4. Capture debug logs for next iteration

## Out of Scope

- ProtocolTest deck provider (1 disabled test — skip)
- Mulligan real state (templates sufficient for now)
- Per-seat filtering (PvP only)
- Mana payment prompts, modal choices, game over protocol
