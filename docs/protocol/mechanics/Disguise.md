---
summary: "Disguise: face-down cast for {3} under ability 307, the face-down 2/2 ward envelope on the stack and battlefield, and the turn-face-up special action."
entity: mechanic/Disguise
---
# Disguise

Disguise lets a card be cast face down from the hand for `{3}` as a 2/2
creature with ward `{2}`, and turned face up later for its disguise cost. The
face-down cast is a [Cast](../actions/Cast.md) with `alternativeGrpId` `307`;
the face-down state is carried by the object and by
[FaceDown](../annotations/FaceDown.md); turning it up is a
[SpecialTurnFaceUp](../actions/Special_TurnFaceUp.md) action.

## Rules grounding

CR 702.168a lets the card be cast as a face-down 2/2 creature with ward `{2}`
by paying `{3}`, and lets its controller turn it face up for its disguise
cost. Turning a permanent face up is a special action and does not use the
stack (CR 702.168d, CR 116.2b).

## Surfaces

| Surface | Entity pages | Disguise values |
|---|---|---|
| Face-down cast | [Cast](../actions/Cast.md), [UserActionTaken](../annotations/UserActionTaken.md), [CastingTimeOption](../annotations/CastingTimeOption.md) | `alternativeGrpId` `307`, generic cost `3` |
| Face-down state | [Card](../object_types/Card.md), [FaceDown](../annotations/FaceDown.md) | `overlayGrpId` `3`, ward ability `141939`, `REASON` `6` |
| Turning face up | [SpecialTurnFaceUp](../actions/Special_TurnFaceUp.md), `TurnPermanent` | The card's own turn-face-up ability |

## Face-down cast lifecycle

1. The hand card is offered as a `Cast` with `alternativeGrpId` `307` and a
   `manaCost` of three generic mana keyed by ability `307`, beside the
   card's ordinary `Cast`.
2. The cast GSM moves the card from the hand to the Stack under a new
   instance ID and records `UserActionTaken` with `actionType` `1`,
   `alternativeGrpId` `307` and `abilityGrpId` `0`.
3. The same GSM creates the persistent `FaceDown` row with `REASON` `6` and
   `abilityGrpId` `307` on the stack instance. The caster's projection also
   carries a persistent `CastingTimeOption` row with `type` `13` and
   `alternateCostGrpId` `307`, and no `castAbilityGrpId`.
4. The spell resolves onto the Battlefield under the same instance ID; the
   `CastingTimeOption` row is deleted with the `Resolve` transfer, and the
   `FaceDown` row stays live.

## Face-down state

While the card is face down, on the Stack and on the Battlefield, its object
carries:

| Field | Caster's view | Other seat's view |
|---|---|---|
| `grpId` | The card's own definition | `3` |
| `visibility`, `viewers` | `Private`, the caster | `Public`, the caster |
| `isFacedown`, `overlayGrpId` | `true`, `3` | `true`, `3` |
| `cardTypes` | `Creature` only | `Creature` only |
| `power`, `toughness` | `2`, `2` | `2`, `2` |
| `uniqueAbilities` | One entry, ward ability `141939` | One entry, ward ability `141939` |
| `name`, `subtypes`, `color` | Absent | Absent |

## Turn-face-up lifecycle

1. The face-down permanent is offered as `SpecialTurnFaceUp` with the card's
   own turn-face-up ability as `alternativeGrpId` and the disguise cost as
   `manaCost`.
2. After payment, one GSM carries `TurnPermanent` naming the permanent,
   then `UserActionTaken` with `actionType` `8`, the turn-face-up ability as
   `alternativeGrpId` and `abilityGrpId` `0`. Abilities triggered by turning
   it face up are created in the same GSM.
3. That GSM deletes the `FaceDown` row and republishes the object with its
   printed characteristics under the same instance ID.

## Sibling shapes

[FaceDown](../annotations/FaceDown.md) lists the other face-down paths.
Foretell also hides a card, but in exile and through a `Special` action;
manifest dread puts a library card onto the Battlefield face down without a
cast, and its face-down object has no ward ability. Ability `307` in
`alternativeGrpId` is what marks a disguise cast.

## Contract

- <a id="Disguise.cast-offer"></a>**Disguise.cast-offer.** A face-down disguise
  cast `MUST` be offered as a `Cast` on the hand card with `alternativeGrpId`
  `307` and a `manaCost` of three generic mana whose `abilityGrpId` is `307`.
- <a id="Disguise.accepted"></a>**Disguise.accepted.** An accepted face-down
  cast `MUST` be recorded by `UserActionTaken` with `actionType` `1`,
  `alternativeGrpId` `307` and `abilityGrpId` `0`.
- <a id="Disguise.cast-option"></a>**Disguise.cast-option.** *(provisional)* In
  the caster's projection, a face-down disguise spell `MUST` carry a
  persistent `CastingTimeOption` row with `type` `13` and `alternateCostGrpId`
  `307`, created in the cast GSM.
- <a id="Disguise.envelope"></a>**Disguise.envelope.** While a disguised card is
  face down on the Stack or the Battlefield, its object `MUST` carry
  `isFacedown`, `overlayGrpId` `3`, `cardTypes` `Creature` only, power and
  toughness `2`, and exactly one entry in `uniqueAbilities`, with `grpId`
  `141939`, and `MUST NOT` carry `name`, `subtypes` or `color`.
- <a id="Disguise.opponent-grpid"></a>**Disguise.opponent-grpid.** *(provisional)* In the
  other seat's projection, a face-down disguised object `MUST` carry `grpId`
  `3`.
- <a id="Disguise.turn-up"></a>**Disguise.turn-up.** *(provisional)* The GSM
  that turns a disguised permanent face up `MUST` carry `TurnPermanent`
  before the action's `UserActionTaken` with `actionType` `8`, and `MUST`
  delete the permanent's `FaceDown` row.

## Open points

- Why does the other seat's projection carry no `CastingTimeOption` row for a
  face-down disguise spell?
- What does `alternativeSourceZcid` name on a face-down cast offer when it
  differs from `instanceId`?
- What does the other seat see as `ResolutionStart.grpid` when a face-down
  spell resolves?
- What happens to the `FaceDown` row when a face-down permanent leaves the
  Battlefield?
