---
summary: "Casualty: an optional creature sacrifice when casting that copies the spell; the type-15 CastingTimeOption, the Sacrifice transfer caused by the spell, and the trigger that makes a Stack-to-Stack copy."
entity: mechanic/Casualty
---
# Casualty

Casualty N lets the caster sacrifice a creature with power N or more while
casting an instant or sorcery; if they do, a cast trigger copies the spell.
The cast is an ordinary [Cast](../actions/Cast.md). The chosen cost is a
[CastingTimeOption](../annotations/CastingTimeOption.md) row of `type` `15`,
the sacrifice is a [ZoneTransfer](../annotations/ZoneTransfer.md) caused by
the spell, and the copy is a Stack-to-Stack `Copy` transfer caused by the
Casualty trigger.

## Rules grounding

Casualty N means "As an additional cost to cast this spell, you may sacrifice a creature with
power N or greater" and "When you cast this spell, if a casualty cost was
paid for it, copy it. If the spell has any targets, you may choose new
targets for the copy" (CR 702.153a).

## Surfaces

| Surface | Entity pages | Casualty values |
|---|---|---|
| Chosen cost | [CastingTimeOption](../annotations/CastingTimeOption.md) | `type` `15`, `casualtyAbilityGrpId` the card's Casualty ability |
| Sacrifice | [ZoneTransfer](../annotations/ZoneTransfer.md) | Battlefield to Graveyard, `category` `Sacrifice`, the spell as `affectorId` |
| Copy trigger | [AbilityInstanceCreated](../annotations/AbilityInstanceCreated.md), [ResolutionStart](../annotations/ResolutionStart.md) | `source_zone` `27`; `grpid` the Casualty ability |
| Copy | [ZoneTransfer](../annotations/ZoneTransfer.md) | Stack to Stack, `category` `Copy` |

## Cast lifecycle

1. The GSM after the spell moves to the stack creates a persistent
   `CastingTimeOption` row on the spell with `type` `15` and
   `casualtyAbilityGrpId` the card's Casualty ability, alongside
   `PlayerSelectingTargets` for the spell.
2. After targets are submitted, the GSM that completes the cast moves the
   chosen creature from the Battlefield to the Graveyard with
   `ObjectIdChanged` and a `ZoneTransfer` with `category` `Sacrifice`, both
   with the spell as `affectorId`, and records `UserActionTaken` with
   `actionType` `1` and `abilityGrpId` `0`.
3. The same GSM creates the Casualty trigger with `AbilityInstanceCreated`
   from the spell, with `source_zone` `27`.

## Copy lifecycle

1. The trigger resolves with `ResolutionStart` whose `grpid` is the Casualty
   ability.
2. The same GSM carries a `ZoneTransfer` from the Stack to the Stack with
   `category` `Copy` and the trigger as `affectorId`, naming the new copy.
3. The copy receives its own `type` `15` `CastingTimeOption` row and a
   `PlayerSelectingTargets` row for its new targets.
4. Original and copy then resolve as separate spells.

## Sibling shapes

Bargain and other optional additional costs also mark the spell with a
`CastingTimeOption` row, with their own `type`. Other copy triggers use the
same `Copy` transfer without a casualty row.

## Contract

- <a id="Casualty.cast-option"></a>**Casualty.cast-option.** *(provisional)*
  A spell whose casualty cost is being paid `MUST` carry a persistent
  `CastingTimeOption` row with `type` `15` and `casualtyAbilityGrpId` the
  card's Casualty ability.
- <a id="Casualty.sacrifice"></a>**Casualty.sacrifice.** *(provisional)* When
  no replacement effect changes where it goes, the sacrificed creature
  `MUST` move Battlefield to Graveyard with `category`
  `Sacrifice` and the spell as `affectorId`, before the cast's
  `UserActionTaken`.
- <a id="Casualty.copy"></a>**Casualty.copy.** *(provisional)* The Casualty
  trigger's resolution `MUST` carry `ResolutionStart` with `grpid` the
  Casualty ability and a Stack-to-Stack `ZoneTransfer` with `category`
  `Copy`.

## Open points

- How is the sacrifice published when a replacement effect sends the
  creature somewhere other than the graveyard?
- Which prompt offers the casualty choice and the creature to sacrifice?
- Why does the copy, which cannot pay Casualty, carry its own `type` `15`
  row?
- How are two Casualty instances on one spell published?
