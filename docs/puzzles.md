---
summary: "Puzzle mode runtime: `just puzzle` hot-swap API, protocol differences from constructed (skip mulligan), .pzl file format, and test integration."
read_when:
  - "running or hot-swapping puzzles via the debug API"
  - "understanding how puzzle mode differs from constructed at the protocol level"
  - "writing puzzle-based tests"
---
# Puzzle Mode

Forge `.pzl` puzzles played through the client. Pre-built board states — no mulligan, no deck building, no ramp-up turns.

## Quick Start

```bash
# 1. Start server
just serve   # (background)
# 2. Set puzzle via runtime API (hot-swaps if in match, queues for next Sparky match)
just puzzle puzzles/bolt-face.pzl
# 3. Navigate to Sparky match
just arena-ts bot-match
```

`just puzzle <file>` calls `POST :8090/api/puzzle?file=<name>` on the debug server. Hot-swaps if already in a match; queues for the next Sparky match otherwise. `GET :8090/api/puzzle` returns the current puzzle; `POST` with no params clears it.

Puzzles only affect Sparky challenge matches. Normal queues and direct matches stay constructed.

Test puzzles live in `matchdoor/src/test/resources/puzzles/`. Simplest: `WEB_TEST_00.pzl` (1 Mountain, 1 Lightning Bolt, AI at 3 life).

## Protocol: Puzzle vs Constructed

Puzzle mode skips the entire pre-game handshake (die roll, starting player choice, deal hand, mulligan). The client receives a board that's already in play.

### Constructed

```
ConnectResp → DieRollResultsResp → Full GSM (stage=Start, empty board)
  → ChooseStartingPlayerReq/Resp
  → DealHand GSM → MulliganReq/Resp (repeat until keep)
  → Full GSM (stage=Play) → ActionsAvailableReq
```

### Puzzle

```
ConnectResp → Full GSM (stage=Play, pre-populated board, pendingMessageCount=1)
  → ActionsAvailableReq
```

The client sees `stage=Play` in the first GSM and enters the turn loop directly. No `MulliganReq` means no mulligan UI. Cards render from the Full GSM without needing ZoneTransfer history — the client's `FirstGameStateEvents()` iterates all zones and creates card visuals.

## Architecture

### Startup Flow

```
POST :8090/api/puzzle?file=puzzles/bolt-face.pzl   (or: just puzzle puzzles/bolt-face.pzl)
  → debug server stores active puzzle path
  → FrontDoor MatchCreated for SparkyStarterDeckDuel uses matchId "puzzle-bolt-face"

On client ConnectReq:
  MatchHandler.isPuzzleMatch()          // true if matchId starts with "puzzle-"
  MatchHandler.loadPuzzleForMatch()     // loads .pzl, needs localization init first
    → GameBootstrap.initializeLocalization()
    → PuzzleSource.loadFromFile(config path or puzzles/<name>.pzl)
  GameBridge.startPuzzle(puzzle)
    → GameBootstrap.initializeCardDatabase()
    → GameBootstrap.createPuzzleGame()  // empty decks, startingHand=0, GameType.Puzzle
    → applyPuzzleSafely(puzzle, game)   // reflection + temp controllers
    → GameBootstrap.finalizeForPuzzle() // game.age=Play, MAIN1 turn 1
    → registerPuzzleCards()             // CardDb + InstanceIdRegistry
    → startFromCurrentState()           // no Match.startGame, no mulligan
    → awaitPriority()
  MatchHandler.sendPuzzleInitialBundle()
    → HandshakeMessages.puzzleInitialBundle()   // ConnectResp + Full GSM
    → HandshakeMessages.puzzleActionsReq()      // first priority actions
  MatchSession.onPuzzleStart()          // seed snapshot, enter auto-pass loop
```

### Key Files

| File | Role |
|------|------|
| Debug API (`/api/puzzle`) | Runtime puzzle selection for Sparky routing |
| `LeylineServer.kt` | Routes SparkyStarterDeckDuel to `puzzle-<name>` match IDs |
| `MatchHandler.kt` | Puzzle detection, bridge creation, initial bundle |
| `MatchSession.kt` | `onPuzzleStart()` — seeds snapshot, enters game loop |
| `GameBridge.kt` | `startPuzzle()`, `applyPuzzleSafely()`, `registerPuzzleCards()` |
| `PuzzleSource.kt` | Load from file/text/resource via `PuzzleIO` |
| `PuzzleCardRegistrar.kt` | Runtime card registration (real grpIds from client SQLite) |
| `HandshakeMessages.kt` | `puzzleInitialBundle()`, `puzzleActionsReq()` |

## Card Registration

Puzzle cards don't come from decks — they're created by `Puzzle.applyGameOnThread()` via `Card.fromPaperCard()`. They need grpIds and instanceId mappings for proto output.

### grpId Resolution

`PuzzleCardRegistrar` resolves grpIds in priority order:

1. **Client SQLite** — `CardDb.lookupByName(cardName)` queries the client's local `Raw_CardDatabase_*.mtga`. Returns the real grpId the client uses for art/text lookup.
2. **Synthetic fallback** — If the card isn't in the client DB (e.g. cards not on Arena), assigns a synthetic grpId starting at 300000. Client shows placeholder art.

### InstanceId Registration

After puzzle application, `registerPuzzleCards()` iterates all cards in all zones:
- Calls `PuzzleCardRegistrar.ensureCardRegisteredByName(card.name)` — uses by-name path because puzzle-applied cards have null `card.rules` (NPE if you try `card.rules.color`)
- Calls `InstanceIdRegistry.getOrAlloc(card.id)` to seed the forge-cardId ↔ instanceId bimap

## Puzzle Application (Reflection)

`Puzzle.applyGameOnThread()` is a protected method on the superclass. Leyline calls it via reflection:

```kotlin
val method = puzzle.javaClass.superclass.getDeclaredMethod("applyGameOnThread", Game::class.java)
method.isAccessible = true
method.invoke(puzzle, game)
```

During application, the engine may fire SBAs or triggers that need controller responses. Temp `WebPlayerController`s with `autoKeep=true` and `timeoutMs=0` are installed on human-controlled players for the duration of the call (`runWithTempControllers` pattern).

## Localization Init

The `Puzzle` constructor triggers `GameState.<clinit>` which requires Forge's localization to be initialized. This must happen **before** `PuzzleSource.loadFromFile()` — not after (as `GameBootstrap.initializeCardDatabase()` would do). `loadPuzzleForMatch()` calls `GameBootstrap.initializeLocalization()` first.

In tests, classes that construct `Puzzle` objects directly must use the `integration` group (which boots the engine) or call `GameBootstrap.initializeLocalization()` in setup.

## MatchHandler Branching

Puzzle mode affects several MatchHandler code paths:

| Message | Constructed | Puzzle |
|---------|------------|--------|
| `ConnectReq` | Create bridge, load decks, send initial bundle with DieRoll | Create bridge with puzzle, send puzzle bundle (no DieRoll) |
| `ChooseStartingPlayerResp` | Send DealHand + MulliganReq | Ignored |
| `MulliganResp` | Process keep/mull | Ignored |
| `PerformActionResp` | Normal action handling | Same (no difference) |
| All combat/targeting | Normal | Same |

Detection: `isPuzzleMatch()` returns true when Front Door routed the match with a `puzzle-` match ID.

## Goal Types

Forge creates a goal enforcement card in the Command zone with triggers. The `Turns:N` field is the deadline.

| Goal | Win trigger | Lose trigger |
|------|-----------|-------------|
| `Win` | Opponent life → 0 (normal rules) | Cleanup of turn N → `LosesGame` |
| `Survive` | Upkeep of turn N+1 → `WinsGame` | Normal game loss (life=0, etc.) |
| `Destroy Specified Creatures` | Last `Targets:` creature leaves BF → `WinsGame` | Cleanup of turn N → `LosesGame` |
| `Play the Specified Permanent` | `Targets:` permanent enters BF → `WinsGame` | Cleanup of turn N → `LosesGame` |
| `Gain Control of Specified Permanents` | Last `Targets:` permanent changes controller → `WinsGame` | Cleanup of turn N → `LosesGame` |
| `Win Before Opponent's Next Turn` | Opponent life → 0 | Opponent's upkeep → `LosesGame` |

`Targets:` uses Forge card filter syntax (e.g. `Creature.OppCtrl`). `TargetCount:N` sets how many must be on BF for Play goal.

`HumanControl:true` gives the human player control of both seats (AI has no autonomous decisions). Useful for scripted test runs.

See `docs/puzzle-design.md` for design patterns and AI behavior considerations.

## `.pzl` File Format

See `docs/puzzle-format.md` for the full spec. Quick reference:

```ini
[metadata]
Name:Lightning Bolt Puzzle
URL:...
Goal:Win this turn
Turns:1
Difficulty:easy
Description:Cast Lightning Bolt to win

[do_not_change]
Amount:0

[human]
Life:20
Hand:Lightning Bolt
Battlefield:Mountain

[ai]
Life:3
```

## Testing

- **`PuzzleBridgeTest.kt`** — 16 integration tests covering: puzzle game creation, state application, card registration, life totals, zone contents, actions available, Full GSM output, multiple puzzle files
- **`PuzzleSourceTest.kt`** — 4 tests (2 unit for metadata parsing, 2 integration for full puzzle loading)
- **Test puzzles** — `matchdoor/src/test/resources/puzzles/`: `WEB_TEST_00`, `WEB_TEST_01`, `WEB_TEST_02` (from forge-gui), `simple-attack`, `custom-life`, `lands-only`

## Limitations

- **No puzzle selection UI** — use `just puzzle <file>` or `POST /api/puzzle`, then launch Sparky. No in-client browser.
- **No goal display** — the Puzzle Goal enforcement card exists in the Command zone but the client has no UI for it.
- **No turn-limit timer** — Forge engine tracks turns but there's no visual countdown.
- **Seat 2 (Familiar) support deferred** — only seat 1 (human) connection is wired. Familiar connects but doesn't receive puzzle-specific state.
- **Cards not on Arena** — will render with placeholder art (synthetic grpId). Most Standard/Pioneer/Modern staples are fine.
