---
summary: "RemoveAbility annotation: persistent removal of all or specific abilities from permanents by a continuous effect; per-instance detail keys, object projection and lifecycle."
entity: annotation/RemoveAbility
---
# RemoveAbility

## Meaning

A continuous effect can make a permanent lose all of its abilities, or specific
ones, while the effect lasts. `RemoveAbility` publishes that removal as
persistent state tied to the effect's identity. The affected object's own
ability list carries the result: removed abilities are absent from its
`uniqueAbilities` while the row is active.

## Rules grounding

Ability-removing effects apply in layer 6, interleaved with ability-adding
effects by timestamp (CR 613.1f, CR 613.7). A permanent that loses all
abilities keeps abilities that a later effect grants. Type-changing parts of
the same effect apply in their own layer.

## Variants

Persistent only, typed `RemoveAbility` and `LayeredEffect`.

| Variant | Value of each instance key | Examples | Co-types |
|---|---|---|---|
| Loses all abilities | The string `all` | Frozen in Ice, Witness Protection, Oko, Thief of Crowns, Harbinger of the Seas | `ModifiedType` when the same effect changes type; `AddAbility` when it grants a replacement ability, as Honest Work does |
| Loses specific abilities | The removed ability grpIds, as Int32 values | A Room's locked door | `ModifiedCost`, `TextChange`, `ModifiedName` |

## Fields

| Field | Role |
|---|---|
| `affectorId` | The card that supplies the effect: the Aura, the permanent with the static ability, the resolving ability's source, or the Room. |
| `affectedIds` | The permanents losing abilities. One row can cover several permanents affected by one static ability. |
| `<instanceId>` keys | One detail key per affected permanent, named by its instance ID as a decimal string. The value is `all` or the removed grpIds. |
| `effect_id` | The effect identity introduced by [LayeredEffectCreated](LayeredEffectCreated.md). |
| `sourceAbilityGRPID` | When present, the resolving or triggered ability that created the effect. Absent for Aura and static effects and for Room doors. |
| `grpid`, `UniqueAbilityId`, `originalAbilityObjectZcid` | Present only when the row is also `AddAbility`, with the [AddAbility](AddAbility.md) meanings. |

The values name no keywords. What was removed is read from the object's
ability list, not from the row.

## Identity model

The detail keys are the `affectedIds` written as decimal strings. A key names
an object, not an ability; the removed abilities are in the key's value.

## Lifecycle

1. `LayeredEffectCreated` introduces the effect identity.
2. In the same GSM the persistent `RemoveAbility` row appears and each
   affected object is published again without the removed abilities. The
   detail values do not change while the row lives.
3. When the effect ends while the permanent stays on the battlefield,
   `LayeredEffectDestroyed` names the identity, the row is deleted, and the
   object is published again with its printed abilities, all in one GSM.
4. When the affected permanent leaves the battlefield, the row is deleted in
   the GSM that carries its `ObjectIdChanged` and `ZoneTransfer`.
5. Unlocking a Room door deletes that door's row, without
   `LayeredEffectDestroyed`, and the object lists the unlocked ability in the
   same GSM.

## Object projection

- **Loses all abilities.** No printed ability remains in `uniqueAbilities`;
  the list can be empty. Abilities granted after the removal stay, with their
  own `UniqueAbilityId`. A type-changing effect leaves the new type's intrinsic
  ability, such as a basic land type's mana ability, as the only entry.
- **Loses specific abilities.** Only the named grpIds are absent. A Room lists
  its door-unlock actions and its unlocked door's ability, not the locked
  door's ability.

## Sibling shapes

[AddAbility](AddAbility.md) uses the same persistent model for grants, and one
row can carry both types. `Qualification` can also restrict what an object
does, but it does not remove abilities.

## Contract

- <a id="RemoveAbility.persistent"></a>**RemoveAbility.persistent.**
  `RemoveAbility` `MUST` be carried in `persistentAnnotations`, typed also
  `LayeredEffect`, with an `effect_id` detail.
- <a id="RemoveAbility.instance-keys"></a>**RemoveAbility.instance-keys.**
  The set of detail keys that are decimal instance IDs `MUST` equal the set of
  `affectedIds`.
- <a id="RemoveAbility.value-shape"></a>**RemoveAbility.value-shape.**
  Each instance key's value `MUST` be either the string `all` or one or more
  Int32 ability grpIds.
- <a id="RemoveAbility.loses-all-projection"></a>**RemoveAbility.loses-all-projection.**
  *(provisional)* For a permanent that is not changing type, while a row with
  value `all` names it, its `uniqueAbilities` `MUST NOT` list any of its
  printed abilities.
- <a id="RemoveAbility.expiry-restores"></a>**RemoveAbility.expiry-restores.**
  *(provisional)* When the effect ends while the permanent remains on the
  battlefield, the GSM that deletes the row `MUST` carry
  `LayeredEffectDestroyed` for its `effect_id` and `MUST` republish the object
  with its printed abilities.

## Open points

- How is a single removed keyword, such as "loses flying", represented?
- How are removals gated by counters or other conditions represented?
