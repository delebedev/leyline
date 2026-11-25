---
summary: "Manifest dread: choosing one of the top two library cards to put onto the battlefield face down as a 2/2, the other going to the graveyard; the SelectNReq prompt 13125, the ManifestLike transfer, the face-down object and the turn-face-up action."
entity: mechanic/ManifestDread
---
# Manifest dread

Manifest dread is a keyword action. The player looks at the top two cards of
their library, manifests one of them, putting it onto the battlefield face
down as a 2/2 creature, and puts the other into their graveyard. The protocol
carries the choice as a [SelectNReq](../prompts/SelectNReq.md) during
resolution, the manifested card as a face-down battlefield object with a
[FaceDown](../annotations/FaceDown.md) row, and turning a creature card face
up as a [Special_TurnFaceUp](../actions/Special_TurnFaceUp.md) action.

## Rules grounding

To manifest dread, a player looks at the top two cards of their library,
manifests one of them and puts the rest into their graveyard (CR 701.62a).
A manifested card that is a creature card can be turned face up for its mana
cost (CR 701.40a, CR 701.40b).

## Surfaces

| Surface | Entity pages |
|---|---|
| Choice | [SelectNReq](../prompts/SelectNReq.md) prompt `13125` |
| Results | [ZoneTransfer](../annotations/ZoneTransfer.md) `ManifestLike` and `Put` |
| Face-down permanent | [FaceDown](../annotations/FaceDown.md) `REASON` `8` |
| Turning face up | [Special_TurnFaceUp](../actions/Special_TurnFaceUp.md) |

## Choice

During resolution the player receives a `SelectNReq` with prompt `13125` and
`allowCancel` `No`. Its `ids` are the two top library cards, with `minSel`
`1`, `maxSel` `1`, `context` `SelectionContext_Resolution`, `optionContext`
`OptionContext_Resolution`, `listType` `SelectionListType_Dynamic` and
`idType` `IdType_InstanceId`. `sourceId` is the resolving object. The chosen
card is not a target.

## Resolution lifecycle

1. The chosen card gets a new instance ID and moves Library to Battlefield
   with `category` `ManifestLike`.
2. The other card gets a new instance ID and moves Library to Graveyard with
   `category` `Put`.
3. The manifested object is private and face down: `overlayGrpId` `3`,
   `isFacedown` `true`, `cardTypes` Creature and power and toughness 2/2,
   with no name, subtypes or colors.
4. It carries a self-affecting persistent `FaceDown` row with `REASON` `8`
   and `abilityGrpId` `351`, which stays until it is turned face up or leaves
   the battlefield.

## Turning face up

A manifested creature card with a mana cost is offered a
`SpecialTurnFaceUp` action on its instance, with `alternativeGrpId` `351` and
the card's mana cost. A noncreature card gets no such action.

## Sibling shapes

| Axis | Manifest dread | Disguise | Foretell |
|---|---|---|---|
| Origin | Top of library, chosen during resolution | Cast face down from hand | Hand, by a special action |
| Zone | Battlefield | Battlefield | Exile |
| `FaceDown` `REASON` / `abilityGrpId` | `8` / `351` | `6` / `307` | `5` / `208` |

Manifest dread's face-down 2/2 has no ward ability; disguise's does.

## Contract

- <a id="ManifestDread.choice"></a>**ManifestDread.choice.** The manifest
  dread choice `MUST` be a `SelectNReq` with prompt `13125`, `allowCancel`
  `No`, `minSel` `1` and `maxSel` `1` over the two top library cards.
- <a id="ManifestDread.manifest-transfer"></a>**ManifestDread.manifest-transfer.**
  *(provisional)* When the chosen card is manifested, it `MUST` move Library
  to Battlefield with `category` `ManifestLike`.
- <a id="ManifestDread.other-to-graveyard"></a>**ManifestDread.other-to-graveyard.**
  *(provisional)* When the other card goes to the graveyard, it `MUST` move
  Library to Graveyard with `category` `Put`.
- <a id="ManifestDread.noncreature-no-turn-up"></a>**ManifestDread.noncreature-no-turn-up.**
  A manifested noncreature card `MUST NOT` be offered a turn-face-up action.

The face-down row is stated by
[FaceDown.reason-ability](../annotations/FaceDown.md#FaceDown.reason-ability)
and the turn-face-up offer by
[Special_TurnFaceUp.offer](../actions/Special_TurnFaceUp.md#Special_TurnFaceUp.offer).

## Open points

- How is the other card's move published when a replacement effect sends
  it somewhere other than the graveyard?
- How is the manifested object shown to the opponent?
- How does turning a manifested card face up through another effect differ?
