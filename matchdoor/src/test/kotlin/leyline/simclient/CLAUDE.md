# simclient

Synthetic GRE-log generator. Drives both seats of a leyline match in-process
(`MatchSession` + `GameBridge` + Forge engine), emits Player.log-shaped output
under `matchdoor/build/simclient/`, and tags each game `source: simclient` via
a `<log>.meta.json` sidecar so scry-ts can ingest the result alongside other
saved games without polluting reference data.

The broad runner is a standalone Gradle JavaExec tool (`:matchdoor:simclient`),
not a Kotest suite. It still compiles against testkit internals for now; row
timeouts, exceptions, and validation failures are written as stats data unless
`--strict` is passed. The regular gate excludes the slow E2E tests.

For the fixed-seed debugging loop, failure taxonomy, and deck-vs-puzzle guidance,
read `docs/simclient-iteration.md`.

If iterating on simclient tooling, read bead `leyline-jy2g` and the titles of
its children first.

Known broad deck-scout findings live in beads with label `simclient-scout`.
Before filing another scout bug, run
`bd query 'label=simclient-scout AND status=open'` and dedupe by card, ability,
or fingerprint.

When filing or updating a `simclient-scout` bead for another agent, make it
agent-ready before handoff:

- Include one copy-paste repro command from the leyline worktree with all env
  vars: `LEYLINE_CARD_DB`, `SIMCLIENT_POLICY`, `SIMCLIENT_CONTINUE_ON_EXCEPTION`,
  deck/opponent/seed, timeout, and Gradle task.
- State the expected current bad stats row: `completionReason`, exception stack
  top/message, `simFindings`, validation violation, or stalled prompt/fingerprint.
- Name the committed deck files or commit that introduced them; do not rely only
  on a temp JSONL artifact.
- Add the likely code seam when known, e.g. mapper/driver/prompt ledger class and
  function names.
- Keep aggregate artifact paths in notes for evidence, but do not make them the
  only way to reproduce.

## Prioritizing Policy Seams

Improve prompt policy where it increases scout signal, not where it makes the AI
smarter in the abstract. Rank seams by:

- False-bug reduction: stale prompts, no-pending submits, no-progress loops, and
  timeout races come first because they pollute every later run.
- Prompt coverage: a dumb valid answer beats a clever strategy; unblock prompt
  shapes that currently end runs or force cleanup.
- Phase/card reach: prefer policies that expose more turns and mechanics
  (`OptionalAction`, `SelectTargets`, `CastingTimeOptions`, `PayCosts`) over
  combat refinement unless combat dominates failures.
- Repeated fingerprints: if the same decision/target fingerprint recurs, fix the
  policy/ledger before filing many card-specific bugs.
- Latency: if Forge-AI consult time correlates with stale pending windows, add
  gating/caching or fall back earlier before adding more advisor calls.

After a broad scout, bucket non-natural rows by `stalledPrompt`,
`simFindings.kind`, `decisionKind`, and the last `promptProgressSamples` entry.
Pick the most common prompt seam that is answerable by policy.

## What's in this directory

- `SimClientDriver.kt` — loop/orchestration, prompt retirement, outcome telemetry.
- `SimPromptLedger.kt` — prompt lifecycle: active prompt selection, handled/retired
  prompt ids, prompt-bound AAR payloads, stall fingerprints.
- `SimPromptPolicy.kt` — greedy + Forge-AI policy. Policies return `SimDecision`;
  they do not submit directly.
- `ForgeAiPromptAdapter.kt` — prompt-specific Forge-AI translations. Keeps
  adapter code out of the driver and falls back to greedy when AI has no answer.
- `SimDecision.kt` — decision model and submitter. The submitter owns all
  `MatchFlowHarness` response calls.
- `ActionAttemptLedger.kt` — per-turn AAR attempt/skip/outcome tracking.
- `ForgeAiPolicy.kt` — Forge-AI advisor. Builds a parallel `PlayerControllerAi`
  consulted through prompt adapters. **Not** registered as the player's actual
  controller (see "Forge-AI advisor — load-bearing rules" below for why).
- `GameLogCapture.kt` — programmatic logback `ListAppender`, attached to the
  root logger for the duration of one game. Drained at game end and folded
  into `GameStats.warnsByLogger` / `errorsByType`.
- `PlayerLogWriter.kt` — formats outbound GRE bundles into Player.log lines
  with the type translations scry-ts expects, and emits `.meta.json` sidecars
  via `writeSimClientSidecar`.
- `SimClientE2ETest.kt` — two fast smoke tests (mono-Forest mirror, vanilla-
  creatures mirror). Verifies the pipeline.
- `src/main/kotlin/leyline/tooling/simclient/` — standalone CLI, config
  parsing, matrix expansion, row/artifact naming, stats models, stats JSON,
  row watchdog, summary, and optional scry ingest.
- `src/main/kotlin/leyline/simclient/` — headless gameplay driver, prompt
  policies, ledgers, Player.log writer, and route/progress telemetry.
- `src/main/kotlin/leyline/tooling/headless/MatchFlowHarness.kt` — main-source
  headless `MatchSession` harness shared by the tool and session tests.

Slow E2E test files are tagged `leyline.SimClientTag` (see `Tags.kt`) so they
stay out of `:testGate`. The broad matrix tool is not tag-driven.

## How to invoke

**Recommended — `just simclient` recipe** runs the standalone tool and copies
logs into `~/.scry/games/`:

```bash
# Required for deck files / arbitrary decks: point at the local card DB.
# Built-in fixture decks such as forest-only and bears do not need this.
export LEYLINE_CARD_DB="$HOME/Library/Application Support/com.wizards.mtga/Downloads/Raw/Raw_CardDatabase_<hash>.mtga"

# Defaults: 4 decks × 5 seeds, maxTurns=25, 120s per-game watchdog
just simclient

# Custom matrix
just simclient mono-r-burn 1..50           # 50 burn games
just simclient "bears,mono-r-burn" 1..20   # 40 mixed games
just simclient "Auras,Black aggro" 1,2,3   # 6 games using data/decks/*.txt

# Pick the policy (greedy default; forge-ai consults Forge AI as advisor)
SIMCLIENT_POLICY=forge-ai just simclient bears 1..5

# Scout mode is the default: record exception stats instead of aborting the batch
SIMCLIENT_CONTINUE_ON_EXCEPTION=true just simclient bears 1..20

# Fixed seat-2 deck instead of mirror matches
SIMCLIENT_OPPONENT_DECK="Pauper Blue Tempo" SIMCLIENT_POLICY=forge-ai \
  just simclient "Green stompy,Pauper Red Burn" 1..10
```

Add a custom deck by saving Arena/export-style deck text as
`data/decks/<name>.txt`, then pass the basename without `.txt`:

```bash
just arena-ts deck get 'https://www.mtgtop8.com/event?e=86014&d=853241&f=ST' \
  --file data/decks/<name>.txt
just arena-ts deck random --count 5 --dir data/decks
just simclient "My deck" 1..5
```

`deck get` also accepts the `d=` id from a MTGTop8 URL, but prefer pasting the
full URL unless the id is already in hand.

If `LEYLINE_CARD_DB` is missing, deck-file rows fail before running. Use the
same Raw card database path the server uses; built-in fixture-only smoke tests
do not need it.

**Direct gradle** (no ingest unless `--ingest-scry` is passed):

```bash
SIMCLIENT_DECKS=mono-r-burn SIMCLIENT_SEEDS=1..20 \
  ./gradlew :matchdoor:simclient
ls matchdoor/build/simclient/   # *.log + *.meta.json
```

Useful tool flags:

```bash
./gradlew :matchdoor:simclient --args="--decks mono-r-burn --seeds 1..20 --resume"
./gradlew :matchdoor:simclient --args="--decks mono-r-burn --seeds 1..100 --shard-index 0 --shard-count 4"
./gradlew :matchdoor:simclient --args="--puzzles bolt-face.pzl --seeds 42 --strict"
```

Useful direct-gradle knobs, all accepted as env vars or lower-case system
properties (`SIMCLIENT_OPPONENT_DECK` ↔ `-Dsimclient.opponent.deck`):

| Knob | Default | Meaning |
|---|---|---|
| `SIMCLIENT_DECKS` | `forest-only,bears,mono-g-curve,mono-r-burn` | Seat-1 deck matrix; built-ins or `data/decks/<name>.txt` |
| `SIMCLIENT_OPPONENT_DECK` | unset | Seat-2 fixed deck; unset means mirror |
| `SIMCLIENT_SEEDS` | `7,13,42,99,314` | Comma list or inclusive range, e.g. `1..20` |
| `SIMCLIENT_POLICY` | `greedy` | `greedy` or `forge-ai` |
| `SIMCLIENT_MAX_TURNS` | `25` | Unresolved-game cap before cleanup concede |
| `SIMCLIENT_GAME_TIMEOUT_SECONDS` | `120` | Wall-clock watchdog per game |
| `LEYLINE_CARD_DB` | required for deck files | Raw card DB path for non-fixture decks |

**Single E2E smoke** (fastest, ~10s, no env):

```bash
./gradlew :matchdoor:simclientSmoke
./gradlew :matchdoor:test --tests "leyline.simclient.SimClientE2ETest"
```

## Output

Each game produces three per-row files plus a run summary:

```
matchdoor/build/simclient/<deck>[-vs-<opponent>]-s<seed>.log         # Player.log-shaped JSON blocks
matchdoor/build/simclient/<deck>[-vs-<opponent>]-s<seed>.meta.json   # provenance sidecar (scry-ts shape)
matchdoor/build/simclient/<deck>[-vs-<opponent>]-s<seed>.stats.json  # per-game telemetry (see Telemetry below)
matchdoor/build/simclient/summary.json                              # run-level class counts
```

Sidecar shape (matches scry-ts `GameMeta`):

```json
{
  "cards": [],
  "tags": ["simclient", "deck:mono-r-burn", "opponent:Pauper Blue Tempo", "seed:42"],
  "notes": [],
  "provenance": {
    "source": "simclient",
    "confidence": "explicit",
    "matchId": "simclient-mono-r-burn-vs-Pauper-Blue-Tempo-s42",
    "eventName": "simclient-mono-r-burn-vs-Pauper Blue Tempo",
    "recordedAt": "2026-05-01T..."
  }
}
```

`just simclient` copies the `.log` and `.meta.json` into `~/.scry/games/` so
scry-ts picks them up. The `.stats.json` stays in the build dir and is the
source artifact for Trackio importers.

Output filenames are file-safe labels derived from deck names. Fixed-opponent
runs include `-vs-<opponent>` to avoid overwriting mirror runs for the same
deck/seed.

## Iteration Loop For Mechanic Fixes

Use simclient as the fast deck-level bracket after a focused SessionTest passes.
Keep the loop small and evidence-driven:

1. Start with the smallest deck pair that exercises the mechanic. Prefer one
   built-in deck plus one seed range, e.g. `just simclient "my-deck" 1..10`.
2. Inspect `.stats.json` first. A useful run has `gameOver=true`, low/no WARNs,
   and no repeated prompt stall. If stats are noisy, fix the loop before reading
   individual logs.
3. Use `scry` only after ingest: `scry games --source simclient`, then
   `scry trace "Card Name" --source simclient` / `scry prompts --source simclient`.
   The trace should confirm the same prompt and annotation shape the SessionTest
   asserted.
4. When a run stalls, reduce to one deck + one seed and reproduce with
   `SIMCLIENT_DECKS=<deck> SIMCLIENT_SEEDS=<seed> ./gradlew :matchdoor:simclient`.
   Patch the smallest failing boundary: prompt response, action translator,
   event capture, or annotation emission.
5. Add or update the focused SessionTest for the root cause before widening the
   simclient matrix again. Simclient proves deck-level survivability; SessionTest
   pins the behavior.

Don't debug broad matrices by eyeballing dozens of logs. Collapse to one seed,
make the failure deterministic, patch, then widen.

## Policy modes — what each prompt does

`SIMCLIENT_POLICY=greedy` (default) or `forge-ai`. Forge-AI mode falls through
to greedy on prompts not yet wired through an AI translator or when the advisor
returns no useful choice.

| Prompt | greedy | forge-ai |
|---|---|---|
| `MulliganReq` | always keep (via `connectAndKeep`) | same |
| `ActionsAvailableReq` | prompt-bound land then first castable spell, else pass | AI picks from the active prompt's AAR actions; falls through to greedy on null / no-castable |
| `DeclareAttackersReq` | declare all, then submit | AI chooses attackers; falls through to greedy on null |
| `DeclareBlockersReq` | submit empty | AI mutates live `Combat`; emits the resulting blocker→attacker map |
| `SelectTargetsReq` | first legal target across slots | greedy (translator not yet wired) |
| `SelectNReq` | choose minimum required ids | AI handles hand-card choices; greedy otherwise |
| `SearchReq` | choose up to `maxFind` from sought ids | greedy |
| `PayCostsReq` | choose minimum required non-mana cost ids | greedy |
| `GroupReq` | scry-style top-all | greedy |
| `CastingTimeOptionsReq` | decline all (`ctoId=0`) | greedy |
| `NumericInputReq` | submit bounded small value | greedy |
| `AssignDamageReq` | echo server-prefilled damage assignments | greedy |
| `IntermissionReq` | pass | greedy |

Anything outside the table falls back to `passPriority`. Adding a new AI
translator: see "Forge-AI advisor" below.

Two safety nets keep games terminating:

- **Same-turn iteration stall**: if no turn advance for 200 iterations, call
  `session.onConcede()` so the engine emits a proper game-over sequence.
- **Cleanup concede**: if the loop exits while the game is still active
  (max-turns hit, no-progress break, iter cap), concede + drain so every
  game produces `gameOver=true`.
- **Wall-clock watchdog**: if a game exceeds `SIMCLIENT_GAME_TIMEOUT_SECONDS`,
  snapshot live harness state (`turn`, `totalMessages`, prompt histogram) before
  cancellation and fail the task. Treat `wall-timeout` as unresolved, not as a
  game result.

`max-turns`, `turn-stall`, `no-progress`, `iter-cap`, `cleanup`, and
`wall-timeout` are unresolved outcomes. They are useful triage signals, not
win/loss data.

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
3. Wire the translator through `ForgeAiPromptAdapter` / `ForgeAiPromptPolicy` so
   `SimClientDriver` stays orchestration-only.
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
- completion attribution — `completionReason`, `cleanupConcede`
- prompt lifecycle — `promptRetiredByReason`, `stalledPrompt`, `stalledFingerprint`
- prompt route audit — `promptRequestsByKind`, `promptRequestSamplesByKind`, `promptRouteFindings`
- action attempts — `decisionOutcomes`, `actionAttemptsByType`,
  `noPendingByDecision`, `skippedAlreadyTried`
- timing attribution — `connectMs`, `stepTotalMs`, `flushTotalMs`,
  `autoPassTotalMs`, `policyTotalMsByPrompt`, `submitTotalMsByDecision`

`noPendingByDecision` is the structured form of the old
`ActionPerformer: PerformActionResp but no pending action` smell. A high
`pre-submit:ActionsAvailableReq_695e` count means the simclient saw stale AARs
after the server had already consumed the priority window; a high
`pass-priority` count means the submitter guard prevented stale pass spam from
reaching `ActionPerformer`.

Adding a new failure mode: think about whether it should be a `GameStats`
field. Anything you want attributable to `(deck × seed × policy)` belongs
here. Anything you want to grep across many runs goes through the
`.stats.json` sidecar (serialised by `statsToJson` in `src/main/kotlin/leyline/tooling/simclient/SimClientStatsJson.kt`).

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

1. For fixture-only smoke tests, pick cards that have YAML fixtures in
   `matchdoor/src/test/resources/test-cards/` — `TestCardRegistry.ensureDeckRegistered`
   will fail loudly if a card isn't there. For `:matchdoor:simclient` deck-file
   runs, SQLite-backed resolution handles arbitrary installed cards.
2. Add an entry to `builtinDecks` in `src/main/kotlin/leyline/tooling/simclient/SimClientRows.kt`:
   `"my-deck" to "20 Mountain\n4 Lightning Bolt\n..."`.
3. Run `just simclient my-deck 1..5` to verify games complete with
   `gameOver=true` and reasonable iteration counts.

For decks under `data/decks/<name>.txt` no code change is needed — pass
`just simclient "Green stompy" 1..5` and `resolveDeck` will load the file.

Use `SIMCLIENT_OPPONENT_DECK="<name>"` when you need a fixed seat-2 deck. If
unset, `GameBridge.start` receives a single deck and seat 2 mirrors seat 1.

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
| `completionReason: max-turns` | long but progressing game; cleanup concede produced game-over |
| `completionReason: wall-timeout` | wall-clock watchdog fired; inspect `turn`, `totalMessages`, prompt histogram to distinguish hang from slow progress |
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

For the GRE trace itself: `<deck>[-vs-<opponent>]-s<seed>.log` is Player.log-
shaped and parseable by anything that reads Player.log (the same classifier the
test suite uses).

## Known limits

- **AI translators incomplete.** `ActionsAvailableReq`, `DeclareAttackersReq`,
  `DeclareBlockersReq`, and hand-card `SelectNReq` have Forge-AI translators
  under `SIMCLIENT_POLICY=forge-ai`. `SelectTargets` / `OptionalAction` /
  `CastingTimeOptions` / `NumericInput` / `Group` / `AssignDamage` still use
  greedy. Each is a small translator — see "Forge-AI advisor" above. Greedy CTO
  declines optional costs unless `SIMCLIENT_ACCEPT_OPTIONAL_COSTS=true` is set;
  greedy NumericInput picks a small bounded value (currently max 3) for X-cost
  coverage without exploding board states.
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
