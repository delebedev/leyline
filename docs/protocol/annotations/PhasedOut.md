---
summary: "PhasedOut annotation: transient event for a permanent phasing out; rules grounding and the open questions, since its shape is not yet established."
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
object keeps its identity. The zone layout reserves zone `12`, PhasedOut
(see [identity and zones](../model/identity-and-zones.md)).

## Variants

Transient. The fields and their values are not established; see Open
points.

## Sibling shapes

A permanent that is exiled and returned is a new object and gets
[ObjectIdChanged](ObjectIdChanged.md) and [ZoneTransfer](ZoneTransfer.md);
phasing is not such a move.

## Contract

No statements yet. The row's fields, identities and lifecycle are not
established.

## Open points

- Is the phased-out permanent named in `affectedIds` under its unchanged
  instance ID, and does the GSM omit `ObjectIdChanged` and `ZoneTransfer`
  for it?
- Does the object carry `zoneId` `12` while phased out, and does it stay a
  member of the battlefield zone?
- What does `affectorId` name: the resolving spell or ability, or nothing for
  a static ability such as phasing itself?
- What do phasing of several permanents, and of attached permanents, look
  like?
