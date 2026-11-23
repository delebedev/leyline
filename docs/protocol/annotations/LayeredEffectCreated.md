---
summary: "LayeredEffectCreated annotation: introduces the synthetic identity of one continuous effect; the effect_id link to persistent modification rows and retirement through LayeredEffectDestroyed."
read_when:
  - "emitting or consuming LayeredEffectCreated or LayeredEffectDestroyed"
  - "publishing a continuous effect that modifies characteristics, abilities or control"
  - "linking a persistent modification row to its effect through effect_id"
entity: annotation/LayeredEffectCreated
---
# LayeredEffectCreated

## Meaning

Continuous effects are applied in layers (CR 613). The protocol gives each
continuous effect a synthetic identity while it is in force.
`LayeredEffectCreated` is the transient event that introduces one such
identity. The persistent rows that describe what the effect does carry the same
identity in their `effect_id` detail.

## Rules grounding

A continuous effect from a resolving spell or ability lasts for its stated
duration (CR 611.2). A continuous effect from a static ability lasts as long as
the ability applies (CR 611.3).

## Variants

One transient row per introduced effect. The row carries no details; what the
effect does is on the persistent rows that name it.

## Fields

| Field | Role |
|---|---|
| `affectorId` | When present, the source that began the effect, such as the resolving ability instance or the permanent with the static ability. Many rows have none. |
| `affectedIds` | The synthetic effect identity, as a one-element list. It names an effect, not the object or player the effect modifies. |
| details | None. |

## Identity model

Effect identities are integers from a range above the instance IDs of game
objects. They appear in three places:

- `affectedIds` of `LayeredEffectCreated`, which introduces the identity;
- the `effect_id` detail of each persistent row describing the effect, such as
  one typed `LayeredEffect` together with `ModifiedPower` and
  `ModifiedToughness`, `AddAbility`, `RemoveAbility`, `ModifiedType`,
  `ModifiedColor`, `CopiedObject`, `ControllerChanged` or
  `MiscContinuousEffect`;
- `affectedIds` of `LayeredEffectDestroyed`, which ends it.

The objects or players the effect modifies are the `affectedIds` of those
persistent rows.

## Lifecycle

1. A resolution, a static ability starting to apply, or a recomputed effect
   introduces an identity with `LayeredEffectCreated`.
2. The persistent rows for the effect carry that identity as `effect_id`, in
   the same GSM unless the identity was introduced in advance. A Spacecraft,
   for example, has identities introduced when it is cast and used by the row
   that grants its abilities once its station threshold is reached.
3. When the effect ends, `LayeredEffectDestroyed` names the identity and the
   persistent rows are deleted.
4. An effect that is recomputed can destroy its old identities and introduce
   new ones in the same GSM.

For example, a +2/+2 pump spell resolving publishes `LayeredEffectCreated` for
a new identity, and a persistent row typed `ModifiedPower`, `ModifiedToughness`
and `LayeredEffect` on the target with that identity as `effect_id`.

## Sibling shapes

| Annotation | Role |
|---|---|
| `LayeredEffectCreated` | Transient birth of an effect identity. |
| `LayeredEffect` | Persistent type on the row that describes the active effect, combined with the specific modification type. |
| `LayeredEffectDestroyed` | Transient end of an effect identity. |

`PowerToughnessModCreated` is a separate transient event for a direct
power/toughness change; it does not introduce an effect identity.

## Contract

- <a id="LayeredEffectCreated.no-details"></a>**LayeredEffectCreated.no-details.**
  A `LayeredEffectCreated` row `MUST NOT` carry detail keys.
- <a id="LayeredEffectCreated.single-effect"></a>**LayeredEffectCreated.single-effect.**
  A row `MUST` carry exactly one effect identity in `affectedIds`.
- <a id="LayeredEffectCreated.introduces-effect-id"></a>**LayeredEffectCreated.introduces-effect-id.**
  A persistent row that carries `effect_id` `MUST` name an identity that a
  `LayeredEffectCreated` introduced in the same GSM or an earlier one.
- <a id="LayeredEffectCreated.destroyed-names-created"></a>**LayeredEffectCreated.destroyed-names-created.**
  A `LayeredEffectDestroyed` `MUST` name in `affectedIds` an identity that a
  `LayeredEffectCreated` introduced earlier.

## Open points

- What does `affectorId` name when it is present but is not an object, and
  what selects its absence?
- When an effect ends, which persistent rows carrying its identity are deleted
  in the `LayeredEffectDestroyed` GSM and which later?
- What rule decides when a static effect is recomputed under a new identity?
