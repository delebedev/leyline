---
summary: "Token game object: a token created during the game; creation lineage, battlefield membership, departure and retirement."
read_when:
  - "projecting a created token"
  - "emitting a token's creation or departure"
entity: object_type/Token
---
# Token

A `Token` is the zone-bearing object for a token created during the game. It
carries the token's current characteristics and keeps separate links to the
effect and object that created it.

## Object model

| Field | Role |
|---|---|
| `instanceId` | The token's lifetime as one object. |
| `grpId` | The token definition and its visible characteristics. |
| `objectSourceGrpId` | The definition of the card or effect that created the token. |
| `parentId` | The spell or ability instance that created the token. That instance may already be retired. |
| `uniqueAbilities` | Abilities the token definition has, when it has any. |

Creation lineage is not ownership. `parentId` and `objectSourceGrpId` say
where the token came from; `ownerSeatId` and `controllerSeatId` say whose it
is now.

## Zone and visibility

A token is a public member of the Battlefield. When it leaves, its old object
can stay in Limbo while annotations describe a destination ID. A token in
another zone ceases to exist (CR 111.7), so that destination ID is a handle
for the move, not a lasting object.

## Lifecycle

1. The creating spell or ability emits `TokenCreated` with the token's ID in
   `affectedIds`.
2. A public `Token` object appears on the Battlefield and joins its member
   list.
3. The token keeps its ID while it stays on the Battlefield.
4. When it leaves, `ObjectIdChanged` and `ZoneTransfer` give it a destination
   ID, followed by `TokenDeleted`.
5. `diffDeletedInstanceIds` retires the old object parked in Limbo.

## Sibling shapes

Unlike a [Card](Card.md), a `Token` normally records its creation lineage.
Unlike a card-side companion, it is itself a zone member and can be targeted
on the Battlefield.

## Contract

- <a id="Token.created"></a>**Token.created.** In the GSM where a token is
  created, a `TokenCreated` row `MUST` name its instance ID in `affectedIds`,
  and a `Public` `Token` object with that ID `MUST` have `zoneId` `28`
  (Battlefield).
- <a id="Token.keeps-id"></a>**Token.keeps-id.** A token `MUST` keep its
  instance ID for as long as it stays on the Battlefield.

## Open points

- When is a `Token` object shown in a Graveyard or Exile before it ceases to
  exist?
- What does `TokenDeleted` carry when the token leaves for a zone other than
  the graveyard?
