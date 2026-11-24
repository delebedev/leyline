---
summary: "Action family overview: the two offer rails, how an offered action is submitted and accepted, and one row per action type."
read_when:
  - "offering, submitting or accepting an action"
  - "finding the page for an ActionType"
---
# Actions

Actions are the choices a seat can submit when the game waits for it: cast a
spell, play a land, activate an ability, take a special action, or pass. An
action carries the offered object, its costs, and the identities needed to
turn one legal choice into the next game state.

## Offer rails

| Rail | Envelope | Role |
|---|---|---|
| `GameStateMessage.actions` | `ActionInfo` with `seatId` and `action` | What either seat could currently do. Board state, independent of priority. |
| `ActionsAvailableReq.actions` | Bare `Action` | What the recipient may submit at this decision, including `Pass` and `FloatMana`. |

The rail rules are specified in
[messages](../stream/messages.md#action-rails). An action page describes the
`Action` fields both rails share.

## Submission and acceptance

The client submits one offered action in a `PerformActionResp`, correlated to
the `ActionsAvailableReq` it answers. When the server accepts the action, a
`UserActionTaken` annotation records it, with `affectorId` the acting seat
and `actionType` the integer of the accepted action type (see
[enums](../model/enums.md#action-types)). That integer is how an accepted
action is told apart from its siblings: an alternative-cost cast and a plain
cast both record `1`.

| Type | Role |
|---|---|
| [Cast](Cast.md) | Cast a spell, including alternative-cost casts. |
| `Activate` | Activate a non-mana ability. |
| `Play` | Play a land. |
| [Activate_Mana](Activate_Mana.md) | Activate a mana ability. |
| `Pass` | Pass priority. Request rail only. |
| [Special](Special.md) | Take a costed special action, such as foretelling or plotting a card. |
| [SpecialTurnFaceUp](Special_TurnFaceUp.md) | Turn a face-down permanent face up. |
| `CastLeft` | Cast the left half of a split card. |
| [CastRight](CastRight.md) | Cast the right half of a split card. |
| [CastAdventure](CastAdventure.md) | Cast the Adventure half of an Adventure card. |
| [CastOmen](CastOmen.md) | Cast the Omen half of an Omen card. |
| [CastMdfc](MDFC.md) | Cast the spell face of a modal double-faced card. |
| [PlayMdfc](MDFC.md) | Play the land face of a modal double-faced card. |
| `CastPrototype` | Cast the prototype face of a card. |
| [CastLeftRoom](Rooms.md), [CastRightRoom](Rooms.md) | Cast one door of a Room. |
| `FloatMana` | Float available mana. Request rail only. |
| `ResolutionCost`, `MakePayment`, `CombatCost`, `SpecialPayment`, `OpeningHandAction` | Sub-actions offered inside other decisions. |
