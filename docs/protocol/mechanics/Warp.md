---
summary: "Warp mechanic: the hand cast for the warp cost, the end-step exile with its Warp transfer category, and the later cast from exile through the shared warp permission."
entity: mechanic/Warp
---
# Warp

Warp lets a player cast a card from the hand for its warp cost. A permanent
cast that way is exiled at the beginning of the next end step, and its owner
may cast it from exile on a later turn. The mechanic uses two cast rails with
different identities: the hand cast is an alternative-cost
[Cast](../actions/Cast.md) and the exile cast goes through a cast permission.
Between them, a delayed trigger's
[DelayedTriggerAffectees](../annotations/DelayedTriggerAffectees.md) row and a
`ZoneTransfer` with `category` `Warp` move the card to Exile.

## Rules grounding

CR 702.185a defines warp as two static abilities: cast the card from the hand
for its warp cost, which sets up the end-step exile of the resulting
permanent, and cast a card exiled this way from exile on a later turn.

## Surfaces

| Surface | Entity pages |
|---|---|
| Hand cast for the warp cost | [Cast](../actions/Cast.md), [UserActionTaken](../annotations/UserActionTaken.md), [CastingTimeOption](../annotations/CastingTimeOption.md) |
| Pending exile | [TriggerHolder](../object_types/TriggerHolder.md), [DelayedTriggerAffectees](../annotations/DelayedTriggerAffectees.md) |
| End-step exile | [ZoneTransfer](../annotations/ZoneTransfer.md) `Warp` |
| Later cast from exile | [UserActionTaken](../annotations/UserActionTaken.md), [CastingTimeOption](../annotations/CastingTimeOption.md) |

## Hand cast lifecycle

1. The hand card is offered as a `Cast` action whose `alternativeGrpId` is a
   warp cost ability. That ability names a cost, not a card: cards with the
   same warp cost share it.
2. The cast GSM carries `ObjectIdChanged` and a Hand to Stack `ZoneTransfer`
   with `category` `CastSpell`, `UserActionTaken` with `actionType` `1`,
   `abilityGrpId` `0` and `alternativeGrpId` the warp cost ability, and a
   persistent `CastingTimeOption` with `type` `13` and `alternateCostGrpId`
   the same ability, without `castAbilityGrpId`.

## End-step exile lifecycle

1. The permanent spell's resolution GSM creates a `TriggerHolder` for the
   delayed exile and a `DelayedTriggerAffectees` row from the holder to the
   permanent, naming the delayed ability `372` with `removesFromZone`.
2. When the trigger fires at the beginning of the next end step, the holder
   is deleted and the row is republished with the fired trigger as
   `affectorId`.
3. When it resolves, `ObjectIdChanged` reallocates the permanent and a
   `ZoneTransfer` from Battlefield to Exile carries `category` `Warp`, with
   the permanent's old instance as `affectorId`. The `DelayedTriggerAffectees`
   row is deleted.

## Exile cast lifecycle

1. On a later turn the exiled card is offered as a cast from Exile.
2. The cast GSM carries `ObjectIdChanged` and an Exile to Stack `ZoneTransfer`
   with `category` `CastSpell`, both with the exile card as `affectorId`;
   `UserActionTaken` with `actionType` `1` and `abilityGrpId` `371`, without
   `alternativeGrpId`; and a persistent `CastingTimeOption` with `type` `13`
   and `castAbilityGrpId` `371`, without `alternateCostGrpId`.

`371` is the warp keyword ability, shared by every warp card. The later cast
pays the card's mana cost; it is a permission, not an alternative cost.

## Sibling shapes

Sneak uses the same hand-cast rail: a cost ability in `alternativeGrpId` and
`alternateCostGrpId` with `CastingTimeOption` `type` `13`. Sneak has no
end-step exile or later cast from exile. Plot and foretell also lead to a
later cast from exile, but reach Exile through a special action rather than a
cast and a delayed trigger.

## Contract

- <a id="Warp.hand-accepted"></a>**Warp.hand-accepted.** A hand cast for the
  warp cost `MUST` be recorded by `UserActionTaken` with `actionType` `1`,
  `abilityGrpId` `0` and `alternativeGrpId` naming the warp cost ability.
- <a id="Warp.hand-option"></a>**Warp.hand-option.** A spell cast for its
  warp cost `MUST` carry a persistent `CastingTimeOption` with `type` `13` and
  `alternateCostGrpId` naming the warp cost ability, and `MUST NOT` carry
  `castAbilityGrpId`.
- <a id="Warp.pending-exile"></a>**Warp.pending-exile.** *(provisional)* The
  resolution of a permanent spell cast for its warp cost `MUST` create a
  `DelayedTriggerAffectees` row from a trigger holder to the permanent,
  naming ability `372`.
- <a id="Warp.exile-category"></a>**Warp.exile-category.** The end-step exile
  of a warped permanent `MUST` move it Battlefield to Exile with `category`
  `Warp`.
- <a id="Warp.exile-accepted"></a>**Warp.exile-accepted.** A cast of a warped
  card from exile `MUST` be recorded by `UserActionTaken` with `actionType`
  `1` and `abilityGrpId` `371`, and `MUST NOT` carry `alternativeGrpId`.
- <a id="Warp.exile-option"></a>**Warp.exile-option.** A spell cast from exile
  through warp `MUST` carry a persistent `CastingTimeOption` with `type` `13`
  and `castAbilityGrpId` `371`, and `MUST NOT` carry `alternateCostGrpId`.

## Open points

- What does the delayed exile carry when the permanent has already left the
  Battlefield?
- What does a warp cast of a nonpermanent spell carry, given that no
  permanent is exiled?
- Which cast does warp offer when the card is cast from somewhere other than
  the hand, such as the top of a library?
