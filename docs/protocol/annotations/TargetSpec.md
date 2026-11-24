---
summary: "TargetSpec annotation: persistent record of one submitted target group of a spell or ability; fields, correlation with SelectTargetsReq, distributions and retirement."
entity: annotation/TargetSpec
---
# TargetSpec

## Meaning

A spell or ability with targets has its targets chosen as it is put on the
stack (CR 601.2c, CR 602.2b). `TargetSpec` publishes one chosen target group of
a stack object and keeps it as state while the object is on the stack.

One row represents one target group, not one target. Independent target
clauses use separate rows. A clause that allows several targets uses one row
whose `affectedIds` lists them in order.

## Rules grounding

A divided effect, such as "deals 3 damage divided as you choose among one, two
or three targets", fixes the division as targets are chosen (CR 601.2d).

## Variants

Persistent only. Branches by the identity domain of the targets:

- **Object targets.** `affectedIds` holds instance IDs.
- **Player targets.** `affectedIds` holds seat numbers.
- **Mixed.** One group can hold values from both domains.
- **Allocated.** `distributions` assigns an amount to each target.

## Fields

| Field | Role |
|---|---|
| `affectorId` | The stack object that owns the group: a spell's stack ID or an ability instance. |
| `affectedIds` | The chosen targets of the group, in order. |
| `abilityGrpId` | The targeting ability. For a modal spell, the chosen mode's ability rather than the card. |
| `index` | The group's position for this stack object, starting at `1`. |
| `promptId` | The wording of the target clause, shared with the request group. |
| `promptParameters` | Prompt context for the source. It equals `affectorId` except in the cases under Open points. |
| `distributions` | Present on the allocated branch: one amount per entry of `affectedIds`, in the same order. |

## Identity model

The row's own `id` identifies it while it lives. `affectorId` plus `index`
identifies the target group; one stack object can own several groups.
`affectedIds` values belong to the request's candidate domain: a seat number
and an instance ID are both integers and are not interchangeable.

## Lifecycle

1. A `SelectTargetsReq` offers one or more target groups, each with a
   `targetIdx`, a `promptId` and a `targetingAbilityGrpId`.
2. The player submits targets.
3. The resulting GSM carries `PlayerSubmittedTargets` for the stack object and
   one persistent `TargetSpec` per submitted group. Each row repeats its
   group's `targetIdx` as `index`, its `promptId` and its
   `targetingAbilityGrpId` as `abilityGrpId`.
4. When the effect divides an amount, a `DistributionReq` follows; after the
   answer, the existing row is published again under the same `id` with
   `distributions` added.
5. When the stack object resolves or leaves the stack, its rows are deleted
   through `diffDeletedPersistentAnnotationIds`. A resolving object's rows are
   deleted in the GSM that carries its `ResolutionComplete`.

Lightning Bolt targeting the opponent produces one row with `index` `1`,
`affectedIds` naming the opponent's seat and `affectorId` the Bolt's stack ID.

## Sibling shapes

- `PlayerSelectingTargets` marks that selection is in progress and carries no
  targets.
- `PlayerSubmittedTargets` is the transient submission event; it does not
  carry the group's fields.
- An effect that says "each" instead of "target", such as an overloaded spell,
  has no target selection and no `TargetSpec`.

## Contract

- <a id="TargetSpec.persistent"></a>**TargetSpec.persistent.**
  `TargetSpec` `MUST` be carried in `persistentAnnotations`.
- <a id="TargetSpec.request-group"></a>**TargetSpec.request-group.**
  A row created for an interactive target choice `MUST` carry `index` equal to
  the answered `SelectTargetsReq` group's `targetIdx` and `promptId` equal to
  that group's `promptId`.
- <a id="TargetSpec.one-row-per-group"></a>**TargetSpec.one-row-per-group.**
  The live state `MUST NOT` hold two `TargetSpec` rows with the same
  `affectorId` and `index`.
- <a id="TargetSpec.distributions-aligned"></a>**TargetSpec.distributions-aligned.**
  When `distributions` is present, it `MUST` have exactly as many entries as
  `affectedIds`.
- <a id="TargetSpec.distribution-same-row"></a>**TargetSpec.distribution-same-row.**
  An allocation made after target submission `MUST` republish the existing
  row under the same `id`, not create a new row.
- <a id="TargetSpec.retired-with-object"></a>**TargetSpec.retired-with-object.**
  A row `MUST` be deleted once its `affectorId` has resolved or left the
  stack.

## Open points

- When does `abilityGrpId` differ from the request group's
  `targetingAbilityGrpId`?
- Which non-divided effects carry `distributions`?
- Why do a few rows outlive their object's `ResolutionComplete` GSM?
- What does `promptParameters` carry when it differs from `affectorId`?
