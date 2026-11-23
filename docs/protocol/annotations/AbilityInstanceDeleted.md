---
summary: "AbilityInstanceDeleted annotation: an ability instance ended; identities matched to its AbilityInstanceCreated and placement on each branch."
read_when:
  - "emitting or consuming AbilityInstanceDeleted"
  - "retiring an ability instance after resolution, countering or a mana payment"
entity: annotation/AbilityInstanceDeleted
---
# AbilityInstanceDeleted

## Meaning

`AbilityInstanceDeleted` closes the lifecycle that
[AbilityInstanceCreated](AbilityInstanceCreated.md) opened: the ability
instance resolved, was countered, or otherwise ceased to exist.

## Variants

One transient row per retired instance, on the same two branches as
`AbilityInstanceCreated`:

- **Stack branch.** The row appears in the GSM where the ability leaves the
  stack, normally after its `ResolutionComplete`.
- **Mana-ability branch.** The row appears in the same GSM as the
  `AbilityInstanceCreated`, after the `ManaPaid` it funded.

## Fields

| Field | Role |
|---|---|
| `affectorId` | The ability's source object, the same object the `AbilityInstanceCreated` named. |
| `affectedIds` | The retired ability instance, as a one-element list. |
| details | None. |

## Identity model

The pair `(affectorId, affectedIds)` repeats the pair from the matching
`AbilityInstanceCreated`. A source that changed instance ID between the two
rows is still named by its ID at creation.

## Lifecycle

See [AbilityInstanceCreated](AbilityInstanceCreated.md#lifecycle) for both
branch sequences.

## Contract

- <a id="AbilityInstanceDeleted.no-details"></a>**AbilityInstanceDeleted.no-details.**
  An `AbilityInstanceDeleted` row `MUST NOT` carry detail keys.
- <a id="AbilityInstanceDeleted.matches-created"></a>**AbilityInstanceDeleted.matches-created.**
  A row's `affectedIds` `MUST` be exactly the `affectedIds` of an earlier
  `AbilityInstanceCreated`, and its `affectorId` `MUST` equal that row's
  `affectorId`.
- <a id="AbilityInstanceDeleted.after-resolution"></a>**AbilityInstanceDeleted.after-resolution.**
  When the ability resolves, its `ResolutionComplete` `MUST` precede the
  `AbilityInstanceDeleted` for the same instance.

## Open points

- Which rows accompany the deletion of an ability that is countered or removed
  from the stack without resolving?
