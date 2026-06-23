---
summary: "ADR: split ActionMapper by cost support and action-family emitters, not by a generic action DSL."
read_when:
  - "working in ActionMapper, action emission, mana costs, auto-tap, or activated actions"
  - "adding a new ActionsAvailableReq action shape"
  - "deciding whether action mapping logic belongs in data rails, cost helpers, or emitters"
---
# ADR 0003: ActionMapper Action-Family Boundaries

## Status

Proposed for one staged refactor branch.

This ADR chooses a behavior-preserving refactor direction for
`ActionMapper`. It does not require a rewrite before action behavior can ship,
and it does not supersede the existing `CastRails` descriptor model.

## Context

`ActionMapper` maps Forge playability into Arena `Action` protos embedded in
`ActionsAvailableReq` and GSM action lists. It is currently a large, high-churn
object because it combines several layers:

- Zone orchestration: walk hand, battlefield, graveyard, exile, command, and room-door sources.
- Legality and cost support: can-pay checks, effective-cost computation, hybrid/two-generic fallback, snow handling, and auto-tap solutions.
- Wire construction: exact `Action` field envelopes, active vs inactive placement, mana requirements, and GSM stripping.
- Mechanic rails: alt-cost casts, room doors, Adventure/Omen, disguise face-up, and graveyard activations.

Recent action work has mostly touched cost and action-rail seams: mana color
mapping, two-generic hybrid costs, Convoke/Improvise display, room action
descriptors, and unpayable zone casts. `CastRails.kt` already extracts the most
table-like cast-route facts; the remaining pressure is shared cost machinery and
action-family emitters living in one file.

## Problem Statement

The problem is not raw file length alone. The problem is that unrelated action
changes share the same helpers, long resolver parameter lists, and builder
mutation code. A new cost mechanic, activated ability shape, or cast rail can
force a reader to understand most of `ActionMapper`.

The refactor should reduce collision points while preserving the current source
of truth for protocol-shaped facts:

- `CastRails` owns declarative alt-cost cast rail descriptors.
- `ActionMapper` remains the public entry point for action emission.
- New extracted code owns cohesive implementation support, not broad policy.

## Decision

Split `ActionMapper` incrementally in this order:

1. Extract pure mana/cost support.
2. Introduce a narrow action build context to remove repeated resolver plumbing.
3. Move auto-tap support behind the context if Stage 1 deferred it.
4. Extract activated-action emission.
5. Reassess cast-family extraction after the first stages.

### Stage 1: Pure Mana And Cost Support

Introduce a focused helper for action cost computations and proto mana-cost
translation. Start with pure or nearly pure cost logic; defer resolver-heavy
auto-tap movement until the context exists unless forwarding APIs make the move
trivial.

Candidate name: `ActionCostMapper` or `ActionManaCosts`.

It may own:

- `canPayManaCost` and `canPlayAndPayManaCost`.
- The two-generic hybrid affordability fallback.
- Available mana-source color collection.
- Effective-cost computation through Forge `CostAdjustment`.
- Forge `ManaCost` to `ManaRequirement` translation.
- Produced-mana string to `ManaColor` mapping wrappers.

It should not initially own:

- Auto-tap source collection and auto-tap solution construction, unless they
  move behind unchanged forwarding APIs. Those functions depend on id, grpId,
  card-data, and ability-registry resolvers; moving them before the context can
  preserve the worst parameter lists.
- `usesPaymentSourceReducer`, which is cast display policy.
- `addManaCostFromCardData`, which is printed-card-data fallback, not cost computation.

It must preserve the public/internal API needed by callers. In particular,
`computeEffectiveCost`, `forgeManaCostToPairs`, `forgeManaCostToRequirements`,
and `producedToManaColor` may need stable forwarding functions or carefully
updated call sites.

Do not replace every direct Forge `ComputerUtilMana.canPayManaCost` call with
the shared `canPayManaCost` wrapper as part of this stage. Some action families
intentionally use narrower direct checks today; changing them would be behavior.

This stage is first because cost logic is the highest reuse point and recent
source of churn. It can move as a pure extraction without changing action-family
policy.

### Stage 2: Action Build Context

Introduce a small context object for repeated resolver dependencies.

Candidate name: `ActionBuildContext`.

It may carry:

- Seat id and player.
- Id resolver.
- GrpId resolver.
- Card data lookup.
- Ability registry lookup.
- Card repository if needed for alt-cost binding lookup.
- Auto-tap helper access after Stage 1.

It must not become a policy object. It should not decide which action families
to emit, which rails apply, or active vs inactive placement. Its job is to make
call signatures smaller and make later extraction cheaper.

Prefer resolver lambdas and narrow data over carrying broad `GameBridge` or
`GsmSnapshot` references. The context should make dependencies explicit, not
hide orchestration.

### Stage 3: Auto-Tap Support

If auto-tap stayed in `ActionMapper` during Stage 1, move it after
`ActionBuildContext` exists.

It may own:

- Auto-tap source collection.
- Greedy auto-tap solution construction.
- Two-generic hybrid auto-tap fallback.
- `ManaSource` if it remains auto-tap-local.

It must preserve snow specs, predictive mana ids, ability ids, and exact source
selection behavior.

### Stage 4: Activated Action Emitter

Extract the activated-action family.

Candidate name: `ActivatedActionEmitter`.

It may own:

- `buildActivateManaAction`.
- `emitPlayableNonManaActivatedAbilities`.
- `emitActivatedAbilityAction`.
- Per-card graveyard activation emission if the context boundary is ready.
- Ability id and unique ability id resolution helpers.
- Produced-mana color collection if it did not move with the cost helper.
- The existing `ActivatedActionEnvelope` distinction between permanent-source
  and ability-only shapes.

It must preserve explicit field-shape differences:

- Battlefield activations include source identity and may set `shouldStop`.
- Hand-zone and graveyard activations omit source identity fields.
- Graveyard activations intentionally omit `grpId`, `facetId`, and `shouldStop`.
- `ActivateMana` carries predictive mana payment options, snow specs, ability ids, and unique ability ids.
- Graveyard activations keep their current direct Forge can-pay check unless a
  separate behavior-changing fix intentionally broadens it.

Keep graveyard zone iteration in `ActionMapper` unless moving it clearly reduces
coupling. The safer first extraction is for the emitter to build/emit the action
for one already-selected graveyard ability.

### Stage 5: Reassess Cast-Family Extraction

Do not extract all cast paths at the start. After Stages 1-3, reassess whether a
cast emitter is still valuable.

Potential future candidate: `CastActionEmitter`.

It would need to cover hand casts, zone casts, Adventure/Omen, room doors,
disguise face-up, and hand alt-cost casts. This is a larger and riskier move
because the cast family already has `CastRails` for the most volatile route
facts, and several cast shapes intentionally diverge by action type.

## Non-Goals

- Do not introduce a generic action DSL.
- Do not table-route every action type.
- Do not merge `buildFromSnapshot` and `buildActionList` in this branch.
- Do not move `CastRails` back into `ActionMapper` or make rails own builder side effects.
- Do not change can-pay, effective-cost, auto-tap, or active/inactive semantics.
- Do not use this refactor to add a new mechanic rail.

## Required Invariants

`ActionMapper.buildFromSnapshot` remains the production action-emission entry
point. `buildActionList` remains the live/pure helper path used by tests and
naive action construction.

Action ordering remains stable unless a test explicitly proves a behavior-neutral
ordering change. Known order-sensitive areas include hand casts before hand-zone
activated abilities and Pass/FloatMana at the end.

Active vs inactive placement must remain unchanged for unaffordable casts,
lands, activations, zone casts, Adventure/Omen, and room doors.

Mana-cost display must remain unchanged for payment-source reducers: Convoke and
Improvise display printed costs where the client needs payment-source reducers
instead of reduced costs.

Auto-tap solution behavior must remain unchanged, including snow specs and
two-generic hybrid fallback.

`computeEffectiveCost` must preserve its temporary mutation/restore behavior:
the original activating player is restored, and commander `castFrom` seeding is
undone after cost adjustment.

Existing direct can-pay checks remain direct unless the branch explicitly files
and tests a behavior change. This is especially relevant for room, Omen,
disguise face-up, and graveyard activated action paths.

Activation envelopes must remain distinct:

- Battlefield activations include `grpId` and `facetId`, and may set `shouldStop`.
- Hand-zone activations omit source identity fields, and mana requirements echo `abilityGrpId`.
- Graveyard activations omit `grpId`, `facetId`, and `shouldStop`.

`ActivateMana` keeps predictive mana ids starting at 10, snow spec propagation,
ability grp id behavior, and unique ability id behavior.

Cast rail behavior remains governed by `CastRails` and the existing resolver
helpers. A cast-family extraction must not hide rail ordering or alt-cost lookup
rules.

GSM action stripping remains a separate responsibility. It may move only if the
new location keeps the same minimal field contract visible.

## Alternatives Considered

### Extract CastActionEmitter First

Rejected as the first stage. Cast logic is large and tempting, but `CastRails`
already extracted the most repeated cast facts. Cast emission also has many
intentional exceptions: Adventure keeps the creature grpId, Omen omits grpId,
room doors are action-type keyed, disguise face-up is special, and zone casts
have source-zone rail differences.

### Generic Action Family DSL

Rejected. Action protos are field-shape sensitive, and hiding those differences
behind a generic descriptor risks making protocol constraints less visible.

### Only Split Files Without New Boundaries

Rejected. Moving private functions into files without clarifying ownership would
reduce line count but not reduce future change cost.

### Keep ActionMapper As One Object

Acceptable for isolated fixes, but not the preferred architecture. Cost helpers
and activated emitters are already cohesive enough to extract, and the file is a
recurring collision point.

## Testing Strategy

Each stage should run focused tests before the full gate.

Stage 1:

- `ActionMapperPureTest`.
- `ActionMapperSnapshotTest`.
- Mana color / mana cost mapping tests.
- Cost reduction tests that cover effective-cost behavior.
- Hybrid/two-generic and snow action tests.
- Tests that pin `computeEffectiveCost` side-effect restore if new seams expose it.

Stage 2:

- Same as Stage 1, plus targeted compile checks around call-site migration.

Stage 3:

- Same as Stage 1 for auto-tap, snow, and hybrid/two-generic behavior.

Stage 4:

- `ActionMapperPureTest` activation cases.
- `ActionMapperSnapshotTest` activation/uniqueAbilityId/snow cases.
- Board tests for Channel/Ninjutsu/graveyard activations if touched.
- Focused shape tests that hand-zone activations omit `grpId`/`facetId` and echo
  `abilityGrpId` on mana costs.
- Focused shape tests that graveyard activations omit `grpId`/`facetId`/`shouldStop`.

If any cast-adjacent helper moves, also run relevant mechanic tests for Plot,
Foretell, Flashback, Disturb, Escape, Room, Omen, and Adventure action shapes.

Before merging the branch, run `:matchdoor:testGate` plus static checks.

## Migration Plan

1. Add the cost helper and move pure cost/mana functions with forwarding wrappers
   only where they preserve existing internal API cheaply. Leave auto-tap in place
   if moving it would just preserve long resolver parameter lists.
2. Run focused tests and static checks.
3. Add `ActionBuildContext` and migrate enough call sites to remove repeated
   resolver parameter lists without changing orchestration.
4. Move auto-tap support behind the context if it did not move in Stage 1.
5. Extract activated action emission behind the context.
6. Reassess whether cast-family extraction still pays for itself. File a follow-up
   instead of continuing if the remaining cast code is clearer in place.

Stop after any stage if the extraction increases the number of concepts needed
to add a small action rail.
