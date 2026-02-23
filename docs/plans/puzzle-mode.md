# Implementation Plan: Puzzle Mode in forge-nexus

> Status: **Implemented** (Phases 1-3, 5 complete; `--puzzle` CLI flag working; Phase 4 deferred)
> Documentation: `forge-nexus/docs/puzzles.md`
> Ref: `~/src/mtga-internals/research/puzzle-mode-feasibility.md`, `~/src/mtga-internals/decompilation-request.md`

## Goal

Run Forge puzzles (`.pzl` files) through forge-nexus so the Arena client plays pre-built board states. Enables fast scenario testing: custom life, specific permanents, targeted interactions — no mulligan, no deck building, no ramp-up turns.

## Key Findings (from decompilation)

- **Cards render from Full GSM without ZoneTransfer history.** `FirstGameStateEvents()` iterates all zones → `CardCreatedEvent` per card → visual created. No synthetic annotations needed.
- **`stage=Play` in first GSM skips mulligan UI.** Mulligan browser is request-driven — no `MulliganReq` → no UI → no blocking.
- **No DieRoll/ChooseStartingPlayer needed.** Client enters play loop directly.

## How forge-web does it (reference)

```
PuzzleRoutes.loadPuzzle(filename)          → Puzzle object from DB
GameBootstrap.createPuzzleGame()           → empty decks, startingHand=0, GameType.Puzzle
applyPuzzleSafely(puzzle, game)            → reflection: Puzzle.applyGameOnThread(game)
  └─ runWithWebControllers(autoKeep=true)    (temp controllers prevent blocking during setup)
GameBootstrap.finalizeForPuzzle(game)      → game.age=Play, devModeSet(MAIN1, turn=1)
GameSessionManager.startGameLoop(room)     → game.age==Play → startFromCurrentState()
  └─ onStackResolved() + mainGameLoop()      (no Match.startGame, no mulligan)
```

Key details:
- `startingHand=0` on both players — engine won't draw
- `startFromCurrentState()` calls `onStackResolved()` (sets `givePriorityToPlayer=true`) then `mainGameLoop()` directly
- Temp `WebPlayerController` with `autoKeep=true` / `timeoutMs=0` during `applyGameOnThread` — some puzzles trigger SBAs/abilities during setup that need controller responses
- `Puzzle.addGoalEnforcement()` creates a "Puzzle Goal" card in Command zone with win/lose triggers

## Protocol: Puzzle vs Constructed

### Constructed (current)
```
ConnectResp → DieRollResultsResp → Full GSM (stage=Start, empty board)
  → ChooseStartingPlayerReq/Resp
  → DealHand → MulliganReq/Resp (repeat until keep)
  → Full GSM (stage=Play) → ActionsAvailableReq
```

### Puzzle (proposed)
```
ConnectResp → Full GSM (stage=Play, cards pre-placed in zones, pendingMessageCount=1)
  → ActionsAvailableReq
```

Skip DieRoll, ChooseStartingPlayer, DealHand, MulliganReq entirely. Client sees `stage=Play` and enters the turn loop.

---

## Phase 1 — GameBridge.startPuzzle()

The core: create a puzzle game, apply state, start the loop without mulligan.

### Tasks

| # | Type | File | Description |
|---|------|------|-------------|
| 1a | Impl | `GameBridge.kt` | `startPuzzle(puzzleContent: String)` method. Flow: `initCardDB` → `createPuzzleGame()` → `applyPuzzleSafely(puzzle, game)` → `finalizeForPuzzle(game)` → wire `WebPlayerController` (no mulligan bridge needed — or `autoKeep=true`) → `loop.startFromCurrentState()` → register EventCollector + Playback → `awaitPriority()` (not `awaitMulliganReady`) |
| 1b | Impl | `GameBridge.kt` | `applyPuzzleSafely(puzzle, game)` — reflection call to `Puzzle.applyGameOnThread(game)`. Install temp controllers with `autoKeep=true`, `timeoutMs=0` during application (copy forge-web's `runWithWebControllers` pattern). Remove after. |
| 1c | Impl | `GameBridge.kt` | Extract shared setup from `start()` into `wireControllerAndLoop(skipMulligan: Boolean)` — both `start()` and `startPuzzle()` call it with different flags. When `skipMulligan=true`: no `seat1MulliganBridge` blocking, use `startFromCurrentState()`, await priority instead of mulligan. |
| 1d | Impl | `GameBridge.kt` | `isPuzzle: Boolean` property (checks `game?.rules?.gameType == GameType.Puzzle`). Exposed for MatchHandler/MatchSession branching. |
| 1e | Test | `PuzzleBridgeTest.kt` | Integration test: `startPuzzle(pzlContent)` → verify `game.age == GameStage.Play` → verify `awaitPriority()` returns pending actions → verify cards on battlefield/hand match puzzle spec → verify life totals. Group: `integration`. |

### Notes
- `CardDb` registration: `Puzzle.applyGameOnThread` creates cards via `Card.fromPaperCard()`. `CardDataDeriver.fromForgeCard()` (from TestCardInjector plan) derives `CardDb.CardData` from these. Need to register each card's grpId in `CardDb` after puzzle application — iterate `game.getCardsIn(allZones)` and register any missing.
- `InstanceIdRegistry` seeding: after puzzle applies, iterate all cards in all zones and call `ids.getOrAlloc(card.id)` to pre-seed the bimap. `StateMapper.buildFromGame()` will then find valid mappings.

---

## Phase 2 — Protocol: Puzzle Handshake

New handshake messages that skip mulligan and send a pre-populated board.

### Tasks

| # | Type | File | Description |
|---|------|------|-------------|
| 2a | Impl | `HandshakeMessages.kt` | `puzzleInitialBundle(seatId, matchId, bridge)` — ConnectResp + Full GSM with `stage=Play`, all 17 zones populated from current game state, `pendingMessageCount=1`. Uses `StateMapper.buildFromGame()` (not `buildInitialGameState` which is pre-deal). |
| 2b | Impl | `StateMapper.kt` | Ensure `buildFromGame()` can produce a valid Full GSM for the initial puzzle state. It already can — but verify: `gameInfo.stage` must be `Play`, `matchState` must be `GameInProgress`. May need a `forceStage` parameter or read from `game.age`. |
| 2c | Impl | `PlayerMapper.kt` | Read `startingLifeTotal` from `game` instead of hardcoding 20. Puzzle games set custom life via `Puzzle.applyGameOnThread` → `player.setLife(N)`. Map: `startingLifeTotal = player.startingLife` (if available) or fall back to current `player.life`. |
| 2d | Impl | `HandshakeMessages.kt` | `puzzleActionsReq(msgId, gsId, bridge)` — ActionsAvailableReq for the first priority in the puzzle. Follows the Full GSM (referenced by `pendingMessageCount=1`). |
| 2e | Conf | `PuzzleHandshakeTest.kt` | Conformance test: verify puzzle initial bundle has correct zone contents, life totals, stage=Play, no MulliganReq, correct pendingMessageCount. Compare against decompilation requirements (GameObjectInfo fields: instanceId, grpId, type, zoneId, visibility, ownerSeatId, controllerSeatId, cardTypes, P/T, color, isTapped). Group: `conformance`. |

---

## Phase 3 — MatchHandler + MatchSession Wiring

Route puzzle games through the new code path.

### Tasks

| # | Type | File | Description |
|---|------|------|-------------|
| 3a | Impl | `MatchHandler.kt` | Puzzle mode detection in `processGREMessage(ConnectReq)`. Option A: match ID convention (e.g. `puzzle-<filename>`). Option B: server config / CLI flag. Option C: separate match type field in connect request. **Recommend Option A** — simplest, no proto changes. |
| 3b | Impl | `MatchHandler.kt` | Puzzle bridge factory: `GameBridge().also { it.startPuzzle(puzzleContent) }`. Load puzzle content by filename from Forge's puzzle DB (reuse `PuzzleLoader` from forge-web, or read `.pzl` files directly). |
| 3c | Impl | `MatchHandler.kt` | New `connectPuzzleBridge(ctx, bridge, matchId, seatId)` — sends `puzzleInitialBundle` + `puzzleActionsReq`. Skips DieRoll, ChooseStartingPlayer, DealHand, MulliganReq. |
| 3d | Impl | `MatchHandler.kt` | On seat 1 connect for puzzle: call `session.onPuzzleStart()` (Phase 3e) instead of waiting for `MulliganResp`. |
| 3e | Impl | `MatchSession.kt` | `onPuzzleStart()` — seeds initial snapshot from puzzle board state, calls `bridge.awaitPriority()`, sends `phaseTransitionDiff` or `postAction` bundle, enters `autoPassEngine.autoPassAndAdvance()`. Similar to `onMulliganKeep()` but without mulligan seeding. |
| 3f | Impl | `MatchHandler.kt` | Skip ChooseStartingPlayer/MulliganResp dispatch for puzzle matches. `processGREMessage` should ignore or fast-path these message types when `bridge.isPuzzle`. |
| 3g | Test | `PuzzleMatchFlowTest.kt` | Integration test: full Netty connect → puzzle handshake → verify client receives Full GSM with cards → submit an action → verify state update. Group: `integration`. |

---

## Phase 4 — Seat 2 (Familiar) Support

Familiar/spectator connection for puzzle mode. Lower priority — seat 1 is sufficient for testing.

### Tasks

| # | Type | File | Description |
|---|------|------|-------------|
| 4a | Impl | `MatchHandler.kt` | Seat 2 connect for puzzle: send `puzzleInitialBundle(seatId=2)` with appropriate visibility (no private hand cards for opponent). |
| 4b | Impl | `MatchSession.kt` | `mirrorToFamiliar` already filters private objects — verify it works for puzzle state. |
| 4c | Test | `PuzzleFamiliarTest.kt` | Seat 2 receives correct state (no human's hand cards visible). Group: `integration`. |

---

## Phase 5 — Puzzle Loading Infrastructure

Make puzzles easily loadable for testing and interactive use.

### Tasks

| # | Type | File | Description |
|---|------|------|-------------|
| 5a | Impl | `PuzzleSource.kt` | Simple puzzle loader: `loadFromFile(path)`, `loadFromText(content)`, `loadFromResource(name)`. Parses `.pzl` format via Forge's `PuzzleIO.parsePuzzleSections()` → `Puzzle` object. |
| 5b | Impl | `justfile` | `just serve-puzzle <filename>` — starts nexus in puzzle mode with a specific `.pzl` file. Hardcodes matchId to `puzzle-<filename>`. |
| 5c | Test | `PuzzleSourceTest.kt` | Unit test: parse a `.pzl` string, verify metadata + state. Group: `unit`. |
| 5d | Res | `src/test/resources/puzzles/` | 3-5 test puzzles covering: simple (1 creature each), combat (attackers/blockers), spell (instant in hand), low life, complex board. Can copy from `forge-gui/res/puzzle/`. |

---

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Arena client chokes on `stage=Play` in first GSM | Low (decompilation says it works) | High | Fallback: send `stage=Start` first, then immediately `stage=Play` Diff. Or fake the mulligan handshake (Option B from feasibility doc). |
| `Puzzle.applyGameOnThread` triggers engine callbacks that block without proper controllers | Medium | Medium | Temp `WebPlayerController` with `autoKeep=true`, `timeoutMs=0` (forge-web pattern). Already solved. |
| `CardDb` missing grpId for puzzle cards | High (first time) | Low | Auto-register via `CardDataDeriver.fromForgeCard()` after puzzle application. TestCardInjector already does this. |
| Puzzle Goal enforcement card confuses client (Command zone card with no grpId) | Medium | Low | Either: (a) filter it from proto output (it's a virtual card), or (b) register it in CardDb with a synthetic grpId. |
| `FirstGameStateEvents` untested with populated battlefield | Low | Medium | Test empirically with a simple puzzle. Decompilation confirms the code iterates all zones. |

## Non-Goals (for now)

- **Puzzle UI in Arena** — no puzzle selection screen, no goal display, no turn-limit timer. Puzzles are loaded via matchId convention or CLI.
- **Puzzle completion tracking** — no persistence of solved/unsolved state.
- **AI-generated puzzles** — forge-web already has this via OpenRouter. Not duplicated in nexus.
- **Multi-turn puzzle orchestration** — Forge engine handles turn advancement natively. No nexus-specific work needed.

## Dependency on TestCardInjector

Phase 1 reuses `CardDataDeriver` (from test-card-injection plan, already implemented) to register puzzle cards in `CardDb`. No new CardDb infrastructure needed.
