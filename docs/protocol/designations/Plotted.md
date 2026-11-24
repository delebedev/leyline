---
summary: "Plotted designation: a card exiled by plot, the persistent marker on the exile object, and its later free cast."
entity: designation/Plotted
---
# Plotted

`Plotted` is the state of a card exiled through plot and eligible for a later
cast without paying its mana cost. The state lives as a persistent designation
on the exile object itself.

## Rules grounding

CR 702.170 defines plotting as a special action that exiles the card without
using the stack. The plotted card may be cast from exile without paying its mana
cost on a later turn, under the mechanic's timing restriction.

Those rules define eligibility. The rows below say how the state travels.

## State

| Surface | Role |
|---|---|
| `GainDesignation` | The card became plotted during its move into exile. |
| `Designation` | The persistent state, on the public exile object. |
| Exile object | The source card's own `grpId`; it is not a copy or a hidden object. |
| Retirement | Consuming the exile object ends the state. |

The persistent row carries `DesignationType` `18` and no other detail; the exile
object's identity carries the rest.

## Lifecycle

1. The plot special action pays its cost without creating a stack object.
2. `ObjectIdChanged` and `ZoneTransfer` move the source card from hand to a
   public exile instance in the same GSM as the gain row.
3. The persistent designation stays on that exile object.
4. A later free cast reallocates the exile instance to a Stack instance and moves
   it with cast-spell semantics; the accepted row records the cast's alternative
   ability.
5. The plotted state ends with the consumed exile instance.

## Sibling shapes

Prepared also exposes a castable object in exile, but its designation stays on a
Battlefield permanent and points at a separate exile copy. Plotted keeps the
designation on the exiled card itself and creates no copy.

## Contract

- <a id="Plotted.gain-on-exile"></a>**Plotted.gain-on-exile.** *(provisional)*
  The hand-to-exile move that plots a card `MUST` carry a `GainDesignation` with
  `DesignationType` `18` in the same GSM.
- <a id="Plotted.persistent-on-exile"></a>**Plotted.persistent-on-exile.** While
  a card is plotted, it `MUST` carry a persistent `Designation` with
  `DesignationType` `18` whose `affectorId` and first affected ID are the exile
  instance.
- <a id="Plotted.no-detail"></a>**Plotted.no-detail.** The plotted persistent row
  `MUST NOT` carry any detail beyond `DesignationType`.
- <a id="Plotted.cast-remap"></a>**Plotted.cast-remap.** *(provisional)* A free
  cast of a plotted card `MUST` reallocate the exile instance to a Stack instance
  and move it with cast-spell semantics.

## Open points

- What row, if any, marks the state ending when the exile object leaves without
  being cast?
- Does every plotted cast carry the same alternative ability ID in its accepted
  row?
- Can a card become plotted again after a first plot ends?
