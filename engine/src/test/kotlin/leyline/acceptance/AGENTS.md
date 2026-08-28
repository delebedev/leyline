# acceptance

Puzzle-backed deterministic acceptance tests for `MatchFlowHarness`.

- Scenario suites live under `data/puzzles/sets/*.yaml` next to their `.pzl` files.
- For direct puzzle-state limits, read `../../../../../../docs/puzzle-harness.md`.
- Keep `steps` backend-neutral: game intent only, no UI coordinates or client gestures.
- Do not add acceptance assertions for internal projection or conformance invariants; prove those in focused contract tests.
- This package interprets scenario steps through `MatchFlowHarness`; the live-client smoke runner consumes the same YAML.
- Forge AI may solve a puzzle upstream or advise an autonomous run. It is not the acceptance executor.
- Simclient is the synthetic discovery and fixed-seed proof lane. It does not replace a YAML acceptance contract.
- Run with `just test-acceptance`.

`AcceptanceSuitesTest` discovers every `data/puzzles/sets/*.yaml` stem and runs it; a suite opts a runner out via top-level metadata (for example `web: {skip: reason}`) rather than by omission from a hard-coded list.

## Current durable patterns

- One gameplay intent per step: `cast`, `activate`, `choose`, `target`, `targets`, `distribute`, `block`, `attack`, `attack_all`, `turn_face_up`, `optional_action`.
- Cast/activate execution should consume the live `ActionsAvailableReq` row, not re-derive from zone state. Disturb proved that zone-name lookup is the wrong abstraction.
- Keep prompt responses separate from the initiating action. `cast` does not implicitly target; `choose` does not implicitly resolve.
- Use `expect: annotation_seen` for transient effects that should not require lasting board state, such as token creation or counter placement during resolution.
- Use `pass_until` only when engine progression is required. Use `expect` for zero-advance checks.
- `resolve_stack` must pass at least once when an empty stack has no published
  post-action horizon; activated abilities can land after the initial zone read.
  An exact pending Visible priority window is the evidence that the prior action
  already completed its synchronization horizon, so an empty stack may stop there.

## Scale cautions

- If we start needing two `zone_contains` / `zone_not_contains` checks in the same block often, add an explicit list form like `all:` rather than relying on YAML duplicate-key tricks.
- If `ability_index` starts to feel brittle, add label-based activation targeting before adding many more activated-ability scenarios.
- Keep optional-cost choice, modal choice, and payment selection distinct in YAML unless the game action is truly the same. They map to different prompt families in the live executor.
- A set file becomes executable the moment it carries `steps`; runner-level opt-outs belong in suite metadata (`web: {skip: reason}`), not in a hand-maintained suite list.

## DSL direction notes

- Keep the suite file as gameplay intent plus evidence. No executor coordinates, clicks, or visual artifact paths.
- Distinguish zone-cast from modal alternate costs. Graveyard casts such as jump-start and escape should not look like modal choices.
- `select_cost` should become `pay` once both executors accept the alias; payments are semantic, not tied to one wire prompt shape.
- Prefer semantic labels/modes over numeric `cto_id`. Keep numeric ids as an escape hatch for cases without stable text.
- Keep `expect: all:` for grouped assertions. It is explicit and avoids YAML duplicate-key ambiguity.
