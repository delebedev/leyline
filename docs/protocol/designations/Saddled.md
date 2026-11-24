---
summary: "Saddled designation: a permanent saddled for the turn, its gain and loss transients, and the persistent marker."
entity: designation/Saddled
---
# Saddled

`Saddled` marks a permanent that became saddled during the current turn,
usually by resolving its saddle ability; other spells and abilities can also
make a permanent saddled. It is a marker with no rules meaning of its own;
other spells and abilities read it.

## Rules grounding

CR 702.171b defines saddled as a designation that lasts until end of turn or
until the permanent leaves the Battlefield. Only permanents can be or become
saddled; the rule does not require the Mount subtype.

## State

| Surface | Role |
|---|---|
| `GainDesignation` | The permanent became saddled. |
| `Designation` | The persistent state on the permanent. |
| `LoseDesignation` | The state expired. |

All three rows name the saddled permanent in `affectedIds` and carry `DesignationType` `17`
with no other detail.

## Lifecycle

1. The saddle activation is paid through its own activation and helper-tap
   surface.
2. The ability's resolution publishes the gain row and the persistent
   designation on the permanent. Another effect that saddles a permanent
   publishes them when it applies.
3. The designation holds for the rest of the turn.
4. The first GSM of the next turn publishes the loss row and retires the
   persistent row.

## Sibling shapes

Saddle and crew have similar activation and helper-tap shapes. Their durable
state differs: saddle uses this designation, while crew animates the Vehicle
through the layered-effect surface. `Suspected` is also a card-state marker but
publishes behaviour rows beside it.

## Contract

- <a id="Saddled.gain-at-resolution"></a>**Saddled.gain-at-resolution.** The
  saddle ability's resolution `MUST` carry a `GainDesignation` with
  `DesignationType` `17` naming the permanent it saddles.
- <a id="Saddled.persistent"></a>**Saddled.persistent.** While the permanent is
  saddled, it `MUST` carry a persistent `Designation` with `DesignationType` `17`
  naming the permanent.
- <a id="Saddled.lose-at-cleanup"></a>**Saddled.lose-at-cleanup.** The first
  GSM of the turn after the permanent became saddled `MUST` carry a
  `LoseDesignation` with `DesignationType` `17` for the permanent and retire
  its persistent row.
- <a id="Saddled.no-detail"></a>**Saddled.no-detail.** The gain, persistent and
  loss rows `MUST NOT` carry a detail beyond `DesignationType`.

## Open points

- Does an effect that saddles a permanent without its saddle ability carry the
  same gain row?

- Does the loss row appear when the permanent leaves the Battlefield mid-turn, or
  only at cleanup?
- Is the persistent row's annotation ID stable across the turn it holds?
