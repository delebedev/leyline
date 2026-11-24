---
summary: "Prepared designation: the permanent's prepared state, its linked prepare-spell copy in exile, and the loss when the copy is cast."
entity: designation/Prepared
---
# Prepared

`Prepared` is a Battlefield-permanent designation that makes a copy of the
permanent's prepare spell available in exile. The durable state stays on the
permanent and names the copy by instance ID.

## Rules grounding

CR 722.3 defines the prepared designation and the exile copy created from a
preparation card's prepare-spell characteristics. The copy stays available while
the source permanent remains prepared on the Battlefield, and casting the copy
removes the designation.

Those rules define the state. The rows and linked objects below say how it
travels.

## State

| Surface | Role |
|---|---|
| `GainDesignation` | The permanent became prepared. |
| `Designation` | The persistent state, on the prepared permanent. |
| `PreparedCopyZcid` | The castable card object in exile. |
| `LoseDesignation` | The permanent became unprepared when the copy was cast. |

The exile object is a copy whose `parentId` names the prepared permanent. Its
`grpId`, card types and abilities are the prepare spell's characteristics, not
the permanent's Battlefield characteristics.

## Lifecycle

1. A permanent can become prepared during its own resolution or when another
   ability resolves.
2. The gain row and the persistent designation appear on the source, and the
   linked copy appears in exile.
3. Available actions identify the exile copy as an ordinary castable object; the
   offer uses no alternative-cost marker.
4. Accepting the cast reallocates the copy from exile to the Stack and emits
   `LoseDesignation` on the source permanent.
5. From that point the designation and its linked copy no longer represent an
   available prepare spell, whether or not the spell later resolves.

## Sibling shapes

[`Plotted`](Plotted.md) places the original card in exile and ends its state by
consuming that object. Prepared leaves the designated permanent on the
Battlefield and uses `PreparedCopyZcid` to name a second object's lifetime.

## Contract

- <a id="Prepared.gain-bracket"></a>**Prepared.gain-bracket.** *(provisional)*
  When a permanent enters the Battlefield prepared, its `GainDesignation` row
  `MUST` appear immediately before the Stack-to-Battlefield `ZoneTransfer` whose
  `category` is `Resolve` for that permanent.
- <a id="Prepared.copy-detail"></a>**Prepared.copy-detail.** The prepared
  persistent row `MUST` carry `PreparedCopyZcid` naming the exile copy's
  instance, with `affectorId` and first affected ID naming the prepared
  permanent.
- <a id="Prepared.copy-parent"></a>**Prepared.copy-parent.** *(provisional)*
  While the permanent remains prepared, the exile copy `MUST` carry `parentId`
  naming it, and the copy's `grpId` `MUST` be the prepare spell's face.
- <a id="Prepared.lose-on-cast"></a>**Prepared.lose-on-cast.** Accepting the
  copy's cast `MUST` carry a `LoseDesignation` with `DesignationType` `24` on
  the prepared permanent.

## Open points

- What happens to the copy and the designation when the permanent leaves the
  Battlefield unprepared?
- Does a control change move the designation and the copy to the new
  controller?
- Can a permanent become prepared again after the copy was cast?
