# acceptance

Puzzle-backed scripted acceptance tests for MatchDoor.

- Scenario suites live under `puzzles/sets/*.yaml` next to their `.pzl` files.
- For direct puzzle-state limits, read `../../../../../../docs/puzzle-harness.md`.
- Keep `steps` backend-neutral: game intent only, no UI coordinates or client gestures.
- This package maps scenario steps to `MatchFlowHarness`; the live-client smoke runner consumes the same YAML.
- Run with `just test-acceptance`.

Executed suites are explicit in `AcceptanceSuitesTest`: `warmup`, `mechanics-warmup`, `combat-warmup`, `graveyard`, `interactions-warmup`, `cost-selection-warmup`, `modal-warmup`, and `mechanics-protocol`.

## Current durable patterns

- One gameplay intent per step: `cast`, `activate`, `choose`, `target`, `block`, `attack`, `attack_all`, `turn_face_up`, `optional_action`.
- Cast/activate execution should consume the live `ActionsAvailableReq` row, not re-derive from zone state. Disturb proved that zone-name lookup is the wrong abstraction.
- Keep prompt responses separate from the initiating action. `cast` does not implicitly target; `choose` does not implicitly resolve.
- Use `expect: annotation_seen` for transient effects that should not require lasting board state, such as token creation or counter placement during resolution.
- Use `pass_until` only when engine progression is required. Use `expect` for zero-advance checks.
- `resolve_stack` must always pass at least once; checking `stack empty` before the first pass is too weak for activated abilities.

## Scale cautions

- If we start needing two `zone_contains` / `zone_not_contains` checks in the same block often, add an explicit list form like `all:` rather than relying on YAML duplicate-key tricks.
- If `ability_index` starts to feel brittle, add label-based activation targeting before adding many more activated-ability scenarios.
- Keep optional-cost choice, modal choice, and payment selection distinct in YAML unless the game action is truly the same. They map to different prompt families in the live executor.
- Auto-discovering every `puzzles/sets/*.yaml` as executable can wait until every set file carries `steps`. For now, keep the executed suite list explicit in the acceptance test.

## DSL direction notes

- Keep the suite file as gameplay intent plus evidence. No executor coordinates, clicks, or visual artifact paths.
- Distinguish zone-cast from modal alternate costs. Graveyard casts such as jump-start and escape should not look like modal choices.
- `select_cost` should become `pay` once both executors accept the alias; payments are semantic, not tied to one wire prompt shape.
- Prefer semantic labels/modes over numeric `cto_id`. Keep numeric ids as an escape hatch for cases without stable text.
- Keep `expect: all:` for grouped assertions. It is explicit and avoids YAML duplicate-key ambiguity.
