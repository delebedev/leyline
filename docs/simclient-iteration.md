---
summary: "Fixed-seed simclient loop for turning prompt, advisor, and engine stalls into small reproducible fixes."
read_when:
  - "debugging simclient stalls, prompt loops, or advisor regressions"
  - "adding simclient telemetry or interpreting simclient stats"
  - "deciding whether to use a deck run or puzzle fixture"
---
# Simclient Iteration Playbook

Use simclient for synthetic Playthrough discovery, fixed-seed reproduction,
policy-realization probes, and synthetic game output:

1. Reproduce with fixed decks and seeds.
2. Classify the failure from stats before reading long logs.
3. Patch one seam.
4. Rerun the exact seeds.
5. Broaden only after the original failure fingerprint collapses.

Prefer deck runs for discovery. Use puzzles after the failure class is concrete enough to make a small deterministic fixture.

## Proof ownership

Simclient is not the acceptance executor and it does not replace a scripted
YAML verdict. Route a finding through this order:

1. Simclient finds or reproduces a failure with a fixed seed.
2. A minimal `.pzl` makes the decision boundary deterministic.
3. The Forge-AI solver confirms the intended line is rules-solvable.
4. `MatchFlowHarness` proves the backend-neutral YAML contract.
5. The live client reuses that YAML intent when native delivery matters.
6. Copilot/Pilot probes autonomous robustness; conformance compares protocol
   fidelity.

Use `just simclient` as the consolidated runner entrypoint. Do not promote an
autonomous soak result directly into acceptance.

## Promotion Path

Scout rows are discovery artifacts, not acceptance contracts. Keep the original
deck, seed, and stats fingerprint intact while classifying the broad failure.
Promote a scout finding only after it compresses to a deterministic puzzle that
direct Forge AI can solve and the GRE/headless path cannot yet realize.

When a finding reaches that shape, switch to `docs/ai-solved-acceptance.md`:

- prove the puzzle directly through Forge AI
- require a terminal result check for lethal probes (`winnerSeat` / `loserSeat`)
- keep the fix at the generic bridge, prompt, action, or adapter seam
- avoid card-name, puzzle-name, or scenario-specific policy branches

If direct Forge AI cannot solve the fixture, keep it in scout triage. That is
still useful signal, but it is not a policy-realization probe yet.

Direct-red does not always mean the puzzle is illegal or that GRE lacks a
mapping. Forge AI can decline a legal line because its own candidate filters or
ability heuristics do not value that line in the current phase. Treat those as
solver-capability caveats: either reshape the fixture until direct AI chooses
the intended line, or file a separate advisor note. Do not promote them
as GRE policy-realization gaps.

Keep workflow automation gaps in the direct-green/GRE-green/headless/live-proof
path separate from probe-specific policy bugs. The former belongs in loop
tooling; the latter belongs with the policy-realization work.

## Run Shapes

Single known-bad seed:

```bash
SIMCLIENT_POLICY=forge-ai \
SIMCLIENT_OPPONENT_DECK="Aggro Sample" \
SIMCLIENT_MAX_TURNS=200 \
SIMCLIENT_GAME_TIMEOUT_SECONDS=900 \
just simclient --decks 'Control Sample' --seeds 3
```

Small seed range after a fix:

```bash
SIMCLIENT_POLICY=forge-ai \
SIMCLIENT_OPPONENT_DECK="Aggro Sample" \
SIMCLIENT_MAX_TURNS=200 \
SIMCLIENT_GAME_TIMEOUT_SECONDS=900 \
just simclient --decks 'Control Sample' --seeds 1..5
```

Scout mode for broad deck sweeps keeps going after per-game exceptions and writes `completionReason=exception` stats rows. This is the standalone tool default; use `--strict` when a matrix is acting as a regression gate:

```bash
SIMCLIENT_CONTINUE_ON_EXCEPTION=true just simclient --decks 'Deck A,Deck B,Deck C' --seeds 1..20
```

Simclient test lane:

```bash
just test-simclient
```

Resume or shard a sweep without changing row identity:

```bash
just simclient --decks 'Deck A,Deck B' --seeds 1..200 --resume
just simclient --decks 'Deck A,Deck B' --seeds 1..200 --shard-index 0 --shard-count 4
```

Use an absolute `--out-dir` for ad hoc inspection from Gradle until relative
path handling is tightened; the default `engine/build/simclient/` path is
safe through `just simclient`.

Quarantine known-bad cards during discovery without editing deck files:

```bash
SIMCLIENT_EXCLUDE_CARDS="Tinybones Joins Up,102468" \
  just simclient --decks 'Deck A,Deck B' --seeds 1..20
```

`data/simclient/quarantine.txt` is loaded by default when present. Put one exact
card name or numeric grpId per line. The default policy is `replace-basic`: remove
matching deck entries in memory and replace their count with the deck's most
common basic land. Use `SIMCLIENT_EXCLUDE_POLICY=skip-deck` or
`--exclude-policy skip-deck` when you want a clean sweep that omits any deck row
touching quarantined cards.

## Autoplay Failure Compression

Use a repeatable autoplay failure to build a deterministic headless probe before
iterating in a graphical client:

1. Reduce the failure to the first wrong decision: prompt kind, source card,
   legal choices, chosen response, and expected response.
2. Write a minimal puzzle where the intended card play or block is the clear
   winning line. Remove unrelated choices and make the relevant resource or
   timing constraint decisive.
3. Run the puzzle with `forge-ai`. This exercises the active-game advisor and
   its normal greedy fallback. Check `aiChoseByPrompt` before attributing the
   submitted action to Forge rather than the fallback.
4. Run the same puzzle with `snapshot`. This exercises serialization,
   reconstruction, consultation, action matching, and response dispatch without
   a graphical client.
5. Assert the terminal result, the discriminating
   `promptProgressSamples[].decisionKind`, and advisor telemetry when decision
   parity matters. A win alone can hide cleanup, fallback, or an unrelated line.
6. Fix the generic prompt or action seam. Add a second mechanic or an ambiguous
   choice as a control when the fix could accidentally depend on action order.
7. Keep both headless lanes as regression probes. Return to the graphical client
   only for the final UI and transport smoke.

Most iterations therefore need no graphical client: autoplay discovers the
failure; the puzzle and the two policies reproduce, diagnose, and verify it.

Run the active-game `forge-ai` policy after the bug shape is known:

```bash
SIMCLIENT_POLICY=forge-ai \
SIMCLIENT_MAX_TURNS=3 \
SIMCLIENT_GAME_TIMEOUT_SECONDS=120 \
just simclient --puzzles extinction-event-choice.pzl --seeds 1
```

Run the same forced position from reconstructed state:

```bash
SIMCLIENT_POLICY=snapshot \
SIMCLIENT_MAX_TURNS=3 \
SIMCLIENT_GAME_TIMEOUT_SECONDS=120 \
just simclient --puzzles extinction-event-choice.pzl --seeds 1
```

`forge-ai` uses the shared `PromptDecisionAdvisor` on the active game, then
retains simclient's retry suppression, strategic fallback, and submission when
the advisor returns an unavailable result. Its `aiChoseByPrompt` and
`advisorUnavailableByReason` telemetry separate those paths. `snapshot`
serializes the position, hydrates an isolated game, consults the same Copilot
decision service, then passes its desired `SimDecision` directly to
`SimDecisionSubmitter`. Simclient retains whole-decision submission, retry
suppression, prompt-complete fallback, and validation. It does not encode and
decode Copilot-native response bytes.

`snapshot-shadow` compares the active and reconstructed advisors' desired
decisions semantically. Per-game stats retain fidelity grades, unavailable
reasons, relevant import findings, semantic agreement counts, and bounded
mismatch samples. This separates advisor agreement from any host-specific
native byte realization.

Simclient writes per-game artifacts under `engine/build/simclient/`:

- `*.stats.json` is the first stop.
- `*.log` is useful after the stats identify the first repeated prompt, action, or object id pattern.
- `*.meta.json` records `source: simclient`, the `runKind` tag (`deck`, `puzzle`,
  or another synthetic caller), and any quarantine overlay. The neutral
  `leyline.tooling.artifact` package owns this paired lifecycle and acceptance
  uses the same interface for optional diagnostic ingestion.
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
- `snapshotFidelityGrades`, `snapshotImportFindings`: reconstructed-state
  confidence and prompt-relevant import findings.
- `snapshotDecisionSources`: desired decisions attributed to Forge AI,
  prompt-derived defaults, or the narrow Copilot safeguard.
- `advisorUnavailableByReason`: explicit reasons no desired decision was
  deliverable.
- `snapshotSemanticAgreement`, `snapshotSemanticMismatchSamples`: active versus
  reconstructed desired-decision agreement and bounded divergence examples.
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
- Fix seam: `PromptSemantic` assignment or bound-route fallback.

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
