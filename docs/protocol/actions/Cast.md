---
summary: "Cast action: the base spell-cast offer; displayed mana cost, static versus payment-time reductions, additional-cost choices and sibling cast shapes."
entity: action/Cast
---
# Cast

## Meaning and object model

`Cast` offers to put a spell on the stack, from the hand or from another zone
an effect allows. `instanceId` is the card in its current zone. Submitting the
action moves the card to the Stack under a new instance ID
(`ObjectIdChanged`, then a `CastSpell` `ZoneTransfer`); resolution then moves
it to its destination as described in
[identity and zones](../model/identity-and-zones.md#instance-identity-over-time).

Face-selection siblings (`CastAdventure`, `CastOmen`, `CastMdfc`, Room doors)
and alternative-cost casts share this lifecycle and differ in their offer
fields; see Sibling shapes.

## Offer

| Field | Role |
|---|---|
| `actionType` | `Cast`. |
| `instanceId` | The card being cast. |
| `manaCost` | The displayed cost; see below. |
| `alternativeGrpId` | Present only on an alternative-cost cast: the cost's ability definition. |
| `alternativeSourceZcid` | On some alternative-cost casts, the object granting the permission. |

`manaCost` entries are `{color[], count}`, aggregated per color, normally
with the generic requirement first. A hybrid "two generic or one colored" symbol is a
single requirement carrying both `ManaColor_TwoGeneric` and the colored
value.

### Displayed cost

`manaCost` is the cost the client shows, not the printed cost. CR 601.2f
totals a spell's cost by applying additional costs and increases, then
reductions. The offer applies every modification that follows from game
state alone, and none that require the player to choose what pays:

- A static reduction, such as "costs {1} less for each instant and sorcery
  card in your graveyard", is already folded into `manaCost` and is
  recomputed every time the action rail is restated. It reduces only generic
  mana, and never below zero.
- A reduction chosen while paying — convoke, improvise, delve — is not
  applied to the offer; the reduction appears later in `PayCostsReq`, where
  the player chooses what pays.

The line is the choice, not whether the reduction reads game state: delve
and a graveyard-counting static reduction both read the graveyard and behave
oppositely.

Both rails normally agree. Where one card's `Cast` offer, with the same
`alternativeGrpId` or none, appears on the GSM rail and on
`ActionsAvailableReq` for the same state, the two `manaCost` arrays are
equal, including on frames where the other seat holds priority. Two cases
differ:

- A spell that costs less depending on its target, such as "costs {3} less
  if it targets a tapped creature", shows the reduction on
  `ActionsAvailableReq` and the unreduced cost on the GSM rail.
- A spell that may be cast with mana of any type shows an all-generic cost on
  the GSM rail and the colored cost on `ActionsAvailableReq`.

### Additional-cost choices

A spell whose additional cost is a choice — "as an additional cost, do X or
pay {N}" — is offered as one `Cast` whose `manaCost` leaves out the optional
`{N}`. The choice is a
`CastingTimeOptionsReq` with `ChooseOrCost`, sent after the cast is submitted,
in the state where the card moves to the stack.

## Lifecycle

1. The client submits the `Cast` action.
2. The next GSM moves the card to the Stack: `ObjectIdChanged`, then a
   `CastSpell` `ZoneTransfer`, then the targeting and payment decisions the
   spell needs.
3. When the cast completes, `UserActionTaken` records `actionType` `1` with
   `abilityGrpId` `0`.

## Sibling shapes

| Axis | `Cast` | Face casts (`CastAdventure`, `CastOmen`, `CastMdfc`) | Alternative-cost `Cast` |
|---|---|---|---|
| `actionType` | `Cast` | its own type | `Cast` |
| `alternativeGrpId` | absent | absent | present |
| `manaCost` | the main face's displayed cost | the chosen face's cost | the alternative cost |
| Persistent cast marker | none | none | `CastingTimeOption` |
| Accepted `actionType` | `1` | that face's type, for example `24` for `CastOmen` | `1` |

## Contract

- <a id="Cast.static-reduction"></a>**Cast.static-reduction.** A `Cast`
  offer's `manaCost` `MUST` include every cost reduction that follows from
  game state alone in the state that carries the offer.
- <a id="Cast.payment-reduction"></a>**Cast.payment-reduction.** A `Cast`
  offer's `manaCost` `MUST NOT` include a reduction that requires the player
  to choose permanents or cards while paying, such as convoke, improvise or
  delve.
- <a id="Cast.rails-agree"></a>**Cast.rails-agree.** For one card in one
  state, the `manaCost` of a `Cast` offer with the same `alternativeGrpId`, or
  none, `MUST` be equal on the GSM rail and on `ActionsAvailableReq`, unless
  the spell's cost depends on its target or the spell may be cast with mana
  of any type.
- <a id="Cast.choose-or-cost"></a>**Cast.choose-or-cost.** *(provisional)*
  A spell whose additional cost is a choice between doing something and
  paying mana `MUST` be offered as exactly one `Cast` whose `manaCost` does
  not include that optional mana, with the choice sent as a
  `CastingTimeOptionsReq` of type `ChooseOrCost` after submission.
- <a id="Cast.accepted"></a>**Cast.accepted.** An accepted `Cast`,
  including an alternative-cost `Cast`, `MUST` be recorded by
  `UserActionTaken` with `actionType` `1`.

## Open points

- How do combined reducers, affinity and several improvise sources show in
  `manaCost`?
- Are emerge, offering and assist reductions shown like convoke?
- How is a true alternative additional cost offered?
- When a static cost change also applies to a spell with a `ChooseOrCost`
  additional cost, does the offer's `manaCost` carry that change?
- Which spells list the generic requirement after the colored ones?
