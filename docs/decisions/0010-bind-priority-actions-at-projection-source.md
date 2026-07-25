---
summary: "ADR: create executable priority commands beside their protocol actions and never reconstruct them from a response."
read_when:
  - "changing ActionMapper, ActionOfferCatalog, PlayableActionQuery, or priority action execution"
  - "adding a cast, land, activation, mana, or alternate-cost action family"
  - "deciding where action legality, protocol projection, and command binding belong"
---
# ADR 0010: Bind Priority Actions at Projection Source

## Status

Accepted for incremental implementation. Amended by
[ADR 0014](0014-command-yield-engine-boundary.md): the exact executable handle
and pending-window ownership move into the engine worker's opaque-token table;
the bind-at-source and bounded-lifetime invariants below are unchanged.

## Context

The pending action catalog correctly binds a client response to one short-lived
priority window. Its entries, however, are currently created after protocol
projection.

`ActionMapper` starts with the exact Forge card, `SpellAbility`, ability index,
legality result, and affordability result, then emits an `Action`. After the
request has been built, `ActionOfferCatalog` reads that protocol action,
enumerates Forge abilities again, and compares protocol fields to recover the
`PlayerAction` command. Casts, alternate costs, activations, mana abilities,
MDFCs, Adventures, Omens, Rooms, and face-up actions each have reconstruction
branches.

This leaves two authorities for one action:

```text
Forge ability -> ActionMapper -> protocol Action
protocol Action -> ActionOfferCatalog -> re-enumerated Forge ability -> command
```

The second path can drift from the first. An unresolved or unknown action is
currently represented by `PassPriority`, which keeps catalog cardinality intact
while hiding that no executable command was bound.

`PlayableActionQuery` also walks lands, casts, activated abilities, and zones
independently for smart phase skipping. It asks the same game-rule question as
priority action projection through a third traversal.

## Decision

Create each executable `ActionOffer` in the same branch that creates its
protocol `Action`.

The source shape is:

```text
Forge action candidate
  -> protocol Action
  + exact PlayerAction command
  = ActionOffer
```

Action projection returns both the `ActionsAvailableReq` and its ordered offers.
The session binds those offers directly to the pending priority window before
the request becomes visible. A response resolves only through the bound
`ActionResponseKey`; it never causes Forge abilities to be enumerated again.

Keep the existing pending-window ownership, stale response rejection, and
supersession behavior in `GameActionBridge`. This decision changes where offers
are produced, not how the pending action future is synchronized.

## Ownership Boundary

Forge owns:

- legal cards and abilities;
- exact cast, alternate-cost, land, and activation candidates;
- stable Forge card and ability identity;
- whether a candidate is legal at the current priority point.

Leyline action projection owns:

- active versus inactive protocol presentation, including affordability;
- client card, ability, and alternate-cost identifiers;
- mana requirements and auto-tap suggestions;
- pairing that projection with the exact executable `PlayerAction`.

The session owns:

- attaching offers to one pending priority window;
- rejecting stale, superseded, duplicate, or absent responses;
- submitting the already-bound command to the blocked engine thread.

Under [ADR 0014](0014-command-yield-engine-boundary.md) these three session
responsibilities relocate to the serial match owner and the engine worker's
token table; the invariants they protect stay the same.

Legality and affordability remain distinct. A legal but unaffordable candidate
must remain available to projection as an inactive action and must prevent smart
phase skipping.

## Candidate Query

Use one Forge-facing candidate traversal for executable priority actions. It may
short-circuit for smart phase skipping, but it must not maintain a second list
of zones or mechanic-specific ability rules.

Existing Forge helpers can support the first migration slices. Where Leyline's
`CardLookup` still reconstructs candidate semantics that Forge already knows,
add a narrow UI-neutral Forge query rather than preserving a bridge-owned rules
catalog. Do not introduce a generic action DSL or a universal planner.

Action-family projection remains explicit. Cast, activation, land, and mana
actions have different protocol envelopes; sharing their candidate source does
not require hiding those differences.

## Identity And Lifetime

Resolve action-side ability identity while the originating Forge candidate is
in hand. Carry the Forge ability id, action index, and protocol ability id needed
by the offer; do not recover them from the emitted action later.

A bound command may retain the exact `SpellAbility` only while its engine thread
is blocked at the same priority window. The catalog is discarded when that
window completes or is superseded. Do not retain mutable Forge objects in
session history or across arbitrary game progress.

This is the action-side ability-identity rule. Stack, event, and prompt identity
continue in [ADR 0011](0011-preserve-ability-definition-identity.md).

## Migration

1. Characterize offer-to-command parity for every executable priority family.
2. Change action emitters to produce `ActionOffer` beside each `Action`.
3. Bind those offers directly from the action build result.
4. Delete protocol-to-command reconstruction from `ActionOfferCatalog`.
5. Route smart phase skipping through the shared candidate traversal and delete
   `PlayableActionQuery` branches made redundant by it.
6. Delete `CardLookup` helpers and cast-rail submission logic that lose their
   final consumer.

Migrate one action family at a time if needed, but do not keep two production
binding paths for the same family. A partially migrated build must fail an
unbound executable action explicitly; it must not substitute `PassPriority`.

## Required Invariants

- Every executable action in a priority request has exactly one bound command.
- Pass and FloatMana are the only projections intentionally bound to
  `PassPriority`.
- Protocol action ordering and active/inactive placement remain unchanged.
- Equivalent payment and auto-tap variants may share a response key only when
  they share the same command.
- The command belongs to the same game-state id and pending action id as the
  request that exposed it.
- Informational and off-priority action lists do not need executable commands.
- Combat declarations and cost-payment actions keep their existing typed
  interaction paths.

## Verification

Round-trip tests should select an emitted action and assert the exact command
for:

- base, additional-cost, and alternate-cost casts;
- graveyard, exile, and command-zone casts;
- MDFC, Adventure, Omen, Room, and face-down or face-up variants;
- land and MDFC-land plays;
- non-mana and mana activations;
- legal but unaffordable actions;
- duplicate-looking offers, partial responses, and payment variants;
- stale and superseded priority windows.

Tests must also prove that an unbound executable action is rejected rather than
converted to pass, and that smart phase skipping agrees with the executable
candidate query without building protocol actions.

## Consequences

- Offer and execution cannot disagree about ability ordering or mechanic rails.
- Adding an action family requires one candidate-to-projection branch, not a
  matching reverse resolver.
- `ActionOfferCatalog` becomes unnecessary as a builder; the pending catalog in
  `GameActionBridge` remains.
- Smart phase skipping and protocol projection share game-rule discovery.
- Action-side ability identity is resolved once at its strongest context.
- `ActionMapper` may still be large because protocol envelopes are explicit;
  file size is not the target.

## Relationship To ADR 0003

[ADR 0003](0003-actionmapper-action-family-boundaries.md) remains authoritative
for action-family projection structure, cost support, and explicit protocol
envelopes. This ADR adds the lifecycle rule that each executable projection
must carry its command from the same source branch.

## Alternatives Considered

- **Keep post-projection binding** — rejected because it preserves reverse
  enumeration and allows projection and execution to drift.
- **Bind only card and ability indexes** — rejected as the final shape because
  later execution can still depend on re-enumeration ordering. Indexes are safe
  only when interpreted within the same bounded candidate set.
- **Reconstruct from protocol identifiers** — rejected because protocol ids are
  presentation identity, not an engine command.
- **Introduce a broad ability-projection service first** — rejected because the
  action source already has the exact identity. Broader identity consolidation
  needs separate evidence after this migration.
