---
summary: "ManaPaid annotation: mana from a source was spent on a spell or ability; producer and consumer identities, the id and color keys, and substitution payments."
read_when:
  - "emitting or consuming ManaPaid"
  - "publishing a mana payment, including convoke-style substitutions"
entity: annotation/ManaPaid
---
# ManaPaid

## Meaning

`ManaPaid` links one unit of payment to the spell or ability it paid for and to
the permanent that produced it (CR 601.2h). It is the payment row of the
mana-ability sequence.

## Variants

- **Mana payment.** Mana produced by a source and spent. Carries `id` and
  `color`.
- **Substitution payment.** A cost paid by something other than mana in place
  of mana, such as tapping a creature for convoke. Carries
  `substitution_grpid`, the ability that allows the substitution, and `color`,
  and no `id`.

## Fields

| Field | Role |
|---|---|
| `affectorId` | The permanent that produced the mana or made the substitution. |
| `affectedIds` | The spell or ability instance the payment went to, as a one-element list. |
| `id` | An identity for the paid mana. |
| `color` | The color of the paid mana. |
| `substitution_grpid` | On the substitution branch, the grpId of the substituting ability. |

## Identity model

The producer is the source permanent, not the mana ability instance: in the
mana-ability sequence `ManaPaid.affectorId` equals the `affectorId` of the
activation's [AbilityInstanceCreated](AbilityInstanceCreated.md). The consumer
is the spell's stack ID, or the instance ID of the ability being paid for.

## Lifecycle

Tapping Llanowar Elves for mana to cast a spell publishes, in order,
`AbilityInstanceCreated`, `TappedUntappedPermanent`, `UserActionTaken` with
`actionType` `4`, `ManaPaid` from the Elves to the spell, and
`AbilityInstanceDeleted`. The `ManaPaid` appears in the GSM where the spell's
costs are paid.

## Contract

- <a id="ManaPaid.single-consumer"></a>**ManaPaid.single-consumer.**
  A `ManaPaid` row `MUST` carry exactly one ID in `affectedIds`: the spell or
  ability instance being paid for.
- <a id="ManaPaid.source-affector"></a>**ManaPaid.source-affector.**
  When the mana comes from a mana ability, `affectorId` `MUST` equal the
  `affectorId` of that activation's `AbilityInstanceCreated`.
- <a id="ManaPaid.mana-keys"></a>**ManaPaid.mana-keys.**
  On the mana payment branch, the row `MUST` carry the Int32 details `id` and
  `color`.
- <a id="ManaPaid.substitution-keys"></a>**ManaPaid.substitution-keys.**
  *(provisional)* On the convoke substitution branch, the row `MUST` carry
  `substitution_grpid` and `color` and `MUST NOT` carry `id`.

## Open points

- Which value set does `color` use? Most payments carry `ManaColor` values,
  such as `5` for green, but power-of-two values such as `16` for green and `8`
  for red also occur. What selects the encoding?
- What does `id` identify, and how does it relate to other rows that name paid
  mana, such as `ManaDetails`?
