---
summary: "Max speed: an ability that works while its controller's speed is 4; the PlayerSpeed designation that carries the speed and the AddAbility grant that appears at 4 and leaves with the permanent."
entity: mechanic/MaxSpeed
---
# Max speed

Max speed is a static ability: the ability it lists works only while its
controller's speed is 4. The protocol has no max speed marker. The speed is
the player's [PlayerSpeed](../designations/PlayerSpeed.md) designation, and the
listed ability appears on the permanent as an ordinary granted ability once
the speed reaches 4.

## Rules grounding

"Max speed — [ability]" means "As long as your speed is 4, this object has
'[ability]'." (CR 702.178a).

## Surfaces

| Surface | Entity pages |
|---|---|
| Player speed | [PlayerSpeed](../designations/PlayerSpeed.md) |
| Granted ability | `AddAbility`, `LayeredEffect`, `LayeredEffectDestroyed` |

## Lifecycle

1. The controller's speed rises through 1 to 4 on one persistent
   `PlayerSpeed` row.
2. In the GSM where the speed becomes 4, each permanent they control with max
   speed gets a persistent row typed `AddAbility` and `LayeredEffect` whose
   affected ID is the permanent and which names the granted ability.
3. When the permanent leaves the battlefield, `LayeredEffectDestroyed` retires
   that row. The player's `PlayerSpeed` row stays at 4.

The speed belongs to the player and the grant belongs to the permanent, so
each ends on its own.

## Sibling shapes

Other conditional static grants, such as those that depend on a count of
permanents, use the same `AddAbility` row without a player designation.

## Contract

- <a id="MaxSpeed.grant-at-four"></a>**MaxSpeed.grant-at-four.**
  *(provisional)* In the GSM where its controller's speed becomes 4, a
  permanent with max speed `MUST` get a persistent `AddAbility` and
  `LayeredEffect` row naming the granted ability.
- <a id="MaxSpeed.object-cleanup"></a>**MaxSpeed.object-cleanup.**
  *(provisional)* When the permanent leaves the battlefield, the grant `MUST`
  be retired with `LayeredEffectDestroyed`, and the player's speed `MUST NOT`
  change.

The speed row is stated by
[PlayerSpeed.update-in-place](../designations/PlayerSpeed.md#PlayerSpeed.update-in-place).

## Open points

- How is a max speed ability that works outside the battlefield shown
  (CR 702.178b)?
- Is the grant published for a permanent that enters while the speed is
  already 4?
