---
summary: "Increment: a cast trigger that puts a +1/+1 counter on the creature when enough mana was spent on the spell; the shared ability ID 404, the link to the causing spell and the counter resolution."
entity: mechanic/Increment
---
# Increment

Increment is a triggered ability: whenever its controller casts a spell, if
the mana spent on that spell exceeds the creature's power or toughness, the
creature gets a +1/+1 counter. The trigger is a triggered
[Ability](../object_types/Ability.md) with the shared ability ID `404`. Its
[TriggeringObject](../annotations/TriggeringObject.md) row names the spell
that caused it, and its resolution is an ordinary counter addition.

## Rules grounding

Increment means "Whenever you cast a spell, if this permanent is a creature
and the amount of mana spent to cast that spell is greater than this
creature's power or this creature's toughness, put a +1/+1 counter on this
creature." (CR 702.191a). Each instance triggers separately (CR 702.191b).

## Surfaces

| Surface | Entity pages |
|---|---|
| Cast trigger | [Ability](../object_types/Ability.md), [AbilityInstanceCreated](../annotations/AbilityInstanceCreated.md), [TriggeringObject](../annotations/TriggeringObject.md) |
| Counter | `CounterAdded`, `Counter`, `PowerToughnessModCreated` |

## Trigger lifecycle

1. The controller casts a qualifying spell. In the GSM that commits the
   cast, `AbilityInstanceCreated` names the creature as `affectorId` and the
   new ability as affected ID, with `source_zone` `28`. The ability object has
   `grpId` `404`, `objectSourceGrpId` naming the creature's card and
   `parentId` naming the creature.
2. A persistent `TriggeringObject` row points from the ability to the spell
   on the stack, with `source_zone` `27`. The cast's own rows, such as its
   payment, share that GSM.

## Resolution lifecycle

1. `ResolutionStart` carries `grpid` `404`.
2. `CounterAdded` names the creature with `counter_type` `1` and
   `transaction_amount` `1`, and `PowerToughnessModCreated` reports `power`
   `1` and `toughness` `1`.
3. The creature's persistent `Counter` row carries the new total, and its
   layered effect is created or replaced to update the displayed size.
4. `ResolutionComplete` carries `grpid` `404`, then `AbilityInstanceDeleted`
   retires the ability.

The creature keeps its instance ID. An ability of the same creature that
triggers when a counter is put on it is a separate ability with its own
lifecycle.

## Sibling shapes

Prowess also triggers on casting a spell, but it grants +1/+1 until end of
turn through a layered effect, with no counter. Increment's trigger names the
spell and leaves a lasting counter.

## Contract

- <a id="Increment.ability-id"></a>**Increment.ability-id.** An increment
  trigger `MUST` be an ability object with `grpId` `404` whose `parentId` is
  the creature, and its `ResolutionStart` and `ResolutionComplete` `MUST`
  carry `grpid` `404`.
- <a id="Increment.triggering-spell"></a>**Increment.triggering-spell.**
  *(provisional)* The trigger's `TriggeringObject` `MUST` name the spell that
  caused it, with `source_zone` `27`.
- <a id="Increment.counter"></a>**Increment.counter.** The resolution `MUST`
  carry `CounterAdded` on the creature with `counter_type` `1` and
  `transaction_amount` `1`.

## Open points

- What does the `TriggeringObject` row name once the causing spell has left
  the stack before the trigger resolves?
