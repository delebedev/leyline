---
summary: "AnimatedLand designation: a persistent card-state marker for a land that currently has creature characteristics, with its controlling seat."
entity: designation/AnimatedLand
---
# AnimatedLand

`AnimatedLand` marks a land that currently has creature characteristics. It is a
durable card-state marker that sits above the continuous-effect rows projecting
the animated characteristics; it does not describe them.

## Meaning

The land remains the same zone-bearing card. Its creature types, power and
toughness, granted abilities and layered effect are published as neighbouring
rows; the designation records only that the land is animated and who controls
it.

## State

| Field | Role |
|---|---|
| `DesignationType` | `23`. |
| `affectorId` | The animated land. |
| `affectedIds` | The same land instance. |
| `ControllerId` | The seat that controls the land. |

The row is persistent. Its controller detail lets a viewer tell which seat the
animation belongs to when the land changes controllers.

## Lifecycle

1. The animation effect orders its layered-effect rows and the persistent
   designation on the land in the same resolution.
2. The land keeps its instance ID while the animation holds.
3. The designation ends with the animation effect.

## Sibling shapes

The designation is not mechanic-specific: any effect that makes a land a
creature produces the same state. It does not replace the rows that say how the
land is animated, and it carries no power, toughness or ability of its own.

## Contract

- <a id="AnimatedLand.persistent-shape"></a>**AnimatedLand.persistent-shape.**
  *(provisional)* A land that currently has creature characteristics `MUST`
  carry a persistent `Designation` whose `DesignationType` is `23` and whose
  `affectorId` and first affected ID are that land's instance.
- <a id="AnimatedLand.controller-detail"></a>**AnimatedLand.controller-detail.**
  *(provisional)* That row `MUST` carry `ControllerId` naming the seat that
  controls the land.

## Open points

- Is the animation ever bracketed by a `GainDesignation` and `LoseDesignation`
  pair, or only published as a persistent row?
- Does the row survive a control change, and does `ControllerId` follow the new
  controller?
- Which animation effects produce the designation, and which animate a land
  without it?
