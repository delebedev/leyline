# Debug Tooling Roadmap

Testing iteration speed, client conformance verification, and game replay without engine boot.

## Status

Phase 1 (Full GSM injection) and Phase 1.5 (Puzzle hot-swap) confirmed working. Remaining phases pending.

---

## Phase 1: Full GSM Injection (DONE)

**Branch:** `feat/inject-full-gsm`

### What we proved

The Arena client accepts a Full `GameStateMessage` mid-game at any point. No validation of `prevGameStateId`, no session-phase guards, no history required. The client completely replaces its internal state and renders from zone membership alone.

### What we shipped

- `POST /api/inject-full` debug endpoint — rebuilds current engine state as Full GSM + `ActionsAvailableReq`, sends to connected client
- `FrameCodecTest` — roundtrip unit tests for the 6-byte wire framing (encoder/decoder/CTRL handshake/partial buffering/large payload)
- `sessionProvider` wiring on `NexusDebugCollector` (parallel to existing `bridgeProvider`)

### Key findings

1. Full GSM must be followed by `ActionsAvailableReq` — client keeps stale actions otherwise, sends responses at old gsId, server rejects as stale
2. Bridge snapshot must be updated after injection (`bridge.snapshotState()`) — otherwise next Diff is computed against wrong baseline
3. No client errors on repeated injection (tested 3+ times mid-game)
4. Annotations can be empty on Full GSM — cards render from zone membership, animations are skipped

---

## Phase 1.5: Puzzle Hot-Swap (DONE)

**Issue:** [#56](https://github.com/delebedev/leyline/issues/56)

### What we shipped

`POST /api/inject-puzzle` debug endpoint — tears down the running game, loads a `.pzl` puzzle, starts a fresh Forge engine, and injects the new board state into the connected client. No lobby re-navigation needed.

### Usage

```bash
# By file name (loads from matchdoor/src/test/resources/puzzles/)
curl -X POST "http://localhost:8090/api/inject-puzzle?file=bolt-face"

# By raw .pzl content in body
curl -X POST http://localhost:8090/api/inject-puzzle -d @path/to/puzzle.pzl
```

### Key findings

1. `GameBridge.resetForPuzzle(puzzle)` — shutdown loop, clear all state (instanceIds, limbo, zones, snapshots, persistent annotations), swap to InMemoryCardRepository, call startPuzzle
2. Production bridges use `ExposedCardRepository` (client SQLite) — puzzle synthetic grpIds (300000+) don't exist there. Must swap to `InMemoryCardRepository` on puzzle reset.
3. Sequential injection works: tested 3+ puzzles in a row, zero client errors
4. Iteration time: ~2s per puzzle swap (engine init + priority wait)

---

## Phase 2: Synthetic Scenario Player (no engine)

Build `ScenarioSession` that constructs Full GSM + actions from a declarative board description. No Forge engine boot.

### Concept

```yaml
board:
  p1: { life: 20, hand: [Lightning Bolt], battlefield: [Mountain] }
  p2: { life: 3, battlefield: [Grizzly Bears] }
actions: [CastSpell(Lightning Bolt, target=p2)]
```

Serialize to Full GSM, send to client, handle response. Iteration time: milliseconds.

### Use cases

- Rapidly iterate annotation types and UI fidelity
- Test proto builder correctness without engine
- "Does the client crash with this field combo?" — sub-second feedback loop
- Card rendering QA across many board states

### Prerequisites

- Phase 1 (confirmed)
- `CardDataDeriver` (exists in test code) for building `GameObjectInfo` from card name
- Zone template builder (17 zones with correct IDs/types/visibility)

---

## Phase 3: Snapshot-Fork Testing

Capture a Full GSM from a real game at an interesting point (combat, stack with 3 items, many tokens). Fork: mutate the snapshot and inject the mutated Full.

### Use cases

- Edge cases you can't easily construct through gameplay ("30 tokens on board")
- Regression testing: capture known-good state, replay after code changes
- A/B testing annotation variants on identical board states

### Implementation

- `GET /api/snapshot` — return current Full GSM as JSON/base64
- `POST /api/inject-snapshot` — accept a (possibly mutated) GSM and inject it
- CLI tool: `just snapshot > board.json`, edit, `just inject < board.json`

---

## Phase 4: Recording Replay

Two recording sources, same `MatchServiceToClientMessage` proto format:

| Source | Files | Current status |
|---|---|---|
| Proxy capture (real Arena) | `capture/payloads/MD_S-C_*.bin` | `ReplayHandler` works (`serve-replay`) |
| Engine dump (our server) | `engine/NNN-*.bin` | Same format, not wired into `ReplayHandler` |

### Work needed

- `ReplayHandler` filename filter: expand from `startsWith("S-C_MATCH")` to also include `NNN-*.bin` engine dump naming
- New mode: `serve-replay-engine` = FD replay (`FrontDoorReplayStub`) + MD engine-dump replay
- Jump-to-point: load specific `.bin` from recording, inject as Full GSM via Phase 1 endpoint

### Diff-only animation testing

Send stable Full GSM as baseline, then send Diffs with specific annotations to test individual visual transitions in isolation. No need to reproduce the game state that triggers a specific animation.

---

## Phase 5: Arena Debug Tooling Integration

Reference: `~/src/mtga-internals/research/debug-tooling-inventory.md`

The client ships 23 debug tabs, autoplay scripting, protocol watchers, and a replay system — all gated by `HasDebugRole()` checking `MTGA_DEBUG` account role.

### No-patch items (try first)

| Item | What | Effort |
|---|---|---|
| `overrides.conf` | `{"features": {"debug": true}}` in persistent data path | Trivial — create file |
| PlayerPrefs `CheckSC` | `ShouldValidateServerCert = false` — kills cert validation | Trivial — edit plist |
| localhost:5001 listener | `WorkflowSourceLogger` POSTs every user interaction | Trivial — stand up HTTP server |
| `DEBUG_PRV_MATCHDOOR_HOST/PORT` | PlayerPrefs redirect to our server | Trivial — edit plist |

### With debug role unlocked

| Tool | Replaces/Upgrades | Impact |
|---|---|---|
| **GRE Watcher** (Tab 2) | Our golden field coverage tests | Real-time client-side parsing verification |
| **DuelScene overlay** (`RenderGameState()`) | Blind injection → verified injection | See client's internal state after our Full GSM |
| **UXEventDebugger** | Guessing if annotations trigger right visuals | Direct animation/event inspection |
| **Autoplay scripting** (30 action types) | `click.swift` + coordinate math | Native action automation, no window-position fragility |
| **ReplayExportModule** (Tab 12) | Our `ReplayHandler` | Client-native replay format, possibly richer |
| **PerformanceCSVLogger** | Proxy capture for GRE recording | Captures full GRE exchange to CSV |

### How debug tools enhance earlier phases

- **Phase 1 (inject-full):** DuelScene overlay verifies client parsed our GSM correctly
- **Phase 2 (synthetic scenarios):** Autoplay drives actions against synthetic state; GRE Watcher shows parsing
- **Phase 3 (snapshot-fork):** DuelScene dump = ground truth for state comparison
- **Phase 4 (replay):** Compare our replay against client's native replay system

### Binary patch path

If no-patch items don't unlock debug: patch `HasDebugRole()` in IL2CPP to return `true`. Single function, unlocks all 23 tabs + DuelScene overlay + autoplay. See `debug-tooling-inventory.md` for decompilation targets.

---

## Priority Order

1. **Phase 1** — DONE
2. **Ship inject-full on `feat/inject-full-gsm`** — merge to master
3. **Phase 5 no-patch items** — try `overrides.conf`, PlayerPrefs, localhost:5001 (minutes each)
4. **Phase 2** — synthetic scenario player (highest iteration speed payoff)
5. **Phase 4** — recording replay (engine dump wiring is small; jump-to-point uses Phase 1)
6. **Phase 3** — snapshot-fork (builds on Phase 1 + Phase 4)
7. **Phase 5 binary patch** — if no-patch didn't work, patch `HasDebugRole()`
