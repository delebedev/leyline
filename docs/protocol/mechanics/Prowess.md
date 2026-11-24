---
summary: "Prowess mechanic: the shared prowess trigger linked to the noncreature spell that caused it, and its +1/+1 resolution without a ResolutionStart row."
entity: mechanic/Prowess
---
# Prowess

Prowess is a triggered ability: whenever its controller casts a noncreature
spell, the creature gets +1/+1 until end of turn. The mechanic uses one prowess
ability shared by every card, a triggered [Ability](../object_types/Ability.md)
linked to the causing spell by
[TriggeringObject](../annotations/TriggeringObject.md), and a temporary stat
effect carried by [LayeredEffectCreated](../annotations/LayeredEffectCreated.md)
and `PowerToughnessModCreated`.

## Rules grounding

CR 702.108a defines prowess. CR 702.108b makes each instance trigger
separately.

## Surfaces

| Surface | Entity pages |
|---|---|
| Trigger | [Ability](../object_types/Ability.md), [AbilityInstanceCreated](../annotations/AbilityInstanceCreated.md), [TriggeringObject](../annotations/TriggeringObject.md) |
| Stat change | [LayeredEffectCreated](../annotations/LayeredEffectCreated.md), `PowerToughnessModCreated`, persistent `ModifiedPower` and `ModifiedToughness` |
| Resolution | [ResolutionComplete](../annotations/ResolutionComplete.md), [AbilityInstanceDeleted](../annotations/AbilityInstanceDeleted.md) |

## Trigger lifecycle

1. In the GSM where the noncreature spell is cast, `AbilityInstanceCreated`
   creates an `Ability` with `grpId` `137` from the prowess creature, with
   `source_zone` the Battlefield. Each prowess source, and each instance on
   one source, creates its own `Ability`.
2. A persistent `TriggeringObject` row names the prowess ability as
   `affectorId` and the causing spell as affected, with `source_zone` the
   Stack.

## Resolution lifecycle

The resolution GSM carries, without a `ResolutionStart`:

1. While the creature is still on the battlefield, `LayeredEffectCreated`
   from the prowess ability, and `PowerToughnessModCreated` from the ability to the creature with `power`
   `1` and `toughness` `1`.
2. `ResolutionComplete` with `grpid` `137`, then `AbilityInstanceDeleted`.
3. A persistent `ModifiedPower` and `ModifiedToughness` row on the creature,
   with `sourceAbilityGRPID` `137` and the new effect's `effect_id`.

The creature keeps its instance ID. The effect ends through the ordinary
end-of-turn removal of layered effects.

## Sibling shapes

Other "whenever you cast a noncreature spell" triggers, such as flurry or
magecraft, link the causing spell through `TriggeringObject` in the same way
but carry their own card-specific ability rather than `137`.

## Contract

- <a id="Prowess.shared-ability"></a>**Prowess.shared-ability.** A prowess
  trigger `MUST` be an `Ability` with `grpId` `137`, and its
  `ResolutionComplete` `MUST` carry `grpid` `137`.
- <a id="Prowess.causing-spell"></a>**Prowess.causing-spell.** A prowess
  trigger's `TriggeringObject` row `MUST` name the causing spell as affected,
  with `source_zone` the Stack.
- <a id="Prowess.no-resolution-start"></a>**Prowess.no-resolution-start.**
  *(provisional)* The resolution GSM of a prowess trigger `MUST NOT` carry a
  `ResolutionStart` for it.
- <a id="Prowess.stat-change"></a>**Prowess.stat-change.** When a prowess
  trigger resolves while its source creature is still on the battlefield,
  the resolution `MUST` carry `PowerToughnessModCreated` from the trigger to its
  source creature with `power` `1` and `toughness` `1`.

## Open points

- What does a prowess trigger's resolution carry when its creature has left
  the battlefield?
- Why does the prowess resolution carry no `ResolutionStart`, and which other
  triggers resolve without one?
- Which GSM deletes the `ModifiedPower` and `ModifiedToughness` row at end of
  turn?
