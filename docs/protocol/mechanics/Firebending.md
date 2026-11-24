---
summary: "Firebending: an attack trigger that adds red mana kept until end of combat; the trigger, the mana-pool entry, the ManaDetails row that keeps the mana, spending and cleanup."
entity: mechanic/Firebending
---
# Firebending

Firebending N is an attack trigger that adds N red mana, which its controller
keeps as steps and phases end until end of combat. The trigger is an ordinary
triggered [Ability](../object_types/Ability.md). Its resolution adds an entry
to the controller's `manaPool` and a persistent `ManaDetails` row that marks
the mana as not emptying. Firebending has no target, no choice and no zone
change.

## Rules grounding

"Firebending N" means "Whenever this creature attacks, add N {R}. Until end
of combat, you don't lose this mana as steps and phases end." (CR 702.189a).
An ability that triggers whenever a player firebends triggers when a
firebending ability they control resolves (CR 702.189b).

## Surfaces

| Surface | Entity pages |
|---|---|
| Attack trigger | [Ability](../object_types/Ability.md), [AbilityInstanceCreated](../annotations/AbilityInstanceCreated.md), [TriggeringObject](../annotations/TriggeringObject.md) |
| Mana | `PlayerInfo.manaPool`, `ManaDetails` |
| Spending | [ManaPaid](../annotations/ManaPaid.md) |

## Trigger lifecycle

1. Attackers are declared. `AbilityInstanceCreated` names the attacking
   creature as `affectorId`, the firebending ability as affected ID and
   `source_zone` `28`.
2. A persistent `TriggeringObject` row points from the ability to the
   creature. With several attack triggers, the ability can wait in Pending
   before it moves to the Stack.
3. The ability resolves with no prompt. `ResolutionStart` and
   `ResolutionComplete` carry the firebending ability's `grpid`.
4. The resolution GSM adds a `manaPool` entry for the controller with
   `color` `ManaColor_Red`, `count` N, `srcInstanceId` naming the creature
   and `abilityGrpId` naming the firebending ability.
5. The same GSM creates a persistent `ManaDetails` row with the creature as
   `affectorId`, the entry's `manaId` as affected ID and a
   `ManaSpecType_DoesNotEmpty` detail.

## Mana lifecycle

1. Spending the mana produces ordinary `ManaPaid` rows naming the creature,
   one row per red mana spent, each carrying the same `manaId`.
2. Spending part of the mana lowers the entry's `count`; the `ManaDetails`
   row stays.
3. Spending all of it deletes the `ManaDetails` row.
4. Unspent mana and its `ManaDetails` row are removed when combat ends.

## Sibling shapes

Battle cry, mentor and training are attack triggers too, but they change
creatures rather than add mana. A land's mana ability is an `Activate_Mana`
action that leaves no stack object; firebending's mana comes from a resolved
trigger.

## Contract

- <a id="Firebending.pool-entry"></a>**Firebending.pool-entry.** The
  resolution of a firebending ability `MUST` add a `manaPool` entry with
  `color` `ManaColor_Red`, `srcInstanceId` naming the creature and
  `abilityGrpId` naming the firebending ability.
- <a id="Firebending.mana-details"></a>**Firebending.mana-details.** The same
  GSM `MUST` create a persistent `ManaDetails` row whose `affectorId` is the
  creature, whose affected ID is the entry's `manaId`, and which carries a
  `ManaSpecType_DoesNotEmpty` detail.
- <a id="Firebending.no-prompt"></a>**Firebending.no-prompt.** The trigger
  `MUST NOT` send a target or choice prompt.
- <a id="Firebending.combat-cleanup"></a>**Firebending.combat-cleanup.**
  *(provisional)* A firebending `ManaDetails` row whose mana is unspent
  `MUST` be deleted when combat ends.
- <a id="Firebending.full-spend"></a>**Firebending.full-spend.**
  *(provisional)* Spending all of the mana `MUST` delete its `ManaDetails`
  row, and spending part of it `MUST` keep the row.

## Open points

- What does the value of `ManaSpecType_DoesNotEmpty` identify? It is not the
  same for every source.
- How is a trigger "whenever you firebend" linked to the firebending ability
  that caused it?
