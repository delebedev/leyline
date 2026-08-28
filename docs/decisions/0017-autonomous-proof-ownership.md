---
summary: "ADR: share prompt decisions while keeping proof execution and host realization separate."
read_when:
  - "changing simclient or Copilot prompt decision routing"
  - "changing synthetic log or metadata artifact ownership"
  - "choosing between Forge-AI, headless acceptance, live-client, or conformance proof"
---
# ADR 0017: Separate Prompt Decisions From Proof Execution

## Status

Accepted and implemented.

## Context

Simclient and Copilot both need the same answer to a GRE prompt, but they do not
have the same host responsibilities. Simclient owns retry suppression,
strategic fallback, telemetry, whole-selection submission, and synthetic game
output. Copilot owns incremental target/combat convergence, proposal metadata,
response encoding, and native delivery. Headless acceptance owns deterministic
YAML execution through `MatchFlowHarness`. Live acceptance owns native-client
delivery. Conformance owns protocol comparison.

The old split duplicated prompt-family selection in the two autonomous hosts and
left synthetic log framing, metadata, and ingestion attached to simclient even
when acceptance emitted the same artifact shape.

## Decision

`PromptDecisionAdvisor` is the one internal prompt-family authority shared by
simclient and Copilot. It returns either a complete desired `SimDecision` with a
source (`ForgeAi` or `Default`) or an `Unavailable` result with one of
`UnsupportedPrompt`, `NoForgeChoice`, `RejectedAttempt`, or `ConsultFailed`.
Simclient records unavailable reasons before using its host fallback. Copilot
turns an unavailable result into an `unrealizable` proposal unless its explicit
main-phase proactive-permanent host safeguard applies.

Target decisions are grouped by GRE `targetIdx` and retain both group identity
and instance ids. Simclient flattens the complete desired map only at its
whole-selection submission boundary. Copilot diffs the grouped map against the
current committed prompt and emits one native response at a time.

`leyline.tooling.artifact` owns the neutral synthetic artifact lifecycle:
ConnectResp framing, GRE envelope serialization, canonical enum conversion,
timestamps, paired log/metadata naming, `source: simclient` provenance,
`runKind`, optional quarantine details, and ingestion. Simclient and acceptance
provide identity and message values without knowing file-shape mechanics.

Proof roles remain distinct:

- Forge AI is an upstream rules-engine solver or autonomous advisor.
- `MatchFlowHarness` executes deterministic YAML acceptance.
- The live client executes the same scripted intent through the Arena head.
- Copilot/Pilot measures autonomous robustness and native response delivery.
- Conformance compares protocol fidelity.

## Consequences

Prompt-family behavior has one desired-response contract while host-specific
execution remains explicit. Grouped multi-target prompts cannot silently lose
their request-group identity. Unavailable Forge choices remain visible in
simclient stats and Copilot proposals instead of becoming silent defaults.

Acceptance can emit and ingest the same synthetic artifact shape without
depending on simclient package code. `summary.json` remains a minimal batch
verdict; detailed evidence stays in per-row stats and paired artifacts.

## Alternatives Considered

- Add a generic `DecisionRealizer`. Rejected because native incremental delivery
  and headless whole-selection submission have different ownership and would
  recreate a leaky abstraction.
- Move retry suppression, land/cast/pass strategy, or telemetry into the advisor.
  Rejected because those are host concerns, not prompt decisions. Copilot's
  narrow main-phase safeguard remains in its host as an explicit fallback.
- Make the advisor depend on acceptance executors or run strategies. Rejected
  because the decision seam must serve both autonomous hosts and remain reusable
  by focused tests.
- Add a serialization framework or generalized artifact hierarchy. Rejected
  because the paired log/metadata lifecycle has one small, stable interface.
