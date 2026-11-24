---
summary: "Flashback: casting an instant or sorcery from the graveyard for its flashback cost and exiling it when it leaves the stack; the graveyard Cast offer, the accepted cast, the stack marker and the move to exile."
entity: mechanic/Flashback
---
# Flashback

Flashback lets a player cast an instant or sorcery from their graveyard for
its flashback cost; the spell is exiled instead of going anywhere else when
it leaves the stack. The protocol carries it as an alternative-cost
[Cast](../actions/Cast.md) offered from the graveyard, the
`CastThroughAbility` branch of
[CastingTimeOption](../annotations/CastingTimeOption.md), and a Stack to
Exile [ZoneTransfer](../annotations/ZoneTransfer.md) on resolution.

## Rules grounding

Flashback lets the card be cast from the graveyard by paying its flashback
cost rather than its mana cost, and exiles it instead of putting it anywhere
else any time it would leave the stack (CR 702.34a).

## Surfaces

| Surface | Entity pages |
|---|---|
| Graveyard offer | [Cast](../actions/Cast.md) |
| Accepted cast | [UserActionTaken](../annotations/UserActionTaken.md), [ZoneTransfer](../annotations/ZoneTransfer.md) `CastSpell` |
| Stack marker | [CastingTimeOption](../annotations/CastingTimeOption.md) `type` `13` |
| Leaving the stack | [ZoneTransfer](../annotations/ZoneTransfer.md) Stack to Exile |

## Cast lifecycle

1. The card in the graveyard is offered as a `Cast` whose `abilityGrpId` and
   `alternativeGrpId` are both the card's flashback ability. Its `manaCost`
   entries carry that ability's ID, and `alternativeSourceZcid` names the
   card.
2. When the client submits it, the card moves Graveyard to Stack with
   `CastSpell` under a new instance ID. The stack spell carries a
   `CastingTimeOption` row of `type` `13` with the flashback ability in
   `alternateCostGrpId` and `castAbilityGrpId`.
3. After payment, `UserActionTaken` records `actionType` `1` with the
   flashback ability in both `alternativeGrpId` and `abilityGrpId`.

## Resolution lifecycle

1. The spell's effect resolves.
2. The spell gets a new instance ID and moves Stack to Exile with `category`
   `Resolve`. The destination, not the category, carries the flashback
   replacement.

The card thus passes through three instance IDs: graveyard, stack and exile.

## Sibling shapes

Jump-start also casts an instant or sorcery from the graveyard and exiles it
afterwards, but it pays the card's mana cost plus a discard, through the
shared jump-start ability. [Escape](Escape.md) does not exile the spell when it
resolves. `Retrace` casts from the graveyard with a land discard and puts the
card back into the graveyard.

## Contract

- <a id="Flashback.offer"></a>**Flashback.offer.** *(provisional)* A flashback
  offer from the graveyard `MUST` carry the flashback ability in both
  `abilityGrpId` and `alternativeGrpId`.
- <a id="Flashback.accepted"></a>**Flashback.accepted.** *(provisional)* An
  accepted flashback cast `MUST` be recorded by `UserActionTaken` with
  `actionType` `1` and the flashback ability in both `alternativeGrpId` and
  `abilityGrpId`.
- <a id="Flashback.cast-marker"></a>**Flashback.cast-marker.** *(provisional)*
  The stack spell's `CastingTimeOption` row `MUST` have `type` `13` with the
  flashback ability in `alternateCostGrpId`.
- <a id="Flashback.resolve-exile"></a>**Flashback.resolve-exile.** When a
  flashback spell resolves, it `MUST` move Stack to Exile under a new instance
  ID.

## Open points

- What `category` does the move to exile carry when a flashback spell is
  countered or otherwise leaves the stack without resolving?
- Does a flashback cast of a card that gained flashback from another source
  name that source in `castAbilityGrpId`?
