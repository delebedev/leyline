# simclient

Synthetic GRE-log generator. Drives a Leyline match in-process through the semantic `HeadlessMatch` seam; writes Player.log-shaped output plus metadata and stats under `engine/build/simclient/`.

Use `docs/simclient-iteration.md` for the fixed-seed debugging loop, failure taxonomy, deck-vs-puzzle choice, quarantine, resume, and sharding. Treat CLI help and current code as option authority.

## Fast routes

```bash
just test-simclient                              # full simclient test lane
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
- `leyline.tooling.headless.HeadlessMatch` — semantic match operations and immutable observations; runtime wiring stays private to the headless implementation.
- This harness package — CLI/config, matrix expansion, watchdog, stats, summary, ingest.

Keep policy, telemetry, and row orchestration here. Put generic session response helpers in the headless harness. Policies return decisions; they do not submit directly.

Simclient evaluates the client-facing execution path. It does not own direct
Forge runs, AI-strength comparisons, or experiment evidence.

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
2. Reuse a `HeadlessMatch` semantic response helper; add a generic helper there if missing.
3. Ensure `PlayerLogWriter` recognizes the message type so downstream parsing preserves it.
4. Ensure prompt detection/ledger retirement includes the type.
5. Test one successful response and one retirement/stale-prompt case.

Do not add a driver branch that bypasses the ledger or submits directly.

## Verification

For simclient tooling changes:

1. run the focused unit tests;
2. run `just test-simclient`;
3. rerun one representative fixed seed;
4. inspect stats and generated log/metadata shape;
5. run a small widened range when policy or orchestration changed.

The broad matrix is discovery evidence. Focused tests and deterministic acceptance remain the regression contract.
