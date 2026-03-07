# 05 — PvP Validation Strategy

## Goal

Record a real 2-account Arena PvP match via proxy, diff leyline output against Arena's golden GRE stream per seat, extract regression tests from divergences.

Single-player conformance (golden `.bin` files, `GoldenFieldCoverageTest`, `ValidatingMessageSink`) already covers annotation shape, field presence, gsId chain, instanceId lifecycle. PvP validation targets the *new* dimensions: dual-seat visibility filtering, independent mulligans, per-seat action routing, FD match-type push, and game-over delivery to both connections.

---

## What to Record

### Prerequisites

- Two Arena test accounts (any format — Direct Challenge is easiest, no queue wait)
- `just serve-proxy` running (proxies both FD + MD, captures all traffic)
- Both clients pointed at leyline's FD proxy endpoint

### Recording Procedure

1. **Launch proxy:** `just serve-proxy` (captures to `recordings/<timestamp>/capture/`)
2. **Client A** connects, sends `ChallengeCreate` (3001) → invites Client B
3. **Client B** connects, sends `ChallengeJoin` (3000)
4. Both send `ChallengeReady` (3008) with deck IDs
5. Host sends `ChallengeStartLaunchCountdown` (3012) → server pushes `MatchCreated` to *both* FD connections
6. **Both MD connections** establish: auth + `ConnectReq` with respective `systemSeatId`
7. **Play short game** (~5 turns): land, creature, combat, spell targeting, game over
   - Minimum coverage: play land, cast creature (both sides), attack/block once, targeted removal once, natural game-over (lethal damage)
8. **Disconnect both clients**

### Capture Artifacts

Current `CaptureSink` already produces per-frame binary files (`payloads/`) and FD JSONL (`fd-frames.jsonl`). Need to extend:

- **Gap:** `CaptureSink` does not tag frames by seat/connection. Both MD connections write to the same `payloads/` directory with a global sequence counter. Frames are distinguishable by payload content (`systemSeatIds` field in GRE wrapper) but not by filename.
- **Gap:** FD frames for both connections intermingle in one `fd-frames.jsonl`. `YourSeatId` in `MatchCreated` push differentiates them, but no per-connection tagging.

---

## What to Diff

### Layer 1: FD Handshake

| Checkpoint | Arena Golden | Leyline Output | Diff Method |
|---|---|---|---|
| `MatchCreated` push (seat 1) | `MatchType=Queue`, `YourSeatId=1`, both `PlayerInfos` | Same envelope shape | Field-level proto diff |
| `MatchCreated` push (seat 2) | `MatchType=Queue`, `YourSeatId=2` | Same with flipped seat | Field-level proto diff |

### Layer 2: MD Initial Bundle (per seat)

| Checkpoint | What to compare |
|---|---|
| `ConnectResp` | Both seats get one; `seatId` matches their `systemSeatId` |
| `DieRollResultsResp` | Same die roll values for both seats |
| Initial Full GSM | Object visibility: seat 1 sees own hand cards (with `GameObjectInfo`), seat 2's hand has `objectInstanceIds` but no `GameObjectInfo` (Hidden) |
| `MulliganReq` | Independent per seat; different `msgId`/`gameStateId` chains |

### Layer 3: Steady-State GRE Stream

| Dimension | Arena Behavior | What to check |
|---|---|---|
| Visibility filtering | Each seat's GSM omits `GameObjectInfo` for opponent's hidden zones (hand, library) | Parse both streams, diff `gameObjects` lists |
| gsId chain | Independent per seat — each connection has its own monotonic gsId sequence | `ClientAccumulator` per seat, assert no gaps |
| Annotation content | Same annotations in both streams (both seats see the same zone transfers) but `systemSeatIds` wrapper differs | Compare annotation types + detail keys, ignore seat-specific wrapper fields |
| Action routing | `ActionsAvailableReq` sent only to active-priority seat | Filter AAR messages per stream, verify they align with `turnInfo.activePlayer` |
| Combat | Both seats get `DeclareAttackersReq`/`DeclareBlockersReq` at correct times | Sequence alignment by phase |
| Game over | Both seats get `IntermissionReq` with correct `winningTeamId` | Direct field comparison |

### Layer 4: Client Actions (C2S)

| Checkpoint | What to compare |
|---|---|
| `PerformActionResp` | Routed to correct engine seat |
| `DeclareAttackersResp` / `SubmitAttackersReq` | Only from attacking player's seat |
| `DeclareBlockersResp` / `SubmitBlockersReq` | Only from defending player's seat |
| `SelectTargetsResp` | From seat that owns the targeting spell |

---

## Test Cases to Extract

### Golden Binary Tests (extend `GoldenFieldCoverageTest`)

1. **`pvp-initial-bundle-seat1.bin`** — ConnectResp + DieRoll + Full GSM from seat 1's perspective
2. **`pvp-initial-bundle-seat2.bin`** — Same from seat 2's perspective
3. **`pvp-mulligan-req-seat1.bin`** / **`pvp-mulligan-req-seat2.bin`** — Independent mulligan sequences
4. **`pvp-aar-active-seat.bin`** — ActionsAvailableReq sent to priority holder only
5. **`pvp-gsm-diff-both-seats.bin`** — Same game action, two different GSM diffs (visibility filtered)

### Conformance Tests (new test class: `PvPConformanceTest`)

1. **`visibility filtering — opponent hand hidden`**: Parse both seat streams from recording, assert seat 1's GSM has no `GameObjectInfo` for seat 2's hand zone objects (and vice versa)
2. **`independent gsId chains`**: Run `ClientAccumulator` on each seat's stream independently, assert both chains are gap-free and monotonic
3. **`action routing correctness`**: For each `ActionsAvailableReq` in recording, assert `systemSeatIds[0]` matches `turnInfo.activePlayer`
4. **`MatchCreated push per seat`**: Parse FD JSONL, find both `MatchCreated` pushes, assert `MatchType=Queue` and different `YourSeatId` values
5. **`game over delivered to both`**: Both streams end with `IntermissionReq` containing same `winningTeamId`

### Invariant Tests (extend `ValidatingMessageSink`)

6. **`cross-seat object leak`**: After processing each GSM, assert no `GameObjectInfo` exists for objects in opponent's Hidden/Private zones. New invariant in `InvariantChecker`.
7. **`action-to-wrong-seat`**: Assert `ActionsAvailableReq` never appears in the non-active-player's stream

---

## Infrastructure Needed

### Recording Infrastructure

| Gap | What to Build | Where |
|---|---|---|
| **Per-connection tagging** | `CaptureSink` must tag MD frames with connection ID / seat ID. Currently both connections write to same directory with global seq. | `CaptureSink.kt` — add `connectionId` parameter to `ingestChunk`, encode in filename (e.g. `000042_MD-S1_MATCH_DATA.bin`) |
| **Dual-client proxy** | Current proxy mode accepts one MD connection. Need to accept two simultaneous MD connections, each with own `CaptureSink` stream. | `LeylineServer` / proxy pipeline — register per-connection capture context |
| **FD per-connection JSONL** | Tag FD frames with connection ID so seat 1 vs seat 2 pushes are separable. | `CaptureSink.writeFdJsonl` — add `connectionId` field to `FdFrameRecord` |

### Test Infrastructure

| Gap | What to Build | Where |
|---|---|---|
| **Dual-seat recording parser** | Load a PvP recording, split into per-seat GRE streams. Key: match `systemSeatIds` in GRE wrapper to route messages. | New `PvPRecordingParser` in `conformance/` |
| **Dual-seat `ClientAccumulator`** | Instantiate two accumulators, feed each seat's filtered stream. Existing `ClientAccumulator` works as-is — just need the split. | Test setup in `PvPConformanceTest` |
| **Cross-seat invariant** | `InvariantChecker.checkVisibilityLeak(seatId, gsm)` — given a seat, assert no `GameObjectInfo` for opponent's hidden zones. Needs zone ownership mapping (zone → owning seat). | `InvariantChecker.kt` |
| **FD envelope diffing** | Compare `MatchCreated` JSON payloads field-by-field. Existing `FieldPathExtractor` works for proto; need JSON equivalent or manual field assertions. | Test helpers or `FieldPathExtractor` extension |

### Stretch: Replay-Based Regression

Once recording + parsing works, build `PvPReplayTest`:
- Load recorded PvP session
- Feed client actions to leyline's dual-seat engine
- Diff leyline's output per seat against Arena golden
- Fail on new divergences (same pattern as `GoldenFieldCoverageTest.expectedMissing`)

This is the end-state validation: fully automated regression from recorded PvP games. Depends on tasks #1 (match lifecycle), #2 (per-seat bridge), #4 (MD dual-seat handling) being implemented first.

---

## Sequencing

1. **Now (no code):** Record a PvP session via `just serve-proxy` with manual dual-client setup. Even without per-seat tagging, the raw `payloads/` directory captures both streams — `systemSeatIds` in each GRE message is enough to manually split.
2. **After #1/#2/#4:** Add per-connection tagging to `CaptureSink`, build `PvPRecordingParser`, write `PvPConformanceTest`.
3. **After implementation stabilizes:** Extract golden binaries, add to `src/test/resources/golden/pvp-*`, wire into `GoldenFieldCoverageTest`.
4. **Ongoing:** Re-record after protocol changes (Arena client updates). Recording is cheap; golden extraction is manual but infrequent.
