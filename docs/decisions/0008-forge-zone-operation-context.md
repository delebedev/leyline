---
summary: "ADR: preserve immutable Forge cause identity on ordered zone-change events; use snapshots only as an observable degraded fallback."
read_when:
  - "changing Forge GameEventCardChangeZone or zone-move event collection"
  - "classifying transfer category, source, cost, cast, or resolution moves"
  - "working on same-frame multi-hop zone changes such as Omen or Paradigm"
---
# ADR 0008: Forge Zone-Operation Context

## Status

Proposed; payload contract locked for implementation review.

## Context

Forge changes zones through `GameAction.changeZone`. At that point it has the
ordered operation, the direct `SpellAbility` cause, wrapper parameters, and the
active cost-payment entry. `GameEventCardChangeZone` currently freezes only the
card and its source and destination zone views. The bridge therefore recovers
discarded cause facts later from the live stack, zone pairs, prompt journals,
neighboring events, and final snapshots.

Snapshots answer what is true at frame close. They cannot preserve two moves of
the same object in one frame. Events answer what happened and in which order.
The two models remain separate.

## Producer Inventory

There are two direct `GameEventCardChangeZone` producers, both in
`GameAction.changeZone`:

1. The development-mode `zoneFrom == null` branch, after adding the object.
2. The normal branch, after the destination has accepted the object and static
   effects have settled, immediately before `ChangesZone` triggers run.

All normal wrappers converge on that second producer:

- `moveTo`, `moveToStack`, `moveToGraveyard`, `moveToHand`, and `moveToPlay`;
- `moveToLibrary`, variant-deck moves, and `moveToJunkyard`;
- `exile`, `destroy`, `sacrifice`, discard, mill, surveil, draw, search, bounce,
  and counter paths that call those wrappers;
- replacement effects that recursively re-enter `changeZone` with updated
  parameters.

`ceaseToExist` is the material exception. It removes copied spells, tokens, and
other transient objects without firing `GameEventCardChangeZone`. That path is
part of the named fallback boundary until Forge emits a dedicated immutable
event for it.

## Producer And Event-Order Matrix

`SA` means the exact Forge `SpellAbility`; `root` means
`SA.getRootAbility()`. “Payment” is `game.costPaymentStack.peek()` at the event
call.

| Operation | `changeZone` input available at emission | Neighboring events, in order | Intermediate in one frame? | Contract implication |
|---|---|---|---|---|
| Regular cast | direct cause = cast SA; source card, SA id, root id, API; payment normally empty after costs | `CardChangeZone(Hand→Stack)`, `SpellMovedToStack`, `SpellAbilityCast` | yes | Freeze cast SA identity on the move; do not recover it from the later stack event. |
| Permanent resolve | direct cause = spell SA in `SpellPermanent`; root/source stable | `CardChangeZone(Stack→Battlefield)`, then `SpellResolved` | yes | Resolution category comes from ordered lifecycle plus root identity. |
| Instant/sorcery resolve | direct cause is `null`; `params[StackSa]` contains the resolving SA | `SpellResolved`, then `CardChangeZone(Stack→Graveyard)` | yes | Context extraction must fall back to `StackSa`; direct cause alone is insufficient. |
| Destroy effect | direct cause = destroying SA; source card/API present | `CardDestroyed`, then `CardChangeZone(Battlefield→Graveyard)` | usually no | Specific event supplies operation; context supplies exact/root ability and affector. |
| Sacrifice as effect | direct cause = effect SA; payment empty | `CardSacrificed`, then `CardChangeZone` | possibly | `costPayment=false` distinguishes this from a paid cost. |
| Sacrifice as cost | direct cause = paid-for SA; payment entry contains the same paid-for ability and `CostSacrifice` | `CardSacrificed`, then `CardChangeZone`, while payment remains live | possibly | Freeze payment status and paid-for ability before the stack pops. |
| State-based death / legend rule | direct cause `null`; payment empty | `CardDestroyed` with no activator, then `CardChangeZone` | several objects may move | Absence of cause is meaningful. Specific SBA/legend facts stay separate events; no invented reason enum. |
| Discard | direct cause = discard SA when effect-driven; params contain `Discard` and `EffectOnly`; payment identifies discard-as-cost | `CardChangeZone`, then log/trigger events | yes | API/root/payment distinguish discard from a generic Hand→Graveyard move. |
| Mill | direct cause = mill SA; API = `Mill`; wrapper moves each card separately | one `CardChangeZone` per card, then mill triggers | yes, many cards | Preserve each ordered card move and shared source identity. |
| Surveil | direct cause = surveil SA; API = `Surveil` | `CardChangeZone`, `CardSurveiled` per moved card, then aggregate `Surveil` | yes, many cards | Later specific event refines the move; it must not replace or reorder it. |
| Exile | direct cause = exiling SA when one exists; API/root/source present | `CardChangeZone`, then `Exiled` trigger processing | yes | Affector comes from context; under-card display remains separate state. |
| Search / tutor | direct cause normally = `ChangeZone` SA; source/root present | one `CardChangeZone` per result, then search/shuffle lifecycle | yes | API + ordered lifecycle replace prompt-journal correlation. |
| Draw | cause is draw SA for effect draws and may be `null` for turn draw | `CardChangeZone(Library→Hand)` before draw triggers | yes | `Draw` API identifies effect draws; cause absence plus turn lifecycle covers normal draw. |
| Bounce | direct cause = moving SA; source/root present | `CardChangeZone(Battlefield→Hand/Library)` | possibly | Zone pair gives outcome; context gives source and ability. |
| Counter | direct cause = countering SA; `params[StackSa]` contains the countered SA | `SpellRemovedFromStack`, then `CardChangeZone(Stack→destination)` | yes | Direct context identifies affector; `StackSa` identifies the affected spell lifecycle. |
| Token disappearance | `ceaseToExist` bypasses `changeZone` | no `CardChangeZone` for the disappearance | yes | Explicit snapshot-only/deletion fallback until Forge emits a fact. |
| Omen | Hand→Stack carries Omen cast SA; replacement moves Stack→Library with its override SA/root | two ordered `CardChangeZone` events around cast/resolve events | yes | Ledger preserves both legs even when the snapshot is Hand→Library. |
| Paradigm original/copy | cast leg carries spell/copy SA; appended ChangeZone sub-ability carries the same root into Stack→Exile | ordered Hand/Exile→Stack and Stack→Exile moves | yes | Root identity joins the legs without final-snapshot reconstruction. |

The characterization tests pin the two producer facts most vulnerable to
regression: cost-payment context is still live during emission, and instant and
permanent resolution place `SpellResolved` on opposite sides of the final zone
move. They also pin destroy/sacrifice ordering and same-frame multi-hop order.

## Decision

Add one reusable, immutable, protocol-neutral record in Forge:

```java
public record GameEventZoneChangeCause(
    int sourceCardId,
    int abilityId,
    int rootAbilityId,
    ApiType api,
    boolean costPayment
) implements Serializable {}
```

Field rules:

- `sourceCardId`: exact effective SA host's Forge card id; `0` when absent.
- `abilityId`: exact effective SA id; `0` when absent.
- `rootAbilityId`: `effectiveSa.getRootAbility().getId()`; `0` when absent.
- `api`: exact effective SA API; nullable because permanent spells and some
  engine abilities have no API.
- `costPayment`: true when the move fires with a live payment entry whose
  payment names the effective or root SA.

Effective-SA precedence is deterministic:

1. Active payment entry's paid-for ability, when present.
2. Direct `changeZone` cause.
3. `params[AbilityKey.StackSa]` for post-resolution and counter paths.
4. `params[AbilityKey.Cause]` for wrappers that carry cause only in parameters.
5. No context.

`GameEventCardChangeZone` gains a nullable `cause` component of this record
type. Keep the existing three-argument constructor and delegate it to
`cause = null` for source compatibility with synthetic callers. Add a
production constructor that receives the direct cause, parameter map, and
current payment entry and freezes the record before `fireEvent` returns.

Do not retain `SpellAbility`, `Card`, `CostPayment`, `CostPart`, parameter maps,
or stack references in the event. Do not add protocol object ids, transfer
categories, annotation names, or wire enums to Forge.

One cause record is sufficient. The concrete distinctions that are not cause
facts already have specific events (`CardDestroyed`, `CardSacrificed`,
`CardSurveiled`, cast, and resolve) or are expressible by API + zone pair +
payment state. A broad `ZoneChangeReason` enum would duplicate those facts and
drift as new mechanics arrive.

## Serialization And Compatibility

All new components are primitives or serializable Forge enums/records. The
serialization contract test must include `GameEventZoneChangeCause`.

Adding a record component changes Java serialized form. Forge event producers
and consumers therefore roll out atomically in the same build. The retained
three-argument constructor provides source compatibility, not old-stream binary
compatibility. Consumers must accept `cause = null` so development-mode,
synthetic, and transitional emitters degrade explicitly.

## Leyline Migration Map

| Current inference | Source after migration | Fallback retained? |
|---|---|---|
| `GameEventCollector` live-stack peek for mill source | `cause.sourceCardId` and `cause.api` | only for events with null context |
| Prompt-journal correlation for searched-to-hand | `cause.api`, root id, and Library→Hand pair | temporary shadow comparison only |
| Zone-pair branches for discard, draw, mill, bounce, exile, cast, resolve, and counter | ordered move + cause API + specific lifecycle event | named snapshot fallback only |
| Adjacent-event priority table in `TransferCategoryResolver` | pure ordered fold over `ZoneMove` plus specific events | removed after shadow parity |
| Destroy/sacrifice affector recovery | `cause.sourceCardId`, exact/root ability ids | null-context fallback |
| Live-stack/ability lookup during transfer planning | frozen exact/root ability ids through `AbilityRegistry` | no normal-path lookup |
| Omen and Paradigm collapsed-flow reconstruction | two or more ordered ledger moves sharing root/source identity | snapshot expansion only when a move event is absent |
| Final snapshot as intermediate-zone evidence | event ledger | snapshot remains final-state projection only |

Lifecycle events for cast, resolve, sacrifice, and destroy should separately gain
the same stable source/exact/root identity where their present views are
insufficient. They reuse the identity fields; they do not introduce a second
cause taxonomy.

## Fallback Boundary And Observability

Snapshot-only transfer inference remains for:

- `ceaseToExist` and other Forge paths proven not to emit a zone-change event;
- synthetic tests or development helpers using the three-argument event
  constructor;
- temporarily mixed event producers during migration.

Every transfer plan records its origin as `EVENT` or `SNAPSHOT_FALLBACK` in
Leyline-internal data. A fallback increments a counter and logs one structured
diagnostic with Forge card id and source/destination zones. Shadow tests assert
the fallback count for their fixture. Normal gameplay tests require zero
fallbacks unless the fixture names an accepted missing-event path.

Fallback is degraded operation, not a second authority: it may recover the final
zone pair, but it must not invent an affector or intermediate move.

## Consequences

- Event order becomes the durable source for multi-hop movement.
- Cause and ability identity cross the Forge boundary once, as immutable values.
- Snapshots remain authoritative for final projected state and continuous
  characteristics.
- New mechanics extend engine facts or the pure fold instead of adding a live
  correlation branch.
- Missing events become measurable and testable.

## Alternatives Considered

- **Store `SpellAbility` on the event** — rejected: mutable engine objects are
  unsafe after emission and unsuitable for serialization.
- **Infer everything from specific events** — rejected: several moves have no
  specific event, and instant resolution orders its final move after the
  summary event.
- **Add `ZoneChangeReason`** — rejected: it duplicates API, payment, zone, and
  existing lifecycle facts without evidence of an irreducible new dimension.
- **Keep snapshots as the primary transfer source** — rejected: final state
  cannot represent ordered intermediate moves.
