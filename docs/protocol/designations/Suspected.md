---
summary: "Suspected designation: a permanent with menace that cannot block, its transients, and the behaviour rows published beside it."
entity: designation/Suspected
---
# Suspected

`Suspected` is a permanent designation that gives the permanent menace and
prevents it from blocking. The designation is the state marker; the behaviour
travels in its own rows.

## Rules grounding

CR 701.60 defines suspected as a designation that only permanents can have. It
remains until the permanent leaves the Battlefield or an effect ends the state,
and a permanent cannot become suspected again while it already has it.

## State

| Surface | Role |
|---|---|
| `GainDesignation` | The permanent became suspected. |
| `Designation` | The persistent state on that permanent. |
| `AddAbility`, `LayeredEffect` | Menace and the can't-block restriction while the state holds. |
| `Qualification` | The client-facing behaviour qualifications for the same state. |
| `LoseDesignation` | The state was explicitly removed. |

The persistent row carries `DesignationType` `16` and no other detail. The
layered and qualification rows are not alternative encodings of the designation:
they carry the behaviour, and the designation says the state is present.

## Lifecycle

1. A resolving ability publishes the gain row for the chosen permanent and
   creates the behaviour rows in the same resolution sequence.
2. The persistent designation, ability rows and qualifications stay anchored to
   the suspected permanent.
3. When an effect moves suspicion between permanents, the same GSM carries the
   loss row for the old permanent and the gain row plus replacement behaviour
   rows for the new one.
4. The old persistent row and behaviour rows retire as the new rows become
   durable.

## Sibling shapes

[`Saddled`](Saddled.md) is a simple card-state marker whose only rules meaning is
that it exists. Suspected additionally needs behaviour rows for menace and the
blocking restriction, so the designation alone does not describe the permanent's
state.

## Contract

- <a id="Suspected.gain"></a>**Suspected.gain.** Becoming suspected `MUST` carry
  a `GainDesignation` with `DesignationType` `16` naming the permanent.
- <a id="Suspected.persistent"></a>**Suspected.persistent.** While the permanent
  is suspected, it `MUST` carry a persistent `Designation` with `DesignationType`
  `16` naming it.
- <a id="Suspected.no-detail"></a>**Suspected.no-detail.** The persistent row
  `MUST NOT` carry a detail beyond `DesignationType`.
- <a id="Suspected.behaviour-rows"></a>**Suspected.behaviour-rows.** While the
  permanent is suspected, it `MUST` also carry `AddAbility` and `LayeredEffect`
  rows for menace and the can't-block restriction, and the matching
  `Qualification` rows.
- <a id="Suspected.move"></a>**Suspected.move.** Moving suspicion between
  permanents `MUST` carry a `LoseDesignation` for `16` on the old permanent and
  a `GainDesignation` for `16` on the new one in the same GSM.

## Open points

- Does losing the state by leaving the Battlefield carry a loss row, or only the
  retirement of the persistent row?
- Which `Qualification` values pair with suspected, and do they differ by
  client?
