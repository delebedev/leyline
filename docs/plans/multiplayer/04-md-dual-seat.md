# MD Dual-Seat Handling

## Goal

Two real human clients connect to Match Door, each with their own MatchHandler + MatchSession. Both seats get full GRE message handling, independent mulligan sequences, per-seat visibility filtering, and per-seat action dispatch. Familiar mirroring stays for 1vAI path — PvP path uses symmetric sessions instead.

## Current State

### MatchHandler — seat 2 is passive

MatchHandler creates one session per connection (line 109). But action dispatch (lines 258-303) gates on `seatId == 1`:

- `PerformActionResp`: only `seatId == 1`
- `DeclareAttackersResp`: only `seatId == 1`
- `DeclareBlockersResp`: only `seatId == 1`
- `SelectTargetsResp`: only `seatId == 1`
- `CancelActionReq`: only `seatId == 1`
- `SelectNResp`: only `seatId == 1`

Seat 2 actions are silently dropped with a debug log. This was correct for Familiar (mirror-only spectator) but blocks a second human.

### MatchHandler.processGREMessage — cross-seat coordination

`ChooseStartingPlayerResp` (lines 170-193) does cross-seat work:
- Finds seat 1's handler via `registry.getHandler(matchId, 1)`
- Sends DealHand to seat 1, then calls `seat1Handler.session.onMulliganKeep()`

This assumes seat 2 responds to ChooseStartingPlayer (die roll winner). For PvP, the die roll winner varies.

### HandshakeMessages — hardcoded seat assumptions

- `roomState()`: hardcodes seat 2 as `_Familiar` with name "Sparky" (line 24).
- `buildRoomConfig()`: seat 2 = `isBotPlayer=true`, eventId="AIBotMatch" (lines 85-89).
- `initialBundle()`: seat 1 gets ConnectResp, seat 2 gets ChooseStartingPlayerReq (lines 118-154). PvP: ConnectResp goes to die roll loser, ChooseStartingPlayerReq to winner (per `multiplayer-connection-flow.md`).
- `dealHandMulliganSeat2()`: hardcoded to seatId=2 (line 186).
- `mulliganReqSeat1()`: hardcoded seat 1 label, references seat 2 player info (line 216).
- `groupReqBundle()`: references `bridge.getPlayer(1)` at line 285.

### MatchSession.mirrorToFamiliar — one-directional

Lines 554-573: seat 1 mirrors to seat 2 after every `sendBundledGRE`. Applies visibility filtering (strips Private objects not visible to mirror seat). One-directional: seat 2 never mirrors back.

### MatchSession.sendGameOver — hardcoded winner check

Line 487: `bridge?.getPlayer(1)` to check if human won. With two humans, need to check by seatId, not by role.

### Per-seat GSM visibility

Already works — `StateMapper.buildFromGame` and `buildDiffFromGame` take `viewingSeatId` parameter. Opponent hand cards get instanceIds but no GameObjectInfo (renders face-down). `ZoneMapper.opponentHandZone(viewingSeatId)` correctly returns the right zone.

### MessageCounter — shared

Both sessions share one MessageCounter via `connectBridge()`. Session 2 syncs up to session 1's counter state (lines 76-88). This is correct for PvP too — gsId chain must be monotonic per match.

## Dual-Seat Design

### Connection model

Each human client gets:
1. One Netty channel → one MatchHandler → one MatchSession
2. MatchSession has its own `seatId`, `sink`, `recorder`
3. Both sessions share the same GameBridge (via MatchRegistry)
4. Both sessions share the same MessageCounter (via `connectBridge()`)

```
Client A ──TLS──> MatchHandler(seat=1) ──> MatchSession(seat=1) ──┐
                                                                   ├── GameBridge (shared)
Client B ──TLS──> MatchHandler(seat=2) ──> MatchSession(seat=2) ──┘
```

### Remove seat 1 gates

All six action handlers in MatchHandler.processGREMessage become unconditional — they delegate to `session?.onPerformAction(greMsg)` regardless of seatId. The session type determines whether the call does anything (see Familiar Coexistence below).

Each session's seatId determines which bridge to use. Per-seat bridge refactor (doc 02) provides `bridge.actionBridge(seatId)`.

### Die roll and initial bundle — parameterize on winner

`initialBundle()` currently keys on seatId (1=loser, 2=winner). Refactor to key on relationship to die roll winner:

```kotlin
fun initialBundle(
    seatId: Int,
    dieRollWinner: Int,  // already exists
    ...
): Pair<MatchServiceToClientMessage, Int>
```

- `seatId == dieRollWinner`: DieRoll + GSM + ChooseStartingPlayerReq
- `seatId != dieRollWinner`: ConnectResp + DieRoll + GSM

The existing `dieRollWinner` parameter already supports this — just replace `if (seatId == 1)` / `if (seatId == 2)` with `if (seatId != dieRollWinner)` / `if (seatId == dieRollWinner)`.

### ChooseStartingPlayerResp — symmetric handling

Currently MatchHandler assumes seat 2 always responds. For PvP:

1. Die roll winner's handler receives `ChooseStartingPlayerResp`.
2. That handler triggers DealHand + MulliganReq for **both** seats via registry cross-lookup.
3. Generalize: `registry.getHandler(matchId, otherSeat)` where `otherSeat = 3 - seatId`.

### Mulligan — independent per-seat

Each seat has its own mulligan bridge (from doc 02). Mulligan flow:

1. Both seats get `DealHand` + `MulliganReq` simultaneously.
2. Each seat responds independently (`MulliganResp`).
3. Each handler manages its own mulligan count and tuck.
4. **Readiness gate:** game loop waits for ALL human seats to keep before entering priority. `GameBridge.awaitAllMulligans()` polls all mulligan bridges.

The last seat to keep triggers `onMulliganKeep()` on seat 1's session (or whichever is designated as the "driver" session — the one that enters the auto-pass loop).

### Per-seat state delivery

Each session calls `BundleBuilder.postAction(game, bridge, matchId, seatId, counter)` — seatId is already parameterized. The GSM is built with `viewingSeatId=seatId`, so each client sees:
- Their own hand (full card info)
- Opponent's hand (instanceIds only, face-down)
- Shared zones (battlefield, stack, exile) — identical view

No new work needed — visibility filtering is already correct.

### Replace mirroring with per-seat sending

**PvP path:** after each game state change, both sessions independently build and send their own GSM:

```kotlin
// In AutoPassEngine or wherever sendRealGameState is called:
for (session in registry.getSessions(matchId)) {
    session.sendRealGameState(bridge)
}
```

Each session builds the GSM for its own seatId (different visibility). No mirroring needed.

**1vAI path:** seat 1 session still mirrors to Familiar via `mirrorToFamiliar()`. Unchanged.

Decision logic: `MatchSession.mirrorToFamiliar()` checks if the peer is a Familiar (via MatchRegistry metadata). If peer is a real human session, skip mirroring — the peer builds its own state.

### sendGameOver — parameterize winner check

Replace:
```kotlin
val humanPlayer = bridge?.getPlayer(1)
val humanWon = humanPlayer?.getOutcome()?.hasWon() ?: false
```

With:
```kotlin
val myPlayer = bridge?.getPlayer(seatId)
val iWon = myPlayer?.getOutcome()?.hasWon() ?: false
val winningTeam = if (iWon) seatId else (3 - seatId)
```

Both sessions send game-over to their respective clients. Room state `MatchCompleted` sent once (by the driver session or via a `sendOnce` guard).

### HandshakeMessages.roomState — PvP variant

New overload for PvP:

```kotlin
fun roomState(matchId: String, players: List<PlayerSlot>)
```

Where `PlayerSlot` = `(userId, name, seatId, teamId)`. No `_Familiar` suffix, no `isBotPlayer`. eventId = the actual event (ranked, direct challenge).

### HandshakeMessages — generalize seat-specific functions

- `dealHandMulliganSeat2()` → `dealHandMulligan(seatId: Int, ...)`. Remove "Seat2" suffix.
- `mulliganReqSeat1()` → `mulliganReq(seatId: Int, ...)`. Remove "Seat1" suffix.
- `groupReqBundle()` line 285: `bridge.getPlayer(1)` → `bridge.getPlayer(seatId)`.

### SessionLock — per-match, not per-session

Currently each MatchSession has its own `sessionLock`. With two sessions sharing a GameBridge, concurrent `onPerformAction` calls from both clients could race on bridge state.

Option A: sessions synchronize on the GameBridge (bridge-level lock). Both sessions block on the same lock. Simple, correct, serializes all game actions.

Option B: sessions keep independent locks. Bridge operations are thread-safe (CompletableFuture is thread-safe, InstanceIdRegistry uses ConcurrentHashMap). Only `DiffSnapshotter.snapshotState()` needs protection — add bridge-level sync just for snapshot.

Recommend Option A: bridge-level lock. Simpler, no concurrent mutation of shared state. Priority stops are inherently sequential (engine blocks until one player responds).

## Familiar Coexistence

Familiar = seat 2 client with `ClientType.Familiar` in real Arena. Used for 1vAI (bot match) to show opponent's perspective.

### Detection

MatchHandler already detects Familiar via `clientId.endsWith("_Familiar")` (line 110). Use this:

```kotlin
val isFamiliar = clientId.endsWith("_Familiar")
```

### Read-only session type — not a gate check

The Familiar connection is a display-only tap. Rather than adding `if (isFamiliar) return` guards inside MatchHandler's action dispatch (fragile, spreads the concern), create a distinct session type:

**FamiliarSession** — a lightweight read-only implementation of SessionOps. It:
- Holds a sink for receiving mirrored messages
- Has no auto-pass loop, no action dispatch, no bridge consumption
- Ignores all `onPerformAction`, `onDeclareAttackers`, etc. calls (no-ops or not even wired)
- Receives messages only via `mirrorToFamiliar()` from the human session

**MatchHandler** creates the right session type at connection time:

```kotlin
val session = if (isFamiliar) {
    FamiliarSession(seatId, matchId, sink)
} else {
    MatchSession(seatId, matchId, sink, registry, ...)
}
```

MatchHandler.processGREMessage unconditionally delegates to `session?.onPerformAction(greMsg)` — FamiliarSession's no-op implementation handles it. No `seatId == 1` checks, no `isFamiliar` checks in the dispatch path. The type system enforces the constraint.

This is cleaner than gate checks because:
1. Familiar behavior is defined in one place (FamiliarSession), not scattered across handlers
2. No risk of forgetting a gate in a new handler
3. MatchSession stays focused on game logic — no Familiar awareness

### MatchRegistry metadata

MatchRegistry tracks session type for mirror routing:

```kotlin
registry.registerSession(matchId, seatId, session)
```

`mirrorToFamiliar()` checks: `registry.getPeer()` returns a FamiliarSession (type check) or uses a `sessionType` enum. Mirror only targets FamiliarSession peers. PvP peers (MatchSession) build their own state.

### Mode matrix

| Match type | Seat 1 | Seat 2 | Mirror? | Both drive? |
|-----------|--------|--------|---------|-------------|
| 1vAI (current) | Human session | Familiar session | Yes (1→2) | No (seat 1 only) |
| PvP (new) | Human session | Human session | No | Yes |
| Puzzle | Human session | Familiar session | Yes (1→2) | No |

## Migration Path

### Phase 1: Generalize HandshakeMessages (backward compatible)

1. Rename `dealHandMulliganSeat2` → `dealHandMulligan(seatId)`.
2. Rename `mulliganReqSeat1` → `mulliganReq(seatId)`.
3. Parameterize `initialBundle` on die roll winner (use existing param, change conditionals).
4. Fix `groupReqBundle` line 285: `getPlayer(seatId)` instead of `getPlayer(1)`.
5. Add PvP `roomState` overload alongside existing one.
6. All existing callers updated to pass their seatId. No behavior change for 1vAI.

### Phase 2: Remove seat 1 gates + FamiliarSession type (requires doc 02)

1. Extract `FamiliarSession` implementing SessionOps with no-op action handlers.
2. MatchHandler creates `FamiliarSession` or `MatchSession` based on `clientId` suffix.
3. Remove `if (seatId == 1)` checks from MatchHandler action dispatch — unconditional delegation.
4. MatchSession.sendGameOver: use `seatId` instead of hardcoded 1.

### Phase 3: Symmetric auto-pass + state delivery

1. `mirrorToFamiliar()`: check `registry.isFamiliar()` before mirroring. PvP skips.
2. After each game state change, notify both sessions (for PvP). Each builds own GSM.
3. Auto-pass loop: both sessions run independently. Engine thread blocks until the active priority holder's bridge is completed. Non-active seat is idle (waiting for priority to rotate).

### Phase 4: Dual mulligan

1. Both seats receive independent DealHand + MulliganReq.
2. Each handler manages its own mulligan bridge (from doc 02).
3. Readiness gate: `awaitAllMulligans()` waits for all human bridges to resolve.
4. Last-to-keep triggers game start on both sessions.

## What Doesn't Change

- **Per-seat GSM visibility** — already correct via `viewingSeatId` parameter.
- **MessageCounter** — shared per match. Both sessions sharing it is already the design (from connectBridge).
- **AutoPassEngine** — operates on session's own seatId. Seat-parameterized by construction.
- **CombatHandler / TargetingHandler** — receive bridge + session. Use session.seatId. Already correct.
- **BundleBuilder** — all methods take seatId as parameter. Already correct.
- **AnnotationBuilder** — pure functions taking seat IDs as ints. No bridge dependency.
- **MatchRegistry** — already keyed by `(matchId, seatId)`. Supports N sessions per match.
- **FrameCodec / TLS pipeline** — transport-level. One per connection. No changes.
- **DebugServer / DebugCollector** — observability. May need UI updates to show both seats but no architectural changes.
- **SessionRecorder** — one per session. Both record independently.
