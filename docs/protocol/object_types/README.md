---
summary: "Game object type family overview: generic objects, protocol utilities and card-side companions, with one row per GameObjectType."
read_when:
  - "finding the page or role of a GameObjectType"
  - "projecting a card, ability, token or utility object into a GSM"
---
# Game object types

Game objects are the identities a GSM projects for cards, abilities, tokens,
revealed cards and card-side companions. An object's `type` decides how its
identity relates to zones, parents, sources, abilities and what each viewer
sees. The shared identity rules, zone layout and visibility live in
[identity and zones](../model/identity-and-zones.md).

Objects fall into three families:

- **Generic objects** are zone members: cards, tokens, stack abilities and
  emblems.
- **Protocol utilities** represent transient client concepts: a card being
  revealed, or a trigger waiting to fire.
- **Card-side companions** represent one face, half or role of a parent card.
  A companion appears in `gameObjects` and names its parent card in
  `parentId`; the zone lists the parent, not the companion.

The object type is independent of annotation and mechanic identity.

| Type | Family | Role |
|---|---|---|
| [Card](Card.md) | generic | A card, permanent or spell. |
| [Token](Token.md) | generic | A token created during the game. |
| [Ability](Ability.md) | generic | An activated or triggered ability on the Stack or in Pending. |
| [Emblem](Emblem.md) | generic | An emblem in the Command zone. |
| `SplitCard` | card side | A split card as a whole. |
| [SplitLeft](SplitLeft.md), [SplitRight](SplitRight.md) | card side | A split card's halves. |
| [RevealedCard](RevealedCard.md) | protocol utility | A temporary public projection of a revealed card. |
| [TriggerHolder](TriggerHolder.md) | protocol utility | A delayed or recurring trigger between live ability instances. |
| [Adventure](Adventure.md) | card side | The Adventure half of an Adventure card. |
| [MDFCBack](MDFCBack.md) | card side | The back face of a modal double-faced card. |
| [DisturbBack](DisturbBack.md) | card side | The back face cast with disturb. |
| `Boon` | card side | A boon granted by a card. |
| `PrototypeFacet` | card side | The prototype characteristics of a card. |
| [RoomLeft](RoomLeft.md), [RoomRight](RoomRight.md) | card side | The doors of a Room. |
| [Omen](Omen.md) | card side | The Omen half of an Omen card. |
