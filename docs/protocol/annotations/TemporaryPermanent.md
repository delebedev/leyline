---
summary: "TemporaryPermanent annotation: persistent marker that a permanent will be removed by a pending ability; self-scoped and TriggerHolder-backed branches, AbilityGrpId, relation identity and reissued rows."
entity: annotation/TemporaryPermanent
---
# TemporaryPermanent

## Meaning

Some permanents are only on the battlefield for a while: tokens created
attacking by mobilize are sacrificed at the next end step, a warped creature
is exiled at the end step, a decayed creature is sacrificed after it attacks.
`TemporaryPermanent` marks such a permanent and names the ability that will
remove it, so the client can show that the permanent is temporary.

## Variants

Persistent only, never combined with another type.

| Branch | `affectorId` | Example |
|---|---|---|
| Self-scoped | The temporary permanent itself | A permanent whose own ability removes it |
| Trigger-backed | The [TriggerHolder](../object_types/TriggerHolder.md) of the pending delayed trigger | Mobilize tokens; a decayed creature after attacking |

The row does not say how the permanent will leave; that is in the ability
named by `AbilityGrpId`.

## Fields

| Field | Role |
|---|---|
| `affectorId` | The permanent itself or the pending trigger's holder, as above. |
| `affectedIds` | The temporary permanent, as a one-element list. |
| `AbilityGrpId` | The definition of the ability that will remove the permanent. Note the capital `A`. |

## Identity model

A trigger-backed row spans three domains: the holder object, the affected
permanent's instance ID and the ability definition. The relation is
identified by `affectorId`, `affectedIds` and `AbilityGrpId` together, not
by the row's `id`, because the same relation can be reissued under new IDs.

## Lifecycle

1. A spell, ability or attack sets up the removal; the row appears.
2. While the removal is pending, the row can be reissued in later GSMs under
   a new `id`. Each reissue deletes the previous `id` through
   `diffDeletedPersistentAnnotationIds` in the same GSM.
3. When the removing ability resolves, or the permanent leaves first, the
   row is deleted.

## Sibling shapes

[DelayedTriggerAffectees](DelayedTriggerAffectees.md) links the same delayed
ability to its objects from the trigger's side, and `DisplayCardUnderCard`
can relate a host to a card under it. Neither always accompanies
`TemporaryPermanent`, and a decayed creature carries this row without
`DelayedTriggerAffectees`.

## Contract

- <a id="TemporaryPermanent.persistent"></a>**TemporaryPermanent.persistent.**
  `TemporaryPermanent` `MUST` be carried in `persistentAnnotations` with an
  `AbilityGrpId` detail and exactly one `affectedIds` entry.
- <a id="TemporaryPermanent.affector-domain"></a>**TemporaryPermanent.affector-domain.**
  `affectorId` `MUST` be either the affected permanent or a `TriggerHolder`.
- <a id="TemporaryPermanent.reissue-deletes-old"></a>**TemporaryPermanent.reissue-deletes-old.**
  *(provisional)* A GSM that reissues a relation under a new `id` `MUST`
  delete the relation's previous `id`.

## Open points

- What triggers a reissue under a new `id`?
- Which removal abilities use the self-scoped branch?
