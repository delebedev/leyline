---
summary: "Emblem game object: a player-associated emblem in the Command zone; universal object identity, source lineage, abilities and persistence."
entity: object_type/Emblem
---
# Emblem

An `Emblem` represents an ongoing ability associated with a player. The object is
a public member of the Command zone rather than the Battlefield, and its own
identity carries no card characteristics.

## Object model

| Field | Role |
|---|---|
| `instanceId` | The emblem object. |
| `grpId`, `overlayGrpId` | `2` for every emblem. |
| `objectSourceGrpId` | The card definition the emblem came from. |
| `parentId` | The object whose resolution created the emblem. |
| `name` | `1`. |
| `uniqueAbilities[].id` | An ability handle within the emblem. |
| `uniqueAbilities[].grpId` | The emblem's ability definitions. |

The emblem's own `grpId` says nothing about which card created it. Its abilities
are hidden abilities of the source card definition, not abilities of the
universal emblem identity. Card types, colors and power/toughness are absent.

## Zone and visibility

An emblem is a public member of the Command zone. Its source and parent
identities describe lineage, not Battlefield membership (CR 114).

## Lifecycle

1. The spell or ability that creates an emblem resolves, and the emblem appears
   in Command as a new object.
2. The emblem stays projected across subsequent game states while its source
   remains.
3. An ability the emblem causes is published as its own ability object with
   `parentId` naming the emblem and `source_zone` naming Command; targeting and
   resolution use that ability's own `grpId`.
4. Retiring such a stack ability does not retire the emblem.

Sephiroth, One-Winged Angel publishes its emblem in the same state as the
transforming trigger's resolution, and its `parentId` names the transformed
card. The Ring's emblem is parented to the resolving ability that caused the
temptation rather than to the originating permanent. That difference does not
establish a parent convention for other emblem creators.

## Sibling shapes

A [`Card`](Card.md) is zone-bearing and carries the characteristics of its face;
an emblem carries none of its own. A [`Token`](Token.md) records creation lineage
through `objectSourceGrpId` and `parentId` and lives on the Battlefield, while an
emblem lives in Command.

## Contract

- <a id="Emblem.command-member"></a>**Emblem.command-member.** An emblem object
  `MUST` be a `Public` member of the Command zone.
- <a id="Emblem.universal-grp-id"></a>**Emblem.universal-grp-id.** Every emblem
  object `MUST` carry `grpId` `2` and `overlayGrpId` `2`.
- <a id="Emblem.plain-characteristics"></a>**Emblem.plain-characteristics.** An
  emblem object `MUST NOT` carry card types, colors, power or toughness.
- <a id="Emblem.source-lineage"></a>**Emblem.source-lineage.** An emblem's
  `objectSourceGrpId` `MUST` name the card definition that created it.
- <a id="Emblem.ability-ids"></a>**Emblem.ability-ids.** Each
  `uniqueAbilities[].grpId` on an emblem `MUST` be one of that emblem's ability
  definitions, and the `uniqueAbilities[].id` values `MUST` be distinct.

## Open points

- Which object do different emblem creators name in `parentId`?
- Is an emblem ever retired while the game continues, and by which row?
