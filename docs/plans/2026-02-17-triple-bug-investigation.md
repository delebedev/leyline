# Triple Bug Investigation: Double Lands / Stuck Stack / Invisible AI

**Date:** 2026-02-17
**Status:** ROOT CAUSE FOUND — fix applied, verification pending
**Branch:** `feat/nexus-quality`

## Bugs

1. **Double lands on battlefield** — playing one land shows two copies on BF
2. **Elves stuck on stack** — casting a creature shows it on stack, never resolves to BF
3. **Sparky invisible** — AI opponent changes state but board appears empty during AI turns

User suspects these are related.

## Architecture Recap

```
Client Action → MatchSession.onPerformAction
                  → bridge.actionBridge.submitAction (engine executes)
                  → bridge.awaitPriority (blocks until next human priority)
                  → autoPassAndAdvance (loop: drain AI diffs, check combat/prompts, send state)
                    → sendRealGameState → BundleBuilder.postAction
                      → StateMapper.buildDiffFromGame (diff vs snapshot)
                        → StateMapper.buildFromGame (side effects: reallocInstanceId, retireToLimbo)
                        → bridge.snapshotState (seeds next diff baseline)
```

AI actions captured by `NexusGamePlayback` (EventBus subscriber on game thread):
```
GameEvent → captureAndPause → BundleBuilder.aiActionDiff → queue
MatchSession.autoPassAndAdvance drains queue → sendBundledGRE
```

## What's Been Verified

### State-building layer is correct

All 152 existing tests pass + 7 new diagnostic tests pass (`DiffDiagnosticTest.kt`):

- **Two consecutive land plays** → no duplicate BF objects in accumulated state
- **Diff structure** after land play → correct type (Diff), correct zones (Hand/BF/Limbo), correct annotations (ObjectIdChanged)
- **Cast creature + resolve** → creature ends up on BF in accumulated state, not stuck on stack
- **Resolve keeps instanceId** → no realloc on Stack→BF (matches real server)
- **AI action diff** → includes BF objects with valid zoneIds
- **Multiple postAction calls** → consistent accumulated state, no corruption
- **Full state between diffs** (declareAttackersBundle) → no duplicates after Full+Diff sequence

**Conclusion: BundleBuilder + StateMapper + ClientAccumulator produce correct state at the unit level.**

### Recording analysis confirms field-level fidelity

RecordingDecoder tests (from previous session) verify our StateMapper output matches real server recordings:
- ObjectIdChanged, ZoneTransfer categories, Limbo retirement, per-seat visibility
- Dual delivery pattern, annotation chains, persistent annotations

### buildFromGame side effects are idempotent on second call

`buildDiffFromGame` calls `buildFromGame` (line 549, side effects run), then `snapshotState` calls it again (line 596). The second call sees no zone transfers (previousZones already updated) → safe.

## What's Been Ruled Out

| Theory | Why ruled out |
|--------|-------------|
| Incorrect diff computation | ClientAccumulator tests pass — zones/objects accumulate correctly |
| Double realloc on resolve | Test confirms Resolve keeps instanceId (no realloc for Stack→BF) |
| Stale snapshot after Full sends | Test `fullStateBetweenDiffsNoCorruption` passes — declareAttackersBundle Full + subsequent Diff stays consistent |
| Zone overlap (card in two zones) | Forge engine guarantees one zone per card; `addSharedZoneCards` and `addPlayerZones` iterate disjoint zone types |
| mirrorToFamiliar double-send | Mirror only sends to peer (seat 2); original goes to seat 1 only |
| Concurrent sends | All MatchSession entry points use `synchronized(sessionLock)` |

## Remaining Hypotheses (Not Yet Tested)

### H1: gameStart Full + immediate autoPassAndAdvance diff causes double-send

In `onMulliganKeep`:
```kotlin
gameStart(...)         // sends Full state (GRE 3: zones + objects)
bridge.snapshotState() // seeds baseline
autoPassAndAdvance()   // immediately sends another diff via sendRealGameState
```

The auto-pass loop enters, checks `shouldAutoPass(actions)` → false (player has Play/Cast actions) → calls `sendRealGameState` → sends a Diff.

**Key question:** Does this second Diff contain BF zone/objects that the Full already sent? If yes, the client processes: Full (clears + replaces) then Diff (upserts). The Diff could re-add objects that are already present. Normally harmless (idempotent upsert), but:

- The Full uses `viewingSeatId=seatId` (filtered)
- The snapshot uses `viewingSeatId=0` (unfiltered)
- The Diff's `current` uses `viewingSeatId=0`
- If the unfiltered diff includes zone content that differs from the filtered Full... **potential mismatch**

This needs empirical verification: dump the Full state zone contents vs the first Diff zone contents.

### H2: Cast auto-resolve skips both "spell on stack" and "stack resolved" blocks

In `onPerformAction` after a Cast:
```kotlin
val isCast = true
val stackWasNonEmpty = game != null && !game.stack.isEmpty  // BEFORE action submitted
```

`stackWasNonEmpty` checks the stack **before** the cast. If stack was empty, `stackWasNonEmpty = false`.

After submit + awaitPriority, if the engine auto-resolved (both players pass):
- `isCast = true` but `!g.stack.isEmpty` → false (resolved)
- `stackWasNonEmpty = false` (was empty before cast)
- **Neither block fires!** Falls through to `autoPassAndAdvance`

The creature goes Hand→Stack→BF between snapshots. `buildFromGame` sees the net result (Hand→BF). `inferCategory` returns **"PlayLand"** (not "CastSpell" + "Resolve"). The client might handle "PlayLand" annotations for a creature incorrectly.

**But:** The creature object still has zoneId=BF. The client should place it there regardless of annotations. Unless the client has category-specific rendering logic that interferes.

**More critically:** The intermediate Stack state is never shown to the client. If the client expects to see Cast→Stack→Resolve→BF (the real server always shows this), skipping the Stack step might confuse client rendering logic.

### H3: AI diffs queued but stale (snapshot drift)

`NexusGamePlayback.captureAndPause` runs on the game thread:
```kotlin
val result = BundleBuilder.aiActionDiff(game, bridge, matchId, seatId, msgIdCounter.get(), gsIdCounter.get())
bridge.snapshotState(game)
queue.add(result.messages)
```

Then `autoPassAndAdvance` drains the queue later (on the Netty thread):
```kotlin
val batches = playback.drainQueue()
for (batch in batches) { sendBundledGRE(batch) }
bridge.snapshotState(game)  // <-- updates snapshot AFTER sending all batches
```

**Issue:** The `bridge.snapshotState(game)` in `autoPassAndAdvance` is called AFTER all batches are sent. But `captureAndPause` already called `snapshotState` after each capture. So the final `snapshotState` in autoPassAndAdvance snapshots the SAME state as the last `captureAndPause` → redundant but safe.

**But:** Between the last `captureAndPause` (on game thread) and `autoPassAndAdvance` drain (on Netty thread), the game state might have advanced further (engine continued after AI's last event). The `snapshotState` in autoPassAndAdvance uses the CURRENT game state (which may be ahead of what the queued diffs captured). This could cause the next human-action diff to miss changes that happened between the last AI capture and the drain point.

### H4: viewingSeatId=0 in snapshot vs seatId in sends

A persistent asymmetry throughout the codebase:

| Call site | viewingSeatId |
|-----------|---------------|
| `gameStart` Full state | `seatId` (=1) |
| `snapshotState` | 0 (default) |
| `buildDiffFromGame` internal `buildFromGame` | 0 |
| `aiActionDiff` → `buildDiffFromGame` | `seatId` (=1) |
| `postAction` → `buildDiffFromGame` | `seatId` (=1) |

The snapshot always includes ALL objects (both hands). The diff computation compares snapshot (viewingSeatId=0) vs current (viewingSeatId=0), then filters changedObjects by opponent hand. Zone content is NOT filtered — if the opponent Hand zone changes, it appears in the diff.

**Potential issue for AI turns:** When Sparky draws a card, the opponent's Hand zone changes. The diff includes the Hand zone update. But since Sparky's hand objects aren't included (filtered), the client gets a zone with instanceIds but no matching objects. The client might handle this inconsistency poorly.

### H5: edictalPass messages interfere with client state

In `autoPassAndAdvance`, when auto-passing:
```kotlin
val edictal = BundleBuilder.edictalPass(seatId, msgIdCounter, gameStateId)
sendBundledGRE(edictal.messages)
```

Edictal messages tell the client "server forced a pass". They don't contain game state. But the client might interpret them as a state checkpoint, affecting how it processes subsequent diffs.

## Files to Investigate

| File | Why |
|------|-----|
| `MatchSession.kt:334-476` | `autoPassAndAdvance` loop — the orchestration core |
| `MatchSession.kt:91-182` | `onPerformAction` — cast flow with isCast/stackWasNonEmpty |
| `NexusGamePlayback.kt:121-158` | `captureAndPause` — AI diff capture and snapshot timing |
| `StateMapper.kt:534-598` | `buildDiffFromGame` — diff computation + snapshot update |
| `StateMapper.kt:382-452` | Annotation loop in `buildFromGame` — zone transfer detection + side effects |
| `StateMapper.kt:1376` | `inferCategory` — Hand→BF="PlayLand", Hand→Stack="CastSpell", Stack→BF="Resolve" |
| `BundleBuilder.kt:100-131` | `postAction` — diff + actions bundle |
| `BundleBuilder.kt:169-223` | `aiActionDiff` — AI action diff with double-diff pattern |

## Root Cause Found

**The bugs were caused by malformed annotations, not state data.**

Server-side game state was always correct (zones, objects, zone memberships). The client threw exceptions while parsing annotations and corrupted its rendering pipeline.

### Evidence: Client Player.log errors

1. **gsId=9 (cast creature):** `TappedUntappedPermanentAnnotationParser` — "Annotation contains no details"
   - Our `TappedUntappedPermanent` had empty `details` and `affectorId=0`
   - Real server sends `details: {tapped: 1}` and `affectorId = mana_ability_instanceId`

2. **gsId=10 (resolve):** `ResolutionStartAnnotationParser` — "Annotation does not contain affector id."
   - Our `ResolutionStart` had `affectorId=0`
   - Real server sends `affectorId = resolving_spell_instanceId`

### Fix: `AnnotationBuilder.kt`

- `resolutionStart`: added `.setAffectorId(instanceId)`
- `resolutionComplete`: added `.setAffectorId(instanceId)`
- `tappedUntappedPermanent`: added `affectorId`, `details: {tapped: 1}`, changed signature to take `(permanentId, abilityId)`
- `zoneTransfer`: added optional `actingSeatId` param for Resolve category

### Why All Three Bugs Shared One Root Cause

The client's annotation parser throws exceptions when encountering malformed annotations. These exceptions corrupt the client's game state processing for that message. Since cast/resolve is required for most game actions, the corruption cascades:
- **Double lands**: Annotation exception during gsId=9 (cast after land play) may cause the client to re-render previously committed state
- **Stuck stack**: ResolutionStart exception at gsId=10 prevents the client from processing Stack→BF move
- **Invisible AI**: Same annotation defects in AI action diffs

## Key Insight

The unit-level state building is correct (proven by tests). The bug was in **annotation field completeness** — missing `affectorId` and `details` fields that the client's parsers require but don't gracefully handle.
