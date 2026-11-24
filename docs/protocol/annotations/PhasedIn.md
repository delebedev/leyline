---
summary: "PhasedIn annotation: transient event for a phased-out permanent phasing back in; same instance ID, no affector on the natural untap-step return, ordering within the untap step and restored projection."
entity: annotation/PhasedIn
---
# PhasedIn

## Meaning

A phased-out permanent phases in during its controller's untap step, before
permanents untap (CR 702.26a, CR 702.26c), and is treated as existing again.
`PhasedIn` marks that return. The departure is [PhasedOut](PhasedOut.md).

## Variants

Transient only, never combined with another type, without details. The
natural return during the untap step has no `affectorId`.

## Fields

| Field | Role |
|---|---|
| `affectorId` | Absent on the natural return. Absence is not the same as `0` or the permanent's own ID. |
| `affectedIds` | The permanent, by the same instance ID that `PhasedOut` named. |
| details | None. |

## Identity model

No new instance is allocated and control does not change. The object's
`zoneId` returns from `12` to the battlefield (see
[PhasedOut](PhasedOut.md) and [identity and zones](../model/identity-and-zones.md)).
Counters and ability identities are kept; per-turn state such as a
planeswalker's used loyalty activation is reset as for any permanent in a new
turn.

## Lifecycle

Within the GSM that covers the controller's untap step:

1. [PhaseOrStepModified](PhaseOrStepModified.md) for the untap step.
2. `PhasedIn` for each returning permanent.
3. [TappedUntappedPermanent](TappedUntappedPermanent.md) rows untapping the
   active player's permanents.
4. `PhaseOrStepModified` for the upkeep step.

The same GSM republishes the object with its battlefield `zoneId` and offers
its actions again. The row lasts for that GSM only.

## Sibling shapes

[ZoneTransfer](ZoneTransfer.md) and [ObjectIdChanged](ObjectIdChanged.md)
never accompany phasing in; a permanent returning from exile uses them
instead.

## Contract

- <a id="PhasedIn.transient"></a>**PhasedIn.transient.**
  `PhasedIn` `MUST` be carried in `annotations` without details and with
  exactly one `affectedIds` entry.
- <a id="PhasedIn.same-instance"></a>**PhasedIn.same-instance.**
  `affectedIds` `MUST` name the instance ID that the permanent's `PhasedOut`
  named.
- <a id="PhasedIn.no-affector"></a>**PhasedIn.no-affector.**
  *(provisional)* A natural return during the untap step `MUST NOT` carry
  `affectorId`.
- <a id="PhasedIn.before-untap"></a>**PhasedIn.before-untap.**
  *(provisional)* `PhasedIn` `MUST` follow the untap step's
  `PhaseOrStepModified` and precede the untap step's
  `TappedUntappedPermanent` rows.

## Open points

- Does an effect that phases a permanent in name an `affectorId`?
- How are several returning permanents, and attached permanents, ordered?
