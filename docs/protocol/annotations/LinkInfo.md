---
summary: "LinkInfo annotation: persistent relation that outlives the GSM it was made in; LinkType branches for an ability and the object sacrificed for it and for a remembered choice, identity domains and retirement."
entity: annotation/LinkInfo
---
# LinkInfo

## Meaning

Some effects remember something after the moment it was decided: an
ability that refers to the object sacrificed to pay for it, or a permanent
that refers to the color or creature type chosen as it entered (CR 607).
`LinkInfo` keeps such a relation as persistent state. `LinkType` selects the
relation and with it the identity domain of `affectedIds`.

## Variants

Persistent only, never combined with another type.

| `LinkType` | Relation | `affectorId` | `affectedIds` |
|---|---|---|---|
| `2` | An activated ability and the object sacrificed to pay its cost, usually its own source, such as a Clue or a Food | The ability instance on the stack | The sacrificed object's instance ID before the sacrifice |
| `3` | A permanent and a choice it remembers | The permanent | Encoded choice values, by `ChooseLinkType` |
| `12`, `16` | Rare; see Open points | | |

On `LinkType` `3`, `ChooseLinkType` names the kind of choice:

| `ChooseLinkType` | `affectedIds` |
|---|---|
| `Color` | One `CardColor` value, such as `3` for black. |
| `Type` | Two values: `6`, then the chosen subtype's identifier. |
| `Parity` | One value for the chosen parity. |
| `Amount` | The chosen number. |

## Fields

| Field | Role |
|---|---|
| `affectorId` | The side of the relation that remembers, as above. |
| `affectedIds` | The remembered side, in the domain the branch selects. |
| `LinkType` | The relation. |
| `ChooseLinkType` | On `LinkType` `3`, the kind of choice, as a string. |
| `sourceAbilityGRPID` | On `LinkType` `3`, normally present: the ability definition that made the choice. |

## Identity model

`affectedIds` is not always an object list. On `LinkType` `2` it holds an
instance ID that the object no longer has: the sacrifice's
[ObjectIdChanged](ObjectIdChanged.md) in the same GSM names it as `orig_id`.
Follow that rename to reach the card in the graveyard. On `LinkType` `3` the
values are colors, subtype identifiers or numbers, never instance or seat IDs,
even when a value coincides with one.

## Lifecycle

1. **`LinkType` `2`.** The ability is activated and its sacrifice cost paid:
   in one GSM, `UserActionTaken`, the sacrifice's `ObjectIdChanged` and
   `ZoneTransfer`, and the new row. The row is normally deleted in the GSM
   where the ability resolves.
2. **`LinkType` `3`.** A player makes the choice, normally as the permanent
   enters: the row appears in the GSM that carries the
   [ChoiceResult](ChoiceResult.md) and stays while the permanent remains on
   the battlefield.

## Sibling shapes

[ChoiceResult](ChoiceResult.md) publishes a choice for one GSM; `LinkInfo`
`3` remembers it on the permanent. [TriggeringObject](TriggeringObject.md)
links a triggered ability to its source; `LinkInfo` `2` links an activated
ability to what it sacrificed.

## Contract

- <a id="LinkInfo.persistent"></a>**LinkInfo.persistent.**
  `LinkInfo` `MUST` be carried in `persistentAnnotations` with an Int32
  `LinkType` detail.
- <a id="LinkInfo.sacrificed-prior-id"></a>**LinkInfo.sacrificed-prior-id.**
  A `LinkType` `2` row `MUST` name as `affectedIds` the `orig_id` of an
  `ObjectIdChanged` in the GSM where the row first appears.
- <a id="LinkInfo.choice-kind"></a>**LinkInfo.choice-kind.**
  A `LinkType` `3` row `MUST` carry `ChooseLinkType`.
- <a id="LinkInfo.type-shape"></a>**LinkInfo.type-shape.**
  A `LinkType` `3` row with `ChooseLinkType` `Type` `MUST` carry exactly two
  `affectedIds` entries, the first `6`.
- <a id="LinkInfo.choice-with-result"></a>**LinkInfo.choice-with-result.**
  *(provisional)* A `LinkType` `3` row for a `Color` or `Type` choice `MUST`
  first appear in a GSM that carries a `ChoiceResult`.

## Open points

- What does the leading `6` of a `Type` choice encode?
- What do `LinkType` `12`, which accompanies a cast or activation, and
  `LinkType` `16`, which accompanies a layered effect, relate?
- Why are some `LinkType` `2` rows retired after their ability's resolution
  GSM?
