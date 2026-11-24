---
summary: "Saddled designation: a Mount saddled for the turn, its gain and loss transients, and the persistent marker."
entity: designation/Saddled
---
# Saddled

`Saddled` marks a permanent that has resolved a saddle ability during the current
turn. It is a marker with no rules meaning of its own; other spells and abilities
read it.

## Rules grounding

CR 702.171 defines saddled as a designation that lasts until end of turn or until
the permanent leaves the Battlefield, and that only a Mount can have.

## State

| Surface | Role |
|---|---|
| `GainDesignation` | The Mount became saddled. |
| `Designation` | The persistent state on the Mount. |
| `LoseDesignation` | The state expired. |

All three rows name the Mount in `affectedIds` and carry `DesignationType` `17`
with no other detail.

## Lifecycle

1. The saddle activation is paid through its own activation and helper-tap
   surface.
2. The ability's resolution publishes the gain row and the persistent
   designation on the Mount.
3. The designation holds for the rest of the turn.
4. Turn-boundary cleanup publishes the loss row and retires the persistent row.

## Sibling shapes

Saddle and crew have similar activation and helper-tap shapes. Their durable
state differs: saddle uses this designation, while crew animates the Vehicle
through the layered-effect surface. `Suspected` is also a card-state marker but
publishes behaviour rows beside it.

## Contract

- <a id="Saddled.gain-at-resolution"></a>**Saddled.gain-at-resolution.** The
  saddle ability's resolution `MUST` carry a `GainDesignation` with
  `DesignationType` `17` naming the Mount.
- <a id="Saddled.persistent"></a>**Saddled.persistent.** While the Mount is
  saddled, it `MUST` carry a persistent `Designation` with `DesignationType` `17`
  naming the Mount.
- <a id="Saddled.lose-at-cleanup"></a>**Saddled.lose-at-cleanup.** Turn-boundary
  cleanup `MUST` carry a `LoseDesignation` with `DesignationType` `17` for the
  Mount and retire its persistent row.
- <a id="Saddled.no-detail"></a>**Saddled.no-detail.** None of the three rows
  `MUST` carry a detail beyond `DesignationType`.

## Open points

- Does the loss row appear when the Mount leaves the Battlefield mid-turn, or
  only at cleanup?
- Is the persistent row's annotation ID stable across the turn it holds?
