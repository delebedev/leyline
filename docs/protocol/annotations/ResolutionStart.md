---
summary: "ResolutionStart annotation: a spell or ability began resolving; identity, grpid and its bracket with ResolutionComplete."
entity: annotation/ResolutionStart
---
# ResolutionStart

## Meaning

`ResolutionStart` marks the moment the top object of the stack begins to
resolve (CR 608.2). The rows that describe its effects follow it, and
[ResolutionComplete](ResolutionComplete.md) closes the bracket.

## Variants

One transient row per resolving object. Spells and abilities share the shape.

## Fields

| Field | Role |
|---|---|
| `affectorId` | The resolving spell or ability instance. |
| `affectedIds` | The same instance, as a one-element list. |
| `grpid` | The definition of what resolves: the card's grpId for a spell, the ability's grpId for an ability. |

## Identity model

The resolving object is named by its stack instance ID in both `affectorId` and
`affectedIds`. A spell that is renamed as it leaves the stack is still named by
its stack ID here.

## Lifecycle

1. `ResolutionStart` names the resolving object.
2. The effect's rows follow, such as `DamageDealt`, `ZoneTransfer` for objects
   the effect moves, or `ChoiceResult`.
3. When the effect needs a decision, the GSM that carries `ResolutionStart`
   precedes the prompt, and `ResolutionComplete` arrives in a later GSM after
   the answer.
4. `ResolutionComplete` closes the bracket.

## Contract

- <a id="ResolutionStart.self-affected"></a>**ResolutionStart.self-affected.**
  A `ResolutionStart` row `MUST` carry `affectedIds` equal to exactly its
  `affectorId`.
- <a id="ResolutionStart.grpid"></a>**ResolutionStart.grpid.**
  When the resolving game object carries no `overlayGrpId`, `grpid` `MUST`
  equal its `grpId`.

## Open points

- Which identity does `grpid` carry for a resolving object that has an
  `overlayGrpId`?
