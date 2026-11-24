---
summary: "ModifiedCost annotation: persistent mana-cost change by a continuous effect, seen as part of a Room's locked-door row shared with TextChange, ModifiedName and RemoveAbility; fields, identities and retirement."
entity: annotation/ModifiedCost
---
# ModifiedCost

## Meaning

A continuous effect can change a permanent's mana cost along with other
characteristics. The known case is a Room: while one of its doors is locked,
the permanent does not have that half's name, mana cost or abilities
(CR 709.5). One persistent row carries all of those changes, typed
`ModifiedCost`, `TextChange`, `ModifiedName`, `RemoveAbility` and
`LayeredEffect`. The object itself carries the resulting characteristics.

## Variants

Persistent only. Every known row has the five types above, one per locked
door of the permanent.

## Fields

| Field | Role |
|---|---|
| `affectorId` | The permanent itself. |
| `affectedIds` | The same permanent, as a one-element list. |
| `<instanceId>` key | The permanent's instance ID as a decimal string. Its value lists the ability grpIds of the locked door, which the permanent does not have. This is the [RemoveAbility](RemoveAbility.md) detail. |
| `effect_id` | The effect identity introduced by [LayeredEffectCreated](LayeredEffectCreated.md). |

## Identity model

The detail key is an object identity used as a field name; the values are
ability definitions; `effect_id` is an effect identity; the row's own `id`
identifies the combined row. A permanent with two locked doors has two rows
with distinct `effect_id` values.

## Lifecycle

1. The Room enters with a locked door: `LayeredEffectCreated` introduces the
   identity and the row appears in the same GSM.
2. The row stays while the door is locked.
3. Unlocking the door deletes the row without `LayeredEffectDestroyed`, and
   the permanent is republished with that half's characteristics.

## Sibling shapes

[RemoveAbility](RemoveAbility.md) describes the detail format and object
projection this row shares. [ModifiedType](ModifiedType.md) is the
type-line counterpart for effects that change types.

## Contract

- <a id="ModifiedCost.combined-row"></a>**ModifiedCost.combined-row.**
  *(provisional)* A `ModifiedCost` row `MUST` also be typed `TextChange`,
  `ModifiedName`, `RemoveAbility` and `LayeredEffect`, and carry `effect_id`.
- <a id="ModifiedCost.self-envelope"></a>**ModifiedCost.self-envelope.**
  `affectorId` `MUST` equal the single entry of `affectedIds`.
- <a id="ModifiedCost.removed-absent"></a>**ModifiedCost.removed-absent.**
  While the row is live, no grpId listed under the instance key `MUST` appear
  in the permanent's `uniqueAbilities`.

## Open points

- Do other cost-changing continuous effects use `ModifiedCost`, and with
  which details?
