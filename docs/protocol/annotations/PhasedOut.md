---
summary: "PhasedOut annotation: transient event for a permanent phasing out; same instance ID, PhasedOut zone projection with battlefield membership kept, affector and absence of zone-transfer annotations."
entity: annotation/PhasedOut
---
# PhasedOut

## Meaning

A permanent that phases out is treated as though it does not exist until it
phases in, but it stays on the battlefield and keeps its identity
(CR 702.26b, CR 702.26d). `PhasedOut` marks the moment it phases out. The
return is [PhasedIn](PhasedIn.md).

## Rules grounding

Phasing is not a zone change (CR 702.26d): no leaves-the-battlefield or
enters-the-battlefield event happens, counters and attachments stay, and the
object keeps its instance ID.

## Variants

Transient only, never combined with another type, without details.

## Fields

| Field | Role |
|---|---|
| `affectorId` | The resolving spell or ability that phased the permanent out. |
| `affectedIds` | The permanent, by its unchanged instance ID. |
| details | None. |

## Identity model

The affector is a stack instance, not the permanent, even when the
permanent's own ability phases it out; the ability object's `parentId` links
the two. The permanent keeps its instance ID through the whole cycle.

While phased out, the object's `zoneId` is `12`, the PhasedOut zone, but its
ID stays in the battlefield zone's member list (see
[identity and zones](../model/identity-and-zones.md)). The object stays in
`gameObjects`, publicly visible, with its controller, counters and abilities.
Its actions are not offered while it is phased out.

## Lifecycle

1. The phasing ability resolves: in one GSM, `ResolutionStart`, `PhasedOut`,
   `ResolutionComplete` and `AbilityInstanceDeleted`, with the object
   republished with `zoneId` `12`.
2. The row lasts for that GSM only.
3. The object stays phased out until its controller's next untap step.

## Sibling shapes

A permanent that is exiled and returned, unlike one that phases out, gets
[ObjectIdChanged](ObjectIdChanged.md) and [ZoneTransfer](ZoneTransfer.md)
and a new instance ID. `PhasedOut` is accompanied by neither.

## Contract

- <a id="PhasedOut.transient"></a>**PhasedOut.transient.**
  `PhasedOut` `MUST` be carried in `annotations` without details and with
  exactly one `affectedIds` entry.
- <a id="PhasedOut.no-transfer"></a>**PhasedOut.no-transfer.**
  The GSM `MUST NOT` carry `ObjectIdChanged` or `ZoneTransfer` for the
  phased-out permanent, and the permanent `MUST` keep its instance ID.
- <a id="PhasedOut.zone-projection"></a>**PhasedOut.zone-projection.**
  *(provisional)* While phased out, the object `MUST` carry `zoneId` `12` and
  `MUST` remain a member of the battlefield zone.
- <a id="PhasedOut.ability-affector"></a>**PhasedOut.ability-affector.**
  *(provisional)* A permanent phased out by a resolving spell or ability
  `MUST` name that stack instance as `affectorId`.

## Open points

- What do phasing by an effect on several permanents, and phasing of
  attached permanents, look like?
- Is there an `affectorId` when a static ability such as phasing itself
  phases a permanent out?
