# bridge/forge/

Forge inheritance seams — the only place engine extends Forge classes.

## Before extending

**Read the whole PlayerController before adding overrides.** Inherited
PCHuman behavior is rarely what you want — but overriding blindly is also
wrong. For each override, document above the method: which inherited path
is broken, what the bridge replacement does, which thread runs it.

## Forge SA-chain rule

A spell is an SA *chain*, not a single SA. Wrapper APIs (Charm, Effect,
Repeat, RepeatEach, …) put the meaningful work in *post-makeChoices* sub-SAs.

Predicates over outer-SA-only properties — `sa.usesTargeting()`, `sa.api`,
`sa.hasParam(...)` — silently miss whatever the chosen sub-SAs do at
resolve-time. When gating Forge cast-pipeline behavior, default to:

- chain-walking helpers Forge already ships: `setupTargets()`,
  `AbilityUtils.resolve()`, `getRootAbility()`, recursive `usesTargeting()`
  variants where relevant
- conditions that are stable across the chain (`sa.targets.isEmpty()` for
  pre-set-target paths)

If you find yourself writing `if (sa.X) ...` to decide cast / targeting /
resolve behavior, ask: "would this still be correct if a Charm wraps the SA?"

## Threading

Engine thread executes overrides synchronously inside Forge's `Game.run()`.
Bridge handoffs (CompletableFuture in `bridge/handoff/`) route back to the
session thread for client interaction. Don't block on the session thread
from an engine-thread override.
