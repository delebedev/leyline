# Fix: Attacker Tap State (leyline-o2q)

## Premise verification

**Confirmed.** Integration test (`AttackerTapStateTest`) reproduces reliably: after SubmitAttackers, the post-submit GSM diff has the creature with `isTapped=true` but `attackState=None`. Real server sends `attackState=Attacking` + `TappedUntappedPermanent` annotation in a dedicated combat diff.

**Root cause:** No intermediate GSM is sent while combat is active on the human seat. After `submitAction(DeclareAttackers)` + `awaitPriority()`, the engine has already resolved combat and advanced to Main2. The first `buildDiffFromGame` call sees `combat == null` → `applyCombatState` never fires → no `attackState`.

**Why AI attacks work:** `GamePlayback` captures diffs synchronously on the engine thread mid-combat. Human attacks go through `onDeclareAttackers` which only builds a diff after `awaitPriority()` overshoots past combat.

## Approach

Insert an intermediate combat-state diff in `CombatHandler.onDeclareAttackers` after `submitAction` + `awaitPriority()` but **before** `autoPass(bridge)`.

At this point:
- Engine is at the next priority stop (COMBAT_DECLARE_ATTACKERS post-tap, or COMBAT_DECLARE_BLOCKERS)
- `combat.isAttacking(card)` is true → `applyCombatState` sets `attackState=Attacking`
- `card.isTapped` is true for non-vigilance → ObjectMapper picks it up
- `GameEventCardTapped` has fired → events queue has `CardTapped` → annotation pipeline emits `TappedUntappedPermanent`

Use existing `BundleBuilder.stateOnlyDiff()` — it's designed for exactly this: "show intermediate state without prompting the client for a response." It calls `buildDiffFromGame` (updates baseline correctly) and sends GSM without ActionsAvailableReq.

### Why stateOnlyDiff and not sendRealGameState

`sendRealGameState` = `postAction` = diff + ActionsAvailableReq. We don't want to offer actions here — the auto-pass loop handles that next. We just want the visual state update so the client sees tapped attackers + attack animation.

## Changes

### 1. CombatHandler.kt — insert intermediate diff (~3 lines)

After `bridge.awaitPriority()`, before `autoPass(bridge)`:

```kotlin
// Send intermediate combat-state diff: tapped creatures + attackState + annotations.
// Without this, autoPass overshoots past combat and the client never sees attackers tapped.
ops.sendBundle(ops.bundleBuilder!!.stateOnlyDiff(bridge.getGame()!!, ops.counter))
```

Need to check if `sendBundle` is available on `SessionOps`. If not, use `sendBundledGRE(result.messages)`.

### 2. AnnotationBuilder.kt — fix int32 vs uint32 detail type (~1 line)

`tappedUntappedPermanent()` uses `uint32Detail("tapped")`. Real server uses `int32Detail("tapped")`. Change to `int32Detail`.

### 3. AttackerTapStateTest.kt — clean up diagnostics, keep assertions

Remove println diagnostics. Keep the two key tests:
- `attackState=Attacking` + `isTapped=true` in post-submit diff
- `TappedUntappedPermanent` annotation present with correct `tapped=1` detail

## Deliverables

- [x] Code: intermediate diff in `CombatHandler.onDeclareAttackers`
- [x] Code: int32 detail type fix in `AnnotationBuilder`
- [x] Test: `AttackerTapStateTest` — reproduces failure, passes after fix
- [ ] Verification: `just test-gate` green (scoped to matchdoor)
- [ ] Doc: update bead leyline-o2q with findings + close

## Unknowns

1. **Does the engine always stop at DECLARE_ATTACKERS post-tap?** If AI has no blockers and no combat tricks, it may advance past DECLARE_BLOCKERS too. The intermediate diff should still capture combat state because `awaitPriority()` returns at the first priority stop after submit, which is before combat resolution clears the combat object. Need to verify in test.

2. **Double-diff concern.** The intermediate diff updates the baseline. The subsequent `autoPass → sendRealGameState` builds another diff against the new baseline. This is correct behavior (second diff shows combat resolution / Main2 state) but we should verify no duplicate annotations.

## Leverage

Medium. Fixes a visible gameplay bug (attackers don't appear tapped). Understanding the intermediate-diff pattern informs future combat-phase work (blockers visual state, damage animations).
