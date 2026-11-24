---
summary: "Ninjutsu mechanic: a hand-zone activated ability whose cost returns an unblocked attacker, the reveal of the ninjutsu card, and its Put onto the Battlefield."
entity: mechanic/Ninjutsu
---
# Ninjutsu

Ninjutsu is an activated ability that works only while its card is in the
hand. Its cost returns an unblocked attacker to its owner's hand; on
resolution the ninjutsu card is put onto the Battlefield tapped and attacking.
The mechanic uses an `Activate` offer on a hand card, an object-selection
[PayCostsReq](../prompts/PayCostsReq.md) for the return cost, a revealed view of
the ninjutsu card, and a `Put` [ZoneTransfer](../annotations/ZoneTransfer.md).

## Rules grounding

CR 702.49a defines the ability and its cost; CR 702.49b keeps the card revealed
from activation until the ability leaves the stack; CR 702.49c makes the
creature attack the same player, planeswalker or battle as the returned one.
Commander ninjutsu (CR 702.49d) is the command-zone variant.

## Surfaces

| Surface | Entity pages |
|---|---|
| Activation from the hand | [Ability](../object_types/Ability.md), [AbilityInstanceCreated](../annotations/AbilityInstanceCreated.md), [UserActionTaken](../annotations/UserActionTaken.md) |
| Return cost | [PayCostsReq](../prompts/PayCostsReq.md) object-selection family, [ZoneTransfer](../annotations/ZoneTransfer.md) `Return` |
| Revealed ninjutsu card | [RevealedCard](../object_types/RevealedCard.md), [InstanceRevealedToOpponent](../annotations/InstanceRevealedToOpponent.md) |
| Resolution | [ZoneTransfer](../annotations/ZoneTransfer.md) `Put` |

## Activation lifecycle

1. During combat, after blockers are declared, the hand card is offered as an
   `Activate` action whose `abilityGrpId` is the card's ninjutsu ability. The
   ability is per card, not shared.
2. On submission the server creates the stack `Ability` with
   `AbilityInstanceCreated` naming the hand card, `source_zone` the hand. Mana
   payment rows name the ability.
3. The return cost arrives as a `PayCostsReq` with `promptId` `8580`: an
   exact-count selection of one, whose `ids` are the unblocked attackers and
   whose `CardId` parameter is the ninjutsu ability
   ([PayCostsReq.select-exact](../prompts/PayCostsReq.md#PayCostsReq.select-exact)).
4. After the `EffectCostResp`, one GSM carries `ObjectIdChanged` and a
   `ZoneTransfer` from Battlefield to Hand with `category` `Return` for the
   chosen attacker, the ability as `affectorId`, and `UserActionTaken` with
   `actionType` `2` and the ninjutsu ability. The same GSM creates a
   `RevealedCard` for the ninjutsu card and an `InstanceRevealedToOpponent`
   row on it.
5. The ninjutsu card keeps its hand instance ID while the ability is on the
   stack.

## Resolution lifecycle

1. `ResolutionStart` carries the ninjutsu ability.
2. `ObjectIdChanged` reallocates the hand card; `RevealedCardDeleted` retires
   its revealed view; a `ZoneTransfer` from Hand to Battlefield with
   `category` `Put` names the ability as `affectorId`.
3. `ResolutionComplete` and `AbilityInstanceDeleted` close the ability, and the
   `InstanceRevealedToOpponent` row on the old hand ID is deleted.
4. The permanent is tapped and attacking through its object state; no
   `TappedUntappedPermanent` row accompanies the put.

## Sibling shapes

Sneak also returns an unblocked attacker and puts its card onto the
Battlefield attacking, but it is an alternative cost of a cast: the card goes
to the stack through a `Cast` action. Ninjutsu never moves its card to the
stack; only the ability is on the stack.

## Contract

- <a id="Ninjutsu.hand-source"></a>**Ninjutsu.hand-source.** The ninjutsu
  ability's `AbilityInstanceCreated` `MUST` name the hand card as `affectorId`
  and carry the hand zone in `source_zone`.
- <a id="Ninjutsu.return-prompt"></a>**Ninjutsu.return-prompt.** The return
  cost `MUST` be requested as an exact-count object-selection `PayCostsReq`
  with `promptId` `8580`, `minSel` and `maxSel` `1`, the unblocked attackers in
  `ids`, and the ninjutsu ability's instance ID as its `CardId` parameter.
- <a id="Ninjutsu.return-transfer"></a>**Ninjutsu.return-transfer.** The
  returned attacker `MUST` move Battlefield to Hand with `category` `Return`
  and the ninjutsu ability as `affectorId`, in the same GSM as the
  activation's `UserActionTaken`.
- <a id="Ninjutsu.source-keeps-id"></a>**Ninjutsu.source-keeps-id.** The
  ninjutsu card `MUST` keep its hand instance ID while the ability is on the
  stack.
- <a id="Ninjutsu.source-revealed"></a>**Ninjutsu.source-revealed.**
  *(provisional)* Grounded for ninjutsu activated during the declare blockers
  step: the GSM that commits the return cost `MUST` create a `RevealedCard`
  for the ninjutsu card and an `InstanceRevealedToOpponent` row naming it,
  and the resolution `MUST` delete both.
- <a id="Ninjutsu.put-transfer"></a>**Ninjutsu.put-transfer.** When a ninjutsu
  ability resolves while its card is still in the hand, the resolution
  `MUST` move its card Hand to Battlefield with `category`
  `Put` and the ability as `affectorId`, between its `ResolutionStart` and
  `ResolutionComplete`.

## Open points

- What does a ninjutsu ability carry when its card has left the hand
  before it resolves?
- What does a commander ninjutsu activation from the command zone carry?
- What does the resolution carry when the ninjutsu card has left the hand?
- Is the returned attacker revealed when it lands in the hand?
