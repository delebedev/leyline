# simclient

Synthetic GRE-log generator. Drives both seats of a leyline match in-process
(`MatchSession` + `GameBridge` + Forge engine), emits Player.log-shaped output
under `matchdoor/build/simclient/`, and tags each game `source: simclient` via
a `<log>.meta.json` sidecar so scry-ts can ingest the result alongside other
saved games without polluting reference data.

Lives in test source — opt-in via the dedicated Gradle task; the regular gate
excludes it.

## What's in this directory

- `SimClientDriver.kt` — the loop: greedy policy + responder dispatcher.
- `PlayerLogWriter.kt` — formats outbound GRE bundles into Player.log lines
  with the type translations scry-ts expects, and emits `.meta.json` sidecars
  via `writeSimClientSidecar`.
- `SimClientE2ETest.kt` — two fast smoke tests (mono-Forest mirror, vanilla-
  creatures mirror). Verifies the pipeline.
- `SimClientBatchTest.kt` — env-driven `(deck × seed)` matrix with stats +
  prompt-histogram aggregation. Reads decks from `data/decks/<name>.txt` or
  the built-in deck table.

All four files are tagged `leyline.SimClientTag` (see `Tags.kt`) so they only
run under the `:matchdoor:simclient` Gradle task, never under `:testGate`.

## How to invoke

**Recommended — `just simclient` recipe** clears prior outputs, runs the
batch, copies logs into `~/.scry/games/`:

```bash
# Defaults: 4 decks × 5 seeds + Simple test deck × 3 seeds
just simclient

# Custom matrix
just simclient mono-r-burn 1..50           # 50 burn games
just simclient "bears,mono-r-burn" 1..20   # 40 mixed games
just simclient "Auras,Black aggro" 1,2,3   # 6 games using data/decks/*.txt
```

**Direct gradle** (no ingest):

```bash
SIMCLIENT_DECKS=mono-r-burn SIMCLIENT_SEEDS=1..20 \
  ./gradlew :matchdoor:simclient
ls matchdoor/build/simclient/   # *.log + *.meta.json
```

**Single E2E smoke** (fastest, ~10s, no env):

```bash
./gradlew :matchdoor:test --tests "leyline.simclient.SimClientE2ETest"
```

## Output

Each game produces two files:

```
matchdoor/build/simclient/<deck>-s<seed>.log        # Player.log-shaped JSON blocks
matchdoor/build/simclient/<deck>-s<seed>.meta.json  # provenance sidecar
```

Sidecar shape (matches scry-ts `GameMeta`):

```json
{
  "cards": [],
  "tags": ["simclient", "deck:mono-r-burn", "seed:42"],
  "notes": [],
  "provenance": {
    "source": "simclient",
    "confidence": "explicit",
    "matchId": "simclient-mono-r-burn-s42",
    "eventName": "simclient-mono-r-burn",
    "recordedAt": "2026-05-01T..."
  }
}
```

`just simclient` copies both into `~/.scry/games/` so scry-ts picks them up.

## Greedy policy

The driver handles a fixed set of GRE message types — anything outside this
list falls back to `passPriority`:

| Prompt | Decision |
|---|---|
| `MulliganReq` | always keep (handled by `connectAndKeep`) |
| `ActionsAvailableReq` | play a land if available; else cast first castable spell from the AAR's active actions; else pass |
| `DeclareAttackersReq` | declare all attackers, then submit |
| `DeclareBlockersReq` | no blocks (submit empty) |
| `SelectTargetsReq` | first legal target across all selection slots |
| `GroupReq` | scry-style top-all (no surveil-to-graveyard) |
| `IntermissionReq` | pass (game-ending; loop exits next iteration) |

Two safety nets keep games terminating:

- **Same-turn iteration stall**: if no turn advance for 200 iterations, call
  `session.onConcede()` so the engine emits a proper game-over sequence.
- **Cleanup concede**: if the loop exits while the game is still active
  (max-turns hit, no-progress break, iter cap), concede + drain so every
  game produces `gameOver=true`.

## Dependencies — what we lean on from the test tree

The driver is thin because it leans on `MatchFlowHarness` (siblings under
`leyline.conformance`) for:

- match boot (`connectAndKeep`, `ConnectionState`, `MatchSession` instantiation,
  seed Full GSM via `GsmSnapshot` snapshotting + `StateMapper.buildFromSnapshot`,
  `bridge.submitKeep`, `session.onMulliganKeep`)
- sink + accumulator wiring (`ListMessageSink`, `ClientAccumulator`,
  `ValidatingMessageSink`, `drainSink` with auto-respond to
  `OptionalActionMessage`)
- action submitters (one wrapper per `session.onXxx` — `playLand`,
  `castCreature`, `passPriority`, `declareAllAttackers` / `submitAttackers`,
  `declareNoBlockers`, `selectTargets`, `respondToScry`, etc.)
- state accessors (`turn()`, `isGameOver()`, `accumulator.actions`)

If the simclient ever moves out of test source, only the essential ~400 lines
of `MatchFlowHarness` need to come along — the conformance-test ergonomics
(`castSpellUntil` lambdas, `toggleAttackers`, etc.) can stay in tests.

## Player.log → scry-ts shape gotchas

scry-ts pattern-matches GRE message-type strings literally. Two translations
in `PlayerLogWriter.translateToScryFormat`:

1. **Top-level message-type prefix.** Leyline's proto enum values are
   `GameStateMessage_695e`, `ConnectResp_695e`, etc. scry-ts expects
   `GREMessageType_GameStateMessage`, `GREMessageType_ConnectResp`. The
   writer rewrites these via an allowlist of GRE message names. Add to the
   allowlist when a new prompt type needs to round-trip through scry-ts.
2. **Synthetic `ConnectResp` at game start.** scry-ts's `detectGames` uses
   `GREMessageType_ConnectResp` as the game boundary marker. The simclient
   skips lobby + handshake, so `emitGameStart` writes a fake ConnectResp on
   first bundle write. Without this, every simclient log shows up as
   "active, 3 GSMs" instead of being walked end-to-end.

## Adding a new built-in deck

1. Pick cards that have YAML fixtures in `matchdoor/src/test/resources/test-cards/`
   — `TestCardRegistry.ensureDeckRegistered` will fail loudly if a card isn't
   there. Run `just card-grp "<name>"` to verify card name → grpId mapping.
2. Add an entry to `builtinDecks` in `SimClientBatchTest.kt`:
   `"my-deck" to "20 Mountain\n4 Lightning Bolt\n..."`.
3. Run `just simclient my-deck 1..5` to verify games complete with
   `gameOver=true` and reasonable iteration counts.

For decks under `data/decks/<name>.txt` no code change is needed — pass
`just simclient "Auras"` and `resolveDeck` will load the file.

## Adding a new responder branch

When a new prompt type stalls greedy games (visible in batch output as
hitting the 200-iter same-turn stall), wire a responder:

1. Add a `when` arm to `SimClientDriver.takeOneStep` matching the
   `GREMessageType` enum.
2. Build the response via `MatchFlowHarness`'s helpers (most prompts already
   have a wrapper) or via the proto builders in `leyline.conformance.ProtoDsl`.
3. Add the message-type string to `PlayerLogWriter.MESSAGE_TYPE_NAMES` if it
   isn't already there — otherwise scry-ts won't recognize it on the
   downstream parse.
4. Add the type to the prompt-detection list in `SimClientDriver.isPrompt`
   so the lookup in `lastPromptMessage` includes it.

## Known limits

- **Greedy only.** Random and Forge-AI policies parked behind a swappable
  `Policy` interface in the design doc; not implemented yet.
- **`ClientAccumulator` is thin.** ~157 lines; no persistent-annotation
  lifecycle, no `ObjectIdChanged` id-chain following, no proto3 deep-merge.
  Sufficient for the driver's `actions: ActionsAvailableReq` reads. Once the
  driver wants board-aware decisions (smart targeting, mulligan-to-N, mana-
  curve sequencing), port the relevant pieces from scry-ts's accumulator.
- **No client handshake / lobby.** Games start at a `MatchSession` with two
  pre-configured seats. Won't catch FD lobby bugs or Netty framing issues.
- **One simclient seat.** Seat 1 is the simclient; seat 2 is Forge's
  `LobbyPlayerAi` server-side. The opposing seat's prompts never traverse
  the in-process channel — flip with `--simclient-seat 2` when that flag is
  added (currently always seat 1).
- **Static `MyRandom` race.** Forge's RNG is JVM-static. The `:simclient`
  task forces serial execution (`maxParallelForks = 1`).
- **`InteractivePromptBridge` errors after concede.** After teardown, Forge
  AI may still call into the bridge and hit a torn-down session. Logged as
  ERROR but functionally harmless. Suppress by adding a "game over already"
  guard at the bridge entry if the noise becomes a problem.

## Where to look next

- Bead `leyline-l5vd` — design + cycle digests.
- Post-process tooling treats `simclient` games as a peer of the `leyline`
  source tag for bracketing / conformance comparisons.
