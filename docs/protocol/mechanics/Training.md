---
summary: "Training mechanic: the shared training trigger on attack, its +1/+1 counter resolution, the trains trigger it causes, and the Training condition row shown while attackers are chosen."
entity: mechanic/Training
---
# Training

Training is a triggered ability: whenever the creature attacks with another
creature of greater power, put a +1/+1 counter on it. The mechanic uses one
training ability shared by every card, a triggered
[Ability](../object_types/Ability.md) linked by
[TriggeringObject](../annotations/TriggeringObject.md), the counter rows, and
an [AbilityWordActive](../annotations/AbilityWordActive.md) row named
`Training` while the condition holds during attack declaration.

## Rules grounding

CR 702.149a defines training. CR 702.149c defines "whenever this creature
trains", a trigger that fires when a resolving training ability puts one or
more +1/+1 counters on it.

## Surfaces

| Surface | Entity pages |
|---|---|
| Condition while choosing attackers | [AbilityWordActive](../annotations/AbilityWordActive.md) `Training` |
| Training trigger | [Ability](../object_types/Ability.md), [AbilityInstanceCreated](../annotations/AbilityInstanceCreated.md), [TriggeringObject](../annotations/TriggeringObject.md) |
| Counter | `CounterAdded`, persistent `Counter` |

## Attack lifecycle

1. While the player chooses attackers, a selection that makes the creature
   attack with a creature of greater power creates a persistent
   `AbilityWordActive` row named `Training`, with the training creature as
   `affectorId` and the greater-power attacker as affected.
2. The GSM that commits the attack taps the creature, deletes that row, and
   creates the training trigger: an `Ability` with `grpId` `220`,
   `AbilityInstanceCreated` from the creature with `source_zone` the
   Battlefield, and a `TriggeringObject` row naming the creature. The trigger
   has no targets.

## Resolution lifecycle

1. `ResolutionStart` with `grpid` `220`.
2. `CounterAdded` from the trigger to the creature with `counter_type` `1` and
   `transaction_amount` `1`, then `PowerToughnessModCreated` and
   `LayeredEffectCreated` for the counter.
3. A "whenever this creature trains" trigger, if the creature has one, is
   created inside the same bracket by its own `AbilityInstanceCreated`.
4. `ResolutionComplete` with `grpid` `220`, then `AbilityInstanceDeleted`.
5. The same GSM creates or updates the creature's persistent `Counter` row.

## Sibling shapes

Mentor and backup also put +1/+1 counters from a trigger tied to combat or
entering, but they target the creature that receives the counter. Training
puts the counter on its own source and needs no target.

## Contract

- <a id="Training.shared-ability"></a>**Training.shared-ability.** A training
  trigger `MUST` be an `Ability` with `grpId` `220`, and its
  `ResolutionStart` and `ResolutionComplete` `MUST` carry `220`.
- <a id="Training.no-target"></a>**Training.no-target.** A training trigger
  `MUST NOT` carry a `TargetSpec` or a `PlayerSelectingTargets` row.
- <a id="Training.counter"></a>**Training.counter.** A resolving training
  trigger `MUST` carry `CounterAdded` from the trigger to its source creature
  with `counter_type` `1`.
- <a id="Training.condition-before-commit"></a>**Training.condition-before-commit.**
  *(provisional)* Grounded for a single training creature: the `Training`
  `AbilityWordActive` row `MUST` be deleted in the GSM that commits the
  attack, and that GSM `MUST NOT` create one.
- <a id="Training.trains-in-bracket"></a>**Training.trains-in-bracket.**
  *(provisional)* A "whenever this creature trains" trigger `MUST` be created
  between the training trigger's `ResolutionStart` and `ResolutionComplete`.

## Open points

- Which selection change during attack declaration creates the `Training`
  row, and is it removed when the selection stops qualifying?
- How do several training creatures in one attack appear in the condition
  rows?
