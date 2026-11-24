---
summary: "ResolutionComplete annotation: a spell or ability finished resolving; pairing with ResolutionStart and ordering against the resolved object's own zone transfer."
entity: annotation/ResolutionComplete
---
# ResolutionComplete

## Meaning

`ResolutionComplete` closes the resolution that
[ResolutionStart](ResolutionStart.md) opened: the spell or ability has
finished applying its effects (CR 608.2).

## Variants

One transient row per resolved object, with the same fields as
`ResolutionStart`.

## Fields

| Field | Role |
|---|---|
| `affectorId` | The resolved spell or ability instance, under its stack ID. |
| `affectedIds` | The same instance, as a one-element list. |
| `grpid` | The same value as the matching `ResolutionStart`. |

## Lifecycle

`ResolutionComplete` follows its `ResolutionStart`, in the same GSM or, when a
prompt interrupts the resolution, a later one. It precedes the rows that
retire the resolved object:

- for a spell, the `ObjectIdChanged`, if any, and the `ZoneTransfer` with
  `category` `Resolve` that moves the card off the stack;
- for an ability, its [AbilityInstanceDeleted](AbilityInstanceDeleted.md).

For example, Lightning Bolt resolving publishes, in order, `ResolutionStart`,
`DamageDealt`, `ResolutionComplete`, `ObjectIdChanged` and the `Resolve`
`ZoneTransfer` to its owner's graveyard.

## Contract

- <a id="ResolutionComplete.self-affected"></a>**ResolutionComplete.self-affected.**
  A `ResolutionComplete` row `MUST` carry `affectedIds` equal to exactly its
  `affectorId`.
- <a id="ResolutionComplete.after-start"></a>**ResolutionComplete.after-start.**
  A `ResolutionComplete` `MUST` be preceded by a `ResolutionStart` with the
  same `affectorId` and `grpid`, in the same GSM or an earlier one.
- <a id="ResolutionComplete.before-resolve-transfer"></a>**ResolutionComplete.before-resolve-transfer.**
  For a resolved spell, `ResolutionComplete` `MUST` precede, in the same GSM,
  the `ZoneTransfer` with `category` `Resolve` that moves the spell off the
  stack.

## Open points

- Which rows close a resolution that is cut short, for example when every
  target became illegal?
