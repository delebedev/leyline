---
summary: "Power-up mechanic: a once-only activated ability committed as an ordinary activation, and the persistent AbilityExhausted row that records its use."
entity: mechanic/PowerUp
---
# Power-up

Power-up is an activated ability that can be activated only once, and costs
less if the permanent entered this turn. The mechanic uses an ordinary
`Activate` offer and stack [Ability](../object_types/Ability.md), and records
the spent use with a persistent `AbilityExhausted` row on the source. The
effect after the colon is the card's own.

## Rules grounding

CR 702.193a defines power-up as an activated ability that can be activated only
once and whose cost is reduced by the permanent's mana cost if it entered this
turn. CR 702.193b sets how colored mana of that reduction applies.

## Surfaces

| Surface | Entity pages |
|---|---|
| Activation | [Ability](../object_types/Ability.md), [AbilityInstanceCreated](../annotations/AbilityInstanceCreated.md), [UserActionTaken](../annotations/UserActionTaken.md) |
| Use spent | persistent `AbilityExhausted` |

## Activation lifecycle

1. The permanent is offered as an `Activate` action whose `abilityGrpId` is
   its power-up ability.
2. On submission the server creates a stack `Ability` with that `grpId`,
   `parentId` the source, and `AbilityInstanceCreated` from the source with
   `source_zone` the Battlefield.
3. Mana is paid as for any activated ability.
4. The GSM that commits the payment carries `UserActionTaken` with
   `actionType` `2` and the power-up ability, and creates a persistent
   `AbilityExhausted` row with the source as `affectorId` and affected ID,
   `AbilityGrpId` the power-up ability, `UniqueAbilityId` the source's slot
   for it, and `UsesRemaining` `0`.
5. The ability resolves with the card's own effect rows.

## Sibling shapes

Exhaust and boast abilities also carry a use limit and publish the same
`AbilityExhausted` row when spent.

## Contract

- <a id="PowerUp.accepted"></a>**PowerUp.accepted.** A power-up activation
  `MUST` be recorded by `UserActionTaken` with `actionType` `2` and
  `abilityGrpId` naming the power-up ability.
- <a id="PowerUp.exhausted"></a>**PowerUp.exhausted.** *(provisional)* The
  GSM that commits a power-up activation `MUST` create a persistent
  `AbilityExhausted` row on the source with `AbilityGrpId` the power-up
  ability and `UsesRemaining` `0`.

## Open points

- How does the reduced cost for a permanent that entered this turn appear in
  the offered action and the payment?
- When is the `AbilityExhausted` row retired, for example when the permanent
  leaves the battlefield?
