---
summary: "Crew: a Vehicle's activated ability paid by tapping creatures with enough total power; the total-power cost prompt, the CrewedThisTurn row and the type change at resolution."
entity: mechanic/Crew
---
# Crew

Crew is an activated ability of a Vehicle. Its cost taps untapped creatures
with enough total power; when it resolves, the Vehicle becomes an artifact
creature until end of turn. The cost is the total-weight branch of
[PayCostsReq](../prompts/PayCostsReq.md); the helpers are remembered by a
persistent `CrewedThisTurn` row; the type change is a
[ModifiedType](../annotations/ModifiedType.md) row.

## Rules grounding

Crew N means "Tap any number of other untapped creatures you control with
total power N or greater: This permanent becomes an artifact creature until
end of turn" (CR 702.122a). The creatures tapped to pay that cost crew the
Vehicle (CR 702.122b, CR 702.122c).

## Surfaces

| Surface | Entity pages | Crew values |
|---|---|---|
| Activation | [Ability](../object_types/Ability.md), [UserActionTaken](../annotations/UserActionTaken.md) | `actionType` `2`, the Vehicle's crew ability |
| Total-power cost | [PayCostsReq](../prompts/PayCostsReq.md) | `minSel` N, creature power in `weights` |
| Helpers tapped | [TappedUntappedPermanent](../annotations/TappedUntappedPermanent.md), `CrewedThisTurn` | The crew ability taps; the Vehicle names the helpers |
| Type change | [LayeredEffectCreated](../annotations/LayeredEffectCreated.md), [ModifiedType](../annotations/ModifiedType.md) | `sourceAbilityGRPID` the crew ability |

## Activation lifecycle

1. A GSM puts the crew ability on the stack: `AbilityInstanceCreated` from
   the Vehicle with `source_zone` `28`. The ability's `grpId` is the
   Vehicle's crew ability, which carries N.
2. The activating player is asked for the helpers through a `PayCostsReq`
   of the total-weight form: the `CardId` parameter names the crew ability
   on the stack, `ids` lists the eligible creatures, `weights` their power,
   and `minSel` is N. Crew 1 to 4 use prompts `8929`, `8924`, `8925` and
   `8922`, the prompts of any "tap creatures with total power N" cost.
3. The next GSM taps each chosen creature with `TappedUntappedPermanent`,
   with the crew ability as `affectorId`, records `UserActionTaken` with
   `actionType` `2` and the crew ability in `abilityGrpId`, and creates the
   persistent `CrewedThisTurn` row: the Vehicle as `affectorId` and the
   helpers in `affectedIds`, with no details.

## Resolution lifecycle

1. A later GSM carries `ResolutionStart` for the crew ability,
   `LayeredEffectCreated` with the crew ability as `affectorId`, and
   `ResolutionComplete`, then `AbilityInstanceDeleted` from the Vehicle.
2. The same GSM creates a persistent `ModifiedType` and `LayeredEffect` row
   on the Vehicle, naming the Vehicle as `affectorId`, with
   `sourceAbilityGRPID` the crew ability and the new `effect_id`.

## Sibling shapes

Saddle uses the same total-power cost prompts. Its helpers are recorded by
`SaddledThisTurn`, and its resolution grants the [Saddled](../designations/Saddled.md) designation instead
of changing the Mount's type. [Convoke](Convoke.md) also taps creatures, but
as mana for a spell.

## Contract

- <a id="Crew.cost-source"></a>**Crew.cost-source.** The crew cost's
  `PayCostsReq` `MUST` use the total-weight form with `minSel` equal to the
  crew value N, and its `CardId` parameter `MUST` name the crew ability on
  the stack.
- <a id="Crew.tap-affector"></a>**Crew.tap-affector.** Each creature tapped to
  crew `MUST` be tapped by a `TappedUntappedPermanent` row whose `affectorId`
  is the crew ability.
- <a id="Crew.crewed-this-turn"></a>**Crew.crewed-this-turn.** The GSM that
  taps the helpers `MUST` create a persistent `CrewedThisTurn` row naming the
  Vehicle as `affectorId` and every helper in `affectedIds`.
- <a id="Crew.type-change"></a>**Crew.type-change.** *(provisional)* The crew
  ability's resolution `MUST` create a persistent `ModifiedType` row on the
  Vehicle, naming the Vehicle as `affectorId`, with `sourceAbilityGRPID` the
  crew ability.

## Open points

- Is the cost prompt skipped when only one set of creatures can pay?
- When are the `CrewedThisTurn` and `ModifiedType` rows deleted, and does a
  second crew of the same Vehicle in one turn extend the first row?
- Do Crew values above 4 have their own prompt IDs?
