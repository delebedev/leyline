---
summary: "Bounded protocol contracts over deterministic acceptance scenarios, a CI lane, and separate checker regressions."
read_when:
  - "running or extending protocol conformance checks"
  - "deciding which lifecycle assertions can replace duplicate session tests"
---
# Protocol conformance

Run `just test-conformance` for authored YAML protocol obligations over the emitted
GRE stream. Every file under [`conformance/contracts/`](../conformance/contracts/)
is discovered and verified. Contracts sharing a `(suite, id)` execute their scenario
once, with independent named failures for each contract. The extended CI job runs the same task and propagates failures.
JUnit output lives under `engine/build/test-results/testConformance/`.

Conformance runs immediately after Build in Extended, before the longer
integration, simclient, and acceptance suites. It shares Forge setup with those
suites and executes its own scenarios, without depending on another task's
output. A failure fails the required Extended check. Its check name stays stable
for branch rules.

[`ProtocolConformanceTest`](../engine/src/test/kotlin/leyline/behavior/conformance/ProtocolConformanceTest.kt)
reuses `MatchdoorAcceptanceExecutor` and the existing scenario YAML:

| Scenario | Protocol obligations |
|---|---|
| `warmup/land-spell-face` | Cast and resolution framing, source and target identity, exact damage count and typed detail values, object reallocation, target-row retirement. |
| `mechanics-warmup/reconfigure-attach-unattach` | Ability and targeting order, target-group cardinality and bounds, prompt flags, source binding, submitted target identity, target-row retirement, attach payment and action, and source-owned attachment/layer resolution. |
| `mechanics-protocol/llanowar-elves-mana` | Creature-source binding, activation and tap order, payment detail types and source identity, exact payment and retirement counts. |
| `mechanics-protocol/investigate-novice-inspector` | Trigger-source row introduction and retirement, token parent and source identity, resolution framing. |
| `mechanics-protocol/boast-usher-of-the-fallen` | Activation identity, exhaustion keys and remaining uses, token parent and source identity, resolution and ability retirement. |
| `modal-warmup/shock-land-temple-garden` | Optional prompt source and incoming identity, life payment, land-entry identity and replacement-row retirement. |
| `graveyard/disturb-lunarch` | Graveyard cast, back-face resolution, and object identity across death and casting. |
| `mechanics-warmup/omen-lifecycle` | Token creation, library destination after resolution, and object reallocation. |
| `siege/zendikar-defeat-cast` | Battle identity, Defense counter type and detail types, exact add/remove deltas, persistent counts, and retirement of the replaced count row. |
| `mechanics-protocol/case-gateway-express-lifecycle` | Solve progress and reset on one row, trigger identity, solved effects, and exactly one solve trigger within the threshold-to-resolution window. |
| `mechanics-protocol/warp-quantum-riddler` | Delayed holder source and parent identity, pending-row update, exile reallocation, ability and row retirement, and later exile-cast permission, option lifetime and entry draw. |
| `mechanics-protocol/daynight-roundtrip-cathar` | Battlefield identity and reciprocal runtime face identities across the night transformation. |
| `mechanics-protocol/discover-hidden-courtyard` | Exile offer and stack reallocation identity, Discover ability binding, absence of casting options before acceptance, and option-row lifetime through resolution. |
| `mechanics-protocol/foretell-depart-the-realm-lifecycle` | Accepted special action, runtime ability binding, face-down reason, persistent suppression identity, later cast option and targeting, return view and identity, and stack-row retirement. |
| `mechanics-warmup/cycling-activate` | Offered hand ability identity, exactly one accepted activation, discard cost, draw inside resolution, and no premature retirement. |
| `mechanics-protocol/saga-origin-final-sacrifice` | Final lore count, same-frame chapter completion, retirement, source reallocation and sacrifice identity. |
| `mechanics-protocol/stock-up-bottom-order` | Resolution begins before selection, exact selection bounds, Put transfers, bottom-order domain and source, and no repeated selection. |
| `mechanics-protocol/ward-tax` | Ward trigger identity, source-row lifetime, and no premature ability retirement. |

Scenario YAML owns gameplay intent. Contract YAML owns protocol expectations;
Kotlin interprets the contracts. Changing an emitted field requires checking its protocol meaning
before changing the expectation. A green gameplay scenario alone does not justify
loosening a protocol assertion.

The default emitted-stream validator rejects duplicate persistent annotation IDs,
duplicate persistent deletion IDs, and emission/deletion overlap within one GSM.
It permits repeated IDs across messages, including mutable row updates and Full
or Undo baselines. Unknown deletions are not hard failures: a projection batch
can create and retire a row before its first viewer-visible emission. Cross-message
lifetime and reuse of retired IDs remain outside this packet-local check. Scenario
contracts retain their distinct lifecycle and gameplay assertions.

The default stream validator also checks ordinary spell identity transitions:
`CastSpell` from Hand to Stack and `Resolve` from Stack to Graveyard, when both
zones are known. Each transfer requires a distinct `ObjectIdChanged` pair before
its affected identity, retirement from active zone lists, and final destination
membership. Public destinations require a matching object row. Limbo may retain
old or intermediate identities, hidden destinations need no visible row, and
same-frame chains defer projection checks only with an ordered successor pair
and a later transfer from the current destination. Other transfer
families and shuffle-only identity changes remain outside this check.

`InvariantCheckerTransitionTest` covers malformed pairs, ordering, retirement and projection
with valid Limbo, hidden and chained controls. The Lightning Bolt contract replay
also rejects altered cast pairs and destination projection. `OmenLifecycleTest`
relies on this default check for distinct cast identity and pair ordering, while
retaining its companion, first-annotation, shuffle and gameplay
obligations. The Omen interaction contract owns the resolution bracket, token
cardinality and required transfer emission.

Runtime card and ability identifiers use the Forge catalog. Contracts relate
those identifiers within one interaction rather than pinning catalog-dependent
numbers. Casting options omit an absent cost or permission field. Warp alternative casts and foretold casts carry only the selected cost; other established alternative-cost routes retain both fields. A targeted alternative cast publishes its selected option on announcement and retains that row through target submission. Stable protocol values, detail keys, counts, and ordering are explicit.
This suite proves the listed interactions, not catalog-wide identity parity or
live-client presentation.

Checker regression coverage is separate from the conformance driver.
[`ProtocolContractTest`](../engine/src/test/kotlin/leyline/testkit/ProtocolContractTest.kt)
runs pure parsing and matching regressions in the unit lane.
[`ProtocolContractMutationTest`](../engine/src/test/kotlin/leyline/testkit/ProtocolContractMutationTest.kt)
runs selected scenarios in the integration lane, verifies each baseline, then
requires its contract to reject altered project-generated output:
duplicate or wrong damage, premature retirement, empty target groups, incorrect
source, prompt parameters or undo flags, untapped mana sources, incorrect payment
sources, incorrect token parents or sources, remaining Boast uses, and missing
retirement, reintroduced retired rows, contradictory row updates,
incorrect optional-decision identities or life payments, incorrect
Disturb source zones, incorrect Omen destinations, string or incorrect Defense
counter types, incorrect counter deltas and counts, and missing counter-row
retirement, incorrect solve progress and designation, duplicate solve triggers,
incorrect delayed holder identities or exile destination, and premature Ward
ability retirement, incorrect transformation faces or identity, mismatched Discover
cast offers, broken stack reallocation, and early casting-option creation or retirement, wrong foretell state or action, broken cycling identities or retirement, and incorrect selection bounds, source, ordering domain or repeated selection, incorrect attachment/action/layer identities, cast permissions and alternative costs, return identities, and option-row updates or reintroduction.
Each regression scenario runs once, grouped by `(suite, id)`; mutations reuse its messages.
Sibling baseline and mutation checks retain independent named failures. The conformance lane only discovers,
executes, and verifies authored contracts.

Keep mechanism, concurrency, cancellation, transport, and head presentation
tests. Remove a duplicate lifecycle test only after a contract proves every
distinct obligation that test protects.

A contract names its acceptance `scenario` (`suite` and `id`), ordered `frames`,
and optional exact `counts`. Each frame contains ordered `events` that coexist in
one emitted message. A later frame may continue in that same message.
An event matches `type`, optional `lane` and `op`, exact detail `keys`, typed
`fields`, positive `present` selectors, and `equals` references to earlier events. `sameRow` relates persistent
row introduction and deletion. Field selectors support protobuf `raw` fields,
normalized `details`, `detailTypes`, identities, array indices, and `length`.
`present` requires each selector to resolve. For protobuf message fields it checks
field presence, so an explicitly present empty message satisfies the obligation.
Enum fields use their protocol names with protobuf collision suffixes removed.
Quote enum names such as `"No"` that YAML otherwise reads as booleans.
Unknown schema fields, invalid references, and explicit null entries fail loading. A missing event, contradictory value, or
incorrect count fails CI.
Counts cover the scenario's entire emitted stream. Matching selects one start;
subsequent events cannot skip a contradictory occurrence to accept a later valid copy.
An optional `where` selects an event using `fields` or `equals` when other
interactions emit the same type, such as multiple mana sources during payment.
The first selected event must satisfy its assertions. A later valid selected
event cannot hide an earlier contradictory one.

Optional `windows` constrain the stream strictly between two bound events.
`absent` forbids a matching event; `count` requires an exact nonnegative count.
`exists` requires at least one matching event inside the window. Earlier
nonmatching events are allowed, unlike a selected frame event whose contradictory
assertions fail immediately.
Patterns use the same typed fields and identity relations as stream counts.
`holds` requires a persistent row introduced or updated by its first anchor to
survive until the endpoint. Retirement at the endpoint is allowed. Boundaries
use event order, so these obligations also distinguish events within one message.
Unknown, equal, or backwards anchors and malformed clauses fail loading.

```yaml
windows:
  - id: no-early-retirement
    after: created
    before: retired
    absent: {type: AbilityInstanceDeleted, equals: {affectedIds: created.affectedIds}}
  - id: source-holds
    holds: source
    until: source-retired
```

The prompt lane supports `SelectTargetsReq`, `OptionalActionMessage`, `SelectNReq`,
`OrderReq`, and `ActionsAvailableReq`. Their full protobuf payload remains
available through `raw` selectors. The `Action` type uses `lane: action` and
`op: offer` for each active action in `ActionsAvailableReq`. Its `raw` selectors
address that action directly. Inactive actions do not become offer events.

Persistent rows become inactive at deletion. A later emission of that row is a
new `create`, while repeated deletions retain their last-known identity for
counting. The target, trigger-source, and replacement contracts require one
creation, no updates, and one deletion for their selected row.

Start with [`lightning-bolt.yaml`](../conformance/contracts/lightning-bolt.yaml)
or [`rabbit-battery-target-selection.yaml`](../conformance/contracts/rabbit-battery-target-selection.yaml).
Add one bounded interaction at a time and bind it to an existing scripted scenario.

The Quantum Riddler exile-cast definition checks cast permission, stable battlefield identity, the entry draw bracket, and row lifetimes across updates. Entry-trigger creation shares the spell-resolution update before the trigger resolves.

Foretell's immediate face-down row cleanup remains an implementation regression
in `ForetellLifecycleTest`; the contracts own cast identities and offers.
Warp's contracts own the delayed-exile and subsequent cast flow. Its scoped
`TemporaryPermanent` exclusion remains an implementation check on the same
scripted stream. `WarpLifecycleTest` retains the two-mana and regular-cost controls.

Saga countered chapters and simultaneous two-lore triggers remain local regressions.
`SagaFinalChapterTest` also retains exact lore progression, pre-response sacrifice
absence, historical chapter parent and gameplay continuation. Cycling keeps exact
hand and graveyard count changes and its cache-cleared identity regression locally.
