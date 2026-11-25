---
summary: "Jump-start: casting an instant or sorcery from the graveyard by discarding a card, then exiling it when it leaves the stack; the shared ability 170 on the offer and the accepted row, the stack marker, the discard and the move to exile."
entity: mechanic/JumpStart
---
# Jump-start

Jump-start lets a player cast an instant or sorcery from their graveyard by
paying its mana cost and discarding a card as an additional cost; the spell
is exiled instead of going anywhere else when it leaves the stack. The
protocol carries it as a [Cast](../actions/Cast.md) from the graveyard keyed
by the shared jump-start ability `170`, the `CastThroughAbility` branch of
[CastingTimeOption](../annotations/CastingTimeOption.md), a Hand to Graveyard
`Discard` transfer for the cost and a Stack to Exile
[ZoneTransfer](../annotations/ZoneTransfer.md) on resolution.

## Rules grounding

Jump-start means "You may cast this card from your graveyard if the resulting
spell is an instant or sorcery spell by discarding a card as an additional
cost to cast it" and "If this spell was cast using its jump-start ability,
exile this card instead of putting it anywhere else any time it would leave
the stack" (CR 702.133a).

## Surfaces

| Surface | Entity pages |
|---|---|
| Graveyard offer | [Cast](../actions/Cast.md) |
| Stack marker | [CastingTimeOption](../annotations/CastingTimeOption.md) `type` `13`, `AbilityWordActive` |
| Discard cost | [ZoneTransfer](../annotations/ZoneTransfer.md) `Discard` |
| Accepted cast | [UserActionTaken](../annotations/UserActionTaken.md) |
| Leaving the stack | [ZoneTransfer](../annotations/ZoneTransfer.md) Stack to Exile |

Unlike [Flashback](Flashback.md), jump-start is not an alternative cost: the
card's own mana cost is paid. Every jump-start card uses ability `170`, and it
appears as the cast ability rather than as an alternative.

## Cast lifecycle

1. The card in the graveyard is offered as a `Cast` whose `abilityGrpId` is
   `170`, with no `alternativeGrpId`. Its `manaCost` entries are the card's
   mana cost, keyed by `170`.
2. When the client submits it, the card moves Graveyard to Stack with
   `CastSpell` under a new instance ID. The stack spell carries a persistent
   `CastingTimeOption` row of `type` `13` with `castAbilityGrpId` `170` and
   no `alternateCostGrpId`, and an `AbilityWordActive` row naming
   `Jump-start`.
3. The mana is paid. In the GSM after the cast, with no replacement effect
   changing where it goes, the discarded card gets a new
   instance ID and moves Hand to Graveyard with `category` `Discard`, and
   `UserActionTaken` records `actionType` `1` with `abilityGrpId` `170` and no
   `alternativeGrpId`.

## Resolution lifecycle

1. The spell's effect resolves.
2. The spell gets a new instance ID and moves Stack to Exile with `category`
   `Resolve`.

The card thus passes through three instance IDs: graveyard, stack and exile.
The discarded card has its own new graveyard ID.

## Sibling shapes

[Flashback](Flashback.md) and [Escape](Escape.md) also cast from the
graveyard, but through an alternative cost named in `alternativeGrpId` and
`alternateCostGrpId`. Jump-start pays the mana cost and adds a discard.

## Contract

- <a id="JumpStart.offer"></a>**JumpStart.offer.** *(provisional)* A
  jump-start offer from the graveyard `MUST` carry `abilityGrpId` `170` and
  no `alternativeGrpId`.
- <a id="JumpStart.cast-marker"></a>**JumpStart.cast-marker.** The stack
  spell `MUST` carry a `CastingTimeOption` row of `type` `13` with
  `castAbilityGrpId` `170`.
- <a id="JumpStart.discard"></a>**JumpStart.discard.** When the discarded card
  goes to the graveyard, it `MUST` move Hand to Graveyard with `category`
  `Discard`.
- <a id="JumpStart.accepted"></a>**JumpStart.accepted.** *(provisional)* An
  accepted jump-start cast `MUST` be recorded by `UserActionTaken` with
  `actionType` `1`, `abilityGrpId` `170` and no `alternativeGrpId`.
- <a id="JumpStart.resolve-exile"></a>**JumpStart.resolve-exile.** When a
  jump-start spell resolves, it `MUST` move Stack to Exile under a new
  instance ID.

## Open points

- Which request chooses the card to discard, and with which fields?
- How is the discard published when a replacement effect sends the card
  elsewhere, such as a madness card going to exile?
- What `category` does the move to exile carry when the spell is countered?
