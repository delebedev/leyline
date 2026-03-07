# 03 — FD Match Routing

## Goal

Extend FrontDoorService to support two match entry paths:

- **Familiar (1vAI)** — existing `Event_AiBotMatch` (612) flow. `MatchType=Familiar`. Client opens two MD connections (human + Familiar). No change.
- **Queue (PvP)** — new `Event_EnterPairing` (603) flow. `MatchType=Queue`. Server holds first connection, pairs with second, pushes `MatchCreated` to both FD channels with different `YourSeat` values. Each player opens one MD connection.

MVP: naive FIFO pairing (first two 603 requests get matched). Direct Challenge is out of scope.

## Current FD Stub

`FrontDoorService` is a stateless `ChannelInboundHandlerAdapter`. Each client connection gets its own handler instance (Netty creates per-channel). No shared state between connections.

Current match trigger:
```
612 (Event_AiBotMatch) -> sendEmptyResponse(txId)
                       -> sendMatchCreated(ctx)
```

`sendMatchCreated()` generates a random matchId, builds JSON with `MatchType=Familiar`, `YourSeat=1`, two PlayerInfos (ForgePlayer + Sparky). Sends as push (fresh txId that doesn't match any pending promise).

`FdEnvelope.buildMatchCreatedJson()` is hardcoded to Familiar: `MatchType="Familiar"`, `MatchTypeInternal=1`, `YourSeat=1`, fixed player names.

No connection tracking, no shared state between FD handler instances — each handler is fire-and-forget.

## Queue Path Design

### Shared state: MatchmakingQueue

New `MatchmakingQueue` object (or singleton held by `LeylineServer`, passed to `FrontDoorService` constructor). Holds the pairing state that must be shared across FD connections.

```
MatchmakingQueue
  waitingPlayer: WaitingPlayer?   (atomic ref)
  pair(player: QueueEntry): PairResult  (Paired | Waiting)

QueueEntry
  ctx: ChannelHandlerContext      (FD channel for push)
  txId: String                    (603 request txId for ack)
  screenName: String
  deckId: String                  (from 603 payload, logged only)

PairResult
  Waiting                         (first player, stored)
  Paired(seat1: QueueEntry, seat2: QueueEntry, matchId: String)
```

### Protocol flow

**Player A sends 603:**
1. FD acks 603 with empty response (matches real server — Promise resolves, spinner shows)
2. `queue.pair(entryA)` returns `Waiting` — entryA stored
3. No further action. TCP keepalive keeps connection alive. No heartbeat needed (real server doesn't send one either).

**Player B sends 603:**
1. FD acks 603 with empty response
2. `queue.pair(entryB)` returns `Paired(seat1=entryA, seat2=entryB, matchId=<uuid>)`
3. Push `MatchCreated` to entryA.ctx with `YourSeat=1, MatchType=Queue`
4. Push `MatchCreated` to entryB.ctx with `YourSeat=2, MatchType=Queue`
5. Both players receive push, open independent MD connections with their assigned seat

**Player cancels (606 Event_LeavePairing):**
1. If player is in `waitingPlayer` slot, clear it
2. Ack 606

### MatchCreated changes

`FdEnvelope.buildMatchCreatedJson()` needs parameterization:

- `matchType: String` — "Familiar" or "Queue"
- `matchTypeInternal: Int` — 1 or 0
- `yourSeat: Int` — 1 or 2
- `playerInfos: List<PlayerInfo>` — seat/team/name for each player
- `eventId: String` — "AIBotMatch" or "PlayQueue"

The current hardcoded version becomes a convenience wrapper for the Familiar case.

### FrontDoorService dispatch additions

```
603 -> handleEnterPairing(ctx, txId, json)
606 -> handleLeavePairing(ctx, txId)
```

`FrontDoorService` receives `MatchmakingQueue` via constructor (same pattern as `matchDoorHost`/`matchDoorPort`).

### Thread safety

`MatchmakingQueue.pair()` must be thread-safe — two FD connections arrive on different Netty I/O threads. `AtomicReference<QueueEntry?>` with `compareAndSet` for the waiting slot. Pairing is a single CAS: try to swap null in; if slot was occupied, you got a pair; if empty, you're now waiting.

Actually simpler: `synchronized` block is fine for MVP. Two concurrent pairings is the max contention. No hot path.

### What creates the Match on MD side?

FD just does pairing + push. The actual `Match` (plan 01) is created when the first MD `ConnectReq` arrives, same as today. For Queue matches:
- First `ConnectReq` creates Match in WAITING state
- Second `ConnectReq` finds existing Match, registers seat 2, transitions to RUNNING
- Both seats get independent initial bundles (ConnectResp + DieRoll + Full GSM)

This MD-side logic is plan 04's concern. FD's only job: generate matchId, assign seats, push MatchCreated with `MatchType=Queue`.

## What About Direct Challenge

Direct Challenge uses CmdTypes 3000-3012 (lobby protocol): create challenge, invite, join, ready, countdown, then MatchCreated push. It's a mini state machine per challenge room.

**Not MVP.** Queue pairing covers the core PvP flow. Direct Challenge adds:
- Challenge room state tracking (per invite code / challenge ID)
- Deck selection after join (ChallengeReady)
- Countdown timer (ChallengeStartLaunchCountdown)
- Notification push to opponent (ChallengeNotification, CmdType 19000)

These are all FD-layer concerns with no engine impact. Can be layered on top of Queue infrastructure later — same `MatchCreated` push at the end, same MD flow.

Current stub already handles `3006` (ChallengeReconnectAll) with empty proto response. Adding challenge support means implementing 3001/3004/3000/3008/3012 handlers and a `ChallengeRoom` state object.

## Migration Path

**Phase 1: Parameterize MatchCreated.** Refactor `buildMatchCreatedJson()` to accept matchType, yourSeat, playerInfos. Existing 612 path passes Familiar defaults. No behavior change.

**Phase 2: MatchmakingQueue.** Introduce `MatchmakingQueue` class. Wire into `FrontDoorService` constructor (default: new instance). Add 603/606 dispatch. Add `CMD_TYPE_NAMES` entries for 603/606.

**Phase 3: Integration test.** Two FD connections, both send 603, verify both receive MatchCreated with correct seats and `MatchType=Queue`. Verify cancel (606) clears waiting slot.

## What Doesn't Change

- **612 (Event_AiBotMatch)** — still works exactly as today (Familiar path)
- **FrontDoorReplayStub** — untouched (replay mode doesn't need pairing)
- **Match Door** — no FD changes affect MD; MD changes are plan 04
- **All other FD stub responses** — auth, golden data, lobby endpoints unchanged
- **FdEnvelope framing** — same wire format, same Response envelope for pushes
- **Single-player dev workflow** — `just serve` still works with 612 → Familiar
