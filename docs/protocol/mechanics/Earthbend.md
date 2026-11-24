---
summary: "Earthbend: a targeted effect that animates a land its controller controls; four layered effects, +1/+1 counters, the animated-land designation and the refresh when the same land is earthbent again."
entity: mechanic/Earthbend
---
# Earthbend

Earthbend turns a land its controller controls into a creature with haste and
puts +1/+1 counters on it. It is carried by a spell or a triggered or
activated ability that targets the land through
[SelectTargetsReq](../prompts/SelectTargetsReq.md). The mechanic adds no
prompt of its own; what it adds is the resolution state on the land: four
layered effects, a counter row and the
[AnimatedLand](../designations/AnimatedLand.md) designation.

## Rules grounding

Earthbend N targets a land you control, makes it a 0/0 creature with haste in
addition to its other types, puts N +1/+1 counters on it, and returns it to
the battlefield tapped if it dies or is put into exile (CR 701.66a).

## Surfaces

| Surface | Entity pages |
|---|---|
| Target the land | [SelectTargetsReq](../prompts/SelectTargetsReq.md), [TargetSpec](../annotations/TargetSpec.md) |
| Animation layers | [LayeredEffectCreated](../annotations/LayeredEffectCreated.md), [ModifiedType](../annotations/ModifiedType.md), [AddAbility](../annotations/AddAbility.md), `ModifiedPower`, `ModifiedToughness` |
| Counters | `CounterAdded`, `Counter`, `PowerToughnessModCreated` |
| Persistent state | [AnimatedLand](../designations/AnimatedLand.md) |
| Repeated earthbend on one land | `LayeredEffectDestroyed`, then new layers |

## Resolution lifecycle

1. The target is chosen through the ordinary target exchange for the spell or
   ability that earthbends.
2. In the resolution GSM, four `LayeredEffectCreated` rows introduce four
   effects; their `affectorId` is the resolving spell or ability.
3. The same GSM carries one persistent row per effect on the land, each with
   `sourceAbilityGRPID` naming the earthbend ability and `effect_id` naming
   its effect, and with the earthbending card's permanent as `affectorId`:

   | Row | Effect |
   |---|---|
   | `ModifiedType` + `LayeredEffect` | The land becomes a creature. |
   | `AddAbility` + `LayeredEffect` | Haste, `grpid` `9`, with `originalAbilityObjectZcid` naming the source permanent. |
   | `ModifiedPower` + `LayeredEffect` | Base power. |
   | `ModifiedToughness` + `LayeredEffect` | Base toughness. |

4. `CounterAdded` puts the counters on the land, `counter_type` `1`, and the
   persistent `Counter` row carries the total. `PowerToughnessModCreated`
   names the land as both `affectorId` and affected ID.
5. The land carries the `AnimatedLand` designation and is projected with both
   `CardType_Creature` and `CardType_Land`, haste in `uniqueAbilities`, and
   the same instance ID it had before.

## Refresh lifecycle

When a land that is already earthbent is earthbent again, the resolution GSM
destroys the four previous effects with `LayeredEffectDestroyed`, then
creates four new effects as above. The `Counter` row stays on the land and its
`count` grows.

## Sibling shapes

Crew also makes a permanent a creature with a `ModifiedType` row, but its
source is the crew ability on a Vehicle and no counters or designation follow.
Earthbend's type change comes from an ability that targets a land, and the
land stays a land.

## Contract

- <a id="Earthbend.layers"></a>**Earthbend.layers.** *(provisional)* The
  resolution of an earthbend `MUST` publish, on the target land, persistent
  `ModifiedType`, `AddAbility`, `ModifiedPower` and `ModifiedToughness` rows
  combined with `LayeredEffect`, each carrying `sourceAbilityGRPID` equal to
  the earthbend ability and an `effect_id` introduced by a
  `LayeredEffectCreated` in the same GSM.
- <a id="Earthbend.haste"></a>**Earthbend.haste.** *(provisional)* The
  earthbend `AddAbility` row `MUST` carry `grpid` `9` and
  `originalAbilityObjectZcid` naming the source permanent.
- <a id="Earthbend.keeps-instance"></a>**Earthbend.keeps-instance.** The
  target land `MUST` keep its instance ID through the resolution.
- <a id="Earthbend.refresh"></a>**Earthbend.refresh.** *(provisional)* When
  the target land already carries earthbend effects, the resolution `MUST`
  emit `LayeredEffectDestroyed` for each of the four previous effect IDs
  before the replacement rows name new ones.

The land's designation is stated by
[AnimatedLand.persistent-shape](../designations/AnimatedLand.md#AnimatedLand.persistent-shape)
and [AnimatedLand.controller-detail](../designations/AnimatedLand.md#AnimatedLand.controller-detail).

## Open points

- How is the return to the battlefield published when an earthbent land dies
  or is exiled?
- Does the target prompt use one prompt ID for every earthbend source?
