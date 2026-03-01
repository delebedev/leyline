# ChooseStartingPlayerReq: Play/Draw Choice Implementation Scope

## What we captured

Session `2026-03-01_00-18-46`, gsId=1, file `000000093_MD_S-C_MATCH_DATA.bin`.

Server sends a multi-message bundle at game start (all in one `MatchServiceToClientMessage`):

1. **ConnectResp** (msgId 2) — settings, protocol version
2. **DieRollResultsResp** (msgId 3) — both players' die rolls
3. **Full GameStateMessage** (msgId 4, gsId 1) — initial zones, pendingMessageCount=1
4. **ChooseStartingPlayerReq** (msgId 5, gsId 1) — play/draw choice

Player rolled 20 vs opponent's 10 → winner chooses.

## Proto shape — S→C

```
greToClientMessages {
  type: ChooseStartingPlayerReq_695e
  systemSeatIds: 1
  msgId: 5
  gameStateId: 1
  chooseStartingPlayerReq {
    teamType: Individual
    systemSeatIds: 1       ← choosable seats
    systemSeatIds: 2
  }
}
```

No `prompt` field set (field 4 exists in proto but empty in capture).

The preceding Full GSM has:
- `pendingMessageCount: 1` — tells client another message follows
- `pendingMessageType: ChooseStartingPlayerResp_097b` — expected response type

Seat allocation: seat 1 gets ConnectResp + DieRoll + GSM, seat 2 gets DieRoll + GSM + ChooseStartingPlayerReq (only die roll winner sees the req).

## Proto shape — C→S

File `000000098_MD_C-S_DATA.bin`. Wrapper is `ClientToMatchServiceMessage` with
`clientToMatchServiceMessageType: ClientToGremessage`, payload is `ClientToGREMessage`:

```
type: ChooseStartingPlayerResp_097b
gameStateId: 1
respId: 5
chooseStartingPlayerResp {
  teamType: Individual
  systemSeatId: 1          ← chosen seat (play first)
  teamId: 1
}
```

`respId` matches `msgId` of the req (5). `systemSeatId` = the seat that will go first.

## Timing and game flow

```
Die roll → winner determined
  ↓
Server sends ChooseStartingPlayerReq to winner
  ↓
Client responds with ChooseStartingPlayerResp (chosen systemSeatId)
  ↓
Server begins mulligan phase (MulliganReq to both seats)
```

In Bo1, the client auto-responds immediately (no UI prompt). Some sessions have
the req but no resp captured (or vice versa) — likely a timing artifact of when
proxy capture starts/stops, or the auto-response fires before the proxy logs it.

## What we already have

- **`HandshakeMessages.kt`** — already builds the ChooseStartingPlayerReq in `initialBundle()`.
  Hardcodes `teamType: Individual`, `systemSeatIds: [1, 2]`. Only sends to seat 2
  (our implementation always makes seat 2 the "winner").
- **`MatchHandler.kt:169`** — already receives `ChooseStartingPlayerResp_097b`, but
  ignores it for puzzles (`"ignoring ChooseStartingPlayerResp for puzzle"`).
- **`GsmBuilder.kt:150`** — sets `pendingMessageType: ChooseStartingPlayerResp_097b`
  on the Full GSM for seat 2.
- **`SmokeTest.kt:229`** — sends a synthetic ChooseStartingPlayerResp in smoke tests.
- **`WebPlayerController.kt:920`** — `chooseStartingPlayer()` auto-chooses self.

## What we need to build

### 1. Wire the actual response — `MatchHandler`

Currently the resp is silently ignored. For non-puzzle modes, extract `systemSeatId`
from the response and submit it through the prompt bridge so the engine knows who
goes first. Today we hardcode "self goes first" in `WebPlayerController` — the
response should override this.

### 2. Die roll fidelity — `HandshakeMessages`

Currently we skip the die roll entirely and always make seat 2 the chooser.
To match Arena fidelity:
- Generate random die rolls (or deterministic for testing)
- Send `DieRollResultsResp` with both players' rolls
- Send `ChooseStartingPlayerReq` only to the winner

### 3. Decoder support

`RecordingDecoder.decodeGRE` doesn't extract `chooseStartingPlayerReq` fields —
it just shows the `greType` name. Add:
- `hasChooseStartingPlayerReq: Boolean` field to `DecodedMessage`
- Or a richer summary (teamType, systemSeatIds)

`decodeClientMessage` doesn't handle `ChooseStartingPlayerResp` — see
`docs/plans/2026-03-01-cs-decoding-scope.md`.

### 4. Golden test

Copy `000000093_MD_S-C_MATCH_DATA.bin` → `src/test/resources/golden/choose-starting-player-req.bin`.
Add field coverage test in `GoldenFieldCoverageTest`.

## Key reference files

| File | Why |
|---|---|
| `HandshakeMessages.kt` | Already builds ChooseStartingPlayerReq |
| `MatchHandler.kt:169` | Already dispatches ChooseStartingPlayerResp (ignored) |
| `GsmBuilder.kt:150` | Sets pendingMessageType for the Full GSM |
| `SmokeTest.kt:229` | Example of synthetic ChooseStartingPlayerResp |
| `WebPlayerController.kt:920` | `chooseStartingPlayer()` auto-choose |
| `DealHandConformanceTest.kt:163` | Conformance test for seat 2 initial bundle |

## Open questions

1. **Bo3 — does the loser choose in game 2/3?** Standard MTG rules say loser of
   previous game chooses play/draw. Need a Bo3 recording to confirm proto shape
   (is it the same req, just sent to different seat?).
2. **What if the client doesn't respond?** Timer probably kicks in — `TimeoutMessage`
   or auto-play. No recording of this scenario yet.
3. **Tied die rolls?** Re-roll presumably. No recording of ties.
4. **`prompt` field** — proto defines field 4 as `Prompt` but it's empty in our
   capture. Might be populated in other game modes or locales.
