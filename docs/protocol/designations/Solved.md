---
summary: "Solved designation: a Case permanent whose solve condition resolved, carried as a self-referential persistent marker."
entity: designation/Solved
---
# Solved

`Solved` marks a Case permanent whose solve condition has resolved. The durable
state is a persistent designation on the Case itself, and the Case's solved
ability text reads it.

## Rules grounding

CR 719.3 defines solved as a designation that remains until the Case leaves the
Battlefield. It is a marker, not an ability.

## State

| Field | Role |
|---|---|
| `DesignationType` | `15`. |
| `affectorId` | The Case permanent. |
| `affectedIds` | The same Case permanent. |

The row carries no detail beyond its type, and it names the Case on both sides of
the row.

## Lifecycle

1. The Case's solve condition creates and resolves its triggered ability.
2. The persistent designation appears on the Case in that resolution GSM.
3. The state remains on the same Battlefield object until that object retires.

## Sibling shapes

`Saddled` publishes a gain row at resolution and a loss row at cleanup.
`Suspected` publishes behaviour rows beside the designation. Solved has neither:
the persistent row alone carries the state, and no other row interprets it.

## Contract

- <a id="Solved.persistent"></a>**Solved.persistent.** A solved Case `MUST` carry
  a persistent `Designation` with `DesignationType` `15` whose `affectorId` and
  first affected ID are the Case's instance.
- <a id="Solved.no-detail"></a>**Solved.no-detail.** The persistent row `MUST
  NOT` carry a detail beyond `DesignationType`.

## Open points

- Does the solve transition also carry a `GainDesignation` row?
- Is the state ever lost while the Case stays on the Battlefield?
