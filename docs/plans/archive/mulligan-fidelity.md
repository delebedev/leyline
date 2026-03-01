# Mulligan Fidelity + Die Roll Randomization

## Context

Mulligan is partially wired — keep/mull works but: (1) `MulliganReq` proto never sets `mulliganCount`/`freeMulliganCount`, (2) after a mull the client doesn't see the re-dealt hand (seat 1 never gets a fresh DealHand GSM), (3) London tuck (GroupReq/GroupResp) is completely missing — player can't select cards to put on bottom. Die roll is hardcoded 18 vs 2 — should use random d20 values. Config `skip_mulligan` is wired correctly (commented out = false = mulligan runs).

## Changes

### 1. `MulliganReq` — add `mulliganCount` + `freeMulliganCount`

**File:** `forge-nexus/src/main/kotlin/forge/nexus/game/GsmBuilder.kt`

- Add `mulliganCount: Int = 0` param to `buildMulliganReq()`
- Set `.setMulliganCount(mulliganCount)` and `.setFreeMulliganCount(0)` on the proto

**File:** `forge-nexus/src/main/kotlin/forge/nexus/server/MatchHandler.kt`

- Pass `mulliganCount` through to `buildMulliganReq()` calls (both in `sendMulliganReq()` and `sendDealHandAndMulligan()`)
- `sendMulliganReq()` and related callers need access to `mulliganCount`

### 2. Fix re-deal hand visibility after mull

**File:** `forge-nexus/src/main/kotlin/forge/nexus/server/MatchHandler.kt`

Current mull branch (line 207-211) calls `sendDealHandAndMulligan(ctx)` which is seat 2's DealHand+MulliganReq combined sender. But `ctx` here is seat 1's channel. The issue: this sends DealHand to seat 1 but the DealHand GSM is built for `seatId=2` (because `dealHandMulliganSeat2` hardcodes seat 2).

Fix: After `submitMull()` + refreshing `seat1Hand`, send a proper seat-1 DealHand (via `sendDealHand(ctx)`) followed by a MulliganReq with updated `mulliganCount`. Replace `sendDealHandAndMulligan(ctx)` in the mull branch with:
```kotlin
sendDealHand(ctx)        // seat 1 gets re-dealt hand
sendMulliganReq()        // seat 1 gets new MulliganReq with mulliganCount
```

Also update `sendMulliganReq()` to accept/use the current `mulliganCount`.

### 3. London tuck — GroupReq/GroupResp

**File:** `forge-nexus/src/main/kotlin/forge/nexus/game/GameBridge.kt`

Add methods:
- `awaitTuckReady()` — poll until `seat1MulliganBridge.pendingPhase == WaitingTuck` (same pattern as `awaitMulliganReady()`)
- `submitTuck(seatId, cards: List<Card>)` — calls `seat1MulliganBridge.submitTuck(cards)`
- `getHandCards(seatId): List<Card>` — returns hand Card objects (for mapping instanceIds → Card)
- `getTuckCount(): Int` — returns `seat1MulliganBridge.pendingCardsToTuck`

**File:** `forge-nexus/src/main/kotlin/forge/nexus/game/GsmBuilder.kt`

Add `buildGroupReq()`:
```kotlin
fun buildGroupReq(
    msgId: Int, gameStateId: Int, seatId: Int,
    handInstanceIds: List<Int>, cardsToTuck: Int,
): GREToClientMessage
```
Proto shape: `GroupReq { instanceIds=[...], groupSpecs=[{lower=N, upper=N}], groupType=Arbitrary, context=LondonMulligan }`, wrapped in `GREMessageType.GroupReq_695e`, `promptId=92`.

**File:** `forge-nexus/src/main/kotlin/forge/nexus/game/mapper/PromptIds.kt`

Add: `const val GROUP_LONDON_MULLIGAN = 92`

**File:** `forge-nexus/src/main/kotlin/forge/nexus/server/MatchHandler.kt`

AcceptHand branch — after `submitKeep()`:
```kotlin
bridge?.submitKeep(seatId)
if (mulliganCount > 0) {
    bridge?.awaitTuckReady()
    sendGroupReq(ctx)       // new sender
} else {
    s?.onMulliganKeep()
}
```

New `GroupResp_097b` dispatch in `processGREMessage`:
- Extract `greMsg.groupResp.groups[0].ids` → instanceIds to put on bottom
- Map each instanceId → Forge Card via `bridge.getForgeCardId(iid)` + hand lookup
- Call `bridge.submitTuck(seatId, cards)`
- Then `bridge.awaitPriority()` + `s?.onMulliganKeep()`

New sender `sendGroupReq(ctx)`.

### 4. Die roll randomization

**File:** `forge-nexus/src/main/kotlin/forge/nexus/protocol/HandshakeMessages.kt`

`buildDieRollResults()` currently hardcodes 18/2. Change to:
- Generate two random d20 values (1..20), re-roll on tie
- Winner gets the higher roll, loser gets the lower
- Keep `winner` param for who goes first (from config) — the random values are cosmetic but should look real (winner > loser)

### 5. Familiar (seat 2) mulligan auto-accept

Currently seat 2's `MulliganResp` is ignored (line 202-203). This is fine — the AI handles mulligan through its own controller. No change needed.

## Files modified

| File | Change |
|------|--------|
| `forge-nexus/.../GsmBuilder.kt` | `mulliganCount` param on `buildMulliganReq()`, new `buildGroupReq()` |
| `forge-nexus/.../MatchHandler.kt` | GroupResp dispatch, tuck flow, mull re-deal fix, mulliganCount threading |
| `forge-nexus/.../GameBridge.kt` | `awaitTuckReady()`, `submitTuck()`, `getHandCards()`, `getTuckCount()` |
| `forge-nexus/.../HandshakeMessages.kt` | Random die roll values |
| `forge-nexus/.../PromptIds.kt` | `GROUP_LONDON_MULLIGAN = 92` |

## Verification

1. `just test-gate` — existing tests pass
2. `just serve` → connect client → verify:
   - Die roll shows random (non-fixed) values
   - Mulligan prompt appears, "Mulligan" button re-deals and shows new cards
   - After keep with mulliganCount > 0: GroupReq UI for bottom-card selection
   - After selecting cards: game starts normally
3. Check `/api/messages` for correct MulliganReq fields and GroupReq/GroupResp flow
