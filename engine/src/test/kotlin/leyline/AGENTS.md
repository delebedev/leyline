# engine tests

Operational guidance for tests under `engine/src/test/kotlin/leyline/`. Read
`engine/AGENTS.md` for production architecture and Forge boundaries.

## Choose the cheapest lane that proves the behavior

| Lane | Base/tag | Use when |
|---|---|---|
| Unit | bare `FunSpec` + `UnitTag` | Pure values and deterministic policy; no game or harness |
| Board | `BoardTest` (`BoardTag`) | Direct bridge, mapper, annotation, action, or snapshot behavior |
| Integration | `SessionTest` (`IntegrationTag`) | A real `MatchSession` must drive priority, prompts, combat, or resolution |
| Simclient | bare `FunSpec` + `SimClientTag` | Simclient runtime behavior |

`BoardTest` supports several setup depths. Prefer `startWithBoard`; use
`startPuzzleAtMain1` for a rules-valid puzzle state and `startGameAtMain1` only
when the engine thread is part of the contract.

Use `SessionTest` only when the assertion requires game-loop interaction such
as `passUntil`, `selectTargets`, `declareAttackers`, or `respondTo*`. If a test
only inspects projected state, move it to Board. A `TierPlacementCheck`
suppression requires `@Suppress("TierPlacementCheck")` plus a comment naming
the loop behavior the rule cannot see.

Every direct spec declares exactly one lane tag. `BoardTest` and `SessionTest`
provide theirs automatically; semantic tags may be added separately. Do not
mix Board and Integration tests in one file.

## Place tests with their owner

- Tests with a clear production SUT mirror its package (`match`,
  `game.mapping`, `bridge.handoff`, and so on).
- `board/<domain>/` and `session/<domain>/` are for behavior-shaped tests with
  no single production owner.
- `mechanics/<keyword>/` owns mechanic action and lifecycle coverage.
- `behavior/<category>/<concept>/` owns protocol theses; `behavior/cards/`
  owns card-text behavior; `behavior/puzzles/` owns puzzle plumbing.
- `testkit/` owns test-only bases, matchers, probes, and message helpers.

A new file earns its own existence only if it holds two or more substantive
tests, needs setup no host can carry (a per-test timeout, a class-level
suppression), or has no thematic host. A single test named after a bug belongs
in the suite that should have caught it, usually as extra assertions on an
existing test rather than as a new one. Name the file for the behavior it
covers, never for the fix, the branch, or the report that prompted it.

## Use the existing harness surface

Test-only helpers live under `engine/src/test/kotlin/leyline/testkit/`.
Shared headless runtime code lives under
`engine/src/harness/kotlin/leyline/tooling/headless/`; tests import its aliases
from `leyline.testkit` where provided.

- Find cards and instance IDs through the probe DSL, which both `BoardTest` and
  `MatchFlowHarness` carry: `human.battlefield.card("Walking Corpse")`,
  `ai.exile.iid("Forum's Favor")`. Use `instanceIdOf` only when the zone is
  computed at runtime. Never walk `getZone(...).cards` to find a card by name —
  the DSL names the zone's contents when the lookup misses.
- Assert prompt windows through `after { ... }` and `MessageSlice` helpers.
  `expectOne*` means exactly one; use the raw message walker when repetition is
  part of the contract.
- Assert zones with `ZoneMatchers`, annotations with `annotation(type)` and the
  `detail*()` helpers, and actions with `ActionMatchers`. `beInZoneOf` and its
  wrappers mean *exactly* `count` copies and default to 1, so assert absence
  with `beMissingFrom(zone, player)` rather than negating a count.
- Build client messages through the proto DSL. Do not duplicate inline proto
  builders or private message walkers.
- Put read-only helpers on message types. Put helpers requiring live state on
  `Board` or `MatchFlowHarness`. Spec bases own lifecycle and naming sugar, not
  behavior.

Extract a shared helper only after repeated use shows a stable contract. A
domain matcher must materially improve the failure message; otherwise prefer a
local function or direct assertion.

## Validate emitted streams

Session tests validate emitted streams by default — the sink checks stable
game-state identity and affector facts on every message. Use
`InvariantSelection.diagnostics()` or `only(...)` for focused structural
checks.

For puzzle state limits and setup-action choices, read
`docs/puzzle-harness.md`.

## Reuse test cards

Prefer registered test-card fixtures for setup and expected results. Add
`engine/src/test/resources/test-cards/<card>.yaml` only when no registered card
exercises the required behavior.

Use YAML fixtures for ordinary metadata. Use handwritten registration only
when the fixture schema cannot express required runtime ability IDs. Use
`TestCardInjector.inject(...)` to place an already registered card into a live
game; it returns `InjectedCard(card, grpId, instanceId, forgeCardId)`.

## Write assertions that fail for the claimed reason

- Assert the named outcome directly, not an indirect side effect.
- Use exact counts when the setup determines the count.
- Never silently return from a test when setup is missing.
- Bail-out loops must assert their terminal condition; prefer `passUntil` and
  `passThroughCombat`.
- Use `assertSoftly` for three or more related assertions.
- Do not use wall-clock correctness assertions or `Thread.sleep`; use harness
  await primitives. Timeouts may guard deadlocks but are not outcomes.
- Use named protocol constants instead of numeric literals.
- Test names are behavior sentences. Comments state current invariants, not
  history or test narration.

## Run the narrowest useful gate

- One class: `just test-one <ClassName> engine`
- Several classes: `just test-many "<ClassA> <ClassB>" engine`
- Test output: `just test-debug <ClassName> engine`
- Safe engine change: `./gradlew :engine:testGate`
- Engine-loop or concurrency change:
  `./gradlew :engine:testGate :engine:testIntegration`
- PR boundary: `just test-gate`

Use dedicated acceptance and simclient tasks when those lanes change; the
ordinary engine test task excludes them. Run engine detekt and ktlint before
push; repository hooks enforce them at pre-push.
