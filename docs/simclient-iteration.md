---
summary: "Fixed-seed simclient loop for turning prompt, advisor, and engine stalls into small reproducible fixes."
read_when:
  - "debugging simclient stalls, prompt loops, or advisor regressions"
  - "adding simclient telemetry or interpreting simclient stats"
  - "deciding whether to use a deck run or puzzle fixture"
---
# Simclient Iteration Playbook

Use simclient to turn vague gameplay failures into a small, repeatable loop:

1. Reproduce with fixed decks and seeds.
2. Classify the failure from stats before reading long logs.
3. Patch one seam.
4. Rerun the exact seeds.
5. Broaden only after the original failure fingerprint collapses.

Prefer deck runs for discovery. Use puzzles after the failure class is concrete enough to make a small deterministic fixture.

## Run Shapes

Single known-bad seed:

```bash
SIMCLIENT_POLICY=forge-ai \
SIMCLIENT_OPPONENT_DECK="Aggro Sample" \
SIMCLIENT_MAX_TURNS=200 \
SIMCLIENT_GAME_TIMEOUT_SECONDS=900 \
just simclient "Control Sample" 3
```

Small seed range after a fix:

```bash
SIMCLIENT_POLICY=forge-ai \
SIMCLIENT_OPPONENT_DECK="Aggro Sample" \
SIMCLIENT_MAX_TURNS=200 \
SIMCLIENT_GAME_TIMEOUT_SECONDS=900 \
just simclient "Control Sample" 1..5
```

Scout mode for broad deck sweeps keeps going after per-game exceptions and writes `completionReason=exception` stats rows. This is the standalone tool default; use `--strict` when a matrix is acting as a regression gate:

```bash
SIMCLIENT_CONTINUE_ON_EXCEPTION=true just simclient "Deck A,Deck B,Deck C" 1..20
```

Fast tool wiring smoke:

```bash
./gradlew :engine:simclientSmoke
```

Differential policy audit smoke:

```bash
SIMCLIENT_DECKS="forest-only,bears" \
SIMCLIENT_SEEDS=1..2 \
SIMCLIENT_MAX_TURNS=8 \
SIMCLIENT_GAME_TIMEOUT_SECONDS=30 \
  ./gradlew :engine:simRef \
    -PsimrefArgs="--out-dir engine/build/sim-ref-shadow-smoke"

SIMCLIENT_DECKS="forest-only,bears" \
SIMCLIENT_SEEDS=1..2 \
SIMCLIENT_POLICY=shadow-ai \
SIMCLIENT_MAX_TURNS=8 \
SIMCLIENT_GAME_TIMEOUT_SECONDS=30 \
  ./gradlew :engine:simclient \
    -PsimclientArgs="--out-dir engine/build/simclient-shadow-smoke"

./gradlew :engine:simDiffReport \
  -PsimDiffReportArgs="--ref-dir engine/build/sim-ref-shadow-smoke --cand-dir engine/build/simclient-shadow-smoke --out-dir engine/build/sim-diff-shadow-smoke"
```

Read `coverage-report.md` as a priority list, not a verdict. Prefer aggregate
coverage gaps with high `healthyRows` and low `issueRows`. Use advisor-gap
`category` and `sample` fields to choose the next small policy seam.

Resume or shard a sweep without changing row identity:

```bash
./gradlew :engine:simclient --args="--decks 'Deck A,Deck B' --seeds 1..200 --resume"
./gradlew :engine:simclient --args="--decks 'Deck A,Deck B' --seeds 1..200 --shard-index 0 --shard-count 4"
```

Use an absolute `--out-dir` for ad hoc inspection from Gradle until relative
path handling is tightened; the default `engine/build/simclient/` path is
safe through `just simclient`.

Quarantine known-bad cards during discovery without editing deck files:

```bash
SIMCLIENT_EXCLUDE_CARDS="Tinybones Joins Up,102468" \
  just simclient "Deck A,Deck B" 1..20
```

`data/simclient/quarantine.txt` is loaded by default when present. Put one exact
card name or numeric grpId per line. The default policy is `replace-basic`: remove
matching deck entries in memory and replace their count with the deck's most
common basic land. Use `SIMCLIENT_EXCLUDE_POLICY=skip-deck` or
`--exclude-policy skip-deck` when you want a clean sweep that omits any deck row
touching quarantined cards.

Puzzle confirmation after the bug shape is known:

```bash
SIMCLIENT_POLICY=forge-ai \
SIMCLIENT_MAX_TURNS=3 \
SIMCLIENT_GAME_TIMEOUT_SECONDS=120 \
just simclient-puzzle extinction-event-choice.pzl 1
```

Simclient writes per-game artifacts under `engine/build/simclient/`:

- `*.stats.json` is the first stop.
- `*.log` is useful after the stats identify the first repeated prompt, action, or object id pattern.
- `*.meta.json` records the run shape and any quarantine overlay.
- `summary.json` groups row outcomes by `failureClass`.

## Stats First

Start with these fields in `*.stats.json`:

- `completionReason`: `natural`, `turn-stall`, timeout, validation failure.
- `failureClass`: derived grouping for dashboards (`natural`, `exception`, `wall-timeout`, `validation`, `prompt-route`, `max-turns`, etc.).
- `winnerSeat`, `finalLifeBySeat`, `finalStatusBySeat`: did the game really finish, or did cleanup decide it?
- `iterations`, `totalMessages`: high values imply loops or stalled progress.
- `promptHistogram`: which GRE prompt type dominates.
- `promptRequestsByKind`: Forge prompt semantic shape, including prompts that never became GRE prompts.
- `promptRequestSamplesByKind`: shortest route to the message text that matters.
- `aiConsultedByPrompt`, `aiChoseByPrompt`: advisor coverage and fallback pressure.
- `targetChoiceCounts`, `targetChoiceSamples`: repeated target fingerprints.
- `simFindings`: derived warnings such as repeated target-choice replay suspects.
- `stalledPrompt`, `stalledFingerprint`: last repeated client-visible prompt.
- `warnsByLogger`, `errorsByType`, `validationViolationsByCheck`: direct failure signals.
- `exceptionMessage`, `exceptionStackTop`: root cause when scout mode converted a crash into a stats row.
- `deckOverlay`, `opponentDeckOverlay`: in-memory quarantine applied to a deck row.

Use logs only after stats tell you what to search for.

Optional-cost coverage: set `SIMCLIENT_ACCEPT_OPTIONAL_COSTS=true` when a deck
or puzzle needs greedy policy to accept optional costs instead of declining
them.

## Failure Taxonomy

Playback or session ordering:

- Symptom: validation complains about non-monotonic `msgId` or `gameStateId`.
- Stats may look normal until validation fails.
- Look for queued playback bundles sent after a newer caller bundle.
- Fix seam: playback draining and session send ordering.

Prompt-shape misroute:

- Symptom: an ordinary choice becomes a `SelectTargetsReq`, often with `sourceId=0` or no real card source.
- Stats: `promptRequestsByKind` shows generic/select prompts while `promptHistogram` shows targeting.
- Fix seam: `PromptSemantic` assignment or `PromptClassifier` fallback.

Target submit mismatch:

- Symptom: repeated target prompt with `SubmitTargetsResp=Success` but no resolution progress.
- Stats: one `targetChoiceCounts` key dominates.
- Logs: same source and target recur with stable source id.
- Fix seam: player/object reverse mapping, target index conversion, or candidate identity.

Engine replay loop:

- Symptom: object instance ids keep increasing while the same card or copy repeats.
- Stats: one source card dominates target choices or prompt counts.
- Stats: `simFindings` reports `replay-loop-suspect` once a target-choice fingerprint reaches the repeat threshold.
- Logs: repeated zone transfer from the same zone into stack, then back again.
- Fix seam: engine lifecycle cleanup, one-shot effect removal, cast-from-zone state.
- Guardrail: any one-shot playable object can produce this, including `MayPlay`, remembered-card, cast-from-non-hand, exile-until-end-of-turn, command-zone temporary permission, copy-and-cast, or prepared-spell effects.
- Manual suspicion threshold: repeated source grpId above roughly ten choices in one turn, increasing stack object ids for the same grpId, or high same-turn iterations while actions still appear to progress.

Advisor action loop:

- Symptom: repeated `ActionsAvailableReq`, same action selected, no meaningful state change.
- Stats: `aiConsultedByPrompt.ActionsAvailableReq` high, `aiChoseByPrompt` low or repeating.
- Fix seam: advisor adapter confidence, bad-action suppression, fallback behavior.

Unsafe autoresolve:

- Symptom: simclient passes, but a legal gameplay choice was defaulted without a client prompt.
- Stats: `promptRequestsByKind` includes `confirm|Generic` or another generic multi-option prompt.
- Fix seam: prompt semantic, adapter, or at minimum a WARN so the run is not silent.

Natural but bad play:

- Symptom: all games complete, but win rate or play pattern is obviously poor.
- Stats: no stall fingerprint; advisor choices are valid but strategically weak.
- Fix seam: advisor heuristic. Do not treat it as a protocol or engine bug without a repeated mechanical fingerprint.

## Inspection Order

1. Read `completionReason`, `iterations`, and `promptHistogram`.
2. Check `promptRequestsByKind` and `promptRequestSamplesByKind` for swallowed Forge prompts.
3. Check `aiConsultedByPrompt` versus `aiChoseByPrompt`.
4. Check `targetChoiceCounts` for one repeated source/target pair.
5. Check `simFindings` for `replay-loop-suspect` before waiting for the 200-iteration fuse.
6. If stalled, search the log for the source id, ability grpId, or prompt sample.
7. Map grpIds to card names and ability text.
8. Read the Forge card script for the source card.
9. Patch the narrow seam and rerun the same seed.

Useful quick summary once a deck pair has run:

```bash
jq -r '[.seed,.completionReason,.winnerSeat,.turn,.iterations] | @tsv' \
  engine/build/simclient/Control-Sample-vs-Aggro-Sample-s*.stats.json
```

For a specific repeated card source:

```bash
jq -r '.targetChoiceCounts // {} | to_entries[] | select(.key | contains("59671"))' \
  engine/build/simclient/*.stats.json
```

## Deck Runs Versus Puzzles

Use deck runs when:

- You are discovering unknown failure classes.
- Advisor behavior and card-pool interaction matter.
- You need fixed-seed before/after comparisons.
- You suspect a recurring pattern but do not know the seam yet.

Use puzzles when:

- A deck run already found the exact card or prompt shape.
- You need a minimal regression fixture.
- The test depends on library order, opening state, or a single prompt.
- You want to verify instrumentation, logging, or warning behavior.

Puzzle libraries are ordered. `humanlibrary=A;B;C` means the fixture can force top-of-library mechanics without relying on shuffles. This is powerful for cascade, discover, scry, draw, and prompt reproduction. Keep the puzzle minimal: only the cards needed to reach the seam.

## Examples

### Playback Ordering: Non-Monotonic Game State

Fingerprint:

- A fixed seed failed validation with newer session output sent before older queued playback.
- The game logic had progressed, but emitted ids were out of order.

Diagnosis:

- The session sent a caller bundle before draining queued playback batches with lower message ids.

Fix:

- Drain queued playback before sending newer caller bundles.

Verification:

- Rerun the exact bad seed.
- Then rerun the small matrix that previously included the bad seed.
- Validation failures should drop to zero.

### Prompt Shape: Source-Less Target Prompt

Fingerprint:

- `SelectTargetsReq` repeated with `sourceId=0`.
- Stats showed target prompts, but the underlying Forge prompt was an ordinary single-object choice.

Diagnosis:

- Generic choice prompts with candidate refs were classified as targeting.

Fix:

- Emit a narrower non-targeting semantic for ordinary single-object choices.
- Add a safe default for the specific replacement choice that otherwise looped.

Verification:

- The exact seed changes from `turn-stall` to `natural`.
- The bogus source-less target prompt disappears from target-choice stats.

### Engine Replay: Prepared Copy Recast Loop

Fingerprint:

- Repeated `SelectTargetsReq` for `Ancestral Recall` from an `Emeritus of Ideation` prepared copy.
- Stack object ids kept increasing.
- `targetChoiceCounts` was dominated by one source: the prepared copy targeting a player.

Diagnosis:

- Casting the prepared spell copy did not clear the source creature's prepared state, so the exile copy stayed playable and recastable.

Fix:

- On prepared-copy `Exile -> Stack`, clear the source creature's prepared effect by remembered-copy identity.

Verification:

- Exact bad seeds changed from `turn-stall` to `natural`.
- Repeated target count collapsed from roughly one hundred to a small number.
- Prepared behavior tests still passed.

### Unsafe Autoresolve: Extinction Event

Fingerprint:

- Puzzle stats showed:

```json
"promptRequestsByKind":{"confirm|Generic":1},
"promptRequestSamplesByKind":{"confirm|Generic":"odd or even"}
```

- The game completed naturally, but a real odd/even choice was defaulted.

Diagnosis:

- A multi-option generic confirm prompt had no narrower semantic or adapter, so the fallback path chose `defaultIndex`.

Fix:

- Keep known safe autoresolves at INFO.
- WARN when `PromptSemantic.Generic` has more than one option.
- Add a puzzle fixture that casts `Extinction Event` against odd and even creatures.

Verification:

- `just simclient-puzzle extinction-event-choice.pzl 1` completes.
- Stats show `warnsByLogger.leyline.match.TargetingHandler = 1`.
- The prompt sample remains visible in `promptRequestSamplesByKind`.

## Fix Criteria

A fix is ready to broaden when:

- The exact seed or puzzle reproducer completes naturally or fails for a new, understood reason.
- The original fingerprint collapses: prompt count drops, target choice domination disappears, or validation failure goes away.
- Focused tests for the touched seam pass.
- A small fixed seed range has no new stall class.

Do not judge an engine or prompt fix by win rate first. Win rate is useful after the mechanical failure is gone.

## What To Commit

Keep commits shaped by failure class:

- Playback/session ordering fix plus its focused test.
- Prompt semantic/classifier fix plus prompt/classifier tests.
- Advisor adapter change plus fixed-seed simclient evidence.
- Puzzle fixture plus the code/logging behavior it verifies.

Avoid bundling deck tuning with engine or prompt fixes. A better deck can hide a broken seam.
