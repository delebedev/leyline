# simclient

Synthetic GRE-log generator. Drives both seats of a leyline match in-process
(`MatchSession` + `GameBridge` + Forge engine), emits Player.log-shaped output
under `matchdoor/build/simclient/`, and tags each game `source: simclient` via
a `<log>.meta.json` sidecar so scry-ts can ingest the result alongside other
saved games without polluting reference data.

Lives in test source — opt-in via the dedicated Gradle task; the regular gate
excludes it.

## What's in this directory

- `SimClientDriver.kt` — the loop: hybrid greedy + Forge-AI responder dispatcher.
- `ForgeAiPolicy.kt` — Forge-AI advisor. Builds a parallel `PlayerControllerAi`
  consulted on `ActionsAvailableReq` and `DeclareBlockersReq`. **Not** registered
  as the player's actual controller (see "Forge-AI advisor — load-bearing rules"
  below for why).
- `GameLogCapture.kt` — programmatic logback `ListAppender`, attached to the
  root logger for the duration of one game. Drained at game end and folded
  into `GameStats.warnsByLogger` / `errorsByType`.
- `PlayerLogWriter.kt` — formats outbound GRE bundles into Player.log lines
  with the type translations scry-ts expects, and emits `.meta.json` sidecars
  via `writeSimClientSidecar`.
- `SimClientE2ETest.kt` — two fast smoke tests (mono-Forest mirror, vanilla-
  creatures mirror). Verifies the pipeline.
- `SimClientBatchTest.kt` — env-driven `(deck × seed)` matrix with stats +
  prompt-histogram aggregation. Reads decks from `data/decks/<name>.txt` or
  the built-in deck table. Writes per-game `.stats.json` sidecar.

All files are tagged `leyline.SimClientTag` (see `Tags.kt`) so they only
run under the `:matchdoor:simclient` Gradle task, never under `:testGate`.

## How to invoke

**Recommended — `just simclient` recipe** clears prior outputs, runs the
batch, copies logs into `~/.scry/games/`:

```bash
# Required for arbitrary decks: point at the local Arena card DB.
export LEYLINE_CARD_DB="$HOME/Library/Application Support/com.wizards.mtga/Downloads/Raw/Raw_CardDatabase_<hash>.mtga"

# Defaults: 4 decks × 5 seeds + Simple test deck × 3 seeds
just simclient

# Custom matrix
just simclient mono-r-burn 1..50           # 50 burn games
just simclient "bears,mono-r-burn" 1..20   # 40 mixed games
just simclient "Auras,Black aggro" 1,2,3   # 6 games using data/decks/*.txt

# Pick the policy (greedy default; forge-ai consults Forge AI as advisor)
SIMCLIENT_POLICY=forge-ai just simclient bears 1..5
```

Add a custom deck by saving Arena/export-style deck text as
`data/decks/<name>.txt`, then pass the basename without `.txt`:

```bash
just simclient "My deck" 1..5
```

If `LEYLINE_CARD_DB` is missing, the batch fails before running. Use the same
Raw card database path the server uses; built-in fixture-only smoke tests do
not need it, but deck-file runs do.

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

Each game produces three files:

```
matchdoor/build/simclient/<deck>-s<seed>.log         # Player.log-shaped JSON blocks
matchdoor/build/simclient/<deck>-s<seed>.meta.json   # provenance sidecar (scry-ts shape)
matchdoor/build/simclient/<deck>-s<seed>.stats.json  # per-game telemetry (see Telemetry below)
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

`just simclient` copies the `.log` and `.meta.json` into `~/.scry/games/` so
scry-ts picks them up. The `.stats.json` stays in the build dir.

## Policy modes — what each prompt does

`SIMCLIENT_POLICY=greedy` (default) or `forge-ai`. Forge-AI mode falls through
to greedy on prompts not yet wired through an AI translator.

| Prompt | greedy | forge-ai |
|---|---|---|
| `MulliganReq` | always keep (via `connectAndKeep`) | same |
| `ActionsAvailableReq` | land then first castable spell, else pass | AI picks; falls through to greedy on null / no-castable |
| `DeclareAttackersReq` | declare all, then submit | greedy (translator not yet wired) |
| `DeclareBlockersReq` | submit empty | AI mutates live `Combat`; emits the resulting blocker→attacker map |
| `SelectTargetsReq` | first legal target across slots | greedy (translator not yet wired) |
| `GroupReq` | scry-style top-all | greedy |
| `CastingTimeOptionsReq` | decline all (`ctoId=0`) | greedy |
| `NumericInputReq` | submit `0` | greedy |
| `IntermissionReq` | pass | greedy |

Anything outside the table falls back to `passPriority`. Adding a new AI
translator: see "Forge-AI advisor" below.

Two safety nets keep games terminating:

- **Same-turn iteration stall**: if no turn advance for 200 iterations, call
  `session.onConcede()` so the engine emits a proper game-over sequence.
- **Cleanup concede**: if the loop exits while the game is still active
  (max-turns hit, no-progress break, iter cap), concede + drain so every
  game produces `gameOver=true`.

## Forge-AI advisor — load-bearing rules

Forge AI sits **outside** the engine's controller chain, NOT as the player's
registered controller. Three rules — break any one and the harness regresses:

1. **Don't register `PlayerControllerAi` as the player's controller.**
   Leyline's bridged `PlayerController` (extends `PlayerControllerHuman`) MUST
   stay registered — that's what emits GRE prompts and blocks on response
   futures. Swap it out and the simclient stops producing prompt traces.

2. **Wrap every Forge-AI call in `Player.runWithController(proc, aiController)`.**
   Forge AI internals (e.g. `AttachAi.attachToCardAIPreferences`) cast
   `player.getController()` to `PlayerControllerAi`. Outside that scope you get
   `ClassCastException`. The runWithController helper layers a timestamp-MAX
   controller and removes it in `finally`.

3. **Skip AI consult on Pass-only AARs** (`hasCastableActionsInAar()`).
   Forge AI's search costs 50-200ms; during that window leyline's auto-pass
   loop consumes the priority window; the subsequent submit lands "no pending
   action", causing a state resync that pollutes the trace with a spurious
   GSM. Real signal showed 200+ "ActionPerformer no pending action" warns per
   long game without this guard.

Adding a new AI translator (`SelectTargets`, `OptionalAction`, etc.):

1. Add a method on `ForgeAiPolicy` that calls the corresponding
   `aiController.<method>(…)` under `seatPlayer.runWithController { ... }`.
2. Read the resulting Forge-side decision (a `SpellAbility`, a mutated
   `Combat`, a chosen card list, etc.) and translate it into a GRE response.
3. Wire a `consultForgeAiFor<X>()` helper in `SimClientDriver` that calls the
   translator, bumps the per-prompt counters via `bumpConsulted/bumpChose`,
   and submits via the existing `harness.session.onXxx` paths.
4. Greedy fallback stays — when AI returns null, the existing greedy branch
   handles it.

The translator is mostly a mirror-image of what `leyline.bridge.forge.PlayerController`
does in the opposite direction (network → Forge). Reuse instance-id resolution
via `harness.bridge.getOrAllocInstanceId(ForgeCardId(card.id))`.

## Telemetry

`GameStats` (returned by `SimClientDriver.runOneGame`) carries:

- timing — `durationMs`
- AI activity — `aiConsulted`, `aiChose`, plus `aiConsultedByPrompt` /
  `aiChoseByPrompt` keyed by GRE prompt name
- log signal — `warnsByLogger`, `errorsByType` populated by `GameLogCapture`
- terminal flags — `gameOver`, `hitIterCap`

Adding a new failure mode: think about whether it should be a `GameStats`
field. Anything you want attributable to `(deck × seed × policy)` belongs
here. Anything you want to grep across many runs goes through the
`.stats.json` sidecar (serialised by `statsToJson` in `SimClientBatchTest`).

`GameLogCapture` works because simclient is serial (`maxParallelForks = 1`).
If parallel execution ever lands, this approach needs per-thread MDC keying.

## Dependencies — what we lean on from the test tree

The driver is thin because it leans on `MatchFlowHarness` from
`leyline.testkit` for:

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
of `MatchFlowHarness` need to come along — the test ergonomics
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
`just simclient "Simple test"` and `resolveDeck` will load the file.

## Adding a new responder branch

When a new prompt type stalls greedy games (visible in batch output as
hitting the 200-iter same-turn stall), wire a responder:

1. Add a `when` arm to `SimClientDriver.takeOneStep` matching the
   `GREMessageType` enum.
2. Build the response via `MatchFlowHarness`'s helpers (most prompts already
   have a wrapper) or via the proto builders in `leyline.testkit.ProtoDsl`.
3. Add the message-type string to `PlayerLogWriter.MESSAGE_TYPE_NAMES` if it
   isn't already there — otherwise scry-ts won't recognize it on the
   downstream parse.
4. Add the type to the prompt-detection list in `SimClientDriver.isPrompt`
   so the lookup in `lastPromptMessage` includes it.

## Reading a run — what each signal means

Per-game `<deck>-s<seed>.stats.json` carries the truth. Quick legend for
interpreting it without re-deriving:

**Healthy game** — `gameOver: true`, `hitIterCap: false`, `aiChose / aiConsulted`
ratio in the 20-50% range, `warnsByLogger` mostly empty, `errorsByType` ≤ 1
(see "Expected residual noise" below).

**Symptom → likely cause:**

| signal | what to suspect |
|---|---|
| `hitIterCap: true` | engine never reached game-over; usually a prompt the responder doesn't handle, or an edge case in mana/cost resolution |
| `iter` ≈ 200 with `turn ≤ 2` | turn-1 stall — see "Same-turn 200-iter stall" below |
| `aiChose / aiConsulted` < 5% | most AAR consults yield no playable spell. Mana-screw, defensive AI position, OR a deck where Forge AI dislikes the available actions |
| `warnsByLogger["leyline.match.ActionPerformer"]` ≥ 10 | the `no pending action` race is firing — should be 0 with the `hasPendingAction()` guard. If it's back, the guard regressed |
| `warnsByLogger["leyline.game.mapping.ZoneMapper"]` > 0 | "no snapshot for hand/graveyard card" — a card was in a Forge zone but not in the snapshot. Real bug; investigate via the per-game `.log` |
| `warnsByLogger["leyline.game.annotations.AnnotationOrderEnforcer"]` > 0 | annotation ordering invariant violated — usually a real protocol-shape bug. Worth filing |
| `errorsByType["leyline.game.snapshot.GrpIdResolver"]` > 0 | a card name in the deck doesn't resolve to a client grpId. Either the card is Forge-only (e.g. "Ferocious Charge") or the client DB is older than the deck. **Dedup is JVM-static** — in a batch run the same missing name appears in only the first game's stats; absence in games 2..N does NOT mean it resolved. Call `GrpIdResolver.resetReportedMissingCardNamesForTest()` between games if per-game attribution matters |
| `errorsByType` includes `InterruptedException` / `CancellationException`, count = 1 | **expected residual noise** — see below |

**Aggregate (across all games in a run, from `SUMMARY.md`):**

`Race fingerprint` table — each row is `(deck × seed)`. Healthy = all `0` race
warns. Anything ≥ 10 means the `hasPendingAction()` guard regressed for that
play pattern.

`AI consult breakdown by prompt type` — hit-rate column. Anything < 10% means
either AI is declining a lot (defensive deck) or the consult-skip predicate
isn't catching cleanup-window prompts for that type.

### Expected residual noise (don't chase these)

- **`InterruptedException` (or `CancellationException`), exactly 1 per game**:
  fired when `GameLoopController.shutdown` calls `bridge.cancelPending()` on a
  prompt that was in-flight at game-end. The bridge catches it, returns the
  default to the engine, no spurious GRE emitted. Bridge logs ERROR but the
  trace is unaffected. Games that end on plain combat damage with nothing on
  the stack avoid it.
- **`SimClientDriver` warns, count = 1** per game where the no-progress break
  fires (after the `hasPendingAction` race fix this should be rare).
- **`leyline.match.AutoPassEngine: autoPass: no pending action, waiting for priority`**,
  occasional — engine's auto-pass loop ran a tick with nothing pending. Benign.

### When to drill into a per-game `.log`

If `warnsByLogger` shows ZoneMapper / AnnotationOrderEnforcer / a leyline
class you don't recognise — the warn line in the gradle stdout (or the test
report XML at `matchdoor/build/test-results/simclient/TEST-*.xml`) carries
the message text with card / annotation context. Stats sidecar only counts;
text is in the gradle report.

For the GRE trace itself: `<deck>-s<seed>.log` is Player.log-shaped and
parseable by anything that reads Player.log (the same classifier the test
suite uses).

## Known limits

- **AI translators incomplete.** Only `ActionsAvailableReq` and
  `DeclareBlockersReq` are AI-driven under `SIMCLIENT_POLICY=forge-ai`.
  `SelectTargets` / `OptionalAction` / `CastingTimeOptions` / `NumericInput` /
  `Group` / `AssignDamage` still use greedy. Each is a small translator —
  see "Forge-AI advisor" above. **Smarter-greedy** is in place for CTO (accept
  first non-zero option) and NumericInput (pick max) so kicker / Bargain /
  X-cost paths get exercised.
- **Same-turn 200-iter stall.** When the engine sits on the same turn for
  more than 200 driver iterations, the simclient calls `session.onConcede()`
  to end the game cleanly. Triggers when the AI / engine is genuinely stuck
  (e.g. mana-screwed, prompt the responder doesn't handle). Visible in stats
  as `iter ≈ 200, turn ≤ 2, gameOver = true` (the concede produced game-over).
  Different from no-progress break — that one was eliminated by the
  `hasPendingAction()` guard.
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
