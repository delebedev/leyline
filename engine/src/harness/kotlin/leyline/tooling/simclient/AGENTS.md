# simclient

Synthetic GRE-log generator. Drives a Leyline match in-process through `MatchSession`, `GameBridge`, and Forge; writes Player.log-shaped output plus metadata and stats under `engine/build/simclient/`.

Use `docs/simclient-iteration.md` for the fixed-seed debugging loop, failure taxonomy, deck-vs-puzzle choice, differential audit, quarantine, resume, and sharding. Treat CLI help and current code as option authority.

## Fast routes

```bash
./gradlew :engine:simclientSmoke                 # tool wiring
just simclient                                   # default matrix + scry ingest
just simclient mono-r-burn 1..10                 # focused deck/seed range
SIMCLIENT_POLICY=forge-ai just simclient bears 3 # policy + fixed seed
```

Arbitrary deck files may require `LEYLINE_CARD_DB`; built-in fixture decks do not. Store reusable decks under `data/decks/`. Use environment variables or CLI flags for run configuration; do not hardcode local paths.

## Ownership

- `SimClientDriver` — game loop and orchestration.
- `SimPromptLedger` — active/handled/retired prompt lifecycle and stall fingerprints.
- `SimPromptPolicy` / `ForgeAiPromptAdapter` — decision policy and Forge-AI translation.
- `SimDecision` — decision model and submission.
- `ActionAttemptLedger` — per-turn action attempts and outcomes.
- `PlayerLogWriter` — Player.log-compatible GRE output and metadata sidecars.
- `GameLogCollector` — per-row warning/error telemetry.
- `leyline.tooling.headless.MatchFlowHarness` — generic in-process session wiring shared with tests.
- This harness package — CLI/config, matrix expansion, watchdog, stats, summary, ingest.

Keep policy, telemetry, and row orchestration here. Put generic session response helpers in the headless harness. Policies return decisions; they do not submit directly.

## Iteration loop

1. Reproduce one deck pair and seed.
2. Read `.stats.json` before long logs; classify completion reason, stalled prompt/fingerprint, validation failure, and warning/error buckets.
3. Patch one generic prompt, action, event, or projection seam.
4. Add or tighten the focused Board/Session test that pins the root cause.
5. Rerun the exact seed, then widen the matrix.
6. Promote stable gameplay findings to deterministic puzzle acceptance; do not turn deck scouts into acceptance contracts directly.

Do not debug broad matrices by eyeballing many logs. Collapse first. A `gameOver=true` cleanup concede is not a natural game completion; read `completionReason`.

## Policy priorities

Improve policy for coverage and valid progress, not abstract playing strength:

1. eliminate stale/no-pending submissions and prompt loops;
2. answer prompt families that terminate runs;
3. unlock more turns, cards, and mechanics;
4. optimize combat/strategy only when it dominates failures.

A dumb legal response beats a clever unstable one. Repeated fingerprints indicate one policy/ledger defect, not many card bugs.

## Forge-AI load-bearing rules

1. Keep Leyline's bridged `PlayerController` registered. Forge AI is an advisor, never the player's installed controller.
2. Wrap every advisor call in `Player.runWithController(..., aiController)`; Forge AI internals assume an AI controller during the call.
3. Skip advisor work on pass-only action requests; latency can outlive the active priority window and create stale submissions.
4. Keep greedy fallback when the advisor has no mapped or useful answer.

Add a translator in `ForgeAiPromptAdapter`, not the driver. Translate the Forge-side choice into `SimDecision`, then submit through existing decision/harness paths.

## Prompt responder changes

When a prompt stalls:

1. Add or extend the policy/adapter decision for that GRE type.
2. Reuse a `MatchFlowHarness` response helper; add a generic helper there if missing.
3. Ensure `PlayerLogWriter` recognizes the message type so downstream parsing preserves it.
4. Ensure prompt detection/ledger retirement includes the type.
5. Test one successful response and one retirement/stale-prompt case.

Do not add a driver branch that bypasses the ledger or submits directly.

## Output contract

Each row writes:

```text
<row>.log         GRE trace
<row>.meta.json   source/tags/identity for ingestion
<row>.stats.json  completion, prompt, policy, validation, timing, warning/error telemetry
```

`summary.json` aggregates the run. Output identity is `(deck, opponent, seed, policy)`. Preserve that identity across retries, resume, and sharding.

The writer must emit the GRE message names expected by downstream parsers and a synthetic `ConnectResp` game boundary because simclient skips lobby/handshake.

## Failure interpretation

- `natural`: usable outcome signal.
- `max-turns`, `turn-stall`, `no-progress`, `iter-cap`, `cleanup`, `wall-timeout`: unresolved; useful for triage, never win/loss evidence.
- `exception`: row failed but scout mode continued.
- high `noPendingByDecision`: stale submission or retirement regression.
- `ZoneMapper` / annotation-order / validating-sink findings: likely implementation defects; reproduce narrowly.
- missing grpId errors: verify card availability/database age before changing policy.

Cancellation during teardown can produce one residual interruption error. Do not generalize that allowance to repeated engine or bridge errors.

## Verification

For simclient tooling changes:

1. run the focused unit tests;
2. run `:engine:simclientSmoke`;
3. rerun one representative fixed seed;
4. inspect stats and generated log/metadata shape;
5. run a small widened range when policy or orchestration changed.

The broad matrix is discovery evidence. Focused tests and deterministic acceptance remain the regression contract.
