---
summary: "PhasedIn annotation: event for a phased-out permanent phasing back in during its controller's untap step; rules grounding and the open questions, since its shape is not yet established."
entity: annotation/PhasedIn
---
# PhasedIn

## Meaning

A phased-out permanent phases in during its controller's untap step, before
permanents untap (CR 702.26a, CR 702.26c), and is treated as existing again.
`PhasedIn` marks that return. The departure is [PhasedOut](PhasedOut.md).

## Variants

The rail, fields and values are not established; see Open points.

## Sibling shapes

[ZoneTransfer](ZoneTransfer.md) and [ObjectIdChanged](ObjectIdChanged.md)
describe zone changes; phasing in is not one (CR 702.26d).

## Contract

No statements yet. The row's fields, identities and ordering are not
established.

## Open points

- Does `affectedIds` name the instance ID that the permanent's `PhasedOut`
  named?
- Does the natural return during the untap step carry an `affectorId`?
- Where does the row sit relative to the untap step's
  [PhaseOrStepModified](PhaseOrStepModified.md) and its
  [TappedUntappedPermanent](TappedUntappedPermanent.md) rows?
- How are several returning permanents, and attached permanents, ordered?
