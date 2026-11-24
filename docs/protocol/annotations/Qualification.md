---
summary: "Qualification annotation: persistent rules predicate on an object or player, such as a combat restriction, evasion keyword, cast permission or cost change; QualificationType branches, object lists and identities."
entity: annotation/Qualification
---
# Qualification

## Meaning

Many static and continuous effects qualify what an object or player may do
or how a rule applies to them: a creature can't block, can't be blocked
except by two or more creatures, doesn't untap, or may be cast from exile; a
spell can't be countered; players can't gain life. `Qualification` publishes
each such predicate as persistent state so the client can explain and enforce
it in its interface.

## Variants

Persistent only. A row normally carries no other type; a few player-level
rows are also typed `MiscContinuousEffect`.

`QualificationType` selects the predicate. Its values have no public enum;
the branches below are those with a known meaning:

| `QualificationType` | Predicate | Branch keys | Example |
|---|---|---|---|
| `30` | Can't attack | `CantAttackObjects` when limited to listed objects | Pacifism |
| `31` | Can't block | `CantBlockObjects` when limited to listed attackers | A creature that can't block |
| `32` | Can't be blocked | `CantBeBlockedByObjects` when limited to listed blockers | A creature that can't be blocked this turn |
| `40` | Combat keyword | none | Menace |
| `47` | May be cast from another zone | none | An adventure card in exile; a card exiled with permission to cast it |
| `21` | Doesn't untap | none | Colossus of Sardia |
| `10` | Can't be countered | none | A spell that can't be countered |
| `1` | Cost change | none | A cost reduction for a class of spells |

Other values occur with branch keys such as `must_attack`, `must_be_blocked`,
`affectedType` and `additional_lands`, and with
the duration keys `Duration`, `durationContinues` and `durationWarning`.
`QualificationSubtype` is `0` on most rows; see Open points.

## Fields

| Field | Role |
|---|---|
| `affectorId` | The source of the predicate: the permanent with the static ability, the Aura, or the card granting a permission. Absent on the adventure cast permission. |
| `affectedIds` | The qualified objects, or seats for a player-scoped predicate. |
| `QualificationType`, `QualificationSubtype` | The predicate, as above. |
| `SourceParent` | The source object's instance ID: `affectorId`, or the source of the ability named by `affectorId`; `0` when the row has no source. |
| `grpid` | The ability definition that states the predicate, such as Menace or "can't block". |
| `parent_grpid` | When present, the card definition of the source. |
| `CantAttackObjects`, `CantBlockObjects`, `CantBeBlockedByObjects` | Instance IDs of the objects the restriction is limited to. |
| `Duration`, `durationContinues`, `durationWarning` | When present, describe a bounded predicate; `Duration` names the boundary, such as `NextUntap`. |

`QualificationType`, `QualificationSubtype`, `SourceParent` and `grpid` are
Int32 details.

## Identity model

`affectorId`, `SourceParent` and the object lists are instance IDs.
`affectedIds` holds instance IDs or seat IDs depending on the predicate.
`grpid` and `parent_grpid` are definitions, not objects. A restriction that
names its counterparts lists them in the detail, not in `affectedIds`:
for "can't be blocked by", `affectedIds` is the attacker and
`CantBeBlockedByObjects` the excluded blockers.

## Lifecycle

1. A permanent with a static ability enters, or an effect begins: the row
   appears in the same GSM.
2. The row stays in `persistentAnnotations` while the predicate applies.
3. The row is deleted when the predicate stops applying: its source leaves,
   the effect ends, a duration expires, or the qualified object leaves.

## Sibling shapes

`ShouldntPlay` is a transient advisory about one option at one moment;
`Qualification` is a lasting rules predicate. `MiscContinuousEffect` carries
player-level effects and can share a row with `Qualification`.
[AddAbility](AddAbility.md) records that an effect grants an ability;
`Qualification` records the predicate the ability imposes.

## Contract

- <a id="Qualification.persistent"></a>**Qualification.persistent.**
  `Qualification` `MUST` be carried in `persistentAnnotations`.
- <a id="Qualification.core-keys"></a>**Qualification.core-keys.**
  A row `MUST` carry `QualificationType`, `QualificationSubtype`,
  `SourceParent` and `grpid` as Int32 details.
- <a id="Qualification.object-list-type"></a>**Qualification.object-list-type.**
  `CantAttackObjects` `MUST` appear only on `QualificationType` `30`,
  `CantBlockObjects` only on `31`, and `CantBeBlockedByObjects` only on `32`.
- <a id="Qualification.no-source"></a>**Qualification.no-source.**
  A row without `affectorId` `MUST` carry `SourceParent` `0`.
- <a id="Qualification.source-parent"></a>**Qualification.source-parent.**
  *(provisional)* When `affectorId` is present, `SourceParent` `MUST` equal
  it or, when `affectorId` is an ability object, that object's `parentId`.

## Open points

- What do the remaining `QualificationType` values and the non-zero
  `QualificationSubtype` values mean?
- What selects an ability object rather than its source as `affectorId`?
- When is a granted keyword published as a `Qualification` and when only as
  [AddAbility](AddAbility.md)?
