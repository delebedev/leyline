# Wire Full London Mulligan (GroupReq tuck)

## Context

Current nexus mulligan handles keep/mull but skips the tuck step. After a London mulligan, the player keeps a 7-card hand and must put N cards on bottom of library — the real server sends a `GroupReq` (promptId=92, context=LondonMulligan) for this. We skip it, so after mulligan the client sees the same cards (no re-deal update, no bottom-card selection).

forge-web already has the full pipeline: `MulliganBridge.awaitTuckDecision()` / `submitTuck()` blocks the engine on tuck. We just need to wire the nexus protocol layer.

## Real server sequence (from recording 2026-02-28_14-15-29)

```
S→C: MulliganReq (gsId=2, promptId=34)
C→S: MulliganResp (Mulligan)
S→C: GSM Diff (re-dealt hand — new 7 cards visible)
S→C: MulliganReq (gsId=4, promptId=34)    ← second chance to keep/mull
C→S: MulliganResp (AcceptHand)
S→C: GroupReq (gsId=5, promptId=92, context=LondonMulligan, instanceIds=[hand cards])
C→S: GroupResp (groups=[{ids: cards to put on bottom}])
S→C: GSM Diff (hand updated — tuck cards removed)
```

## Recording observations

**Session `2026-03-01_11-33-28` — 4 mulligan rounds (gsId 2–7):**

- Hands delivered as zone objects (cards visible to the seat owner)
- Zone `objectInstanceIds` arrays **stay empty** throughout mulligan — cards have no zone membership until the game starts
- gsId 2: AI opponent's 7-card hand (zone 35)
- gsId 5: Player's 7-card hand after first mull
- gsId 6: Player's next 11-object hand (Rooms contribute face objects)
- gsId 8: First game action (draw step) — zone membership populated from here on

Implication: during mulligan, the client renders hand objects purely from the object list, not from zone contents. The re-deal GSM diff should deliver new objects but doesn't need to update zone `objectInstanceIds` until the game proper starts.

## Changes

### 1. `GameBridge.kt` — Add tuck support

- `awaitTuckReady()` — poll until `seat1MulliganBridge.pendingPhase == WaitingTuck` (same pattern as `awaitMulliganReady()`)
- `submitTuck(seatId, cards: List<Card>)` — calls `seat1MulliganBridge.submitTuck(cards)`, then `awaitPriority()`
- `getHandInstanceIds(seatId)` — returns current hand as instanceIds (for GroupReq)
- `getTuckCount()` — returns `seat1MulliganBridge.pendingCardsToTuck`

### 2. `HandshakeMessages.kt` — Build GroupReq message

New `buildGroupReq()`:

```kotlin
fun buildGroupReq(
    msgId: Int, gameStateId: Int, seatId: Int,
    handInstanceIds: List<Int>, cardsToTuck: Int,
): GREToClientMessage
```

Proto shape:
```
GroupReq {
    instanceIds = [hand card instanceIds]
    groupSpecs = [{ lowerBound=cardsToTuck, upperBound=cardsToTuck }]
    context = LondonMulligan
}
```

Wrapped in GREToClientMessage with `type=GroupReq_695e`, `promptId=92`.

### 3. `MatchHandler.kt` — Wire keep→tuck→game + GroupResp handler

**AcceptHand branch** — after `submitKeep()`, if `mulliganCount > 0`:
```kotlin
bridge?.submitKeep(seatId)
if (mulliganCount > 0) {
    bridge?.awaitTuckReady()
    sendGroupReq(ctx)
} else {
    s?.onMulliganKeep()
}
```

**New `GroupResp_097b` dispatch:**
- Extract `GroupResp.groups[0].ids` → instanceIds of cards to put on bottom
- Map instanceIds → Forge Card objects via `bridge.getForgeCardId()` + player hand lookup
- Call `bridge.submitTuck(seatId, cards)` → proceed to `session.onMulliganKeep()`

### 4. Fix re-deal hand visibility (mull branch)

Current mull branch (line 202-206) sends DealHand to seat 2 but never updates seat 1's visible hand. After `submitMull()`:
```kotlin
bridge?.submitMull(seatId)
seat1Hand = bridge?.getHandGrpIds(1) ?: emptyList()
sendDealHand(ctx)     // seat 1 gets re-dealt hand
sendMulliganReq()     // seat 1 gets new MulliganReq
```

## Files to modify

| File | Change |
|------|--------|
| `server/MatchHandler.kt` | GroupResp handler, keep→tuck flow, mull re-deal fix |
| `game/GameBridge.kt` | `awaitTuckReady()`, `submitTuck()`, `getHandInstanceIds()`, `getTuckCount()` |
| `protocol/HandshakeMessages.kt` | `buildGroupReq()` factory |

## Verification

1. `just test-gate` — unit + conformance pass
2. `just serve` → connect client → mulligan → verify:
   - After mull: see new 7 cards (not same cards)
   - After keep (with mulliganCount > 0): GroupReq UI for bottom-card selection
   - After selecting cards to bottom: game starts normally
3. Debug API `/api/messages` shows GroupReq/GroupResp in flow

## Effort: M

~3 files, ~80 lines. Main risk: timing between `submitKeep` → engine calls `tuckCardsViaMulligan` (blocks on tuck bridge) → detect tuck-ready before sending GroupReq. The `awaitTuckReady()` poll handles this.
