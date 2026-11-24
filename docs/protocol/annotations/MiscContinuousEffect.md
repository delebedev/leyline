---
summary: "MiscContinuousEffect annotation: persistent continuous effect on a player without its own annotation family; maximum-hand-size, additional-land and extra-phase branches, companion types, identities and retirement."
entity: annotation/MiscContinuousEffect
---
# MiscContinuousEffect

## Meaning

Some continuous effects change a player's rules rather than a card's
characteristics: no maximum hand size, an additional land play each turn, or
an additional combat phase. `MiscContinuousEffect` publishes such an effect
as a persistent row on the player. The branch is selected by which detail
keys are present.

## Variants

| Branch | Detail keys | Also typed |
|---|---|---|
| Maximum hand size | `MaxHandSize`, `effect_id` | `LayeredEffect` |
| Additional land plays | `additional_lands`, `SourceParent`, `grpid`, `QualificationType`, `QualificationSubtype` | [Qualification](Qualification.md) |
| Extra phase | `extra_phases`, `grpid` | none |

The branches are separate shapes. A key from one branch is not present, or
defaulted, on another.

## Fields

| Field | Role |
|---|---|
| `affectorId` | The source: the permanent with the static ability, or the ability instance on the stack for an extra phase. |
| `affectedIds` | The seat of the affected player, as a one-element list. |
| `MaxHandSize` | The maximum hand size. "No maximum hand size" is `2147483647`. |
| `effect_id` | The effect identity introduced by [LayeredEffectCreated](LayeredEffectCreated.md). |
| `additional_lands` | The number of additional land plays. |
| `SourceParent` | The source object, the same ID as `affectorId`. |
| `grpid` | The ability definition that grants the land play or the extra phase. |
| `QualificationType`, `QualificationSubtype` | The `Qualification` fields of the land-play permission. |
| `extra_phases` | The `Phase` value of the additional phase, `3` for combat. |

## Identity model

`affectedIds` holds a seat ID, never an object. `affectorId` and
`SourceParent` are instance IDs; `grpid` is an ability definition;
`effect_id` is an effect identity (see
[identity and zones](../model/identity-and-zones.md) and
[enums](../model/enums.md)).

## Lifecycle

1. The effect starts, typically when its source resolves or enters the
   battlefield. In that GSM, a `LayeredEffectCreated` for `effect_id` comes
   first on the maximum-hand-size branch, and the row appears.
2. The row stays while the effect applies.
3. The row is deleted when the effect ends, for example when its source
   leaves the battlefield. `LayeredEffectDestroyed` for `effect_id` can
   accompany the deletion.

## Sibling shapes

The card-level families, such as [ModifiedType](ModifiedType.md),
[AddAbility](AddAbility.md) or `ModifiedPower`, describe changes to objects.
`MiscContinuousEffect` names a player, not an object.

## Contract

- <a id="MiscContinuousEffect.persistent"></a>**MiscContinuousEffect.persistent.**
  `MiscContinuousEffect` `MUST` be carried in `persistentAnnotations`, with
  one seat ID in `affectedIds`.
- <a id="MiscContinuousEffect.hand-size-layered"></a>**MiscContinuousEffect.hand-size-layered.**
  A row with `MaxHandSize` `MUST` also be typed `LayeredEffect` and carry
  `effect_id`.
- <a id="MiscContinuousEffect.land-qualification"></a>**MiscContinuousEffect.land-qualification.**
  *(provisional)* A row with `additional_lands` `MUST` also be typed
  `Qualification`, with `SourceParent` equal to `affectorId`.

## Open points

- When does a hand-size row end with `LayeredEffectDestroyed`, and when
  without?
- How are extra turns, and other player-level effects, published?
